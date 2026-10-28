#pragma once
// nativemorph - shared public API of the native core (pck.cpp / patch.cpp).
// Declared once so the desktop CLI and the Android JNI bridge both link
// against the exact same signatures.
#include "pck.hpp"

#include <cstdint>
#include <string>
#include <vector>

namespace nm {

// Parse + validate a PCK v2 (handles .pck directly, .zip bundles, and
// .exe-embedded packs). Throws std::runtime_error with a diagnostic.
void load_pck(PckFile& p, const std::string& input_path);

// Decompress (zstd) one entry payload into memory.
std::vector<uint8_t> read_entry(const std::vector<uint8_t>& pack_bytes,
                                const PckFile& p, const PckEntry& e);

// Re-emit the container as a STANDALONE pack (base 0) with an optional
// settings surgery: entries matching replace_path get the replacement
// payload (project.godot is appended when absent), entries matching
// remove_path are dropped (the converted project.binary case). Untouched
// entries are copied BYTE-EXACT (payloads, md5s, sizes carried over
// verbatim). The replacement payload is stored with a fresh MD5.
void repack(const std::vector<uint8_t>& pack_bytes, const PckFile& p,
            const std::vector<uint8_t>& replacement_settings,
            const std::string& remove_path, const std::string& out_path);

// Re-emit with no entry changes (settings surgery off).
inline void repack(const std::vector<uint8_t>& pack_bytes, const PckFile& p,
                   const std::string& out_path) {
  repack(pack_bytes, p, {}, "", out_path);
}

// Engine detection + portability audit (Unity -> reject, GDExtension audit).
PortabilityReport inspect(const std::vector<uint8_t>& pack_bytes,
                          const PckFile& p);

// Patch the project.godot text with the mobile optimization profile.
bool patch_project_godot(std::string text, const PatchOptions& opts,
                         std::string& project_name_out,
                         std::string& patched_out);

// ---- ECFG binary project settings (project.binary) --------------------
// Godot's default export converts project.godot to a compiled project.binary
// entry; the runtime accepts either (preferring the binary). These helpers
// decode the binary form back into patchable text.

enum class EcfgStatus { Ok, Partial, Failed };

struct EcfgEntry {
  std::string key;   // "application/config/name"
  std::string text;  // rendered Godot text literal ("\"OPEN HOUSE\"")
};

struct EcfgDoc {
  std::vector<EcfgEntry> entries;  // in file order
  std::string name;                // application/config/name (unquoted)
  std::string icon;                // application/config/icon (res:// stripped)
};

// Decode an ECFG blob (the payload of a project.binary entry).
//   Ok       every entry rendered -> ecfg_to_text() may be used
//   Partial  some entries used unsupported variant types (name/icon are
//            still captured); the blob must be kept as-is (fail-safe)
//   Failed   not ECFG-framed at all
EcfgStatus decode_ecfg(const uint8_t* data, size_t len, int engine_major,
                       EcfgDoc& out, std::string& err);

// Render a fully decoded doc as a textual project.godot (ConfigFile syntax
// with the engine's config_version).
std::string ecfg_to_text(const EcfgDoc& doc, int engine_major);

// Result of locating the settings entry of a pack (text or binary form).
struct SettingsResult {
  bool found = false;        // a settings entry existed at all
  bool binary = false;       // the pack stores settings as project.binary
  bool converted = false;    // binary decoded cleanly and was converted
  bool remove_binary = false;  // repack must drop project.binary (converted)
  std::string text;          // settings text ready for patching (may be empty
                             // when the compiled form must be kept as-is)
  std::string name;           // best-effort project name
  std::string icon;          // best-effort icon path (res:// stripped)
  std::string kept_reason;   // why the compiled settings were kept (if so)
};

// Shared import flow (JNI bridge, desktop CLI, host test driver): find
// project.godot / project.binary, prefer text, convert a cleanly decodable
// binary, fail safe to "keep the pack's own compiled settings".
SettingsResult acquire_settings(const std::vector<uint8_t>& pack_bytes,
                                const PckFile& p);

}  // namespace nm
