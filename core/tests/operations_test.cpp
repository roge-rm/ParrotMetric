#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_string.hpp>

#include <BRepGProp.hxx>
#include <GProp_GProps.hxx>

#include <algorithm>
#include <cmath>

#include "model/operations.h"

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

double volume(const NamedShape& s) {
    GProp_GProps props;
    BRepGProp::VolumeProperties(s.shape, props);
    return props.Mass();
}

bool has(const std::vector<std::string>& names, const std::string& n) {
    return std::find(names.begin(), names.end(), n) != names.end();
}

const gp_Ax3 top(gp::XOY());

}  // namespace

TEST_CASE("an extruded rectangle names its sides by the lines they came from") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    CHECK(volume(box) == Catch::Approx(8000));
    auto faces = box.faceNames();
    CHECK(faces.size() == 6);
    for (auto n : {"F1.s1", "F1.s2", "F1.s3", "F1.s4", "F1.start", "F1.end"}) CHECK(has(faces, n));
    CHECK(box.findEdges("F1.end|F1.s1").size() == 1);
}

TEST_CASE("a symmetric extrude goes both ways") {
    NamedShape box = extrude(1, top, rectangle(10, 10), {{{1, 2, 3, 4}, 5, 5}}, 5, 5);
    CHECK(volume(box) == Catch::Approx(1000));
}

TEST_CASE("a fillet finds its edge again after the sketch under it changes") {
    auto build = [](double width) {
        NamedShape box = extrude(1, top, rectangle(width, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
        return fillet(2, box, {"F1.end|F1.s1"}, 2);
    };
    NamedShape a = build(40);
    NamedShape b = build(60);
    // The same edge, rounded, in both: the box less a quarter of a 2 mm square, plus a quarter circle.
    const double lost = (4 - M_PI) * 1.0;
    CHECK(volume(a) == Catch::Approx(8000 - lost * 40).epsilon(1e-6));
    CHECK(volume(b) == Catch::Approx(12000 - lost * 60).epsilon(1e-6));
    CHECK(has(b.faceNames(), "F2.r(F1.end|F1.s1)"));
}

TEST_CASE("a cut keeps the names of what it cuts and names the hole by its circle") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    NamedShape tool = extrude(2, top, {circle(5, 20, 10, 4)}, {{{5}, 20, 10}}, 20, 5);
    NamedShape cut = combine(2, box, tool, Combine::Cut);
    CHECK(volume(cut) == Catch::Approx(8000 - M_PI * 16 * 10).epsilon(1e-6));
    auto faces = cut.faceNames();
    CHECK(has(faces, "F1.s1"));
    CHECK(has(faces, "F1.end"));
    CHECK(has(faces, "F2.s5"));
    // The rim of the hole on top can be rounded.
    NamedShape rounded = fillet(3, cut, {"F1.end|F2.s5"}, 1);
    CHECK(volume(rounded) < volume(cut));
}

TEST_CASE("a revolved rectangle makes a ring") {
    // A 2 x 10 rectangle, 8 to 10 mm from the y axis, turned all the way round it.
    std::vector<SketchCurve> r = {line(1, 8, 0, 10, 0), line(2, 10, 0, 10, 10), line(3, 10, 10, 8, 10), line(4, 8, 10, 8, 0)};
    NamedShape ring = revolve(1, top, r, {{{1, 2, 3, 4}, 9, 5}}, 0, 0, 0, 1, 2 * M_PI);
    CHECK(volume(ring) == Catch::Approx(M_PI * (100 - 64) * 10).epsilon(1e-6));
}

TEST_CASE("regions with the same curves round them are told apart by where they were picked") {
    std::vector<SketchCurve> c = {circle(1, 0, 0, 10), circle(2, 12, 0, 10)};
    NamedShape left = extrude(1, top, c, {{{1, 2}, -5, 0}}, 1, 0);
    NamedShape right = extrude(1, top, c, {{{1, 2}, 17, 0}}, 1, 0);
    GProp_GProps pl, pr;
    BRepGProp::VolumeProperties(left.shape, pl);
    BRepGProp::VolumeProperties(right.shape, pr);
    CHECK(pl.CentreOfMass().X() < 0);
    CHECK(pr.CentreOfMass().X() > 12);
}

TEST_CASE("a fillet too big fails with a reason") {
    NamedShape box = extrude(1, top, rectangle(10, 10), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    CHECK_THROWS_WITH(fillet(2, box, {"F1.end|F1.s1"}, 20), Catch::Matchers::Equals("The fillet doesn't fit"));
    CHECK_THROWS_WITH(fillet(2, box, {"F9.end|F9.s1"}, 1), Catch::Matchers::Equals("The edges to round aren't there any more"));
}
