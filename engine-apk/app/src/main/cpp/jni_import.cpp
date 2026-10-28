// nativemorph - Android JNI bridge for the engine APK.
//
// Runs the import-time optimizer ON DEVICE: the user picks a game file
// (.pck / .zip / .exe / .apk), the Kotlin layer copies it into the game's
// work directory, and this entry point:
//   1. locates the PCK (embedded in archives/exes when needed),
//   2. inspects it (engine detect, Unity rejection, GDExtension audit),
//   3. patches project.godot with the caller-selected profile
//      (default Fidelity = same graphics: mobile renderer, authored
//      MSAA/shadows kept, scale 1.0),
//   4. repacks byte-exactly into game.pck,
//   5. returns a JSON report for the UI.
//
// Data files only: no game code is interpreted, decompiled or modified
// beyond the text settings entry. Copyright stays with the user.
#include <jni.h>
#include <android/log.h>

#include <zip.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "nm_api.hpp"

#define LOGI(...) \
  __android_log_print(ANDROID_LOG_INFO, "nativemorph", __VA_ARGS__)
#define LOGE(...) \
  __android_log_print(ANDROID_LOG_ERROR, "nativemorph", __VA_ARGS__)

namespace {

constexpr uint64_t kMaxOnDevicePack = uint64_t(1610) * 1024 * 1024;  // ~1.6G

bool has_suffix_ci(const std::string& s, const std::string& suf) {
  if (s.size() < suf.size()) return false;
  for (size_t i = 0; i < suf.size(); i++) {
    char a = s[s.size() - suf.size() + i];
    char b = suf[i];
    if (a >= 'A' && a <= 'Z') a = char(a - 'A' + 'a');
    if (b >= 'A' && b <= 'Z') b = char(b - 'A' + 'a');
    if (a != b) return false;
  }
  return true;
}

bool read_whole(const std::string& path, std::vector<uint8_t>& out) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f) return false;
  std::streamoff n = f.tellg();
  if (n < 0) return false;
  f.seekg(0, std::ios::beg);
  out.resize(size_t(n));
  if (n > 0) f.read(reinterpret_cast<char*>(out.data()), n);
  return f.good() || n == 0;
}

uint64_t file_size_of(const std::string& path) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  return f ? uint64_t(f.tellg()) : 0;
}

std::string jstr(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r = c ? c : "";
  env->ReleaseStringUTFChars(s, c);
  return r;
}

jstring to_jstr(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

// Minimal JSON string escaping (no dependency on a JSON library).
// 4-byte UTF-8 sequences (U+10000.., e.g. emoji in game names) are escaped
// as \u surrogate pairs: JNI's NewStringUTF expects modified UTF-8, which
// encodes supplementary code points differently, and org.json reassembles
// the pair into the real character at parse time.
std::string jesc(const std::string& in) {
  std::string out;
  out.reserve(in.size() + 8);
  for (size_t i = 0; i < in.size(); i++) {
    const unsigned char c = in[i];
    if (c >= 0xF0 && i + 3 < in.size() &&
        (in[i+1] & 0xC0) == 0x80 && (in[i+2] & 0xC0) == 0x80 &&
        (in[i+3] & 0xC0) == 0x80) {
      const uint32_t cp = (uint32_t(c & 0x07) << 18) |
                          (uint32_t(in[i+1] & 0x3F) << 12) |
                          (uint32_t(in[i+2] & 0x3F) << 6) |
                          uint32_t(in[i+3] & 0x3F);
      const uint32_t v = cp - 0x10000;
      char buf[16];
      std::snprintf(buf, sizeof buf, "\\u%04x\\u%04x",
                    0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF));
      out += buf;
      i += 3;
      continue;
    }
    switch (c) {
      case '"':  out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof buf, "\\u%04x", c);
          out += buf;
        } else {
          out += char(c);
        }
    }
  }
  return out;
}

// ---- naive JSON field readers (opts blob comes from our own Kotlin code) --
std::string json_str(const std::string& j, const std::string& key,
                     const std::string& dflt) {
  std::string needle = "\"" + key + "\"";
  size_t p = j.find(needle);
  if (p == std::string::npos) return dflt;
  p = j.find(':', p + needle.size());
  if (p == std::string::npos) return dflt;
  p = j.find('"', p);
  if (p == std::string::npos) return dflt;
  size_t q = j.find('"', p + 1);
  if (q == std::string::npos) return dflt;
  return j.substr(p + 1, q - p - 1);
}

double json_num(const std::string& j, const std::string& key, double dflt) {
  std::string needle = "\"" + key + "\"";
  size_t p = j.find(needle);
  if (p == std::string::npos) return dflt;
  p = j.find(':', p + needle.size());
  if (p == std::string::npos) return dflt;
  double v = 0;
  if (std::sscanf(j.c_str() + p + 1, "%lf", &v) == 1) return v;
  return dflt;
}

nm::PatchOptions parse_opts(const std::string& j) {
  nm::PatchOptions o;
  o.renderer = json_str(j, "renderer", "keep");
  if (o.renderer != "mobile" && o.renderer != "gl_compatibility")
    o.renderer = "keep";  // same-graphics: leave the authored method alone
  double scale = json_num(j, "scale", 1.0);
  o.scale = std::min(1.0, std::max(0.5, scale));
  o.fsr = json_num(j, "fsr", 0.0) > 0.5;
  o.msaa = int(json_num(j, "msaa", -1.0));   // <0 = keep authored
  o.shadows = int(json_num(j, "shadows", -1.0));  // <0 = keep authored
  return o;
}

std::string human_bytes(uint64_t n) {
  char buf[32];
  if (n >= 1024ull * 1024 * 1024)
    std::snprintf(buf, sizeof buf, "%.2f GB", double(n) / (1024.0 * 1024 * 1024));
  else if (n >= 1024 * 1024)
    std::snprintf(buf, sizeof buf, "%.1f MB", double(n) / (1024.0 * 1024));
  else
    std::snprintf(buf, sizeof buf, "%llu KB", (unsigned long long)(n / 1024));
  return buf;
}

// Locate the PCK inside an archive input (.zip bundle or a Godot .apk export:
// the export template stores the pack at assets/<name>.pck) and extract it.
std::string extract_pck_from_archive(const std::string& archive,
                                     const std::string& out_pck) {
  int err = 0;
  zip_t* z = zip_open(archive.c_str(), ZIP_RDONLY, &err);
  if (!z) throw std::runtime_error("cannot open archive: " + archive);

  std::string best;
  bool in_assets = false;
  zip_int64_t total = zip_get_num_entries(z, 0);
  for (zip_int64_t i = 0; i < total; i++) {
    const char* nm = zip_get_name(z, zip_uint64_t(i), ZIP_FL_ENC_GUESS);
    if (!nm) continue;
    std::string s(nm);
    if (!has_suffix_ci(s, ".pck")) continue;
    const bool assets = s.rfind("assets/", 0) == 0;
    const bool top = s.find('/') == std::string::npos;
    if (!assets && !top) continue;
    // Prefer the assets pack (apk export) over anything else; then the
    // largest (the main pack of a multi-pck bundle).
    if (best.empty() || (assets && !in_assets) ||
        (assets == in_assets && s.size() > best.size())) {
      best = s;
      in_assets = assets;
    }
  }
  if (best.empty()) {
    zip_close(z);
    throw std::runtime_error(
        "no Godot .pck found inside the archive (unsupported input)");
  }

  zip_int64_t idx = zip_name_locate(z, best.c_str(), ZIP_FL_NOCASE);
  zip_file* f = zip_fopen_index(z, zip_uint64_t(idx), 0);
  if (!f) {
    zip_close(z);
    throw std::runtime_error("cannot read pack entry: " + best);
  }
  std::ofstream out(out_pck, std::ios::binary | std::ios::trunc);
  if (!out) {
    zip_fclose(f);
    zip_close(z);
    throw std::runtime_error("cannot write " + out_pck);
  }
  std::vector<char> chunk(1 << 16);
  zip_int64_t r;
  while ((r = zip_fread(f, chunk.data(), chunk.size())) > 0)
    out.write(chunk.data(), r);
  zip_fclose(f);
  zip_close(z);
  if (!out.good()) throw std::runtime_error("write failed for " + out_pck);
  return out_pck;
}

class Progress {
 public:
  Progress(JNIEnv* env, jobject cb) : env_(env), cb_(cb) {
    cls_ = env_->GetObjectClass(cb_);
    mid_ = env_->GetMethodID(cls_, "onProgress",
                             "(Ljava/lang/String;ILjava/lang/String;)V");
  }
  void operator()(const std::string& stage, int pct, const std::string& msg) {
    if (mid_ == nullptr) return;
    env_->CallVoidMethod(
        cb_, mid_, to_jstr(env_, stage), jint(pct), to_jstr(env_, msg));
    if (env_->ExceptionCheck()) env_->ExceptionClear();
  }

 private:
  JNIEnv* env_;
  jobject cb_;
  jclass cls_ = nullptr;
  jmethodID mid_ = nullptr;
};

std::string notes_json(const nm::PortabilityReport& rep) {
  std::string out = "[";
  bool first = true;
  for (const auto& n : rep.notes) {
    if (!first) out += ",";
    first = false;
    out += "{\"code\":\"" + jesc(n.code) + "\",\"message\":\"" +
           jesc(n.message) + "\",\"blocker\":" +
           (n.blocker ? "true" : "false") + "}";
  }
  out += "]";
  return out;
}

std::string error_json(const std::string& what) {
  return "{\"ok\":false,\"error\":\"" + jesc(what) + "\"}";
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_nativemorph_engine_NativeBridge_importGame(JNIEnv* env, jobject,
                                                   jstring j_src,
                                                   jstring j_work,
                                                   jstring j_opts,
                                                   jobject j_progress) {
  const std::string src = jstr(env, j_src);
  const std::string work = jstr(env, j_work);
  const std::string opts_raw = jstr(env, j_opts);

  try {
    if (src.empty() || work.empty())
      return to_jstr(env, error_json("missing import arguments"));

    Progress progress(env, j_progress);
    progress("copy", 2, "Reading source file...");

    // ---- 1. locate the pack -------------------------------------------
    std::string mapped = src;
    const bool archive_in = has_suffix_ci(src, ".zip") ||
                            has_suffix_ci(src, ".apk") ||
                            has_suffix_ci(src, ".obb");
    if (archive_in) {
      progress("scan", 8, "Extracting the game pack from the archive...");
      mapped = extract_pck_from_archive(src, work + "/import.pck");
    }

    const uint64_t src_size = file_size_of(mapped);
    if (src_size == 0) return to_jstr(env, error_json("empty or unreadable pack"));
    if (src_size > kMaxOnDevicePack)
      return to_jstr(env, error_json(
          "pack is " + human_bytes(src_size) +
          "; the on-device v1 importer handles packs up to ~1.6 GB"));

    progress("inspect", 15, "Parsing pack container...");
    nm::PckFile p;
    nm::load_pck(p, mapped);

    std::vector<uint8_t> buf;
    if (!read_whole(mapped, buf))
      return to_jstr(env, error_json("failed to read pack data"));

    // ---- 2. inspect / portability audit --------------------------------
    progress("inspect", 25, "Auditing portability...");
    nm::PortabilityReport rep = nm::inspect(buf, p);

    if (rep.engine == nm::EngineKind::UnityDetected || !rep.native_loadable) {
      progress("done", 100, "Rejected (see report)");
      return to_jstr(env,
          "{\"ok\":false,\"rejected\":true,\"engine\":\"" +
          (rep.engine == nm::EngineKind::UnityDetected ? std::string("unity")
                                                      : std::string("unknown")) +
          "\",\"notes\":" + notes_json(rep) + ",\"error\":\"" +
          jesc("this input cannot run natively on ARM Android; see notes") +
          "\"}");
    }

    // ---- runtime routing --------------------------------------------------
    // PCK format 1 (Godot 3.0-3.6) runs on the embedded 3.6.2 runtime;
    // formats 2 (4.0-4.3), 3 (4.4+) and 4 (4.7+) run on the embedded 4.7.2
    // runtime - both ship inside this APK.
    if (p.ver_major != 3 && p.ver_major != 4)
      return to_jstr(env, error_json(
          "pack targets Godot " + std::to_string(p.ver_major) +
          " (this engine bundles the 3.x and 4.x runtimes)"));
    if (p.format == 1 && p.ver_major != 3)
      rep.notes.push_back({"ODD_FORMAT",
          "PCK v1 directory with Godot " + std::to_string(p.ver_major) +
          " version fields; interpreting as a 3.x pack.", false});
    if (p.format >= 2 && p.ver_major == 3)
      rep.notes.push_back({"ODD_FORMAT",
          "PCK v" + std::to_string(p.format) + " directory with Godot 3.x "
          "version fields; interpreting as a 4.x pack.", false});
    const int runtime_minor = p.ver_major == 3 ? 6 : 7;
    if (p.ver_minor > uint32_t(runtime_minor))
      rep.notes.push_back({"NEWER_PACK",
          "Pack was exported by Godot " + std::to_string(p.ver_major) + "." +
          std::to_string(p.ver_minor) + " which is newer than the embedded "
          "runtime; usually fine, re-export if anything misbehaves.", false});

    // ---- 3. settings (text project.godot OR compiled project.binary) ----
    nm::PatchOptions opts = parse_opts(opts_raw);
    opts.engine_major = p.runtime_flavor();
    progress("patch", 35, "Reading project settings...");
    nm::SettingsResult settings = nm::acquire_settings(buf, p);
    if (!settings.found)
      return to_jstr(env, error_json(
          "no project.godot / project.binary inside the pack - this looks "
          "like a resource or DLC pack, not a game. Import the game's main "
          ".pck / .exe / .zip instead"));
    if (!settings.kept_reason.empty())
      rep.notes.push_back({"BINARY_SETTINGS_KEPT", settings.kept_reason, false});
    if (settings.converted)
      rep.notes.push_back({"BINARY_SETTINGS_CONVERTED",
          "settings were stored as compiled project.binary (default export "
          "option); converted to text so the optimizer applies", false});

    std::string project_name = settings.name, patched, pg_text = settings.text;
    if (!pg_text.empty()) {
      progress("patch", 40, "Applying the " +
               (opts.scale < 1.0 ? std::string("battery") : std::string("same-graphics")) +
               " mobile profile...");
      if (!nm::patch_project_godot(pg_text, opts, project_name, patched))
        return to_jstr(env, error_json("failed to patch project settings"));
    }

    // ---- 4. repack ------------------------------------------------------
    progress("repack", 55, "Rebuilding the pack (byte-exact for untouched files)...");
    const std::string out_pck = work + "/game.pck";
    std::vector<uint8_t> repl(patched.begin(), patched.end());
    nm::repack(buf, p, repl,
               settings.remove_binary ? "project.binary" : "", out_pck);

    const uint64_t out_size = file_size_of(out_pck);
    if (out_size == 0)
      return to_jstr(env, error_json("repack produced no output"));

    // ---- 5. extract the game's own icon for the library card ------------
    // (the settings' config/icon when it points at a raster format, else a
    // root icon.png if present; .svg is skipped - Android can't decode it.)
    std::string icon_file;
    {
      std::string icon_path = settings.icon;
      auto decodable = [](const std::string& p) {
        return has_suffix_ci(p, ".png") || has_suffix_ci(p, ".jpg") ||
               has_suffix_ci(p, ".jpeg") || has_suffix_ci(p, ".webp") ||
               has_suffix_ci(p, ".bmp");
      };
      if (icon_path.empty() || !decodable(icon_path)) {
        // fall back to a root icon.png if the authored icon is svg/missing
        for (const auto& e : p.entries)
          if (e.path == "icon.png") { icon_path = "icon.png"; break; }
      }
      if (!icon_path.empty() && decodable(icon_path)) {
        for (const auto& e : p.entries) {
          if (e.path != icon_path) continue;
          try {
            std::vector<uint8_t> bytes = nm::read_entry(buf, p, e);
            if (bytes.empty() || bytes.size() > 8u * 1024 * 1024) break;
            const std::string ext = icon_path.substr(
                icon_path.find_last_of('.') + 1);
            const std::string dst = work + "/icon." + ext;
            std::ofstream f(dst, std::ios::binary | std::ios::trunc);
            if (f) {
              f.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
              if (f.good()) icon_file = "icon." + ext;
            }
          } catch (...) {
          }
          break;
        }
      }
    }

    progress("done", 100, "Import complete");

    std::ostringstream js;
    js << "{\"ok\":true"
       << ",\"name\":\"" << jesc(project_name.empty() ? "Godot game"
                                                      : project_name) << "\""
       << ",\"engine_version\":\"" << p.ver_major << "." << p.ver_minor << "."
       << p.ver_patch << "\""
       << ",\"format\":" << p.format
       << ",\"flavor\":\"" << p.runtime_flavor() << "\""
       << ",\"files\":" << p.entries.size()
       << ",\"size_in\":" << src_size
       << ",\"size_out\":" << out_size
       << ",\"size_in_h\":\"" << human_bytes(src_size) << "\""
       << ",\"size_out_h\":\"" << human_bytes(out_size) << "\""
       << ",\"profile\":\"" << (opts.scale < 1.0 ? "battery" : "fidelity")
       << "\""
       << ",\"settings\":\"" << (settings.binary
                                         ? (settings.converted ? "binary-converted"
                                                               : "binary-kept")
                                         : "text") << "\""
       << ",\"renderer\":\"" << jesc(opts.renderer) << "\""
       << ",\"icon\":\"" << jesc(icon_file) << "\""
       << ",\"notes\":" << notes_json(rep) << "}";
    LOGI("import done: %s -> %s", src.c_str(), out_pck.c_str());
    return to_jstr(env, js.str());
  } catch (const std::exception& e) {
    LOGE("import failed: %s", e.what());
    return to_jstr(env, error_json(e.what()));
  } catch (...) {
    LOGE("import failed: unknown error");
    return to_jstr(env, error_json("unknown native error"));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nativemorph_engine_NativeBridge_coreVersion(JNIEnv* env, jobject) {
  return to_jstr(env, "nmcore 1.2 (PCK v1/v2/v3/v4; Godot 3+4 runtimes; "
                      "text + compiled project settings)");
}
