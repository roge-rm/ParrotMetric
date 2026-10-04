#pragma once

#include <memory>

#include "display/display_mesh.h"
#include "mesh/mesh.h"

class TopoDS_Shape;

namespace pm {

/** How finely a solid is turned into triangles. */
struct Tessellation {
    double chord = 0.01;   // Largest gap between a triangle and the true surface, mm.
    double angle = 0.25;   // Largest angle between neighbouring triangles on a curve, radians.
};

/** A boundary-represented solid body. Immutable; operations return new ones. */
class Solid {
public:
    static Solid box(double x, double y, double z);
    /** Wraps a shape from OCCT, such as one read from a file. */
    static Solid fromShape(const TopoDS_Shape& shape) { return Solid(shape); }

    /** Rounds every edge. Throws std::runtime_error if the radius doesn't fit. */
    Solid filletAllEdges(double radius) const;

    Mesh tessellate(const Tessellation& t = {}) const;
    /** For the renderer: smooth normals on each face, and every edge. Numbered in OCCT map order. */
    DisplayMesh display(const Tessellation& t = {}) const;
    double volume() const;

    const TopoDS_Shape& shape() const { return *shape_; }

private:
    explicit Solid(const TopoDS_Shape& shape);
    std::shared_ptr<const TopoDS_Shape> shape_;
};

}  // namespace pm
