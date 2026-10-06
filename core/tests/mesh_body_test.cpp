#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include "mesh/mesh_body.h"
#include "mesh/stl.h"
#include "sculpt/sculpt.h"

using namespace pm;

TEST_CASE("an imported mesh can be cut by a box") {
    // Through STL, as an imported file would arrive.
    Mesh cube = MeshBody::box(20, 20, 20).toMesh();
    MeshBody body = MeshBody::fromMesh(readStl(writeStl(cube)));
    MeshBody cut = body.boolean(MeshBody::box(10, 10, 40).translated(5, 5, -10), BooleanOp::Cut);
    CHECK(cut.volume() == Catch::Approx(8000.0 - 2000.0));
    CHECK(openEdgeCount(cut.toMesh()) == 0);
}

TEST_CASE("a mesh with a hole is refused") {
    Mesh m = MeshBody::box(1, 1, 1).toMesh();
    m.triangles.pop_back();
    CHECK_THROWS(MeshBody::fromMesh(m));
}

TEST_CASE("a mesh can be reduced, remeshed and smoothed") {
    MeshBody box = MeshBody::box(20, 20, 20);
    // Split finer, then reduced back down: the flat sides need few triangles.
    MeshBody fine = box.remeshed(2);
    CHECK(fine.triangleCount() > box.triangleCount() * 10);
    CHECK(fine.volume() == Catch::Approx(8000.0));
    MeshBody fewer = fine.reduced(0.01);
    CHECK(fewer.triangleCount() < fine.triangleCount() / 4);
    CHECK(fewer.volume() == Catch::Approx(8000.0).epsilon(1e-4));
    // Rounded off with no edge kept sharp it turns into a blob; keeping its square edges it stays a box.
    MeshBody round = box.smoothed(180, 4);
    CHECK(round.triangleCount() == box.triangleCount() * 16);
    CHECK(round.volume() != Catch::Approx(8000.0).epsilon(0.05));
    CHECK(openEdgeCount(round.toMesh()) == 0);
    CHECK(fine.smoothed(30, 2).volume() == Catch::Approx(8000.0).epsilon(0.01));
    CHECK_THROWS(box.reduced(0));
    CHECK_THROWS(box.smoothed(30, 0));
}

TEST_CASE("a mesh can be hollowed, erased and filled, and separated") {
    // Walls 2 thick inside a 40 cube: what's left is the cube less a 36 cube, give or take the grid.
    MeshBody box = MeshBody::box(40, 40, 40);
    MeshBody hollow = box.hollowed(2);
    CHECK(hollow.volume() == Catch::Approx(64000.0 - 46656.0).epsilon(0.08));
    CHECK(openEdgeCount(hollow.toMesh()) == 0);
    CHECK(hollow.parts().size() == 1);
    CHECK_THROWS(box.hollowed(25));
    // Erasing a patch of a ball and filling it in keeps it closed and much the same.
    MeshBody ball = MeshBody::fromMesh(Sculpt::sphere(20, 4));
    MeshBody mended = ball.erased({{{0, 0, 20, 6}}});
    CHECK(openEdgeCount(mended.toMesh()) == 0);
    // The patch carries the ball's curve on across the hole, so little is lost.
    CHECK(mended.volume() == Catch::Approx(ball.volume()).epsilon(0.002));
    CHECK(mended.volume() < ball.volume());
    CHECK_THROWS(ball.erased({{{100, 0, 0, 1}}}));
    // Two boxes apart are two pieces, the bigger first.
    MeshBody two = MeshBody::box(10, 10, 10).boolean(MeshBody::box(5, 5, 5).translated(20, 0, 0), BooleanOp::Join);
    auto pieces = two.parts();
    REQUIRE(pieces.size() == 2);
    CHECK(pieces[0].volume() == Catch::Approx(1000.0));
    CHECK(pieces[1].volume() == Catch::Approx(125.0));
}
