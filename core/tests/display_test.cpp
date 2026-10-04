#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>
#include <cmath>

#include "display/display_mesh.h"
#include "display/thickness.h"
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

TEST_CASE("wall thickness is how far it is through the body") {
    // A slab 4 thick on top of nothing: up and down faces read 4, the sides 30 and 20 across.
    DisplayMesh d = displayMesh(MeshBody::box(30, 20, 4).toMesh());
    auto t = wallThickness(d);
    REQUIRE(t.size() == d.vertexCount());
    int up = 0, side = 0;
    for (size_t i = 0; i < t.size(); ++i) {
        float nz = d.normals[i * 3 + 2], nx = d.normals[i * 3];
        if (std::abs(nz) > 0.9f) { CHECK(t[i] == Catch::Approx(4.0).margin(1e-3)); ++up; }
        if (std::abs(nx) > 0.9f) { CHECK(t[i] == Catch::Approx(30.0).margin(1e-3)); ++side; }
    }
    CHECK(up > 0);
    CHECK(side > 0);
}

TEST_CASE("thin walls show across big flat faces") {
    // A box with 1 mm walls and no top: its sides have only corner vertices until split.
    MeshBody cup = MeshBody::box(30, 20, 10).boolean(MeshBody::box(28, 18, 10).translated(1, 1, 1), BooleanOp::Cut);
    DisplayMesh d = withThickness(displayMesh(cup.toMesh()));
    REQUIRE(d.thickness.size() == d.vertexCount());
    size_t sides = 0, thin = 0;
    for (size_t i = 0; i < d.vertexCount(); ++i) {
        if (std::abs(d.normals[i * 3 + 2]) > 0.1f) continue;
        ++sides;
        if (d.thickness[i] < 1.01f) ++thin;
    }
    CHECK(sides > 1000);
    CHECK(double(thin) / double(sides) > 0.85);
}
