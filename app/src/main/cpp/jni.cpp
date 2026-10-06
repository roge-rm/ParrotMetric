// The JNI side of com.rm.parrotmetric.Core. Kernel calls come from a worker
// thread and the renderer calls from the GL thread; the lock keeps them apart.
// Kernel calls report failure by throwing a Java RuntimeException with a
// reason fit to show.
#include <jni.h>

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepTools.hxx>
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
#include <array>
#include <map>
#include <atomic>
#include <unordered_map>
#include <cmath>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>

#include "display/display_mesh.h"
#include "display/thickness.h"
#include "io/exchange.h"
#include "io/mesh_formats.h"
#include "mesh/mesh_body.h"
#include "mesh/repair.h"
#include "mesh/stl.h"
#include "model/operations.h"
#include "model/views.h"
#include "model/store.h"
#include "render/picture.h"
#include "render/renderer.h"
#include "sculpt/sculpt.h"
#include "sketch/region_faces.h"
#include "sketch/regions.h"
#include "sketch/text.h"
#include "solid/solid.h"
#include "solid/speed.h"

namespace {

std::mutex lock;
pm::Renderer renderer;
pm::BodyStore store;
// The mesh being sculpted, if any; the renderer draws it.
std::unique_ptr<pm::Sculpt> sculpt;

/** What each displayed thing is, in the order the renderer numbers them. */
struct Shown {
    bool sketch = false;
    int plane = -1;                                 // Construction planes: which of those passed to show.
    std::vector<std::string> faceNames, edgeNames;  // Bodies: names by face and edge number.
    std::vector<std::string> cornerNames;           // Solids: names by corner number.
    int sketchIndex = 0;                            // Sketches: which of the sketches passed to show.
    std::shared_ptr<pm::DisplayMesh> mesh;          // Mesh bodies: what was drawn, for finding a picked flat area.
};
std::vector<Shown> shown;
/** Where the last tap or click was, view pixels. */
float lastTapX = -1, lastTapY = -1;
/** Threads drawn as a symbol, shown with the bodies from the next show(). */
std::vector<pm::DisplayMesh> threadMarks;
/** How far each body is drawn from where it is, mm, by handle, for exploded views. */
std::map<jlong, std::array<float, 3>> bodyOffsets;
std::vector<pm::Body> shownBodies;  // The bodies of the last show(), in order, for measuring.
std::vector<pm::Pick> selection;
std::unordered_map<int, pm::Picture> pictures;  // Canvas pictures by key, under lock.
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
    std::vector<std::string> faceNames, edgeNames, cornerNames;
    size_t triangles = 0;  // Mesh bodies' own triangles.
    int shadeMode = 0;     // The check view DisplayMesh::shade was worked out for, if any.
    std::shared_ptr<const pm::DisplayMesh> plain;  // The mesh before any check view's changes.
};
std::unordered_map<jlong, DisplayCached> displayCache;  // Only show() uses it, one call at a time.

const DisplayCached& displayShape(jlong handle, const pm::Body& b);

/** The view bodies are coloured by (Renderer::setAnalysis); thickness has to be worked out for bodies shown. */
std::atomic<int> analysisMode{0};

const DisplayCached& displayOf(jlong handle, const pm::Body& b) {
    const DisplayCached& c = displayShape(handle, b);
    int mode = analysisMode;
    int want = (mode == 2 || mode == 4) ? mode : 0;
    if (c.shadeMode == want) return c;
    DisplayCached& again = displayCache[handle];
    if (want == 0) again.mesh = again.plain;
    else again.mesh = std::make_shared<pm::DisplayMesh>(want == 2 ? pm::withThickness(*again.plain) : pm::withCurvature(*again.plain, !b.solid));
    again.shadeMode = want;
    return again;
}

const DisplayCached& displayShape(jlong handle, const pm::Body& b) {
    int detail = b.solid ? displayDetail.load() : -1;
    auto found = displayCache.find(handle);
    if (found != displayCache.end() && found->second.detail == detail) return found->second;
    DisplayCached c;
    c.detail = detail;
    if (b.solid) {
        c.faceNames = b.solid->faceNames();
        c.edgeNames = b.solid->edgeNames();
        auto names = b.solid->cornerNames();
        auto mesh = pm::Solid::fromShape(b.solid->shape).display(displayTessellation());
        // Only real corners get a dot: those named.
        std::vector<float> kept;
        for (size_t i = 0; i < names.size() && i * 3 + 2 < mesh.corners.size(); ++i) {
            if (names[i].empty()) continue;
            kept.insert(kept.end(), mesh.corners.begin() + long(i * 3), mesh.corners.begin() + long(i * 3 + 3));
            c.cornerNames.push_back(names[i]);
        }
        mesh.corners = std::move(kept);
        mesh.body = true;
        c.mesh = std::make_shared<pm::DisplayMesh>(std::move(mesh));
    } else {
        pm::Mesh m = b.mesh->toMesh();
        c.triangles = m.triangles.size();
        auto mesh = pm::displayMesh(m);
        mesh.body = true;
        c.mesh = std::make_shared<pm::DisplayMesh>(std::move(mesh));
    }
    c.plain = c.mesh;
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
    d.corners.clear();
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
    // Faces, edges, sketch areas, construction planes, corners.
    jint counts[5] = {0, 0, 0, 0, 0};
    for (const auto& p : selection) {
        if (shown[p.body].plane >= 0) counts[3]++;
        else if (shown[p.body].sketch) counts[2] += p.kind == pm::Pick::Face ? 1 : 0;
        else if (p.kind == pm::Pick::Vertex) counts[4]++;
        else counts[p.kind == pm::Pick::Edge ? 1 : 0]++;
    }
    jintArray out = env->NewIntArray(5);
    env->SetIntArrayRegion(out, 0, 5, counts);
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

/** kind: 0 one radius, 1 from r to second along each edge, 2 r across; see pm::fillet. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_fillet(JNIEnv* env, jobject, jint id, jlong body, jobjectArray edges, jdouble r, jint kind,
                                                            jdouble second) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        pm::Body out;
        out.solid = pm::fillet(id, s, strings(env, edges), static_cast<pm::FilletKind>(kind), r, second);
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

/** Where a named corner of a solid is, or null if it hasn't got it. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_corner(JNIEnv* env, jobject, jlong body, jstring name) {
    const char* c = env->GetStringUTFChars(name, nullptr);
    std::string n(c);
    env->ReleaseStringUTFChars(name, c);
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        auto p = s.findCorner(n);
        if (!p) return nullptr;
        double v[3] = {p->X(), p->Y(), p->Z()};
        jdoubleArray out = env->NewDoubleArray(3);
        env->SetDoubleArrayRegion(out, 0, 3, v);
        return out;
    } catch (const std::exception&) {
        return nullptr;
    }
}

/**
 * What shape a named edge or face is, for construction geometry: kind, then
 * a point, a direction and a size. 0 a straight edge (its start, along it,
 * its length); 1 a round edge (centre, its axis, radius); 2 a cylinder or
 * cone face (a point on its axis, the axis, radius); 3 a sphere (centre, 0,
 * radius); 4 a flat face (its middle, its normal, 0). Null if it's none of
 * these or not there.
 */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_shapeOf(JNIEnv* env, jobject, jlong body, jstring name, jboolean edge) {
    const char* c = env->GetStringUTFChars(name, nullptr);
    std::string n(c);
    env->ReleaseStringUTFChars(name, c);
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        double v[12] = {-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        auto put = [&](double kind, const gp_Pnt& p, const gp_Dir& d, double size) {
            v[0] = kind; v[1] = p.X(); v[2] = p.Y(); v[3] = p.Z(); v[4] = d.X(); v[5] = d.Y(); v[6] = d.Z(); v[7] = size;
        };
        if (edge) {
            for (const auto& e : s.findEdges(n)) {
                BRepAdaptor_Curve curve(e);
                if (curve.GetType() == GeomAbs_Line) {
                    gp_Pnt a = curve.Value(curve.FirstParameter()), b = curve.Value(curve.LastParameter());
                    if (a.Distance(b) > 1e-9) put(0, a, gp_Dir(gp_Vec(a, b)), a.Distance(b));
                } else if (curve.GetType() == GeomAbs_Circle) {
                    gp_Circ circ = curve.Circle();
                    put(1, circ.Location(), circ.Axis().Direction(), circ.Radius());
                }
                break;
            }
        } else {
            for (const auto& f : s.findFaces(n)) {
                BRepAdaptor_Surface surface(f);
                switch (surface.GetType()) {
                    case GeomAbs_Cylinder: {
                        gp_Cylinder cyl = surface.Cylinder();
                        put(2, cyl.Location(), cyl.Axis().Direction(), cyl.Radius());
                        // 1 for a hole: the face looks in towards its axis.
                        double u0, u1, w0, w1;
                        BRepTools::UVBounds(f, u0, u1, w0, w1);
                        gp_Pnt p;
                        gp_Vec du, dv;
                        surface.D1((u0 + u1) / 2, (w0 + w1) / 2, p, du, dv);
                        gp_Vec normal = du.Crossed(dv);
                        if (f.Orientation() == TopAbs_REVERSED) normal.Reverse();
                        gp_Vec radial(cyl.Location(), p);
                        radial -= gp_Vec(cyl.Axis().Direction()) * radial.Dot(gp_Vec(cyl.Axis().Direction()));
                        v[8] = normal.Dot(radial) < 0 ? 1 : 0;
                        v[9] = w0;
                        v[10] = w1;
                        break;
                    }
                    case GeomAbs_Cone: {
                        gp_Cone cone = surface.Cone();
                        put(2, cone.Location(), cone.Axis().Direction(), cone.RefRadius());
                        // A hole if it looks in towards its axis; where it starts and ends along the axis from its corners.
                        double u0, u1, w0, w1;
                        BRepTools::UVBounds(f, u0, u1, w0, w1);
                        gp_Pnt p;
                        gp_Vec du, dv;
                        surface.D1((u0 + u1) / 2, (w0 + w1) / 2, p, du, dv);
                        gp_Vec normal = du.Crossed(dv);
                        if (f.Orientation() == TopAbs_REVERSED) normal.Reverse();
                        gp_Vec axis(cone.Axis().Direction()), radial(cone.Location(), p);
                        radial -= axis * radial.Dot(axis);
                        v[8] = normal.Dot(radial) < 0 ? 1 : 0;
                        double lo = 1e300, hi = -1e300;
                        for (double w : {w0, w1}) {
                            double along = gp_Vec(cone.Location(), surface.Value(u0, w)).Dot(axis);
                            lo = std::min(lo, along);
                            hi = std::max(hi, along);
                        }
                        v[9] = lo;
                        v[10] = hi;
                        v[11] = 1;
                        break;
                    }
                    case GeomAbs_Sphere: put(3, surface.Sphere().Location(), gp::DZ(), surface.Sphere().Radius()); break;
                    case GeomAbs_Plane: {
                        gp_Ax3 ax = pm::facePlane(s, n);
                        put(4, ax.Location(), ax.Direction(), 0);
                        break;
                    }
                    default: break;
                }
                break;
            }
        }
        if (v[0] < 0) return nullptr;
        jdoubleArray out = env->NewDoubleArray(12);
        env->SetDoubleArrayRegion(out, 0, 12, v);
        return out;
    } catch (const std::exception&) {
        return nullptr;
    }
}

/** The point a fraction t (0 to 1) along a named edge, and which way the edge runs there: x, y, z, then a unit direction. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_alongEdge(JNIEnv* env, jobject, jlong body, jstring name, jdouble t) {
    const char* c = env->GetStringUTFChars(name, nullptr);
    std::string n(c);
    env->ReleaseStringUTFChars(name, c);
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        for (const auto& e : s.findEdges(n)) {
            BRepAdaptor_Curve curve(e);
            double u = curve.FirstParameter() + std::clamp(double(t), 0.0, 1.0) * (curve.LastParameter() - curve.FirstParameter());
            gp_Pnt p;
            gp_Vec d;
            curve.D1(u, p, d);
            if (d.Magnitude() < 1e-12) return nullptr;
            if (e.Orientation() == TopAbs_REVERSED) d.Reverse();
            d.Normalize();
            double v[6] = {p.X(), p.Y(), p.Z(), d.X(), d.Y(), d.Z()};
            jdoubleArray out = env->NewDoubleArray(6);
            env->SetDoubleArrayRegion(out, 0, 6, v);
            return out;
        }
        return nullptr;
    } catch (const std::exception&) {
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
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_snapFitTool(JNIEnv* env, jobject, jint id, jdoubleArray plane, jdoubleArray points,
                                                                 jdoubleArray middle, jdouble length, jdouble width, jdouble thickness,
                                                                 jdouble overhang, jdouble catchHeight, jdouble gap, jboolean catchPart, jstring tag) {
    try {
        auto p = doubles(env, plane);
        auto pts = doubles(env, points);
        auto m = doubles(env, middle);
        std::vector<std::pair<double, double>> at;
        for (size_t i = 0; i + 1 < pts.size(); i += 2) at.push_back({pts[i], pts[i + 1]});
        const char* c = env->GetStringUTFChars(tag, nullptr);
        std::string t(c);
        env->ReleaseStringUTFChars(tag, c);
        pm::Body out;
        out.solid = pm::snapFitTool(id, planeOf(p.data()), at, gp_Pnt(m[0], m[1], m[2]), length, width, thickness, overhang, catchHeight, gap,
                                    catchPart, t);
        std::lock_guard<std::mutex> g(lock);
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

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
/** A body cut by another into the parts inside and outside it. Meshes and solids mixed are done as meshes. */
JNIEXPORT jlongArray JNICALL Java_com_rm_parrotmetric_Core_splitBy(JNIEnv* env, jobject, jint id, jlong body, jlong tool) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        const pm::Body t = store.get(tool);
        g.unlock();
        std::vector<pm::Body> pieces;
        if (b.mesh || t.mesh) {
            pm::MeshBody mb = b.asMesh(), mt = t.asMesh();
            pm::MeshBody inside = mb.boolean(mt, pm::BooleanOp::Intersect), outside = mb.boolean(mt, pm::BooleanOp::Cut);
            if (inside.empty() || outside.empty()) throw std::runtime_error("The bodies don't cross");
            pm::Body i, o;
            i.mesh = inside;
            o.mesh = outside;
            pieces.push_back(std::move(o));
            pieces.push_back(std::move(i));
        } else {
            for (auto& n : pm::splitBy(id, *b.solid, *t.solid)) {
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

/** How much two bodies overlap, mm³. */
JNIEXPORT jdouble JNICALL Java_com_rm_parrotmetric_Core_overlapVolume(JNIEnv* env, jobject, jlong a, jlong b) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body x = store.get(a);
        const pm::Body y = store.get(b);
        g.unlock();
        if (x.solid && y.solid) return pm::overlapVolume(*x.solid, *y.solid);
        return x.asMesh().boolean(y.asMesh(), pm::BooleanOp::Intersect).volume();
    } catch (const std::exception& e) {
        return 0;
    }
}

/**
 * A path: a sketch's curves on a plane when there are any, else the named
 * edges of a body. Takes the lock itself.
 */
TopoDS_Wire pathOf(JNIEnv* env, jdoubleArray plane, jintArray kinds, jintArray ids, jdoubleArray nums, jlong body, jobjectArray edges) {
    auto k = ints(env, kinds);
    if (!k.empty()) {
        auto p = doubles(env, plane);
        auto i = ints(env, ids);
        auto n = doubles(env, nums);
        return pm::pathFromSketch(planeOf(p.data()), curvesOf(k.data(), i.data(), n.data(), k.size()));
    }
    auto names = strings(env, edges);
    std::unique_lock<std::mutex> g(lock);
    pm::NamedShape s = solidOf(body);
    g.unlock();
    std::vector<TopoDS_Edge> found;
    for (const auto& name : names) {
        auto e = s.findEdges(name);
        if (e.empty()) throw std::runtime_error("An edge of the path isn't there any more");
        found.push_back(e[0]);
    }
    return pm::pathFromEdges(found);
}

jlong keep(pm::NamedShape shape) {
    pm::Body b;
    b.solid = std::move(shape);
    std::lock_guard<std::mutex> g(lock);
    return store.add(std::move(b));
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_sweep(JNIEnv* env, jobject, jint id, jdoubleArray plane, jintArray kinds, jintArray ids,
                                                           jdoubleArray nums, jintArray pickCounts, jintArray pickIds, jdoubleArray pickPoints,
                                                           jdoubleArray pathPlane, jintArray pathKinds, jintArray pathIds, jdoubleArray pathNums,
                                                           jlong pathBody, jobjectArray pathEdges) {
    try {
        TopoDS_Wire path = pathOf(env, pathPlane, pathKinds, pathIds, pathNums, pathBody, pathEdges);
        auto p = doubles(env, plane);
        auto k = ints(env, kinds), i = ints(env, ids);
        auto n = doubles(env, nums);
        auto picks = picksOf(ints(env, pickCounts), ints(env, pickIds), doubles(env, pickPoints));
        return keep(pm::sweep(id, planeOf(p.data()), curvesOf(k.data(), i.data(), n.data(), k.size()), picks, path));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_pipe(JNIEnv* env, jobject, jint id, jdoubleArray pathPlane, jintArray pathKinds,
                                                          jintArray pathIds, jdoubleArray pathNums, jlong pathBody, jobjectArray pathEdges,
                                                          jdouble diameter, jdouble inner) {
    try {
        return keep(pm::pipe(id, pathOf(env, pathPlane, pathKinds, pathIds, pathNums, pathBody, pathEdges), diameter, inner));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_coil(JNIEnv* env, jobject, jint id, jdoubleArray plane, jdouble u, jdouble v, jdouble diameter,
                                                          jdouble pitch, jdouble turns, jdouble section, jboolean square) {
    try {
        auto p = doubles(env, plane);
        return keep(pm::coil(id, planeOf(p.data()), u, v, diameter, pitch, turns, section, square));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_lipTool(JNIEnv* env, jobject, jint id, jlong body, jstring face, jdouble inside,
                                                             jdouble outside, jdouble height, jstring tag) {
    try {
        auto text = [&](jstring j) {
            const char* c = env->GetStringUTFChars(j, nullptr);
            std::string s(c);
            env->ReleaseStringUTFChars(j, c);
            return s;
        };
        std::string name = text(face), t = text(tag);
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::lipTool(id, s, name, inside, outside, height, t));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_thread(JNIEnv* env, jobject, jint id, jlong body, jstring face, jdouble pitch, jdouble clearance) {
    try {
        const char* c = env->GetStringUTFChars(face, nullptr);
        std::string name(c);
        env->ReleaseStringUTFChars(face, c);
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::thread(id, s, name, pitch, clearance));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/**
 * A loft through one area of each of several sketches: their planes (nine
 * numbers each), how many curves each has, the curves, and per sketch its
 * picked area as an id count, the ids and a point inside.
 */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_loft(JNIEnv* env, jobject, jint id, jdoubleArray planes, jintArray curveCounts, jintArray kinds,
                                                          jintArray ids, jdoubleArray nums, jintArray pickCounts, jintArray pickIds,
                                                          jdoubleArray pickPoints, jboolean ruled, jdouble twist, jboolean hasGuide,
                                                          jdoubleArray pathPlane, jintArray pathKinds, jintArray pathIds, jdoubleArray pathNums,
                                                          jlong pathBody, jobjectArray pathEdges) {
    try {
        auto p = doubles(env, planes);
        auto counts = ints(env, curveCounts);
        auto k = ints(env, kinds), i = ints(env, ids);
        auto n = doubles(env, nums);
        auto picks = picksOf(ints(env, pickCounts), ints(env, pickIds), doubles(env, pickPoints));
        std::vector<pm::LoftProfile> profiles;
        size_t start = 0;
        for (size_t s = 0; s < counts.size() && s < picks.size(); ++s) {
            size_t c = size_t(counts[s]);
            profiles.push_back({planeOf(p.data() + s * 9), curvesOf(k.data() + start, i.data() + start, n.data() + start * kCurveNumbers, c), picks[s]});
            start += c;
        }
        if (!hasGuide) return keep(pm::loft(id, profiles, ruled, twist));
        TopoDS_Wire guide = pathOf(env, pathPlane, pathKinds, pathIds, pathNums, pathBody, pathEdges);
        return keep(pm::loft(id, profiles, ruled, twist, &guide));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/**
 * Text as outline curves, nine numbers each: kind (0 line, 3 Bézier), start, end, then the two
 * controls. [font] is a built-in one (pm::TextFont); a font file in [data] is used instead if it isn't empty.
 */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_textOutline(JNIEnv* env, jobject, jstring text, jdouble height, jboolean bold, jint font,
                                                                        jbyteArray data) {
    try {
        const char* c = env->GetStringUTFChars(text, nullptr);
        std::string s(c);
        env->ReleaseStringUTFChars(text, c);
        std::vector<uint8_t> file = bytesOf(env, data);
        std::vector<double> out;
        for (const auto& k : pm::textOutline(s, height, bold, 0, pm::TextFont(std::clamp(int(font), 0, 3)), file.empty() ? nullptr : &file))
            out.insert(out.end(), {double(k.kind), k.x1, k.y1, k.x2, k.y2, k.cx1, k.cy1, k.cx2, k.cy2});
        jdoubleArray a = env->NewDoubleArray(jsize(out.size()));
        env->SetDoubleArrayRegion(a, 0, jsize(out.size()), out.data());
        return a;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

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

/** A body's volume (mm³), surface area (mm²) and centre of mass x, y, z. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_properties(JNIEnv* env, jobject, jlong body) {
    std::unique_lock<std::mutex> g(lock);
    // A body let go by a rebuild since it was asked for gives nothing.
    if (!store.has(body)) return env->NewDoubleArray(0);
    const pm::Body b = store.get(body);
    g.unlock();
    double out[5] = {0, 0, 0, 0, 0};
    if (b.solid) {
        GProp_GProps volume, surface;
        BRepGProp::VolumeProperties(b.solid->shape, volume);
        BRepGProp::SurfaceProperties(b.solid->shape, surface);
        out[0] = std::abs(volume.Mass());
        out[1] = surface.Mass();
        out[2] = volume.CentreOfMass().X(); out[3] = volume.CentreOfMass().Y(); out[4] = volume.CentreOfMass().Z();
    } else {
        // From the triangles: each with the origin makes a tetrahedron.
        pm::Mesh m = b.mesh->toMesh();
        double v = 0, cx = 0, cy = 0, cz = 0, area = 0;
        for (const auto& t : m.triangles) {
            const auto& p = m.vertices[t[0]];
            const auto& q = m.vertices[t[1]];
            const auto& r = m.vertices[t[2]];
            double d = (double(p[0]) * (double(q[1]) * r[2] - double(q[2]) * r[1]) - double(p[1]) * (double(q[0]) * r[2] - double(q[2]) * r[0]) +
                        double(p[2]) * (double(q[0]) * r[1] - double(q[1]) * r[0])) / 6;
            v += d;
            cx += d * (p[0] + q[0] + r[0]) / 4; cy += d * (p[1] + q[1] + r[1]) / 4; cz += d * (p[2] + q[2] + r[2]) / 4;
            double ux = q[0] - p[0], uy = q[1] - p[1], uz = q[2] - p[2], wx = r[0] - p[0], wy = r[1] - p[1], wz = r[2] - p[2];
            area += std::sqrt(std::pow(uy * wz - uz * wy, 2) + std::pow(uz * wx - ux * wz, 2) + std::pow(ux * wy - uy * wx, 2)) / 2;
        }
        out[0] = std::abs(v);
        out[1] = area;
        if (std::abs(v) > 1e-12) { out[2] = cx / v; out[3] = cy / v; out[4] = cz / v; }
    }
    jdoubleArray a = env->NewDoubleArray(5);
    env->SetDoubleArrayRegion(a, 0, 5, out);
    return a;
}

/** The box round a body: x, y, z low, then high. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_bounds(JNIEnv* env, jobject, jlong body) {
    std::unique_lock<std::mutex> g(lock);
    // A body let go by a rebuild since it was asked for gives nothing.
    if (!store.has(body)) return env->NewDoubleArray(0);
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
/**
 * A drawing's view of the solid bodies: view is the direction towards the viewer then the view's
 * x direction. Seen curves after a count, then hidden ones the same way, 12 numbers each.
 */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_projectView(JNIEnv* env, jobject, jlongArray handles, jdoubleArray view, jboolean hidden, jdouble fast) {
    try {
        auto v = doubles(env, view);
        std::vector<TopoDS_Shape> shapes;
        {
            std::lock_guard<std::mutex> g(lock);
            for (jlong h : longs(env, handles)) {
                pm::Body b = store.get(h);
                if (b.solid) shapes.push_back(b.solid->shape);
            }
        }
        // fast: the tolerance to work from triangles at, 0 for the exact way, below 0 to choose: the
        // exact way takes minutes on threads and knurls, so shapes like those are worked out from triangles.
        if (fast < 0) fast = pm::viewEffort(shapes) > 600 ? 0.05 : 0;
        pm::ProjectedView pv = fast > 0 ? pm::projectViewFast(shapes, gp_Dir(v[0], v[1], v[2]), gp_Dir(v[3], v[4], v[5]), hidden, fast)
                                        : pm::projectView(shapes, gp_Dir(v[0], v[1], v[2]), gp_Dir(v[3], v[4], v[5]), hidden);
        std::vector<double> out;
        for (const auto* list : {&pv.visible, &pv.hidden}) {
            out.push_back(double(list->size()));
            for (const auto& c : *list) out.insert(out.end(), {double(c.kind), c.x1, c.y1, c.x2, c.y2, c.r, c.a0, c.a1, 0, 0, 0, 0});
        }
        jdoubleArray result = env->NewDoubleArray(jsize(out.size()));
        env->SetDoubleArrayRegion(result, 0, jsize(out.size()), out.data());
        return result;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

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
                                                                       jintArray colours, jint format, jint quality) {
    try {
        std::vector<pm::Body> bodies;
        {
            std::lock_guard<std::mutex> g(lock);
            for (jlong h : longs(env, handles)) bodies.push_back(store.get(h));
        }
        auto labels = strings(env, names);
        auto tints = ints(env, colours);
        const double chords[3] = {0.005, 0.02, 0.1}, angles[3] = {0.1, 0.25, 0.5};
        int q = std::clamp(int(quality), 0, 2);
        if (format == 3 || format == 4) {
            std::vector<pm::NamedMesh> objects;
            for (size_t i = 0; i < bodies.size(); ++i)
                objects.push_back({i < labels.size() ? labels[i] : "Body", bodies[i].asMesh(chords[q], angles[q]).toMesh(), i < tints.size() ? tints[i] : -1});
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
                                                         jdoubleArray constructionPlanes, jdoubleArray axes, jdoubleArray points, jintArray colours,
                                                         jdoubleArray canvasNumbers, jboolean refit) {
    auto cv = doubles(env, canvasNumbers);
    auto cp = doubles(env, constructionPlanes);
    auto ax = doubles(env, axes);
    auto pts = doubles(env, points);
    auto h = longs(env, handles);
    auto tints = ints(env, colours);
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
        s.cornerNames = c.cornerNames;
        triangles += c.triangles;
        meshes.push_back(*c.mesh);
        {
            std::lock_guard<std::mutex> g(lock);
            auto off = bodyOffsets.find(h[i]);
            if (off != bodyOffsets.end()) {
                auto& m = meshes.back();
                auto shift = [&](std::vector<float>& v) {
                    for (size_t k = 0; k + 2 < v.size(); k += 3)
                        for (int a = 0; a < 3; ++a) v[k + a] += off->second[a];
                };
                shift(m.positions);
                shift(m.corners);
                for (auto& e : m.edges) shift(e.points);
            }
        }
        if (i < tints.size() && tints[i] >= 0) {
            int t = tints[i];
            meshes.back().faceColour[0] = float((t >> 16) & 255) / 255;
            meshes.back().faceColour[1] = float((t >> 8) & 255) / 255;
            meshes.back().faceColour[2] = float(t & 255) / 255;
        }
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
    for (const auto& m : threadMarks) {
        meshes.push_back(m);
        Shown sh;
        sh.plane = -2;
        nextShown.push_back(std::move(sh));
    }
    shown = std::move(nextShown);
    shownBodies = bodies;
    shownTriangles = triangles;
    selection.clear();
    renderer.setBodies(std::move(meshes), refit);
    // Pictures: their key, four corners and opacity.
    std::vector<pm::Canvas> canvases;
    for (size_t i = 0; i + 13 < cv.size(); i += 14) {
        auto found = pictures.find(int(cv[i]));
        if (found == pictures.end() || !found->second.rgba) continue;
        pm::Canvas c;
        c.rgba = found->second.rgba;
        c.width = found->second.width;
        c.height = found->second.height;
        for (int k = 0; k < 12; ++k) c.corners[k] = float(cv[i + 1 + size_t(k)]);
        c.opacity = float(cv[i + 13]);
        canvases.push_back(std::move(c));
    }
    renderer.setCanvases(std::move(canvases));
}

/** Reads a picture (PNG or JPEG) and keeps it under [key] for canvases. Returns its width and height, or null if it can't be read. */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_canvasImage(JNIEnv* env, jobject, jint key, jbyteArray bytes) {
    auto data = bytesOf(env, bytes);
    pm::Picture p = pm::decodePicture(std::vector<uint8_t>(data.begin(), data.end()));
    if (!p.rgba) return nullptr;
    {
        std::lock_guard<std::mutex> g(lock);
        pictures[key] = p;
    }
    jint size[2] = {p.width, p.height};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, size);
    return out;
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
    lastTapX = x;
    lastTapY = y;
    // A finger covers thin edges and corners, so it reaches a little way for them.
    pm::Pick p = pickable(renderer.pickNear(x, y, 12, [](const pm::Pick& q) { return pickable(q).kind != pm::Pick::None; }));
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
    lastTapX = x;
    lastTapY = y;
    pm::Pick p = pickable(renderer.pickNear(x, y, 5, [](const pm::Pick& q) { return pickable(q).kind != pm::Pick::None; }));
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
 * A double click: the edge under the point and those running on smoothly
 * from it, in place of the selection or with add, added to it. Anything
 * else under the point is selected as a click would.
 */
JNIEXPORT jintArray JNICALL Java_com_rm_parrotmetric_Core_clickChain(JNIEnv* env, jobject, jfloat x, jfloat y, jboolean add) {
    std::lock_guard<std::mutex> g(lock);
    pm::Pick p = pickable(renderer.pickNear(x, y, 5, [](const pm::Pick& q) { return pickable(q).kind != pm::Pick::None; }));
    if (!add) selection.clear();
    auto pick = [](const pm::Pick& q) {
        if (std::find(selection.begin(), selection.end(), q) == selection.end()) selection.push_back(q);
    };
    if (p.kind == pm::Pick::Edge && p.body < shownBodies.size() && shownBodies[p.body].solid && !shown[p.body].sketch) {
        for (int e : pm::tangentChain(shownBodies[p.body].solid->shape, int(p.index))) pick({pm::Pick::Edge, p.body, uint32_t(e)});
    } else if (p.kind != pm::Pick::None) {
        pick(p);
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

/** The names of the selected corners (NamedShape::cornerNames). */
JNIEXPORT jobjectArray JNICALL Java_com_rm_parrotmetric_Core_selectedCorners(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    std::vector<std::string> out;
    for (const auto& p : selection)
        if (p.kind == pm::Pick::Vertex && p.index < shown[p.body].cornerNames.size() && !shown[p.body].cornerNames[p.index].empty())
            out.push_back(shown[p.body].cornerNames[p.index]);
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

/** Colours bodies to check them for printing; see Renderer::setAnalysis. Thickness shows from the next show(). */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setAnalysis(JNIEnv*, jobject, jint mode, jdouble limit) {
    std::lock_guard<std::mutex> g(lock);
    analysisMode = mode;
    renderer.setAnalysis(mode, float(limit));
}

/** Sketch areas win picks over bodies in front of them; see Renderer::setAreasFirst. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setAreasFirst(JNIEnv*, jobject, jboolean on) {
    std::lock_guard<std::mutex> g(lock);
    renderer.setAreasFirst(on);
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
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_select(JNIEnv* env, jobject, jobjectArray edges, jintArray regions, jobjectArray faces,
                                                            jobjectArray corners) {
    auto names = strings(env, edges);
    auto faceNames = strings(env, faces);
    auto cornerNames = strings(env, corners);
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
            for (uint32_t c = 0; c < s.cornerNames.size(); ++c)
                if (!s.cornerNames[c].empty() && std::find(cornerNames.begin(), cornerNames.end(), s.cornerNames[c]) != cornerNames.end())
                    selection.push_back({pm::Pick::Vertex, b, c});
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

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setCovered(JNIEnv*, jobject, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    std::lock_guard<std::mutex> g(lock);
    renderer.setCovered(left, top, right, bottom);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_viewFrom(JNIEnv*, jobject, jfloat yaw, jfloat pitch) {
    std::lock_guard<std::mutex> g(lock);
    renderer.viewFrom(yaw, pitch);
}

/** The camera to come back to: target x, y, z, yaw, pitch and distance in mm. See Renderer::view. */
JNIEXPORT jfloatArray JNICALL Java_com_rm_parrotmetric_Core_currentView(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    auto v = renderer.view();
    jfloatArray out = env->NewFloatArray(6);
    env->SetFloatArrayRegion(out, 0, 6, v.data());
    return out;
}

/** Moves the camera smoothly to a view as currentView gives it. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_setView(JNIEnv* env, jobject, jfloatArray view) {
    jsize n = env->GetArrayLength(view);
    if (n < 6) return;
    std::array<float, 6> v;
    env->GetFloatArrayRegion(view, 0, 6, v.data());
    std::lock_guard<std::mutex> g(lock);
    renderer.setView(v);
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


JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_offsetFaces(JNIEnv* env, jobject, jint id, jlong body, jobjectArray faces, jdouble distance) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::offsetFaces(id, s, strings(env, faces), distance));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_deleteFaces(JNIEnv* env, jobject, jint id, jlong body, jobjectArray faces) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::deleteFaces(id, s, strings(env, faces)));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** 12 numbers for each place along the path; see pm::pathPlaces. */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_pathPlaces(JNIEnv* env, jobject, jdoubleArray pathPlane, jintArray pathKinds,
                                                                       jintArray pathIds, jdoubleArray pathNums, jlong pathBody,
                                                                       jobjectArray pathEdges, jint count, jdouble spacing, jboolean turn,
                                                                       jboolean reverse) {
    try {
        auto places = pm::pathPlaces(pathOf(env, pathPlane, pathKinds, pathIds, pathNums, pathBody, pathEdges), count, spacing, turn, reverse);
        std::vector<double> flat;
        for (const auto& m : places) flat.insert(flat.end(), m.begin(), m.end());
        jdoubleArray out = env->NewDoubleArray(static_cast<jsize>(flat.size()));
        env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
        return out;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/** kind: 0 reduce (size a tolerance), 1 remesh (size the longest edge), 2 smooth (size the sharp angle in degrees, and steps). */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_meshEdit(JNIEnv* env, jobject, jint, jlong body, jint kind, jdouble size, jint steps) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        g.unlock();
        pm::MeshBody m = b.asMesh();
        pm::Body out;
        switch (kind) {
            case 0: out.mesh = m.reduced(size); break;
            case 1: out.mesh = m.remeshed(size); break;
            case 3: out.mesh = m.hollowed(size); break;
            case 2: out.mesh = m.smoothed(size, steps); break;
        }
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** A rib (or web) from open curves on a plane, joined to the body; see pm::rib. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_rib(JNIEnv* env, jobject, jint id, jlong body, jdoubleArray plane, jintArray kinds, jintArray ids,
                                                         jdoubleArray nums, jdouble thickness, jboolean flip, jboolean web) {
    try {
        auto p = doubles(env, plane);
        auto k = ints(env, kinds), i = ints(env, ids);
        auto n = doubles(env, nums);
        auto curves = curvesOf(k.data(), i.data(), n.data(), k.size());
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::rib(id, s, planeOf(p.data()), curves, thickness, flip, web));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_patch(JNIEnv* env, jobject, jint id, jdoubleArray plane, jintArray kinds, jintArray ids,
                                                           jdoubleArray nums, jintArray pickCounts, jintArray pickIds, jdoubleArray pickPoints) {
    try {
        auto p = doubles(env, plane);
        auto k = ints(env, kinds), i = ints(env, ids);
        auto n = doubles(env, nums);
        auto picks = picksOf(ints(env, pickCounts), ints(env, pickIds), doubles(env, pickPoints));
        return keep(pm::patch(id, planeOf(p.data()), curvesOf(k.data(), i.data(), n.data(), k.size()), picks));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_patchEdges(JNIEnv* env, jobject, jint id, jlong body, jobjectArray edges) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::patchEdges(id, s, strings(env, edges)));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_stitch(JNIEnv* env, jobject, jint id, jlongArray bodies) {
    try {
        auto h = longs(env, bodies);
        std::vector<pm::NamedShape> parts;
        {
            std::lock_guard<std::mutex> g(lock);
            for (jlong x : h) parts.push_back(solidOf(x));
        }
        return keep(pm::stitch(id, parts));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_gather(JNIEnv* env, jobject, jlongArray bodies) {
    try {
        auto h = longs(env, bodies);
        std::vector<pm::NamedShape> parts;
        {
            std::lock_guard<std::mutex> g(lock);
            for (jlong x : h) parts.push_back(solidOf(x));
        }
        return keep(pm::gather(parts));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_thicken(JNIEnv* env, jobject, jint id, jlong body, jdouble thickness, jboolean both) {
    try {
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::thicken(id, s, thickness, both));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_emboss(JNIEnv* env, jobject, jint id, jlong body, jstring face, jdoubleArray plane, jintArray kinds,
                                                            jintArray ids, jdoubleArray nums, jintArray pickCounts, jintArray pickIds,
                                                            jdoubleArray pickPoints, jdouble depth, jboolean sink) {
    try {
        const char* c = env->GetStringUTFChars(face, nullptr);
        std::string name(c);
        env->ReleaseStringUTFChars(face, c);
        auto p = doubles(env, plane);
        auto k = ints(env, kinds), i = ints(env, ids);
        auto n = doubles(env, nums);
        auto picks = picksOf(ints(env, pickCounts), ints(env, pickIds), doubles(env, pickPoints));
        std::unique_lock<std::mutex> g(lock);
        pm::NamedShape s = solidOf(body);
        g.unlock();
        return keep(pm::emboss(id, s, name, planeOf(p.data()), curvesOf(k.data(), i.data(), n.data(), k.size()), picks, depth, sink));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}


// Sculpting. Every call is under the lock, which drawing takes too.

/**
 * Starts sculpting: the packed mesh of a sculpt step being changed, else the
 * body (a solid is turned into even triangles first), else a shape: 0 a
 * sphere [size] mm across, 1 a block. At most [maxTriangles] as detail is added.
 */
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptStart(JNIEnv* env, jobject, jlong body, jbyteArray packed, jint shape, jdouble size,
                                                                     jint maxTriangles) {
    try {
        std::unique_lock<std::mutex> g(lock);
        auto bytes = bytesOf(env, packed);
        // The body it's made from, as a mesh, if there's one.
        std::optional<pm::Mesh> input;
        bool solid = false;
        if (body != 0) {
            if (!store.has(body)) throw std::runtime_error("The body isn't there any more");
            const pm::Body b = store.get(body);
            g.unlock();
            input = b.asMesh().toMesh();
            solid = !b.isMesh();
            g.lock();
        }
        g.unlock();
        std::unique_ptr<pm::Sculpt> made;
        if (!bytes.empty()) {
            // A Sculpt step carried on: as it was, or its strokes made again if what it was made from has changed.
            made = pm::Sculpt::resume(bytes, input ? &*input : nullptr, solid, size_t(maxTriangles));
        } else if (input) {
            made = pm::Sculpt::fromBody(*input, solid, size_t(maxTriangles));
        } else if (shape == 0) {
            made = std::make_unique<pm::Sculpt>(pm::Sculpt::sphere(float(size / 2), 5), size_t(maxTriangles));
        } else {
            float side = float(size);
            made = std::make_unique<pm::Sculpt>(pm::MeshBody::box(side, side, side).translated(-side / 2, -side / 2, -side / 2).toMesh(), size_t(maxTriangles));
            made->evenOut(side / 40);
            made->forget();
        }
        g.lock();
        sculpt = std::move(made);
        renderer.setSculpt(sculpt.get(), true);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return JNI_FALSE;
    }
}

/** How the mesh being sculpted looks; see Renderer::setSculptLook. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptLook(JNIEnv*, jobject, jint look, jboolean wire) {
    std::lock_guard<std::mutex> g(lock);
    renderer.setSculptLook(look, wire);
}

/** Whether (x, y) on the view, in pixels, is over the mesh being sculpted. */
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptHit(JNIEnv*, jobject, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> g(lock);
    if (!sculpt) return JNI_FALSE;
    sculpt->setCamera(renderer.viewProjection(), renderer.width(), renderer.height());
    return sculpt->hit(x, y) ? JNI_TRUE : JNI_FALSE;
}

/** Starts a stroke; see pm::BrushSettings. False if (x, y) is off the mesh. */
JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptBegin(JNIEnv*, jobject, jfloat x, jfloat y, jfloat pressure, jint brush, jfloat radius,
                                                                     jfloat strength, jboolean invert, jint mirror, jboolean dynamic, jfloat detail,
                                                                     jboolean pressureSize, jboolean pressureStrength) {
    std::lock_guard<std::mutex> g(lock);
    if (!sculpt) return JNI_FALSE;
    pm::BrushSettings b;
    b.brush = pm::Brush(std::clamp(int(brush), 0, int(pm::Brush::Mask)));
    b.radius = radius;
    b.strength = strength;
    b.invert = invert;
    b.mirror = mirror;
    b.dynamic = dynamic;
    b.detail = detail;
    b.pressureSize = pressureSize;
    b.pressureStrength = pressureStrength;
    sculpt->setCamera(renderer.viewProjection(), renderer.width(), renderer.height());
    return sculpt->begin(x, y, pressure, b) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptMove(JNIEnv*, jobject, jfloat x, jfloat y, jfloat pressure) {
    std::lock_guard<std::mutex> g(lock);
    if (!sculpt) return;
    sculpt->setCamera(renderer.viewProjection(), renderer.width(), renderer.height());
    sculpt->move(x, y, pressure);
}

JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptEnd(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(lock);
    if (sculpt) sculpt->end();
}

JNIEXPORT jboolean JNICALL Java_com_rm_parrotmetric_Core_sculptUndo(JNIEnv*, jobject, jboolean redo) {
    std::lock_guard<std::mutex> g(lock);
    if (!sculpt) return JNI_FALSE;
    return (redo ? sculpt->redo() : sculpt->undo()) ? JNI_TRUE : JNI_FALSE;
}

/** Whether it can undo, and redo, then its triangles and average edge length (mm). */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_sculptInfo(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    double out[4] = {0, 0, 0, 0};
    if (sculpt) {
        out[0] = sculpt->canUndo();
        out[1] = sculpt->canRedo();
        out[2] = double(sculpt->triangleCount());
        out[3] = sculpt->averageEdge();
    }
    jdoubleArray a = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(a, 0, 4, out);
    return a;
}

/** 0 clears the mask, 1 turns it inside out, 2 evens out the triangles at [edge] mm (0: as they are on average). */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_sculptChange(JNIEnv*, jobject, jint what, jdouble edge) {
    std::lock_guard<std::mutex> g(lock);
    if (!sculpt) return;
    if (what == 0) sculpt->clearMask();
    else if (what == 1) sculpt->invertMask();
    else sculpt->evenOut(edge > 0 ? float(edge) : sculpt->averageEdge());
}

/** Stops sculpting. With keep, the mesh packed for the design file; else nothing. */
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_sculptFinish(JNIEnv* env, jobject, jboolean keep) {
    try {
        std::lock_guard<std::mutex> g(lock);
        renderer.setSculpt(nullptr, false);
        std::unique_ptr<pm::Sculpt> done = std::move(sculpt);
        if (!done || !keep) return array(env, {});
        if (done->stroking()) done->end();
        return array(env, done->save());
    } catch (const std::exception& e) {
        fail(env, e.what());
        return array(env, {});
    }
}

/** The mesh being sculpted as it is now, packed, without stopping; empty if none. */
JNIEXPORT jbyteArray JNICALL Java_com_rm_parrotmetric_Core_sculptPack(JNIEnv* env, jobject) {
    try {
        std::lock_guard<std::mutex> g(lock);
        if (!sculpt || sculpt->stroking()) return array(env, {});
        return array(env, sculpt->save());
    } catch (const std::exception& e) {
        return array(env, {});
    }
}

/**
 * A Sculpt step's mesh body: as it was saved, or if [input], the body it was
 * made from, has changed since, its strokes made again on that.
 */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_sculptedBody(JNIEnv* env, jobject, jint, jbyteArray packed, jlong input) {
    try {
        std::optional<pm::Body> from;
        {
            std::lock_guard<std::mutex> g(lock);
            if (input != 0 && store.has(input)) from = store.get(input);
        }
        std::optional<pm::Mesh> mesh;
        if (from) mesh = from->asMesh().toMesh();
        pm::Body b;
        b.mesh = pm::MeshBody::fromMesh(pm::Sculpt::result(bytesOf(env, packed), mesh ? &*mesh : nullptr, from && !from->isMesh()));
        std::lock_guard<std::mutex> g(lock);
        return store.add(std::move(b));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_gear(JNIEnv* env, jobject, jint id, jdoubleArray plane, jdouble u, jdouble v, jdouble turn, jdouble module,
                                                          jint teeth, jdouble pressureAngle, jdouble thickness, jdouble helix, jboolean herringbone,
                                                          jdouble bore, jdouble clearance) {
    try {
        auto p = doubles(env, plane);
        return keep(pm::gear(id, planeOf(p.data()), u, v, turn, module, teeth, pressureAngle, thickness, helix, herringbone, bore, clearance));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/**
 * Threads to draw as a symbol: a fine helix on each of these round faces, a
 * turn every pitch, just off the face so it shows. Shown from the next show().
 */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_threadMarks(JNIEnv* env, jobject, jlongArray bodies, jobjectArray faces, jdoubleArray pitches) {
    auto h = longs(env, bodies);
    auto names = strings(env, faces);
    auto p = doubles(env, pitches);
    std::vector<pm::DisplayMesh> marks;
    for (size_t i = 0; i < h.size() && i < names.size() && i < p.size(); ++i) {
        try {
            std::unique_lock<std::mutex> g(lock);
            if (!store.has(h[i])) continue;
            pm::NamedShape s = solidOf(h[i]);
            g.unlock();
            for (const auto& f : s.findFaces(names[i])) {
                BRepAdaptor_Surface surface(f);
                if (surface.GetType() != GeomAbs_Cylinder || p[i] <= 0) continue;
                gp_Cylinder cyl = surface.Cylinder();
                double u0, u1, w0, w1;
                BRepTools::UVBounds(f, u0, u1, w0, w1);
                // Out from a shaft, in from a hole: the side it's seen from.
                gp_Pnt at;
                gp_Vec du, dv;
                surface.D1((u0 + u1) / 2, (w0 + w1) / 2, at, du, dv);
                gp_Vec normal = du.Crossed(dv);
                if (f.Orientation() == TopAbs_REVERSED) normal.Reverse();
                gp_Vec axis(cyl.Axis().Direction()), radial(cyl.Location(), at);
                radial -= axis * radial.Dot(axis);
                double r = cyl.Radius() * (normal.Dot(radial) < 0 ? 0.99 : 1.01);
                gp_Vec x(cyl.Position().XDirection()), y(cyl.Position().YDirection());
                const int perTurn = 24;
                int steps = std::min(20000, int(std::ceil((w1 - w0) / p[i] * perTurn)));
                pm::DisplayMesh m;
                pm::DisplayMesh::Edge e;
                for (int k = 0; k <= steps; ++k) {
                    double w = w0 + (w1 - w0) * k / std::max(1, steps), a = 2 * M_PI * (w - w0) / p[i];
                    gp_Pnt q = cyl.Location().Translated(axis * w + x * (r * std::cos(a)) + y * (r * std::sin(a)));
                    e.points.insert(e.points.end(), {float(q.X()), float(q.Y()), float(q.Z())});
                }
                m.edges.push_back(std::move(e));
                const float colour[4] = {0.13f, 0.18f, 0.17f, 0.55f};
                std::copy(colour, colour + 4, m.edgeColour);
                marks.push_back(std::move(m));
            }
        } catch (const std::exception&) {
        }
    }
    std::lock_guard<std::mutex> g(lock);
    threadMarks = std::move(marks);
}

/**
 * The point on a shown mesh body under the last tap or click, as the last
 * frame was drawn, and the body's place in the shown list: four numbers, or
 * null if there's no mesh there or that tap was asked about already.
 */
JNIEXPORT jdoubleArray JNICALL Java_com_rm_parrotmetric_Core_tappedMeshPoint(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(lock);
    const float x = lastTapX, y = lastTapY;
    if (x < 0) return nullptr;
    // Each tap is answered once.
    lastTapX = lastTapY = -1;
    float inv[16];
    if (!pm::Sculpt::invert(renderer.viewProjection(), inv)) return nullptr;
    float w = float(renderer.width()), h = float(renderer.height());
    if (w <= 0 || h <= 0) return nullptr;
    float nx = 2 * x / w - 1, ny = 1 - 2 * y / h;
    auto unproject = [&](float z, double* out) {
        float v[4] = {nx, ny, z, 1}, o[4];
        for (int r = 0; r < 4; ++r) o[r] = inv[r] * v[0] + inv[4 + r] * v[1] + inv[8 + r] * v[2] + inv[12 + r] * v[3];
        for (int k = 0; k < 3; ++k) out[k] = o[k] / o[3];
    };
    double from[3], to[3];
    unproject(-1, from);
    unproject(1, to);
    double dir[3] = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
    double best = 1e300, hit[4] = {0, 0, 0, -1};
    for (size_t s = 0; s < shown.size(); ++s) {
        if (!shown[s].mesh) continue;
        const pm::DisplayMesh& d = *shown[s].mesh;
        for (size_t t = 0; t + 2 < d.indices.size(); t += 3) {
            // Möller-Trumbore.
            const float* a = &d.positions[d.indices[t] * 3];
            const float* b = &d.positions[d.indices[t + 1] * 3];
            const float* c = &d.positions[d.indices[t + 2] * 3];
            double e1[3] = {double(b[0]) - a[0], double(b[1]) - a[1], double(b[2]) - a[2]};
            double e2[3] = {double(c[0]) - a[0], double(c[1]) - a[1], double(c[2]) - a[2]};
            double p[3] = {dir[1] * e2[2] - dir[2] * e2[1], dir[2] * e2[0] - dir[0] * e2[2], dir[0] * e2[1] - dir[1] * e2[0]};
            double det = e1[0] * p[0] + e1[1] * p[1] + e1[2] * p[2];
            if (std::abs(det) < 1e-12) continue;
            double tv[3] = {from[0] - a[0], from[1] - a[1], from[2] - a[2]};
            double u = (tv[0] * p[0] + tv[1] * p[1] + tv[2] * p[2]) / det;
            if (u < 0 || u > 1) continue;
            double q[3] = {tv[1] * e1[2] - tv[2] * e1[1], tv[2] * e1[0] - tv[0] * e1[2], tv[0] * e1[1] - tv[1] * e1[0]};
            double v = (dir[0] * q[0] + dir[1] * q[1] + dir[2] * q[2]) / det;
            if (v < 0 || u + v > 1) continue;
            double along = (e2[0] * q[0] + e2[1] * q[1] + e2[2] * q[2]) / det;
            if (along < 0 || along >= best) continue;
            best = along;
            for (int k = 0; k < 3; ++k) hit[k] = from[k] + dir[k] * along;
            hit[3] = double(s);
        }
    }
    if (hit[3] < 0) return nullptr;
    jdoubleArray out = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(out, 0, 4, hit);
    return out;
}

/** A mesh body hollowed, or erased at spots (x, y, z, radius each) and filled; see pm::MeshBody. */
JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_meshErase(JNIEnv* env, jobject, jint, jlong body, jdoubleArray spots) {
    try {
        auto s = doubles(env, spots);
        std::vector<std::array<double, 4>> list;
        for (size_t i = 0; i + 3 < s.size(); i += 4) list.push_back({s[i], s[i + 1], s[i + 2], s[i + 3]});
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        g.unlock();
        pm::Body out;
        out.mesh = b.asMesh().erased(list);
        g.lock();
        return store.add(std::move(out));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

/** Each separate piece of a body, biggest first: a mesh's, or a solid's solids. */
JNIEXPORT jlongArray JNICALL Java_com_rm_parrotmetric_Core_separate(JNIEnv* env, jobject, jint id, jlong body) {
    try {
        std::unique_lock<std::mutex> g(lock);
        const pm::Body b = store.get(body);
        g.unlock();
        std::vector<pm::Body> pieces;
        if (b.mesh) {
            for (auto& m : b.mesh->parts()) {
                pm::Body p;
                p.mesh = m;
                pieces.push_back(std::move(p));
            }
        } else {
            pm::NamedShape s = b.solid ? *b.solid : pm::NamedShape();
            std::vector<std::pair<double, TopoDS_Shape>> solids;
            for (TopExp_Explorer e(s.shape, TopAbs_SOLID); e.More(); e.Next()) {
                GProp_GProps props;
                BRepGProp::VolumeProperties(e.Current(), props);
                solids.push_back({props.Mass(), e.Current()});
            }
            std::sort(solids.begin(), solids.end(), [](const auto& a, const auto& c) { return a.first > c.first; });
            for (const auto& [v, shape] : solids) {
                pm::Body p;
                pm::NamedShape piece;
                piece.shape = shape;
                // Each piece keeps the names its faces had.
                for (TopExp_Explorer f(shape, TopAbs_FACE); f.More(); f.Next())
                    if (s.names.IsBound(f.Current())) piece.names.Bind(f.Current(), s.names.Find(f.Current()));
                for (TopExp_Explorer f(shape, TopAbs_EDGE); f.More(); f.Next())
                    if (s.names.IsBound(f.Current())) piece.names.Bind(f.Current(), s.names.Find(f.Current()));
                p.solid = piece;
                pieces.push_back(std::move(p));
            }
        }
        if (pieces.size() < 2) throw std::runtime_error("It's all one piece");
        std::vector<jlong> handles;
        g.lock();
        for (auto& p : pieces) handles.push_back(store.add(std::move(p)));
        g.unlock();
        jlongArray out = env->NewLongArray(jsize(handles.size()));
        env->SetLongArrayRegion(out, 0, jsize(handles.size()), handles.data());
        return out;
    } catch (const std::exception& e) {
        fail(env, e.what());
        return nullptr;
    }
}

/** Draws these bodies moved by these offsets (x, y, z each) from the next show(), for an exploded view; others where they are. */
JNIEXPORT void JNICALL Java_com_rm_parrotmetric_Core_bodyOffsets(JNIEnv* env, jobject, jlongArray bodies, jdoubleArray offsets) {
    auto h = longs(env, bodies);
    auto o = doubles(env, offsets);
    std::lock_guard<std::mutex> g(lock);
    bodyOffsets.clear();
    for (size_t i = 0; i < h.size() && i * 3 + 2 < o.size(); ++i)
        bodyOffsets[h[i]] = {float(o[i * 3]), float(o[i * 3 + 1]), float(o[i * 3 + 2])};
}

JNIEXPORT jlong JNICALL Java_com_rm_parrotmetric_Core_fastener(JNIEnv* env, jobject, jint id, jdoubleArray seat, jint kind, jdouble d, jdouble length,
                                                              jdouble head, jdouble headHeight, jdouble socket, jdouble angle) {
    try {
        auto p = doubles(env, seat);
        return keep(pm::fastener(id, planeOf(p.data()), pm::Fastener(kind), d, length, head, headHeight, socket, angle));
    } catch (const std::exception& e) {
        fail(env, e.what());
        return 0;
    }
}

}  // extern "C"
