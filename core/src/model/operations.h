#pragma once

#include <gp_Ax3.hxx>

#include <string>
#include <vector>

#include "mesh/mesh.h"
#include "model/named_shape.h"
#include "sketch/regions.h"

namespace pm {

/**
 * The features as geometry. Each takes its inputs and the feature's number,
 * which goes at the front of the names of the faces it makes ("F3...").
 * Each throws std::runtime_error with a short reason when it can't be built.
 */

/** Which region of a sketch: the curves round it, and a point inside it as it was picked. */
struct RegionPick {
    std::vector<int> curveIds;
    double u = 0, v = 0;
};

/**
 * Sweeps sketch regions straight along the plane's normal, from `back`
 * behind the plane to `forward` in front of it (mm; either may be negative,
 * but not both ending where they start). Sides are named F<id>.s<curve>,
 * the ends F<id>.start and F<id>.end. A taper (radians) leans the sides in
 * going forward, pivoting at the plane.
 */
NamedShape extrude(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double forward, double back, double taper = 0);

/**
 * Turns sketch regions round an axis in the sketch plane, from (ax, ay)
 * along (dx, dy), by angle radians. Faces are named as for extrude.
 */
NamedShape revolve(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double ax, double ay, double dx, double dy, double angle);

enum class Combine { Join, Cut, Intersect };

/** Joins, cuts or intersects target with tool. */
NamedShape combine(int id, const NamedShape& target, const NamedShape& tool, Combine how);

/** Rounds the named edges. New faces are F<id>.r(<edge name>). */
NamedShape fillet(int id, const NamedShape& body, const std::vector<std::string>& edges, double radius);

enum class ChamferKind { Equal, TwoDistances, DistanceAngle };

/**
 * Bevels the named edges: distance along both faces, or along one face with
 * `second` along the other (mm) or at `second` radians to it. The first face
 * of each edge gets `distance`, the other one if flip. New faces are
 * F<id>.c(<edge name>).
 */
NamedShape chamfer(int id, const NamedShape& body, const std::vector<std::string>& edges, double distance,
                   ChamferKind kind = ChamferKind::Equal, double second = 0, bool flip = false);

/** Hollows a solid to walls `thickness` thick inside it, leaving the named faces open. */
NamedShape shell(int id, const NamedShape& body, const std::vector<std::string>& open, double thickness);

/**
 * Tilts the named faces by angle radians, pivoting where they meet the
 * named neutral face and pulling along its normal.
 */
NamedShape draft(int id, const NamedShape& body, const std::vector<std::string>& faces, const std::string& neutral, double angle);

/**
 * A moved copy of a body: m is a 3x4 matrix, rows of rotation then
 * translation, and may scale, unevenly too. Faces are named
 * F<id>.<tag>(<old name>).
 */
NamedShape transformed(int id, const NamedShape& body, const double m[12], const std::string& tag);

/** The solid pieces of a body on each side of a plane. */
std::vector<NamedShape> split(int id, const NamedShape& body, const gp_Pnt& origin, const gp_Dir& normal);

/** How a hole's top is shaped. */
enum class HoleKind { Simple, Counterbore, Countersink };

/**
 * The shape a set of holes takes out: one per (u, v) on the plane, going in
 * against its normal. depth 0 goes right through (2 m). A counterbore is a
 * wider step `topDepth` deep; a countersink a 90 degree cone `topDiameter`
 * across at the top.
 */
NamedShape holeTool(int id, const gp_Ax3& plane, const std::vector<std::pair<double, double>>& at, double diameter, double depth,
                    HoleKind kind, double topDiameter, double topDepth);

/**
 * Edges as sketch curves on a plane: lines, circles and arcs as they are,
 * anything else as short lines. The edges should lie in the plane.
 */
std::vector<SketchCurve> curvesOnPlane(const TopoDS_Shape& edges, const gp_Ax3& plane);

/** Where a solid crosses a plane, as sketch curves on it. */
std::vector<SketchCurve> section(const NamedShape& body, const gp_Ax3& plane);

/**
 * A mesh made into a solid: each triangle a face, then flat neighbours
 * merged. Faces are named F<id>.i<k>. Throws if it doesn't close up.
 */
NamedShape meshToSolid(int id, const Mesh& mesh);

/** The plane of a named flat face, facing out, with x as given if it lies in the plane. Throws if the face isn't flat. */
gp_Ax3 facePlane(const NamedShape& body, const std::string& face);

/** Whether two solids share any volume. */
bool overlaps(const NamedShape& a, const NamedShape& b);

}  // namespace pm
