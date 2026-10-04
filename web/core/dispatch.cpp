// Written by gen_bridge.py from NativeCore.kt; run that instead of editing this.
// Runs jni.cpp's function for each call, numbered in NativeCore's order.

#include "bridge.h"

extern "C" {
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setScratchDirectory(JNIEnv*, jobject, jstring);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_extrude(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jdouble, jdouble, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_revolve(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_combine(JNIEnv*, jobject, jint, jlong, jlong, jint);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_fillet(JNIEnv*, jobject, jint, jlong, jobjectArray, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_chamfer(JNIEnv*, jobject, jint, jlong, jobjectArray, jdouble, jint, jdouble, jboolean);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_overlaps(JNIEnv*, jobject, jlong, jlong);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_facePlane(JNIEnv*, jobject, jlong, jstring);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_faceNames(JNIEnv*, jobject, jlong);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_signature(JNIEnv*, jobject, jlong, jstring, jboolean);
JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_relocate(JNIEnv*, jobject, jlong, jdoubleArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_importBody(JNIEnv*, jobject, jint, jbyteArray, jint);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_shell(JNIEnv*, jobject, jint, jlong, jobjectArray, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_draft(JNIEnv*, jobject, jint, jlong, jobjectArray, jstring, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_transform(JNIEnv*, jobject, jint, jlong, jdoubleArray, jstring);
JNIEXPORT jlongArray JNICALL Java_com_rm_parrotmetric_Core_split(JNIEnv*, jobject, jint, jlong, jdoubleArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_holeTool(JNIEnv*, jobject, jint, jdoubleArray, jdoubleArray, jdouble, jdouble, jint, jdouble, jdouble);
JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_repairReport(JNIEnv*, jobject, jbyteArray, jint);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_convertToSolid(JNIEnv*, jobject, jint, jlong);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_bodyCentre(JNIEnv*, jobject, jlong);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_section(JNIEnv*, jobject, jlongArray, jdoubleArray);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_selectedMeshPlane(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_retain(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_release(JNIEnv*, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_isMesh(JNIEnv*, jobject, jlong);
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_exportBodies(JNIEnv*, jobject, jlongArray, jobjectArray, jint, jint);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_show(JNIEnv*, jobject, jlongArray, jdoubleArray, jintArray, jintArray, jintArray, jdoubleArray, jdoubleArray, jdoubleArray, jdoubleArray, jboolean);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectedPlanes(JNIEnv*, jobject);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_measure(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setSection(JNIEnv*, jobject, jboolean, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_faceOutline(JNIEnv*, jobject, jlong, jstring, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_rm_parrotmetric_Core_shownTriangles(JNIEnv*, jobject);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_tap(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_clearSelection(JNIEnv*, jobject);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedEdges(JNIEnv*, jobject);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedFaces(JNIEnv*, jobject);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectedRegions(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_select(JNIEnv*, jobject, jobjectArray, jintArray, jobjectArray);
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_findRegions(JNIEnv*, jobject, jintArray, jintArray, jdoubleArray);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_surfaceCreated(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_surfaceChanged(JNIEnv*, jobject, jint, jint);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_drawFrame(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setDensity(JNIEnv*, jobject, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_orbit(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_pan(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_zoom(JNIEnv*, jobject, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_fit(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_viewFrom(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_cameraState(JNIEnv*, jobject);
}

namespace pmweb {

void dispatch(int call, Reader& in, Writer& out, JNIEnv* env) {
    switch (call) {
        case 0: {  // setScratchDirectory
            auto a0 = in.string();
            Java_com_rm_parrotmetric_Core_setScratchDirectory(env, nullptr, a0);
            break;
        }
        case 1: {  // extrude
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.doubles();
            auto a5 = in.ints();
            auto a6 = in.ints();
            auto a7 = in.doubles();
            auto a8 = in.f64();
            auto a9 = in.f64();
            auto a10 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_extrude(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
            if (!failed()) out.put(r);
            break;
        }
        case 2: {  // revolve
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.doubles();
            auto a5 = in.ints();
            auto a6 = in.ints();
            auto a7 = in.doubles();
            auto a8 = in.f64();
            auto a9 = in.f64();
            auto a10 = in.f64();
            auto a11 = in.f64();
            auto a12 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_revolve(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12);
            if (!failed()) out.put(r);
            break;
        }
        case 3: {  // combine
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.i64();
            auto a3 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_combine(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 4: {  // fillet
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto a3 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_fillet(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 5: {  // chamfer
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto a3 = in.f64();
            auto a4 = in.i32();
            auto a5 = in.f64();
            auto a6 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_chamfer(env, nullptr, a0, a1, a2, a3, a4, a5, a6);
            if (!failed()) out.put(r);
            break;
        }
        case 6: {  // overlaps
            auto a0 = in.i64();
            auto a1 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_overlaps(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 7: {  // facePlane
            auto a0 = in.i64();
            auto a1 = in.string();
            auto r = Java_com_rm_parrotmetric_Core_facePlane(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 8: {  // faceNames
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_faceNames(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 9: {  // signature
            auto a0 = in.i64();
            auto a1 = in.string();
            auto a2 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_signature(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 10: {  // relocate
            auto a0 = in.i64();
            auto a1 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_relocate(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 11: {  // importBody
            auto a0 = in.i32();
            auto a1 = in.bytes();
            auto a2 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_importBody(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 12: {  // shell
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto a3 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_shell(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 13: {  // draft
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto a3 = in.string();
            auto a4 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_draft(env, nullptr, a0, a1, a2, a3, a4);
            if (!failed()) out.put(r);
            break;
        }
        case 14: {  // transform
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.doubles();
            auto a3 = in.string();
            auto r = Java_com_rm_parrotmetric_Core_transform(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 15: {  // split
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_split(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 16: {  // holeTool
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.doubles();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto a5 = in.i32();
            auto a6 = in.f64();
            auto a7 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_holeTool(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7);
            if (!failed()) out.put(r);
            break;
        }
        case 17: {  // repairReport
            auto a0 = in.bytes();
            auto a1 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_repairReport(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 18: {  // convertToSolid
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_convertToSolid(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 19: {  // bodyCentre
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_bodyCentre(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 20: {  // section
            auto a0 = in.longs();
            auto a1 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_section(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 21: {  // selectedMeshPlane
            auto r = Java_com_rm_parrotmetric_Core_selectedMeshPlane(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 22: {  // retain
            auto a0 = in.i64();
            Java_com_rm_parrotmetric_Core_retain(env, nullptr, a0);
            break;
        }
        case 23: {  // release
            auto a0 = in.i64();
            Java_com_rm_parrotmetric_Core_release(env, nullptr, a0);
            break;
        }
        case 24: {  // isMesh
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_isMesh(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 25: {  // exportBodies
            auto a0 = in.longs();
            auto a1 = in.strings();
            auto a2 = in.i32();
            auto a3 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_exportBodies(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 26: {  // show
            auto a0 = in.longs();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.ints();
            auto a5 = in.doubles();
            auto a6 = in.doubles();
            auto a7 = in.doubles();
            auto a8 = in.doubles();
            auto a9 = in.boolean();
            Java_com_rm_parrotmetric_Core_show(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9);
            break;
        }
        case 27: {  // selectedPlanes
            auto r = Java_com_rm_parrotmetric_Core_selectedPlanes(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 28: {  // measure
            auto r = Java_com_rm_parrotmetric_Core_measure(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 29: {  // setSection
            auto a0 = in.boolean();
            auto a1 = in.f64();
            auto a2 = in.f64();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto a5 = in.f64();
            auto a6 = in.f64();
            Java_com_rm_parrotmetric_Core_setSection(env, nullptr, a0, a1, a2, a3, a4, a5, a6);
            break;
        }
        case 30: {  // faceOutline
            auto a0 = in.i64();
            auto a1 = in.string();
            auto a2 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_faceOutline(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 31: {  // shownTriangles
            auto r = Java_com_rm_parrotmetric_Core_shownTriangles(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 32: {  // tap
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto r = Java_com_rm_parrotmetric_Core_tap(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 33: {  // clearSelection
            Java_com_rm_parrotmetric_Core_clearSelection(env, nullptr);
            break;
        }
        case 34: {  // selectedEdges
            auto r = Java_com_rm_parrotmetric_Core_selectedEdges(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 35: {  // selectedFaces
            auto r = Java_com_rm_parrotmetric_Core_selectedFaces(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 36: {  // selectedRegions
            auto r = Java_com_rm_parrotmetric_Core_selectedRegions(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 37: {  // select
            auto a0 = in.strings();
            auto a1 = in.ints();
            auto a2 = in.strings();
            Java_com_rm_parrotmetric_Core_select(env, nullptr, a0, a1, a2);
            break;
        }
        case 38: {  // findRegions
            auto a0 = in.ints();
            auto a1 = in.ints();
            auto a2 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_findRegions(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 39: {  // surfaceCreated
            Java_com_rm_parrotmetric_Core_surfaceCreated(env, nullptr);
            break;
        }
        case 40: {  // surfaceChanged
            auto a0 = in.i32();
            auto a1 = in.i32();
            Java_com_rm_parrotmetric_Core_surfaceChanged(env, nullptr, a0, a1);
            break;
        }
        case 41: {  // drawFrame
            auto r = Java_com_rm_parrotmetric_Core_drawFrame(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 42: {  // setDensity
            auto a0 = in.f32();
            Java_com_rm_parrotmetric_Core_setDensity(env, nullptr, a0);
            break;
        }
        case 43: {  // orbit
            auto a0 = in.f32();
            auto a1 = in.f32();
            Java_com_rm_parrotmetric_Core_orbit(env, nullptr, a0, a1);
            break;
        }
        case 44: {  // pan
            auto a0 = in.f32();
            auto a1 = in.f32();
            Java_com_rm_parrotmetric_Core_pan(env, nullptr, a0, a1);
            break;
        }
        case 45: {  // zoom
            auto a0 = in.f32();
            Java_com_rm_parrotmetric_Core_zoom(env, nullptr, a0);
            break;
        }
        case 46: {  // fit
            Java_com_rm_parrotmetric_Core_fit(env, nullptr);
            break;
        }
        case 47: {  // viewFrom
            auto a0 = in.f32();
            auto a1 = in.f32();
            Java_com_rm_parrotmetric_Core_viewFrom(env, nullptr, a0, a1);
            break;
        }
        case 48: {  // cameraState
            auto r = Java_com_rm_parrotmetric_Core_cameraState(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        default:
            throwMessage("No such call");
    }
}

}  // namespace pmweb
