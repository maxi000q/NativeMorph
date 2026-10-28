// nativemorph - PCK reader/validator/repacker + embedded-pack locator.
//
// Data files only: this module never interprets code, never extracts sources.
// It reads a Godot resource pack (formats 1/2/3/4), lets the caller patch
// text entries (project.godot), and re-emits a container whose untouched
// payloads are byte-identical to the input.
//
// Field layout mirrors the engine sources (verified line by line):
//   Godot 4.7: core/io/file_access_pack.cpp  (formats 2/3/4)
//              editor/export/editor_export_platform.cpp (writer)
//   Godot 3.5.3/3.6.2: core/io/file_access_pack.cpp (format 1)
// Every read is validated; any inconsistency aborts with a diagnostic before
// a single output byte is written.

#include "pck.hpp"

#include <zip.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace nm {

namespace {

// ---------- byte readers (all container integers are LITTLE-ENDIAN) ------
uint32_t rd_le32(const uint8_t* p) {
  return uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
         (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
uint64_t rd_le64(const uint8_t* p) {
  return uint64_t(rd_le32(p)) | (uint64_t(rd_le32(p + 4)) << 32);
}
void wr_le32(std::vector<uint8_t>& out, uint32_t v) {
  out.push_back(uint8_t(v)); out.push_back(uint8_t(v >> 8));
  out.push_back(uint8_t(v >> 16)); out.push_back(uint8_t(v >> 24));
}
void wr_le64(std::vector<uint8_t>& out, uint64_t v) {
  wr_le32(out, uint32_t(v));
  wr_le32(out, uint32_t(v >> 32));
}

// ---------- whole-file buffer ----------
std::vector<uint8_t> read_file(const std::string& path) {
  std::ifstream f(path, std::ios::binary);
  if (!f) throw std::runtime_error("cannot open: " + path);
  f.seekg(0, std::ios::end);
  std::streamoff n = f.tellg();
  if (n < 0) throw std::runtime_error("cannot size: " + path);
  f.seekg(0, std::ios::beg);
  std::vector<uint8_t> buf;
  buf.resize(size_t(n));
  if (n > 0) f.read(reinterpret_cast<char*>(buf.data()), n);
  return buf;
}

// ---------- MD5 (RFC 1321; K-table computed from the definition
// K[i] = floor(|sin(i+1)| * 2^32), so no magic constants are transcribed) ----------
struct MD5 {
  uint32_t a = 0x67452301u, b = 0xefcdab89u, c = 0x98badcfeu, d = 0x10325476u;
  static uint32_t rotl(uint32_t x, int s) { return (x << s) | (x >> (32 - s)); }
  void block(const uint8_t* p) {
    static uint32_t K[64], S[64];
    static const uint32_t S0[4] = {7, 12, 17, 22};
    static const uint32_t S1[4] = {5, 9, 14, 20};
    static const uint32_t S2[4] = {4, 11, 16, 23};
    static const uint32_t S3[4] = {6, 10, 15, 21};
    static bool init = false;
    if (!init) {
      for (int i = 0; i < 64; i++) {
        K[i] = uint32_t(4294967296.0 * std::fabs(std::sin(double(i + 1))));
        S[i] = (i / 16 == 0) ? S0[i % 4]
              : (i / 16 == 1) ? S1[i % 4]
              : (i / 16 == 2) ? S2[i % 4]
                              : S3[i % 4];
      }
      init = true;
    }
    uint32_t M[16];
    for (int i = 0; i < 16; i++)
      M[i] = uint32_t(p[i * 4]) | (uint32_t(p[i * 4 + 1]) << 8) |
             (uint32_t(p[i * 4 + 2]) << 16) | (uint32_t(p[i * 4 + 3]) << 24);
    uint32_t A = a, B = b, C = c, D = d;
    for (int i = 0; i < 64; i++) {
      uint32_t F; int g;
      if (i < 16)      { F = (B & C) | (~B & D);      g = i; }
      else if (i < 32) { F = (D & B) | (~D & C);      g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D;               g = (3 * i + 5) % 16; }
      else             { F = C ^ (B | ~D);            g = (7 * i) % 16; }
      F = F + A + K[i] + M[g];
      A = D; D = C; C = B;
      B = B + rotl(F, int(S[i]));
    }
    a += A; b += B; c += C; d += D;
  }
  std::vector<uint8_t> finish(const uint8_t* data, size_t len) {
    std::vector<uint8_t> msg(data, data + len);
    uint64_t bits = uint64_t(len) * 8;
    msg.push_back(0x80);
    while (msg.size() % 64 != 56) msg.push_back(0x00);
    for (int i = 0; i < 8; i++) msg.push_back(uint8_t(bits >> (8 * i)));  // LE length
    for (size_t off = 0; off < msg.size(); off += 64) block(msg.data() + off);
    std::vector<uint8_t> out(16);
    for (int i = 0; i < 4; i++) {
      out[0 + i] = uint8_t(a >> (8 * i));
      out[4 + i] = uint8_t(b >> (8 * i));
      out[8 + i] = uint8_t(c >> (8 * i));
      out[12 + i] = uint8_t(d >> (8 * i));
    }
    return out;
  }
};

void md5_of(const uint8_t* data, size_t len, uint8_t out[16]) {
  auto digest = MD5().finish(data, len);
  std::memcpy(out, digest.data(), 16);
}

// ---------- input normalization ----------
bool has_suffix_ci(const std::string& s, const std::string& suf) {
  if (s.size() < suf.size()) return false;
  auto eq = [](char a, char b) {
    return std::tolower(uint8_t(a)) == std::tolower(uint8_t(b));
  };
  return std::equal(suf.rbegin(), suf.rend(), s.rbegin() + 0, eq);
}

// Zip local-file-header magic.
bool is_zip(const std::vector<uint8_t>& buf) {
  return buf.size() >= 4 && buf[0] == 'P' && buf[1] == 'K' &&
         ((buf[2] == 3 && buf[3] == 4) || (buf[2] == 5 && buf[3] == 6) ||
          (buf[2] == 7 && buf[3] == 8));
}

std::string extract_from_zip(const std::string& zip_path) {
  int err = 0;
  zip_t* z = zip_open(zip_path.c_str(), ZIP_RDONLY, &err);
  if (!z) throw std::runtime_error("cannot open zip: " + zip_path);
  std::string best;
  zip_int64_t total = zip_get_num_entries(z, 0);
  for (zip_int64_t i = 0; i < total; i++) {
    const char* name = zip_get_name(z, zip_uint64_t(i), ZIP_FL_ENC_GUESS);
    if (!name) continue;
    std::string nm(name);
    const std::string leaf = nm.substr(nm.find_last_of('/') + 1);
    if (!has_suffix_ci(leaf, ".pck")) continue;
    // Prefer a top-level pack; among equals prefer the largest name (main
    // pack of a multi-pck bundle).
    const size_t depth = std::count(nm.begin(), nm.end(), '/');
    if (best.empty() || depth < 2) {
      if (best.empty() || nm.size() > best.size()) best = nm;
    }
  }
  if (best.empty()) {
    zip_close(z);
    throw std::runtime_error("no .pck found inside the zip bundle");
  }
  zip_int64_t idx = zip_name_locate(z, best.c_str(), ZIP_FL_NOCASE);
  zip_file* f = zip_fopen_index(z, zip_uint64_t(idx), 0);
  if (!f) { zip_close(z); throw std::runtime_error("cannot read pack entry " + best); }
  std::string tmp = zip_path + ".extracted.pck";
  std::ofstream out(tmp, std::ios::binary);
  std::vector<char> chunk(1 << 16);
  zip_int64_t r;
  while ((r = zip_fread(f, chunk.data(), chunk.size())) > 0)
    out.write(chunk.data(), r);
  zip_fclose(f); zip_close(z);
  return tmp;
}

// A Godot self-contained .exe appends the pack, then a small trailer:
// ...payload... [u64 pack_size][u32 'GDPC'].
// (Same trailer in 3.x and 4.x; see file_access_pack.cpp end-of-file scan.)
struct Embedded { uint64_t start = 0; bool found = false; };
Embedded find_embedded_pck(const std::vector<uint8_t>& buf) {
  Embedded e;
  const size_t n = buf.size();
  if (n < 4 + 8 + 4) return e;
  const uint8_t* tail = buf.data() + n - 4;
  if (std::memcmp(tail, "GDPC", 4) != 0) return e;
  uint64_t sz = rd_le64(buf.data() + n - 12);
  if (sz > n - 12) return e;
  uint64_t st = uint64_t(n) - 12 - sz;
  if (rd_le32(buf.data() + st) == PCK_MAGIC) {
    e.start = st; e.found = true;
  }
  return e;
}

// Strip trailing NUL padding (4.4+ exporters pad paths to 4 bytes) and the
// optional res:// prefix; keep the clean relative path.
std::string clean_path(const char* p, size_t n) {
  size_t len = n;
  while (len > 0 && p[len - 1] == 0) len--;
  std::string s(p, len);
  if (s.rfind("res://", 0) == 0) s = s.substr(6);
  return s;
}

}  // namespace

// ================= load =================
// Accepts .pck directly, .zip bundles (extracts the pack), and self-contained
// .exe (locates the embedded pack). All entry offsets end up ABSOLUTE in the
// file the caller reads (the .mapped_path).
void load_pck(PckFile& p, const std::string& input_path) {
  p.source_path = input_path;
  p.mapped_path = input_path;

  std::vector<uint8_t> buf = read_file(p.mapped_path);
  if (is_zip(buf))
    p.mapped_path = extract_from_zip(p.mapped_path),
    buf = read_file(p.mapped_path);

  uint64_t base = 0;
  if (rd_le32(buf.data()) != PCK_MAGIC) {
    Embedded e = find_embedded_pck(buf);
    if (!e.found) {
      if (has_suffix_ci(input_path, ".exe"))
        throw std::runtime_error(
            "this .exe does not contain the game data (no embedded pack). "
            "It is just the Windows launcher: pick the .pck file that sits "
            "next to it in the game folder instead");
      throw std::runtime_error(
          "not a Godot game pack (no GDPC container found). Supported inputs: "
          ".pck / self-contained .exe / .zip bundle / Godot .apk export");
    }
    base = e.start;
  }

  const uint8_t* h = buf.data() + base;
  const size_t bsize = buf.size() - base;
  if (bsize < 20) throw std::runtime_error("truncated pack header");
  if (rd_le32(h + 0) != PCK_MAGIC)
    throw std::runtime_error("bad magic (expected GDPC)");
  p.format = rd_le32(h + 4);
  if (p.format < 1 || p.format > 4)
    throw std::runtime_error("unsupported PCK format " + std::to_string(p.format) +
                             " (" + format_label(p.format) + ")");
  p.ver_major = rd_le32(h + 8);
  p.ver_minor = rd_le32(h + 12);
  p.ver_patch = rd_le32(h + 16);

  uint64_t dir_off = 0;  // absolute offset of file_count in the input file

  if (p.format == 1) {
    // Godot 3.x: 16 reserved words then the directory. No pack_flags, no
    // file_base. Entry offsets are ABSOLUTE in the host file: the 3.x
    // exporter records positions while writing (into the exe when
    // embedding), and the reader adds only p_offset (0 for direct opens).
    // 3.6.2 writer also pads paths to 4 bytes; readers tolerate unpadded.
    if (bsize < 84) throw std::runtime_error("truncated v1 header");
    p.pack_flags = 0;
    p.file_base = 0;
    dir_off = base + 84;
  } else {
    if (bsize < 104) throw std::runtime_error("truncated v2+ header");
    p.pack_flags = rd_le32(h + 20);
    const uint64_t stored_file_base = rd_le64(h + 24);
    if (p.pack_flags & packflag::DIR_ENCRYPTED)
      throw std::runtime_error(
          "the pack directory is AES-encrypted (exported with a script "
          "encryption key); this input cannot be imported");
    if (p.pack_flags & packflag::SPARSE_BUNDLE)
      throw std::runtime_error(
          "sparse bundle packs (DLC-style) are not supported for import");
    // file_base: relative to the pack start for v3/v4 (always) and for v2
    // when the REL_FILEBASE flag is set; otherwise absolute in the file.
    p.file_base = (p.format >= 3 || (p.pack_flags & packflag::REL_FILEBASE))
                      ? base + stored_file_base
                      : stored_file_base;
    if (p.format >= 3) {
      dir_off = base + rd_le64(h + 32);  // dir_offset is pack-start-relative
    } else {
      dir_off = base + 96;  // v2: directory right after the 16 reserved words
    }
  }

  if (dir_off + 4 > buf.size())
    throw std::runtime_error("directory offset points past EOF");
  uint32_t count = rd_le32(buf.data() + dir_off);
  if (count == 0 || count > 5'000'000)
    throw std::runtime_error("implausible file_count " + std::to_string(count));

  size_t off = dir_off + 4;
  p.entries.reserve(count);
  for (uint32_t i = 0; i < count; i++) {
    if (off + 4 > buf.size()) throw std::runtime_error("directory truncated");
    uint32_t plen = rd_le32(buf.data() + off); off += 4;
    if (plen == 0 || plen > 4096 || off + plen + 32 > buf.size())
      throw std::runtime_error("implausible path length at entry " + std::to_string(i));
    PckEntry e;
    {
      const char* rp = reinterpret_cast<const char*>(buf.data() + off);
      // Paths are UTF-8: bytes >= 0x80 are multibyte sequences (CJK,
      // accents, emoji...) and perfectly legal. Only C0 controls (and DEL)
      // indicate a misparse; NUL is tolerated as 4.4+ path padding.
      for (uint32_t k = 0; k < plen; k++) {
        const uint8_t ch = uint8_t(rp[k]);
        if ((ch < 0x20 && ch != 0) || ch == 0x7f)
          throw std::runtime_error("non-printable path in directory");
      }
      e.path = clean_path(rp, plen);
      if (e.path.empty()) throw std::runtime_error("empty path in directory");
    }
    off += plen;
    const uint64_t rel = rd_le64(buf.data() + off); off += 8;
    e.size = rd_le64(buf.data() + off); off += 8;
    std::memcpy(e.md5, buf.data() + off, 16); off += 16;
    if (p.format >= 2) {
      e.flags = rd_le32(buf.data() + off); off += 4;
    }
    e.offset = p.file_base + rel;  // absolute in the input file
    if (e.flags & pckflag::REMOVAL) continue;  // removal record, no payload
    if (e.offset + e.size > uint64_t(buf.size()))
      throw std::runtime_error("entry '" + e.path + "' spans past EOF");
    p.entries.push_back(std::move(e));
  }
}

// ================= read one entry =================
// No compression exists in PCK formats 1-4 (engine readers have no
// decompression step); this is a byte-exact slice.
std::vector<uint8_t> read_entry(const std::vector<uint8_t>& pack_bytes,
                                const PckFile& p, const PckEntry& e) {
  (void)p;
  const uint8_t* src = pack_bytes.data() + e.offset;
  return std::vector<uint8_t>(src, src + e.size);
}

namespace {

// The single entry writer used for formats 2/3/4: path padded to a 4-byte
// multiple (matching the 4.4+ exporters; older readers tolerate the padding
// because they stop at the NUL).
void write_entry_v2plus(std::vector<uint8_t>& out, const std::string& path,
                        uint64_t rel_offset, uint64_t size, const uint8_t* md5,
                        uint32_t flags) {
  const uint32_t pad = (4 - (path.size() % 4)) % 4;
  wr_le32(out, uint32_t(path.size() + pad));
  out.insert(out.end(), path.begin(), path.end());
  out.insert(out.end(), size_t(pad), 0);
  wr_le64(out, rel_offset);
  wr_le64(out, size);
  out.insert(out.end(), md5, md5 + 16);
  wr_le32(out, flags);
}

// v1 entry: same path padding as the 3.6.2 exporter (readers tolerate
// unpadded as well), NO flags field, offsets absolute in the output file.
void write_entry_v1(std::vector<uint8_t>& out, const std::string& path,
                    uint64_t abs_offset, uint64_t size, const uint8_t* md5) {
  const uint32_t pad = (4 - (path.size() % 4)) % 4;
  wr_le32(out, uint32_t(path.size() + pad));
  out.insert(out.end(), path.begin(), path.end());
  out.insert(out.end(), size_t(pad), 0);
  wr_le64(out, abs_offset);
  wr_le64(out, size);
  out.insert(out.end(), md5, md5 + 16);
}

}  // namespace

// ================= repack =================
// Rebuilds the container as a STANDALONE pack (base 0) with optional settings
// surgery:
//   - replacement_settings non-empty: the project.godot entry gets this
//     payload (a fresh MD5 is computed); when no such entry exists it is
//     APPENDED (the project.binary-only pack case).
//   - remove_path non-empty: matching entries are dropped entirely (the
//     converted project.binary case - the runtime prefers the binary entry,
//     so it must be removed for the patched text to take effect).
// Untouched entries are copied BYTE-EXACT (payloads, md5s, sizes carried over
// verbatim).
//
// Output layout mirrors the engine writer for each format:
//   v1:   header(84) + count + entries(unpadded, ABSOLUTE offsets) + payload
//   v2:   header(100: flags, file_base, 16 reserved, count) + entries +
//         payload  (entry offsets relative to file_base; REL flag set)
//   v3/4: header(104, aligned to 16) + payload + directory
//         (file_base + dir_offset in the header, both pack-start-relative)
void repack(const std::vector<uint8_t>& pack_bytes, const PckFile& p,
            const std::vector<uint8_t>& replacement_settings,
            const std::string& remove_path, const std::string& out_path) {
  const bool replacing = !replacement_settings.empty();

  // ---- output entry list (drop removed; mark replaced; note append) ----
  struct Out {
    const PckEntry* src;
    bool replaced;
  };
  std::vector<Out> outs;
  bool have_settings_entry = false;
  for (const PckEntry& e : p.entries) {
    if (!remove_path.empty() && e.path == remove_path) continue;  // dropped
    bool rep = replacing && e.path == "project.godot";
    if (e.path == "project.godot") have_settings_entry = true;
    outs.push_back({&e, rep});
  }
  const bool append_settings = replacing && !have_settings_entry;
  const size_t n = outs.size() + (append_settings ? 1 : 0);

  // ---------- header ----------
  std::vector<uint8_t> head;
  head.reserve(1 << 16);
  const uint32_t out_flags =
      p.format >= 2 ? (p.pack_flags | packflag::REL_FILEBASE) : 0;

  wr_le32(head, PCK_MAGIC);
  wr_le32(head, p.format);
  wr_le32(head, p.ver_major);
  wr_le32(head, p.ver_minor);
  wr_le32(head, p.ver_patch);
  if (p.format == 1) {
    for (int i = 0; i < 16; i++) wr_le32(head, 0);  // reserved
  } else if (p.format == 2) {
    wr_le32(head, out_flags);
    wr_le64(head, 0);  // file_base placeholder @24
    for (int i = 0; i < 16; i++) wr_le32(head, 0);  // reserved
  } else {
    wr_le32(head, out_flags);
    wr_le64(head, 0);  // file_base placeholder @24
    wr_le64(head, 0);  // dir_offset placeholder @32
    for (int i = 0; i < 16; i++) wr_le32(head, 0);  // reserved/salt region
    while (head.size() % 16) head.push_back(0);  // 104 -> 112
  }
  if (p.format <= 2)
    wr_le32(head, uint32_t(n));  // file_count (v1/v2: right after header)

  // ---------- directory size (v1/v2: payload follows the directory) ------
  size_t dir_size = 0;
  if (p.format <= 2) {
    auto dir_entry_size = [&](const std::string& path) {
      // v1 stores res://-prefixed paths; v2+ store clean paths. Both pad to 4.
      const size_t plen = path.size() + (p.format == 1 ? 6 : 0);
      const size_t padded = (plen + 3) & ~size_t(3);
      return 4 + padded + 8 + 8 + 16 + (p.format == 1 ? 0 : 4);
    };
    for (const Out& o : outs) dir_size += dir_entry_size(o.src->path);
    if (append_settings) dir_size += dir_entry_size("project.godot");
  } else {
    dir_size = 4;  // v3/v4: the count is stored at dir_offset, before entries
  }
  // v1/v2: payload follows header+directory; v3/v4: payload starts right
  // after the header (file_base) and the directory comes after the body.
  const uint64_t payload_base =
      p.format <= 2 ? uint64_t(head.size() + dir_size) : uint64_t(head.size());

  // ---------- payloads (byte-exact / replacement / appended) ------------
  std::vector<uint8_t> body;
  body.reserve(1 << 22);
  std::vector<uint64_t> abs_off(n, 0);
  {
    size_t i = 0;
    auto emit = [&](bool replaced) {
      abs_off[i] = payload_base + body.size();
      if (replaced) {
        body.insert(body.end(), replacement_settings.begin(),
                    replacement_settings.end());
      } else {
        const PckEntry& e = *outs[i].src;
        const uint8_t* src = pack_bytes.data() + e.offset;
        body.insert(body.end(), src, src + e.size);
      }
      if (p.format >= 3)  // writer convention: 16-byte payload alignment
        while (body.size() % 16) body.push_back(0);
      i++;
    };
    for (size_t k = 0; k < outs.size(); k++) emit(outs[k].replaced);
    if (append_settings) emit(true);
  }

  // ---------- directory ----------------------------------------------------
  std::vector<uint8_t> dir;
  dir.reserve(1 << 18);
  if (p.format >= 3) wr_le32(dir, uint32_t(n));  // count lives at dir_offset
  uint8_t fresh[16];
  if (replacing)
    md5_of(replacement_settings.data(), replacement_settings.size(), fresh);
  {
    size_t i = 0;
    auto write_dir = [&](const std::string& path, uint64_t sz,
                         const uint8_t* md5, uint32_t eflags) {
      if (p.format == 1) {
        // 3.x lookups key on the raw stored string; the exporter stores
        // res://-prefixed paths (4.x stores clean paths - its reader strips
        // the prefix itself).
        write_entry_v1(dir, "res://" + path, abs_off[i], sz, md5);
      } else {
        // entry offsets are relative to file_base for 2/3/4
        write_entry_v2plus(dir, path, abs_off[i] - payload_base, sz, md5,
                           eflags);
      }
      i++;
    };
    for (const Out& o : outs) {
      const uint64_t sz = o.replaced ? uint64_t(replacement_settings.size())
                                     : o.src->size;
      write_dir(o.src->path, sz, o.replaced ? fresh : o.src->md5,
                o.replaced ? 0 : o.src->flags);
    }
    if (append_settings)
      write_dir("project.godot", uint64_t(replacement_settings.size()),
                fresh, 0);
  }

  // ---------- header fixups -------------------------------------------------
  if (p.format == 2) {
    for (int b = 0; b < 8; b++)
      head[24 + b] = uint8_t(payload_base >> (8 * b));  // file_base
  } else if (p.format >= 3) {
    const uint64_t dir_offset = uint64_t(head.size() + body.size());
    for (int b = 0; b < 8; b++)
      head[24 + b] = uint8_t(payload_base >> (8 * b));  // file_base (rel)
    for (int b = 0; b < 8; b++)
      head[32 + b] = uint8_t(dir_offset >> (8 * b));    // dir_offset (rel)
  }

  // ---------- emit ----------------------------------------------------------
  std::ofstream f(out_path, std::ios::binary | std::ios::trunc);
  if (!f) throw std::runtime_error("cannot write " + out_path);
  f.write(reinterpret_cast<const char*>(head.data()), head.size());
  if (p.format <= 2) {
    f.write(reinterpret_cast<const char*>(dir.data()), dir.size());
    f.write(reinterpret_cast<const char*>(body.data()), body.size());
  } else {
    f.write(reinterpret_cast<const char*>(body.data()), body.size());
    f.write(reinterpret_cast<const char*>(dir.data()), dir.size());
  }
  f.close();
  if (!f.good()) throw std::runtime_error("write failed for " + out_path);
}

// ================= inspection =================
PortabilityReport inspect(const std::vector<uint8_t>& pack_bytes,
                          const PckFile& p) {
  PortabilityReport r;
  r.engine = EngineKind::GodotNative;
  r.engine_version = std::to_string(p.ver_major) + "." +
                     std::to_string(p.ver_minor) + "." +
                     std::to_string(p.ver_patch);
  r.files_total = p.entries.size();
  r.native_loadable = true;

  bool has_project = false;
  bool encrypted_entries = false, delta_entries = false;
  std::string project_godot;

  for (const PckEntry& e : p.entries) {
    const std::string& pth = e.path;

    // --- Unity signatures: reject with reason (translation tier, not native)
    if (pth == "globalgamemanagers" || pth == "globalgamemanagers.assets" ||
        pth.find("il2cpp_data") != std::string::npos ||
        pth == "data.unity3d") {
      r.engine = EngineKind::UnityDetected;
      r.native_loadable = false;
      r.notes.push_back({"UNITY_X86_64",
                         "Unity PC build detected (engine code is compiled "
                         "x86-64 and cannot run natively on ARM; native "
                         "morphing does not exist for this input). Use the "
                         "translation tier (tier 2) instead.", true});
      return r;
    }

    if (e.flags & pckflag::ENCRYPTED) encrypted_entries = true;
    if (e.flags & pckflag::DELTA) delta_entries = true;

    // --- GDExtension / GDNative native blocks
    if (pth.size() > 12 && pth.substr(pth.size() - 12) == ".gdextension") {
      const auto text = read_entry(pack_bytes, p, e);
      const std::string head(text.begin(),
                             text.begin() + std::min<size_t>(text.size(), 256));
      if (head.find("windows") != std::string::npos ||
          head.find("linux") != std::string::npos ||
          head.find("x86_64") != std::string::npos ||
          head.find("win64") != std::string::npos) {
        r.native_loadable = false;
        r.notes.push_back({"GDEXT_X86_64",
                           e.path + " ships desktop native extension "
                           "libraries. Native code must be rebuilt for "
                           "android/arm64 by the vendor; data-side "
                           "repackaging cannot retarget it.", true});
      }
    }
    const std::string leaf = pth.substr(pth.find_last_of('/') + 1);
    const bool desktop_lib =
        leaf.size() > 4 &&
        (leaf.substr(leaf.size() - 4) == ".dll" ||
         leaf.substr(leaf.size() - 6) == ".dylib" ||
         (leaf.substr(leaf.size() - 3) == ".so" &&
          pth.find("android") == std::string::npos &&
          pth.find("libgodot") == std::string::npos));
    if (desktop_lib) {
      r.native_loadable = false;
      r.notes.push_back({"NATIVE_LIB_DESKTOP",
                         "desktop shared library in pack: " + e.path, true});
    }

    // --- texture census (feeds the transcode plan)
    if (leaf.size() > 5 && leaf.substr(leaf.size() - 5) == ".ctex") {
      r.textures.ctex_total++;
      r.textures.ctex_vram++;
    } else if (leaf.size() > 4 && leaf.substr(leaf.size() - 4) == ".png") r.textures.loose_png++;
    else if (leaf.size() > 4 && leaf.substr(leaf.size() - 4) == ".jpg") r.textures.loose_jpg++;
    else if (leaf.size() > 4 && leaf.substr(leaf.size() - 4) == ".jpeg") r.textures.loose_jpg++;
    else if (leaf.size() > 6 && leaf.substr(leaf.size() - 6) == ".basis") r.textures.basis++;
    else if (leaf.size() > 7 && leaf.substr(leaf.size() - 7) == ".basisu") r.textures.basis++;
    if (pth == "project.godot" || pth == "project.binary")
      has_project = true;
  }

  if (encrypted_entries) {
    r.native_loadable = false;
    r.notes.push_back({"ENCRYPTED_FILES",
                       "some entries are AES-encrypted (script encryption "
                       "key); this input cannot run without the key", true});
  }
  if (delta_entries) {
    r.native_loadable = false;
    r.notes.push_back({"DELTA_PACK",
                       "delta/patch packs need their base pack and are not "
                       "supported for import", true});
  }
  if (!has_project) {
    // Neither text nor compiled settings: the engine has nothing to boot
    // (resource/DLC packs from PCKPacker land here too). Kept non-blocking
    // here so the import flow can emit the precise, actionable message.
    r.notes.push_back({"NO_PROJECT_SETTINGS",
                       "no project.godot / project.binary entry; resource or "
                       "DLC pack, not a runnable game", false});
  }

  if (r.native_loadable) {
    char buf[64];
    std::snprintf(buf, sizeof buf, "%s pack: runs on the embedded %s runtime",
                  format_label(p.format), p.runtime_flavor() == 3 ? "3.6.2" : "4.7.2");
    r.notes.push_back({"NATIVE_OK", buf, false});
  }
  return r;
}

}  // namespace nm
