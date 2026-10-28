#pragma once
// nativemorph - PCK container model + portability report types.
//
// Layout verified against the engine readers/writers:
//   core/io/file_access_pack.cpp   (Godot 4.7 master: formats 2/3/4 + 3.5.3: format 1)
//   editor/export/editor_export_platform.cpp (pack writer, 4.7 master)
// All integers are LITTLE-ENDIAN.
//
// PCK FORMAT 1 (Godot 3.0 – 3.6):
//   u32 magic, u32 format(1), u32 ver_major/minor/patch,
//   u32 reserved[16]              (no pack_flags, no file_base)
//   u32 file_count, then entries:
//     u32 path_len, u8 path[], u64 offset(ABSOLUTE in file), u64 size,
//     u8 md5[16]
//
// PCK FORMAT 2 (Godot 4.0 – 4.3):
//   u32 magic, u32 format(2), u32 ver_major/minor/patch,
//   u32 pack_flags, u64 file_base, u32 reserved[16],
//   u32 file_count, entries:
//     u32 path_len(+pad to 4), path(+pad), u64 offset(RELATIVE to file_base),
//     u64 size, u8 md5[16], u32 flags
//   file_base is ABSOLUTE in the file unless pack_flags & REL_FILEBASE
//   (then it is relative to the pack start - e.g. embedded in an .exe).
//
// PCK FORMAT 3/4 (Godot 4.4+): same entry layout as format 2, but after
//   file_base comes u64 dir_offset (relative to pack start), then a 32-byte
//   reserved/salt region; the directory lives AT dir_offset (not right after
//   the header). file_base is ALWAYS relative to the pack start.
//
// There is no per-entry compression in any of these formats (the engine
// readers have no decompression step); size == stored size.
//
// Self-contained executables: the pack is appended to the binary, then
//   u64 pack_size (LE), u32 magic. Both 3.x and 4.x use this trailer.
#include <cstdint>
#include <string>
#include <vector>

namespace nm {

enum class EngineKind { GodotNative, UnityDetected, Unknown };

// Optimizer profile. Fidelity keeps the game's visuals as authored; the
// fields parameterize the project.godot patch (see patch.cpp).
struct PatchOptions {
  std::string renderer = "keep";      // "keep" (authored) | "mobile" | "gl_compatibility"
  double scale = 1.0;               // 3D render scale (0.5 .. 1.0)
  bool fsr = false;                 // FSR upscaling when scale < 1.0
  int msaa = -1;                     // <0 = keep authored
  int shadows = -1;                // <0 = keep authored
  std::string layout = "gamepad";   // touch overlay layout id
  int engine_major = 4;             // 3 => use Godot 3.x project key names
};

inline bool valid_layout(const std::string& s) {
  return s == "gamepad" || s == "minimal" || s == "retro";
}
inline bool valid_renderer(const std::string& s) {
  return s == "keep" || s == "mobile" || s == "gl_compatibility";
}

enum class Stage {
  Inspect, Patch, Repack, Package, Sign, Done, Error
};

inline const char* stage_name(Stage s) {
  switch (s) {
    case Stage::Inspect: return "inspect";
    case Stage::Patch:   return "patch";
    case Stage::Repack:  return "repack";
    case Stage::Package: return "package";
    case Stage::Sign:    return "sign";
    case Stage::Done:    return "done";
    case Stage::Error:   return "error";
  }
  return "?";
}

// ---- pack-level flags (4.x) ----
namespace packflag {
constexpr uint32_t DIR_ENCRYPTED  = 1u << 0;  // directory is AES-encrypted
constexpr uint32_t REL_FILEBASE   = 1u << 1;  // file_base relative to pack start
constexpr uint32_t SPARSE_BUNDLE  = 1u << 2;  // sparse/DLC bundle
}  // namespace packflag

// ---- entry-level flags (4.x) ----
namespace pckflag {
constexpr uint32_t ENCRYPTED = 1u << 0;
constexpr uint32_t REMOVAL   = 1u << 1;  // removal record (no payload)
constexpr uint32_t DELTA     = 1u << 2;  // delta patch over a base pack
}  // namespace pckflag

constexpr uint32_t PCK_MAGIC = 0x43504447u;  // "GDPC" read as LE u32

struct PckEntry {
  std::string path;        // clean relative path ("project.godot")
  uint64_t offset = 0;     // ABSOLUTE on-disk offset (in the input file)
  uint64_t size = 0;       // stored == logical size (no compression)
  uint32_t flags = 0;       // pckflag::* (4.x only)
  uint8_t  md5[16] = {};
};

struct PckFile {
  uint32_t format = 0;      // 1 | 2 | 3 | 4
  uint32_t ver_major = 0, ver_minor = 0, ver_patch = 0;
  uint32_t pack_flags = 0;
  uint64_t file_base = 0;  // effective (absolute) payload base in the input
  std::vector<PckEntry> entries;
  std::string source_path;
  std::string mapped_path;

  bool encrypted_dir() const { return (pack_flags & packflag::DIR_ENCRYPTED) != 0; }
  // Which bundled runtime serves this pack.
  int runtime_flavor() const {
    return format == 1 ? 3 : 4;  // format 1 => Godot 3.x runtime
  }
};

struct PortabilityNote {
  std::string code;     // machine-readable, e.g. GDEXT_X86_64, ENCRYPTED_FILES
  std::string message;  // human-readable, surfaced in the app UI
  bool blocker = true;
};

struct TextureCensus {
  size_t ctex_total = 0;
  size_t ctex_vram = 0;
  size_t ctex_lossless = 0;
  size_t loose_png = 0, loose_jpg = 0, basis = 0;
  std::string sample_format_magic;
};

struct PortabilityReport {
  EngineKind engine = EngineKind::Unknown;
  std::string engine_version;   // "4.4.1"
  std::string project_name;
  bool native_loadable = false;
  std::vector<PortabilityNote> notes;
  TextureCensus textures;
  size_t files_total = 0;
};

// Human-readable label for a pack format number ("1" -> "Godot 3.x").
inline const char* format_label(uint32_t fmt) {
  switch (fmt) {
    case 1: return "Godot 3.x (PCK v1)";
    case 2: return "Godot 4.0-4.3 (PCK v2)";
    case 3: return "Godot 4.4+ (PCK v3)";
    case 4: return "Godot 4.7+ (PCK v4)";
    default: return "unknown";
  }
}

}  // namespace nm
