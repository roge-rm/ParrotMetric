#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include "mesh/mesh_body.h"
#include "mesh/repair.h"

using namespace pm;

TEST_CASE("a cube missing a face, with a flipped triangle and a repeat, comes back closed") {
    Mesh m = MeshBody::box(10, 10, 10).toMesh();
    // Take one triangle out, turn another round, and repeat a third.
    m.triangles.erase(m.triangles.begin());
    std::swap(m.triangles[3][1], m.triangles[3][2]);
    m.triangles.push_back(m.triangles[5]);
    RepairReport r;
    Mesh fixed = repair(m, r);
    CHECK(r.duplicates == 1);
    CHECK(r.flipped == 1);
    CHECK(r.holesFilled == 1);
    CHECK(r.openEdges == 0);
    CHECK(volume(fixed) == Catch::Approx(1000));
    CHECK_NOTHROW(MeshBody::fromMesh(fixed));
    CHECK(!r.summary().empty());
}

TEST_CASE("an inside-out mesh is turned the right way") {
    Mesh m = MeshBody::box(2, 2, 2).toMesh();
    for (auto& t : m.triangles) std::swap(t[1], t[2]);
    RepairReport r;
    Mesh fixed = repair(m, r);
    CHECK(r.insideOut);
    CHECK(volume(fixed) == Catch::Approx(8));
}

TEST_CASE("a good mesh is left alone") {
    RepairReport r;
    repair(MeshBody::box(3, 3, 3).toMesh(), r);
    CHECK(!r.changed());
    CHECK(r.summary().empty());
}
