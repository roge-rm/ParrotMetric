# ParrotMetric

A parametric 3D modeller for Android with a touch UI, for making things to 3D print. Draw sketches, extrude, fillet and chamfer them, and change any earlier step to have everything after it rebuild. Import STL files and edit them too, and export STL.

It's early days: right now the app shows a rounded box, imports an STL, cuts a hole in it and exports the result.

## Building

Get the submodules first:

    git submodule update --init --depth 1

Then build as usual:

    ./gradlew :app:assembleDebug

The first build compiles Open CASCADE for each ABI, which takes a while. It's kept in core/build/occt and reused after that.

The core's tests run on the build machine:

    core/scripts/build-occt.sh host
    cmake -S core -B core/build/host -G Ninja -DPM_TESTS=ON
    cmake --build core/build/host
    ctest --test-dir core/build/host

## Licence

GPL 3. The libraries it's built with are listed in licences/.
