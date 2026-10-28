cmake_minimum_required(VERSION 3.22)
project(nativemorph LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Shared core sources live at the repo root (single source of truth: the
# desktop CLI and this JNI bridge compile the SAME pck/patch translation
# units).
set(NATIVE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../../native)

# ---- libzip (archive inputs: .zip bundles / .apk game exports) -----------
FetchContent_Declare(libzip
  GIT_REPOSITORY https://github.com/nih-at/libzip.git
  GIT_TAG        v1.10.1)
set(LIBZIP_DO_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_DOC OFF CACHE BOOL "" FORCE)
set(BUILD_REGRESS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENSSL OFF CACHE BOOL "" FORCE)
set(ENABLE_COMMONCRYPTO OFF CACHE BOOL "" FORCE)
set(ENABLE_GNUTLS OFF CACHE BOOL "" FORCE)
set(ENABLE_MBEDTLS OFF CACHE BOOL "" FORCE)
set(ENABLE_ZSTD OFF CACHE BOOL "" FORCE)
set(ENABLE_LZMA OFF CACHE BOOL "" FORCE)
set(ENABLE_BZIP2 OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(libzip)

# ---- the import optimizer (runs on-device at game import time) -----------
add_library(nativemorph SHARED
  jni_import.cpp
  ${NATIVE_DIR}/src/pck.cpp
  ${NATIVE_DIR}/src/ecfg.cpp
  ${NATIVE_DIR}/src/patch.cpp)

target_include_directories(nativemorph PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}
  ${NATIVE_DIR}/include)

target_link_libraries(nativemorph PRIVATE
  zip
  log)

target_compile_options(nativemorph PRIVATE -Wall -Wextra -O2)

# ---- JNI registration shim for the relocated Godot 3.6.2 runtime ---------
# Binds the engine's original Java_org_godotengine_godot_* exported symbols
# onto the relocated org.nativemorph.god3x classes via RegisterNatives.
add_library(nm_godot3_jni SHARED nm_godot3_jni.cpp)
target_link_libraries(nm_godot3_jni PRIVATE log dl)
target_compile_options(nm_godot3_jni PRIVATE -Wall -Wextra -O2)
