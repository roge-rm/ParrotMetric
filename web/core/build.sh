#!/bin/bash
# Builds the core as WebAssembly into web/core/out: parrotmetric-core.js and
# .wasm. OCCT is built for it first (core/build/occt/wasm), which takes a while
# the first time.
set -e
# The SDK's CMake and Ninja, as the app build uses.
PATH=${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/4.1.2/bin:$PATH
here=$(cd "$(dirname "$0")" && pwd)
root=$here/../..
export EMSDK=${EMSDK:-$HOME/.local/share/emsdk}
. "$EMSDK/emsdk_env.sh" >/dev/null 2>&1
toolchain=$EMSDK/upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake
jdk=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
launcher=
command -v ccache >/dev/null && launcher="-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
# In a throwaway checkout (a git worktree, as the F-Droid publisher makes), OCCT is
# copied from the main checkout rather than built again. build-occt.sh still builds
# it if the copy was made from a different OCCT.
top=$(cd "$root" && pwd -P)
main=$(dirname "$(git -C "$top" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || echo "$top/.git")")
if [ "$main" != "$top" ] && [ ! -f "$top/core/build/occt/wasm/.rev" ] && [ -f "$main/core/build/occt/wasm/.rev" ]; then
    mkdir -p "$top/core/build/occt"
    cp -a "$main/core/build/occt/wasm" "$top/core/build/occt/"
fi
OCCT_FLAGS=-fexceptions OCCT_CMAKE_ARGS="$launcher" "$root/core/scripts/build-occt.sh" wasm "$toolchain"
B=$here/build
cmake -S "$here" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
    -DJNI_HEADER_DIR="$jdk/include" $launcher >/dev/null
cmake --build "$B" -j "${JOBS:-10}"
mkdir -p "$here/out"
cp "$B/parrotmetric-core.js" "$B/parrotmetric-core.wasm" "$here/out/"
ls -la "$here/out"
