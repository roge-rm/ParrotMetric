#include "mesh/stl.h"

#include <cmath>
#include <cstring>
#include <sstream>
#include <stdexcept>

namespace pm {
namespace {

Mesh readBinary(const std::vector<uint8_t>& data, uint32_t count, float weld) {
    MeshBuilder builder(weld);
    const uint8_t* p = data.data() + 84;
    for (uint32_t i = 0; i < count; ++i, p += 50) {
        std::array<float, 3> v[3];
        // Skips the 12-byte normal; it's recomputed from the winding.
        std::memcpy(v, p + 12, 36);
        builder.triangle(v[0], v[1], v[2]);
    }
    return builder.take();
}

Mesh readAscii(const std::vector<uint8_t>& data, float weld) {
    MeshBuilder builder(weld);
    std::istringstream in(std::string(data.begin(), data.end()));
    std::string word;
    std::array<float, 3> v[3];
    int corner = 0;
    while (in >> word) {
        if (word == "vertex") {
            if (corner > 2) throw std::runtime_error("STL facet has more than three corners");
            in >> v[corner][0] >> v[corner][1] >> v[corner][2];
            if (!in) throw std::runtime_error("Bad number in STL");
            ++corner;
        } else if (word == "endfacet") {
            if (corner != 3) throw std::runtime_error("STL facet without three corners");
            builder.triangle(v[0], v[1], v[2]);
            corner = 0;
        }
    }
    return builder.take();
}

}  // namespace

Mesh readStl(const std::vector<uint8_t>& data, float weldDistance) {
    if (data.size() >= 84) {
        uint32_t count;
        std::memcpy(&count, data.data() + 80, 4);
        // Some binary files start with "solid" too, so the size decides.
        if (data.size() == 84 + size_t(count) * 50) return readBinary(data, count, weldDistance);
    }
    if (data.size() >= 5 && std::memcmp(data.data(), "solid", 5) == 0) return readAscii(data, weldDistance);
    throw std::runtime_error("Not an STL file");
}

std::vector<uint8_t> writeStl(const Mesh& mesh, const std::string& header) {
    std::vector<uint8_t> out(84 + mesh.triangles.size() * 50, 0);
    std::memcpy(out.data(), header.data(), std::min<size_t>(header.size(), 80));
    uint32_t count = uint32_t(mesh.triangles.size());
    std::memcpy(out.data() + 80, &count, 4);
    uint8_t* p = out.data() + 84;
    for (const auto& t : mesh.triangles) {
        const auto& a = mesh.vertices[t[0]];
        const auto& b = mesh.vertices[t[1]];
        const auto& c = mesh.vertices[t[2]];
        float u[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float w[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        float n[3] = {u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0]};
        float len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len > 0) for (float& x : n) x /= len;
        std::memcpy(p, n, 12);
        std::memcpy(p + 12, a.data(), 12);
        std::memcpy(p + 24, b.data(), 12);
        std::memcpy(p + 36, c.data(), 12);
        p += 50;
    }
    return out;
}

}  // namespace pm
