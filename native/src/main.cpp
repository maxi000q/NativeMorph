// nativemorph - CLI orchestrator.
//
// Verbs:
//   nativemorph inspect <game.pck|.zip|.exe>          portability report (JSON)
//   nativemorph build <game> --template <t.apk> ...   full pipeline -> APK
//   nativemorph tex <image> -o <out.astc>             ASTC encode tool
//
// Progress contract (stdout, one JSON object per line) consumed by web/lib:
//   {"stage":"inspect","state":"run","msg":"..."}
//   {"stage":"done","apk":"/abs/path.apk","sha256":"..."}
//   {"stage":"error","code":"GDEXT_X86_64","report":{...}}
// Exit codes: 0 success, 2 usage, 10 portability rejection, 20 pipeline/IO.

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "nm_api.hpp"
#include "pck.hpp"

namespace nm {

// implemented in tex_pool.cpp
int run_tex_verb(int argc, char** argv);

}  // namespace nm

using namespace nm;

namespace {

// ---------------- tiny JSON emitters (escape-only, no parsing needed) ------
std::string jstr(const std::string& s) {
  std::string o = "\"";
  for (char c : s) {
    switch (c) {
      case '"': o += "\\\""; break;
      case '\\': o += "\\\\"; break;
      case '\n': o += "\\n"; break;
      case '\r': o += "\\r"; break;
      case '\t': o += "\\t"; break;
      default:
        if (uint8_t(c) < 0x20) {
          char b[8]; std::snprintf(b, sizeof b, "\\u%04x", c); o += b;
        } else o += c;
    }
  }
  return o + "\"";
}

void emit(const std::string& line) { std::fprintf(stdout, "%s\n", line.c_str()); std::fflush(stdout); }

void emit_stage(Stage s, const char* state, int pct = -1,
                const std::string& msg = "") {
  std::string o = "{\"stage\":" + jstr(stage_name(s)) +
                  ",\"state\":" + jstr(state);
  if (pct >= 0) o += ",\"pct\":" + std::to_string(pct);
  if (!msg.empty()) o += ",\"msg\":" + jstr(msg);
  emit(o + "}");
}

std::string emit_report_json(const PortabilityReport& r) {
  std::string o = "{\"engine\":\"";
  o += r.engine == EngineKind::GodotNative
           ? "godot-native"
           : r.engine == EngineKind::UnityDetected ? "unity-x86_64" : "unknown";
  o += "\",\"engine_version\":" + jstr(r.engine_version);
  o += ",\"project_name\":" + jstr(r.project_name);
  o += ",\"native_loadable\":" + std::string(r.native_loadable ? "true" : "false");
  o += ",\"files_total\":" + std::to_string(r.files_total);
  o += ",\"textures\":{\"ctex_total\":" + std::to_string(r.textures.ctex_total) +
       ",\"ctex_vram\":" + std::to_string(r.textures.ctex_vram) +
       ",\"ctex_lossless\":" + std::to_string(r.textures.ctex_lossless) +
       ",\"loose_png\":" + std::to_string(r.textures.loose_png) +
       ",\"loose_jpg\":" + std::to_string(r.textures.loose_jpg) +
       ",\"basis\":" + std::to_string(r.textures.basis) + "}";
  o += ",\"notes\":[";
  for (size_t i = 0; i < r.notes.size(); i++) {
    if (i) o += ",";
    o += "{\"code\":" + jstr(r.notes[i].code) +
         ",\"message\":" + jstr(r.notes[i].message) +
         ",\"blocker\":" + std::string(r.notes[i].blocker ? "true" : "false") +
         "}";
  }
  o += "]}";
  return o;
}

// ---------------- sha256 (streaming, FIPS 180-4, self-contained) -----------
struct SHA256 {
  uint32_t h[8] = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                   0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
  uint8_t buf[64]{};
  size_t fill = 0;
  uint64_t total = 0;
  static uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }
  void block(const uint8_t* p) {
    static const uint32_t K[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,
        0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,
        0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,
        0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,
        0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,
        0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,
        0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,
        0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,
        0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    uint32_t w[64];
    for (int i = 0; i < 16; i++)
      w[i] = (uint32_t(p[4*i]) << 24) | (uint32_t(p[4*i+1]) << 16) |
             (uint32_t(p[4*i+2]) << 8) | uint32_t(p[4*i+3]);
    for (int i = 16; i < 64; i++) {
      uint32_t s0 = rotr(w[i-15],7) ^ rotr(w[i-15],18) ^ (w[i-15] >> 3);
      uint32_t s1 = rotr(w[i-2],17) ^ rotr(w[i-2],19) ^ (w[i-2] >> 10);
      w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    uint32_t a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];
    for (int i = 0; i < 64; i++) {
      uint32_t S1 = rotr(e,6) ^ rotr(e,11) ^ rotr(e,25);
      uint32_t ch = (e & f) ^ (~e & g);
      uint32_t t1 = hh + S1 + ch + K[i] + w[i];
      uint32_t S0 = rotr(a,2) ^ rotr(a,13) ^ rotr(a,22);
      uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
      uint32_t t2 = S0 + maj;
      hh=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hh;
  }
  void update(const uint8_t* p, size_t n) {
    total += n;
    while (n) {
      size_t take = std::min(n, size_t(64) - fill);
      std::memcpy(buf + fill, p, take);
      fill += take; p += take; n -= take;
      if (fill == 64) { block(buf); fill = 0; }
    }
  }
  std::string hex() {
    uint64_t bits = total * 8;
    uint8_t pad = 0x80;
    update(&pad, 1);
    uint8_t z = 0;
    while (fill != 56) update(&z, 1);
    uint8_t lenb[8];
    for (int i = 0; i < 8; i++) lenb[i] = uint8_t(bits >> (56 - 8 * i));
    update(lenb, 8);
    char out[65];
    for (int i = 0; i < 8; i++)
      std::snprintf(out + i * 8, 9, "%08x", h[i]);
    return std::string(out, 64);
  }
};

std::string sha256_file(const std::string& path) {
  std::ifstream f(path, std::ios::binary);
  if (!f) return "";
  SHA256 s;
  std::vector<char> chunk(1 << 16);
  while (f.read(chunk.data(), chunk.size()) || f.gcount() > 0)
    s.update(reinterpret_cast<const uint8_t*>(chunk.data()), size_t(f.gcount()));
  return s.hex();
}

// ---------------- process runner (gradle, zipalign, apksigner) ------------
int run_cmd(const std::string& cmd, Stage stage, const std::string& what) {
  emit_stage(stage, "run", -1, what + ": " + cmd);
  int rc = std::system(cmd.c_str());
  if (rc != 0) {
    emit_stage(Stage::Error, "fail", -1,
               what + " exited with status " + std::to_string(rc));
  }
  return rc;
}

std::string shell_escape(const std::string& s) {
  return "'" + std::string(s) + "'";
}

struct BuildArgs {
  std::string input, template_apk, keystore, ks_alias = "nativemorph",
              ks_pass, out = "out/game.apk", package, app_name, gradle_root;
  PatchOptions opts;  // optimizer profile + overlay layout
};

BuildArgs parse_build_args(int argc, char** argv) {
  BuildArgs b;
  for (int i = 2; i < argc; i++) {
    std::string a = argv[i];
    auto next = [&]() -> std::string { return (i + 1 < argc) ? argv[++i] : ""; };
    if (a == "--template") b.template_apk = next();
    else if (a == "--keystore") b.keystore = next();
    else if (a == "--ks-alias") b.ks_alias = next();
    else if (a == "--ks-pass") b.ks_pass = next();          // env:VAR or literal
    else if (a == "--out") b.out = next();
    else if (a == "--package") b.package = next();
    else if (a == "--app-name") b.app_name = next();
    else if (a == "--gradle-root") b.gradle_root = next();
    // --- optimizer profile + overlay layout (web tier passes these) ---
    else if (a == "--layout") b.opts.layout = next();
    else if (a == "--renderer") b.opts.renderer = next();
    else if (a == "--scale") b.opts.scale = std::stod(next());
    else if (a == "--fsr") b.opts.fsr = std::stoi(next()) != 0;
    else if (a == "--msaa") b.opts.msaa = std::stoi(next());
    else if (a == "--shadows") b.opts.shadows = std::stoi(next());
    else if (b.input.empty()) b.input = a;
  }
  return b;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr,
                  "usage: nativemorph <inspect|build|tex> ...\n");
    return 2;
  }
  const std::string verb = argv[1];

  // ---------------- tex verb ----------------
  if (verb == "tex") return run_tex_verb(argc, argv);

  // ---------------- inspect verb ----------------
  if (verb == "inspect") {
    if (argc < 3) { std::fprintf(stderr, "inspect needs an input\n"); return 2; }
    try {
      PckFile p;
      load_pck(p, argv[2]);
      std::vector<uint8_t> bytes;
      {
        std::ifstream f(p.mapped_path, std::ios::binary);
        f.seekg(0, std::ios::end);
        bytes.resize(size_t(f.tellg()));
        f.seekg(0); f.read(reinterpret_cast<char*>(bytes.data()),
                           std::streamsize(bytes.size()));
      }
      PortabilityReport r = inspect(bytes, p);
      nm::SettingsResult settings = nm::acquire_settings(bytes, p);
      r.project_name = settings.name;
      emit("{\"report\":" + emit_report_json(r) + ",\"settings\":\"" +
           (settings.binary ? (settings.converted ? "binary-converted"
                                                  : "binary-kept")
                            : (settings.found ? "text" : "none")) + "\"}");
      return r.native_loadable ? 0 : 10;
    } catch (const std::exception& ex) {
      emit("{\"stage\":\"error\",\"code\":\"IO\",\"message\":" +
           jstr(ex.what()) + "}");
      return 20;
    }
  }

  // ---------------- repack verb ----------------
  // Mirrors the on-device import exactly: locate pack, audit, patch
  // project.godot with the profile, repack to a standalone .pck.
  if (verb == "repack") {
    std::string in, out = "game.pck";
    std::string profile = "fidelity";
    for (int i = 2; i < argc; i++) {
      std::string a = argv[i];
      if (a == "--out" && i + 1 < argc) out = argv[++i];
      else if (a == "--profile" && i + 1 < argc) profile = argv[++i];
      else if (in.empty()) in = a;
    }
    if (in.empty()) { std::fprintf(stderr, "repack needs <input>\n"); return 2; }
    try {
      PckFile p;
      load_pck(p, in);
      std::vector<uint8_t> bytes;
      {
        std::ifstream f(p.mapped_path, std::ios::binary);
        f.seekg(0, std::ios::end);
        bytes.resize(size_t(f.tellg()));
        f.seekg(0); f.read(reinterpret_cast<char*>(bytes.data()),
                           std::streamsize(bytes.size()));
      }
      PortabilityReport rep = inspect(bytes, p);
      if (p.ver_major != 3 && p.ver_major != 4) {
        std::fprintf(stderr, "unsupported engine %u\n", p.ver_major);
        return 10;
      }
      PatchOptions opts;
      opts.engine_major = p.runtime_flavor();
      if (profile == "battery") { opts.scale = 0.75; opts.fsr = true; }
      // fidelity: same graphics (authored msaa/shadows kept)
      nm::SettingsResult settings = nm::acquire_settings(bytes, p);
      if (!settings.found) {
        std::fprintf(stderr, "no project.godot / project.binary entry\n");
        return 10;
      }
      std::vector<uint8_t> patched_pg;
      std::string name = settings.name;
      if (!settings.text.empty()) {
        std::string patched;
        patch_project_godot(settings.text, opts, name, patched);
        patched_pg.assign(patched.begin(), patched.end());
      }
      repack(bytes, p, patched_pg,
             settings.remove_binary ? "project.binary" : "", out);
      emit("{\"stage\":\"done\",\"ok\":true,\"out\":\"" + out +
           "\",\"engine\":" + std::to_string(p.ver_major) +
           ",\"format\":" + std::to_string(p.format) +
           ",\"name\":\"" + name + "\"}");
      return 0;
    } catch (const std::exception& ex) {
      std::fprintf(stderr, "repack failed: %s\n", ex.what());
      return 20;
    }
  }

  // ---------------- build verb ----------------
  if (verb == "build") {
    BuildArgs b = parse_build_args(argc, argv);
    if (b.input.empty() || b.template_apk.empty()) {
      std::fprintf(stderr,
                    "build needs <input> --template <apk> [--keystore ...]\n");
      return 2;
    }
    try {
      // ---- inspect ----
      emit_stage(Stage::Inspect, "run", 10, "loading container");
      PckFile p;
      load_pck(p, b.input);
      std::vector<uint8_t> bytes;
      {
        std::ifstream f(p.mapped_path, std::ios::binary);
        f.seekg(0, std::ios::end);
        bytes.resize(size_t(f.tellg()));
        f.seekg(0); f.read(reinterpret_cast<char*>(bytes.data()),
                           std::streamsize(bytes.size()));
      }
      emit_stage(Stage::Inspect, "run", 60, "portability audit");
      PortabilityReport rep = inspect(bytes, p);

      // ---- validate caller options before anything is written ----
      if (!valid_layout(b.opts.layout) || !valid_renderer(b.opts.renderer) ||
          b.opts.scale < 0.5 || b.opts.scale > 1.0) {
        emit_stage(Stage::Error, "fail", -1,
                   "invalid profile options (layout/renderer/scale)");
        return 2;
      }

      // ---- settings (text or compiled project.binary) + patch ----
      emit_stage(Stage::Patch, "run", 5, "injecting render profile");
      std::vector<uint8_t> patched_pg;
      std::string app_name = b.app_name;
      {
        nm::SettingsResult settings = acquire_settings(bytes, p);
        if (app_name.empty()) app_name = settings.name;
        if (!settings.text.empty()) {
          std::string patched, name;  // name from the text entry; app_name
                                      // (caller-provided) keeps priority
          patch_project_godot(settings.text, b.opts, name, patched);
          if (app_name.empty()) app_name = name;
          patched_pg.assign(patched.begin(), patched.end());
        }
      }
      if (app_name.empty()) app_name = "NativeMorph Game";
      if (!rep.native_loadable) {
        emit("{\"stage\":\"error\",\"code\":\"PORTABILITY\",\"report\":" +
             emit_report_json(rep) + "}");
        return 10;
      }
      {
        char buf[160];
        std::snprintf(buf, sizeof buf,
                      "renderer=%s scale=%.2f fsr=%d msaa=%d shadows=%d",
                      b.opts.renderer.c_str(), b.opts.scale,
                      b.opts.fsr ? 1 : 0, b.opts.msaa, b.opts.shadows);
        emit_stage(Stage::Patch, "ok", 100, buf);
      }

      // ---- repack ----
      emit_stage(Stage::Repack, "run", 30, "re-emitting container");
      std::string tmp_pck = b.out + ".repacked.pck";
      {
        std::string dir = b.out.substr(0, b.out.find_last_of('/'));
        if (!dir.empty()) {
          std::string mk = "mkdir -p " + shell_escape(dir);
          if (std::system(mk.c_str()) != 0) { /* best effort */ }
        }
      }
      repack(bytes, p, patched_pg, "", tmp_pck);
      emit_stage(Stage::Repack, "ok", 100, "entries byte-preserved: " +
                                            std::to_string(p.entries.size()));

      // ---- package (gradle wrapper project) ----
      emit_stage(Stage::Package, "run", 10, "staging assets/main.pck + overlay config");
      std::string wrapper = b.gradle_root.empty()
                                ? std::string("android/wrapper")
                                : b.gradle_root;
      {
        std::string stage_cmd =
            "mkdir -p " + wrapper + "/app/src/main/assets && cp " +
            shell_escape(tmp_pck) + " " +
            shell_escape(wrapper + "/app/src/main/assets/main.pck");
        if (std::system(stage_cmd.c_str()) != 0) {
          emit_stage(Stage::Error, "fail", -1, "cannot stage main.pck");
          return 20;
        }
      }
      // Touch-overlay config: which of the 3 mobile layouts the wrapper
      // floats over the game (read by TouchOverlayView at startup).
      {
        std::ofstream cfg(wrapper + "/app/src/main/assets/nativemorph_config.json",
                          std::ios::trunc);
        if (!cfg) {
          emit_stage(Stage::Error, "fail", -1, "cannot write overlay config");
          return 20;
        }
        cfg << "{\n"
            << "  \"layout\": \"" << b.opts.layout << "\",\n"
            << "  \"opacity\": 0.35\n"
            << "}\n";
      }
      std::string pkg = b.package.empty() ? "" : b.package;
      std::string gradle_cmd =
          "cd " + shell_escape(wrapper) + " && gradle assembleRelease -q " +
          (pkg.empty() ? std::string("")
                       : "-PnativemorphPackage=" + shell_escape(pkg)) +
          " -PnativemorphAppName=" + shell_escape(app_name) +
          " -PnativemorphTemplateAar=" + shell_escape(b.template_apk);
      if (run_cmd(gradle_cmd, Stage::Package, "gradle assembleRelease") != 0)
        return 20;
      emit_stage(Stage::Package, "ok", 100, "unsigned APK assembled");

      // ---- sign ----
      emit_stage(Stage::Sign, "run", 40, "zipalign + apksigner");
      std::string unsigned_apk = wrapper + "/app/build/outputs/apk/release/app-release-unsigned.apk";
      std::string pass = b.ks_pass;
      if (pass.rfind("env:", 0) == 0) {
        const char* v = std::getenv(pass.c_str() + 4);
        pass = v ? v : "";
      }
      std::string sign_cmd =
          "bash tools/sign.sh " + shell_escape(unsigned_apk) + " " +
          shell_escape(b.out) + " " + shell_escape(b.keystore) + " " +
          shell_escape(b.ks_alias) + " " + shell_escape(pass);
      if (run_cmd(sign_cmd, Stage::Sign, "apksigner") != 0) return 20;

      std::string digest = sha256_file(b.out);
      emit("{\"stage\":\"done\",\"apk\":" + jstr(b.out) + ",\"sha256\":" +
           jstr(digest) + ",\"app_name\":" + jstr(app_name) +
           ",\"engine\":" + jstr(rep.engine_version) +
           ",\"layout\":" + jstr(b.opts.layout) + "}");
      std::remove(tmp_pck.c_str());
      return 0;
    } catch (const std::exception& ex) {
      emit("{\"stage\":\"error\",\"code\":\"IO\",\"message\":" +
           jstr(ex.what()) + "}");
      return 20;
    }
  }

  std::fprintf(stderr, "unknown verb: %s\n", verb.c_str());
  return 2;
}
