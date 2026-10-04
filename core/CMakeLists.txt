cmake_minimum_required(VERSION 3.22)
project(parrotmetric_core CXX)

# The modelling core: no platform code. The app links it into its JNI library,
# and the host build runs the tests:
#   cmake -S core -B core/build/host -G Ninja -DPM_TESTS=ON && cmake --build core/build/host && ctest --test-dir core/build/host
#
# OCCT comes prebuilt from scripts/build-occt.sh, one install per target.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(PM_TESTS "Build the host tests" OFF)
if(ANDROID)
    set(PM_OCCT_TARGET ${ANDROID_ABI})
else()
    set(PM_OCCT_TARGET host)
endif()
set(PM_OCCT_DIR ${CMAKE_CURRENT_LIST_DIR}/build/occt/${PM_OCCT_TARGET} CACHE PATH "OCCT install to link against")
set(third_party ${CMAKE_CURRENT_LIST_DIR}/../third_party)

# NO_CMAKE_FIND_ROOT_PATH: the NDK toolchain would otherwise look only in its sysroot.
find_package(OpenCASCADE REQUIRED CONFIG PATHS ${PM_OCCT_DIR} NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)

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

add_library(pmcore STATIC
        src/mesh/mesh.cpp
        src/mesh/stl.cpp
        src/mesh/mesh_body.cpp
        src/io/exchange.cpp
        src/solid/solid.cpp)
# The renderer needs OpenGL ES 3, which only the Android build has for now.
if(ANDROID)
    target_sources(pmcore PRIVATE src/render/renderer.cpp)
    target_link_libraries(pmcore PUBLIC GLESv3)
endif()
target_include_directories(pmcore PUBLIC src)
target_include_directories(pmcore SYSTEM PUBLIC ${OpenCASCADE_INCLUDE_DIR} ${third_party}/eigen)
target_link_libraries(pmcore PUBLIC manifold
        TKDESTEP TKDEIGES TKXSBase TKDE
        TKOffset TKFillet TKBool TKBO TKPrim TKMesh TKShHealing TKTopAlgo TKGeomAlgo
        TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel)

if(PM_TESTS)
    add_subdirectory(${third_party}/catch2 ${CMAKE_BINARY_DIR}/catch2 EXCLUDE_FROM_ALL)
    add_executable(pmcore_tests tests/stl_test.cpp tests/mesh_body_test.cpp tests/solid_test.cpp tests/exchange_test.cpp)
    target_link_libraries(pmcore_tests PRIVATE pmcore Catch2::Catch2WithMain)
    include(CTest)
    add_test(NAME pmcore_tests COMMAND pmcore_tests)
endif()
