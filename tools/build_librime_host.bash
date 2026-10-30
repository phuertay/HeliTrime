#!/usr/bin/env bash
# Host build of librime 1.17.0 the way Trime v3.3.11 builds it, plus a 你好 check.
set -euo pipefail

SRC=/tmp/rime-src
PREFIX=/tmp/rime-prefix
BUILD=/tmp/rime-build
JOBS="$(nproc)"

export CC=gcc
export CXX=g++
export PATH="${PREFIX}/bin:${PATH}"
export LD_LIBRARY_PATH="${PREFIX}/lib:${LD_LIBRARY_PATH:-}"
export LIBRARY_PATH="${PREFIX}/lib:${LIBRARY_PATH:-}"
export CPATH="${PREFIX}/include:${CPATH:-}"
export CMAKE_PREFIX_PATH="${PREFIX}"
export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig:${PKG_CONFIG_PATH:-}"

common_cmake=(
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX="${PREFIX}"
  -DCMAKE_PREFIX_PATH="${PREFIX}"
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DBUILD_SHARED_LIBS=OFF
)

if [[ ! -e "${PREFIX}/lib/libiconv.so" ]]; then
  echo "== libiconv (shared) =="
  rm -rf "${BUILD}/libiconv"
  mkdir -p "${BUILD}/libiconv"
  (
    cd "${BUILD}/libiconv"
    "${SRC}/libiconv/configure" --prefix="${PREFIX}" --enable-shared --disable-static
    make -j"${JOBS}"
    make install
  )
fi

if [[ ! -e "${PREFIX}/lib/libboost_regex.a" && ! -e "${PREFIX}/lib/libboost_regex.so" ]]; then
  echo "== boost =="
  cmake -S "${SRC}/boost" -B "${BUILD}/boost" "${common_cmake[@]}" \
    -DBOOST_INCLUDE_LIBRARIES="algorithm;crc;dll;interprocess;range;regex;scope_exit;signals2;utility;uuid"
  cmake --build "${BUILD}/boost" -j"${JOBS}"
  cmake --install "${BUILD}/boost"
fi

if [[ ! -e "${PREFIX}/lib/libsnappy.a" ]]; then
  echo "== snappy =="
  cmake -S "${SRC}/snappy" -B "${BUILD}/snappy" "${common_cmake[@]}" \
    -DSNAPPY_BUILD_TESTS=OFF -DSNAPPY_BUILD_BENCHMARKS=OFF -DSNAPPY_INSTALL=ON
  cmake --build "${BUILD}/snappy" -j"${JOBS}"
  cmake --install "${BUILD}/snappy"
fi

if [[ ! -e "${PREFIX}/lib/libleveldb.a" ]]; then
  echo "== leveldb =="
  cmake -S "${SRC}/leveldb" -B "${BUILD}/leveldb" "${common_cmake[@]}" \
    -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF \
    -DCMAKE_REQUIRED_INCLUDES="${PREFIX}/include" \
    -DCMAKE_REQUIRED_LINK_OPTIONS="-L${PREFIX}/lib"
  cmake --build "${BUILD}/leveldb" -j"${JOBS}"
  cmake --install "${BUILD}/leveldb"
fi

if [[ ! -e "${PREFIX}/lib/libglog.a" ]]; then
  echo "== glog =="
  cmake -S "${SRC}/glog" -B "${BUILD}/glog" "${common_cmake[@]}" \
    -DWITH_GFLAGS=OFF -DWITH_GTEST=OFF -DWITH_UNWIND=none -DWITH_PKGCONFIG=OFF
  cmake --build "${BUILD}/glog" -j"${JOBS}"
  cmake --install "${BUILD}/glog"
fi

if [[ ! -e "${PREFIX}/lib/libyaml-cpp.a" ]]; then
  echo "== yaml-cpp =="
  cmake -S "${SRC}/yaml-cpp" -B "${BUILD}/yaml-cpp" "${common_cmake[@]}" \
    -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF -DYAML_BUILD_SHARED_LIBS=OFF
  cmake --build "${BUILD}/yaml-cpp" -j"${JOBS}"
  cmake --install "${BUILD}/yaml-cpp"
fi

if [[ ! -e "${PREFIX}/lib/libmarisa.a" ]]; then
  echo "== marisa =="
  cmake -S "${SRC}/marisa-trie" -B "${BUILD}/marisa" "${common_cmake[@]}" \
    -DBUILD_TESTING=OFF -DENABLE_TOOLS=OFF
  cmake --build "${BUILD}/marisa" -j"${JOBS}"
  cmake --install "${BUILD}/marisa"
fi

if [[ ! -e "${PREFIX}/lib/libopencc.a" ]]; then
  echo "== opencc =="
  cmake -S "${SRC}/OpenCC" -B "${BUILD}/opencc" "${common_cmake[@]}" \
    -DBUILD_DOCUMENTATION=OFF -DENABLE_GTEST=OFF -DBUILD_TESTING=OFF
  cmake --build "${BUILD}/opencc" -j"${JOBS}"
  cmake --install "${BUILD}/opencc"
fi

if [[ ! -e "${PREFIX}/lib/librime.so" && ! -e "${PREFIX}/lib/librime.so.1" ]]; then
  echo "== librime =="
  cmake -S "${SRC}/librime" -B "${BUILD}/librime" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${PREFIX}" \
    -DCMAKE_PREFIX_PATH="${PREFIX}" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_STATIC=ON \
    -DBUILD_TEST=OFF \
    -DBUILD_DATA=OFF \
    -DENABLE_LOGGING=ON \
    -DENABLE_EXTERNAL_PLUGINS=OFF \
    -DICONV_INCLUDE_DIR="${PREFIX}/include" \
    -DICONV_LIBRARY="${PREFIX}/lib/libiconv.so"
  cmake --build "${BUILD}/librime" -j"${JOBS}"
  cmake --install "${BUILD}/librime"
fi

echo "HOST LIBRIME READY"
ls -la "${PREFIX}/lib"/librime* "${PREFIX}/bin"/rime_deployer 2>/dev/null || true

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -d /tmp/rime-data/shared/build && ( -f "${PREFIX}/lib/librime.so" || -f "${PREFIX}/lib/librime.so.1" ) ]]; then
  echo "== nihao =="
  g++ -o /tmp/nihao_host_test "${ROOT}/tools/nihao_host_test.cc" \
    -I"${PREFIX}/include" -L"${PREFIX}/lib" -Wl,-rpath,"${PREFIX}/lib" -lrime
  /tmp/nihao_host_test
fi
