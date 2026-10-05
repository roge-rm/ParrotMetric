#pragma once

#include <TopoDS_Wire.hxx>
#include <gp_Ax3.hxx>

#include <array>
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
 * going forward, pivoting at the plane. A thin extrude (thin > 0, mm) keeps
 * only a wall that thick inside each region's edges.
 */
NamedShape extrude(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double forward, double back, double taper = 0, double thin = 0);

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

enum class FilletKind { Constant, Variable, Chord };

/**
 * Rounds edges: Constant, [size] radius; Variable, from [size] at each
 * edge's start to [second] at its end; Chord, so the rounding is [size]
 * across from face to face.
 */
NamedShape fillet(int id, const NamedShape& body, const std::vector<std::string>& edges, FilletKind kind, double size, double second);

/**
 * Moves faces along their normals by [distance] (out when more than 0),
 * the body growing or shrinking to follow, as pressing or pulling them.
 * Each moved face keeps its name.
 */
NamedShape offsetFaces(int id, const NamedShape& body, const std::vector<std::string>& faces, double distance);

/** Takes faces away and closes the gap they leave, as removing a fillet, hole or boss does. */
NamedShape deleteFaces(int id, const NamedShape& body, const std::vector<std::string>& faces);

/**
 * Where copies go along a path: [count] of them, spread evenly over its
 * length, or [spacing] mm apart when that's more than 0. Each is a 3x4
 * matrix (rows of rotation then translation) taking the path's start to
 * its place; with [turn], turned to follow the path too. The first is the
 * start itself. With [reverse], the path is taken from its end back.
 */
std::vector<std::array<double, 12>> pathPlaces(const TopoDS_Wire& path, int count, double spacing, bool turn, bool reverse = false);

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

enum class Primitive { Box, Cylinder, Sphere, Torus, Cone };

/**
 * A simple solid standing on a plane, centred on (u, v) in it. Sizes (mm):
 * box width a (along the plane's x), depth b and height c; cylinder
 * diameter a and height b; sphere diameter a; torus diameter a across the
 * middle of its tube, and tube diameter b; cone base diameter a, top
 * diameter b (0 for a point) and height c. Flat faces are named by side:
 * F<id>.start (on the plane), .end (the top), and the box's .x0, .x1, .y0,
 * .y1; curved faces F<id>.side.
 */
NamedShape primitive(int id, const gp_Ax3& plane, Primitive kind, double u, double v, double a, double b, double c);

/**
 * A body cut into pieces where another body's surface passes through it:
 * the parts inside the tool and the parts outside. The tool isn't changed.
 */
std::vector<NamedShape> splitBy(int id, const NamedShape& body, const NamedShape& tool);

/** How much two bodies overlap, mm³; 0 if they don't. */
double overlapVolume(const NamedShape& a, const NamedShape& b);

/** Sketch curves on a plane joined end to end into one path. Throws if they don't make one chain. */
TopoDS_Wire pathFromSketch(const gp_Ax3& plane, const std::vector<SketchCurve>& curves);

/** Edges joined end to end into one path. Throws if they don't make one chain. */
TopoDS_Wire pathFromEdges(const std::vector<TopoDS_Edge>& edges);

/**
 * Sweeps sketch regions along a path, the regions staying as square to it
 * as they start. Sides are named F<id>.s<curve> as for extrude, the ends
 * F<id>.start and F<id>.end.
 */
NamedShape sweep(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                 const TopoDS_Wire& path);

/** A round tube along a path, [diameter] across; hollow when [inner] (a diameter) is more than 0. */
NamedShape pipe(int id, const TopoDS_Wire& path, double diameter, double inner);

/**
 * A coil round an axis standing on a plane at (u, v): [diameter] across the
 * middle of its wire, rising [pitch] mm a turn for [turns] turns, its wire
 * [section] mm across, round or [square].
 */
NamedShape coil(int id, const gp_Ax3& plane, double u, double v, double diameter, double pitch, double turns, double section, bool square);

/**
 * A body with a thread cut into one of its round faces: on the outside of
 * a shaft or the inside of a hole, as the face looks. ISO metric: a 60°
 * groove [pitch] mm a turn, as long as the face. The groove is named F<id>.t.
 */
NamedShape thread(int id, const NamedShape& body, const std::string& face, double pitch);

/**
 * A rib or web from open sketch curves, grown until it meets the body and
 * joined to it. A rib is [thickness] across the sketch plane, centred on it,
 * and grows in the plane, square to the curves' run (the other way with
 * [flip]). A web is [thickness] wide in the plane and grows along the
 * plane's normal (against it with [flip]). New faces are F<id>.w<n>.
 */
NamedShape rib(int id, const NamedShape& body, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, double thickness, bool flip, bool web);

/**
 * Sketch areas projected along the sketch's normal onto a face of the body,
 * flat or curved, and raised out of it or with [sink] sunk into it, [depth]
 * mm measured square to the face. New faces are F<id>.e<n>.
 */
NamedShape emboss(int id, const NamedShape& body, const std::string& face, const gp_Ax3& plane, const std::vector<SketchCurve>& curves,
                  const std::vector<RegionPick>& picks, double depth, bool sink);

/** Sketch areas as flat surfaces with no thickness. Faces are F<id>.a<n>. */
NamedShape patch(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks);

/** A surface filling a closed loop of a body's edges, curving to meet them. The face is F<id>.a0. */
NamedShape patchEdges(int id, const NamedShape& body, const std::vector<std::string>& edges);

/** Surfaces sewn together where their edges meet; a solid if they close round. Faces keep their names. */
NamedShape stitch(int id, const std::vector<NamedShape>& parts);

/** A surface made thick, to one side of it or [both] sides. New faces are F<id>.k<n>. */
NamedShape thicken(int id, const NamedShape& surface, double thickness, bool both);

/** One area of a sketch, for a loft. */
struct LoftProfile {
    gp_Ax3 plane;
    std::vector<SketchCurve> curves;
    RegionPick pick;
};

/** A solid through areas in order, smooth or [ruled] (straight between them). The ends are F<id>.start and F<id>.end. */
NamedShape loft(int id, const std::vector<LoftProfile>& profiles, bool ruled);

/** The box round a body: x, y, z low, then x, y, z high. */
std::array<double, 6> bounds(const NamedShape& body);

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

/**
 * Rings round the openings in a flat rim face, standing [height] mm out of
 * it: from [inside] to [outside] mm out from each opening's edge (less than
 * 0 is into the opening). A lip on a base is (0, width); the groove in its
 * lid is (-gap, width + gap). Faces are F<id>.<tag><n>.
 */
NamedShape lipTool(int id, const NamedShape& body, const std::string& face, double inside, double outside, double height, const std::string& tag);

/**
 * The edges that run on smoothly from an edge, end to end, as a rounded
 * rectangle's outline does: their numbers in OCCT map order, the edge's own first.
 */
std::vector<int> tangentChain(const TopoDS_Shape& shape, int edge);

/** Whether two solids share any volume or meet over a face, so that joining them makes one solid. */
bool overlaps(const NamedShape& a, const NamedShape& b);

}  // namespace pm
