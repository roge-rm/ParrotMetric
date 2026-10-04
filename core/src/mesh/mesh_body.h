#pragma once

#include <cstddef>
#include <string>
#include <array>
#include <utility>
#include <vector>

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
    /** Moved by a 3x4 matrix, rows of rotation and then translation: m[0..3] is the first row. */
    MeshBody transformed(const double m[12]) const;
    /** The parts on each side of a plane: first the side the normal points to. Either may be empty. */
    std::pair<MeshBody, MeshBody> split(const double origin[3], const double normal[3]) const;
    bool empty() const;
    /**
     * Where the mesh crosses a plane, as closed loops in the plane's own x
     * and y. The plane is given by its origin, x direction and y direction.
     */
    std::vector<std::vector<std::array<double, 2>>> slice(const double origin[3], const double x[3], const double y[3]) const;
    /** The middle of its bounding box. */
    std::array<double, 3> centre() const;

    Mesh toMesh() const;
    double volume() const;
    size_t triangleCount() const;

private:
    explicit MeshBody(const manifold::Manifold& m);
    manifold::Manifold* m_;
};

}  // namespace pm
