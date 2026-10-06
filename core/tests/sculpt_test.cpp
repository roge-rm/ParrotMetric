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

TEST_CASE("Evening out a block keeps its edges sharp") {
    pm::Mesh box = pm::MeshBody::box(20, 20, 20).toMesh();
    pm::Sculpt s(box);
    s.evenOut(1.0f);
    auto b = s.bounds();
    // Still a 20 mm block, corners and all.
    for (int k = 0; k < 3; ++k) CHECK_THAT(b[size_t(k) + 3] - b[size_t(k)], WithinAbs(20, 0.05));
    CHECK_THAT(pm::volume(s.mesh()), WithinAbs(8000, 40));
}

TEST_CASE("Mirror goes through the centre it's given") {
    pm::Mesh m = pm::Sculpt::sphere(20, 4);
    for (auto& v : m.vertices) v[0] += 30;
    pm::Sculpt s(m);
    float vp[16];
    camera(vp);
    s.setCamera(vp, 400, 400);
    s.setMirrorCentre(30, 0, 0);
    pm::BrushSettings b;
    b.mirror = 1;
    b.dynamic = false;
    b.radius = 30;
    // The ball's middle is at x 30, right of the view's middle; stroke to its right.
    REQUIRE(s.begin(330, 200, 1, b));
    s.move(330, 206, 1);
    s.end();
    auto right = nearest(s, 42, 0, 15), left = nearest(s, 18, 0, 15);
    CHECK_THAT(right[0] - 30, WithinAbs(30 - left[0], 1e-3));
    CHECK_THAT(right[2], WithinAbs(left[2], 1e-3));
}

TEST_CASE("Pull stretches the surface as far as the pointer goes") {
    auto ps = ball();
    auto& s = *ps;
    pm::BrushSettings b;
    b.brush = pm::Brush::Pull;
    b.radius = 30;
    // A hundred pixels up the screen is about 17 mm at the ball's front.
    REQUIRE(s.begin(200, 200, 1, b));
    for (int i = 1; i <= 50; ++i) s.move(200, 200 - 2.0f * float(i), 1);
    s.end();
    float most = -1e9f;
    for (const auto& v : s.mesh().vertices) most = std::max(most, v[1]);
    CHECK(most > 16);
    CHECK(pm::openEdgeCount(s.mesh()) == 0);
}

TEST_CASE("Pull reaches as far with the mirror on") {
    auto ps = ball();
    auto& s = *ps;
    pm::BrushSettings b;
    b.brush = pm::Brush::Pull;
    b.radius = 30;
    b.mirror = 1;
    REQUIRE(s.begin(250, 200, 1, b));
    for (int i = 1; i <= 50; ++i) s.move(250, 200 - 2.0f * float(i), 1);
    s.end();
    float most = -1e9f;
    for (const auto& v : s.mesh().vertices) most = std::max(most, v[1]);
    CHECK(most > 14);
}

TEST_CASE("Pull on a ball as the app shows it") {
    // The app's ball: 25 mm across, split five times, on a 1280 x 858 view about 158 mm away.
    pm::Sculpt s(pm::Sculpt::sphere(25, 5));
    float t = std::tan(0.4f), n = 1, f = 400, aspect = 1280.0f / 858.0f;
    float proj[16] = {1 / (t * aspect), 0, 0, 0, 0, 1 / t, 0, 0, 0, 0, -(f + n) / (f - n), -1, 0, 0, -2 * f * n / (f - n), 0};
    float view[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -158, 1};
    float vp[16];
    for (int c = 0; c < 4; ++c)
        for (int r = 0; r < 4; ++r) {
            float sum = 0;
            for (int k = 0; k < 4; ++k) sum += proj[k * 4 + r] * view[c * 4 + k];
            vp[c * 4 + r] = sum;
        }
    s.setCamera(vp, 1280, 858);
    pm::BrushSettings b;
    b.brush = pm::Brush::Pull;
    b.radius = 50;
    b.strength = 1;
    b.mirror = 1;
    b.dynamic = false;
    auto before = s.mesh();
    REQUIRE(s.begin(640, 470, 1, b));
    for (int i = 1; i <= 20; ++i) s.move(640, 470 + 5.0f * float(i), 1);
    s.end();
    // Without new detail the points are the same ones: the furthest moved about as far as the pointer, 100 pixels or 15 mm.
    auto after = s.mesh();
    float most = 0;
    if (after.vertices.size() == before.vertices.size())
        for (size_t i = 0; i < after.vertices.size(); ++i)
            most = std::max(most, std::abs(after.vertices[i][1] - before.vertices[i][1]));
    INFO("moved " << most << ", " << before.vertices.size() << " points before, " << after.vertices.size() << " after");
    CHECK(most > 13);
}

namespace {

// A session from a ball body, with the test camera.
std::unique_ptr<pm::Sculpt> bodySession(float radius) {
    auto s = pm::Sculpt::fromBody(pm::Sculpt::sphere(radius, 4), false, 2000000);
    float vp[16];
    camera(vp);
    s->setCamera(vp, 400, 400);
    return s;
}

bool same(const pm::Mesh& a, const pm::Mesh& b) {
    if (a.vertices.size() != b.vertices.size() || a.triangles != b.triangles) return false;
    for (size_t i = 0; i < a.vertices.size(); ++i)
        for (int k = 0; k < 3; ++k)
            if (std::abs(a.vertices[i][size_t(k)] - b.vertices[i][size_t(k)]) > 1e-4f) return false;
    return true;
}

}  // namespace

TEST_CASE("A saved session keeps its mask") {
    auto s = bodySession(20);
    pm::BrushSettings mask;
    mask.brush = pm::Brush::Mask;
    mask.strength = 1;
    mask.radius = 60;
    stroke(*s, mask, 200, 195, 200, 205);
    auto bytes = s->save();
    auto back = pm::Sculpt::load(bytes, 2000000);
    float most = 0;
    for (const auto& v : back->vertices()) most = std::max(most, v.mask);
    CHECK(most > 0.3f);
    // Loading puts the points in space order, so it's the same shape, not the same list.
    CHECK(back->triangleCount() == s->triangleCount());
    CHECK_THAT(pm::volume(back->mesh()), WithinAbs(pm::volume(s->mesh()), 1e-3));
}

TEST_CASE("A sculpt is made again on a changed body") {
    auto s = bodySession(20);
    pm::BrushSettings draw;
    draw.radius = 60;
    stroke(*s, draw, 200, 190, 200, 210);
    stroke(*s, draw, 190, 200, 210, 200);
    auto bytes = s->save();
    auto ball20 = pm::Sculpt::sphere(20, 4), ball25 = pm::Sculpt::sphere(25, 4);
    // Unchanged: the saved mesh as it is.
    CHECK_FALSE(pm::Sculpt::changedSince(bytes, ball20));
    CHECK(same(pm::Sculpt::result(bytes, &ball20, false), s->mesh()));
    pm::Mesh m = pm::Sculpt::result(bytes, &ball20, false);
    float top20 = -1e9f;
    for (const auto& v : m.vertices) top20 = std::max(top20, v[2]);
    // On a bigger ball the same strokes raise its top too.
    CHECK(pm::Sculpt::changedSince(bytes, ball25));
    pm::Mesh big = pm::Sculpt::result(bytes, &ball25, false);
    float top25 = -1e9f;
    for (const auto& v : big.vertices) top25 = std::max(top25, v[2]);
    CHECK(top20 > 20.5f);
    CHECK(top25 > 25.5f);
    CHECK(pm::openEdgeCount(big) == 0);
}

TEST_CASE("Replaying the record on the same body gives the same mesh") {
    auto s = bodySession(20);
    pm::BrushSettings b;
    b.radius = 50;
    b.detail = 1;
    stroke(*s, b, 170, 200, 230, 200);
    b.brush = pm::Brush::Pull;
    stroke(*s, b, 200, 210, 200, 150);
    s->evenOut(s->averageEdge());
    pm::BrushSettings undone;
    undone.radius = 80;
    undone.strength = 1;
    stroke(*s, undone, 150, 250, 250, 250);
    REQUIRE(s->undo());
    auto bytes = s->save();
    // Replayed from scratch, as for a changed body, on the same one: the undone stroke isn't in it.
    // Through result() with a body that only just differs, so the record is played again.
    pm::Mesh moved = pm::Sculpt::sphere(20, 4);
    moved.vertices[0][0] += 1e-6f;
    pm::Mesh replayed = pm::Sculpt::result(bytes, &moved, false);
    // A body changed by a hair can split a few triangles differently, but it's the same sculpt.
    CHECK(std::abs(double(replayed.triangles.size()) - double(s->mesh().triangles.size())) < 0.01 * double(replayed.triangles.size()));
    CHECK(std::abs(pm::volume(replayed) - pm::volume(s->mesh())) < 1.0);
}
