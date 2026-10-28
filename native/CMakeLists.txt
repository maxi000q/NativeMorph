cmake_minimum_required(VERSION 3.21)
project(nativemorph LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(Threads REQUIRED)

include(FetchContent)

# ---- Dependencies (pinned; adjust tags for your build host policy) ----
# zstd: needed to DECOMPRESS the project.godot entry when the source pack
# stores it compressed (Godot 4 export default). The repacker stores the
# patched entry UNCOMPRESSED so encoding is never needed.
# Built from the fetched sources (common + decompress is all the pipeline
# uses) so behavior is identical on every host.
FetchContent_Declare(zstd
  GIT_REPOSITORY https://github.com/facebook/zstd.git
  GIT_TAG        v1.5.6)
FetchContent_Populate(zstd)
file(GLOB ZSTD_COMMON ${zstd_SOURCE_DIR}/lib/common/*.c)
file(GLOB ZSTD_DEC ${zstd_SOURCE_DIR}/lib/decompress/*.c)
file(GLOB ZSTD_ASM ${zstd_SOURCE_DIR}/lib/decompress/*.S)  # x86_64 fast loop
add_library(nm_zstd STATIC ${ZSTD_COMMON} ${ZSTD_DEC} ${ZSTD_ASM})
target_include_directories(nm_zstd PUBLIC ${zstd_SOURCE_DIR}/lib)

# libzip: reading user-supplied .zip game bundles (locate the .pck inside).
FetchContent_Declare(libzip
  GIT_REPOSITORY https://github.com/nih-at/libzip.git
  GIT_TAG        v1.10.1)
set(LIBZIP_DO_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_DOC OFF CACHE BOOL "" FORCE)
set(BUILD_REGRESS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(libzip)

# Arm astc-encoder: BC7/DXTC -> ASTC transcode tier (Adreno 619 native format).
# Option keeps quick/CI builds possible (2-core runners): the tex verb is
# reported as unavailable when compiled out; the pipeline verbs are unaffected.
option(NATIVEMORPH_WITH_ASTC "Build the ASTC texture tier" ON)
if(NATIVEMORPH_WITH_ASTC)
  FetchContent_Declare(astcenc
    GIT_REPOSITORY https://github.com/ARM-software/astc-encoder.git
    GIT_TAG        4.8.0)
  FetchContent_MakeAvailable(astcenc)
endif()

# stb: image decode front-end for the texture verb.
FetchContent_Declare(stb
  GIT_REPOSITORY https://github.com/nothings/stb.git
  GIT_TAG        master)
FetchContent_MakeAvailable(stb)

add_executable(nativemorph
  src/main.cpp
  src/pck.cpp
  src/ecfg.cpp
  src/patch.cpp
  src/tex_pool.cpp)

target_include_directories(nativemorph PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/include
  ${zstd_SOURCE_DIR}/lib
  ${stb_SOURCE_DIR})

target_link_libraries(nativemorph PRIVATE
  Threads::Threads
  nm_zstd
  zip)
if(NATIVEMORPH_WITH_ASTC)
  target_link_libraries(nativemorph PRIVATE astcenc::astcenc)
  target_compile_definitions(nativemorph PRIVATE NATIVEMORPH_WITH_ASTC)
endif()

target_compile_options(nativemorph PRIVATE -Wall -Wextra -O2)
