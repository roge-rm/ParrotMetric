#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_string.hpp>

#include <BRepAdaptor_Curve.hxx>
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
