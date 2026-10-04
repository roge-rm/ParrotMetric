// The JNI side of com.rm.parrotmetric.Core. Kernel calls come from a worker
// thread and the renderer calls from the GL thread; the lock keeps them apart.
// Kernel calls report failure by throwing a Java RuntimeException with a
// reason fit to show.
#include <jni.h>

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BRepGProp.hxx>
#include <GProp_GProps.hxx>
#include <Standard_Failure.hxx>
#include <cstdio>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <TopoDS.hxx>
#include <gp_Circ.hxx>
#include <BRep_Builder.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS_Compound.hxx>

#include <algorithm>
#include <atomic>
#include <unordered_map>
#include <cmath>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>

#include "display/display_mesh.h"
#include "io/exchange.h"
#include "io/mesh_formats.h"
#include "mesh/mesh_body.h"
#include "mesh/repair.h"
#include "mesh/stl.h"
#include "model/operations.h"
#include "model/store.h"
#include "render/renderer.h"
#include "sketch/region_faces.h"
#include "sketch/regions.h"
#include "solid/solid.h"
#include "solid/speed.h"

namespace {

std::mutex lock;
pm::Renderer renderer;
pm::BodyStore store;

/** What each displayed thing is, in the order the renderer numbers them. */
struct Shown {
    bool sketch = false;
    int plane = -1;                                 // Construction planes: which of those passed to show.
    std::vector<std::string> faceNames, edgeNames;  // Bodies: names by face and edge number.
    int sketchIndex = 0;                            // Sketches: which of the sketches passed to show.
    std::shared_ptr<pm::DisplayMesh> mesh;          // Mesh bodies: what was drawn, for finding a picked flat area.
};
std::vector<Shown> shown;
std::vector<pm::Body> shownBodies;  // The bodies of the last show(), in order, for measuring.
std::vector<pm::Pick> selection;
size_t shownTriangles = 0;

// Kernel arguments.

void fail(JNIEnv* env, const std::string& why) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), why.c_str());
}

std::vector<uint8_t> bytesOf(JNIEnv* env, jbyteArray data) {
    std::vector<uint8_t> bytes(static_cast<size_t>(env->GetArrayLength(data)));
    env->GetByteArrayRegion(data, 0, jsize(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

jbyteArray array(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    jbyteArray out = env->NewByteArray(jsize(bytes.size()));
    env->SetByteArrayRegion(out, 0, jsize(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return out;
}

template <class T, class A, class Get>
std::vector<T> read(JNIEnv* env, A arr, Get get) {
    if (!arr) return {};
    std::vector<T> out(static_cast<size_t>(env->GetArrayLength(arr)));
    (env->*get)(arr, 0, jsize(out.size()), out.data());
    return out;
}

std::vector<jint> ints(JNIEnv* env, jintArray a) { return read<jint>(env, a, &JNIEnv::GetIntArrayRegion); }
std::vector<jdouble> doubles(JNIEnv* env, jdoubleArray a) { return read<jdouble>(env, a, &JNIEnv::GetDoubleArrayRegion); }
std::vector<jlong> longs(JNIEnv* env, jlongArray a) { return read<jlong>(env, a, &JNIEnv::GetLongArrayRegion); }

std::vector<std::string> strings(JNIEnv* env, jobjectArray a) {
    std::vector<std::string> out;
    jsize n = env->GetArrayLength(a);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(a, i));
        const char* c = env->GetStringUTFChars(s, nullptr);
        out.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    return out;
}

jobjectArray stringArray(JNIEnv* env, const std::vector<std::string>& v) {
    jobjectArray out = env->NewObjectArray(jsize(v.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < v.size(); ++i) {
        jstring s = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(out, jsize(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}

/** Numbers per curve, as Sketches.kt packs them. */
constexpr size_t kCurveNumbers = 11;

/** Curves as kind (0 line, 1 circle, 2 arc, 3 bezier), id and eleven numbers each: x1 y1 x2 y2 r a0 a1 cx1 cy1 cx2 cy2. */
std::vector<pm::SketchCurve> curvesOf(const jint* kinds, const jint* ids, const jdouble* nums, size_t n) {
    std::vector<pm::SketchCurve> curves(n);
    for (size_t i = 0; i < n; ++i) {
        auto& c = curves[i];
        c.kind = pm::SketchCurve::Kind(kinds[i]);
        c.id = ids[i];
        const double* d = &nums[i * kCurveNumbers];
        c.x1 = d[0]; c.y1 = d[1]; c.x2 = d[2]; c.y2 = d[3]; c.r = d[4]; c.a0 = d[5]; c.a1 = d[6];
        c.cx1 = d[7]; c.cy1 = d[8]; c.cx2 = d[9]; c.cy2 = d[10];
    }
    return curves;
}

/** A plane as origin, x and y, nine numbers. */
gp_Ax3 planeOf(const jdouble* p) {
    gp_Dir x(p[3], p[4], p[5]), y(p[6], p[7], p[8]);
    return gp_Ax3(gp_Pnt(p[0], p[1], p[2]), x.Crossed(y), x);
}

/** Picks as a curve count each, the curve ids one after another, and a u v point each. */
std::vector<pm::RegionPick> picksOf(const std::vector<jint>& counts, const std::vector<jint>& ids, const std::vector<jdouble>& points) {
    std::vector<pm::RegionPick> picks;
    size_t k = 0;
    for (size_t i = 0; i < counts.size(); ++i) {
        pm::RegionPick p;
        for (int j = 0; j < counts[i]; ++j) p.curveIds.push_back(ids[k++]);
        p.u = points[i * 2];
        p.v = points[i * 2 + 1];
        picks.push_back(std::move(p));
    }
    return picks;
}

const pm::NamedShape& solidOf(jlong h) {
    const pm::Body& b = store.get(h);
    if (!b.solid) throw std::runtime_error("That needs a solid body, not a mesh");
    return *b.solid;
}

/** A mesh file's triangles: format 0 STL, 3 OBJ, 4 3MF (all its objects together). */
pm::Mesh readMesh(const std::vector<uint8_t>& bytes, int format) {
    if (format == 0) return pm::readStl(bytes);
    if (format == 3) return pm::readObj(std::string(bytes.begin(), bytes.end()));
    pm::Mesh all;
    for (const auto& o : pm::read3mf(bytes)) {
        uint32_t base = uint32_t(all.vertices.size());
        all.vertices.insert(all.vertices.end(), o.mesh.vertices.begin(), o.mesh.vertices.end());
        for (auto t : o.mesh.triangles) all.triangles.push_back({t[0] + base, t[1] + base, t[2] + base});
    }
    return all;
}

// Display.

// How finely solids are meshed for display: 0 low, 1 medium, 2 high.
std::atomic<int> displayDetail{2};

pm::Tessellation displayTessellation() {
    static const pm::Tessellation levels[3] = {{0.1, 0.4}, {0.05, 0.3}, {0.01, 0.25}};
    return levels[std::clamp(displayDetail.load(), 0, 2)];
}

/** A body as shown, kept by its handle: bodies never change, so it's reused until the body goes or the detail changes. */
struct DisplayCached {
    int detail = 0;
    std::shared_ptr<const pm::DisplayMesh> mesh;
    std::vector<std::string> faceNames, edgeNames;
    size_t triangles = 0;  // Mesh bodies' own triangles.
};
std::unordered_map<jlong, DisplayCached> displayCache;  // Only show() uses it, one call at a time.

const DisplayCached& displayOf(jlong handle, const pm::Body& b) {
    int detail = b.solid ? displayDetail.load() : -1;
    auto found = displayCache.find(handle);
    if (found != displayCache.end() && found->second.detail == detail) return found->second;
    DisplayCached c;
    c.detail = detail;
    if (b.solid) {
        c.faceNames = b.solid->faceNames();
        c.edgeNames = b.solid->edgeNames();
        c.mesh = std::make_shared<pm::DisplayMesh>(pm::Solid::fromShape(b.solid->shape).display(displayTessellation()));
    } else {
        pm::Mesh m = b.mesh->toMesh();
        c.triangles = m.triangles.size();
        c.mesh = std::make_shared<pm::DisplayMesh>(pm::displayMesh(m));
    }
    return displayCache[handle] = std::move(c);
}

/** A sketch's regions as see-through faces, and its curves as light lines. */
pm::DisplayMesh displaySketch(const gp_Ax3& plane, const std::vector<pm::SketchCurve>& curves) {
    gp_Trsf onPlane;
    onPlane.SetDisplacement(gp_Ax3(gp::XOY()), plane);
    TopLoc_Location loc(onPlane);
    TopoDS_Compound faces;
    BRep_Builder builder;
    builder.MakeCompound(faces);
    for (const auto& r : pm::buildRegionFaces(curves)) builder.Add(faces, r.face.Moved(loc));
    pm::DisplayMesh d = pm::Solid::fromShape(faces).display();
    d.edges.clear();
    for (const auto& c : curves) {
        pm::DisplayMesh::Edge e;
        auto add = [&](double u, double v) {
            gp_Pnt p = gp_Pnt(u, v, 0).Transformed(onPlane);
            e.points.insert(e.points.end(), {float(p.X()), float(p.Y()), float(p.Z())});
        };
        if (c.kind == pm::SketchCurve::Line) {
            add(c.x1, c.y1);
            add(c.x2, c.y2);
        } else if (c.kind == pm::SketchCurve::Bezier) {
            for (int i = 0; i <= 24; ++i) {
                double t = i / 24.0, u = 1 - t;
                add(u * u * u * c.x1 + 3 * u * u * t * c.cx1 + 3 * u * t * t * c.cx2 + t * t * t * c.x2,
                    u * u * u * c.y1 + 3 * u * u * t * c.cy1 + 3 * u * t * t * c.cy2 + t * t * t * c.y2);
            }
        } else {
            double a0 = c.kind == pm::SketchCurve::Circle ? 0 : c.a0;
            double a1 = c.kind == pm::SketchCurve::Circle ? 2 * M_PI : c.a1;
            while (a1 <= a0) a1 += 2 * M_PI;
            int n = std::max(8, int((a1 - a0) / (M_PI / 32)));
            for (int i = 0; i <= n; ++i) {
                double a = a0 + (a1 - a0) * i / n;
                add(c.x1 + c.r * std::cos(a), c.y1 + c.r * std::sin(a));
            }
        }
        d.edges.push_back(std::move(e));
    }
    const float edge[4] = {0.55f, 0.78f, 0.94f, 1.0f};
    const float face[4] = {0.66f, 0.95f, 0.93f, 0.16f};
    std::copy(edge, edge + 4, d.edgeColour);
    std::copy(face, face + 4, d.faceColour);
    return d;
}

/** Selected faces, edges, sketch areas and construction planes, counted. */
/** What a pick selects: sketch areas but not sketch lines, and planes by their square but not axes or outlines. */
pm::Pick pickable(pm::Pick p) {
    if (p.kind == pm::Pick::None || p.body >= shown.size()) return {};
    if (shown[p.body].sketch && p.kind == pm::Pick::Edge) return {};
    if (shown[p.body].plane != -1 && (shown[p.body].plane == -2 || p.kind == pm::Pick::Edge)) return {};
    return p;
}

jintArray selectionCounts(JNIEnv* env) {
    jint counts[4] = {0, 0, 0, 0};
    for (const auto& p : selection) {
        if (shown[p.body].plane >= 0) counts[3]++;
        else if (shown[p.body].sketch) counts[2] += p.kind == pm::Pick::Face ? 1 : 0;
        else counts[p.kind == pm::Pick::Edge ? 1 : 0]++;
    }
    jintArray out = env->NewIntArray(4);
    env->SetIntArrayRegion(out, 0, 4, counts);
    return out;
}

/** A construction plane as a see-through square, half as wide as `half`, or an axis as a line. */
pm::DisplayMesh displayPlane(const double* p, double half) {
    gp_Pnt o(p[0], p[1], p[2]);
    gp_Vec x(p[3], p[4], p[5]), y(p[6], p[7], p[8]);
    pm::DisplayMesh d;
    gp_Vec n = x.Crossed(y);
    gp_Pnt c[4] = {o.Translated(-x * half - y * half), o.Translated(x * half - y * half), o.Translated(x * half + y * half), o.Translated(-x * half + y * half)};
    for (const auto& q : c) {
        d.positions.insert(d.positions.end(), {float(q.X()), float(q.Y()), float(q.Z())});
        d.normals.insert(d.normals.end(), {float(n.X()), float(n.Y()), float(n.Z())});
        d.faceOfVertex.push_back(0);
    }
    d.indices = {0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2};  // Both sides, so it can be tapped from either.
    d.faceCount = 1;
    pm::DisplayMesh::Edge e;
    for (int i = 0; i <= 4; ++i) e.points.insert(e.points.end(), {float(c[i % 4].X()), float(c[i % 4].Y()), float(c[i % 4].Z())});
    d.edges.push_back(std::move(e));
    const float edge[4] = {1.0f, 0.82f, 0.25f, 0.8f};
    const float face[4] = {1.0f, 0.82f, 0.25f, 0.12f};
    std::copy(edge, edge + 4, d.edgeColour);
    std::copy(face, face + 4, d.faceColour);
    d.behind = true;
    return d;
}

pm::DisplayMesh displayAxis(const double* a, double half) {
    pm::DisplayMesh d;
    pm::DisplayMesh::Edge e;
    for (int s = -1; s <= 1; s += 2)
        e.points.insert(e.points.end(), {float(a[0] + s * a[3] * half), float(a[1] + s * a[4] * half), float(a[2] + s * a[5] * half)});
    d.edges.push_back(std::move(e));
    const float edge[4] = {1.0f, 0.82f, 0.25f, 1.0f};
    std::copy(edge, edge + 4, d.edgeColour);
    return d;
}

/** A construction point: three short crossing lines. */
pm::DisplayMesh displayPoint(const double* p, double half) {
    pm::DisplayMesh d;
    for (int k = 0; k < 3; ++k) {
        pm::DisplayMesh::Edge e;
        for (int s = -1; s <= 1; s += 2) {
            float q[3] = {float(p[0]), float(p[1]), float(p[2])};
            q[k] += float(s * half);
            e.points.insert(e.points.end(), q, q + 3);
        }
        d.edges.push_back(std::move(e));
    }
    const float edge[4] = {0.66f, 0.93f, 0.89f, 1.0f};
    std::copy(edge, edge + 4, d.edgeColour);
    return d;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setScratchDirectory(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    pm::setScratchDirectory(p);
    env->ReleaseStringUTFChars(path, p);
}

// Kernel. Each returns a new body's handle, already retained once.

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_extrude(JNIEnv* env, jobject, jint id, jdoubleArray plane, jintArray kinds,
                                                             jintArray ids, jdoubleArray nums, jintArray pickCounts, jintArray pickIds,
                                                             jdoubleArray pickPoints, jdouble forward, jdouble back, jdouble taper,
                                                             jdouble thin) {
    try {
        auto p = doubles(env, plane);
        auto k = ints(env, kinds), i = ints(env, ids);
        auto n = doubles(env, nums);
        auto curves = curvesOf(k.data(), i.data(), n.data(), k.size());
        auto picks = picksOf(ints(env, pickCounts), ints(env, pickIds), doubles(env, pickPoints));
        pm::Body b;
        b.solid = pm::extrude(id, planeOf(p.data()), curves, picks, forward, back, taper, thin);
        std::lock_guard<std::mutex> g(lock);
        return store.add(std::move(b));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_revolve(JNIEnv* env, jobject, jint id, jdoubleArray plane, jintArray kinds,
                                                             jintArray ids, jdoubleArray nums, jintArray pickCounts, jintArray pickIds,
                                                             jdoubleArray pickPoints, jdouble ax, jdouble ay, jdouble dx, jdouble dy,
                                                             jdouble angle) {
    try {
        auto p = doubles(env, plane);
        auto k = ints(env, kinds), i = ints(env, ids);
        auto n = doubles(env, nums);
        auto curves = curvesOf(k.data(), i.data(), n.data(), k.size());
        auto picks = picksOf(ints(env, pickCounts), ints(env, pickIds), doubles(env, pickPoints));
        pm::Body b;
        b.solid = pm::revolve(id, planeOf(p.data()), curves, picks, ax, ay, dx, dy, angle);
        std::lock_guard<std::mutex> g(lock);
        return store.add(std::move(b));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** how: 0 join, 1 cut, 2 intersect. A mesh on either side makes the result a mesh. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_combine(JNIEnv* env, jobject, jint id, jlong target, jlong tool, jint how) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body a = store.get(target), b = store.get(tool);
        g.unlock();
        pm::Body out;
        if (a.isMesh() || b.isMesh()) {
            pm::BooleanOp op = how == 0 ? pm::BooleanOp::Join : how == 1 ? pm::BooleanOp::Cut : pm::BooleanOp::Intersect;
            out.mesh = a.asMesh().boolean(b.asMesh(), op);
            if (out.mesh->triangleCount() == 0) throw std::runtime_error(how == 1 ? "The cut removes the whole body" : "The bodies don't overlap");
        } else {
            out.solid = pm::combine(id, *a.solid, *b.solid, pm::Combine(how));
        }
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_fillet(JNIEnv* env, jobject, jint id, jlong body, jobjectArray edges, jdouble r) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        pm::Body out;
        out.solid = pm::fillet(id, s, strings(env, edges), r);
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** kind: 0 equal, 1 two distances, 2 distance and angle (second in radians); see pm::chamfer. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_chamfer(JNIEnv* env, jobject, jint id, jlong body, jobjectArray edges, jdouble d,
                                                             jint kind, jdouble second, jboolean flip) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        pm::Body out;
        out.solid = pm::chamfer(id, s, strings(env, edges), d, pm::ChamferKind(kind), second, flip);
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_overlaps(JNIEnv* env, jobject, jlong a, jlong b) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body x = store.get(a), y = store.get(b);
        g.unlock();
        if (x.isMesh() || y.isMesh()) return x.asMesh().boolean(y.asMesh(), pm::BooleanOp::Intersect).volume() > 1e-9;
        return pm::overlaps(*x.solid, *y.solid);
    } catch (const std::exception& e) {
        fail(env, e.what());
        return JNI_FALSE;
    }
}

/** A named flat face's centre and outward normal, six numbers. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_facePlane(JNIEnv* env, jobject, jlong body, jstring name) {
    try {
        const char* c = env->GetStringUTFChars(name, nullptr);
        std::string n(c);
        env->ReleaseStringUTFChars(name, c);
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        gp_Ax3 ax = pm::facePlane(s, n);
        double v[6] = {ax.Location().X(), ax.Location().Y(), ax.Location().Z(), ax.Direction().X(), ax.Direction().Y(), ax.Direction().Z()};
        jdoubleArray out = env->NewDoubleArray(6);
        env->SetDoubleArrayRegion(out, 0, 6, v);
        return out;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/** Where a named face (or edge) of a solid is and how big, for finding it again; see signatureOf. Null if it hasn't got one. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_signature(JNIEnv* env, jobject, jlong body, jstring name, jboolean edge) {
    const char* c = env->GetStringUTFChars(name, nullptr);
    std::string n(c);
    env->ReleaseStringUTFChars(name, c);
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body& b = store.get(body);
        if (!b.solid) return nullptr;
        pm::NamedShape s = *b.solid;
        g.unlock();
        auto v = pm::signatureOf(s, n, edge);
        if (v.empty()) return nullptr;
        jdoubleArray out = env->NewDoubleArray(jsize(v.size()));
        env->SetDoubleArrayRegion(out, 0, jsize(v.size()), v.data());
        return out;
    } catch (...) {
        return nullptr;
    }
}

/** The name of the face or edge of a solid most like a signature, or null if none is close. */
JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_relocate(JNIEnv* env, jobject, jlong body, jdoubleArray signature) {
    auto sig = doubles(env, signature);
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body& b = store.get(body);
        if (!b.solid) return nullptr;
        pm::NamedShape s = *b.solid;
        g.unlock();
        std::string n = pm::relocate(s, std::vector<double>(sig.begin(), sig.end()));
        return n.empty() ? nullptr : env->NewStringUTF(n.c_str());
    } catch (...) {
        return nullptr;
    }
}

/**
 * Reads a file into a body. format: 0 STL, 3 OBJ, 4 3MF (meshes; a 3MF's
 * objects become one body), 1 STEP, 2 IGES (solids, faces named F<id>.i<k>).
 */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_importBody(JNIEnv* env, jobject, jint id, jbyteArray data, jint format) {
    try {
        auto bytes = bytesOf(env, data);
        pm::Body b;
        if (format == 0 || format == 3 || format == 4) {
            pm::RepairReport report;
            b.mesh = pm::MeshBody::fromMesh(pm::repair(readMesh(bytes, format), report));
        } else {
            pm::Solid s = pm::readSolid(bytes, format == 1 ? pm::SolidFormat::Step : pm::SolidFormat::Iges);
            pm::NamedShape named;
            named.shape = s.shape();
            int k = 0;
            for (TopExp_Explorer f(named.shape, TopAbs_FACE); f.More(); f.Next())
                if (!named.names.IsBound(f.Current())) named.names.Bind(f.Current(), "F" + std::to_string(id) + ".i" + std::to_string(k++));
            b.solid = std::move(named);
        }
        std::lock_guard<std::mutex> g(lock);
        return store.add(std::move(b));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_shell(JNIEnv* env, jobject, jint id, jlong body, jobjectArray open, jdouble t) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        pm::Body out;
        out.solid = pm::shell(id, s, strings(env, open), t);
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_draft(JNIEnv* env, jobject, jint id, jlong body, jobjectArray faces, jstring neutral, jdouble angle) {
    try {
        const char* c = env->GetStringUTFChars(neutral, nullptr);
        std::string n(c);
        env->ReleaseStringUTFChars(neutral, c);
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        pm::Body out;
        out.solid = pm::draft(id, s, strings(env, faces), n, angle);
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** A moved copy of a solid or mesh: m is twelve numbers, rows of rotation then translation. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_transform(JNIEnv* env, jobject, jint id, jlong body, jdoubleArray m, jstring tag) {
    try {
        auto mat = doubles(env, m);
        const char* c = env->GetStringUTFChars(tag, nullptr);
        std::string t(c);
        env->ReleaseStringUTFChars(tag, c);
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        g.unlock();
        pm::Body out;
        if (b.mesh) out.mesh = b.mesh->transformed(mat.data());
        else out.solid = pm::transformed(id, *b.solid, mat.data(), t);
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** The pieces of a solid or mesh either side of a plane: origin and normal, six numbers. */
JNIEXPORT jlongArray JNICALL Java_com_rm_parrotmetric_Core_split(JNIEnv* env, jobject, jint id, jlong body, jdoubleArray plane) {
    try {
        auto p = doubles(env, plane);
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        g.unlock();
        std::vector<pm::Body> pieces;
        if (b.mesh) {
            auto [front, back] = b.mesh->split(p.data(), p.data() + 3);
            if (front.empty() || back.empty()) throw std::runtime_error("The plane doesn't cut through the body");
            pm::Body f, k;
            f.mesh = front;
            k.mesh = back;
            pieces.push_back(std::move(f));
            pieces.push_back(std::move(k));
        } else {
            for (auto& n : pm::split(id, *b.solid, gp_Pnt(p[0], p[1], p[2]), gp_Dir(p[3], p[4], p[5]))) {
                pm::Body piece;
                piece.solid = std::move(n);
                pieces.push_back(std::move(piece));
            }
        }
        g.lock();
        std::vector<jlong> handles;
        for (auto& piece : pieces) handles.push_back(store.add(std::move(piece)));
        g.unlock();
        jlongArray out = env->NewLongArray(jsize(handles.size()));
        env->SetLongArrayRegion(out, 0, jsize(handles.size()), handles.data());
        return out;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/** The shape holes take out: plane (nine numbers), points as u v pairs, kind 0 simple, 1 counterbore, 2 countersink. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_holeTool(JNIEnv* env, jobject, jint id, jdoubleArray plane, jdoubleArray points,
                                                              jdouble diameter, jdouble depth, jint kind, jdouble topDiameter, jdouble topDepth) {
    try {
        auto p = doubles(env, plane);
        auto pts = doubles(env, points);
        std::vector<std::pair<double, double>> at;
        for (size_t i = 0; i + 1 < pts.size(); i += 2) at.push_back({pts[i], pts[i + 1]});
        pm::Body out;
        out.solid = pm::holeTool(id, planeOf(p.data()), at, diameter, depth, pm::HoleKind(kind), topDiameter, topDepth);
        std::lock_guard<std::mutex> g(lock);
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** A body's face names; none for a mesh. */
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_faceNames(JNIEnv* env, jobject, jlong h) {
    std::unique_lock<std::mutex> g(lock);
    try {
        const pm::Body& b = store.get(h);
        return stringArray(env, b.solid ? b.solid->faceNames() : std::vector<std::string>());
    } catch (const std::exception& e) {
        g.unlock();
        fail(env, e.what());
        return nullptr;
    }
}

/** What repair would change in a mesh file, as a line to show; empty when nothing. Throws if it can't be read. */
JNIEXPORT jstring JNICALL Java_com_rm_parrotmetric_Core_repairReport(JNIEnv* env, jobject, jbyteArray data, jint format) {
    try {
        pm::RepairReport report;
        pm::repair(readMesh(bytesOf(env, data), format), report);
        std::string s = report.summary();
        if (report.openEdges > 0) s += (s.empty() ? "" : "; ") + std::string("it still has gaps and may not print as one piece");
        return env->NewStringUTF(s.c_str());
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_convertToSolid(JNIEnv* env, jobject, jint id, jlong body) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        g.unlock();
        if (b.solid) {
            // Already a solid: the same body, held once more.
            g.lock();
            store.retain(body);
            return body;
        }
        pm::Body out;
        out.solid = pm::meshToSolid(id, b.mesh->toMesh());
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** The middle of a body: a solid's centre of mass, a mesh's bounding box centre. */
/** kind is pm::Primitive's order; sizes as pm::primitive takes them. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_primitive(JNIEnv* env, jobject, jint id, jdoubleArray plane, jint kind, jdouble u,
                                                               jdouble v, jdouble a, jdouble b, jdouble c) {
    try {
        auto p = doubles(env, plane);
        pm::Body out;
        out.solid = pm::primitive(id, planeOf(p.data()), pm::Primitive(kind), u, v, a, b, c);
        std::lock_guard<std::mutex> g(lock);
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** The box round a body: x, y, z low, then high. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_bounds(JNIEnv* env, jobject, jlong body) {
    std::unique_lock<std::mutex> g(lock);
    const pm::Body b = store.get(body);
    g.unlock();
    std::array<double, 6> box = b.mesh ? b.mesh->bounds() : pm::bounds(*b.solid);
    jdoubleArray out = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(out, 0, 6, box.data());
    return out;
}

JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_bodyCentre(JNIEnv* env, jobject, jlong body) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        g.unlock();
        double c[3];
        if (b.mesh) {
            auto m = b.mesh->centre();
            c[0] = m[0]; c[1] = m[1]; c[2] = m[2];
        } else {
            GProp_GProps p;
            BRepGProp::VolumeProperties(b.solid->shape, p);
            c[0] = p.CentreOfMass().X(); c[1] = p.CentreOfMass().Y(); c[2] = p.CentreOfMass().Z();
        }
        jdoubleArray out = env->NewDoubleArray(3);
        env->SetDoubleArrayRegion(out, 0, 3, c);
        return out;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/** Where bodies cross a plane (nine numbers), as sketch curves packed as for faceOutline. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_section(JNIEnv* env, jobject, jlongArray handles, jdoubleArray plane) {
    try {
        auto p = doubles(env, plane);
        std::vector<pm::Body> bodies;
        {
            std::lock_guard<std::mutex> g(lock);
            for (jlong h : longs(env, handles)) bodies.push_back(store.get(h));
        }
        gp_Ax3 ax = planeOf(p.data());
        std::vector<pm::SketchCurve> curves;
        for (const auto& b : bodies) {
            if (b.solid) {
                auto c = pm::section(*b.solid, ax);
                curves.insert(curves.end(), c.begin(), c.end());
            } else {
                for (const auto& loop : b.mesh->slice(p.data(), p.data() + 3, p.data() + 6))
                    for (size_t i = 0; i < loop.size(); ++i) {
                        const auto& a = loop[i];
                        const auto& c = loop[(i + 1) % loop.size()];
                        pm::SketchCurve l;
                        l.kind = pm::SketchCurve::Line; l.x1 = a[0]; l.y1 = a[1]; l.x2 = c[0]; l.y2 = c[1];
                        curves.push_back(l);
                    }
            }
        }
        std::vector<double> out = {double(curves.size())};
        for (const auto& c : curves) out.insert(out.end(), {double(c.kind), c.x1, c.y1, c.x2, c.y2, c.r, c.a0, c.a1, 0, 0, 0, 0});
        jdoubleArray result = env->NewDoubleArray(jsize(out.size()));
        env->SetDoubleArrayRegion(result, 0, jsize(out.size()), out.data());
        return result;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/** The flat part of a mesh that's selected: its middle and outward normal, six numbers; null if none is. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_selectedMeshPlane(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    for (const auto& p : selection) {
        if (p.kind != pm::Pick::Face || p.body >= shown.size() || !shown[p.body].mesh) continue;
        const pm::DisplayMesh& d = *shown[p.body].mesh;
        double c[3] = {0, 0, 0}, n[3] = {0, 0, 0}, area = 0;
        for (size_t t = 0; t + 2 < d.indices.size(); t += 3) {
            if (d.faceOfVertex[d.indices[t]] != p.index) continue;
            const float* a = &d.positions[d.indices[t] * 3];
            const float* b = &d.positions[d.indices[t + 1] * 3];
            const float* e = &d.positions[d.indices[t + 2] * 3];
            double u[3] = {double(b[0]) - a[0], double(b[1]) - a[1], double(b[2]) - a[2]};
            double v[3] = {double(e[0]) - a[0], double(e[1]) - a[1], double(e[2]) - a[2]};
            double cr[3] = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
            double ar = std::sqrt(cr[0] * cr[0] + cr[1] * cr[1] + cr[2] * cr[2]) / 2;
            for (int k = 0; k < 3; ++k) {
                c[k] += ar * (a[k] + b[k] + e[k]) / 3;
                n[k] += cr[k];
            }
            area += ar;
        }
        if (area <= 0) continue;
        double len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        double out[6] = {c[0] / area, c[1] / area, c[2] / area, n[0] / len, n[1] / len, n[2] / len};
        jdoubleArray a = env->NewDoubleArray(6);
        env->SetDoubleArrayRegion(a, 0, 6, out);
        return a;
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_retain(JNIEnv*, jobject, jlong h) {
    std::lock_guard<std::mutex> g(lock);
    store.retain(h);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_release(JNIEnv*, jobject, jlong h) {
    std::lock_guard<std::mutex> g(lock);
    store.release(h);
}

JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_isMesh(JNIEnv*, jobject, jlong h) {
    std::lock_guard<std::mutex> g(lock);
    try {
        return store.get(h).isMesh();
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

/**
 * Bodies as a file: format 0 STL (merged), 3 OBJ or 4 3MF (a named object
 * each), 1 STEP or 2 IGES (solids only). quality 0 fine, 1 medium, 2 coarse
 * sets how closely solids are followed by triangles. Null if there's
 * nothing that format can hold.
 */
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_exportBodies(JNIEnv* env, jobject, jlongArray handles, jobjectArray names,
                                                                       jint format, jint quality) {
    try {
        std::vector<pm::Body> bodies;
        {
            std::lock_guard<std::mutex> g(lock);
            for (jlong h : longs(env, handles)) bodies.push_back(store.get(h));
        }
        auto labels = strings(env, names);
        const double chords[3] = {0.005, 0.02, 0.1}, angles[3] = {0.1, 0.25, 0.5};
        int q = std::clamp(int(quality), 0, 2);
        if (format == 3 || format == 4) {
            std::vector<pm::NamedMesh> objects;
            for (size_t i = 0; i < bodies.size(); ++i)
                objects.push_back({i < labels.size() ? labels[i] : "Body", bodies[i].asMesh(chords[q], angles[q]).toMesh()});
            if (objects.empty()) return nullptr;
            if (format == 4) return array(env, pm::write3mf(objects));
            std::string text = pm::writeObj(objects);
            return array(env, std::vector<uint8_t>(text.begin(), text.end()));
        }
        if (format == 0) {
            pm::Mesh all;
            for (const auto& b : bodies) {
                pm::Mesh m = b.asMesh(chords[q], angles[q]).toMesh();
                uint32_t base = uint32_t(all.vertices.size());
                all.vertices.insert(all.vertices.end(), m.vertices.begin(), m.vertices.end());
                for (auto t : m.triangles) all.triangles.push_back({t[0] + base, t[1] + base, t[2] + base});
            }
            if (all.triangles.empty()) return nullptr;
            return array(env, pm::writeStl(all));
        }
        TopoDS_Compound c;
        BRep_Builder builder;
        builder.MakeCompound(c);
        bool any = false;
        for (const auto& b : bodies) if (b.solid) { builder.Add(c, b.solid->shape); any = true; }
        if (!any) return nullptr;
        return array(env, pm::writeSolid(pm::Solid::fromShape(c), format == 1 ? pm::SolidFormat::Step : pm::SolidFormat::Iges));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/**
 * What to draw: these bodies, then these sketches, each sketch a plane (nine
 * numbers) and its curve count, with all the sketches' curves together as
 * for findRegions. The selection is cleared.
 */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_show(JNIEnv* env, jobject, jlongArray handles, jdoubleArray planes,
                                                         jintArray curveCounts, jintArray kinds, jintArray ids, jdoubleArray nums,
                                                         jdoubleArray constructionPlanes, jdoubleArray axes, jdoubleArray points, jboolean refit) {
    auto cp = doubles(env, constructionPlanes);
    auto ax = doubles(env, axes);
    auto pts = doubles(env, points);
    auto h = longs(env, handles);
    auto p = doubles(env, planes);
    auto counts = ints(env, curveCounts);
    auto k = ints(env, kinds), i = ints(env, ids);
    auto n = doubles(env, nums);
    std::vector<pm::Body> bodies;
    {
        std::lock_guard<std::mutex> g(lock);
        for (jlong x : h) bodies.push_back(store.get(x));
    }
    std::vector<Shown> nextShown;
    std::vector<pm::DisplayMesh> meshes;
    size_t triangles = 0;
    for (size_t i = 0; i < bodies.size(); ++i) {
        const pm::Body& b = bodies[i];
        const DisplayCached& c = displayOf(h[i], b);
        Shown s;
        s.faceNames = c.faceNames;
        s.edgeNames = c.edgeNames;
        triangles += c.triangles;
        meshes.push_back(*c.mesh);
        if (b.mesh) s.mesh = std::const_pointer_cast<pm::DisplayMesh>(c.mesh);
        nextShown.push_back(std::move(s));
    }
    // Forget bodies no longer shown.
    for (auto it = displayCache.begin(); it != displayCache.end();) {
        if (std::find(h.begin(), h.end(), it->first) == h.end()) it = displayCache.erase(it);
        else ++it;
    }
    size_t start = 0;
    for (size_t s = 0; s < counts.size(); ++s) {
        size_t c = size_t(counts[s]);
        auto curves = curvesOf(k.data() + start, i.data() + start, n.data() + start * kCurveNumbers, c);
        start += c;
        meshes.push_back(displaySketch(planeOf(p.data() + s * 9), curves));
        Shown sh;
        sh.sketch = true;
        sh.sketchIndex = int(s);
        nextShown.push_back(std::move(sh));
    }
    // Construction planes and axes, sized to what's there.
    Bnd_Box box;
    for (const auto& b : bodies) if (b.solid) BRepBndLib::Add(b.solid->shape, box);
    double half = box.IsVoid() ? 25.0 : std::max(15.0, std::sqrt(box.SquareExtent()) * 0.4);
    for (size_t i = 0; i + 8 < cp.size(); i += 9) {
        meshes.push_back(displayPlane(cp.data() + i, half));
        Shown sh;
        sh.plane = int(i / 9);
        nextShown.push_back(std::move(sh));
    }
    for (size_t i = 0; i + 5 < ax.size(); i += 6) {
        meshes.push_back(displayAxis(ax.data() + i, half * 1.3));
        Shown sh;
        sh.plane = -2;  // Shown but not picked.
        nextShown.push_back(std::move(sh));
    }
    for (size_t i = 0; i + 2 < pts.size(); i += 3) {
        meshes.push_back(displayPoint(pts.data() + i, half * 0.06));
        Shown sh;
        sh.plane = -2;
        nextShown.push_back(std::move(sh));
    }
    std::lock_guard<std::mutex> g(lock);
    shown = std::move(nextShown);
    shownBodies = bodies;
    shownTriangles = triangles;
    selection.clear();
    renderer.setBodies(std::move(meshes), refit);
}

/** How finely solids are meshed for display, 0 low to 2 high; takes effect at the next show(). */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setDisplayDetail(JNIEnv*, jobject, jint level) {
    displayDetail = std::clamp(int(level), 0, 2);
}

/** A short piece of fixed work, timed in ms, to judge this device. Takes a fraction of a second; call it off the main thread. */
JNIEXPORT jdouble JNICALL Java_com_rm_parrotmetric_Core_speedTest(JNIEnv*, jobject) {
    return pm::speedTest();
}

JNIEXPORT jint JNICALL Java_com_rm_parrotmetric_Core_shownTriangles(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    return jint(shownTriangles);
}

// Selection.

/**
 * Selects or unselects what's under the point, or clears the selection on
 * empty space. GL thread. Returns how many faces, edges and sketch regions
 * are selected.
 */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_tap(JNIEnv* env, jobject, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> g(lock);
    pm::Pick p = pickable(renderer.pick(x, y));
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

/** A click: selects what's under the point in place of the selection, or with add, adds it or takes it out. */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_click(JNIEnv* env, jobject, jfloat x, jfloat y, jboolean add) {
    std::lock_guard<std::mutex> g(lock);
    pm::Pick p = pickable(renderer.pick(x, y));
    auto it = std::find(selection.begin(), selection.end(), p);
    if (!add) {
        selection.clear();
        if (p.kind != pm::Pick::None) selection.push_back(p);
    } else if (p.kind != pm::Pick::None) {
        if (it != selection.end()) selection.erase(it);
        else selection.push_back(p);
    }
    renderer.setSelection(selection);
    return selectionCounts(env);
}

/**
 * Selects what's in a screen box: with crossing, anything partly in it;
 * else what's wholly inside. In place of the selection, or added with add.
 */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectBox(JNIEnv* env, jobject, jfloat x0, jfloat y0, jfloat x1, jfloat y1,
                                                                   jboolean crossing, jboolean add) {
    std::lock_guard<std::mutex> g(lock);
    if (!add) selection.clear();
    for (pm::Pick p : renderer.pickBox(x0, y0, x1, y1, crossing)) {
        p = pickable(p);
        // Construction planes only by tapping them.
        if (p.kind == pm::Pick::None || shown[p.body].plane >= 0) continue;
        if (std::find(selection.begin(), selection.end(), p) == selection.end()) selection.push_back(p);
    }
    renderer.setSelection(selection);
    return selectionCounts(env);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_clearSelection(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    selection.clear();
    renderer.setSelection(selection);
}

/** The selected edges' names. */
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedEdges(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    std::vector<std::string> out;
    for (const auto& p : selection)
        if (p.kind == pm::Pick::Edge && !shown[p.body].sketch && p.index < shown[p.body].edgeNames.size())
            out.push_back(shown[p.body].edgeNames[p.index]);
    return stringArray(env, out);
}

/** The selected faces as body number (in the order shown) and name, "3\tF2.end"; a mesh's faces have no name. */
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedFaces(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    std::vector<std::string> out;
    for (const auto& p : selection) {
        if (p.kind != pm::Pick::Face || shown[p.body].sketch || shown[p.body].plane != -1) continue;
        if (shown[p.body].mesh) out.push_back(std::to_string(p.body) + "\t");
        else if (p.index < shown[p.body].faceNames.size()) out.push_back(std::to_string(p.body) + "\t" + shown[p.body].faceNames[p.index]);
    }
    return stringArray(env, out);
}

/** Shows a section: everything behind the plane through (ox, oy, oz) facing (nx, ny, nz) is hidden. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setSection(JNIEnv*, jobject, jboolean on, jdouble ox, jdouble oy, jdouble oz, jdouble nx, jdouble ny, jdouble nz) {
    std::lock_guard<std::mutex> g(lock);
    renderer.setClip(float(nx), float(ny), float(nz), float(-(nx * ox + ny * oy + nz * oz)), on);
}

namespace {

std::string mm(double v) {
    char b[48];
    std::snprintf(b, sizeof b, "%.2f", v);
    std::string s(b);
    while (s.back() == '0') s.pop_back();
    if (s.back() == '.') s.pop_back();
    return s;
}

}  // namespace

/**
 * Measurements of what's selected, as lines to show: an edge's length (and
 * radius), a face's area (and radius), the gap and angle between two
 * things, and the volume and size of the body they're on.
 */
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_measure(JNIEnv* env, jobject) {
    std::vector<TopoDS_Shape> picked;
    std::vector<pm::Body> owners;
    // The bodies shown come from the last show(); the picked shapes are found by name in them.
    std::vector<std::string> lines;
    try {
        std::vector<std::pair<int, std::string>> faces, edges;
        {
            std::lock_guard<std::mutex> g(lock);
            for (const auto& p : selection) {
                const Shown& s = shown[p.body];
                if (s.sketch || s.plane != -1) continue;
                if (p.kind == pm::Pick::Face && p.index < s.faceNames.size()) faces.push_back({int(p.body), s.faceNames[p.index]});
                if (p.kind == pm::Pick::Edge && p.index < s.edgeNames.size()) edges.push_back({int(p.body), s.edgeNames[p.index]});
            }
            for (size_t b = 0; b < shownBodies.size(); ++b) owners.push_back(shownBodies[b]);
        }
        for (const auto& [b, n] : edges) {
            if (size_t(b) >= owners.size() || !owners[size_t(b)].solid) continue;
            for (const auto& e : owners[size_t(b)].solid->findEdges(n)) {
                picked.push_back(e);
                GProp_GProps props;
                BRepGProp::LinearProperties(e, props);
                std::string line = "Edge " + mm(props.Mass()) + " mm long";
                BRepAdaptor_Curve c(e);
                if (c.GetType() == GeomAbs_Circle) line += ", radius " + mm(c.Circle().Radius());
                lines.push_back(line);
                break;
            }
        }
        for (const auto& [b, n] : faces) {
            if (size_t(b) >= owners.size() || !owners[size_t(b)].solid) continue;
            for (const auto& f : owners[size_t(b)].solid->findFaces(n)) {
                picked.push_back(f);
                GProp_GProps props;
                BRepGProp::SurfaceProperties(f, props);
                std::string line = "Face " + mm(props.Mass()) + " mm²";
                BRepAdaptor_Surface su(f);
                if (su.GetType() == GeomAbs_Cylinder) line += ", radius " + mm(su.Cylinder().Radius());
                lines.push_back(line);
                break;
            }
        }
        if (picked.size() == 2) {
            BRepExtrema_DistShapeShape dist(picked[0], picked[1]);
            if (dist.IsDone()) lines.push_back("Apart " + mm(dist.Value()) + " mm");
            // The angle between two flat faces or two straight edges.
            auto direction = [](const TopoDS_Shape& s, gp_Dir& d) {
                if (s.ShapeType() == TopAbs_FACE) {
                    BRepAdaptor_Surface su(TopoDS::Face(s));
                    if (su.GetType() != GeomAbs_Plane) return false;
                    d = su.Plane().Axis().Direction();
                    return true;
                }
                BRepAdaptor_Curve c(TopoDS::Edge(s));
                if (c.GetType() != GeomAbs_Line) return false;
                d = c.Line().Direction();
                return true;
            };
            gp_Dir d1, d2;
            if (picked[0].ShapeType() == picked[1].ShapeType() && direction(picked[0], d1) && direction(picked[1], d2)) {
                double a = d1.Angle(d2) * 180 / M_PI;
                if (a > 90) a = 180 - a;
                lines.push_back("At " + mm(a) + "°");
            }
        }
        // The body under the first pick.
        int body = !faces.empty() ? faces[0].first : !edges.empty() ? edges[0].first : -1;
        if (body >= 0 && size_t(body) < owners.size()) {
            const pm::Body& b = owners[size_t(body)];
            Bnd_Box box;
            double vol = 0;
            if (b.solid) {
                GProp_GProps props;
                BRepGProp::VolumeProperties(b.solid->shape, props);
                vol = props.Mass();
                BRepBndLib::AddOptimal(b.solid->shape, box, false, false);
            }
            if (!box.IsVoid()) {
                double x0, y0, z0, x1, y1, z1;
                box.Get(x0, y0, z0, x1, y1, z1);
                lines.push_back("Body " + mm(vol / 1000) + " cm³, " + mm(x1 - x0) + " × " + mm(y1 - y0) + " × " + mm(z1 - z0) + " mm");
            }
        }
    } catch (const Standard_Failure&) {
        lines.push_back("That couldn't be measured");
    }
    return stringArray(env, lines);
}

/** The selected construction planes, by their place in the list passed to show. */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectedPlanes(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    std::vector<jint> out;
    for (const auto& p : selection)
        if (shown[p.body].plane >= 0) out.push_back(shown[p.body].plane);
    jintArray a = env->NewIntArray(jsize(out.size()));
    env->SetIntArrayRegion(a, 0, jsize(out.size()), out.data());
    return a;
}

/**
 * The edges of a named face, as sketch curves on a plane (nine numbers),
 * packed as for curvesOf after a count: lines, circles and arcs as they
 * are, anything else as short lines.
 */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_faceOutline(JNIEnv* env, jobject, jlong body, jstring face, jdoubleArray plane) {
    try {
        const char* c = env->GetStringUTFChars(face, nullptr);
        std::string name(c);
        env->ReleaseStringUTFChars(face, c);
        auto p = doubles(env, plane);
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        auto faces = s.findFaces(name);
        if (faces.empty()) throw std::runtime_error("The face isn't there any more");
        gp_Ax3 ax = planeOf(p.data());
        gp_Trsf toPlane;
        toPlane.SetTransformation(ax);  // World to the plane's own coordinates.
        std::vector<double> out = {0};
        auto add = [&](int kind, std::initializer_list<double> v) {
            out[0] += 1;
            out.push_back(kind);
            out.insert(out.end(), v);
            for (size_t k = v.size(); k < kCurveNumbers; ++k) out.push_back(0);
        };
        for (TopExp_Explorer e(faces[0], TopAbs_EDGE); e.More(); e.Next()) {
            const TopoDS_Edge& edge = TopoDS::Edge(e.Current());
            if (BRep_Tool::Degenerated(edge)) continue;
            BRepAdaptor_Curve curve(edge);
            auto local = [&](double t) { return curve.Value(t).Transformed(toPlane); };
            double t0 = curve.FirstParameter(), t1 = curve.LastParameter();
            if (curve.GetType() == GeomAbs_Line) {
                gp_Pnt a = local(t0), b = local(t1);
                add(0, {a.X(), a.Y(), b.X(), b.Y()});
            } else if (curve.GetType() == GeomAbs_Circle) {
                gp_Circ circ = curve.Circle();
                gp_Pnt centre = circ.Location().Transformed(toPlane);
                double r = circ.Radius();
                if (std::abs(t1 - t0 - 2 * M_PI) < 1e-9) {
                    add(1, {centre.X(), centre.Y(), 0, 0, r});
                } else {
                    gp_Pnt a = local(t0), b = local(t1), m = local((t0 + t1) / 2);
                    double a0 = std::atan2(a.Y() - centre.Y(), a.X() - centre.X());
                    double a1 = std::atan2(b.Y() - centre.Y(), b.X() - centre.X());
                    double am = std::atan2(m.Y() - centre.Y(), m.X() - centre.X());
                    // Arcs go anticlockwise; if the middle isn't on the way round from a to b, swap the ends.
                    auto span = [](double from, double to) { double d = to - from; while (d < 0) d += 2 * M_PI; return d; };
                    if (span(a0, am) > span(a0, a1)) std::swap(a0, a1);
                    add(2, {centre.X(), centre.Y(), 0, 0, r, a0, a1});
                }
            } else {
                const int n = 24;
                for (int i = 0; i < n; ++i) {
                    gp_Pnt a = local(t0 + (t1 - t0) * i / n), b = local(t0 + (t1 - t0) * (i + 1) / n);
                    add(0, {a.X(), a.Y(), b.X(), b.Y()});
                }
            }
        }
        jdoubleArray result = env->NewDoubleArray(jsize(out.size()));
        env->SetDoubleArrayRegion(result, 0, jsize(out.size()), out.data());
        return result;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/** The selected sketch regions as pairs: sketch number, region number (largest first, as findRegions gives them). */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_selectedRegions(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    std::vector<jint> out;
    for (const auto& p : selection)
        if (p.kind == pm::Pick::Face && shown[p.body].sketch) {
            out.push_back(shown[p.body].sketchIndex);
            out.push_back(jint(p.index));
        }
    jintArray a = env->NewIntArray(jsize(out.size()));
    env->SetIntArrayRegion(a, 0, jsize(out.size()), out.data());
    return a;
}

/** Selects edges and faces by name and sketch regions by (sketch, region) pairs, as when editing a feature. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_select(JNIEnv* env, jobject, jobjectArray edges, jintArray regions, jobjectArray faces) {
    auto names = strings(env, edges);
    auto faceNames = strings(env, faces);
    auto r = ints(env, regions);
    std::lock_guard<std::mutex> g(lock);
    selection.clear();
    for (uint32_t b = 0; b < shown.size(); ++b) {
        const Shown& s = shown[b];
        if (s.plane != -1) continue;
        if (s.sketch) {
            for (size_t i = 0; i + 1 < r.size(); i += 2)
                if (r[i] == s.sketchIndex) selection.push_back({pm::Pick::Face, b, uint32_t(r[i + 1])});
        } else {
            for (uint32_t e = 0; e < s.edgeNames.size(); ++e)
                if (std::find(names.begin(), names.end(), s.edgeNames[e]) != names.end()) selection.push_back({pm::Pick::Edge, b, e});
            for (uint32_t f = 0; f < s.faceNames.size(); ++f)
                if (std::find(faceNames.begin(), faceNames.end(), s.faceNames[f]) != faceNames.end()) selection.push_back({pm::Pick::Face, b, f});
        }
    }
    renderer.setSelection(selection);
}

// Sketch regions.

/**
 * The closed regions of a sketch's curves, flattened: region count, then per
 * region its area, inside point u v, id count, ids, loop count, and per loop
 * its point count and x y pairs.
 */
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_findRegions(JNIEnv* env, jobject, jintArray kinds, jintArray ids, jdoubleArray numbers) {
    auto k = ints(env, kinds), i = ints(env, ids);
    auto n = doubles(env, numbers);
    auto curves = curvesOf(k.data(), i.data(), n.data(), k.size());
    std::vector<float> out;
    auto regions = pm::findRegions(curves);
    out.push_back(float(regions.size()));
    for (const auto& r : regions) {
        out.push_back(float(r.area));
        out.push_back(float(r.insideU));
        out.push_back(float(r.insideV));
        out.push_back(float(r.curveIds.size()));
        for (int id : r.curveIds) out.push_back(float(id));
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

// The renderer, on the GL thread.

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

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_orbit(JNIEnv*, jobject, jfloat dx, jfloat dy) {
    std::lock_guard<std::mutex> g(lock);
    renderer.orbit(dx, dy);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_pan(JNIEnv*, jobject, jfloat dx, jfloat dy) {
    std::lock_guard<std::mutex> g(lock);
    renderer.pan(dx, dy);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_zoom(JNIEnv*, jobject, jfloat f) {
    std::lock_guard<std::mutex> g(lock);
    renderer.zoom(f);
}

/** Zooms towards the point under (x, y), screen pixels, which stays put. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_zoomAt(JNIEnv*, jobject, jfloat f, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> g(lock);
    renderer.zoomAt(f, x, y);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_fit(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    renderer.fit();
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_viewFrom(JNIEnv*, jobject, jfloat yaw, jfloat pitch) {
    std::lock_guard<std::mutex> g(lock);
    renderer.viewFrom(yaw, pitch);
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

}  // extern "C"
