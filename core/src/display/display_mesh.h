#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "mesh/mesh.h"

namespace pm {

/**
 * What the renderer draws for one body: triangles grouped into faces, and
 * edges as polylines. Face and edge numbers are what a tap selects, so they
 * match the body's own numbering (a solid's faces and edges in OCCT map
 * order, a mesh body's flat regions).
 */
struct DisplayMesh {
    // Per vertex. A face's vertices aren't shared with other faces, so each
    // can keep its own normal and face number.
    std::vector<float> positions;  // x, y, z in mm
    std::vector<float> normals;    // unit x, y, z
    std::vector<uint32_t> faceOfVertex;
    std::vector<uint32_t> indices;  // three per triangle

    struct Edge {
        std::vector<float> points;  // x, y, z per point; empty for edges not drawn, such as seams
    };
    std::vector<Edge> edges;
    /** How thick the body is at each vertex (mm), for the thickness view; empty when not worked out. */
    std::vector<float> thickness;
    /** A body, as opposed to a sketch or plane: the views that colour bodies colour it. */
    bool body = false;
    /** Corners that can be picked: x, y, z each. Solids only. */
    std::vector<float> corners;

    uint32_t faceCount = 0;

    // Edge colour, RGBA. Sketches show light on the dark ground; bodies' edges are dark.
    float edgeColour[4] = {0.13f, 0.18f, 0.17f, 0.9f};
    // Face colour, RGBA. Below 1 alpha the faces are see-through and drawn after the solid ones.
    float faceColour[4] = {0.80f, 0.82f, 0.80f, 1.0f};
    // Drawn a touch behind its true place, so sketches on it are seen and tapped first (construction planes).
    bool behind = false;

    size_t vertexCount() const { return positions.size() / 3; }
};

/**
 * Display data for a mesh body. Flat-shaded; touching triangles whose normals
 * are within flatAngle (radians) join one face, and edges are drawn where
 * faces meet at more than edgeAngle.
 */
DisplayMesh displayMesh(const Mesh& mesh, double flatAngle = 0.5 * 3.14159265 / 180, double edgeAngle = 25 * 3.14159265 / 180);

}  // namespace pm
