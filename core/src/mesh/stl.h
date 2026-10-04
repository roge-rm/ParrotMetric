#pragma once

#include <string>
#include <vector>

#include "mesh/mesh.h"

namespace pm {

/**
 * Reads binary or ASCII STL. Corners closer than weldDistance (mm) become one
 * vertex, since STL stores every triangle's corners separately. Throws
 * std::runtime_error if the data isn't STL.
 */
Mesh readStl(const std::vector<uint8_t>& data, float weldDistance = 1e-4f);

/** Writes binary STL. */
std::vector<uint8_t> writeStl(const Mesh& mesh, const std::string& header = "ParrotMetric");

}  // namespace pm
