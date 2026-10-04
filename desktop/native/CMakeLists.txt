cmake_minimum_required(VERSION 3.22)
project(parrotmetric_desktop C CXX)

# The core for the desktop app: the same JNI library as Android
# (app/src/main/cpp/jni.cpp) with the offscreen GL the desktop view draws
# with. PM_OCCT_TARGET names the OCCT install in core/build/occt to link.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Cross builds pass JNI_INCLUDE_DIRS themselves, since the headers are the
# same everywhere and FindJNI would look for a JVM of the target's kind.
if(NOT JNI_INCLUDE_DIRS)
    find_package(JNI REQUIRED)
endif()

set(root ${CMAKE_CURRENT_LIST_DIR}/../..)
add_subdirectory(${root}/core ${CMAKE_BINARY_DIR}/core)

add_library(parrotmetric SHARED ${root}/app/src/main/cpp/jni.cpp offscreen.cpp)
target_include_directories(parrotmetric PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(parrotmetric PRIVATE pmcore)
if(WIN32)
    # No "lib" in front, as Java looks for parrotmetric.dll.
    set_target_properties(parrotmetric PROPERTIES PREFIX "")
    target_link_libraries(parrotmetric PRIVATE gdi32 user32)
    target_link_options(parrotmetric PRIVATE -static -Wl,--gc-sections -s)
else()
    target_link_libraries(parrotmetric PRIVATE ${CMAKE_DL_LIBS})
    # Drops the parts of OCCT nothing calls, and keeps its symbols out of the library's exports.
    target_link_options(parrotmetric PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -s)
endif()

if(WIN32)
    # ParrotMetric.exe, which starts the app on the Java runtime next to it
    # (desktop/windows/launcher.c), with the app's icon.
    set(LAUNCHER_ICON ${CMAKE_CURRENT_LIST_DIR}/../windows/parrotmetric.ico)
    set(PM_VERSION "0.0" CACHE STRING "The version the launcher reports")
    enable_language(RC)
    configure_file(../windows/launcher.rc.in ${CMAKE_BINARY_DIR}/launcher.rc @ONLY)
    add_executable(launcher WIN32 ../windows/launcher.c ${CMAKE_BINARY_DIR}/launcher.rc)
    set_target_properties(launcher PROPERTIES OUTPUT_NAME ParrotMetric)
    target_compile_definitions(launcher PRIVATE _WIN32_WINNT=0x0A00 UNICODE _UNICODE)
    target_link_options(launcher PRIVATE -municode -static)
endif()
