#include <catch2/catch_test_macros.hpp>

#include "display/display_mesh.h"
#include "mesh/mesh_body.h"
#include "solid/solid.h"

using namespace pm;

TEST_CASE("a box solid shows six faces and twelve edges") {
    DisplayMesh d = Solid::box(10, 20, 30).display();
    CHECK(d.faceCount == 6);
    CHECK(d.edges.size() == 12);
    for (const auto& e : d.edges) CHECK(e.points.size() >= 6);
    CHECK(d.normals.size() == d.positions.size());
    CHECK(d.faceOfVertex.size() == d.vertexCount());
}

TEST_CASE("a filleted box shows its rounded faces and their edges") {
    DisplayMesh d = Solid::box(20, 20, 20).filletAllEdges(2).display();
    CHECK(d.faceCount == 26);
    size_t drawn = 0;
    for (const auto& e : d.edges) drawn += e.points.empty() ? 0 : 1;
    CHECK(drawn == 48);
}

TEST_CASE("a mesh box groups its triangles into six flat faces with sharp edges") {
    DisplayMesh d = displayMesh(MeshBody::box(10, 10, 10).toMesh());
    CHECK(d.faceCount == 6);
    // Each of the 12 box edges, but split where triangles meet along it.
    CHECK(d.edges.size() >= 12);
    CHECK(d.indices.size() == 12 * 3);
}
