// nativemorph - topology-aware ASTC encode tool (texture verb).
//
// This is the original spec's Section 1.3 ("Adreno translation & texture
// compression layer"), relocated to where transcoding can actually run:
// AHEAD OF TIME, either on the build server or on-device via Termux.
//
// Core-to-role mapping (SD695 / Kryo 660):
//   CPU topology is discovered at runtime from
//   /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq
//   - top frequency bin  -> encode placement (Cortex-A78 x2 @ 2.2 GHz on the
//                          tablet; the host's P-cores on the build server)
//   - remaining cores    -> I/O + container work (Cortex-A55 x6 @ 1.7 GHz)
//   The calling thread is pinned to the encode set with
//   pthread_setaffinity_np(); astcenc's internal thread count is sized from
//   that set. On the Snapdragon 695 this is exactly the A78/A55 split.
//
// v1 surface: `nativemorph tex in.png -o out.astc` (loose assets; this is also
// the entry point tier-1.5 will call per-.ctex once container surgery lands).
//
// v1.1 plan (in-pack .ctex surgery): Godot's CompressedTexture2D on-disk
// format must be parsed per template version (see
// scene/resources/compressed_texture.cpp in the engine source). v1 therefore
// CENSUSES .ctex entries in `inspect` and fails closed rather than guessing:
// no .ctex payload is rewritten without a verified layout. For owned projects
// the official re-export path (tools/reexport_mobile.sh) achieves the same
// conversion with the engine's own importer.

#ifdef NATIVEMORPH_WITH_ASTC

#define STB_IMAGE_IMPLEMENTATION
#include <stb_image.h>

#include <astcenc.h>

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#include <sched.h>
#endif

namespace nm {

namespace {

// ---------------- CPU topology discovery ----------------
struct Topology {
  std::vector<unsigned> encode_cores;  // top frequency bin = A78s / P-cores
  std::vector<unsigned> io_cores;     // rest = A55s / E-cores
};

Topology detect_topology() {
  Topology t;
  unsigned n = std::thread::hardware_concurrency();
  n = std::max(1u, n);
  std::vector<std::pair<unsigned long, unsigned>> freq;  // (max_freq, cpu)
  for (unsigned c = 0; c < n; c++) {
    std::string sysfs = "/sys/devices/system/cpu/cpu" + std::to_string(c) +
                        "/cpufreq/cpuinfo_max_freq";
    std::ifstream f(sysfs);
    unsigned long hz = 0;
    if (f >> hz) freq.emplace_back(hz, c);
  }
  if (freq.empty()) {
    // No cpufreq view (container / non-Linux): first half encodes.
    for (unsigned c = 0; c < n; c++)
      (c < n / 2 ? t.encode_cores : t.io_cores).push_back(c);
    return t;
  }
  unsigned long top = 0;
  for (auto& [hz, c] : freq) top = std::max(top, hz);
  for (auto& [hz, c] : freq) {
    // Bin cores within 10% of the top frequency as "performance".
    if (hz >= top - top / 10) t.encode_cores.push_back(c);
    else t.io_cores.push_back(c);
  }
  if (t.encode_cores.empty()) t.encode_cores.push_back(freq.back().second);
  return t;
}

void pin_thread(const std::vector<unsigned>& cpus) {
#ifdef _WIN32
  (void)cpus;
#else
  cpu_set_t set;
  CPU_ZERO(&set);
  for (unsigned c : cpus) CPU_SET(c, &set);
  pthread_setaffinity_np(pthread_self(), sizeof(set), &set);
#endif
}

struct AstcImage {
  int w = 0, h = 0;
  std::vector<uint8_t> rgba;  // 8-bit RGBA
};

bool load_rgba(const std::string& path, AstcImage& img) {
  int n = 0;
  stbi_uc* px = stbi_load(path.c_str(), &img.w, &img.h, &n, 4);
  if (!px) return false;
  img.rgba.assign(px, px + size_t(img.w) * img.h * 4);
  stbi_image_free(px);
  return true;
}

// Encodes RGBA8 -> ASTC blocks (6x6 default: good quality/space middle
// ground for UI and albedo sets on Adreno; pass 8x8 for speed-critical).
bool astc_encode_file(const std::string& in, const std::string& out,
                      unsigned block_x, unsigned block_y, bool verbose) {
  Topology topo = detect_topology();
  if (verbose) {
    std::fprintf(stderr, "[topology] encode cores:");
    for (unsigned c : topo.encode_cores) std::fprintf(stderr, " %u", c);
    std::fprintf(stderr, " | io cores:");
    for (unsigned c : topo.io_cores) std::fprintf(stderr, " %u", c);
    std::fprintf(stderr, "\n");
  }

  AstcImage img;
  if (!load_rgba(in, img)) {
    std::fprintf(stderr, "tex: cannot decode %s\n", in.c_str());
    return false;
  }

  // LDR fast profile: the mobile-budget operating point on Adreno 619.
  astcenc_config cfg{};
  if (astcenc_config_init(ASTCENC_PRF_L_FAST, block_x, block_y, 0, 0.0f,
                          &cfg) != ASTCENC_SUCCESS) {
    std::fprintf(stderr, "tex: astcenc config init failed\n");
    return false;
  }

  astcenc_context* ctx = nullptr;
  unsigned threads = std::max<unsigned>(1, unsigned(topo.encode_cores.size()));
  if (astcenc_context_alloc(&cfg, int(threads), &ctx) != ASTCENC_SUCCESS) {
    std::fprintf(stderr, "tex: astcenc context alloc failed\n");
    return false;
  }

  astcenc_image image{};
  image.dim_x = uint32_t(img.w);
  image.dim_y = uint32_t(img.h);
  image.dim_z = 1;
  image.data_type = ASTCENC_TYPE_U8;
  void* slices[1] = {img.rgba.data()};
  image.data = slices;

  astcenc_swizzle swz{ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B,
                      ASTCENC_SWZ_A};

  const size_t blocks_x = (size_t(img.w) + block_x - 1) / block_x;
  const size_t blocks_y = (size_t(img.h) + block_y - 1) / block_y;
  const size_t out_size = blocks_x * blocks_y * 16;
  std::vector<uint8_t> enc(out_size);

  pin_thread(topo.encode_cores);  // astcenc workers inherit our affinity set
  astcenc_error err =
      astcenc_compress_image(ctx, &image, &swz, enc.data(), out_size, 0);
  astcenc_context_free(ctx, ASTCENC_CONTEXT_FREE_DEFAULT);
  if (err != ASTCENC_SUCCESS) {
    std::fprintf(stderr, "tex: compress error %d (%s)\n", int(err),
                 astcenc_get_error_string(err));
    return false;
  }

  std::ofstream f(out, std::ios::binary | std::ios::trunc);
  if (!f) return false;
  // ASTC file header: magic, block dims, image dims (LE), fixed 1s.
  uint8_t hdr[16];
  hdr[0] = 0x13; hdr[1] = 0xAB; hdr[2] = 0xA4; hdr[3] = 0x5C;
  hdr[4] = uint8_t(block_x); hdr[5] = uint8_t(block_y); hdr[6] = 1;
  hdr[7] = uint8_t(img.w);  hdr[8]  = uint8_t(uint32_t(img.w) >> 8);
  hdr[9] = uint8_t(img.h);  hdr[10] = uint8_t(uint32_t(img.h) >> 8);
  for (int i = 11; i < 16; i++) hdr[i] = 1;
  f.write(reinterpret_cast<const char*>(hdr), 16);
  f.write(reinterpret_cast<const char*>(enc.data()), std::streamsize(out_size));
  if (verbose)
    std::fprintf(stderr, "[astc] %dx%d -> %zu bytes (%zux%zu blocks)\n",
                 img.w, img.h, out_size + 16, blocks_x, blocks_y);
  return true;
}

}  // namespace

int run_tex_verb(int argc, char** argv) {
  std::string in, out = "out.astc";
  unsigned bx = 6, by = 6;
  bool verbose = false;
  for (int i = 2; i < argc; i++) {
    std::string a = argv[i];
    if (a == "-o" && i + 1 < argc) out = argv[++i];
    else if (a == "--block" && i + 2 < argc) {
      bx = unsigned(std::stoi(argv[++i]));
      by = unsigned(std::stoi(argv[++i]));
    } else if (a == "-v") verbose = true;
    else if (in.empty()) in = a;
  }
  if (in.empty()) {
    std::fprintf(stderr,
                  "usage: nativemorph tex <image.png|jpg> -o out.astc "
                  "[--block 6 6] [-v]\n");
    return 2;
  }
  return astc_encode_file(in, out, bx, by, verbose) ? 0 : 1;
}

}  // namespace nm

#else  // !NATIVEMORPH_WITH_ASTC

#include <cstdio>

namespace nm {
int run_tex_verb(int argc, char** argv) {
  (void)argc; (void)argv;
  std::fprintf(stderr,
                "tex: not built (configure with -DNATIVEMORPH_WITH_ASTC=ON)\n");
  return 2;
}
}  // namespace nm

#endif  // NATIVEMORPH_WITH_ASTC
