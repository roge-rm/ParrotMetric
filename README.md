# ParrotMetric

A parametric 3D modeller for making things to 3D print, on Android, Linux, Windows and in a browser. Draw sketches with constraints and dimensions, then extrude, revolve, fillet, chamfer, shell and cut them into parts. Every step stays in the history, so changing an early one rebuilds everything after it.

It opens and saves its own designs (.pmet), exports STL, 3MF, OBJ, STEP and IGES, and imports STEP and IGES solids and STL, 3MF and OBJ meshes, which it repairs as they come in. Meshes can be cut, joined, sketched on and turned into solids.

## Building

Get the submodules first:

    git submodule update --init --depth 1

Each target builds Open CASCADE for itself the first time, which takes a while. It's kept in core/build/occt and reused after that.

Android, 64-bit (arm64 and x86_64), and with -Parm32 the 32-bit build (armv7 and x86):

    ./gradlew :app:assembleRelease
    ./gradlew :app:assembleRelease -Parm32

Release builds are signed with the key named in ../Keys/parrotmetric-keystore.properties, when there is one.

Desktop, on this machine:

    ./gradlew :desktop:run

The packages are built in Docker containers (desktop/native/Dockerfile.*):

    ./gradlew :desktop:debAmd64 :desktop:debArm64
    ./gradlew :desktop:appImageAmd64 :desktop:appImageArm64
    ./gradlew :desktop:windowsX64

The browser build needs Emscripten (emsdk, in ~/.local/share/emsdk or wherever EMSDK says):

    ./gradlew :webApp:wasmJsBrowserDistribution

The page is in web/app/build/dist/wasmJs/productionExecutable. After changing NativeCore.kt, run web/core/gen_bridge.py to update the browser's side of the calls.

The core's tests run on the build machine:

    core/scripts/build-occt.sh host
    cmake -S core -B core/build/host -G Ninja -DPM_TESTS=ON
    cmake --build core/build/host
    ctest --test-dir core/build/host

## Licence

GPL 3. The libraries it's built with are listed in licences/.
