/*
 * The platform part of the JNI headers for the WebAssembly build, where
 * jni.cpp runs behind bridge.cpp's stand-in for a JVM. jlong is 64 bits as
 * everywhere else.
 */
#ifndef PARROTMETRIC_WASM_JNI_MD_H
#define PARROTMETRIC_WASM_JNI_MD_H

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif
