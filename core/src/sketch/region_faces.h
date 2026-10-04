#pragma once

#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>

#include <utility>
#include <vector>

#include "sketch/regions.h"

namespace pm {

/** A region as an OCCT face in the XY plane, with the sketch curve each of its edges came from. */
struct RegionFace {
    TopoDS_Face face;
    std::vector<std::pair<TopoDS_Edge, int>> edgeCurves;
    Region info;
};

/** One sketch curve as an edge in the XY plane, or a null edge if it has no length. */
TopoDS_Edge sketchEdge(const SketchCurve& c);

/** The regions of a sketch's curves as faces, largest first. */
std::vector<RegionFace> buildRegionFaces(const std::vector<SketchCurve>& curves);

}  // namespace pm
