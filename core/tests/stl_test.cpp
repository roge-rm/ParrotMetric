#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include <string>

#include "mesh/stl.h"

using namespace pm;

namespace {

/** A unit tetrahedron, winding outwards. */
Mesh tetrahedron() {
    Mesh m;
    m.vertices = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    m.triangles = {{0, 2, 1}, {0, 1, 3}, {0, 3, 2}, {1, 2, 3}};
    return m;
}

}  // namespace

TEST_CASE("binary STL round trip keeps the shape and welds corners") {
    Mesh m = tetrahedron();
    auto bytes = writeStl(m);
    REQUIRE(bytes.size() == 84 + 4 * 50);
    Mesh back = readStl(bytes);
    CHECK(back.vertices.size() == 4);
    CHECK(back.triangles.size() == 4);
    CHECK(openEdgeCount(back) == 0);
    CHECK(volume(back) == Catch::Approx(1.0 / 6.0));
}

TEST_CASE("ASCII STL reads") {
    std::string text =
        "solid t\n"
        "facet normal 0 0 -1\n outer loop\n  vertex 0 0 0\n  vertex 0 1 0\n  vertex 1 0 0\n endloop\nendfacet\n"
        "facet normal 0 -1 0\n outer loop\n  vertex 0 0 0\n  vertex 1 0 0\n  vertex 0 0 1\n endloop\nendfacet\n"
        "facet normal -1 0 0\n outer loop\n  vertex 0 0 0\n  vertex 0 0 1\n  vertex 0 1 0\n endloop\nendfacet\n"
        "facet normal 1 1 1\n outer loop\n  vertex 1 0 0\n  vertex 0 1 0\n  vertex 0 0 1\n endloop\nendfacet\n"
        "endsolid t\n";
    Mesh m = readStl(std::vector<uint8_t>(text.begin(), text.end()));
    CHECK(m.vertices.size() == 4);
    CHECK(openEdgeCount(m) == 0);
    CHECK(volume(m) == Catch::Approx(1.0 / 6.0));
}

TEST_CASE("junk is refused") {
    std::vector<uint8_t> junk(10, 'x');
    CHECK_THROWS(readStl(junk));
}
