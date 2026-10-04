#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include <cmath>

#include "sketch/regions.h"

using namespace pm;

namespace {

SketchCurve line(int id, double x1, double y1, double x2, double y2) {
    SketchCurve c;
    c.kind = SketchCurve::Line; c.id = id; c.x1 = x1; c.y1 = y1; c.x2 = x2; c.y2 = y2;
    return c;
}

SketchCurve circle(int id, double x, double y, double r) {
    SketchCurve c;
    c.kind = SketchCurve::Circle; c.id = id; c.x1 = x; c.y1 = y; c.r = r;
    return c;
}

std::vector<SketchCurve> rectangle(double w, double h) {
    return {line(1, 0, 0, w, 0), line(2, w, 0, w, h), line(3, w, h, 0, h), line(4, 0, h, 0, 0)};
}

}  // namespace

TEST_CASE("a rectangle is one region bounded by its four sides") {
    auto regions = findRegions(rectangle(40, 20));
    REQUIRE(regions.size() == 1);
    CHECK(regions[0].area == Catch::Approx(800));
    CHECK(regions[0].curveIds == std::vector<int>{1, 2, 3, 4});
    CHECK(regions[0].loops.size() == 1);
    CHECK(regions[0].loops[0].size() >= 4);
}

TEST_CASE("a circle inside a rectangle makes a plate with a hole, and the disc") {
    auto curves = rectangle(40, 20);
    curves.push_back(circle(5, 20, 10, 5));
    auto regions = findRegions(curves);
    REQUIRE(regions.size() == 2);
    CHECK(regions[0].area == Catch::Approx(800 - M_PI * 25).epsilon(1e-4));
    CHECK(regions[0].loops.size() == 2);
    CHECK(regions[1].area == Catch::Approx(M_PI * 25).epsilon(1e-4));
    CHECK(regions[1].curveIds == std::vector<int>{5});
}

TEST_CASE("two overlapping circles make three regions") {
    auto regions = findRegions({circle(1, 0, 0, 10), circle(2, 12, 0, 10)});
    REQUIRE(regions.size() == 3);
    double total = 0;
    for (const auto& r : regions) total += r.area;
    // Two discs less the overlap they share.
    double d = 12, r = 10;
    double lens = 2 * r * r * std::acos(d / (2 * r)) - d / 2 * std::sqrt(4 * r * r - d * d);
    CHECK(total == Catch::Approx(2 * M_PI * r * r - lens).epsilon(1e-4));
}

TEST_CASE("lines that don't close make no region") {
    CHECK(findRegions({line(1, 0, 0, 10, 0), line(2, 10, 0, 10, 10)}).empty());
}

TEST_CASE("a spline closing a shape with a line makes a region") {
    // A curve over the top from (0,0) to (10,0), and a line back underneath.
    SketchCurve b;
    b.kind = SketchCurve::Bezier; b.id = 1;
    b.x1 = 0; b.y1 = 0; b.cx1 = 0; b.cy1 = 8; b.cx2 = 10; b.cy2 = 8; b.x2 = 10; b.y2 = 0;
    auto regions = findRegions({b, line(2, 10, 0, 0, 0)});
    REQUIRE(regions.size() == 1);
    // The area under it: the integral of y dx, 1440 times the integral of t²(1-t)², so 48.
    CHECK(regions[0].area == Catch::Approx(48).epsilon(1e-3));
}
