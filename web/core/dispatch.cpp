// Written by gen_bridge.py from NativeCore.kt; run that instead of editing this.
// Runs jni.cpp's function for each call, numbered in NativeCore's order.

#include "bridge.h"

extern "C" {
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setScratchDirectory(JNIEnv*, jobject, jstring);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_extrude(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_revolve(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_combine(JNIEnv*, jobject, jint, jlong, jlong, jint);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_fillet(JNIEnv*, jobject, jint, jlong, jobjectArray, jdouble, jint, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_offsetFaces(JNIEnv*, jobject, jint, jlong, jobjectArray, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_deleteFaces(JNIEnv*, jobject, jint, jlong, jobjectArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_meshEdit(JNIEnv*, jobject, jint, jlong, jint, jdouble, jint);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_patch(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_patchEdges(JNIEnv*, jobject, jint, jlong, jobjectArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_stitch(JNIEnv*, jobject, jint, jlongArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_gather(JNIEnv*, jobject, jlongArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_emboss(JNIEnv*, jobject, jint, jlong, jstring, jdoubleArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jdouble, jboolean);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_thicken(JNIEnv*, jobject, jint, jlong, jdouble, jboolean);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_rib(JNIEnv*, jobject, jint, jlong, jdoubleArray, jintArray, jintArray, jdoubleArray, jdouble, jboolean, jboolean);
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
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_snapFitTool(JNIEnv*, jobject, jint, jdoubleArray, jdoubleArray, jdoubleArray, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean, jstring);
JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_repairReport(JNIEnv*, jobject, jbyteArray, jint);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_convertToSolid(JNIEnv*, jobject, jint, jlong);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_bodyCentre(JNIEnv*, jobject, jlong);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_sweep(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jlong, jobjectArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_pipe(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jdoubleArray, jlong, jobjectArray, jdouble, jdouble);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_pathPlaces(JNIEnv*, jobject, jdoubleArray, jintArray, jintArray, jdoubleArray, jlong, jobjectArray, jint, jdouble, jboolean, jboolean);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_coil(JNIEnv*, jobject, jint, jdoubleArray, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_thread(JNIEnv*, jobject, jint, jlong, jstring, jdouble, jdouble);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_lipTool(JNIEnv*, jobject, jint, jlong, jstring, jdouble, jdouble, jdouble, jstring);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_textOutline(JNIEnv*, jobject, jstring, jdouble, jboolean);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_canvasImage(JNIEnv*, jobject, jint, jbyteArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_loft(JNIEnv*, jobject, jint, jdoubleArray, jintArray, jintArray, jintArray, jdoubleArray, jintArray, jintArray, jdoubleArray, jboolean, jdouble, jboolean, jdoubleArray, jintArray, jintArray, jdoubleArray, jlong, jobjectArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_primitive(JNIEnv*, jobject, jint, jdoubleArray, jint, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_bounds(JNIEnv*, jobject, jlong);
JNIEXPORT jlongArray JNICALL Java_com_rm_parrotmetric_Core_splitBy(JNIEnv*, jobject, jint, jlong, jlong);
JNIEXPORT jdouble JNICALL Java_com_rm_parrotmetric_Core_overlapVolume(JNIEnv*, jobject, jlong, jlong);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedCorners(JNIEnv*, jobject);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_corner(JNIEnv*, jobject, jlong, jstring);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_shapeOf(JNIEnv*, jobject, jlong, jstring, jboolean);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_alongEdge(JNIEnv*, jobject, jlong, jstring, jdouble);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_properties(JNIEnv*, jobject, jlong);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_section(JNIEnv*, jobject, jlongArray, jdoubleArray);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_projectView(JNIEnv*, jobject, jlongArray, jdoubleArray, jboolean, jdouble);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_selectedMeshPlane(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_retain(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_release(JNIEnv*, jobject, jlong);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_isMesh(JNIEnv*, jobject, jlong);
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_exportBodies(JNIEnv*, jobject, jlongArray, jobjectArray, jintArray, jint, jint);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_show(JNIEnv*, jobject, jlongArray, jdoubleArray, jintArray, jintArray, jintArray, jdoubleArray, jdoubleArray, jdoubleArray, jdoubleArray, jintArray, jdoubleArray, jboolean);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectedPlanes(JNIEnv*, jobject);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_measure(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setSection(JNIEnv*, jobject, jboolean, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setAnalysis(JNIEnv*, jobject, jint, jdouble);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_faceOutline(JNIEnv*, jobject, jlong, jstring, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_rm_parrotmetric_Core_shownTriangles(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setDisplayDetail(JNIEnv*, jobject, jint);
JNIEXPORT jdouble JNICALL Java_com_rm_parrotmetric_Core_speedTest(JNIEnv*, jobject);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_tap(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_click(JNIEnv*, jobject, jfloat, jfloat, jboolean);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_clickChain(JNIEnv*, jobject, jfloat, jfloat, jboolean);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectBox(JNIEnv*, jobject, jfloat, jfloat, jfloat, jfloat, jboolean, jboolean);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_clearSelection(JNIEnv*, jobject);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedEdges(JNIEnv*, jobject);
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedFaces(JNIEnv*, jobject);
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectedRegions(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_select(JNIEnv*, jobject, jobjectArray, jintArray, jobjectArray, jobjectArray);
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_findRegions(JNIEnv*, jobject, jintArray, jintArray, jdoubleArray);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_surfaceCreated(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_surfaceChanged(JNIEnv*, jobject, jint, jint);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_drawFrame(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setDensity(JNIEnv*, jobject, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_orbit(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_pan(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_zoom(JNIEnv*, jobject, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_zoomAt(JNIEnv*, jobject, jfloat, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_fit(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setCovered(JNIEnv*, jobject, jfloat, jfloat, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_viewFrom(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_cameraState(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setAreasFirst(JNIEnv*, jobject, jboolean);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptStart(JNIEnv*, jobject, jlong, jbyteArray, jint, jdouble, jint);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptHit(JNIEnv*, jobject, jfloat, jfloat);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptBegin(JNIEnv*, jobject, jfloat, jfloat, jfloat, jint, jfloat, jfloat, jboolean, jint, jboolean, jfloat, jboolean, jboolean);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptMove(JNIEnv*, jobject, jfloat, jfloat, jfloat);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptEnd(JNIEnv*, jobject);
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptUndo(JNIEnv*, jobject, jboolean);
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_sculptInfo(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptChange(JNIEnv*, jobject, jint, jdouble);
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_sculptFinish(JNIEnv*, jobject, jboolean);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_sculptedBody(JNIEnv*, jobject, jint, jbyteArray, jlong);
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_sculptPack(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptLook(JNIEnv*, jobject, jint, jboolean);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_gear(JNIEnv*, jobject, jint, jdoubleArray, jdouble, jdouble, jdouble, jdouble, jint, jdouble, jdouble, jdouble, jboolean, jdouble, jdouble);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_threadMarks(JNIEnv*, jobject, jlongArray, jobjectArray, jdoubleArray);
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_bodyOffsets(JNIEnv*, jobject, jlongArray, jdoubleArray);
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_fastener(JNIEnv*, jobject, jint, jdoubleArray, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
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
            auto a11 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_extrude(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
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
            auto a4 = in.i32();
            auto a5 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_fillet(env, nullptr, a0, a1, a2, a3, a4, a5);
            if (!failed()) out.put(r);
            break;
        }
        case 5: {  // offsetFaces
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto a3 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_offsetFaces(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 6: {  // deleteFaces
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto r = Java_com_rm_parrotmetric_Core_deleteFaces(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 7: {  // meshEdit
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.i32();
            auto a3 = in.f64();
            auto a4 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_meshEdit(env, nullptr, a0, a1, a2, a3, a4);
            if (!failed()) out.put(r);
            break;
        }
        case 8: {  // patch
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.doubles();
            auto a5 = in.ints();
            auto a6 = in.ints();
            auto a7 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_patch(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7);
            if (!failed()) out.put(r);
            break;
        }
        case 9: {  // patchEdges
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto r = Java_com_rm_parrotmetric_Core_patchEdges(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 10: {  // stitch
            auto a0 = in.i32();
            auto a1 = in.longs();
            auto r = Java_com_rm_parrotmetric_Core_stitch(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 11: {  // gather
            auto a0 = in.longs();
            auto r = Java_com_rm_parrotmetric_Core_gather(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 12: {  // emboss
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.string();
            auto a3 = in.doubles();
            auto a4 = in.ints();
            auto a5 = in.ints();
            auto a6 = in.doubles();
            auto a7 = in.ints();
            auto a8 = in.ints();
            auto a9 = in.doubles();
            auto a10 = in.f64();
            auto a11 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_emboss(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
            if (!failed()) out.put(r);
            break;
        }
        case 13: {  // thicken
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.f64();
            auto a3 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_thicken(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 14: {  // rib
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.doubles();
            auto a3 = in.ints();
            auto a4 = in.ints();
            auto a5 = in.doubles();
            auto a6 = in.f64();
            auto a7 = in.boolean();
            auto a8 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_rib(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8);
            if (!failed()) out.put(r);
            break;
        }
        case 15: {  // chamfer
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
        case 16: {  // overlaps
            auto a0 = in.i64();
            auto a1 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_overlaps(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 17: {  // facePlane
            auto a0 = in.i64();
            auto a1 = in.string();
            auto r = Java_com_rm_parrotmetric_Core_facePlane(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 18: {  // faceNames
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_faceNames(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 19: {  // signature
            auto a0 = in.i64();
            auto a1 = in.string();
            auto a2 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_signature(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 20: {  // relocate
            auto a0 = in.i64();
            auto a1 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_relocate(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 21: {  // importBody
            auto a0 = in.i32();
            auto a1 = in.bytes();
            auto a2 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_importBody(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 22: {  // shell
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto a3 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_shell(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 23: {  // draft
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.strings();
            auto a3 = in.string();
            auto a4 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_draft(env, nullptr, a0, a1, a2, a3, a4);
            if (!failed()) out.put(r);
            break;
        }
        case 24: {  // transform
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.doubles();
            auto a3 = in.string();
            auto r = Java_com_rm_parrotmetric_Core_transform(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 25: {  // split
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_split(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 26: {  // holeTool
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
        case 27: {  // snapFitTool
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.doubles();
            auto a3 = in.doubles();
            auto a4 = in.f64();
            auto a5 = in.f64();
            auto a6 = in.f64();
            auto a7 = in.f64();
            auto a8 = in.f64();
            auto a9 = in.f64();
            auto a10 = in.boolean();
            auto a11 = in.string();
            auto r = Java_com_rm_parrotmetric_Core_snapFitTool(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
            if (!failed()) out.put(r);
            break;
        }
        case 28: {  // repairReport
            auto a0 = in.bytes();
            auto a1 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_repairReport(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 29: {  // convertToSolid
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_convertToSolid(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 30: {  // bodyCentre
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_bodyCentre(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 31: {  // sweep
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.doubles();
            auto a5 = in.ints();
            auto a6 = in.ints();
            auto a7 = in.doubles();
            auto a8 = in.doubles();
            auto a9 = in.ints();
            auto a10 = in.ints();
            auto a11 = in.doubles();
            auto a12 = in.i64();
            auto a13 = in.strings();
            auto r = Java_com_rm_parrotmetric_Core_sweep(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13);
            if (!failed()) out.put(r);
            break;
        }
        case 32: {  // pipe
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.doubles();
            auto a5 = in.i64();
            auto a6 = in.strings();
            auto a7 = in.f64();
            auto a8 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_pipe(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8);
            if (!failed()) out.put(r);
            break;
        }
        case 33: {  // pathPlaces
            auto a0 = in.doubles();
            auto a1 = in.ints();
            auto a2 = in.ints();
            auto a3 = in.doubles();
            auto a4 = in.i64();
            auto a5 = in.strings();
            auto a6 = in.i32();
            auto a7 = in.f64();
            auto a8 = in.boolean();
            auto a9 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_pathPlaces(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9);
            if (!failed()) out.put(r);
            break;
        }
        case 34: {  // coil
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.f64();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto a5 = in.f64();
            auto a6 = in.f64();
            auto a7 = in.f64();
            auto a8 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_coil(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8);
            if (!failed()) out.put(r);
            break;
        }
        case 35: {  // thread
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.string();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_thread(env, nullptr, a0, a1, a2, a3, a4);
            if (!failed()) out.put(r);
            break;
        }
        case 36: {  // lipTool
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.string();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto a5 = in.f64();
            auto a6 = in.string();
            auto r = Java_com_rm_parrotmetric_Core_lipTool(env, nullptr, a0, a1, a2, a3, a4, a5, a6);
            if (!failed()) out.put(r);
            break;
        }
        case 37: {  // textOutline
            auto a0 = in.string();
            auto a1 = in.f64();
            auto a2 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_textOutline(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 38: {  // canvasImage
            auto a0 = in.i32();
            auto a1 = in.bytes();
            auto r = Java_com_rm_parrotmetric_Core_canvasImage(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 39: {  // loft
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.ints();
            auto a5 = in.doubles();
            auto a6 = in.ints();
            auto a7 = in.ints();
            auto a8 = in.doubles();
            auto a9 = in.boolean();
            auto a10 = in.f64();
            auto a11 = in.boolean();
            auto a12 = in.doubles();
            auto a13 = in.ints();
            auto a14 = in.ints();
            auto a15 = in.doubles();
            auto a16 = in.i64();
            auto a17 = in.strings();
            auto r = Java_com_rm_parrotmetric_Core_loft(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17);
            if (!failed()) out.put(r);
            break;
        }
        case 40: {  // primitive
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.i32();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto a5 = in.f64();
            auto a6 = in.f64();
            auto a7 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_primitive(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7);
            if (!failed()) out.put(r);
            break;
        }
        case 41: {  // bounds
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_bounds(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 42: {  // splitBy
            auto a0 = in.i32();
            auto a1 = in.i64();
            auto a2 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_splitBy(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 43: {  // overlapVolume
            auto a0 = in.i64();
            auto a1 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_overlapVolume(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 44: {  // selectedCorners
            auto r = Java_com_rm_parrotmetric_Core_selectedCorners(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 45: {  // corner
            auto a0 = in.i64();
            auto a1 = in.string();
            auto r = Java_com_rm_parrotmetric_Core_corner(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 46: {  // shapeOf
            auto a0 = in.i64();
            auto a1 = in.string();
            auto a2 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_shapeOf(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 47: {  // alongEdge
            auto a0 = in.i64();
            auto a1 = in.string();
            auto a2 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_alongEdge(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 48: {  // properties
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_properties(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 49: {  // section
            auto a0 = in.longs();
            auto a1 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_section(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 50: {  // projectView
            auto a0 = in.longs();
            auto a1 = in.doubles();
            auto a2 = in.boolean();
            auto a3 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_projectView(env, nullptr, a0, a1, a2, a3);
            if (!failed()) out.put(r);
            break;
        }
        case 51: {  // selectedMeshPlane
            auto r = Java_com_rm_parrotmetric_Core_selectedMeshPlane(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 52: {  // retain
            auto a0 = in.i64();
            Java_com_rm_parrotmetric_Core_retain(env, nullptr, a0);
            break;
        }
        case 53: {  // release
            auto a0 = in.i64();
            Java_com_rm_parrotmetric_Core_release(env, nullptr, a0);
            break;
        }
        case 54: {  // isMesh
            auto a0 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_isMesh(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 55: {  // exportBodies
            auto a0 = in.longs();
            auto a1 = in.strings();
            auto a2 = in.ints();
            auto a3 = in.i32();
            auto a4 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_exportBodies(env, nullptr, a0, a1, a2, a3, a4);
            if (!failed()) out.put(r);
            break;
        }
        case 56: {  // show
            auto a0 = in.longs();
            auto a1 = in.doubles();
            auto a2 = in.ints();
            auto a3 = in.ints();
            auto a4 = in.ints();
            auto a5 = in.doubles();
            auto a6 = in.doubles();
            auto a7 = in.doubles();
            auto a8 = in.doubles();
            auto a9 = in.ints();
            auto a10 = in.doubles();
            auto a11 = in.boolean();
            Java_com_rm_parrotmetric_Core_show(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
            break;
        }
        case 57: {  // selectedPlanes
            auto r = Java_com_rm_parrotmetric_Core_selectedPlanes(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 58: {  // measure
            auto r = Java_com_rm_parrotmetric_Core_measure(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 59: {  // setSection
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
        case 60: {  // setAnalysis
            auto a0 = in.i32();
            auto a1 = in.f64();
            Java_com_rm_parrotmetric_Core_setAnalysis(env, nullptr, a0, a1);
            break;
        }
        case 61: {  // faceOutline
            auto a0 = in.i64();
            auto a1 = in.string();
            auto a2 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_faceOutline(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 62: {  // shownTriangles
            auto r = Java_com_rm_parrotmetric_Core_shownTriangles(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 63: {  // setDisplayDetail
            auto a0 = in.i32();
            Java_com_rm_parrotmetric_Core_setDisplayDetail(env, nullptr, a0);
            break;
        }
        case 64: {  // speedTest
            auto r = Java_com_rm_parrotmetric_Core_speedTest(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 65: {  // tap
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto r = Java_com_rm_parrotmetric_Core_tap(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 66: {  // click
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto a2 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_click(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 67: {  // clickChain
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto a2 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_clickChain(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 68: {  // selectBox
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto a2 = in.f32();
            auto a3 = in.f32();
            auto a4 = in.boolean();
            auto a5 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_selectBox(env, nullptr, a0, a1, a2, a3, a4, a5);
            if (!failed()) out.put(r);
            break;
        }
        case 69: {  // clearSelection
            Java_com_rm_parrotmetric_Core_clearSelection(env, nullptr);
            break;
        }
        case 70: {  // selectedEdges
            auto r = Java_com_rm_parrotmetric_Core_selectedEdges(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 71: {  // selectedFaces
            auto r = Java_com_rm_parrotmetric_Core_selectedFaces(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 72: {  // selectedRegions
            auto r = Java_com_rm_parrotmetric_Core_selectedRegions(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 73: {  // select
            auto a0 = in.strings();
            auto a1 = in.ints();
            auto a2 = in.strings();
            auto a3 = in.strings();
            Java_com_rm_parrotmetric_Core_select(env, nullptr, a0, a1, a2, a3);
            break;
        }
        case 74: {  // findRegions
            auto a0 = in.ints();
            auto a1 = in.ints();
            auto a2 = in.doubles();
            auto r = Java_com_rm_parrotmetric_Core_findRegions(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 75: {  // surfaceCreated
            Java_com_rm_parrotmetric_Core_surfaceCreated(env, nullptr);
            break;
        }
        case 76: {  // surfaceChanged
            auto a0 = in.i32();
            auto a1 = in.i32();
            Java_com_rm_parrotmetric_Core_surfaceChanged(env, nullptr, a0, a1);
            break;
        }
        case 77: {  // drawFrame
            auto r = Java_com_rm_parrotmetric_Core_drawFrame(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 78: {  // setDensity
            auto a0 = in.f32();
            Java_com_rm_parrotmetric_Core_setDensity(env, nullptr, a0);
            break;
        }
        case 79: {  // orbit
            auto a0 = in.f32();
            auto a1 = in.f32();
            Java_com_rm_parrotmetric_Core_orbit(env, nullptr, a0, a1);
            break;
        }
        case 80: {  // pan
            auto a0 = in.f32();
            auto a1 = in.f32();
            Java_com_rm_parrotmetric_Core_pan(env, nullptr, a0, a1);
            break;
        }
        case 81: {  // zoom
            auto a0 = in.f32();
            Java_com_rm_parrotmetric_Core_zoom(env, nullptr, a0);
            break;
        }
        case 82: {  // zoomAt
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto a2 = in.f32();
            Java_com_rm_parrotmetric_Core_zoomAt(env, nullptr, a0, a1, a2);
            break;
        }
        case 83: {  // fit
            Java_com_rm_parrotmetric_Core_fit(env, nullptr);
            break;
        }
        case 84: {  // setCovered
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto a2 = in.f32();
            auto a3 = in.f32();
            Java_com_rm_parrotmetric_Core_setCovered(env, nullptr, a0, a1, a2, a3);
            break;
        }
        case 85: {  // viewFrom
            auto a0 = in.f32();
            auto a1 = in.f32();
            Java_com_rm_parrotmetric_Core_viewFrom(env, nullptr, a0, a1);
            break;
        }
        case 86: {  // cameraState
            auto r = Java_com_rm_parrotmetric_Core_cameraState(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 87: {  // setAreasFirst
            auto a0 = in.boolean();
            Java_com_rm_parrotmetric_Core_setAreasFirst(env, nullptr, a0);
            break;
        }
        case 88: {  // sculptStart
            auto a0 = in.i64();
            auto a1 = in.bytes();
            auto a2 = in.i32();
            auto a3 = in.f64();
            auto a4 = in.i32();
            auto r = Java_com_rm_parrotmetric_Core_sculptStart(env, nullptr, a0, a1, a2, a3, a4);
            if (!failed()) out.put(r);
            break;
        }
        case 89: {  // sculptHit
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto r = Java_com_rm_parrotmetric_Core_sculptHit(env, nullptr, a0, a1);
            if (!failed()) out.put(r);
            break;
        }
        case 90: {  // sculptBegin
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto a2 = in.f32();
            auto a3 = in.i32();
            auto a4 = in.f32();
            auto a5 = in.f32();
            auto a6 = in.boolean();
            auto a7 = in.i32();
            auto a8 = in.boolean();
            auto a9 = in.f32();
            auto a10 = in.boolean();
            auto a11 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_sculptBegin(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
            if (!failed()) out.put(r);
            break;
        }
        case 91: {  // sculptMove
            auto a0 = in.f32();
            auto a1 = in.f32();
            auto a2 = in.f32();
            Java_com_rm_parrotmetric_Core_sculptMove(env, nullptr, a0, a1, a2);
            break;
        }
        case 92: {  // sculptEnd
            Java_com_rm_parrotmetric_Core_sculptEnd(env, nullptr);
            break;
        }
        case 93: {  // sculptUndo
            auto a0 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_sculptUndo(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 94: {  // sculptInfo
            auto r = Java_com_rm_parrotmetric_Core_sculptInfo(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 95: {  // sculptChange
            auto a0 = in.i32();
            auto a1 = in.f64();
            Java_com_rm_parrotmetric_Core_sculptChange(env, nullptr, a0, a1);
            break;
        }
        case 96: {  // sculptFinish
            auto a0 = in.boolean();
            auto r = Java_com_rm_parrotmetric_Core_sculptFinish(env, nullptr, a0);
            if (!failed()) out.put(r);
            break;
        }
        case 97: {  // sculptedBody
            auto a0 = in.i32();
            auto a1 = in.bytes();
            auto a2 = in.i64();
            auto r = Java_com_rm_parrotmetric_Core_sculptedBody(env, nullptr, a0, a1, a2);
            if (!failed()) out.put(r);
            break;
        }
        case 98: {  // sculptPack
            auto r = Java_com_rm_parrotmetric_Core_sculptPack(env, nullptr);
            if (!failed()) out.put(r);
            break;
        }
        case 99: {  // sculptLook
            auto a0 = in.i32();
            auto a1 = in.boolean();
            Java_com_rm_parrotmetric_Core_sculptLook(env, nullptr, a0, a1);
            break;
        }
        case 100: {  // gear
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.f64();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto a5 = in.f64();
            auto a6 = in.i32();
            auto a7 = in.f64();
            auto a8 = in.f64();
            auto a9 = in.f64();
            auto a10 = in.boolean();
            auto a11 = in.f64();
            auto a12 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_gear(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12);
            if (!failed()) out.put(r);
            break;
        }
        case 101: {  // threadMarks
            auto a0 = in.longs();
            auto a1 = in.strings();
            auto a2 = in.doubles();
            Java_com_rm_parrotmetric_Core_threadMarks(env, nullptr, a0, a1, a2);
            break;
        }
        case 102: {  // bodyOffsets
            auto a0 = in.longs();
            auto a1 = in.doubles();
            Java_com_rm_parrotmetric_Core_bodyOffsets(env, nullptr, a0, a1);
            break;
        }
        case 103: {  // fastener
            auto a0 = in.i32();
            auto a1 = in.doubles();
            auto a2 = in.i32();
            auto a3 = in.f64();
            auto a4 = in.f64();
            auto a5 = in.f64();
            auto a6 = in.f64();
            auto a7 = in.f64();
            auto a8 = in.f64();
            auto r = Java_com_rm_parrotmetric_Core_fastener(env, nullptr, a0, a1, a2, a3, a4, a5, a6, a7, a8);
            if (!failed()) out.put(r);
            break;
        }
        default:
            throwMessage("No such call");
    }
}

}  // namespace pmweb
