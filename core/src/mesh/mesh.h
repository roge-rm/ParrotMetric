#pragma once

#include <array>
#include <cstdint>
#include <utility>
#include <unordered_map>
#include <vector>

namespace pm {

/** An indexed triangle mesh in millimetres. Triangles wind anticlockwise seen from outside. */
struct Mesh {
    std::vector<std::array<float, 3>> vertices;
    std::vector<std::array<uint32_t, 3>> triangles;

    size_t triangleCount() const { return triangles.size(); }
};

/**
 * Builds a Mesh from separate triangles, joining corners that land in the same
 * weld cell (distance in mm) into one vertex. Triangles squashed to a point or
 * line by welding are dropped.
 */
class MeshBuilder {
public:
    explicit MeshBuilder(float weldDistance = 1e-4f) : scale_(1.0f / weldDistance) {}

    void triangle(const std::array<float, 3>& a, const std::array<float, 3>& b, const std::array<float, 3>& c);
    Mesh take() { return std::move(mesh_); }

private:
    uint32_t add(const std::array<float, 3>& p);

    Mesh mesh_;
    float scale_;
    std::unordered_map<uint64_t, uint32_t> index_;
};

/** Volume enclosed by a closed mesh, in cubic millimetres. */
double volume(const Mesh& mesh);

/** Number of edges not shared by exactly two triangles. Zero for a closed, manifold mesh. */
size_t openEdgeCount(const Mesh& mesh);

}  // namespace pm
