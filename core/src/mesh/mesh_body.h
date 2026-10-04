#pragma once

#include <string>

#include "mesh/mesh.h"

namespace manifold {
class Manifold;
}

namespace pm {

enum class BooleanOp { Join, Cut, Intersect };

/**
 * A closed triangle mesh body, for imported meshes and for booleans between
 * meshes. A MeshBody is immutable; the operations return new ones.
 */
class MeshBody {
public:
    /** Throws std::runtime_error with the reason if the mesh isn't closed and manifold. */
    static MeshBody fromMesh(const Mesh& mesh);
    /** A box from the origin to size, for tests and quick shapes. */
    static MeshBody box(float x, float y, float z);

    MeshBody(const MeshBody&);
    MeshBody& operator=(const MeshBody&);
    ~MeshBody();

    MeshBody boolean(const MeshBody& tool, BooleanOp op) const;
    MeshBody translated(float x, float y, float z) const;

    Mesh toMesh() const;
    double volume() const;
    size_t triangleCount() const;

private:
    explicit MeshBody(const manifold::Manifold& m);
    manifold::Manifold* m_;
};

}  // namespace pm
