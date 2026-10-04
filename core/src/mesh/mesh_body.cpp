#include "mesh/mesh_body.h"

#include <manifold/manifold.h>
#include <algorithm>
#include <numeric>

#include <stdexcept>

namespace pm {
namespace {

const char* errorText(manifold::Manifold::Error e) {
    using E = manifold::Manifold::Error;
    switch (e) {
        case E::NotManifold: return "The mesh has holes or edges shared by more than two triangles";
        case E::NonFiniteVertex: return "The mesh has a corner at an invalid position";
        case E::VertexOutOfBounds: return "The mesh refers to a corner that isn't there";
        default: return "The mesh couldn't be used as a body";
    }
}

}  // namespace

MeshBody::MeshBody(const manifold::Manifold& m) : m_(new manifold::Manifold(m)) {}
MeshBody::MeshBody(const MeshBody& o) : m_(new manifold::Manifold(*o.m_)) {}
MeshBody& MeshBody::operator=(const MeshBody& o) {
    *m_ = *o.m_;
    return *this;
}
MeshBody::~MeshBody() { delete m_; }

MeshBody MeshBody::fromMesh(const Mesh& mesh) {
    manifold::MeshGL gl;
    gl.numProp = 3;
    gl.vertProperties.reserve(mesh.vertices.size() * 3);
    for (const auto& v : mesh.vertices) gl.vertProperties.insert(gl.vertProperties.end(), v.begin(), v.end());
    gl.triVerts.reserve(mesh.triangles.size() * 3);
    for (const auto& t : mesh.triangles) gl.triVerts.insert(gl.triVerts.end(), t.begin(), t.end());
    manifold::Manifold m(gl);
    if (m.Status() != manifold::Manifold::Error::NoError) throw std::runtime_error(errorText(m.Status()));
    return MeshBody(m);
}

MeshBody MeshBody::box(float x, float y, float z) { return MeshBody(manifold::Manifold::Cube({x, y, z})); }

MeshBody MeshBody::boolean(const MeshBody& tool, BooleanOp op) const {
    switch (op) {
        case BooleanOp::Join: return MeshBody(*m_ + *tool.m_);
        case BooleanOp::Cut: return MeshBody(*m_ - *tool.m_);
        case BooleanOp::Intersect: return MeshBody(*m_ ^ *tool.m_);
    }
    return *this;
}

MeshBody MeshBody::translated(float x, float y, float z) const { return MeshBody(m_->Translate({x, y, z})); }

Mesh MeshBody::toMesh() const {
    manifold::MeshGL gl = m_->GetMeshGL();
    Mesh mesh;
    mesh.vertices.resize(gl.NumVert());
    for (size_t i = 0; i < mesh.vertices.size(); ++i) {
        const float* p = &gl.vertProperties[i * gl.numProp];
        mesh.vertices[i] = {p[0], p[1], p[2]};
    }
    mesh.triangles.resize(gl.NumTri());
    for (size_t i = 0; i < mesh.triangles.size(); ++i) {
        mesh.triangles[i] = {gl.triVerts[i * 3], gl.triVerts[i * 3 + 1], gl.triVerts[i * 3 + 2]};
    }
    return mesh;
}

MeshBody MeshBody::transformed(const double m[12]) const {
    manifold::mat3x4 t({m[0], m[4], m[8]}, {m[1], m[5], m[9]}, {m[2], m[6], m[10]}, {m[3], m[7], m[11]});
    return MeshBody(m_->Transform(t));
}

std::pair<MeshBody, MeshBody> MeshBody::split(const double origin[3], const double normal[3]) const {
    manifold::vec3 n(normal[0], normal[1], normal[2]);
    double offset = origin[0] * normal[0] + origin[1] * normal[1] + origin[2] * normal[2];
    auto [front, back] = m_->SplitByPlane(n, offset);
    return {MeshBody(front), MeshBody(back)};
}

bool MeshBody::empty() const { return m_->IsEmpty(); }

MeshBody MeshBody::reduced(double tolerance) const {
    if (tolerance <= 0) throw std::runtime_error("The tolerance has to be more than 0");
    return MeshBody(m_->Simplify(tolerance));
}

MeshBody MeshBody::remeshed(double length) const {
    if (length <= 0) throw std::runtime_error("The edge length has to be more than 0");
    // About the size of the body's box divided by the length, cubed and squared; kept from running away.
    auto b = bounds();
    double extent = std::max({b[3] - b[0], b[4] - b[1], b[5] - b[2]});
    if (extent / length > 2000) throw std::runtime_error("That would make too many triangles; use a longer edge");
    return MeshBody(m_->RefineToLength(length));
}

MeshBody MeshBody::smoothed(double sharpAngle, int steps) const {
    if (steps < 1 || steps > 6) throw std::runtime_error("Use between 1 and 6 steps");
    if (triangleCount() * size_t(steps) * size_t(steps) > 4000000) throw std::runtime_error("That would make too many triangles");
    // Manifold keeps the faces it groups as flat flat, and it groups near-flat neighbours;
    // each triangle its own face lets them all curve.
    manifold::MeshGL gl = m_->GetMeshGL();
    gl.faceID.resize(gl.NumTri());
    std::iota(gl.faceID.begin(), gl.faceID.end(), 0u);
    gl.runIndex.clear();
    gl.runOriginalID.clear();
    gl.runTransform.clear();
    manifold::Manifold apart(gl);
    if (apart.Status() != manifold::Manifold::Error::NoError) throw std::runtime_error("The mesh couldn't be smoothed");
    return MeshBody(apart.SmoothOut(sharpAngle, 0).Refine(steps));
}

std::vector<std::vector<std::array<double, 2>>> MeshBody::slice(const double o[3], const double x[3], const double y[3]) const {
    // Into the plane's own coordinates (the inverse of its frame, a rotation), so the plane is z = 0.
    double n[3] = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
    auto dot = [](const double a[3], const double b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; };
    manifold::mat3x4 t({x[0], y[0], n[0]}, {x[1], y[1], n[1]}, {x[2], y[2], n[2]}, {-dot(x, o), -dot(y, o), -dot(n, o)});
    std::vector<std::vector<std::array<double, 2>>> out;
    for (const auto& poly : m_->Transform(t).Slice(0)) {
        std::vector<std::array<double, 2>> loop;
        for (const auto& p : poly) loop.push_back({p.x, p.y});
        out.push_back(std::move(loop));
    }
    return out;
}

std::array<double, 3> MeshBody::centre() const {
    auto box = m_->BoundingBox();
    return {(box.min.x + box.max.x) / 2, (box.min.y + box.max.y) / 2, (box.min.z + box.max.z) / 2};
}

std::array<double, 6> MeshBody::bounds() const {
    auto box = m_->BoundingBox();
    return {box.min.x, box.min.y, box.min.z, box.max.x, box.max.y, box.max.z};
}

double MeshBody::volume() const { return m_->Volume(); }
size_t MeshBody::triangleCount() const { return m_->NumTri(); }

}  // namespace pm
