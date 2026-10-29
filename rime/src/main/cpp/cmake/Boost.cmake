# Boost 1.89 cmake build, same tarball Trime v3.3.11 uses.
# Extracted under the build tree so the source checkout stays clean.
set(BOOST_VERSION 1.89.0)
set(BOOST_ARCHIVE "${CMAKE_BINARY_DIR}/boost-${BOOST_VERSION}-cmake.tar.xz")
set(BOOST_SRC "${CMAKE_BINARY_DIR}/boost-${BOOST_VERSION}")

if(NOT EXISTS "${BOOST_ARCHIVE}")
  message(STATUS "Downloading Boost ${BOOST_VERSION}")
  file(
    DOWNLOAD
    "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
    "${BOOST_ARCHIVE}"
    EXPECTED_HASH
      SHA256=67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74
    SHOW_PROGRESS)
endif()

if(NOT EXISTS "${BOOST_SRC}/CMakeLists.txt")
  message(STATUS "Extracting Boost ${BOOST_VERSION}")
  file(ARCHIVE_EXTRACT INPUT "${BOOST_ARCHIVE}" DESTINATION "${CMAKE_BINARY_DIR}")
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    range
    regex
    scope_exit
    signals2
    utility
    uuid)

add_subdirectory("${BOOST_SRC}" "${CMAKE_BINARY_DIR}/boost-build" EXCLUDE_FROM_ALL)
