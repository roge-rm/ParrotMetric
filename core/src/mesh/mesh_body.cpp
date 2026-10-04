#include "mesh/mesh_body.h"

#include <manifold/manifold.h>

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

double MeshBody::volume() const { return m_->Volume(); }
size_t MeshBody::triangleCount() const { return m_->NumTri(); }

}  // namespace pm
