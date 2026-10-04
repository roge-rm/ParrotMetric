cmake_minimum_required(VERSION 3.22)
project(parrotmetric_core C CXX)

# The modelling core: no platform code. The app links it into its JNI library,
# and the host build runs the tests:
#   cmake -S core -B core/build/host -G Ninja -DPM_TESTS=ON && cmake --build core/build/host && ctest --test-dir core/build/host
#
# OCCT comes prebuilt from scripts/build-occt.sh, one install per target.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(PM_TESTS "Build the host tests" OFF)
option(PM_BENCH "Build the benchmark, to run on a device" OFF)
# Which OCCT install: the Android ABI, or a name the desktop and browser builds pass in.
if(ANDROID)
    set(PM_OCCT_TARGET ${ANDROID_ABI})
elseif(NOT PM_OCCT_TARGET)
    set(PM_OCCT_TARGET host)
endif()
set(PM_OCCT_DIR ${CMAKE_CURRENT_LIST_DIR}/build/occt/${PM_OCCT_TARGET} CACHE PATH "OCCT install to link against")
set(third_party ${CMAKE_CURRENT_LIST_DIR}/../third_party)

# NO_CMAKE_FIND_ROOT_PATH: the NDK toolchain would otherwise look only in its sysroot.
find_package(OpenCASCADE REQUIRED CONFIG PATHS ${PM_OCCT_DIR} NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
# OCCT names libstdc++ itself, which would link it dynamically even where the
# build asks for it static (the Linux packages). The compiler adds the right
# C++ runtime anyway.
get_target_property(occt_kernel_libs TKernel INTERFACE_LINK_LIBRARIES)
if(occt_kernel_libs)
    list(FILTER occt_kernel_libs EXCLUDE REGEX "stdc\\+\\+")
    set_target_properties(TKernel PROPERTIES INTERFACE_LINK_LIBRARIES "${occt_kernel_libs}")
endif()

# Manifold, for mesh bodies. Built from source with the core, without its 2D
# cross sections (they need Clipper2) or threads.
set(MANIFOLD_CROSS_SECTION OFF CACHE BOOL "" FORCE)
set(MANIFOLD_TEST OFF CACHE BOOL "" FORCE)
set(MANIFOLD_PAR OFF CACHE BOOL "" FORCE)
set(MANIFOLD_PYBIND OFF CACHE BOOL "" FORCE)
set(MANIFOLD_CBIND OFF CACHE BOOL "" FORCE)
set(MANIFOLD_JSBIND OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${third_party}/manifold ${CMAKE_BINARY_DIR}/manifold EXCLUDE_FROM_ALL)

# The fonts for text, built in as bytes.
set(pm_fonts ${CMAKE_CURRENT_LIST_DIR}/../third_party/fonts)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${pm_fonts}/NotoSans-Regular.ttf ${pm_fonts}/NotoSans-Bold.ttf)
set(pm_fonts_cpp "// Made by CMake from third_party/fonts.\n")
foreach(symbol IN ITEMS regular bold)
    if(symbol STREQUAL "regular")
        set(file NotoSans-Regular)
    else()
        set(file NotoSans-Bold)
    endif()
    file(READ ${pm_fonts}/${file}.ttf hex HEX)
    string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," bytes "${hex}")
    string(APPEND pm_fonts_cpp "extern const unsigned char pm_font_${symbol}[] = {${bytes}};\n")
endforeach()
file(WRITE ${CMAKE_BINARY_DIR}/fonts.cpp.new "${pm_fonts_cpp}")
file(COPY_FILE ${CMAKE_BINARY_DIR}/fonts.cpp.new ${CMAKE_BINARY_DIR}/fonts.cpp ONLY_IF_DIFFERENT)

add_library(pmcore STATIC
        src/display/display_mesh.cpp
        src/display/thickness.cpp
        src/mesh/mesh.cpp
        src/mesh/stl.cpp
        src/mesh/mesh_body.cpp
        src/mesh/repair.cpp
        src/io/exchange.cpp
        src/io/mesh_formats.cpp
        src/sketch/regions.cpp
        src/sketch/text.cpp
        ${CMAKE_BINARY_DIR}/fonts.cpp
        src/model/named_shape.cpp
        src/model/operations.cpp
        src/model/store.cpp
        src/solid/solid.cpp
        src/solid/speed.cpp)
# The renderer: OpenGL ES 3 on Android, WebGL 2 in the browser, and on desktop
# whatever GL the machine has, loaded at run time (render/gl.h).
target_sources(pmcore PRIVATE src/render/renderer.cpp src/render/gl_loader.cpp src/render/picture.cpp)
if(ANDROID)
    target_link_libraries(pmcore PUBLIC GLESv3)
elseif(NOT EMSCRIPTEN)
    target_compile_definitions(pmcore PUBLIC PM_GL_LOADED EGL_NO_X11)
    target_include_directories(pmcore SYSTEM PUBLIC ${third_party}/khronos)
endif()

# zlib: Android's own, otherwise built in from third_party/zlib.
if(ANDROID)
    set(PM_ZLIB z)
else()
    file(GLOB zlib_sources ${third_party}/zlib/*.c)
    add_library(pm_zlib STATIC ${zlib_sources})
    target_include_directories(pm_zlib PUBLIC ${third_party}/zlib)
    if(NOT WIN32)
        target_compile_definitions(pm_zlib PRIVATE HAVE_UNISTD_H)
    endif()
    set(PM_ZLIB pm_zlib)
endif()
target_include_directories(pmcore PUBLIC src)
target_include_directories(pmcore PRIVATE ${third_party}/stb)
target_include_directories(pmcore SYSTEM PUBLIC ${OpenCASCADE_INCLUDE_DIR} ${third_party}/eigen)
target_link_libraries(pmcore PUBLIC manifold ${PM_ZLIB}
        TKDESTEP TKDEIGES TKXSBase TKDE
        TKOffset TKFillet TKBool TKBO TKPrim TKMesh TKShHealing TKTopAlgo TKGeomAlgo
        TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel)

if(PM_TESTS)
    add_subdirectory(${third_party}/catch2 ${CMAKE_BINARY_DIR}/catch2 EXCLUDE_FROM_ALL)
    add_executable(pmcore_tests tests/stl_test.cpp tests/mesh_body_test.cpp tests/solid_test.cpp tests/exchange_test.cpp tests/display_test.cpp tests/regions_test.cpp tests/operations_test.cpp tests/mesh_formats_test.cpp tests/repair_test.cpp)
    target_link_libraries(pmcore_tests PRIVATE pmcore Catch2::Catch2WithMain)
    include(CTest)
    add_test(NAME pmcore_tests COMMAND pmcore_tests)
endif()

if(PM_BENCH)
    add_executable(pmcore_bench bench/bench.cpp)
    target_link_libraries(pmcore_bench PRIVATE pmcore)
endif()
