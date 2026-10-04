#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include "io/mesh_formats.h"
#include "mesh/mesh_body.h"

using namespace pm;

TEST_CASE("3MF round trip keeps each body") {
    NamedMesh a{"Body 1", MeshBody::box(10, 10, 10).toMesh()};
    NamedMesh b{"Body <2> & co", MeshBody::box(5, 5, 5).translated(20, 0, 0).toMesh()};
    auto bytes = write3mf({a, b});
    auto back = read3mf(bytes);
    REQUIRE(back.size() == 2);
    CHECK(back[0].name == "Body 1");
    CHECK(volume(back[0].mesh) == Catch::Approx(1000));
    CHECK(volume(back[1].mesh) == Catch::Approx(125));
    CHECK(openEdgeCount(back[1].mesh) == 0);
}

TEST_CASE("OBJ round trip, with quads split") {
    NamedMesh a{"Box", MeshBody::box(10, 20, 30).toMesh()};
    Mesh back = readObj(writeObj({a}));
    CHECK(volume(back) == Catch::Approx(6000));
    std::string quadCube =
        "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nv 0 0 1\nv 1 0 1\nv 1 1 1\nv 0 1 1\n"
        "f 1 4 3 2\nf 5 6 7 8\nf 1 2 6 5\nf 2 3 7 6\nf 3 4 8 7\nf 4 1 5 8\n";
    Mesh cube = readObj(quadCube);
    CHECK(cube.triangles.size() == 12);
    CHECK(volume(cube) == Catch::Approx(1.0));
    CHECK(openEdgeCount(cube) == 0);
}

TEST_CASE("bad files are refused") {
    CHECK_THROWS(read3mf(std::vector<uint8_t>(100, 7)));
    CHECK_THROWS(readObj("hello\n"));
}
