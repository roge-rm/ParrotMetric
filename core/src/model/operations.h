#pragma once

#include <gp_Ax3.hxx>

#include <string>
#include <vector>

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
 * the ends F<id>.start and F<id>.end.
 */
NamedShape extrude(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double forward, double back);

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

/** Bevels the named edges by distance. New faces are F<id>.c(<edge name>). */
NamedShape chamfer(int id, const NamedShape& body, const std::vector<std::string>& edges, double distance);

/** The plane of a named flat face, facing out, with x as given if it lies in the plane. Throws if the face isn't flat. */
gp_Ax3 facePlane(const NamedShape& body, const std::string& face);

/** Whether two solids share any volume. */
bool overlaps(const NamedShape& a, const NamedShape& b);

}  // namespace pm
