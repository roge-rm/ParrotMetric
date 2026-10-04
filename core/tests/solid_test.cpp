#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include <cmath>

#include "solid/solid.h"
#include "mesh/mesh_body.h"
#include "mesh/stl.h"

using namespace pm;

TEST_CASE("a filleted box tessellates to a closed mesh of the right volume") {
    const double s = 20, r = 2;
    Solid box = Solid::box(s, s, s).filletAllEdges(r);
    // A cube with every edge and corner rounded: the inner box, the slabs on
    // each face, quarter cylinders on each edge and a sphere's worth of corners.
    const double inner = s - 2 * r;
    const double expected = inner * inner * inner + 6 * inner * inner * r + 3 * M_PI * r * r * inner
                          + 4.0 / 3.0 * M_PI * r * r * r;
    CHECK(box.volume() == Catch::Approx(expected).epsilon(1e-6));

    Mesh mesh = box.tessellate();
    CHECK(openEdgeCount(mesh) == 0);
    CHECK(volume(mesh) == Catch::Approx(expected).epsilon(2e-3));
    // And it survives STL and becomes a valid mesh body.
    MeshBody body = MeshBody::fromMesh(readStl(writeStl(mesh)));
    CHECK(body.volume() == Catch::Approx(expected).epsilon(2e-3));
}

TEST_CASE("a fillet too big for the box fails cleanly") {
    CHECK_THROWS(Solid::box(10, 10, 10).filletAllEdges(6));
}
