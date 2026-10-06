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
    /** Fewer triangles, the surface moving no more than tolerance mm. */
    MeshBody reduced(double tolerance) const;
    /** Long triangles split until no edge is longer than length mm. */
    MeshBody remeshed(double length) const;
    /**
     * Rounded off: each triangle split into steps x steps and laid on a
     * smooth surface through the corners, keeping edges sharper than
     * sharpAngle degrees.
     */
    MeshBody smoothed(double sharpAngle, int steps) const;
    /**
     * Hollowed out, leaving walls [thickness] mm thick all round inside. The
     * inside is found on a grid at most [cells] cells, so it's a little rough
     * at about the grid's size; throws if that's too coarse for the walls.
     */
    MeshBody hollowed(double thickness, size_t cells = 6000000) const;
    /**
     * The triangles near each spot taken away (x, y, z and a radius each, mm)
     * and each hole that leaves filled with a smooth patch.
     */
    MeshBody erased(const std::vector<std::array<double, 4>>& spots) const;
    /** Each separate piece, biggest first. */
    std::vector<MeshBody> parts() const;
    bool empty() const;
    /**
     * Where the mesh crosses a plane, as closed loops in the plane's own x
     * and y. The plane is given by its origin, x direction and y direction.
     */
    std::vector<std::vector<std::array<double, 2>>> slice(const double origin[3], const double x[3], const double y[3]) const;
    /** The middle of its bounding box. */
    std::array<double, 3> centre() const;
    /** The box round it: x, y, z low, then x, y, z high. */
    std::array<double, 6> bounds() const;

    Mesh toMesh() const;
    double volume() const;
    size_t triangleCount() const;

private:
    explicit MeshBody(const manifold::Manifold& m);
    manifold::Manifold* m_;
};

}  // namespace pm
