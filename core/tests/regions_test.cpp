#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include <algorithm>
#include <cmath>
#include <fstream>
#include <iterator>

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

#include "sketch/text.h"

TEST_CASE("text outlines are capital letters the height asked for, and close into areas") {
    auto curves = textOutline("Ho", 10, false, 100);
    REQUIRE(!curves.empty());
    double top = -1e9, bottom = 1e9;
    for (const auto& c : curves) { top = std::max(top, std::max(c.y1, c.y2)); bottom = std::min(bottom, std::min(c.y1, c.y2)); }
    CHECK(top == Catch::Approx(10.0).margin(0.3));
    CHECK(bottom == Catch::Approx(0.0).margin(0.3));
    CHECK(curves.front().id == 100);
    // H is one area; o is a ring, so its outer area has a hole.
    auto regions = findRegions(curves);
    CHECK(regions.size() >= 2);
    auto bold = textOutline("H", 10, true, 0);
    double width = 0, boldWidth = 0;
    for (const auto& c : textOutline("H", 10, false, 0)) width = std::max(width, std::max(c.x1, c.x2));
    for (const auto& c : bold) boldWidth = std::max(boldWidth, std::max(c.x1, c.x2));
    CHECK(boldWidth > width);
    CHECK_THROWS(textOutline("H", 0, false, 0));
}

TEST_CASE("text can be in any of the built-in fonts or a font file") {
    auto widest = [](const std::vector<SketchCurve>& curves) {
        double w = 0;
        for (const auto& c : curves) w = std::max(w, std::max(c.x1, c.x2));
        return w;
    };
    // Monospaced: as wide whatever the letters.
    CHECK(widest(textOutline("iiiiW", 10, false, 0, TextFont::Mono)) == Catch::Approx(widest(textOutline("WWWWW", 10, false, 0, TextFont::Mono))).epsilon(0.08));
    CHECK(widest(textOutline("iiiiW", 10, false, 0, TextFont::Sans)) < 0.7 * widest(textOutline("WWWWW", 10, false, 0, TextFont::Sans)));
    for (auto f : {TextFont::Serif, TextFont::Rounded}) CHECK(!textOutline("Ag", 10, true, 0, f).empty());
    // A font file read in gives the same as the built-in font it is.
    std::ifstream in(std::string(PM_SOURCE_DIR) + "/../third_party/fonts/Quicksand-Regular.ttf", std::ios::binary);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    REQUIRE(file.size() > 1000);
    CHECK(widest(textOutline("Hello", 10, false, 0, TextFont::Sans, &file)) == Catch::Approx(widest(textOutline("Hello", 10, false, 0, TextFont::Rounded))));
    std::vector<uint8_t> junk(500, 7);
    CHECK_THROWS(textOutline("H", 10, false, 0, TextFont::Sans, &junk));
}

TEST_CASE("an open arc with lines off its ends has no area") {
    std::vector<SketchCurve> curves;
    SketchCurve arc;
    arc.kind = SketchCurve::Arc; arc.id = 4; arc.x1 = 0; arc.y1 = 16.6242; arc.r = 6;
    arc.a0 = std::atan2(11.8598 - 16.6242, 3.6470); arc.a1 = std::atan2(11.8598 - 16.6242, -3.6470);
    curves.push_back(arc);
    SketchCurve a; a.kind = SketchCurve::Line; a.id = 6; a.x1 = 3.6470; a.y1 = 11.8598; a.x2 = 14.1733; a.y2 = 11.8598;
    SketchCurve b; b.kind = SketchCurve::Line; b.id = 9; b.x1 = -3.6470; b.y1 = 11.8598; b.x2 = -14.1733; b.y2 = 11.8598;
    curves.push_back(a);
    curves.push_back(b);
    CHECK(findRegions(curves).empty());
    curves.resize(1);
    CHECK(findRegions(curves).empty());
}
