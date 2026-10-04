#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include "mesh/mesh_body.h"
#include "mesh/stl.h"

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
