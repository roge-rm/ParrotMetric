#include "mesh/repair.h"

#include <algorithm>
#include <array>
#include <map>
#include <set>
#include <utility>

namespace pm {

std::string RepairReport::summary() const {
    std::string s;
    auto add = [&](size_t n, const char* one, const char* many) {
        if (!n) return;
        if (!s.empty()) s += ", ";
        s += n == 1 ? std::string(one) : std::to_string(n) + " " + many;
    };
    if (insideOut) s = "turned it right side out";
    add(flipped, "turned a triangle round", "triangles turned round");
    add(holesFilled, "closed a hole", "holes closed");
    add(duplicates, "dropped a repeated triangle", "repeated triangles dropped");
    if (!s.empty()) s = "Repaired: " + s;
    return s;
}

namespace {

using Edge = std::pair<uint32_t, uint32_t>;

Edge key(uint32_t a, uint32_t b) { return {std::min(a, b), std::max(a, b)}; }

}  // namespace

Mesh repair(const Mesh& in, RepairReport& report, size_t maxHoleEdges) {
    Mesh m;
    m.vertices = in.vertices;

    // Repeated triangles, in any winding, go.
    std::set<std::array<uint32_t, 3>> seen;
    for (const auto& t : in.triangles) {
        std::array<uint32_t, 3> s = t;
        std::sort(s.begin(), s.end());
        if (!seen.insert(s).second) { report.duplicates++; continue; }
        m.triangles.push_back(t);
    }

    // Turn triangles to agree with their neighbours, spreading out from each unvisited one.
    std::map<Edge, std::vector<uint32_t>> sides;
    for (uint32_t i = 0; i < m.triangles.size(); ++i)
        for (int k = 0; k < 3; ++k) sides[key(m.triangles[i][k], m.triangles[i][(k + 1) % 3])].push_back(i);
    std::vector<int> state(m.triangles.size(), 0);  // 0 unvisited, 1 done.
    auto hasEdge = [&](const std::array<uint32_t, 3>& t, uint32_t a, uint32_t b) {
        for (int k = 0; k < 3; ++k) if (t[k] == a && t[(k + 1) % 3] == b) return true;
        return false;
    };
    std::vector<std::vector<uint32_t>> pieces;
    for (uint32_t seed = 0; seed < m.triangles.size(); ++seed) {
        if (state[seed]) continue;
        std::vector<uint32_t> piece, stack{seed};
        state[seed] = 1;
        while (!stack.empty()) {
            uint32_t t = stack.back();
            stack.pop_back();
            piece.push_back(t);
            const auto tri = m.triangles[t];
            for (int k = 0; k < 3; ++k) {
                uint32_t a = tri[k], b = tri[(k + 1) % 3];
                const auto& near = sides[key(a, b)];
                if (near.size() != 2) continue;
                uint32_t o = near[0] == t ? near[1] : near[0];
                if (state[o]) continue;
                // Agreeing neighbours run their shared edge the other way.
                if (hasEdge(m.triangles[o], a, b)) {
                    std::swap(m.triangles[o][1], m.triangles[o][2]);
                    report.flipped++;
                }
                state[o] = 1;
                stack.push_back(o);
            }
        }
        pieces.push_back(std::move(piece));
    }
    // Pieces that turned out mostly flipped were really the right way round: count the smaller part.
    report.flipped = std::min(report.flipped, m.triangles.size() - report.flipped);

    // Close holes: walk each loop of open edges and fan it from its middle.
    std::map<uint32_t, std::vector<uint32_t>> next;  // Open edges, in the winding a fill needs (reversed).
    std::map<Edge, int> uses;
    for (const auto& t : m.triangles)
        for (int k = 0; k < 3; ++k) uses[key(t[k], t[(k + 1) % 3])]++;
    for (const auto& t : m.triangles)
        for (int k = 0; k < 3; ++k) {
            uint32_t a = t[k], b = t[(k + 1) % 3];
            if (uses[key(a, b)] == 1) next[b].push_back(a);
        }
    std::set<Edge> used;
    for (auto& [start, outs] : next) {
        for (uint32_t first : outs) {
            if (used.count({start, first})) continue;
            std::vector<uint32_t> loop{start};
            uint32_t at = first;
            used.insert({start, first});
            bool closed = false;
            while (loop.size() <= maxHoleEdges) {
                if (at == start) { closed = true; break; }
                loop.push_back(at);
                auto it = next.find(at);
                if (it == next.end()) break;
                uint32_t step = UINT32_MAX;
                for (uint32_t n : it->second) if (!used.count({at, n})) { step = n; break; }
                if (step == UINT32_MAX) break;
                used.insert({at, step});
                at = step;
            }
            if (!closed || loop.size() < 3) continue;
            std::array<float, 3> c{0, 0, 0};
            for (uint32_t v : loop) for (int k = 0; k < 3; ++k) c[k] += m.vertices[v][k] / float(loop.size());
            uint32_t mid = uint32_t(m.vertices.size());
            m.vertices.push_back(c);
            for (size_t i = 0; i < loop.size(); ++i) m.triangles.push_back({loop[i], loop[(i + 1) % loop.size()], mid});
            report.holesFilled++;
        }
    }

    // Outwards: a closed mesh facing in has negative volume.
    if (volume(m) < 0) {
        for (auto& t : m.triangles) std::swap(t[1], t[2]);
        report.insideOut = true;
    }
    report.openEdges = openEdgeCount(m);
    return m;
}

}  // namespace pm
