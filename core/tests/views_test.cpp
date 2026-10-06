#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include <algorithm>

#include "model/operations.h"
#include "model/views.h"

using namespace pm;

namespace {

const gp_Ax3 top(gp_Pnt(0, 0, 0), gp::DZ(), gp::DX());

/** The box round the curves: x, y low then high. */
std::array<double, 4> extent(const std::vector<SketchCurve>& cs) {
    std::array<double, 4> b{1e9, 1e9, -1e9, -1e9};
    auto take = [&](double x, double y) { b[0] = std::min(b[0], x); b[1] = std::min(b[1], y); b[2] = std::max(b[2], x); b[3] = std::max(b[3], y); };
    for (const auto& c : cs) {
        if (c.kind == SketchCurve::Line) { take(c.x1, c.y1); take(c.x2, c.y2); }
        else { take(c.x1 - c.r, c.y1 - c.r); take(c.x1 + c.r, c.y1 + c.r); }
    }
    return b;
}

}  // namespace

TEST_CASE("a box with a hole seen from the top and the front") {
    NamedShape box = primitive(1, top, Primitive::Box, 0, 0, 40, 20, 10);
    NamedShape hole = primitive(2, top, Primitive::Cylinder, 0, 0, 8, 10, 0);
    NamedShape part = combine(3, box, hole, Combine::Cut);
    // From above, x across: the outline 40 by 20, and the hole as a circle.
    ProjectedView above = projectView({part.shape}, gp::DZ(), gp::DX(), true);
    auto b = extent(above.visible);
    CHECK(b[2] - b[0] == Catch::Approx(40).margin(1e-6));
    CHECK(b[3] - b[1] == Catch::Approx(20).margin(1e-6));
    CHECK(std::count_if(above.visible.begin(), above.visible.end(), [](const SketchCurve& c) { return c.kind == SketchCurve::Circle && std::abs(c.r - 4) < 1e-6; }) >= 1);
    CHECK(above.hidden.empty());
    // From the front: 40 wide, 10 high; the hole's sides hidden.
    ProjectedView front = projectView({part.shape}, gp_Dir(0, -1, 0), gp::DX(), true);
    auto f = extent(front.visible);
    CHECK(f[2] - f[0] == Catch::Approx(40).margin(1e-6));
    CHECK(f[3] - f[1] == Catch::Approx(10).margin(1e-6));
    auto h = extent(front.hidden);
    CHECK(h[2] - h[0] == Catch::Approx(8).margin(1e-6));
    // Without hidden lines, none.
    CHECK(projectView({part.shape}, gp_Dir(0, -1, 0), gp::DX(), false).hidden.empty());
}

TEST_CASE("the quick way from triangles gives the same outline") {
    NamedShape box = primitive(1, top, Primitive::Box, 0, 0, 40, 20, 10);
    NamedShape hole = primitive(2, top, Primitive::Cylinder, 0, 0, 8, 10, 0);
    NamedShape part = combine(3, box, hole, Combine::Cut);
    ProjectedView front = projectViewFast({part.shape}, gp_Dir(0, -1, 0), gp::DX(), true, 0.05);
    auto f = extent(front.visible);
    CHECK(f[2] - f[0] == Catch::Approx(40).margin(1e-3));
    CHECK(f[3] - f[1] == Catch::Approx(10).margin(1e-3));
    auto h = extent(front.hidden);
    CHECK(h[2] - h[0] == Catch::Approx(8).margin(0.1));
    CHECK(viewEffort({part.shape}) < 600);
}
