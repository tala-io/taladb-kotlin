cmake_minimum_required(VERSION 3.22.1)
project(taladb_jni C)

# engine/ is staged by scripts/build-engine.sh or scripts/fetch-engine.sh and
# passed in by taladb/build.gradle.kts.
if(NOT TALADB_ENGINE_DIR)
    message(FATAL_ERROR "TALADB_ENGINE_DIR is not set")
endif()
set(TALADB_FFI_SO "${TALADB_ENGINE_DIR}/jniLibs/${ANDROID_ABI}/libtaladb_ffi.so")
if(NOT EXISTS "${TALADB_FFI_SO}")
    message(FATAL_ERROR "Missing ${TALADB_FFI_SO}. Run scripts/build-engine.sh or scripts/fetch-engine.sh first.")
endif()

add_library(taladb_ffi SHARED IMPORTED)
# IMPORTED_NO_SONAME makes CMake link with -ltaladb_ffi instead of the file's
# full path. Engine builds before ABI version 2 have no SONAME, and for such a
# library the linker records whatever path it was given as the dependency —
# an absolute path on the build machine, which no device can resolve.
set_target_properties(taladb_ffi PROPERTIES
    IMPORTED_LOCATION "${TALADB_FFI_SO}"
    IMPORTED_NO_SONAME TRUE)

add_library(taladb_jni SHARED taladb_jni.c)
target_include_directories(taladb_jni PRIVATE "${TALADB_ENGINE_DIR}/include")
target_compile_options(taladb_jni PRIVATE -std=c11 -Wall -Wextra -Werror)
target_link_libraries(taladb_jni taladb_ffi)

# Google Play requires 16 KB page alignment for 64-bit libraries in apps
# targeting Android 15+, and a 16 KB-page device cannot load anything less.
# NDK r28+ does this by default; stating it keeps an older NDK from silently
# producing a 4 KB-aligned library. CI checks the result with readelf.
target_link_options(taladb_jni PRIVATE "-Wl,-z,max-page-size=16384")
