#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

#include "io/exchange.h"

using namespace pm;

TEST_CASE("STEP and IGES round trips keep the solid") {
    Solid box = Solid::box(30, 20, 10).filletAllEdges(1.5);
    for (SolidFormat f : {SolidFormat::Step, SolidFormat::Iges}) {
        Solid back = readSolid(writeSolid(box, f), f);
        CHECK(back.volume() == Catch::Approx(box.volume()).epsilon(1e-4));
    }
}

TEST_CASE("a file that isn't STEP is refused") {
    std::string junk = "not a step file";
    CHECK_THROWS(readSolid(std::vector<uint8_t>(junk.begin(), junk.end()), SolidFormat::Step));
}
