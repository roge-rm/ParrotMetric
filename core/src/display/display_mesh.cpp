#include "display/display_mesh.h"

#include <cmath>
#include <map>
#include <utility>

namespace pm {
namespace {

using Vec = std::array<double, 3>;

Vec normalOf(const Mesh& m, const std::array<uint32_t, 3>& t) {
    const auto& a = m.vertices[t[0]];
    const auto& b = m.vertices[t[1]];
    const auto& c = m.vertices[t[2]];
    Vec u{b[0] - a[0], b[1] - a[1], b[2] - a[2]}, w{c[0] - a[0], c[1] - a[1], c[2] - a[2]};
    Vec n{u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0]};
    double len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
    if (len > 0) for (double& x : n) x /= len;
    return n;
}

double dot(const Vec& a, const Vec& b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

}  // namespace

DisplayMesh displayMesh(const Mesh& mesh, double flatAngle, double edgeAngle) {
    const size_t n = mesh.triangles.size();
    std::vector<Vec> normal(n);
    for (size_t i = 0; i < n; ++i) normal[i] = normalOf(mesh, mesh.triangles[i]);

    // The triangles on each side of every edge.
    std::map<std::pair<uint32_t, uint32_t>, std::vector<uint32_t>> sides;
    for (uint32_t i = 0; i < n; ++i) {
        const auto& t = mesh.triangles[i];
        for (int k = 0; k < 3; ++k) {
            uint32_t a = t[k], b = t[(k + 1) % 3];
            sides[{std::min(a, b), std::max(a, b)}].push_back(i);
        }
    }
    std::vector<std::vector<uint32_t>> neighbours(n);
    for (const auto& [edge, tris] : sides) {
        if (tris.size() != 2) continue;
        neighbours[tris[0]].push_back(tris[1]);
        neighbours[tris[1]].push_back(tris[0]);
    }

    // Faces: grow each from a seed over neighbours facing the same way as the seed.
    const double flatCos = std::cos(flatAngle);
    std::vector<uint32_t> face(n, UINT32_MAX);
    uint32_t faces = 0;
    std::vector<uint32_t> stack;
    for (uint32_t seed = 0; seed < n; ++seed) {
        if (face[seed] != UINT32_MAX) continue;
        face[seed] = faces;
        stack.assign(1, seed);
        while (!stack.empty()) {
            uint32_t t = stack.back();
            stack.pop_back();
            for (uint32_t o : neighbours[t]) {
                if (face[o] == UINT32_MAX && dot(normal[o], normal[seed]) >= flatCos) {
                    face[o] = faces;
                    stack.push_back(o);
                }
            }
        }
        ++faces;
    }

    DisplayMesh d;
    d.faceCount = faces;
    d.positions.reserve(n * 9);
    d.normals.reserve(n * 9);
    d.faceOfVertex.reserve(n * 3);
    d.indices.reserve(n * 3);
    for (uint32_t i = 0; i < n; ++i) {
        for (uint32_t v : mesh.triangles[i]) {
            const auto& p = mesh.vertices[v];
            d.positions.insert(d.positions.end(), {p[0], p[1], p[2]});
            d.normals.insert(d.normals.end(), {float(normal[i][0]), float(normal[i][1]), float(normal[i][2])});
            d.faceOfVertex.push_back(face[i]);
            d.indices.push_back(uint32_t(d.indices.size()));
        }
    }

    // Edges where two faces meet at a sharp angle, and open boundaries.
    const double edgeCos = std::cos(edgeAngle);
    for (const auto& [edge, tris] : sides) {
        bool sharp = tris.size() != 2 ||
                     (face[tris[0]] != face[tris[1]] && dot(normal[tris[0]], normal[tris[1]]) < edgeCos);
        if (!sharp) continue;
        const auto& a = mesh.vertices[edge.first];
        const auto& b = mesh.vertices[edge.second];
        d.edges.push_back({{a[0], a[1], a[2], b[0], b[1], b[2]}});
    }
    return d;
}

}  // namespace pm
