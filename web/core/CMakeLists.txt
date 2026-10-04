cmake_minimum_required(VERSION 3.22)
project(parrotmetric_web C CXX)

# The core as WebAssembly for the browser app: jni.cpp as on Android and
# desktop, run by bridge.cpp, drawing with WebGL 2. Built by build.sh with
# Emscripten into parrotmetric-core.js and .wasm. Everything, OCCT included,
# uses WebAssembly's own exceptions (-fwasm-exceptions).

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(flags "-fwasm-exceptions")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${flags}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${flags}")

# jni.h, which is the same everywhere, from a JDK; jni/ has this build's jni_md.h.
if(NOT JNI_HEADER_DIR)
    message(FATAL_ERROR "Set JNI_HEADER_DIR to a JDK's include folder")
endif()

set(root ${CMAKE_CURRENT_LIST_DIR}/../..)
set(PM_OCCT_TARGET wasm)
add_subdirectory(${root}/core ${CMAKE_BINARY_DIR}/core)

add_executable(parrotmetric-core ${root}/app/src/main/cpp/jni.cpp bridge.cpp dispatch.cpp)
target_include_directories(parrotmetric-core PRIVATE ${CMAKE_CURRENT_LIST_DIR}/jni ${JNI_HEADER_DIR} ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(parrotmetric-core PRIVATE pmcore)
target_link_options(parrotmetric-core PRIVATE
    ${flags}
    -sMODULARIZE=1 -sEXPORT_NAME=createParrotMetricCore -sENVIRONMENT=web
    -sALLOW_MEMORY_GROWTH=1 -sMAXIMUM_MEMORY=4GB -sSTACK_SIZE=4MB
    -sMIN_WEBGL_VERSION=2 -sMAX_WEBGL_VERSION=2
    -sEXPORTED_FUNCTIONS=_pm_args,_pm_call,_pm_gl_start,_pm_gl_resize
    -sEXPORTED_RUNTIME_METHODS=HEAPU8,ccall
    -Wl,--gc-sections)
