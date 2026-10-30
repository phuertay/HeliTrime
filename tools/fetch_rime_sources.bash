#!/usr/bin/env bash
# Fetch the librime 1.17.0 tree Trime v3.3.11 builds, plus luna_pinyin data.
# Output is third_party/rime, which is gitignored. A checked-in .so is not the recipe.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ROOT}/third_party/rime"
mkdir -p "${DEST}"

pin() {
  local url="$1" name="$2" commit="$3"
  local dir="${DEST}/${name}"
  if [[ ! -d "${dir}/.git" ]]; then
    git init "${dir}"
    git -C "${dir}" remote add origin "${url}"
  fi
  git -C "${dir}" fetch --depth 1 origin "${commit}"
  git -C "${dir}" checkout --detach FETCH_HEAD
}

pin https://github.com/rime/librime.git librime 33e78140250125871856cdc5b42ddc6a5fcd3cd4
pin https://github.com/google/glog.git glog 7b134a5c82c0c0b5698bb6bf7a835b230c5638e4
pin https://github.com/google/leveldb.git leveldb 99b3c03b3284f5886f9ef9a4ef703d57373e61be
pin https://github.com/s-yata/marisa-trie.git marisa-trie 3e87d53b78e15f2f43783d5e376561a8c9722051
pin https://github.com/jbeder/yaml-cpp.git yaml-cpp 2f86d13775d119edbb69af52e5f566fd65c6953b
pin https://github.com/BYVoid/OpenCC.git OpenCC 907bfcbbd3aae86ff04bc8eaca67c8af03108ddf
pin https://github.com/google/snappy.git snappy 32ded457c0b1fe78ceb8397632c416568d6714a0
pin https://github.com/rime/rime-prelude.git rime-prelude 082425ea0684bca36474415d4a0e8db9b016487e
pin https://github.com/rime/rime-luna-pinyin.git rime-luna-pinyin 56b934b099dfbeab842320f13aa8b461a6ab3e42
pin https://github.com/rime/rime-essay.git rime-essay 054920de4f54c9e5994276a96a4fc2a35cb51aa3

BOOST_URL="https://github.com/boostorg/boost/releases/download/boost-1.89.0/boost-1.89.0-cmake.tar.xz"
BOOST_SHA="67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"
ICONV_URL="https://ftp.gnu.org/pub/gnu/libiconv/libiconv-1.18.tar.gz"
ICONV_SHA="3b08f5f4f9b4eb82f151a7040bfd6fe6c6fb922efe4b1659c66ea933276965e8"

if [[ ! -f "${DEST}/boost/CMakeLists.txt" ]]; then
  curl -fsSL -o "${DEST}/boost-1.89.0-cmake.tar.xz" "${BOOST_URL}"
  echo "${BOOST_SHA}  ${DEST}/boost-1.89.0-cmake.tar.xz" | sha256sum -c -
  tar -xJf "${DEST}/boost-1.89.0-cmake.tar.xz" -C "${DEST}"
  mv "${DEST}/boost-1.89.0" "${DEST}/boost"
fi

if [[ ! -f "${DEST}/libiconv/configure" ]]; then
  curl -fsSL -o "${DEST}/libiconv-1.18.tar.gz" "${ICONV_URL}"
  echo "${ICONV_SHA}  ${DEST}/libiconv-1.18.tar.gz" | sha256sum -c -
  tar -xzf "${DEST}/libiconv-1.18.tar.gz" -C "${DEST}"
  mv "${DEST}/libiconv-1.18" "${DEST}/libiconv"
fi

echo "RIME SOURCES READY ${DEST}"
