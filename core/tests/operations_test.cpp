#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_string.hpp>

#include <BRepAdaptor_Curve.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepGProp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <GProp_GProps.hxx>

#include <algorithm>
#include <cmath>

#include "model/operations.h"
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

TEST_CASE("a shell leaves walls round an open top") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    NamedShape cup = shell(2, box, {"F1.end"}, 2);
    // The box less the hollow: 36 x 16 x 8 taken out.
    CHECK(volume(cup) == Catch::Approx(8000 - 36 * 16 * 8).epsilon(1e-4));
    CHECK(has(cup.faceNames(), "F1.s1"));
}

TEST_CASE("areas extruded side by side make one end face, not one per area") {
    // Two rectangles sharing a side, 20 x 10 each.
    std::vector<SketchCurve> c = {line(1, 0, 0, 20, 0), line(2, 20, 0, 20, 10), line(3, 20, 10, 0, 10), line(4, 0, 10, 0, 0),
                                  line(5, 20, 0, 40, 0), line(6, 40, 0, 40, 10), line(7, 40, 10, 20, 10)};
    NamedShape block = extrude(1, top, c, {{{1, 2, 3, 4}, 10, 5}, {{2, 5, 6, 7}, 30, 5}}, 5, 0);
    CHECK(volume(block) == Catch::Approx(40 * 10 * 5));
    auto names = block.faceNames();
    CHECK(std::count(names.begin(), names.end(), "F1.end") == 1);
    CHECK(std::count(names.begin(), names.end(), "F1.start") == 1);
    // The front and back are still a side for each line: nothing a later step names goes away.
    CHECK(names.size() == 8);
    CHECK(std::count(names.begin(), names.end(), "F1.s5") == 1);
}

TEST_CASE("a shell names each inside face after the face it lines") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    NamedShape cup = shell(2, box, {"F1.end"}, 2);
    auto names = cup.faceNames();
    std::string all;
    for (const auto& n : names) all += n + " ";
    INFO(all);
    CHECK(has(names, "F2.in(F1.start)"));
    CHECK(has(names, "F2.in(F1.s1)"));
    // The floor inside is flat and 2 up from the bottom.
    gp_Ax3 floor = facePlane(cup, "F2.in(F1.start)");
    CHECK(std::abs(floor.Location().Z()) == Catch::Approx(2));
}

TEST_CASE("a shell keeps the inside corner of an L sharp") {
    // An L, 40 by 30 with arms 15 wide, 20 high and open at the top.
    std::vector<SketchCurve> l = {line(1, 0, 0, 40, 0), line(2, 40, 0, 40, 15), line(3, 40, 15, 15, 15),
                                  line(4, 15, 15, 15, 30), line(5, 15, 30, 0, 30), line(6, 0, 30, 0, 0)};
    NamedShape block = extrude(1, top, l, {{{1, 2, 3, 4, 5, 6}, 5, 5}}, 20, 0);
    NamedShape cup = shell(2, block, {"F1.end"}, 2);
    // The inside is the L 2 in from each side, 18 deep, with a square corner: 36 x 11 + 11 x 15 = 561 mm2.
    CHECK(volume(cup) == Catch::Approx(volume(block) - 561 * 18).epsilon(1e-4));
}

TEST_CASE("a draft tilts the sides") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    NamedShape tilted = draft(2, box, {"F1.s1", "F1.s2", "F1.s3", "F1.s4"}, "F1.start", 5 * M_PI / 180);
    CHECK(volume(tilted) < volume(box));
    CHECK(volume(tilted) > volume(box) * 0.8);
}

TEST_CASE("a mirrored copy keeps the old names inside its own") {
    NamedShape box = extrude(1, top, rectangle(10, 10), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    // Mirror across the YZ plane: x becomes -x.
    const double m[12] = {-1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    NamedShape copy = transformed(2, box, m, "m");
    CHECK(volume(copy) == Catch::Approx(1000));
    CHECK(has(copy.faceNames(), "F2.m(F1.end)"));
    GProp_GProps p;
    BRepGProp::VolumeProperties(copy.shape, p);
    CHECK(p.CentreOfMass().X() == Catch::Approx(-5));
}

TEST_CASE("a plane through a body splits it in two") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    auto pieces = split(2, box, gp_Pnt(10, 0, 0), gp_Dir(1, 0, 0));
    REQUIRE(pieces.size() == 2);
    double a = volume(pieces[0]), b = volume(pieces[1]);
    CHECK(std::min(a, b) == Catch::Approx(2000));
    CHECK(std::max(a, b) == Catch::Approx(6000));
    CHECK_THROWS(split(2, box, gp_Pnt(100, 0, 0), gp_Dir(1, 0, 0)));
}

TEST_CASE("holes go down from the top face") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    gp_Ax3 onTop(gp_Pnt(0, 0, 10), gp::DZ(), gp::DX());
    NamedShape through = holeTool(2, onTop, {{10, 10}, {30, 10}}, 4, 0, HoleKind::Simple, 0, 0);
    NamedShape drilled = combine(2, box, through, Combine::Cut);
    CHECK(volume(drilled) == Catch::Approx(8000 - 2 * M_PI * 4 * 10).epsilon(1e-4));
    NamedShape bored = holeTool(3, onTop, {{20, 10}}, 4, 6, HoleKind::Counterbore, 8, 2);
    NamedShape withBore = combine(3, box, bored, Combine::Cut);
    CHECK(volume(withBore) == Catch::Approx(8000 - M_PI * 16 * 2 - M_PI * 4 * 4).epsilon(1e-3));
}

TEST_CASE("an edge whose name went away is found again by its shape") {
    NamedShape before = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    auto edge = signatureOf(before, "F1.end|F1.s1", true);
    REQUIRE(edge.size() == 10);
    auto face = signatureOf(before, "F1.s2", false);
    REQUIRE(face.size() == 10);
    CHECK(signatureOf(before, "F1.s9", false).empty());

    // The same box, a little longer, with its first line redrawn as line 9.
    auto curves = rectangle(42, 20);
    curves[0].id = 9;
    NamedShape after = extrude(1, top, curves, {{{9, 2, 3, 4}, 5, 5}}, 10, 0);
    CHECK(after.findEdges("F1.end|F1.s1").empty());
    CHECK(relocate(after, edge) == "F1.end|F1.s9");
    CHECK(relocate(after, face) == "F1.s2");

    // Nothing near enough: a much smaller box far away.
    NamedShape other = extrude(1, top, rectangle(2, 2), {{{1, 2, 3, 4}, 1, 1}}, 1, 0);
    auto far = signatureOf(after, "F1.s3", false);
    for (auto& v : {2, 3, 4}) far[size_t(v)] += 500;
    CHECK(relocate(other, far).empty());
}

TEST_CASE("a tapered extrude narrows going forward") {
    NamedShape box = extrude(1, top, rectangle(20, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0, 10 * M_PI / 180);
    // Each side leans in by 10 tan 10° at the top: a frustum of a square pyramid.
    double t = 10 * std::tan(10 * M_PI / 180);
    double a = 20 * 20, b = (20 - 2 * t) * (20 - 2 * t);
    CHECK(volume(box) == Catch::Approx(10.0 / 3 * (a + b + std::sqrt(a * b))).epsilon(1e-3));
    CHECK(has(box.faceNames(), "F1.s1"));
}

TEST_CASE("a chamfer can take two distances, or a distance and an angle") {
    NamedShape box = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    NamedShape two = chamfer(2, box, {"F1.end|F1.s1"}, 1, ChamferKind::TwoDistances, 2);
    CHECK(volume(two) == Catch::Approx(8000 - 0.5 * 1 * 2 * 40));
    NamedShape angled = chamfer(2, box, {"F1.end|F1.s1"}, 2, ChamferKind::DistanceAngle, M_PI / 4);
    CHECK(volume(angled) == Catch::Approx(8000 - 0.5 * 2 * 2 * 40));
    CHECK_THROWS(chamfer(2, box, {"F1.end|F1.s1"}, 2, ChamferKind::DistanceAngle, M_PI / 2));
}

TEST_CASE("a solid can be scaled unevenly") {
    NamedShape box = extrude(1, top, rectangle(10, 10), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    const double m[12] = {2, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0.5, 0};
    NamedShape out = transformed(2, box, m, "s");
    CHECK(volume(out) == Catch::Approx(1000));
    CHECK(has(out.faceNames(), "F2.s(F1.end)"));
}

TEST_CASE("a solid scaled unevenly about its middle") {
    std::vector<SketchCurve> curves = {line(1, 14, -4.5, 26, -4.5), line(2, 26, -4.5, 26, 4.5), line(3, 26, 4.5, 14, 4.5), line(4, 14, 4.5, 14, -4.5)};
    NamedShape box = extrude(1, top, curves, {{{1, 2, 3, 4}, 20, 0}}, 10, 0);
    // Three times as tall about its middle, (20, 0, 5).
    const double m[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 3, -10};
    NamedShape out = transformed(2, box, m, "t");
    CHECK(volume(out) == Catch::Approx(12 * 9 * 30));
}

TEST_CASE("uneven scaling works on rounded, bevelled, tapered and curved solids") {
    const double m[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 3, -10};  // Three times as tall about z = 5.
    NamedShape box = extrude(1, top, rectangle(20, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    std::vector<std::pair<const char*, NamedShape>> shapes = {
        {"chamfered", chamfer(2, box, {"F1.end|F1.s1"}, 2)},
        {"two-distance chamfer", chamfer(2, box, {"F1.end|F1.s1", "F1.end|F1.s2"}, 1, ChamferKind::TwoDistances, 3)},
        {"filleted", fillet(2, box, {"F1.end|F1.s1", "F1.end|F1.s2"}, 3)},
        {"tapered", extrude(1, top, rectangle(20, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0, 0.1)},
        {"cylinder", extrude(1, top, {circle(1, 0, 0, 8)}, {{{1}, 0, 0}}, 10, 0)},
        {"with a hole", combine(3, box, extrude(2, top, {circle(1, 10, 10, 4)}, {{{1}, 10, 10}}, 10, 0), Combine::Cut)},
    };
    for (auto& [what, shape] : shapes) {
        INFO(what);
        NamedShape out = transformed(9, shape, m, "s");
        // Measured closely: the scaled shape's curved faces are splines.
        GProp_GProps props;
        BRepGProp::VolumeProperties(out.shape, props, 1e-7);
        CHECK(props.Mass() == Catch::Approx(3 * volume(shape)).epsilon(1e-3));
    }
}

TEST_CASE("scaling unevenly keeps flat faces flat") {
    NamedShape box = extrude(1, top, rectangle(20, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    const double m[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 3, -10};
    NamedShape out = transformed(9, box, m, "s");
    int flat = 0, faces = 0;
    for (TopExp_Explorer f(out.shape, TopAbs_FACE); f.More(); f.Next()) {
        faces++;
        if (BRepAdaptor_Surface(TopoDS::Face(f.Current())).GetType() == GeomAbs_Plane) flat++;
    }
    CHECK(faces == 6);
    CHECK(flat == 6);
    int straight = 0;
    for (TopExp_Explorer e(out.shape, TopAbs_EDGE); e.More(); e.Next())
        if (BRepAdaptor_Curve(TopoDS::Edge(e.Current())).GetType() == GeomAbs_Line) straight++;
    CHECK(straight == 24);  // Each of the 12 edges, seen from both its faces.

    // What comes after still works: a fillet on it, and a sketch on its top.
    NamedShape rounded = fillet(10, out, {"F9.s(F1.end)|F9.s(F1.s1)"}, 2);
    CHECK(volume(rounded) < volume(out));
    gp_Ax3 onTop = facePlane(out, "F9.s(F1.end)");
    CHECK(onTop.Location().Z() == Catch::Approx(20));
}

TEST_CASE("a line poking into a rectangle doesn't stop it extruding") {
    auto curves = rectangle(40, 20);
    curves.push_back(line(5, 20, -10, 20, 10));
    auto regions = findRegions(curves);
    REQUIRE(regions.size() == 1);
    NamedShape body = extrude(1, top, curves, {{regions[0].curveIds, 30, 15}}, 10, 0);
    CHECK(volume(body) == Catch::Approx(8000));
}

TEST_CASE("primitives have the sizes asked for and stand on their plane") {
    const double pi = 3.14159265358979;
    CHECK(volume(primitive(1, top, Primitive::Box, 0, 0, 10, 20, 30)) == Catch::Approx(6000));
    CHECK(volume(primitive(1, top, Primitive::Cylinder, 0, 0, 10, 20, 0)) == Catch::Approx(pi * 25 * 20));
    CHECK(volume(primitive(1, top, Primitive::Sphere, 0, 0, 10, 0, 0)) == Catch::Approx(4.0 / 3 * pi * 125).epsilon(1e-4));
    CHECK(volume(primitive(1, top, Primitive::Torus, 0, 0, 40, 10, 0)) == Catch::Approx(2 * pi * pi * 20 * 25).epsilon(1e-4));
    CHECK(volume(primitive(1, top, Primitive::Cone, 0, 0, 10, 0, 12)) == Catch::Approx(pi * 25 * 12 / 3).epsilon(1e-4));

    NamedShape box = primitive(3, top, Primitive::Box, 5, 7, 10, 20, 30);
    auto b = bounds(box);
    CHECK(b[0] == Catch::Approx(0).margin(1e-6));
    CHECK(b[1] == Catch::Approx(-3).margin(1e-6));
    CHECK(b[2] == Catch::Approx(0).margin(1e-6));
    CHECK(b[5] == Catch::Approx(30).margin(1e-6));
    auto names = box.faceNames();
    std::sort(names.begin(), names.end());
    CHECK(names == std::vector<std::string>{"F3.end", "F3.start", "F3.x0", "F3.x1", "F3.y0", "F3.y1"});
    CHECK_THROWS(primitive(1, top, Primitive::Torus, 0, 0, 10, 10, 0));
}

TEST_CASE("a thin extrude keeps only a wall inside the sketch's edges") {
    NamedShape wall = extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0, 0, 2);
    CHECK(volume(wall) == Catch::Approx((800 - 36 * 16) * 10));
    // The outside keeps its names.
    auto names = wall.faceNames();
    CHECK(std::count(names.begin(), names.end(), "F1.s1") == 1);
    CHECK_THROWS(extrude(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0, 0, 15));
}

TEST_CASE("a body split by another comes apart where they cross") {
    NamedShape block = primitive(1, top, Primitive::Box, 0, 0, 20, 20, 10);
    NamedShape rod = primitive(2, top, Primitive::Cylinder, 0, 0, 10, 30, 0);
    auto pieces = splitBy(3, block, rod);
    REQUIRE(pieces.size() == 2);
    double total = volume(pieces[0]) + volume(pieces[1]);
    CHECK(total == Catch::Approx(4000));
    const double pi = 3.14159265358979;
    double inside = std::min(volume(pieces[0]), volume(pieces[1]));
    CHECK(inside == Catch::Approx(pi * 25 * 10).epsilon(1e-4));
    CHECK(overlapVolume(block, rod) == Catch::Approx(pi * 25 * 10).epsilon(1e-4));
    CHECK(overlapVolume(block, primitive(4, top, Primitive::Box, 100, 0, 5, 5, 5)) == 0);
}

TEST_CASE("solids overlap when they share volume or a face, not just an edge") {
    NamedShape block = primitive(1, top, Primitive::Box, 0, 0, 20, 20, 10);
    CHECK(overlaps(block, primitive(2, top, Primitive::Box, 10, 0, 20, 20, 10)));
    // Side by side, sharing a face: a join makes one solid.
    CHECK(overlaps(block, primitive(3, top, Primitive::Box, 20, 0, 20, 20, 10)));
    // Only an edge in common, or a gap.
    CHECK_FALSE(overlaps(block, primitive(4, top, Primitive::Box, 20, 20, 20, 20, 10)));
    CHECK_FALSE(overlaps(block, primitive(5, top, Primitive::Box, 21, 0, 20, 20, 10)));
}

TEST_CASE("each corner of a box has a name it can be found by") {
    NamedShape box = primitive(1, top, Primitive::Box, 0, 0, 10, 20, 30);
    auto names = box.cornerNames();
    REQUIRE(names.size() == 8);
    for (const auto& n : names) {
        auto p = box.findCorner(n);
        REQUIRE(p.has_value());
        CHECK(std::abs(std::abs(p->X()) - 5) < 1e-9);
        CHECK(std::abs(std::abs(p->Y()) - 10) < 1e-9);
    }
    std::vector<std::string> sorted = names;
    std::sort(sorted.begin(), sorted.end());
    CHECK(std::unique(sorted.begin(), sorted.end()) == sorted.end());
}

namespace {
TopoDS_Edge straight(double x0, double y0, double z0, double x1, double y1, double z1) {
    return BRepBuilderAPI_MakeEdge(gp_Pnt(x0, y0, z0), gp_Pnt(x1, y1, z1));
}
}  // namespace

TEST_CASE("a sweep along a straight path is a prism") {
    auto path = pathFromEdges({straight(5, 5, 0, 5, 5, 30)});
    NamedShape s = sweep(1, top, rectangle(10, 10), {{{1, 2, 3, 4}, 5, 5}}, path);
    CHECK(volume(s) == Catch::Approx(3000).epsilon(1e-6));
    auto names = s.faceNames();
    CHECK(std::count(names.begin(), names.end(), "F1.s1") == 1);
    CHECK(std::count(names.begin(), names.end(), "F1.end") == 1);
}

TEST_CASE("paths have to be one chain") {
    CHECK_THROWS(pathFromEdges({straight(0, 0, 0, 10, 0, 0), straight(20, 0, 0, 30, 0, 0)}));
    // Out of order is fine.
    CHECK_NOTHROW(pathFromEdges({straight(10, 0, 0, 10, 10, 0), straight(0, 0, 0, 10, 0, 0)}));
}

TEST_CASE("pipes, solid and hollow") {
    const double pi = 3.14159265358979;
    auto path = pathFromEdges({straight(0, 0, 0, 0, 0, 50)});
    CHECK(volume(pipe(1, path, 10, 0)) == Catch::Approx(pi * 25 * 50).epsilon(1e-6));
    CHECK(volume(pipe(1, path, 10, 6)) == Catch::Approx(pi * 16 * 50).epsilon(1e-6));
    // Round a corner.
    auto bent = pathFromEdges({straight(0, 0, 0, 0, 0, 30), straight(0, 0, 30, 30, 0, 30)});
    CHECK_NOTHROW(pipe(1, bent, 4, 0));
}

TEST_CASE("a coil's volume is its wire's section times its length") {
    const double pi = 3.14159265358979;
    NamedShape c = coil(1, top, 0, 0, 20, 5, 3, 2, false);
    double length = 3 * std::hypot(pi * 20, 5);
    CHECK(volume(c) == Catch::Approx(pi * 1 * length).epsilon(0.02));
    CHECK(volume(coil(1, top, 0, 0, 20, 5, 2, 2, true)) == Catch::Approx(4 * 2 * std::hypot(pi * 20, 5)).epsilon(0.02));
    CHECK_THROWS(coil(1, top, 0, 0, 20, 1, 3, 2, false));
}

TEST_CASE("threads cut into a shaft and into a hole") {
    NamedShape shaft = primitive(1, top, Primitive::Cylinder, 0, 0, 10, 20, 0);
    double before = volume(shaft);
    NamedShape threaded = thread(2, shaft, "F1.side", 1.5);
    double after = volume(threaded);
    CHECK(after < before * 0.95);
    CHECK(after > before * 0.75);
    auto names = threaded.faceNames();
    CHECK(std::any_of(names.begin(), names.end(), [](const std::string& n) { return n.rfind("F2.t", 0) == 0; }));

    NamedShape block = combine(5, primitive(3, top, Primitive::Box, 0, 0, 20, 20, 10), primitive(4, top, Primitive::Cylinder, 0, 0, 8, 10, 0), Combine::Cut);
    double solid = volume(block);
    NamedShape tapped = thread(6, block, "F4.side", 1.25);
    CHECK(volume(tapped) < solid);
    CHECK(volume(tapped) > solid * 0.9);
    CHECK_THROWS(thread(7, block, "F3.end", 1.25));
}

TEST_CASE("a loft between two squares is a box") {
    gp_Ax3 up(gp_Pnt(0, 0, 20), gp::DZ(), gp::DX());
    LoftProfile low{top, rectangle(10, 10), {{1, 2, 3, 4}, 5, 5}};
    LoftProfile high{up, rectangle(10, 10), {{1, 2, 3, 4}, 5, 5}};
    NamedShape box = loft(1, {low, high}, true);
    CHECK(volume(box) == Catch::Approx(2000).epsilon(1e-6));
    auto names = box.faceNames();
    CHECK(std::count(names.begin(), names.end(), "F1.start") == 1);
    CHECK(std::count(names.begin(), names.end(), "F1.s2") == 1);
    CHECK_THROWS(loft(1, {low}, false));
}

TEST_CASE("pressing and pulling a flat face keeps its name") {
    NamedShape box = primitive(1, top, Primitive::Box, 0, 0, 10, 20, 30);
    NamedShape pulled = offsetFaces(2, box, {"F1.end"}, 5);
    CHECK(volume(pulled) == Catch::Approx(7000).epsilon(1e-6));
    auto names = pulled.faceNames();
    CHECK(std::count(names.begin(), names.end(), "F1.end") == 1);
    NamedShape pushed = offsetFaces(3, box, {"F1.end"}, -5);
    CHECK(volume(pushed) == Catch::Approx(5000).epsilon(1e-6));
    names = pushed.faceNames();
    CHECK(std::count(names.begin(), names.end(), "F1.end") == 1);
    CHECK_THROWS(offsetFaces(4, box, {"F1.end"}, 0));
}

TEST_CASE("deleting a hole's faces fills it in") {
    NamedShape box = primitive(1, top, Primitive::Box, 0, 0, 20, 20, 10);
    NamedShape drilled = combine(3, box, primitive(2, top, Primitive::Cylinder, 0, 0, 6, 10, 0), Combine::Cut);
    REQUIRE(volume(drilled) < 4000);
    NamedShape filled = deleteFaces(4, drilled, {"F2.side"});
    CHECK(volume(filled) == Catch::Approx(4000).epsilon(1e-6));
}

TEST_CASE("variable and chord fillets") {
    const double pi = 3.14159265358979;
    NamedShape box = primitive(1, top, Primitive::Box, 0, 0, 20, 20, 20);
    std::string edge;
    for (const auto& e : box.edgeNames()) if (e.find("F1.end") != std::string::npos) { edge = e; break; }
    REQUIRE(!edge.empty());
    NamedShape varied = fillet(2, box, {edge}, FilletKind::Variable, 1, 3);
    CHECK(volume(varied) < 8000);
    CHECK(volume(varied) > 8000 - (9 - pi * 9 / 4) * 20);
    // A square edge's chord of 2 is a radius of 2 / (2 sin 45°).
    NamedShape chord = fillet(3, box, {edge}, FilletKind::Chord, 2, 0);
    double r = 2 / (2 * std::sin(pi / 4));
    CHECK(volume(chord) == Catch::Approx(8000 - (r * r - pi * r * r / 4) * 20).epsilon(1e-6));
}

TEST_CASE("places along a path, spread evenly or spaced") {
    auto path = pathFromEdges({straight(0, 0, 0, 100, 0, 0)});
    auto even = pathPlaces(path, 5, 0, false);
    REQUIRE(even.size() == 5);
    CHECK(even[0][3] == Catch::Approx(0).margin(1e-9));
    CHECK(even[2][3] == Catch::Approx(50));
    CHECK(even[4][3] == Catch::Approx(100));
    auto spaced = pathPlaces(path, 3, 10, false);
    CHECK(spaced[2][3] == Catch::Approx(20));
    CHECK_THROWS(pathPlaces(path, 3, 60, false));
    // From the far end back.
    auto back = pathPlaces(path, 3, 10, false, true);
    CHECK(back[0][3] == Catch::Approx(0).margin(1e-9));
    CHECK(back[2][3] == Catch::Approx(-20));
    // Round a corner, turned to follow: the last copy faces up the second leg.
    auto bent = pathFromEdges({straight(0, 0, 0, 10, 0, 0), straight(10, 0, 0, 10, 10, 0)});
    auto turned = pathPlaces(bent, 2, 0, true);
    CHECK(turned[1][0] == Catch::Approx(0).margin(1e-9));  // x goes to y: cos 90°
    CHECK(turned[1][4] == Catch::Approx(1));
}

TEST_CASE("a rib grows down to the body and a web out of its plane") {
    NamedShape box = extrude(1, top, rectangle(40, 40), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    // Standing across the middle of the box, sketch y pointing down: the line is 20 above the top.
    gp_Ax3 across(gp_Pnt(0, 20, 0), gp_Dir(0, 1, 0), gp_Dir(1, 0, 0));
    std::vector<SketchCurve> above = {line(1, 5, -30, 35, -30)};
    NamedShape ribbed = rib(2, box, across, above, 2, false, false);
    CHECK(volume(ribbed) == Catch::Approx(16000 + 2 * 30 * 20).epsilon(1e-6));
    CHECK(has(ribbed.faceNames(), "F2.w0"));
    CHECK_THROWS_WITH(rib(2, box, across, above, 2, true, false), "It doesn't meet the body that way");
    // A web from a plane 30 up, facing down, grows to the top face.
    gp_Ax3 high(gp_Pnt(0, 0, 30), gp_Dir(0, 0, -1), gp_Dir(1, 0, 0));
    NamedShape webbed = rib(3, box, high, {line(1, 5, -20, 35, -20)}, 2, false, true);
    CHECK(volume(webbed) == Catch::Approx(16000 + 30 * 2 * 20).epsilon(1e-4));
}

TEST_CASE("surfaces: patch, stitch, thicken and trim") {
    auto area = [](const NamedShape& s) {
        GProp_GProps props;
        BRepGProp::SurfaceProperties(s.shape, props);
        return props.Mass();
    };
    NamedShape sheet = patch(1, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}});
    CHECK(area(sheet) == Catch::Approx(800));
    CHECK(has(sheet.faceNames(), "F1.a0"));
    CHECK(volume(thicken(2, sheet, 2, false)) == Catch::Approx(1600));
    CHECK(volume(thicken(2, sheet, 2, true)) == Catch::Approx(1600));
    // Cut across the middle, the sheet is two.
    CHECK(split(3, sheet, gp_Pnt(20, 0, 0), gp_Dir(1, 0, 0)).size() == 2);

    // A box's top filled from its edges, and its faces sewn back into a solid.
    NamedShape box = extrude(4, top, rectangle(40, 20), {{{1, 2, 3, 4}, 5, 5}}, 10, 0);
    std::vector<std::string> rim;
    for (const auto& e : box.edgeNames()) if (e.find("F4.end") != std::string::npos) rim.push_back(e);
    REQUIRE(rim.size() == 4);
    CHECK(area(patchEdges(5, box, rim)) == Catch::Approx(800).epsilon(1e-3));
    std::vector<NamedShape> faces;
    for (TopExp_Explorer f(box.shape, TopAbs_FACE); f.More(); f.Next()) {
        NamedShape one;
        one.shape = f.Current();
        one.names.Bind(f.Current(), box.names.Find(f.Current()));
        faces.push_back(one);
    }
    NamedShape sewn = stitch(6, faces);
    CHECK(volume(sewn) == Catch::Approx(8000));
    CHECK(has(sewn.faceNames(), "F4.end"));
}

TEST_CASE("areas are raised from and sunk into a round face") {
    NamedShape rod = extrude(1, top, {circle(1, 0, 0, 10)}, {{{1}, 0, 0}}, 30, 0);
    std::string side;
    for (const auto& f : rod.faceNames()) if (f != "F1.start" && f != "F1.end") side = f;
    // Looking at the rod from in front, a 4 by 4 square halfway up.
    gp_Ax3 front(gp_Pnt(0, -20, 0), gp_Dir(0, 1, 0), gp_Dir(1, 0, 0));
    std::vector<SketchCurve> square = {line(1, -2, -13, 2, -13), line(2, 2, -13, 2, -17), line(3, 2, -17, -2, -17), line(4, -2, -17, -2, -13)};
    const double before = volume(rod);
    NamedShape raised = emboss(2, rod, side, front, square, {{{1, 2, 3, 4}, 0, -15}}, 1, false);
    // Close to 4 x 4 x 1, a little more out where the face curves away.
    CHECK(volume(raised) - before > 15);
    CHECK(volume(raised) - before < 18);
    NamedShape sunk = emboss(3, rod, side, front, square, {{{1, 2, 3, 4}, 0, -15}}, 1, true);
    CHECK(before - volume(sunk) > 14);
    CHECK(before - volume(sunk) < 17);
    CHECK_THROWS(emboss(4, rod, "F1.end", front, square, {{{1, 2, 3, 4}, 0, -15}}, 1, false));
}
