# Points librime at the Boost targets built in this same CMake invocation.
# Header-only libraries (signals2 and the rest) are not usage-requirements of
# Boost::regex, so every modular include directory is listed.
set(Boost_FOUND TRUE)
set(Boost_LIBRARIES Boost::regex)
file(GLOB Boost_INCLUDE_DIRS LIST_DIRECTORIES true "${RIME_DEPS_DIR}/boost/libs/*/include")
