// nativemorph - ECFG binary project settings (project.binary) decoder.
//
// Godot exports with the default "Convert Text Resources to Binary" option
// store the project settings as a compiled `project.binary` entry instead of
// a text `project.godot` (editor/export/editor_export_platform.cpp: the
// settings are saved via ProjectSettings::save_custom into "tmpproject.
// binary" and stored as res://project.binary). The runtime accepts either
// (ProjectSettings::_load_settings_text_or_binary, binary preferred), so a
// pack with ONLY project.binary is a perfectly runnable game - it just has no
// text entry to patch.
//
// This module closes that gap:
//   decode_ecfg()          parse the ECFG blob (magic "ECFG", count, then
//                          len-prefixed key + len-prefixed Variant pairs) and
//                          render every value as a Godot text literal
//   ecfg_to_text()         re-emit the decoded settings as a textual
//                          project.godot (ConfigFile syntax, config_version)
//   acquire_settings()     the one-call flow used by the JNI bridge, the CLI
//                          and the host test driver: find project.godot /
//                          project.binary, prefer text, convert binary when
//                          cleanly decodable, fail-safe to "keep the pack's
//                          own binary" otherwise.
//
// Variant binary layout (core/io/marshalls.cpp decode_variant):
//   u32 header: type in the low 8 bits; bit 16 = 64-bit data flag for
//   INT/FLOAT/maths; bits 16-19 carry container typing for typed
//   ARRAY/DICTIONARY (4.4+; unsupported here - fail-safe). Strings are
//   u32 length + UTF-8 bytes padded to 4. All integers little-endian.
//
// Type tables differ between engine majors (verified against
// core/variant/variant.h on 4.0-stable .. 4.7-stable and 3.6-stable):
//   4.x (identical 4.0-4.7): NIL=0 BOOL=1 INT=2 FLOAT=3 STRING=4 VECTOR2=5
//     VECTOR2I=6 RECT2=7 RECT2I=8 VECTOR3=9 VECTOR3I=10 TRANSFORM2D=11
//     VECTOR4=12 VECTOR4I=13 PLANE=14 QUATERNION=15 AABB=16 BASIS=17
//     TRANSFORM3D=18 PROJECTION=19 COLOR=20 STRING_NAME=21 NODE_PATH=22
//     RID=23 OBJECT=24 CALLABLE=25 SIGNAL=26 DICTIONARY=27 ARRAY=28
//     PACKED_BYTE_ARRAY=29 PACKED_INT32_ARRAY=30 PACKED_INT64_ARRAY=31
//     PACKED_FLOAT32_ARRAY=32 PACKED_FLOAT64_ARRAY=33 PACKED_STRING_ARRAY=34
//     PACKED_VECTOR2_ARRAY=35 PACKED_VECTOR3_ARRAY=36 PACKED_COLOR_ARRAY=37
//     (PACKED_VECTOR4_ARRAY=38, 4.3+)
//   3.x: NIL=0 BOOL=1 INT=2 REAL=3 STRING=4 VECTOR2=5 RECT2=6 VECTOR3=7
//     TRANSFORM2D=8 PLANE=9 QUAT=10 AABB=11 BASIS=12 TRANSFORM=13 COLOR=14
//     NODE_PATH=15 _RID=16 OBJECT=17 DICTIONARY=18 ARRAY=19 POOL_BYTE_ARRAY=20
//     POOL_INT_ARRAY=21 POOL_REAL_ARRAY=22 POOL_STRING_ARRAY=23
//     POOL_VECTOR2_ARRAY=24 POOL_VECTOR3_ARRAY=25 POOL_COLOR_ARRAY=26
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "nm_api.hpp"
#include "pck.hpp"

namespace nm {

namespace {

// ---- little-endian cursor with bounds checking ---------------------------
struct Reader {
  const uint8_t* p;
  size_t left;
  bool ok = true;
  explicit Reader(const uint8_t* d, size_t n) : p(d), left(n) {}
  void fail() { ok = false; }
  uint32_t u32() {
    if (left < 4) { ok = false; return 0; }
    const uint32_t v = uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
                       (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
    p += 4; left -= 4;
    return v;
  }
  uint64_t u64() {
    if (left < 8) { ok = false; return 0; }
    const uint64_t lo = u32();
    const uint64_t hi = u32();
    return lo | (hi << 32);
  }
  float f32() {
    const uint32_t b = u32();
    float f;
    std::memcpy(&f, &b, 4);
    return f;
  }
  double f64() {
    const uint64_t b = u64();
    double d;
    std::memcpy(&d, &b, 8);
    return d;
  }
  std::string str() {  // u32 length + bytes, padded to a 4-byte multiple
    const uint32_t n = u32();
    if (!ok) return {};
    const uint32_t pad = (4 - n % 4) % 4;
    if (size_t(n) + pad > left) { ok = false; return {}; }
    std::string s(reinterpret_cast<const char*>(p), n);
    p += n + pad; left -= n + pad;
    return s;
  }
  const uint8_t* take(size_t n) {
    if (n > left) { ok = false; return nullptr; }
    const uint8_t* q = p;
    p += n; left -= n;
    return q;
  }
};

std::string strip_trailing_nul(std::string s) {
  while (!s.empty() && s.back() == '\0') s.pop_back();
  return s;
}

// ConfigFile text value for a string: quoted, minimal escapes.
std::string quote(const std::string& raw) {
  std::string s = strip_trailing_nul(raw);
  std::string out = "\"";
  for (size_t i = 0; i < s.size(); i++) {
    const char c = s[i];
    switch (c) {
      case '"':  out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\t': out += "\\t"; break;
      case '\r': out += "\\r"; break;
      default:
        if (uint8_t(c) < 0x20) out += ' ';
        else out += c;  // UTF-8 passes through (the file is UTF-8)
    }
  }
  out += "\"";
  return out;
}

// Float literal that the text parser reads back as exactly this value.
// f32 needs 9 significant digits, f64 needs 17 (the engine writes f32 when
// lossless and f64 otherwise, so both widths occur in real files).
std::string fmt_float(double v, bool wide) {
  char buf[40];
  std::snprintf(buf, sizeof buf, wide ? "%.17g" : "%.9g", v);
  std::string s(buf);
  if (s.find('.') == std::string::npos && s.find('e') == std::string::npos &&
      s.find('n') == std::string::npos && s.find('i') == std::string::npos)
    s += ".0";
  return s;
}

std::string fmt_int(int64_t v) {
  char buf[32];
  std::snprintf(buf, sizeof buf, "%lld", static_cast<long long>(v));
  return buf;
}

void join(std::string& out, const std::vector<std::string>& parts,
          const char* sep) {
  for (size_t i = 0; i < parts.size(); i++) {
    if (i) out += sep;
    out += parts[i];
  }
}

std::string fmt_num(double v, bool wide) { return fmt_float(v, wide); }

// ---- variant -> text literal (recursive) ----------------------------------
// gen: 3 or 4 (engine major; selects the type table + literal names).
// Returns false on a type we do not render (caller treats as unsupported).
bool render_value(Reader& r, int gen, std::string& out, int depth) {
  if (depth > 16 || !r.ok) return false;
  const uint32_t header = r.u32();
  if (!r.ok) return false;
  const uint32_t type = header & 0xFF;
  const bool dbl = (header & (1u << 16)) != 0;  // 64-bit data flag
  const uint32_t kind = (header >> 16) & 0xF;   // container typing (4.4+)

  // 4.x and 3.x share 0..4; everything else is table-dependent.
  if (type <= 4) {
    switch (type) {
      case 0: return false;  // NIL: engine semantics = setting erased; skip
      case 1: out += r.u32() ? "true" : "false"; return r.ok;
      case 2: out += fmt_int(dbl ? int64_t(r.u64()) : int64_t(r.u32()));
              return r.ok;
      case 3: out += fmt_float(dbl ? r.f64() : double(r.f32()), dbl);
              return r.ok;
      case 4: out += quote(r.str()); return r.ok;
    }
  }
  if (gen == 4) {
    switch (type) {
      case 5: {  // VECTOR2
        const double x = dbl ? r.f64() : r.f32(), y = dbl ? r.f64() : r.f32();
        out += "Vector2(" + fmt_num(x, dbl) + ", " + fmt_num(y, dbl) + ")";
        return r.ok;
      }
      case 6: {  // VECTOR2I
        const int64_t x = r.u32(), y = r.u32();
        out += "Vector2i(" + fmt_int(x) + ", " + fmt_int(y) + ")";
        return r.ok;
      }
      case 7: case 8: {  // RECT2 / RECT2I
        std::vector<std::string> v;
        if (type == 7) {
          for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        } else {
          for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_int(r.u32()));
        }
        out += (type == 7 ? "Rect2(" : "Rect2i(");
        join(out, v, ", ");
        out += ")";
        return r.ok;
      }
      case 9: {  // VECTOR3
        std::vector<std::string> v;
        for (int i = 0; i < 3 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Vector3("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 10: {  // VECTOR3I
        std::vector<std::string> v;
        for (int i = 0; i < 3 && r.ok; i++) v.push_back(fmt_int(r.u32()));
        out += "Vector3i("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 12: {  // VECTOR4
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Vector4("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 13: {  // VECTOR4I
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_int(r.u32()));
        out += "Vector4i("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 14: {  // PLANE
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Plane("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 15: {  // QUATERNION
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Quaternion("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 20: {  // COLOR
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Color("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 21: {  // STRING_NAME
        out += "&" + quote(r.str());
        return r.ok;
      }
      case 22: {  // NODE_PATH (4.x layout; also valid for 3.x-style blobs
        // handled below, but 3.x packs never reach this branch)
        const uint32_t first = r.u32();
        if (first & 0x80000000u) {
          const uint32_t namecount = first & 0x7FFFFFFFu;
          uint32_t subnamecount = r.u32();
          const uint32_t np_flags = r.u32();
          if (np_flags & 2) subnamecount++;
          std::vector<std::string> names;
          for (uint32_t i = 0; i < namecount + subnamecount && r.ok; i++)
            names.push_back(r.str());
          if (!r.ok) return false;
          std::string path;
          for (size_t i = 0; i < names.size(); i++) {
            if (i) path += "/";
            path += names[i];
          }
          out += "NodePath(" + quote(path) + ")";
          return true;
        }
        // Old format: a single string.
        std::string s = strip_trailing_nul(r.str());
        if (!r.ok) return false;
        out += "NodePath(" + quote(s) + ")";
        return true;
      }
      case 27: {  // DICTIONARY
        if (kind) return false;  // typed dictionary (4.4+)
        uint32_t count = r.u32();
        if (!r.ok) return false;
        count &= 0x7FFFFFFFu;
        out += "{";
        for (uint32_t i = 0; i < count; i++) {
          std::string k, v;
          if (!render_value(r, gen, k, depth + 1) ||
              !render_value(r, gen, v, depth + 1))
            return false;
          if (i) out += ", ";
          out += k + ": " + v;
        }
        out += "}";
        return r.ok;
      }
      case 28: {  // ARRAY
        if (kind) return false;  // typed array (4.4+)
        uint32_t count = r.u32();
        if (!r.ok) return false;
        count &= 0x7FFFFFFFu;
        out += "[";
        for (uint32_t i = 0; i < count; i++) {
          std::string v;
          if (!render_value(r, gen, v, depth + 1)) return false;
          if (i) out += ", ";
          out += v;
        }
        out += "]";
        return r.ok;
      }
      case 29: {  // PACKED_BYTE_ARRAY
        const uint32_t n = r.u32();
        if (!r.ok || !r.take(n + (4 - n % 4) % 4)) return false;
        out += "PackedByteArray()";
        return true;  // contents not needed for settings semantics
      }
      case 30: case 31: {  // PACKED_INT32 / INT64 ARRAY
        const uint32_t n = r.u32();
        const size_t w = type == 30 ? 4 : 8;
        if (!r.ok || !r.take(size_t(n) * w)) return false;
        out += type == 30 ? "PackedInt32Array()" : "PackedInt64Array()";
        return true;
      }
      case 32: case 33: {  // PACKED FLOAT32 / FLOAT64 ARRAY
        const uint32_t n = r.u32();
        const size_t w = type == 32 ? 4 : 8;
        if (!r.ok || !r.take(size_t(n) * w)) return false;
        out += type == 32 ? "PackedFloat32Array()" : "PackedFloat64Array()";
        return true;
      }
      case 34: {  // PACKED_STRING_ARRAY
        const uint32_t n = r.u32();
        if (!r.ok) return false;
        out += "PackedStringArray(";
        for (uint32_t i = 0; i < n && r.ok; i++) {
          if (i) out += ", ";
          out += quote(r.str());
        }
        out += ")";
        return r.ok;
      }
      case 35: case 36: case 37: case 38: {  // PACKED VECTOR/COLOR ARRAYS
        const size_t per = type == 35 ? 2 : type == 36 ? 3 : 4;
        const uint32_t n = r.u32();
        if (!r.ok || !r.take(size_t(n) * per * (dbl ? 8 : 4))) return false;
        out += type == 35 ? "PackedVector2Array()"
             : type == 36 ? "PackedVector3Array()"
             : type == 37 ? "PackedColorArray()" : "PackedVector4Array()";
        return true;
      }
      default:
        return false;  // TRANSFORM2D/BASIS/... : not settings material
    }
  } else {
    // ---- 3.x table ----
    switch (type) {
      case 5: {  // VECTOR2
        const float x = r.f32(), y = r.f32();
        out += "Vector2(" + fmt_num(x, dbl) + ", " + fmt_num(y, dbl) + ")";
        return r.ok;
      }
      case 6: {  // RECT2
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Rect2("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 7: {  // VECTOR3
        std::vector<std::string> v;
        for (int i = 0; i < 3 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Vector3("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 9: {  // PLANE
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Plane("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 10: {  // QUAT
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Quat("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 14: {  // COLOR
        std::vector<std::string> v;
        for (int i = 0; i < 4 && r.ok; i++) v.push_back(fmt_num(dbl ? r.f64() : r.f32(), dbl));
        out += "Color("; join(out, v, ", "); out += ")";
        return r.ok;
      }
      case 18: {  // DICTIONARY
        uint32_t count = r.u32();
        if (!r.ok) return false;
        count &= 0x7FFFFFFFu;
        out += "{";
        for (uint32_t i = 0; i < count; i++) {
          std::string k, v;
          if (!render_value(r, gen, k, depth + 1) ||
              !render_value(r, gen, v, depth + 1))
            return false;
          if (i) out += ", ";
          out += k + ": " + v;
        }
        out += "}";
        return r.ok;
      }
      case 19: {  // ARRAY
        uint32_t count = r.u32();
        if (!r.ok) return false;
        count &= 0x7FFFFFFFu;
        out += "[";
        for (uint32_t i = 0; i < count; i++) {
          std::string v;
          if (!render_value(r, gen, v, depth + 1)) return false;
          if (i) out += ", ";
          out += v;
        }
        out += "]";
        return r.ok;
      }
      case 20: {  // POOL_BYTE_ARRAY
        const uint32_t n = r.u32();
        if (!r.ok || !r.take(n + (4 - n % 4) % 4)) return false;
        out += "PoolByteArray()";
        return true;
      }
      case 21: case 22: {  // POOL_INT_ARRAY / POOL_REAL_ARRAY
        const uint32_t n = r.u32();
        if (!r.ok || !r.take(size_t(n) * 4)) return false;
        out += type == 21 ? "PoolIntArray()" : "PoolRealArray()";
        return true;
      }
      case 23: {  // POOL_STRING_ARRAY
        const uint32_t n = r.u32();
        if (!r.ok) return false;
        out += "PoolStringArray(";
        for (uint32_t i = 0; i < n && r.ok; i++) {
          if (i) out += ", ";
          out += quote(r.str());
        }
        out += ")";
        return r.ok;
      }
      case 24: case 25: case 26: {  // POOL VECTOR2/3/COLOR ARRAYS
        const size_t per = type == 24 ? 2 : type == 25 ? 3 : 4;
        const uint32_t n = r.u32();
        if (!r.ok || !r.take(size_t(n) * per * 4)) return false;
        out += type == 24 ? "PoolVector2Array()"
             : type == 25 ? "PoolVector3Array()" : "PoolColorArray()";
        return true;
      }
      default:
        return false;  // NODE_PATH/_RID/OBJECT/TRANSFORM...: not rendered
    }
  }
}

}  // namespace

// ================= decode_ecfg =================
EcfgStatus decode_ecfg(const uint8_t* data, size_t len, int engine_major,
                       EcfgDoc& out, std::string& err) {
  out = EcfgDoc{};
  if (len < 8 || std::memcmp(data, "ECFG", 4) != 0) {
    err = "bad project.binary magic (expected ECFG)";
    return EcfgStatus::Failed;
  }
  Reader top(data + 4, len - 4);
  const uint32_t count = top.u32();
  if (!top.ok || count > 100000) {
    err = "implausible ECFG entry count";
    return EcfgStatus::Failed;
  }
  bool any_failed = false;
  for (uint32_t i = 0; i < count && top.ok; i++) {
    // Keys are a u32 length + raw bytes with NO padding (the engine reads
    // them with get_buffer(slen); only Variant-internal strings are padded).
    const uint32_t klen = top.u32();
    if (!top.ok || klen > top.left) {
      err = "truncated ECFG key at entry " + std::to_string(i);
      return EcfgStatus::Failed;
    }
    std::string key(reinterpret_cast<const char*>(top.p), klen);
    top.p += klen;
    top.left -= klen;

    const uint32_t vlen = top.u32();
    if (!top.ok) { err = "truncated ECFG entry " + std::to_string(i); return EcfgStatus::Failed; }
    if (vlen > top.left) { err = "value length overruns blob at " + key; return EcfgStatus::Failed; }

    Reader vr(top.p, vlen);
    std::string rendered;
    const bool rendered_ok = render_value(vr, engine_major, rendered, 0) && vr.ok;
    if (rendered_ok && vr.left != 0) {
      // decode did not consume exactly the framed value: treat as unsupported
      any_failed = true;
    } else if (rendered_ok) {
      out.entries.push_back({key, rendered});
      if (key == "application/config/name" && rendered.size() >= 2 &&
          rendered.front() == '"') {
        // un-escape the literal (" \\ \n \t \r) for the display name
        std::string raw = strip_trailing_nul(rendered.substr(1, rendered.size() - 2));
        std::string un;
        for (size_t c = 0; c < raw.size(); c++) {
          if (raw[c] == '\\' && c + 1 < raw.size()) {
            const char n = raw[++c];
            if (n == 'n') un += '\n';
            else if (n == 't') un += '\t';
            else if (n == 'r') un += '\r';
            else un += n;  // \" \\ and anything else
          } else {
            un += raw[c];
          }
        }
        out.name = un;
      }
      if (key == "application/config/icon" && rendered.size() >= 2 &&
          rendered.front() == '"') {
        std::string icon = strip_trailing_nul(rendered.substr(1, rendered.size() - 2));
        if (icon.rfind("res://", 0) == 0) icon = icon.substr(6);
        out.icon = icon;
      }
    } else {
      any_failed = true;  // unsupported type in this entry; skip it
    }
    top.p += vlen;
    top.left -= vlen;
  }
  if (!top.ok) {
    err = "ECFG framing broke mid-entry";
    return EcfgStatus::Failed;
  }
  return any_failed ? EcfgStatus::Partial : EcfgStatus::Ok;
}

// ================= ecfg_to_text =================
std::string ecfg_to_text(const EcfgDoc& doc, int engine_major) {
  std::ostringstream oss;
  // CONFIG_VERSION: 5 in 4.x, 4 in 3.x (core/config/project_settings.cpp).
  oss << "config_version=" << (engine_major == 3 ? 4 : 5) << "\n";

  std::vector<const EcfgEntry*> top_level;
  std::vector<std::string> section_order;
  std::map<std::string, std::vector<const EcfgEntry*>> sections;
  for (const auto& e : doc.entries) {
    const size_t slash = e.key.find('/');
    if (slash == std::string::npos || slash == 0) {
      top_level.push_back(&e);
      continue;
    }
    const std::string sec = e.key.substr(0, slash);
    if (!sections.count(sec)) section_order.push_back(sec);
    sections[sec].push_back(&e);
  }
  for (const auto* e : top_level) oss << e->key << "=" << e->text << "\n";
  for (const std::string& sec : section_order) {
    oss << "\n[" << sec << "]\n";
    for (const auto* e : sections[sec]) {
      // ConfigFile: section is the FIRST component, the key is the ENTIRE
      // remainder (keys contain slashes).
      oss << e->key.substr(sec.size() + 1) << "=" << e->text << "\n";
    }
  }
  return oss.str();
}

// ================= acquire_settings =================
// One call shared by the JNI bridge, the desktop CLI and the host test
// driver: locate the settings entry, prefer text, convert a cleanly
// decodable project.binary, fail safe otherwise.
SettingsResult acquire_settings(const std::vector<uint8_t>& pack_bytes,
                                const PckFile& p) {
  SettingsResult res;
  const PckEntry* pg = nullptr;
  const PckEntry* pb = nullptr;
  for (const auto& e : p.entries) {
    if (e.path == "project.godot" && !pg) pg = &e;
    else if (e.path == "project.binary" && !pb) pb = &e;
  }
  if (!pg && !pb) {
    res.found = false;
    return res;
  }
  res.found = true;

  if (pg) {
    auto raw = read_entry(pack_bytes, p, *pg);
    res.text.assign(raw.begin(), raw.end());
  }

  if (pb) {
    // The runtime PREFERS project.binary and only falls back to the text
    // entry, so a binary blob must either be fully converted (and removed
    // from the pack) or left completely untouched - a patched text entry
    // would silently lose to it.
    auto raw = read_entry(pack_bytes, p, *pb);
    EcfgDoc doc;
    std::string err;
    const EcfgStatus st =
        decode_ecfg(raw.data(), raw.size(), int(p.ver_major), doc, err);
    res.binary = true;
    res.name = doc.name;
    res.icon = doc.icon;
    if (st == EcfgStatus::Ok) {
      res.text = ecfg_to_text(doc, int(p.ver_major));
      res.converted = true;
      res.remove_binary = true;
    } else {
      res.converted = false;
      res.remove_binary = false;
      // The engine reads project.binary in preference to any text entry, so
      // patching the text would be a no-op: leave settings unpatched.
      res.text.clear();
      res.kept_reason =
          st == EcfgStatus::Partial
              ? "project.binary uses variant types this build cannot fully "
                "convert (" + err + "); keeping the compiled settings as-is"
              : "project.binary could not be parsed (" + err +
                "); keeping the compiled settings as-is";
    }
  }
  return res;
}

}  // namespace nm
