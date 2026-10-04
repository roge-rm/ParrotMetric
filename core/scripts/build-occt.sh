#!/bin/sh
# Builds the parts of OpenCASCADE we use as static libraries and installs them
# in core/build/occt/<target>. Target is "host" or an Android ABI (arm64-v8a,
# x86_64, armeabi-v7a). Does nothing if that install is already there and the
# submodule hasn't moved.
#   core/scripts/build-occt.sh host
#   core/scripts/build-occt.sh arm64-v8a /path/to/ndk
set -eu
# The SDK's CMake and Ninja, the same ones the app build uses.
PATH=${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/4.1.2/bin:$PATH
target=$1
ndk=${2:-}
here=$(cd "$(dirname "$0")/.." && pwd)
src=$here/../third_party/occt
out=$here/build/occt/$target
work=$here/build/occt-work/$target
# The modelling and file format toolkits. OCCT's CMake adds what they depend on.
# The file formats pull in its viewer toolkits too, built here without FreeType or
# OpenGL, and the linker drops what we don't call.
toolkits="TKMesh TKFillet TKOffset TKBool TKPrim TKShHealing TKHLR TKDESTEP TKDEIGES TKDEOBJ TKDEPLY TKDEVRML"
rev="$(git -C "$src" rev-parse HEAD) $toolkits"

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
    -DCMAKE_C_FLAGS=-ffunction-sections\ -fdata-sections \
    -DCMAKE_CXX_FLAGS=-ffunction-sections\ -fdata-sections \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON

if [ "$target" != host ]; then
    set -- "$@" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$target" \
        -DANDROID_PLATFORM=android-27 \
        -DANDROID_STL=c++_shared
fi

rm -rf "$work" "$out"
cmake "$@"
cmake --build "$work" -j "${OCCT_JOBS:-10}"
cmake --install "$work"
echo "$rev" > "$out/.rev"
rm -rf "$work"
