cmake_minimum_required(VERSION 3.22)
project(parrotmetric_jni CXX)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../core ${CMAKE_BINARY_DIR}/core)

add_library(parrotmetric SHARED jni.cpp)
target_link_libraries(parrotmetric pmcore log)
# Drops the parts of OCCT nothing calls, and folds identical functions into one.
target_link_options(parrotmetric PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--icf=all -Wl,-O2)
