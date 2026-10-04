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
