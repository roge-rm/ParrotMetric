#pragma once

#include <string>

#include "mesh/mesh.h"

namespace pm {

/** What [repair] changed. */
struct RepairReport {
    size_t duplicates = 0;   // Triangles that were exactly on top of another.
    size_t flipped = 0;      // Triangles turned to face the same way as their neighbours.
    size_t holesFilled = 0;  // Gaps closed.
    bool insideOut = false;  // The whole mesh faced inwards and was turned round.
    size_t openEdges = 0;    // Edges still left open after repair.

    bool changed() const { return duplicates || flipped || holesFilled || insideOut; }
    /** A short line for the person, or empty if nothing was done. */
    std::string summary() const;
};

/**
 * Tidies a mesh read from a file so it can be a body: drops repeated
 * triangles, turns triangles to agree with their neighbours and outwards,
 * and closes holes whose edge loop has up to maxHoleEdges edges.
 */
Mesh repair(const Mesh& in, RepairReport& report, size_t maxHoleEdges = 256);

}  // namespace pm
