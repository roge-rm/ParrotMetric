#!/bin/sh
# Builds the parts of OpenCASCADE we use as static libraries and installs them
# in core/build/occt/<target>. Target is "host", an Android ABI (arm64-v8a,
# x86_64, armeabi-v7a, x86) with the NDK's path, or any other name with a
# CMake toolchain file, for the desktop and browser builds. Does nothing if
# that install is already there and the submodule hasn't moved.
#   core/scripts/build-occt.sh host
#   core/scripts/build-occt.sh arm64-v8a /path/to/ndk
#   core/scripts/build-occt.sh windows-x64 /path/to/toolchain.cmake
set -eu
# The SDK's CMake and Ninja, the same ones the app build uses.
PATH=${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/4.1.2/bin:$PATH
target=$1
second=${2:-}
here=$(cd "$(dirname "$0")/.." && pwd)
src=$here/../third_party/occt
out=$here/build/occt/$target
work=$here/build/occt-work/$target
# The modelling and file format toolkits. OCCT's CMake adds what they depend on.
# The file formats pull in its viewer toolkits too, built here without FreeType or
# OpenGL, and the linker drops what we don't call.
toolkits="TKMesh TKFillet TKOffset TKBool TKPrim TKShHealing TKHLR TKDESTEP TKDEIGES TKDEOBJ TKDEPLY TKDEVRML"
# OCCT_OPT replaces the release build's -O3, such as -Os for a smaller library.
rev="$(git -C "$src" rev-parse HEAD 2>/dev/null || echo unknown) $toolkits${OCCT_FLAGS:+ $OCCT_FLAGS}${OCCT_OPT:+ opt=$OCCT_OPT}"

if [ -f "$out/.rev" ] && [ "$(cat "$out/.rev")" = "$rev" ]; then
    exit 0
fi


set -- -G Ninja -S "$src" -B "$work" \
    -DCMAKE_BUILD_TYPE=Release \
    -DINSTALL_DIR="$out" \
    -DBUILD_LIBRARY_TYPE=Static \
    -DBUILD_MODULE_FoundationClasses=OFF \
    -DBUILD_MODULE_ModelingData=OFF \
    -DBUILD_MODULE_ModelingAlgorithms=OFF \
    -DBUILD_MODULE_Visualization=OFF \
    -DBUILD_MODULE_ApplicationFramework=OFF \
    -DBUILD_MODULE_DataExchange=OFF \
    -DBUILD_MODULE_Draw=OFF \
    -DBUILD_ADDITIONAL_TOOLKITS="$toolkits" \
    -DBUILD_DOC_Overview=OFF \
    -DBUILD_USE_PCH=OFF \
    -DUSE_TCL=OFF -DUSE_TK=OFF -DUSE_FREETYPE=OFF -DUSE_XLIB=OFF \
    -DUSE_OPENGL=OFF -DUSE_GLES2=OFF \
    "-DCMAKE_C_FLAGS=-ffunction-sections -fdata-sections ${OCCT_FLAGS:-}" \
    "-DCMAKE_CXX_FLAGS=-ffunction-sections -fdata-sections ${OCCT_FLAGS:-}" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
if [ -n "${OCCT_OPT:-}" ]; then
    set -- "$@" "-DCMAKE_C_FLAGS_RELEASE=$OCCT_OPT -DNDEBUG" "-DCMAKE_CXX_FLAGS_RELEASE=$OCCT_OPT -DNDEBUG"
fi

case "$second" in
    "") ;;
    *.cmake) set -- "$@" -DCMAKE_TOOLCHAIN_FILE="$second" ;;
    *) set -- "$@" \
        -DCMAKE_TOOLCHAIN_FILE="$second/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$target" \
        -DANDROID_PLATFORM=android-27 \
        -DANDROID_STL=c++_shared ;;
esac
# Extra settings for a target, such as a compiler cache. OCCT_FLAGS adds
# compiler flags, such as the browser build's exception handling.
if [ -n "${OCCT_CMAKE_ARGS:-}" ]; then
    # shellcheck disable=SC2086
    set -- "$@" $OCCT_CMAKE_ARGS
fi

rm -rf "$work" "$out"
cmake "$@"
cmake --build "$work" -j "${OCCT_JOBS:-10}"
cmake --install "$work"
echo "$rev" > "$out/.rev"
rm -rf "$work"
