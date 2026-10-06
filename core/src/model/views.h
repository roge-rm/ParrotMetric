#pragma once

#include <TopoDS_Shape.hxx>
#include <gp_Dir.hxx>

#include <vector>

#include "sketch/regions.h"

namespace pm {

/** A flat view of solids for a drawing: the lines seen, and those hidden behind. */
struct ProjectedView {
    std::vector<SketchCurve> visible, hidden;
};

/**
 * The sharp edges and outlines of [shapes] as seen from [towardsViewer] (a direction pointing at the
 * viewer), with [right] running across the view. In the view's own x, y, mm. Hidden lines only when asked.
 */
ProjectedView projectView(const std::vector<TopoDS_Shape>& shapes, const gp_Dir& towardsViewer, const gp_Dir& right, bool hidden);

/**
 * A rough measure of how slow the exact way would be: each curved face that isn't a cylinder, cone,
 * sphere or torus counts by its control points, the rest count one.
 */
double viewEffort(const std::vector<TopoDS_Shape>& shapes);

/**
 * The same worked out from the shapes' triangles: much quicker on helical faces such as threads and
 * knurls, but curves come out as short lines, at [tolerance] mm.
 */
ProjectedView projectViewFast(const std::vector<TopoDS_Shape>& shapes, const gp_Dir& towardsViewer, const gp_Dir& right, bool hidden, double tolerance);

}  // namespace pm
