/*
 * The platform part of the JNI headers, for building the Windows library on
 * Linux: jni.h is the same everywhere, but the cross build only has the Linux
 * JDK. These match Windows' own jni_md.h.
 */
#ifndef PARROTMETRIC_WIN32_JNI_MD_H
#define PARROTMETRIC_WIN32_JNI_MD_H

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif
