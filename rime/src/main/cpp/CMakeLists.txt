cmake_minimum_required(VERSION 3.22)
project(helitrime_rime)

# AGP configures the debug variant as Debug, which compiles librime at -O0
# (about 11 MB after strip). The debug APK is the package CI ships, so the
# engine is Release. Java minify stays on its own switch.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

if(NOT RIME_DEPS_DIR)
  if(EXISTS "/tmp/rime-src/librime/CMakeLists.txt")
    set(RIME_DEPS_DIR "/tmp/rime-src")
  else()
    set(RIME_DEPS_DIR "${CMAKE_SOURCE_DIR}/../../../../third_party/rime")
  endif()
endif()
if(NOT EXISTS "${RIME_DEPS_DIR}/librime/CMakeLists.txt")
  # ponytail: no librime tree on this machine. AGP still packages
  # src/main/jniLibs/${ANDROID_ABI}. Fetch sources to compile instead.
  if(EXISTS "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librime_jni.so")
    message(STATUS "librime sources missing; packaging prebuilt jniLibs/${ANDROID_ABI}")
    return()
  endif()
  message(FATAL_ERROR "librime sources missing at ${RIME_DEPS_DIR}. Run tools/fetch_rime_sources.bash")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

# libiconv stays a shared library (LGPL). Configure generates config.h for this ABI.
set(ICONV_SRC "${RIME_DEPS_DIR}/libiconv")
set(ICONV_BUILD "${CMAKE_BINARY_DIR}/libiconv")
set(ICONV_CFLAGS "${CMAKE_C_FLAGS}")
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  set(ICONV_CFLAGS "${ICONV_CFLAGS} ${CMAKE_C_FLAGS_DEBUG}")
else()
  set(ICONV_CFLAGS "${ICONV_CFLAGS} ${CMAKE_C_FLAGS_RELEASE}")
endif()
file(MAKE_DIRECTORY "${ICONV_BUILD}")
if(NOT EXISTS "${ICONV_BUILD}/lib/config.h")
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env
            "CC=${CMAKE_C_COMPILER}"
            "CFLAGS=${ICONV_CFLAGS}"
            "LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS}"
            "${ICONV_SRC}/configure"
            "--host=aarch64-linux-android"
            "--build=x86_64-pc-linux-gnu"
            "--enable-shared"
            "--disable-static"
            "--prefix=${ICONV_BUILD}/prefix"
    WORKING_DIRECTORY "${ICONV_BUILD}"
    RESULT_VARIABLE iconv_configure_result
  )
  if(NOT iconv_configure_result EQUAL 0)
    message(FATAL_ERROR "libiconv configure failed: ${iconv_configure_result}")
  endif()
endif()
# nl_langinfo is hidden below Android API 26. The charset name falls back
# without it, which is enough for UTF-8 pinyin.
foreach(iconv_config
    "${ICONV_BUILD}/config.h"
    "${ICONV_BUILD}/lib/config.h"
    "${ICONV_BUILD}/libcharset/config.h")
  if(EXISTS "${iconv_config}")
    file(READ "${iconv_config}" iconv_config_text)
    string(REPLACE "#define HAVE_LANGINFO_CODESET 1" "#define HAVE_LANGINFO_CODESET 0"
           iconv_config_text "${iconv_config_text}")
    file(WRITE "${iconv_config}" "${iconv_config_text}")
  endif()
endforeach()
add_library(iconv SHARED
  "${ICONV_SRC}/lib/iconv.c"
  "${ICONV_SRC}/libcharset/lib/localcharset.c"
  "${ICONV_SRC}/lib/compat.c")
target_include_directories(iconv PRIVATE
  "${ICONV_BUILD}"
  "${ICONV_BUILD}/lib"
  "${ICONV_BUILD}/include"
  "${ICONV_BUILD}/libcharset/include"
  "${ICONV_SRC}/lib"
  "${ICONV_SRC}/include"
  "${ICONV_SRC}/libcharset/include")
target_compile_definitions(iconv PRIVATE BUILDING_LIBICONV BUILDING_LIBCHARSET HAVE_CONFIG_H "LIBDIR=\"/usr/lib\"")
target_compile_options(iconv PRIVATE -fvisibility=hidden)

set(BOOST_INCLUDE_LIBRARIES algorithm crc dll interprocess range regex scope_exit signals2 utility uuid)
add_subdirectory("${RIME_DEPS_DIR}/boost" "${CMAKE_BINARY_DIR}/boost" EXCLUDE_FROM_ALL)

set(WITH_GFLAGS OFF CACHE BOOL "" FORCE)
set(WITH_GTEST OFF CACHE BOOL "" FORCE)
set(WITH_PKGCONFIG OFF CACHE BOOL "" FORCE)
set(WITH_SYMBOLIZE OFF CACHE BOOL "" FORCE)
set(WITH_UNWIND none CACHE STRING "" FORCE)
add_subdirectory("${RIME_DEPS_DIR}/glog" "${CMAKE_BINARY_DIR}/glog" EXCLUDE_FROM_ALL)

set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
set(YAML_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_DEPS_DIR}/yaml-cpp" "${CMAKE_BINARY_DIR}/yaml-cpp" EXCLUDE_FROM_ALL)

set(SNAPPY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(SNAPPY_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_DEPS_DIR}/snappy" "${CMAKE_BINARY_DIR}/snappy" EXCLUDE_FROM_ALL)

set(HAVE_SNAPPY TRUE CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_DEPS_DIR}/leveldb" "${CMAKE_BINARY_DIR}/leveldb" EXCLUDE_FROM_ALL)
if(TARGET leveldb)
  target_link_libraries(leveldb snappy)
endif()

set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory("${RIME_DEPS_DIR}/marisa-trie" "${CMAKE_BINARY_DIR}/marisa" EXCLUDE_FROM_ALL)

set(OPENCC_OVERLAY "${CMAKE_BINARY_DIR}/opencc-overlay")
file(MAKE_DIRECTORY "${OPENCC_OVERLAY}")
file(GLOB OPENCC_ENTRIES "${RIME_DEPS_DIR}/OpenCC/*")
foreach(entry ${OPENCC_ENTRIES})
  get_filename_component(entry_name "${entry}" NAME)
  if(NOT entry_name STREQUAL "CMakeLists.txt" AND NOT EXISTS "${OPENCC_OVERLAY}/${entry_name}")
    file(CREATE_LINK "${entry}" "${OPENCC_OVERLAY}/${entry_name}" SYMBOLIC)
  endif()
endforeach()
file(READ "${RIME_DEPS_DIR}/OpenCC/CMakeLists.txt" OPENCC_CMAKE)
string(REPLACE "add_subdirectory(data)" "if(HELITHRIME_OPENCC_DATA)\nadd_subdirectory(data)\nendif()"
       OPENCC_CMAKE "${OPENCC_CMAKE}")
string(REPLACE "add_subdirectory(doc)" "" OPENCC_CMAKE "${OPENCC_CMAKE}")
string(REPLACE "add_subdirectory(test)" "" OPENCC_CMAKE "${OPENCC_CMAKE}")
file(WRITE "${OPENCC_OVERLAY}/CMakeLists.txt" "${OPENCC_CMAKE}")
set(HELITHRIME_OPENCC_DATA OFF)
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(ENABLE_GTEST OFF CACHE BOOL "" FORCE)
set(ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)
set(LIBMARISA marisa CACHE STRING "" FORCE)
add_subdirectory("${OPENCC_OVERLAY}" "${CMAKE_BINARY_DIR}/OpenCC" EXCLUDE_FROM_ALL)
file(GLOB LIBOPENCC_HEADERS "${RIME_DEPS_DIR}/OpenCC/src/*.hpp"
     "${CMAKE_BINARY_DIR}/OpenCC/src/opencc_config.h")
file(COPY ${LIBOPENCC_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opencc")

set(X11Keysym "${RIME_DEPS_DIR}/librime/include" CACHE PATH "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_DATA OFF CACHE BOOL "" FORCE)
set(ENABLE_LOGGING ON CACHE BOOL "" FORCE)
set(ENABLE_EXTERNAL_PLUGINS OFF CACHE BOOL "" FORCE)
set(ICONV_LIBRARIES iconv)
set(ICONV_INCLUDE_DIR "${ICONV_BUILD}/include")
include_directories("${ICONV_BUILD}/include")
add_subdirectory("${RIME_DEPS_DIR}/librime" "${CMAKE_BINARY_DIR}/librime" EXCLUDE_FROM_ALL)

add_library(rime_jni SHARED rime_jni.cc)
target_include_directories(rime_jni PRIVATE
  "${RIME_DEPS_DIR}/librime/src"
  "${CMAKE_BINARY_DIR}/librime/src"
  "${ICONV_BUILD}/include")
target_link_libraries(rime_jni rime-static "-Wl,--no-as-needed" iconv "-Wl,--as-needed" log)
