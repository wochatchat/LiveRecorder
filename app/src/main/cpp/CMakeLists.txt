# QuickJS (Bellard, 2026-06-04) — vendored C sources for dynamic JS signing.
# Builds libquickjs.so from the interpreter core only; quickjs-libc / CLI tools
# are intentionally excluded (no std/os modules needed for PyExecJS-style eval).
cmake_minimum_required(VERSION 3.22)
project(quickjs C)

set(QUICKJS_SOURCES
    quickjs/quickjs.c
    quickjs/cutils.c
    quickjs/dtoa.c
    quickjs/libregexp.c
    quickjs/libunicode.c
)

add_library(quickjs SHARED ${QUICKJS_SOURCES} quickjs-jni.c)

target_include_directories(quickjs PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)
target_compile_definitions(quickjs PRIVATE
    _GNU_SOURCE
    CONFIG_VERSION="2026-06-04"
)
target_compile_options(quickjs PRIVATE -O2)