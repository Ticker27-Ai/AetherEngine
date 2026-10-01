cmake_minimum_required(VERSION 3.22.1)
project(aether-native CXX)

# ---------------------------------------------------------------------------
# AetherEngine native layer
#
#   Kotlin  --JNI-->  libaether-native.so  --C ABI-->  libaether_vfs.a (Rust)
#
# The Rust staticlib is produced outside Gradle by tools/build-rust.sh
# (cargo-ndk). AGP passes -DAETHER_VFS_LIB=<path> per ABI; see
# core/host/build.gradle.kts.
#
# Sources:
#   hidden_api.cpp     — ART hidden-API unseal, with runtime calibration
#   vfs_bridge.cpp     — C ABI <-> JNI shim over the Rust policy engine
#   native_extract.cpp — ELF validation + exec-bit handling for guest .so files
#   aether_native.cpp  — JNI_OnLoad / RegisterNatives
# ---------------------------------------------------------------------------

set(AETHER_VFS_LIB "" CACHE FILEPATH "Path to libaether_vfs.a for the current ABI")

if(NOT AETHER_VFS_LIB)
  message(FATAL_ERROR
    "AETHER_VFS_LIB is not set. Build the Rust policy engine first:\n"
    "    ./tools/build-rust.sh --release\n"
    "Expected artifact: core/vfs/target/<triple>/release/libaether_vfs.a")
endif()

if(NOT EXISTS "${AETHER_VFS_LIB}")
  message(FATAL_ERROR
    "Rust staticlib not found at: ${AETHER_VFS_LIB}\n"
    "Run: ./tools/build-rust.sh --release  (or --abi ${ANDROID_ABI} --release)")
endif()

message(STATUS "aether: linking Rust policy engine ${AETHER_VFS_LIB}")

add_library(aether-native SHARED
  aether_native.cpp
  hidden_api.cpp
  vfs_bridge.cpp
  native_extract.cpp
)

target_include_directories(aether-native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(aether-native PRIVATE
  -Wall
  -Wextra
  -Wno-unused-parameter
  # We are a library that must never crash the host process: an unexpected
  # switch value or a bad format string is a hard error at review time.
  -Werror=switch
  -Werror=format
)

target_link_options(aether-native PRIVATE
  # Keep our dependency surface invisible to the guest: only JNI_OnLoad is
  # exported (everything else is -fvisibility=hidden from Gradle).
  "-Wl,--exclude-libs,ALL"
  "-Wl,--gc-sections"
)

target_link_libraries(aether-native
  ${AETHER_VFS_LIB}
  log
)
