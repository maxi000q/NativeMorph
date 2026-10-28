// nativemorph - project.godot settings injector (repack-time, entry-level).
//
// This is the honest form of "rewrite the renderer at the binary level": the
// settings file is a TEXT ENTRY inside the pack; we patch the text and re-emit
// the entry. No engine binaries are touched (they are replaced wholesale by the
// prebuilt ARM64 template APK of the matching engine version).
//
// Why these settings, for Adreno 619 (SM6125-class, Vulkan 1.1):
//   - rendering_method: "mobile" (Vulkan mobile pipeline) or
//     "gl_compatibility" (GLES3). Forward+ only features (SSR, SDFGI,
//     volumetric GI, volumetric fog in the F+ path) are NOT IMPLEMENTED in
//     either, so switching strips them by construction.
//   - scaling_3d/scale + optional FSR: Adreno 619 has no fragment-rate shading
//     (no dynamic foveated rendering path); static render-scale (+FSR1) is the
//     supported resolution story and the single biggest fill-rate win.
//   - MSAA and directional-shadow atlas: bandwidth-first budget on a
//     1920x1200 panel.
//
// The profile is caller-driven (PatchOptions): the web tier offers
// Fidelity (parity) / Balanced / Performance presets and custom overrides.
//
// Parsing rules: ConfigFile INI-like ([section], key = value). Unknown lines
// are preserved verbatim. Existing keys are replaced in place; missing keys
// are appended to their section; missing sections are appended at EOF.

#include <cstdint>
#include <cstdio>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "pck.hpp"

namespace nm {

namespace {

bool is_section(const std::string& l, std::string& name) {
  size_t a = l.find('[');
  if (a == std::string::npos) return false;
  size_t b = l.find(']', a);
  if (b == std::string::npos) return false;
  name = l.substr(a + 1, b - a - 1);
  return true;
}

// Format a scale factor without trailing zeros ("0.75", "1").
std::string fmt_scale(double v) {
  char buf[32];
  std::snprintf(buf, sizeof buf, "%.4g", v);
  return buf;
}

// The mobile-adaptation profile, parameterized by the caller's PatchOptions.
// Keys follow the project-settings naming of the TARGET engine major:
//   Godot 4 (rendering_method / scaling_3d / msaa_3d / directional_shadow)
//   Godot 3 (fallback_to_gles2; the engine has no render-scale pipeline, so
//            the battery profile cannot trade resolution there - it stays
//            same-graphics by construction).
//
// Negative msaa/shadows values mean "keep the game's authored setting"
// (same-graphics fidelity profile): the key is simply not emitted.
std::vector<std::pair<std::string, std::string>> build_profile(
    const PatchOptions& o) {
  if (o.engine_major == 3) {
    std::vector<std::pair<std::string, std::string>> out = {
        // Robustness only: kicks in if GLES3 is unavailable; Adreno 619 has
        // GLES 3.2 so visuals stay exactly as authored.
        {"rendering/quality/driver/fallback_to_gles2", "true"},
    };
    if (o.msaa >= 0)
      out.push_back({"rendering/quality/filters/msaa",
                     std::to_string(o.msaa)});
    return out;
  }
  std::vector<std::pair<std::string, std::string>> out;
  // Render-scale keys only when a profile actually rescales (battery).
  // Same-graphics fidelity leaves the game's own scaling untouched.
  if (o.scale < 1.0 || o.fsr) {
    out.push_back({"rendering/scaling_3d/scale", fmt_scale(o.scale)});
    // mode: 0 = bilinear, 1 = FSR1 (Godot 4.2 enum order)
    out.push_back({"rendering/scaling_3d/mode",
                   (o.fsr && o.scale < 1.0) ? "1" : "0"});
  }
  // "keep" = same-graphics fidelity: leave rendering_method exactly as the
  // game authored it (Forward+ games stay Forward+; the 4.7.2 runtime ships
  // all three pipelines). Only emit the override keys when a profile
  // explicitly requests mobile or gl_compatibility.
  if (o.renderer != "keep" && !o.renderer.empty()) {
    out.push_back({"rendering/renderer/rendering_method", "\"" + o.renderer + "\""});
    out.push_back({"rendering/renderer/rendering_method.mobile", "\"" + o.renderer + "\""});
  }
  if (o.msaa >= 0)
    out.push_back({"rendering/anti_aliasing/quality/msaa_3d",
                   std::to_string(o.msaa)});
  if (o.shadows > 0) {
    out.push_back({"rendering/lights_and_shadows/directional_shadow/size",
                   std::to_string(o.shadows)});
    out.push_back({"rendering/lights_and_shadows/directional_shadow/"
                   "soft_shadow_filter_quality",
                   "1"});
  }
  // Glow clamp only for the battery-tuned profiles; same-graphics keeps the
  // authored glow levels untouched.
  if (o.renderer != "keep" && !o.renderer.empty())
    out.push_back({"rendering/environment/glow/glow_level/3", "0.0"});
  return out;
}

// Godot's ConfigFile: the section is the FIRST path component and the key
// is the ENTIRE remainder (keys contain slashes, e.g. under [rendering] the
// key is "renderer/rendering_method").
std::string section_of(const std::string& key) {
  size_t slash = key.find('/');
  if (slash == 0 || slash == std::string::npos) return "";
  return key.substr(0, slash);
}

std::string leaf_of(const std::string& key) {
  size_t slash = key.find('/');
  if (slash == std::string::npos) return key;
  return key.substr(slash + 1);
}

}  // namespace

// Returns the patched text plus the project's config/name (for APK labels).
bool patch_project_godot(std::string text, const PatchOptions& opts,
                          std::string& project_name_out,
                          std::string& patched_out) {
  const auto profile = build_profile(opts);

  // Read config/name first (used for the APK label). Escape-aware: names may
  // contain \" \\ \n etc.; the value ends at the first UNESCAPED quote.
  {
    const std::string key = "config/name=";
    std::istringstream iss(text);
    std::string line;
    while (std::getline(iss, line)) {
      size_t pos = line.find(key);
      if (pos == std::string::npos || line.find(';') < pos) continue;
      size_t q1 = line.find('"', pos + key.size());
      if (q1 == std::string::npos) continue;
      std::string v;
      for (size_t i = q1 + 1; i < line.size(); i++) {
        if (line[i] == '\\' && i + 1 < line.size()) {
          const char n = line[++i];
          if (n == 'n') v += '\n';
          else if (n == 't') v += '\t';
          else if (n == 'r') v += '\r';
          else v += n;  // \" \\ and anything else
        } else if (line[i] == '"') {
          break;  // closing quote
        } else {
          v += line[i];
        }
      }
      project_name_out = v;
    }
  }

  // Split into lines, index sections.
  std::vector<std::string> lines;
  {
    std::istringstream iss(text);
    std::string line;
    while (std::getline(iss, line)) lines.push_back(line);
    if (!text.empty() && text.back() != '\n') lines.push_back("");
  }

  auto reindex = [&](std::map<std::string, size_t>& section_line) {
    section_line.clear();
    std::string cur;
    for (size_t i = 0; i < lines.size(); i++) {
      std::string sec;
      if (is_section(lines[i], sec)) { cur = sec; section_line[cur] = i; }
    }
  };

  std::map<std::string, size_t> section_line;
  reindex(section_line);

  // Replace existing keys / remember what's present.
  std::map<std::string, bool> present;
  {
    std::string cur;
    for (size_t i = 0; i < lines.size(); i++) {
      std::string sec;
      if (is_section(lines[i], sec)) { cur = sec; continue; }
      if (cur.empty()) continue;
      for (const auto& kv : profile) {
        if (section_of(kv.first) != cur) continue;
        const std::string needle = leaf_of(kv.first) + "=";
        size_t pos = lines[i].find(needle);
        if (pos != std::string::npos &&
            (pos == 0 || lines[i][pos - 1] == ' ' ||
             lines[i][pos - 1] == '\t')) {
          std::string indent = lines[i].substr(0, pos);
          size_t semi = lines[i].find(";", pos);
          std::string tail =
              (semi == std::string::npos) ? "" : lines[i].substr(semi);
          lines[i] =
              indent + needle + kv.second + (tail.empty() ? "" : " " + tail);
          present[kv.first] = true;
        }
      }
    }
  }

  // Append missing keys under their section (create section at EOF if needed).
  for (const auto& kv : profile) {
    if (present[kv.first]) continue;
    std::string sec = section_of(kv.first);
    auto it = section_line.find(sec);
    if (it == section_line.end()) {
      lines.push_back("[" + sec + "]");
      section_line[sec] = lines.size() - 1;
      it = section_line.find(sec);
    }
    size_t insert_at = lines.size();
    for (size_t j = it->second + 1; j < lines.size(); j++) {
      std::string s2;
      if (is_section(lines[j], s2)) { insert_at = j; break; }
      insert_at = j + 1;
    }
    lines.insert(lines.begin() + insert_at,
                 leaf_of(kv.first) + "=" + kv.second);
    reindex(section_line);
  }

  std::ostringstream oss;
  for (size_t i = 0; i < lines.size(); i++) {
    oss << lines[i];
    if (i + 1 < lines.size()) oss << "\n";
  }
  patched_out = oss.str();
  return true;
}

}  // namespace nm
