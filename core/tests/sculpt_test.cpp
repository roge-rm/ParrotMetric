#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>

#include <cmath>
#include <memory>

#include "mesh/mesh.h"
#include "mesh/mesh_body.h"
#include "sculpt/sculpt.h"

using Catch::Matchers::WithinAbs;

namespace {

// Looking down -z from (0, 0, 100) at the origin, y up, on a 400 x 400 view; column-major.
void camera(float vp[16]) {
    float t = std::tan(0.4f), n = 1, f = 300;
    float proj[16] = {1 / t, 0, 0, 0, 0, 1 / t, 0, 0, 0, 0, -(f + n) / (f - n), -1, 0, 0, -2 * f * n / (f - n), 0};
    float view[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -100, 1};
    for (int c = 0; c < 4; ++c)
        for (int r = 0; r < 4; ++r) {
            float s = 0;
            for (int k = 0; k < 4; ++k) s += proj[k * 4 + r] * view[c * 4 + k];
            vp[c * 4 + r] = s;
        }
}

std::unique_ptr<pm::Sculpt> ball(float radius = 20, int levels = 4) {
    auto s = std::make_unique<pm::Sculpt>(pm::Sculpt::sphere(radius, levels));
    float vp[16];
    camera(vp);
    s->setCamera(vp, 400, 400);
    return s;
}

float highest(const pm::Sculpt& s) {
    float z = -1e9f;
    for (const auto& v : s.mesh().vertices) z = std::max(z, v[2]);
    return z;
}

// The point nearest p, as it is now.
std::array<float, 3> nearest(const pm::Sculpt& s, float x, float y, float z) {
    auto m = s.mesh();
    std::array<float, 3> best{};
    float d = 1e30f;
    for (const auto& v : m.vertices) {
        float e = (v[0] - x) * (v[0] - x) + (v[1] - y) * (v[1] - y) + (v[2] - z) * (v[2] - z);
        if (e < d) { d = e; best = v; }
    }
    return best;
}

void stroke(pm::Sculpt& s, pm::BrushSettings b, float x0, float y0, float x1, float y1) {
    REQUIRE(s.begin(x0, y0, 1, b));
    for (int i = 1; i <= 10; ++i) s.move(x0 + (x1 - x0) * float(i) / 10, y0 + (y1 - y0) * float(i) / 10, 1);
    s.end();
}

}  // namespace

TEST_CASE("A sphere to start from is closed") {
    auto m = pm::Sculpt::sphere(10, 3);
    CHECK(m.triangles.size() == 1280);
    CHECK(pm::openEdgeCount(m) == 0);
    CHECK_THAT(pm::volume(m), WithinAbs(4.0 / 3 * M_PI * 1000, 120));
}

TEST_CASE("Draw raises the surface, and undo puts it back") {
    auto ps = ball();
    auto& s = *ps;
    float before = highest(s);
    pm::BrushSettings b;
    b.dynamic = false;
    b.radius = 60;
    stroke(s, b, 200, 190, 200, 210);
    CHECK(highest(s) > before + 0.5f);
    CHECK(s.canUndo());
    REQUIRE(s.undo());
    CHECK_THAT(highest(s), WithinAbs(before, 1e-4));
    REQUIRE(s.redo());
    CHECK(highest(s) > before + 0.5f);
}

TEST_CASE("A stroke off the surface doesn't start") {
    auto ps = ball();
    auto& s = *ps;
    pm::BrushSettings b;
    CHECK_FALSE(s.hit(5, 5));
    CHECK(s.hit(200, 200));
    CHECK_FALSE(s.begin(5, 5, 1, b));
    CHECK_FALSE(s.canUndo());
}

TEST_CASE("Dynamic detail adds triangles and keeps the surface closed") {
    auto ps = ball(20, 3);
    auto& s = *ps;
    size_t before = s.triangleCount();
    pm::BrushSettings b;
    b.radius = 50;
    b.detail = 1;
    stroke(s, b, 170, 200, 230, 200);
    CHECK(s.triangleCount() > before);
    auto m = s.mesh();
    CHECK(pm::openEdgeCount(m) == 0);
    CHECK_NOTHROW(pm::MeshBody::fromMesh(m));
    // Undo takes the new triangles away again.
    REQUIRE(s.undo());
    CHECK(s.triangleCount() == before);
}

TEST_CASE("The mask holds what it covers") {
    auto ps = ball();
    auto& s = *ps;
    pm::BrushSettings mask;
    mask.brush = pm::Brush::Mask;
    mask.strength = 1;
    mask.radius = 80;
    mask.dynamic = false;
    for (int i = 0; i < 4; ++i) stroke(s, mask, 200, 195, 200, 205);
    float before = highest(s);
    pm::BrushSettings draw;
    draw.radius = 30;
    draw.dynamic = false;
    stroke(s, draw, 200, 198, 200, 202);
    CHECK_THAT(highest(s), WithinAbs(before, 0.05));
    s.invertMask();
    stroke(s, draw, 200, 198, 200, 202);
    CHECK(highest(s) > before + 0.06f);
}

TEST_CASE("Mirror across x does the same on the other side") {
    auto ps = ball();
    auto& s = *ps;
    pm::BrushSettings b;
    b.mirror = 1;
    b.dynamic = false;
    b.radius = 40;
    // Right of the middle on screen is +x.
    stroke(s, b, 280, 200, 280, 205);
    auto right = nearest(s, 12, 0, 16), left = nearest(s, -12, 0, 16);
    CHECK_THAT(right[0], WithinAbs(-left[0], 1e-3));
    CHECK_THAT(right[2], WithinAbs(left[2], 1e-3));
}

TEST_CASE("Grab drags the surface with the pointer") {
    auto ps = ball();
    auto& s = *ps;
    float before = nearest(s, 0, 0, 20)[1];
    pm::BrushSettings b;
    b.brush = pm::Brush::Grab;
    b.dynamic = false;
    b.radius = 40;
    // Up the screen is +y.
    stroke(s, b, 200, 200, 200, 160);
    auto top = nearest(s, 0, 8, 20);
    CHECK(top[1] > before + 3);
}

TEST_CASE("Even out gives triangles near the size asked for") {
    pm::Sculpt s(pm::Sculpt::sphere(20, 2));
    s.evenOut(1.5f);
    CHECK_THAT(s.averageEdge(), WithinAbs(1.5, 0.5));
    CHECK(pm::openEdgeCount(s.mesh()) == 0);
    REQUIRE(s.undo());
    CHECK(s.triangleCount() == 320);
}

TEST_CASE("A sculpted mesh packs and comes back the same") {
    auto m = pm::Sculpt::sphere(10, 2);
    auto bytes = pm::packMesh(m);
    CHECK(bytes.size() < m.vertices.size() * 12 + m.triangles.size() * 12);
    auto back = pm::unpackMesh(bytes);
    CHECK(back.vertices == m.vertices);
    CHECK(back.triangles == m.triangles);
    bytes[10] ^= 0xff;
    CHECK_THROWS(pm::unpackMesh(bytes));
}

TEST_CASE("A sculpted body's box takes in what was pulled out") {
    auto ps = ball();
    auto& s = *ps;
    pm::BrushSettings b;
    b.brush = pm::Brush::Grab;
    b.radius = 40;
    stroke(s, b, 200, 200, 200, 40);
    auto box = s.bounds();
    auto body = pm::MeshBody::fromMesh(s.mesh());
    auto bb = body.bounds();
    for (int k = 0; k < 6; ++k) CHECK_THAT(bb[size_t(k)], WithinAbs(box[size_t(k)], 1e-3));
    CHECK(box[4] > 25);
}

TEST_CASE("Remeshing leaves no empty slots to draw") {
    pm::Sculpt s(pm::Sculpt::sphere(20, 3));
    s.evenOut(1.0f);
    s.evenOut(2.0f);
    CHECK(s.triangles().size() == s.triangleCount());
    CHECK(pm::openEdgeCount(s.mesh()) == 0);
    REQUIRE(s.undo());
    CHECK(s.triangles().size() == s.triangleCount());
}
