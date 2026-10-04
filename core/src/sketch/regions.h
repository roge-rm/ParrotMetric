#pragma once

#include <array>
#include <vector>

namespace pm {

/** A sketch curve in the sketch plane's own x and y, in mm. Construction curves aren't passed in. */
struct SketchCurve {
    enum Kind { Line, Circle, Arc };
    Kind kind = Line;
    int id = 0;
    // Line: from (x1, y1) to (x2, y2). Circle and arc: centre (x1, y1), radius r.
    double x1 = 0, y1 = 0, x2 = 0, y2 = 0, r = 0;
    // Arc: anticlockwise from angle a0 to a1, in radians.
    double a0 = 0, a1 = 0;
};

/**
 * One closed area the curves enclose, with no curve crossing it: what an
 * extrude can use. Curves that cross each other split areas where they cross.
 */
struct Region {
    using Loop = std::vector<std::array<float, 2>>;
    std::vector<Loop> loops;       // The outline first, then any holes, as points round each.
    std::vector<int> curveIds;     // Every curve along its edges, sorted, once each.
    double area = 0;               // mm²
    double insideU = 0, insideV = 0;  // A point inside it, for telling apart regions with the same curves round them.
};

std::vector<Region> findRegions(const std::vector<SketchCurve>& curves);

}  // namespace pm
