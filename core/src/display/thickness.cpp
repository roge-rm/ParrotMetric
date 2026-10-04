#include "display/thickness.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <thread>
#include <unordered_map>

#include "parallel.h"

namespace pm {

namespace {

constexpr float kNothing = 1e9f;

struct Box {
    float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
    void add(const float* p) {
        for (int k = 0; k < 3; ++k) {
            lo[k] = std::min(lo[k], p[k]);
            hi[k] = std::max(hi[k], p[k]);
        }
    }
    void add(const Box& b) {
        add(b.lo);
        add(b.hi);
    }
    /** Where a ray enters, or kNothing if it misses or only enters past far. */
    float enter(const float* o, const float* inv, float far) const {
        float t0 = 0, t1 = far;
        for (int k = 0; k < 3; ++k) {
            float a = (lo[k] - o[k]) * inv[k], b = (hi[k] - o[k]) * inv[k];
            if (a > b) std::swap(a, b);
            t0 = std::max(t0, a);
            t1 = std::min(t1, b);
            if (t0 > t1) return kNothing;
        }
        return t0;
    }
};

/** A tree of boxes over the triangles: leaves hold up to four triangles. */
class Tree {
public:
    Tree(const std::vector<float>& pos, const std::vector<uint32_t>& idx) : pos_(pos), idx_(idx) {
        size_t n = idx.size() / 3;
        order_.resize(n);
        centres_.resize(n * 3);
        for (size_t t = 0; t < n; ++t) {
            order_[t] = uint32_t(t);
            for (int k = 0; k < 3; ++k)
                centres_[t * 3 + k] = (corner(t, 0)[k] + corner(t, 1)[k] + corner(t, 2)[k]) / 3;
        }
        if (n == 0) return;
        nodes_.reserve(n);
        nodes_.emplace_back();
        build(0, 0, n);
    }

    /** The nearest hit along the ray from o in direction d (unit), or kNothing. */
    float hit(const float* o, const float* d) const {
        if (nodes_.empty()) return kNothing;
        float inv[3];
        for (int k = 0; k < 3; ++k) inv[k] = std::abs(d[k]) > 1e-12f ? 1 / d[k] : 1e30f;
        float best = kNothing;
        uint32_t stack[64];
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            const Node& node = nodes_[stack[--top]];
            if (node.box.enter(o, inv, best) == kNothing) continue;
            if (node.count > 0) {
                for (uint32_t i = 0; i < node.count; ++i) best = std::min(best, triangle(order_[node.first + i], o, d));
            } else if (top < 62) {
                stack[top++] = node.first;
                stack[top++] = node.first + 1;
            }
        }
        return best;
    }

private:
    struct Node {
        Box box;
        uint32_t first = 0;  // The first child, or the first triangle in order_ for a leaf.
        uint32_t count = 0;  // Triangles in a leaf; 0 for an inner node.
    };

    const float* corner(size_t t, int c) const { return &pos_[idx_[t * 3 + size_t(c)] * 3]; }

    /** Fills the node at slot with the tree over order_[from, to). */
    void build(uint32_t slot, size_t from, size_t to) {
        Box box, centres;
        for (size_t i = from; i < to; ++i) {
            for (int c = 0; c < 3; ++c) box.add(corner(order_[i], c));
            centres.add(&centres_[order_[i] * 3]);
        }
        nodes_[slot].box = box;
        if (to - from <= 4) {
            nodes_[slot].first = uint32_t(from);
            nodes_[slot].count = uint32_t(to - from);
            return;
        }
        // Split the longest side at the median.
        int axis = 0;
        for (int k = 1; k < 3; ++k)
            if (centres.hi[k] - centres.lo[k] > centres.hi[axis] - centres.lo[axis]) axis = k;
        size_t mid = (from + to) / 2;
        std::nth_element(order_.begin() + long(from), order_.begin() + long(mid), order_.begin() + long(to),
                         [&](uint32_t a, uint32_t b) { return centres_[a * 3 + size_t(axis)] < centres_[b * 3 + size_t(axis)]; });
        // The two children sit side by side.
        uint32_t left = uint32_t(nodes_.size());
        nodes_.emplace_back();
        nodes_.emplace_back();
        nodes_[slot].first = left;
        build(left, from, mid);
        build(left + 1, mid, to);
    }

    /** Möller-Trumbore: the distance along the ray to triangle t, or kNothing. */
    float triangle(uint32_t t, const float* o, const float* d) const {
        const float *a = corner(t, 0), *b = corner(t, 1), *c = corner(t, 2);
        float e1[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float e2[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        float p[3] = {d[1] * e2[2] - d[2] * e2[1], d[2] * e2[0] - d[0] * e2[2], d[0] * e2[1] - d[1] * e2[0]};
        float det = e1[0] * p[0] + e1[1] * p[1] + e1[2] * p[2];
        if (std::abs(det) < 1e-12f) return kNothing;
        float inv = 1 / det;
        float s[3] = {o[0] - a[0], o[1] - a[1], o[2] - a[2]};
        float u = (s[0] * p[0] + s[1] * p[1] + s[2] * p[2]) * inv;
        if (u < 0 || u > 1) return kNothing;
        float q[3] = {s[1] * e1[2] - s[2] * e1[1], s[2] * e1[0] - s[0] * e1[2], s[0] * e1[1] - s[1] * e1[0]};
        float v = (d[0] * q[0] + d[1] * q[1] + d[2] * q[2]) * inv;
        if (v < 0 || u + v > 1) return kNothing;
        float dist = (e2[0] * q[0] + e2[1] * q[1] + e2[2] * q[2]) * inv;
        return dist > 0 ? dist : kNothing;
    }

    const std::vector<float>& pos_;
    const std::vector<uint32_t>& idx_;
    std::vector<uint32_t> order_;
    std::vector<float> centres_;
    std::vector<Node> nodes_;
};

}  // namespace

namespace {

/** Thickness at each of samples (x, y, z, then the normal's x, y, z each), cast against mesh's triangles. */
std::vector<float> castAll(const DisplayMesh& mesh, const std::vector<float>& samples) {
    size_t n = samples.size() / 6;
    std::vector<float> out(n, kNothing);
    Tree tree(mesh.positions, mesh.indices);
    // Rays start a hair inside, so they don't hit the face they leave.
    Box all;
    for (size_t i = 0; i < mesh.vertexCount(); ++i) all.add(&mesh.positions[i * 3]);
    float size = 0;
    for (int k = 0; k < 3; ++k) size = std::max(size, all.hi[k] - all.lo[k]);
    const float nudge = std::max(size * 1e-5f, 1e-5f);
    auto run = [&](size_t from, size_t to) {
        for (size_t i = from; i < to; ++i) {
            const float* p = &samples[i * 6];
            float d[3] = {-p[3], -p[4], -p[5]};
            float o[3] = {p[0] + d[0] * nudge, p[1] + d[1] * nudge, p[2] + d[2] * nudge};
            float t = tree.hit(o, d);
            if (t < kNothing) out[i] = t + nudge;
        }
    };
    unsigned threads = useCores() ? std::max(1u, std::min(8u, std::thread::hardware_concurrency())) : 1;
    if (threads <= 1 || n < 2000) {
        run(0, n);
        return out;
    }
    std::vector<std::thread> pool;
    size_t step = (n + threads - 1) / threads;
    for (unsigned t = 0; t < threads; ++t) {
        size_t from = t * step, to = std::min(n, from + step);
        if (from < to) pool.emplace_back(run, from, to);
    }
    for (auto& t : pool) t.join();
    return out;
}

/** A unit vector. */
void normalise(float* v) {
    float l = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    if (l > 1e-12f)
        for (int k = 0; k < 3; ++k) v[k] /= l;
}

}  // namespace

std::vector<float> wallThickness(const DisplayMesh& mesh) {
    // Each vertex sampled a tenth of the way in towards the middle of a triangle using it.
    size_t n = mesh.vertexCount();
    std::vector<float> samples(n * 6);
    std::vector<bool> placed(n, false);
    for (size_t t = 0; t + 2 < mesh.indices.size(); t += 3) {
        float mid[3] = {0, 0, 0};
        for (int c = 0; c < 3; ++c)
            for (int k = 0; k < 3; ++k) mid[k] += mesh.positions[mesh.indices[t + size_t(c)] * 3 + size_t(k)] / 3;
        for (int c = 0; c < 3; ++c) {
            uint32_t v = mesh.indices[t + size_t(c)];
            if (placed[v]) continue;
            placed[v] = true;
            for (int k = 0; k < 3; ++k) {
                samples[v * 6 + size_t(k)] = mesh.positions[v * 3 + size_t(k)] * 0.9f + mid[k] * 0.1f;
                samples[v * 6 + 3 + size_t(k)] = mesh.normals[v * 3 + size_t(k)];
            }
        }
    }
    return castAll(mesh, samples);
}

DisplayMesh withThickness(const DisplayMesh& mesh) {
    // Split so there are at most about this many triangles, and none much smaller than needed.
    constexpr double kBudget = 400000;
    double area = 0;
    Box all;
    for (size_t i = 0; i < mesh.vertexCount(); ++i) all.add(&mesh.positions[i * 3]);
    float size = 0;
    for (int k = 0; k < 3; ++k) size = std::max(size, all.hi[k] - all.lo[k]);
    auto at = [&](size_t t, int c) { return &mesh.positions[mesh.indices[t * 3 + size_t(c)] * 3]; };
    size_t triangles = mesh.indices.size() / 3;
    for (size_t t = 0; t < triangles; ++t) {
        const float *a = at(t, 0), *b = at(t, 1), *c = at(t, 2);
        float u[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, v[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        float x[3] = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        area += 0.5 * std::sqrt(double(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]));
    }
    double length = std::max(std::sqrt(2 * area / std::max(kBudget - double(triangles), 1.0)), double(size) / 300);

    DisplayMesh out = mesh;
    out.positions.clear();
    out.normals.clear();
    out.faceOfVertex.clear();
    out.indices.clear();
    for (size_t t = 0; t < triangles; ++t) {
        uint32_t id[3] = {mesh.indices[t * 3], mesh.indices[t * 3 + 1], mesh.indices[t * 3 + 2]};
        float longest = 0;
        for (int e = 0; e < 3; ++e) {
            const float *p = &mesh.positions[id[e] * 3], *q = &mesh.positions[id[(e + 1) % 3] * 3];
            longest = std::max(longest, std::sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2])));
        }
        int n = std::clamp(int(std::ceil(longest / length)), 1, 32);
        // A grid of points over the triangle, row i of n + 1 - i points, by barycentric weights.
        uint32_t first = uint32_t(out.vertexCount());
        auto index = [&](int i, int j) { return first + uint32_t(i * (n + 1) - i * (i - 1) / 2 + j); };
        for (int i = 0; i <= n; ++i)
            for (int j = 0; j <= n - i; ++j) {
                float wb = float(j) / float(n), wc = float(i) / float(n), wa = 1 - wb - wc;
                float nm[3];
                for (int k = 0; k < 3; ++k) {
                    out.positions.push_back(wa * mesh.positions[id[0] * 3 + size_t(k)] + wb * mesh.positions[id[1] * 3 + size_t(k)] +
                                            wc * mesh.positions[id[2] * 3 + size_t(k)]);
                    nm[k] = wa * mesh.normals[id[0] * 3 + size_t(k)] + wb * mesh.normals[id[1] * 3 + size_t(k)] + wc * mesh.normals[id[2] * 3 + size_t(k)];
                }
                normalise(nm);
                out.normals.insert(out.normals.end(), nm, nm + 3);
                out.faceOfVertex.push_back(mesh.faceOfVertex[id[0]]);
            }
        for (int i = 0; i < n; ++i)
            for (int j = 0; j < n - i; ++j) {
                out.indices.insert(out.indices.end(), {index(i, j), index(i, j + 1), index(i + 1, j)});
                if (j + 1 < n - i) out.indices.insert(out.indices.end(), {index(i, j + 1), index(i + 1, j + 1), index(i + 1, j)});
            }
    }
    // Cast against the original triangles: the same surface, fewer to search.
    std::vector<float> samples(out.vertexCount() * 6);
    std::vector<bool> placed(out.vertexCount(), false);
    for (size_t t = 0; t + 2 < out.indices.size(); t += 3) {
        float mid[3] = {0, 0, 0};
        for (int c = 0; c < 3; ++c)
            for (int k = 0; k < 3; ++k) mid[k] += out.positions[out.indices[t + size_t(c)] * 3 + size_t(k)] / 3;
        for (int c = 0; c < 3; ++c) {
            uint32_t v = out.indices[t + size_t(c)];
            if (placed[v]) continue;
            placed[v] = true;
            for (int k = 0; k < 3; ++k) {
                samples[v * 6 + size_t(k)] = out.positions[v * 3 + size_t(k)] * 0.9f + mid[k] * 0.1f;
                samples[v * 6 + 3 + size_t(k)] = out.normals[v * 3 + size_t(k)];
            }
        }
    }
    out.shade = castAll(mesh, samples);
    return out;
}

DisplayMesh withCurvature(const DisplayMesh& mesh, bool smooth) {
    DisplayMesh out = mesh;
    size_t n = mesh.vertexCount();
    // Which vertex each is the same as: itself, or with [smooth] the first in the same place.
    std::vector<uint32_t> same(n);
    if (smooth) {
        std::unordered_map<uint64_t, uint32_t> at;
        auto key = [&](size_t i) {
            uint64_t k = 0;
            for (int c = 0; c < 3; ++c) k = k * 1000003u + uint64_t(int64_t(std::llround(mesh.positions[i * 3 + size_t(c)] * 1e4)));
            return k;
        };
        for (size_t i = 0; i < n; ++i) same[i] = at.emplace(key(i), uint32_t(i)).first->second;
    } else {
        for (size_t i = 0; i < n; ++i) same[i] = uint32_t(i);
    }
    std::vector<float> normal(n * 3, 0.0f);
    if (smooth) {
        // By the area of each triangle round it.
        for (size_t t = 0; t + 2 < mesh.indices.size(); t += 3) {
            const float* a = &mesh.positions[mesh.indices[t] * 3];
            const float* b = &mesh.positions[mesh.indices[t + 1] * 3];
            const float* c = &mesh.positions[mesh.indices[t + 2] * 3];
            float u[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, v[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
            float x[3] = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
            for (int k = 0; k < 3; ++k)
                for (int d = 0; d < 3; ++d) normal[same[mesh.indices[t + size_t(k)]] * 3 + size_t(d)] += x[d];
        }
        for (size_t i = 0; i < n; ++i) normalise(&normal[same[i] * 3]);
    } else {
        normal = mesh.normals;
    }
    // Along each edge, how fast the normal turns for the distance: averaged round each vertex.
    std::vector<float> sum(n, 0.0f);
    std::vector<int> count(n, 0);
    for (size_t t = 0; t + 2 < mesh.indices.size(); t += 3)
        for (int e = 0; e < 3; ++e) {
            uint32_t i = same[mesh.indices[t + size_t(e)]], j = same[mesh.indices[t + size_t((e + 1) % 3)]];
            if (i == j) continue;
            const float *pi = &mesh.positions[i * 3], *pj = &mesh.positions[j * 3];
            const float *ni = &normal[i * 3], *nj = &normal[j * 3];
            float d[3] = {pi[0] - pj[0], pi[1] - pj[1], pi[2] - pj[2]};
            float len2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
            if (len2 < 1e-12f) continue;
            float k = ((ni[0] - nj[0]) * d[0] + (ni[1] - nj[1]) * d[1] + (ni[2] - nj[2]) * d[2]) / len2;
            sum[i] += k; ++count[i];
            sum[j] += k; ++count[j];
        }
    std::vector<float> k(n, 0.0f);
    for (size_t i = 0; i < n; ++i) k[i] = count[i] > 0 ? sum[i] / float(count[i]) : 0.0f;
    // Each edge's estimate is rough on uneven triangles: evened out with the neighbours a few times.
    for (int pass = 0; pass < 4; ++pass) {
        std::vector<float> total(k), many(n, 1.0f);
        for (size_t t = 0; t + 2 < mesh.indices.size(); t += 3)
            for (int e = 0; e < 3; ++e) {
                uint32_t i = same[mesh.indices[t + size_t(e)]], j = same[mesh.indices[t + size_t((e + 1) % 3)]];
                if (i == j) continue;
                total[i] += k[j]; many[i] += 1;
                total[j] += k[i]; many[j] += 1;
            }
        for (size_t i = 0; i < n; ++i) if (same[i] == i) k[i] = total[i] / many[i];
    }
    out.shade.assign(n, 0.0f);
    for (size_t i = 0; i < n; ++i) out.shade[i] = k[same[i]];
    return out;
}

}  // namespace pm
