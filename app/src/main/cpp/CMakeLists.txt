cmake_minimum_required(VERSION 3.22.1)
project(lutty_raw)

# Vendored LibRaw 0.21.4, built as a static lib with no external image codecs.
# DNG deflate compression needs zlib, which the NDK ships (libz); lossless-JPEG DNG
# decoding is handled by LibRaw's own internal decompressor, so libjpeg is not needed.
set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libraw)

file(GLOB_RECURSE LIBRAW_SOURCES ${LIBRAW_DIR}/src/*.cpp)
# The *_ph.cpp files are "placeholder" alternates that redefine the real implementations;
# LibRaw's own Makefile.am compiles the full versions and omits these, so exclude them or the
# link fails with duplicate symbols (dcraw_process, mem_image, ...).
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")

add_library(rawdev SHARED
    raw_jni.cpp
    ${LIBRAW_SOURCES}
)

target_include_directories(rawdev PRIVATE ${LIBRAW_DIR})

target_compile_definitions(rawdev PRIVATE
    LIBRAW_NODLL          # static/local build, no dllimport/export
    USE_ZLIB              # enable deflate-compressed DNG support via NDK libz
    NO_JPEG               # no libjpeg (lossy-DNG previews unused; raw path is internal)
    NO_JASPER
    NO_LCMS
    LIBRAW_NO_MEMPOOL_CHECK
)

# LibRaw is C++; silence the warnings its large codebase emits so the log stays readable.
target_compile_options(rawdev PRIVATE -w -O2 -fexceptions -frtti)

find_library(z-lib z)
find_library(log-lib log)
target_link_libraries(rawdev ${z-lib} ${log-lib})
