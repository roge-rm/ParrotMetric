#include "mesh/mesh.h"

#include <cmath>
#include <map>
#include <utility>

namespace pm {

uint32_t MeshBuilder::add(const std::array<float, 3>& p) {
    uint64_t key = 1469598103934665603ull;
    for (float v : p) key = (key ^ uint64_t(std::llround(v * scale_))) * 1099511628211ull;
    auto [it, added] = index_.try_emplace(key, uint32_t(mesh_.vertices.size()));
    if (added) mesh_.vertices.push_back(p);
    return it->second;
}

void MeshBuilder::triangle(const std::array<float, 3>& a, const std::array<float, 3>& b, const std::array<float, 3>& c) {
    uint32_t ia = add(a), ib = add(b), ic = add(c);
    if (ia != ib && ib != ic && ia != ic) mesh_.triangles.push_back({ia, ib, ic});
}

double volume(const Mesh& mesh) {
    // Sum of the signed tetrahedra from the origin to each triangle.
    double sum = 0;
    for (const auto& t : mesh.triangles) {
        const auto& a = mesh.vertices[t[0]];
        const auto& b = mesh.vertices[t[1]];
        const auto& c = mesh.vertices[t[2]];
        sum += double(a[0]) * (double(b[1]) * c[2] - double(b[2]) * c[1])
             - double(a[1]) * (double(b[0]) * c[2] - double(b[2]) * c[0])
             + double(a[2]) * (double(b[0]) * c[1] - double(b[1]) * c[0]);
    }
    return sum / 6.0;
}

size_t openEdgeCount(const Mesh& mesh) {
    std::map<std::pair<uint32_t, uint32_t>, int> uses;
    for (const auto& t : mesh.triangles) {
        for (int i = 0; i < 3; ++i) {
            uint32_t a = t[i], b = t[(i + 1) % 3];
            uses[{std::min(a, b), std::max(a, b)}]++;
        }
    }
    size_t open = 0;
    for (const auto& [edge, n] : uses) {
        if (n != 2) ++open;
    }
    return open;
}

}  // namespace pm
