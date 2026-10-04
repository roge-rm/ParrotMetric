#pragma once

#include <vector>

#include "display/display_mesh.h"

namespace pm {

/**
 * How thick the body is at each vertex of a display mesh, in mm: the
 * distance in from the vertex, against its normal, to the far side. Where
 * nothing is hit the value is a large number. Each sample is taken a little
 * in from its vertex towards the middle of a triangle it's on.
 */
std::vector<float> wallThickness(const DisplayMesh& mesh);

/**
 * The mesh with its triangles split small enough to show thickness across
 * big flat faces, each with its own corners, and DisplayMesh::thickness
 * filled in. Faces, edges and corners stay as they were.
 */
DisplayMesh withThickness(const DisplayMesh& mesh);

}  // namespace pm
