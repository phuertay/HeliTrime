cmake_minimum_required(VERSION 3.22)
project(helitrime_rime)

# Trime v3.3.11 reproducible link line, without Trime's keyboard or plugins.
add_link_options("LINKER:--hash-style=both,--build-id=none")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})
set(LIBRIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/librime")

if(NOT EXISTS "${LIBRIME_DIR}/deps/glog/CMakeLists.txt")
  message(FATAL_ERROR
    "librime deps are missing. From the repo root run: git submodule update --init --recursive rime/third_party/librime")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

include(Boost)

set(WITH_GFLAGS OFF CACHE BOOL "" FORCE)
set(WITH_GTEST OFF CACHE BOOL "" FORCE)
set(WITH_UNWIND OFF CACHE BOOL "" FORCE)
set(WITH_PKGCONFIG OFF CACHE BOOL "" FORCE)
add_subdirectory("${LIBRIME_DIR}/deps/glog" "${CMAKE_BINARY_DIR}/glog" EXCLUDE_FROM_ALL)

set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_CONTRIB OFF CACHE BOOL "" FORCE)
add_subdirectory("${LIBRIME_DIR}/deps/yaml-cpp" "${CMAKE_BINARY_DIR}/yaml-cpp" EXCLUDE_FROM_ALL)

set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${LIBRIME_DIR}/deps/leveldb" "${CMAKE_BINARY_DIR}/leveldb" EXCLUDE_FROM_ALL)

set(MARISA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory("${LIBRIME_DIR}/deps/marisa-trie" "${CMAKE_BINARY_DIR}/marisa" EXCLUDE_FROM_ALL)

set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(ENABLE_GTEST OFF CACHE BOOL "" FORCE)
set(ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)
set(LIBMARISA marisa CACHE FILEPATH "" FORCE)
add_subdirectory("${LIBRIME_DIR}/deps/opencc" "${CMAKE_BINARY_DIR}/opencc" EXCLUDE_FROM_ALL)
# librime includes <opencc/Config.hpp>. OpenCC does not install that layout in-tree.
file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/include/opencc")
file(GLOB OPENCC_HEADERS
  "${LIBRIME_DIR}/deps/opencc/src/*.hpp"
  "${LIBRIME_DIR}/deps/opencc/src/*.h"
  "${CMAKE_BINARY_DIR}/opencc/src/opencc_config.h")
file(COPY ${OPENCC_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opencc")
include_directories("${CMAKE_BINARY_DIR}/include")

set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_LOGGING ON CACHE BOOL "" FORCE)
# Boost's modular layout keeps headers in libs/<name>/include. librime's
# find_package(Boost) does not add those header-only roots on Android.
file(GLOB BOOST_HEADER_ROOTS LIST_DIRECTORIES true "${BOOST_SRC}/libs/*/include")
include_directories(${BOOST_HEADER_ROOTS})
add_subdirectory("${LIBRIME_DIR}" "${CMAKE_BINARY_DIR}/librime" EXCLUDE_FROM_ALL)
if(TARGET rime-static)
  target_compile_options(rime-static PRIVATE "-Wno-error=deprecated-declarations")
endif()

# GNU libiconv as a shared library (LGPL). Not folded into librime.
include(ExternalProject)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(ICONV_HOST armv7a-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
  set(ICONV_HOST aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "x86")
  set(ICONV_HOST i686-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
  set(ICONV_HOST x86_64-linux-android)
else()
  set(ICONV_HOST "")
endif()

set(ICONV_PREFIX "${CMAKE_BINARY_DIR}/iconv-prefix")
string(REPLACE "android-" "" ICONV_API "${ANDROID_PLATFORM}")
set(ICONV_CC "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/${ICONV_HOST}${ICONV_API}-clang")
ExternalProject_Add(libiconv_external
  URL https://ftp.gnu.org/pub/gnu/libiconv/libiconv-1.17.tar.gz
  URL_HASH SHA256=8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313
  CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env "CC=${ICONV_CC}" <SOURCE_DIR>/configure
    --host=${ICONV_HOST}
    --prefix=${ICONV_PREFIX}
    --enable-shared
    --disable-static
    --disable-nls
  BUILD_COMMAND make -j4
  INSTALL_COMMAND make install
  BUILD_BYPRODUCTS "${ICONV_PREFIX}/lib/libiconv.so"
)

add_library(iconv SHARED IMPORTED GLOBAL)
set_target_properties(iconv PROPERTIES IMPORTED_LOCATION "${ICONV_PREFIX}/lib/libiconv.so")
add_dependencies(iconv libiconv_external)

add_library(rime_jni SHARED rime_jni.cc)
target_include_directories(rime_jni PRIVATE
  "${LIBRIME_DIR}/src"
  "${CMAKE_BINARY_DIR}/librime/src"
)
# Whole-archive keeps librime's static module registrars. Without it the
# linker drops them and RimeInitialize walks a null module name.
target_link_libraries(rime_jni
  -Wl,--whole-archive
  rime-static
  -Wl,--no-whole-archive
  iconv
  log
)
add_dependencies(rime_jni libiconv_external)

# Ship libiconv.so next to librime_jni.so so the APK packages it.
add_custom_command(TARGET rime_jni POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${ICONV_PREFIX}/lib/libiconv.so"
    "$<TARGET_FILE_DIR:rime_jni>/libiconv.so"
)
