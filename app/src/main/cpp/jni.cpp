// The JNI side of com.rm.parrotmetric.Core. Model calls come from a worker
// thread and the renderer calls from the GL thread; the lock keeps them apart.
#include <jni.h>

#include <algorithm>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>

#include "io/exchange.h"
#include "display/display_mesh.h"
#include "mesh/mesh_body.h"
#include "mesh/stl.h"
#include "render/renderer.h"
#include "sketch/regions.h"
#include "solid/solid.h"

namespace {

std::mutex lock;
pm::Renderer renderer;
std::optional<pm::MeshBody> body;  // What's shown and what the mesh exports write.
std::optional<pm::Solid> solid;    // The same as a solid, until a mesh edit; for STEP and IGES.

std::vector<pm::Pick> selection;
std::optional<pm::DisplayMesh> modelDisplay;
std::vector<pm::DisplayMesh> sketchDisplays;  // Finished sketches, drawn as light lines after the model.

void updateRenderer(bool refit) {
    std::vector<pm::DisplayMesh> shown;
    if (modelDisplay) shown.push_back(*modelDisplay);
    for (const auto& s : sketchDisplays) shown.push_back(s);
    renderer.setBodies(std::move(shown), refit);
}

void show(const pm::MeshBody& b, std::optional<pm::Solid> s = std::nullopt, bool refit = true) {
    modelDisplay = s ? s->display() : pm::displayMesh(b.toMesh());
    body = b;
    solid = std::move(s);
    selection.clear();
    updateRenderer(refit);
}

jintArray selectionCounts(JNIEnv* env) {
    jint counts[2] = {0, 0};
    for (const auto& p : selection) counts[p.kind == pm::Pick::Edge ? 1 : 0]++;
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, counts);
    return out;
}

jstring error(JNIEnv* env, const std::exception& e) { return env->NewStringUTF(e.what()); }

std::vector<uint8_t> bytesOf(JNIEnv* env, jbyteArray data) {
    std::vector<uint8_t> bytes(size_t(env->GetArrayLength(data)));
    env->GetByteArrayRegion(data, 0, jsize(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

jbyteArray array(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    jbyteArray out = env->NewByteArray(jsize(bytes.size()));
    env->SetByteArrayRegion(out, 0, jsize(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return out;
}

/** The format numbers Core.kt uses. */
enum Format { Stl = 0, Step = 1, Iges = 2 };

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_showFilletedBox(JNIEnv* env, jobject, jdouble size, jdouble radius) {
    try {
        pm::Solid s = pm::Solid::box(size, size, size).filletAllEdges(radius);
        pm::MeshBody b = pm::MeshBody::fromMesh(s.tessellate());
        std::lock_guard<std::mutex> g(lock);
        show(b, s);
        return nullptr;
    } catch (const std::exception& e) {
        return error(env, e);
    }
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setScratchDirectory(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    pm::setScratchDirectory(p);
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_importFile(JNIEnv* env, jobject, jbyteArray data, jint format) {
    try {
        std::vector<uint8_t> bytes = bytesOf(env, data);
        if (format == Stl) {
            pm::MeshBody b = pm::MeshBody::fromMesh(pm::readStl(bytes));
            std::lock_guard<std::mutex> g(lock);
            show(b);
        } else {
            pm::Solid s = pm::readSolid(bytes, format == Step ? pm::SolidFormat::Step : pm::SolidFormat::Iges);
            pm::MeshBody b = pm::MeshBody::fromMesh(s.tessellate());
            std::lock_guard<std::mutex> g(lock);
            show(b, s);
        }
        return nullptr;
    } catch (const std::exception& e) {
        return error(env, e);
    }
}

/** Cuts a square hole down through the middle of what's shown, half its width. */
JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_cutHole(JNIEnv* env, jobject) {
    try {
        // Works on a copy so the GL thread isn't held up during the boolean.
        std::optional<pm::MeshBody> current;
        {
            std::lock_guard<std::mutex> g(lock);
            current = body;
        }
        if (!current) return env->NewStringUTF("Nothing to cut");
        pm::Mesh m = current->toMesh();
        float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
        for (const auto& v : m.vertices)
            for (int i = 0; i < 3; ++i) {
                lo[i] = std::min(lo[i], v[i]);
                hi[i] = std::max(hi[i], v[i]);
            }
        float w = (hi[0] - lo[0]) * 0.5f, d = (hi[1] - lo[1]) * 0.5f, h = hi[2] - lo[2];
        pm::MeshBody tool = pm::MeshBody::box(w, d, h * 2).translated(lo[0] + w * 0.5f, lo[1] + d * 0.5f, lo[2] - h * 0.5f);
        pm::MeshBody result = current->boolean(tool, pm::BooleanOp::Cut);
        std::lock_guard<std::mutex> g(lock);
        show(result, std::nullopt, false);
        return nullptr;
    } catch (const std::exception& e) {
        return error(env, e);
    }
}

/** The file's bytes, or null if what's shown can't be written in that format. */
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_exportFile(JNIEnv* env, jobject, jint format) {
    try {
        std::lock_guard<std::mutex> g(lock);
        if (format == Stl) return body ? array(env, pm::writeStl(body->toMesh())) : nullptr;
        if (!solid) return nullptr;
        return array(env, pm::writeSolid(*solid, format == Step ? pm::SolidFormat::Step : pm::SolidFormat::Iges));
    } catch (const std::exception&) {
        return nullptr;
    }
}

JNIEXPORT jint JNICALL Java_com_rm_parrotmetric_Core_triangleCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    return body ? jint(body->triangleCount()) : 0;
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_surfaceCreated(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    renderer.surfaceCreated();
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_surfaceChanged(JNIEnv*, jobject, jint w, jint h) {
    std::lock_guard<std::mutex> g(lock);
    renderer.surfaceChanged(w, h);
}

/** True while the view is moving and wants another frame. */
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_drawFrame(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    return renderer.draw();
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setDensity(JNIEnv*, jobject, jfloat d) {
    std::lock_guard<std::mutex> g(lock);
    renderer.setDensity(d);
}

/**
 * Selects or unselects what's under the point, or clears the selection on
 * empty space. GL thread. Returns how many faces and edges are selected.
 */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_tap(JNIEnv* env, jobject, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> g(lock);
    pm::Pick p = renderer.pick(x, y);
    if (p.kind == pm::Pick::None) {
        selection.clear();
    } else {
        auto it = std::find(selection.begin(), selection.end(), p);
        if (it != selection.end()) selection.erase(it);
        else selection.push_back(p);
    }
    renderer.setSelection(selection);
    return selectionCounts(env);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_clearSelection(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    selection.clear();
    renderer.setSelection(selection);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_pan(JNIEnv*, jobject, jfloat dx, jfloat dy) {
    std::lock_guard<std::mutex> g(lock);
    renderer.pan(dx, dy);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_fit(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    renderer.fit();
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_viewFrom(JNIEnv*, jobject, jfloat yaw, jfloat pitch) {
    std::lock_guard<std::mutex> g(lock);
    renderer.viewFrom(yaw, pitch);
}

/** The camera's yaw and pitch in radians, for the orientation cube. */
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_cameraAngles(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    jfloat a[2] = {renderer.yaw(), renderer.pitch()};
    jfloatArray out = env->NewFloatArray(2);
    env->SetFloatArrayRegion(out, 0, 2, a);
    return out;
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_orbit(JNIEnv*, jobject, jfloat dx, jfloat dy) {
    std::lock_guard<std::mutex> g(lock);
    renderer.orbit(dx, dy);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_zoom(JNIEnv*, jobject, jfloat f) {
    std::lock_guard<std::mutex> g(lock);
    renderer.zoom(f);
}

/**
 * The closed regions of a sketch's curves. Each curve is a kind (0 line,
 * 1 circle, 2 arc), an id and seven numbers: x1 y1 x2 y2 r a0 a1. Returned
 * flattened: region count, then per region its area, id count, ids, loop
 * count, and per loop its point count and x y pairs.
 */
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_findRegions(JNIEnv* env, jobject, jintArray kinds, jintArray ids, jdoubleArray numbers) {
    jsize n = env->GetArrayLength(kinds);
    std::vector<jint> k(static_cast<size_t>(n)), id(static_cast<size_t>(n));
    std::vector<jdouble> v(size_t(n) * 7);
    env->GetIntArrayRegion(kinds, 0, n, k.data());
    env->GetIntArrayRegion(ids, 0, n, id.data());
    env->GetDoubleArrayRegion(numbers, 0, n * 7, v.data());
    std::vector<pm::SketchCurve> curves(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto& c = curves[size_t(i)];
        c.kind = pm::SketchCurve::Kind(k[size_t(i)]);
        c.id = id[size_t(i)];
        const double* d = &v[size_t(i) * 7];
        c.x1 = d[0]; c.y1 = d[1]; c.x2 = d[2]; c.y2 = d[3]; c.r = d[4]; c.a0 = d[5]; c.a1 = d[6];
    }
    std::vector<float> out;
    auto regions = pm::findRegions(curves);
    out.push_back(float(regions.size()));
    for (const auto& r : regions) {
        out.push_back(float(r.area));
        out.push_back(float(r.curveIds.size()));
        for (int i : r.curveIds) out.push_back(float(i));
        out.push_back(float(r.loops.size()));
        for (const auto& loop : r.loops) {
            out.push_back(float(loop.size()));
            for (const auto& p : loop) out.insert(out.end(), {p[0], p[1]});
        }
    }
    jfloatArray result = env->NewFloatArray(jsize(out.size()));
    env->SetFloatArrayRegion(result, 0, jsize(out.size()), out.data());
    return result;
}

/** Yaw, pitch, viewport width and height, then the last frame's view-projection matrix (16, column-major). */
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_cameraState(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    float a[20] = {renderer.yaw(), renderer.pitch(), float(renderer.width()), float(renderer.height())};
    std::copy(renderer.viewProjection(), renderer.viewProjection() + 16, a + 4);
    jfloatArray out = env->NewFloatArray(20);
    env->SetFloatArrayRegion(out, 0, 20, a);
    return out;
}

/** The selected face's centre and outward normal if exactly one flat face of the solid is selected, else null. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_selectedFacePlane(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    if (!solid || selection.size() != 1 || selection[0].kind != pm::Pick::Face || selection[0].body != 0) return nullptr;
    std::vector<double> plane = solid->facePlane(selection[0].index);
    if (plane.empty()) return nullptr;
    jdoubleArray out = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(out, 0, 6, plane.data());
    return out;
}

/**
 * Finished sketches to draw in 3D: flattened as sketch count, then per sketch
 * its polyline count, and per polyline its point count and x y z triples.
 */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setSketches(JNIEnv* env, jobject, jfloatArray data) {
    jsize n = env->GetArrayLength(data);
    std::vector<float> d(static_cast<size_t>(n));
    env->GetFloatArrayRegion(data, 0, n, d.data());
    std::vector<pm::DisplayMesh> sketches;
    size_t i = 0;
    size_t count = n > 0 ? size_t(d[i++]) : 0;
    for (size_t s = 0; s < count; ++s) {
        pm::DisplayMesh m;
        const float light[4] = {0.55f, 0.78f, 0.94f, 1.0f};
        std::copy(light, light + 4, m.edgeColour);
        size_t lines = size_t(d[i++]);
        for (size_t l = 0; l < lines; ++l) {
            size_t pts = size_t(d[i++]);
            pm::DisplayMesh::Edge e;
            e.points.assign(d.begin() + long(i), d.begin() + long(i + pts * 3));
            i += pts * 3;
            m.edges.push_back(std::move(e));
        }
        sketches.push_back(std::move(m));
    }
    std::lock_guard<std::mutex> g(lock);
    sketchDisplays = std::move(sketches);
    updateRenderer(false);
}

}  // extern "C"
