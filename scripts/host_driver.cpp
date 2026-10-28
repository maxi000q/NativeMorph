// Host driver for the UTF-8 regression test: mirrors the on-device JNI
// import (load -> inspect -> patch -> repack -> icon extract) without
// Android. Also has a --verify mode that re-parses an output pack and
// checks every entry's payload against the source pack (byte-exact).
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "nm_api.hpp"

static std::vector<uint8_t> slurp(const std::string& p) {
  std::ifstream f(p, std::ios::binary | std::ios::ate);
  if (!f) return {};
  size_t n = size_t(f.tellg());
  f.seekg(0);
  std::vector<uint8_t> v(n);
  if (n) f.read(reinterpret_cast<char*>(v.data()), n);
  return v;
}

static bool has_suffix_ci(const std::string& s, const std::string& suf) {
  if (s.size() < suf.size()) return false;
  for (size_t i = 0; i < suf.size(); i++) {
    char a = s[s.size() - suf.size() + i], b = suf[i];
    if (a >= 'A' && a <= 'Z') a = char(a - 'A' + 'a');
    if (b >= 'A' && b <= 'Z') b = char(b - 'A' + 'a');
    if (a != b) return false;
  }
  return true;
}

static std::string jesc_out(const std::string& in) {
  std::string buf;
  for (unsigned char c : in) {
    if (c == '"') buf += "\\\"";
    else if (c == '\\') buf += "\\\\";
    else if (c < 0x20) buf += ' ';
    else buf += char(c);
  }
  return buf;
}

// usage:
//   host_driver <in.pck> <workdir> <optsJson>       import (JSON on stdout)
//   host_driver --verify <a.pck> <b.pck> [mode]    roundtrip check
//     mode "converted" (default): settings entries are surgically replaced -
//       project.godot may differ / be added, project.binary must be GONE
//     mode "kept": settings untouched - every entry byte-exact incl.
//       project.binary, no new project.godot
//     mode "text": classic text pack - only project.godot may differ
int main(int argc, char** argv) {
  if (argc >= 4 && std::strcmp(argv[1], "--verify") == 0) {
    const std::string mode = argc >= 5 ? argv[4] : "converted";
    // verify source vs output: every entry must match byte-exact unless its
    // path is project.godot (patched on purpose).
    nm::PckFile a, b;
    try {
      nm::load_pck(a, argv[2]);
      nm::load_pck(b, argv[3]);
    } catch (const std::exception& e) {
      std::printf("{\"ok\":false,\"error\":\"%s\"}\n", jesc_out(e.what()).c_str());
      return 1;
    }
    std::vector<uint8_t> ba = slurp(a.mapped_path);
    std::vector<uint8_t> bb = slurp(b.mapped_path);
    bool a_binary = false, b_binary = false, b_pg = false;
    for (const auto& e : a.entries) if (e.path == "project.binary") a_binary = true;
    for (const auto& e : b.entries) {
      if (e.path == "project.binary") b_binary = true;
      if (e.path == "project.godot") b_pg = true;
    }
    const bool converted = mode == "converted";
    const bool a_had_binary = a_binary;
    if (converted && a_had_binary && b_binary) {
      std::printf("{\"ok\":false,\"error\":\"project.binary not removed\"}\n");
      return 1;
    }
    if (converted && a_had_binary && !b_pg) {
      std::printf("{\"ok\":false,\"error\":\"converted pack has no project.godot\"}\n");
      return 1;
    }
    if (mode == "kept" && !a_had_binary) {
      std::printf("{\"ok\":false,\"error\":\"kept mode on a pack without project.binary\"}\n");
      return 1;
    }
    int matched = 0, total = 0;
    for (const auto& ea : a.entries) {
      const bool settings_entry =
          ea.path == "project.godot" || ea.path == "project.binary";
      // converted: both settings entries are surgical. text: only
      // project.godot may differ. kept: everything must be byte-exact.
      const bool skip = settings_entry &&
                        (converted || (mode == "text" &&
                                       ea.path == "project.godot"));
      if (skip) continue;
      total++;
      const nm::PckEntry* hit = nullptr;
      for (const auto& eb : b.entries)
        if (eb.path == ea.path) { hit = &eb; break; }
      if (!hit) { std::printf("{\"ok\":false,\"error\":\"missing %s\"}\n", jesc_out(ea.path).c_str()); return 1; }
      if (ea.size != hit->size ||
          std::memcmp(ba.data() + ea.offset, bb.data() + hit->offset, size_t(ea.size)) != 0) {
        std::printf("{\"ok\":false,\"error\":\"content differs: %s\"}\n", jesc_out(ea.path).c_str());
        return 1;
      }
      matched++;
    }
    std::printf("{\"ok\":true,\"matched\":%d,\"total\":%d}\n", matched, total);
    return 0;
  }

  if (argc < 4) {
    std::fprintf(stderr, "usage: host_driver <in.pck> <workdir> <optsJson> | --verify <a.pck> <b.pck>\n");
    return 2;
  }
  const std::string src = argv[1], work = argv[2], opts_raw = argv[3];
  try {
    nm::PckFile p;
    nm::load_pck(p, src);
    std::vector<uint8_t> buf = slurp(p.mapped_path);
    nm::PortabilityReport rep = nm::inspect(buf, p);
    if (rep.engine == nm::EngineKind::UnityDetected || !rep.native_loadable) {
      std::printf("{\"ok\":false,\"rejected\":true}\n");
      return 1;
    }
    if (p.ver_major != 3 && p.ver_major != 4) {
      std::printf("{\"ok\":false,\"error\":\"bad engine major\"}\n");
      return 1;
    }

    // Settings: text project.godot OR compiled project.binary (converted).
    nm::SettingsResult settings = nm::acquire_settings(buf, p);
    if (!settings.found) {
      std::printf("{\"ok\":false,\"error\":\"no project.godot/project.binary\"}\n");
      return 1;
    }
    // parse opts: {"renderer":"keep"} (naive but sufficient for the test)
    std::string renderer = "keep";
    if (opts_raw.find("\"mobile\"") != std::string::npos) renderer = "mobile";
    if (opts_raw.find("\"gl_compatibility\"") != std::string::npos) renderer = "gl_compatibility";

    nm::PatchOptions o;
    o.renderer = renderer;
    o.engine_major = p.runtime_flavor();

    std::string pg_text = settings.text;
    std::string name = settings.name, patched;
    if (!pg_text.empty()) {
      if (!nm::patch_project_godot(pg_text, o, name, patched)) {
        std::printf("{\"ok\":false,\"error\":\"patch failed\"}\n");
        return 1;
      }
      std::ofstream pf(work + "/patched_project.godot", std::ios::binary);
      pf << patched;
    }

    std::vector<uint8_t> repl(patched.begin(), patched.end());
    const std::string out_pck = work + "/game.pck";
    nm::repack(buf, p, repl,
               settings.remove_binary ? "project.binary" : "", out_pck);

    // icon extraction (mirrors jni_import.cpp)
    std::string icon_file;
    {
      std::string icon_path = settings.icon;
      auto decodable = [](const std::string& pp) {
        return has_suffix_ci(pp, ".png") || has_suffix_ci(pp, ".jpg") ||
               has_suffix_ci(pp, ".jpeg") || has_suffix_ci(pp, ".webp") ||
               has_suffix_ci(pp, ".bmp");
      };
      if (icon_path.empty() || !decodable(icon_path)) {
        for (const auto& e : p.entries)
          if (e.path == "icon.png") { icon_path = "icon.png"; break; }
      }
      if (!icon_path.empty() && decodable(icon_path)) {
        for (const auto& e : p.entries) {
          if (e.path != icon_path) continue;
          std::vector<uint8_t> bytes = nm::read_entry(buf, p, e);
          if (!bytes.empty() && bytes.size() <= 8u * 1024 * 1024) {
            const std::string ext = icon_path.substr(icon_path.find_last_of('.') + 1);
            std::ofstream f(work + "/icon." + ext, std::ios::binary | std::ios::trunc);
            f.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
            if (f.good()) icon_file = "icon." + ext;
          }
          break;
        }
      }
    }

    std::printf("{\"ok\":true,\"name\":\"%s\",\"engine_version\":\"%d.%d.%d\","
                "\"files\":%zu,\"renderer\":\"%s\",\"icon\":\"%s\",\"settings\":\"%s\"}\n",
                jesc_out(name).c_str(), p.ver_major, p.ver_minor, p.ver_patch,
                p.entries.size(), jesc_out(o.renderer).c_str(),
                jesc_out(icon_file).c_str(),
                jesc_out(settings.binary ? (settings.converted ? "binary-converted"
                                                             : "binary-kept")
                                         : "text").c_str());
    return 0;
  } catch (const std::exception& e) {
    std::printf("{\"ok\":false,\"error\":\"%s\"}\n", jesc_out(e.what()).c_str());
    return 1;
  }
}
