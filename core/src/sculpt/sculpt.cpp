#include "sculpt/sculpt.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <iterator>
#include <limits>
#include <map>
#include <numeric>
#include <stdexcept>
#include <utility>

#include <zlib.h>

namespace pm {

namespace {

inline void sub(const float* a, const float* b, float* o) { o[0] = a[0] - b[0]; o[1] = a[1] - b[1]; o[2] = a[2] - b[2]; }
inline float dot(const float* a, const float* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
inline void cross(const float* a, const float* b, float* o) {
    o[0] = a[1] * b[2] - a[2] * b[1];
    o[1] = a[2] * b[0] - a[0] * b[2];
    o[2] = a[0] * b[1] - a[1] * b[0];
}
inline float length(const float* a) { return std::sqrt(dot(a, a)); }
inline void normalise(float* a) {
    float l = length(a);
    if (l > 1e-20f) { a[0] /= l; a[1] /= l; a[2] /= l; }
}
inline float distance2(const float* a, const float* b) {
    float d[3];
    sub(a, b, d);
    return dot(d, d);
}

// A smooth bump: 1 in the middle, 0 at the edge, flat at both.
inline float falloff(float t) {
    if (t >= 1) return 0;
    float u = 1 - t * t;
    return u * u;
}

// Across the planes through the origin picked by the bits of m: 1 x, 2 y, 4 z.
inline void mirrored(const float* p, int m, float* o) {
    o[0] = (m & 1) ? -p[0] : p[0];
    o[1] = (m & 2) ? -p[1] : p[1];
    o[2] = (m & 4) ? -p[2] : p[2];
}

bool invert4(const float* m, float* inv) {
    float t[16];
    t[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
    t[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
    t[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
    t[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
    t[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
    t[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
    t[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
    t[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
    t[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
    t[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
    t[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
    t[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
    t[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
    t[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
    t[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
    t[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
    float det = m[0] * t[0] + m[1] * t[4] + m[2] * t[8] + m[3] * t[12];
    if (std::abs(det) < 1e-30f) return false;
    for (int i = 0; i < 16; ++i) inv[i] = t[i] / det;
    return true;
}

// Column-major matrix times (x, y, z, w).
inline void transform(const float* m, const float* v, float* o) {
    for (int r = 0; r < 4; ++r) o[r] = m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3];
}

inline uint32_t spread10(uint32_t x) {
    x &= 0x3ff;
    x = (x | (x << 16)) & 0x030000ff;
    x = (x | (x << 8)) & 0x0300f00f;
    x = (x | (x << 4)) & 0x030c30c3;
    x = (x | (x << 2)) & 0x09249249;
    return x;
}

constexpr int kLeafSize = 8;
constexpr uint32_t kDead = 0;

inline bool dead(const std::array<uint32_t, 3>& t) { return t[0] == t[1]; }

}  // namespace

struct Sculpt::Bvh {
    struct Node {
        float lo[3], hi[3];
        int left = -1, right = -1, parent = -1, leaf = -1;
    };
    std::vector<Node> nodes;
    std::vector<std::vector<uint32_t>> leaves;
    std::vector<int> leafNode;
    std::vector<int> triLeaf;
    std::vector<uint8_t> leafDirty;
    std::vector<int> dirty;
    // Leaves new triangles went into that may now hold too many.
    std::vector<int> grown;

    void markTriangle(uint32_t t) {
        if (t >= triLeaf.size()) return;
        int l = triLeaf[t];
        if (l >= 0 && !leafDirty[size_t(l)]) {
            leafDirty[size_t(l)] = 1;
            dirty.push_back(l);
        }
    }
};

struct Sculpt::Change {
    bool whole = false;
    bool changed = false;
    std::vector<uint32_t> idx;
    std::vector<Vertex> before, after;
    std::vector<Vertex> vertsBefore, vertsAfter;
    std::vector<std::array<uint32_t, 3>> trisBefore, trisAfter;

    size_t bytes() const {
        return idx.size() * 4 + (before.size() + after.size() + vertsBefore.size() + vertsAfter.size()) * sizeof(Vertex) +
               (trisBefore.size() + trisAfter.size()) * 12;
    }
};

Sculpt::Sculpt(const Mesh& mesh, size_t maxTriangles) : bvh_(new Bvh), maxTriangles_(maxTriangles) {
    if (mesh.triangles.empty()) throw std::runtime_error("There's nothing to sculpt");
    reset(mesh);
}

Sculpt::~Sculpt() = default;

namespace {

// Puts points in space order, and triangles by their first point, so what's near on the surface is near
// in the arrays: the GPU reuses more of its work on shared points, and a brush touches few blocks.
void spaceOrder(std::vector<Sculpt::Vertex>& verts, std::vector<std::array<uint32_t, 3>>& tris) {
    size_t n = verts.size();
    float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
    for (const auto& v : verts)
        for (int k = 0; k < 3; ++k) { lo[k] = std::min(lo[k], v.p[k]); hi[k] = std::max(hi[k], v.p[k]); }
    float span = std::max({hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2], 1e-6f});
    std::vector<uint32_t> code(n), order(n);
    for (size_t i = 0; i < n; ++i) {
        uint32_t c[3];
        for (int k = 0; k < 3; ++k) c[k] = uint32_t((verts[i].p[k] - lo[k]) / span * 1023.0f);
        code[i] = spread10(c[0]) | spread10(c[1]) << 1 | spread10(c[2]) << 2;
    }
    std::iota(order.begin(), order.end(), 0u);
    std::sort(order.begin(), order.end(), [&](uint32_t a, uint32_t b) { return code[a] < code[b]; });
    std::vector<uint32_t> where(n);
    for (size_t i = 0; i < n; ++i) where[order[i]] = uint32_t(i);
    std::vector<Sculpt::Vertex> sorted(n);
    for (size_t i = 0; i < n; ++i) sorted[i] = verts[order[i]];
    verts.swap(sorted);
    for (auto& t : tris)
        for (auto& v : t) v = where[v];
    std::sort(tris.begin(), tris.end(), [](const auto& a, const auto& b) {
        return std::min({a[0], a[1], a[2]}) < std::min({b[0], b[1], b[2]});
    });
}

}  // namespace

void Sculpt::reset(const Mesh& mesh) {
    verts_.assign(mesh.vertices.size(), Vertex{});
    for (size_t i = 0; i < mesh.vertices.size(); ++i) {
        const auto& p = mesh.vertices[i];
        verts_[i] = Vertex{{p[0], p[1], p[2]}, {0, 0, 1}, 0};
    }
    tris_.clear();
    tris_.reserve(mesh.triangles.size());
    for (const auto& t : mesh.triangles)
        if (t[0] != t[1] && t[1] != t[2] && t[0] != t[2]) tris_.push_back(t);
    spaceOrder(verts_, tris_);
    relink();
    std::vector<uint32_t> all(verts_.size());
    std::iota(all.begin(), all.end(), 0u);
    normalsAround(all);
}

// Finding things.

void Sculpt::rebuildBvh() {
    Bvh& b = *bvh_;
    b.nodes.assign(1, {});
    b.leaves.clear();
    b.leafNode.clear();
    b.leafDirty.clear();
    b.dirty.clear();
    b.grown.clear();
    b.triLeaf.assign(tris_.size(), -1);
    std::vector<uint32_t> ids;
    ids.reserve(tris_.size());
    for (uint32_t t = 0; t < tris_.size(); ++t)
        if (triAlive_[t]) ids.push_back(t);
    grow(ids, 0);
}

void Sculpt::grow(std::vector<uint32_t>& ids, int root) {
    Bvh& b = *bvh_;
    auto centre = [&](uint32_t t, int k) {
        const auto& tr = tris_[t];
        return verts_[tr[0]].p[k] + verts_[tr[1]].p[k] + verts_[tr[2]].p[k];
    };
    struct Job {
        size_t from, to;
        int node;
    };
    size_t firstNew = b.nodes.size();
    std::vector<Job> jobs{{0, ids.size(), root}};
    while (!jobs.empty()) {
        Job j = jobs.back();
        jobs.pop_back();
        auto* nd = &b.nodes[size_t(j.node)];
        if (j.to - j.from <= size_t(kLeafSize)) {
            int l = int(b.leaves.size());
            b.leaves.emplace_back(ids.begin() + long(j.from), ids.begin() + long(j.to));
            b.leafNode.push_back(j.node);
            b.leafDirty.push_back(0);
            nd->leaf = l;
            nd->left = nd->right = -1;
            for (size_t i = j.from; i < j.to; ++i) b.triLeaf[ids[i]] = l;
            continue;
        }
        float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
        for (size_t i = j.from; i < j.to; ++i)
            for (int k = 0; k < 3; ++k) { lo[k] = std::min(lo[k], centre(ids[i], k)); hi[k] = std::max(hi[k], centre(ids[i], k)); }
        int axis = 0;
        for (int k = 1; k < 3; ++k)
            if (hi[k] - lo[k] > hi[axis] - lo[axis]) axis = k;
        size_t mid = (j.from + j.to) / 2;
        std::nth_element(ids.begin() + long(j.from), ids.begin() + long(mid), ids.begin() + long(j.to),
                         [&](uint32_t a, uint32_t c) { return centre(a, axis) < centre(c, axis); });
        int left = int(b.nodes.size()), right = left + 1;
        b.nodes.resize(b.nodes.size() + 2);
        nd = &b.nodes[size_t(j.node)];
        nd->leaf = -1;
        nd->left = left;
        nd->right = right;
        b.nodes[size_t(left)].parent = b.nodes[size_t(right)].parent = j.node;
        jobs.push_back({mid, j.to, right});
        jobs.push_back({j.from, mid, left});
    }
    // Bounds, children before parents: children were made after their parents, so backwards, then the root.
    auto fit = [&](size_t i) {
        auto& nd = b.nodes[i];
        std::fill(nd.lo, nd.lo + 3, 1e30f);
        std::fill(nd.hi, nd.hi + 3, -1e30f);
        if (nd.leaf >= 0) {
            for (uint32_t t : b.leaves[size_t(nd.leaf)])
                if (triAlive_[t])
                    for (uint32_t v : tris_[t])
                        for (int k = 0; k < 3; ++k) { nd.lo[k] = std::min(nd.lo[k], verts_[v].p[k]); nd.hi[k] = std::max(nd.hi[k], verts_[v].p[k]); }
        } else {
            for (int c : {nd.left, nd.right}) {
                if (c < 0) continue;
                for (int k = 0; k < 3; ++k) { nd.lo[k] = std::min(nd.lo[k], b.nodes[size_t(c)].lo[k]); nd.hi[k] = std::max(nd.hi[k], b.nodes[size_t(c)].hi[k]); }
            }
        }
    };
    for (size_t i = b.nodes.size(); i-- > firstNew;) fit(i);
    fit(size_t(root));
}

void Sculpt::splitGrownLeaves() {
    Bvh& b = *bvh_;
    std::vector<int> grown;
    grown.swap(b.grown);
    std::sort(grown.begin(), grown.end());
    grown.erase(std::unique(grown.begin(), grown.end()), grown.end());
    for (int l : grown) {
        auto& list = b.leaves[size_t(l)];
        // A triangle may be listed again after its slot was used for another.
        std::vector<uint32_t> ids;
        for (uint32_t t : list)
            if (triAlive_[t] && b.triLeaf[t] == l) ids.push_back(t);
        std::sort(ids.begin(), ids.end());
        ids.erase(std::unique(ids.begin(), ids.end()), ids.end());
        if (ids.size() <= size_t(kLeafSize) * 2) {
            list = ids;
            continue;
        }
        int node = b.leafNode[size_t(l)];
        list.clear();
        grow(ids, node);
        // Its parents' boxes are already round all of it, so they stay as they are.
    }
}

bool Sculpt::raycast(const float from[3], const float dir[3], float out[3]) const {
    const Bvh& b = *bvh_;
    if (b.nodes.empty()) return false;
    float best = std::numeric_limits<float>::max();
    float inv[3] = {1 / (std::abs(dir[0]) > 1e-20f ? dir[0] : 1e-20f), 1 / (std::abs(dir[1]) > 1e-20f ? dir[1] : 1e-20f),
                    1 / (std::abs(dir[2]) > 1e-20f ? dir[2] : 1e-20f)};
    std::vector<int> stack{0};
    stack.reserve(64);
    while (!stack.empty()) {
        const auto& nd = b.nodes[size_t(stack.back())];
        stack.pop_back();
        float t0 = 0, t1 = best;
        bool miss = false;
        for (int k = 0; k < 3 && !miss; ++k) {
            float a = (nd.lo[k] - from[k]) * inv[k], c = (nd.hi[k] - from[k]) * inv[k];
            if (a > c) std::swap(a, c);
            t0 = std::max(t0, a);
            t1 = std::min(t1, c);
            miss = t0 > t1;
        }
        if (miss) continue;
        if (nd.leaf < 0) {
            if (nd.left >= 0) stack.push_back(nd.left);
            if (nd.right >= 0) stack.push_back(nd.right);
            continue;
        }
        for (uint32_t t : b.leaves[size_t(nd.leaf)]) {
            if (!triAlive_[t]) continue;
            const float* p0 = verts_[tris_[t][0]].p;
            const float* p1 = verts_[tris_[t][1]].p;
            const float* p2 = verts_[tris_[t][2]].p;
            float e1[3], e2[3], h[3], s[3], q[3];
            sub(p1, p0, e1);
            sub(p2, p0, e2);
            cross(dir, e2, h);
            float a = dot(e1, h);
            if (std::abs(a) < 1e-12f) continue;
            float f = 1 / a;
            sub(from, p0, s);
            float u = f * dot(s, h);
            if (u < 0 || u > 1) continue;
            cross(s, e1, q);
            float v = f * dot(dir, q);
            if (v < 0 || u + v > 1) continue;
            float t2 = f * dot(e2, q);
            if (t2 > 0 && t2 < best) best = t2;
        }
    }
    if (best == std::numeric_limits<float>::max()) return false;
    for (int k = 0; k < 3; ++k) out[k] = from[k] + dir[k] * best;
    return true;
}

void Sculpt::within(const float centre[3], float radius, std::vector<uint32_t>& out) const {
    out.clear();
    const Bvh& b = *bvh_;
    if (b.nodes.empty()) return;
    auto& stamp = const_cast<std::vector<uint32_t>&>(stamp_);
    if (++stampNow_ == 0) {
        std::fill(stamp.begin(), stamp.end(), 0);
        stampNow_ = 1;
    }
    float r2 = radius * radius;
    std::vector<int> stack{0};
    while (!stack.empty()) {
        const auto& nd = b.nodes[size_t(stack.back())];
        stack.pop_back();
        float d2 = 0;
        for (int k = 0; k < 3; ++k) {
            float e = std::max({nd.lo[k] - centre[k], 0.0f, centre[k] - nd.hi[k]});
            d2 += e * e;
        }
        if (d2 > r2) continue;
        if (nd.leaf < 0) {
            if (nd.left >= 0) stack.push_back(nd.left);
            if (nd.right >= 0) stack.push_back(nd.right);
            continue;
        }
        for (uint32_t t : b.leaves[size_t(nd.leaf)]) {
            if (!triAlive_[t]) continue;
            for (uint32_t v : tris_[t]) {
                if (stamp[v] == stampNow_) continue;
                stamp[v] = stampNow_;
                if (distance2(verts_[v].p, centre) <= r2) out.push_back(v);
            }
        }
    }
}

// The camera.

void Sculpt::setCamera(const float viewProjection[16], int width, int height) {
    std::copy(viewProjection, viewProjection + 16, vp_);
    width_ = std::max(width, 1);
    height_ = std::max(height, 1);
    invert4(vp_, inv_);
}

void Sculpt::ray(float x, float y, float from[3], float dir[3]) const {
    float nx = 2 * x / float(width_) - 1, ny = 1 - 2 * y / float(height_);
    float a[4] = {nx, ny, -1, 1}, b[4] = {nx, ny, 1, 1}, pa[4], pb[4];
    transform(inv_, a, pa);
    transform(inv_, b, pb);
    for (int k = 0; k < 3; ++k) {
        from[k] = pa[k] / pa[3];
        dir[k] = pb[k] / pb[3] - from[k];
    }
    normalise(dir);
}

float Sculpt::worldRadius(const float at[3], float pixels) const {
    float p[4] = {at[0], at[1], at[2], 1}, c[4];
    transform(vp_, p, c);
    if (std::abs(c[3]) < 1e-20f) return 1;
    float ndc[4] = {c[0] / c[3] + 2 * pixels / float(width_), c[1] / c[3], c[2] / c[3], 1}, w[4];
    transform(inv_, ndc, w);
    float q[3] = {w[0] / w[3], w[1] / w[3], w[2] / w[3]};
    return std::sqrt(distance2(q, at));
}

bool Sculpt::onViewPlane(float x, float y, const float through[3], float out[3]) const {
    float from[3], dir[3], cf[3], cd[3];
    ray(x, y, from, dir);
    // Facing the camera: the direction it looks in, through the middle of the view.
    ray(float(width_) / 2, float(height_) / 2, cf, cd);
    float denom = dot(dir, cd);
    if (std::abs(denom) < 1e-9f) return false;
    float d[3];
    sub(through, from, d);
    float t = dot(d, cd) / denom;
    for (int k = 0; k < 3; ++k) out[k] = from[k] + dir[k] * t;
    return true;
}

bool Sculpt::hit(float x, float y) const {
    float from[3], dir[3], at[3];
    ray(x, y, from, dir);
    return raycast(from, dir, at);
}

// Strokes.

bool Sculpt::begin(float x, float y, float pressure, const BrushSettings& settings) {
    if (stroking_) end();
    float from[3], dir[3], at[3];
    ray(x, y, from, dir);
    if (!raycast(from, dir, at)) return false;
    s_ = settings;
    stroking_ = true;
    topologyChanged_ = false;
    ++strokeNo_;
    startChange(s_.dynamic && s_.brush != Brush::Mask);
    lastX_ = x;
    lastY_ = y;
    lastPressure_ = pressure;
    travelled_ = 0;
    layerStart_.clear();
    held_.clear();
    float radius = worldRadius(at, s_.radius * (s_.pressureSize ? std::max(pressure, 0.1f) : 1.0f));
    if (s_.brush == Brush::Grab) {
        std::copy(at, at + 3, grabFrom_);
        std::vector<uint32_t> near;
        for (int m = 0; m < 8; ++m) {
            if (m & ~s_.mirror) continue;
            float c[3];
            mirrored(at, m, c);
            within(c, radius, near);
            for (uint32_t v : near) {
                float w = falloff(std::sqrt(distance2(verts_[v].p, c)) / radius) * (1 - verts_[v].mask);
                if (w <= 0) continue;
                held_.push_back({v, w, {verts_[v].p[0], verts_[v].p[1], verts_[v].p[2]}, m});
            }
        }
        return true;
    }
    if (s_.brush == Brush::Pull) {
        std::copy(at, at + 3, pullAt_);
        return true;
    }
    dab(x, y, pressure);
    return true;
}

void Sculpt::move(float x, float y, float pressure) {
    if (!stroking_) return;
    if (s_.brush == Brush::Grab) {
        grabTo(x, y);
        lastX_ = x;
        lastY_ = y;
        return;
    }
    // Dabs a set share of the brush apart, whatever the pointer's speed.
    float spacing = std::max(1.5f, s_.radius * 0.15f);
    float dx = x - lastX_, dy = y - lastY_;
    float len = std::sqrt(dx * dx + dy * dy);
    if (len <= 0) return;
    float done = 0;
    float px = lastX_, py = lastY_;
    while (travelled_ + (len - done) >= spacing) {
        float step = spacing - travelled_;
        done += step;
        travelled_ = 0;
        float t = done / len;
        float nx = lastX_ + dx * t, ny = lastY_ + dy * t, np = lastPressure_ + (pressure - lastPressure_) * t;
        if (s_.brush == Brush::Pull) {
            float a[3], b[3];
            if (onViewPlane(px, py, pullAt_, a) && onViewPlane(nx, ny, pullAt_, b)) {
                float delta[3];
                sub(b, a, delta);
                float radius = worldRadius(pullAt_, s_.radius * (s_.pressureSize ? std::max(np, 0.1f) : 1.0f));
                std::vector<uint32_t> movedAll;
                for (int m = 0; m < 8; ++m) {
                    if (m & ~s_.mirror) continue;
                    float c[3], d[3];
                    mirrored(pullAt_, m, c);
                    mirrored(delta, m, d);
                    apply(c, nullptr, radius, s_.strength * (s_.pressureStrength ? np : 1.0f), m, d);
                }
                for (int k = 0; k < 3; ++k) pullAt_[k] += delta[k];
            }
        } else {
            dab(nx, ny, np);
        }
        px = nx;
        py = ny;
    }
    travelled_ += len - done;
    lastX_ = x;
    lastY_ = y;
    lastPressure_ = pressure;
}

void Sculpt::end() {
    if (!stroking_) return;
    if (s_.brush == Brush::Grab && s_.dynamic && !held_.empty()) {
        std::vector<uint32_t> region;
        for (const auto& h : held_) region.push_back(h.v);
        float edge = worldRadius(grabFrom_, s_.radius) * (0.3f - 0.26f * s_.detail);
        refine(region, edge * 4 / 3, edge * 0.25f);
    }
    if (topologyChanged_) {
        // Packed and put back in order when many slots are empty or a lot was added out of order, which draws slower.
        if (freeTris_.size() * 4 > tris_.size() || tris_.size() * 10 > orderedTriangles_ * 13) compact();
        else splitGrownLeaves();
    }
    finishChange();
    stroking_ = false;
    held_.clear();
}

void Sculpt::grabTo(float x, float y) {
    float q[3];
    if (!onViewPlane(x, y, grabFrom_, q)) return;
    float delta[3];
    sub(q, grabFrom_, delta);
    std::vector<uint32_t> moved;
    for (const auto& h : held_) {
        float d[3];
        mirrored(delta, h.mirror, d);
        record(h.v);
        for (int k = 0; k < 3; ++k) verts_[h.v].p[k] = h.start[k] + d[k] * h.w;
        moved.push_back(h.v);
    }
    if (open_) open_->changed = true;
    normalsAround(moved);
}

void Sculpt::dab(float x, float y, float pressure) {
    float from[3], dir[3], at[3];
    ray(x, y, from, dir);
    if (!raycast(from, dir, at)) return;
    float radius = worldRadius(at, s_.radius * (s_.pressureSize ? std::max(pressure, 0.1f) : 1.0f));
    float strength = s_.strength * (s_.pressureStrength ? pressure : 1.0f);
    for (int m = 0; m < 8; ++m) {
        if (m & ~s_.mirror) continue;
        float c[3];
        mirrored(at, m, c);
        apply(c, nullptr, radius, strength, m, nullptr);
    }
}

void Sculpt::apply(const float centre[3], const float*, float radius, float strength, int, const float delta[3]) {
    std::vector<uint32_t> near;
    within(centre, radius, near);
    if (near.empty()) return;
    // The surface's way out and middle under the brush, weighted towards the middle.
    float n[3] = {0, 0, 0}, mid[3] = {0, 0, 0}, total = 0;
    std::vector<float> w(near.size());
    for (size_t i = 0; i < near.size(); ++i) {
        const Vertex& v = verts_[near[i]];
        w[i] = falloff(std::sqrt(distance2(v.p, centre)) / radius);
        for (int k = 0; k < 3; ++k) { n[k] += v.n[k] * w[i]; mid[k] += v.p[k] * w[i]; }
        total += w[i];
    }
    if (total <= 0) return;
    normalise(n);
    for (int k = 0; k < 3; ++k) mid[k] /= total;
    float sign = s_.invert ? -1.0f : 1.0f;
    Brush brush = s_.brush;
    std::vector<uint32_t> moved;
    moved.reserve(near.size());

    if (brush == Brush::Mask) {
        for (size_t i = 0; i < near.size(); ++i) {
            Vertex& v = verts_[near[i]];
            record(near[i]);
            v.mask = std::clamp(v.mask + w[i] * strength * 0.5f * sign, 0.0f, 1.0f);
            markVertex(near[i]);
        }
        if (open_) open_->changed = true;
        return;
    }

    // Smooth works from the neighbours as they were, so the order points are taken in doesn't matter.
    std::vector<std::array<float, 3>> target;
    if (brush == Brush::Smooth) {
        target.resize(near.size());
        for (size_t i = 0; i < near.size(); ++i) {
            uint32_t v = near[i];
            float sum[3] = {0, 0, 0};
            int count = 0;
            for (uint32_t t : vertTris_[v])
                for (uint32_t o : tris_[t]) {
                    if (o == v) continue;
                    for (int k = 0; k < 3; ++k) sum[k] += verts_[o].p[k];
                    ++count;
                }
            for (int k = 0; k < 3; ++k) target[i][size_t(k)] = count ? sum[k] / float(count) : verts_[v].p[k];
        }
    }
    for (size_t i = 0; i < near.size(); ++i) {
        uint32_t vi = near[i];
        Vertex& v = verts_[vi];
        float f = w[i] * (1 - v.mask);
        if (f <= 0) continue;
        // Leave alone the far side of a thin part.
        if (brush != Brush::Smooth && brush != Brush::Pull && dot(v.n, n) < -0.1f) continue;
        float d[3] = {0, 0, 0};
        switch (brush) {
            case Brush::Draw:
                for (int k = 0; k < 3; ++k) d[k] = n[k] * radius * 0.06f * strength * sign * f;
                break;
            case Brush::Inflate:
                for (int k = 0; k < 3; ++k) d[k] = v.n[k] * radius * 0.06f * strength * sign * f;
                break;
            case Brush::Clay: {
                // Up to a plane a little above the surface, so it builds up flat like added clay.
                float plane[3];
                for (int k = 0; k < 3; ++k) plane[k] = mid[k] + n[k] * radius * 0.08f * sign;
                float off[3];
                sub(v.p, plane, off);
                float h = dot(off, n);
                if (h * sign < 0)
                    for (int k = 0; k < 3; ++k) d[k] = -n[k] * h * f * std::min(1.0f, strength * 0.6f);
                break;
            }
            case Brush::Flatten: {
                float off[3];
                sub(v.p, mid, off);
                float h = dot(off, n);
                for (int k = 0; k < 3; ++k) d[k] = -n[k] * h * f * std::min(1.0f, strength * 0.5f) * sign;
                break;
            }
            case Brush::Smooth:
                for (int k = 0; k < 3; ++k) d[k] = (target[i][size_t(k)] - v.p[k]) * f * std::min(1.0f, strength * 0.9f);
                break;
            case Brush::Pinch:
            case Brush::Crease: {
                // Towards the middle, across the surface.
                float in[3];
                sub(centre, v.p, in);
                float up = dot(in, n);
                for (int k = 0; k < 3; ++k) in[k] -= n[k] * up;
                float pull = brush == Brush::Pinch ? 0.15f * sign : 0.2f;
                for (int k = 0; k < 3; ++k) d[k] = in[k] * f * strength * pull;
                if (brush == Brush::Crease) {
                    // A groove, sharper in the middle; inverted, a ridge.
                    float g = f * f;
                    for (int k = 0; k < 3; ++k) d[k] -= n[k] * radius * 0.05f * strength * sign * g;
                }
                break;
            }
            case Brush::Layer: {
                if (layerStart_.size() < verts_.size() * 3) layerStart_.resize(verts_.size() * 3, std::numeric_limits<float>::quiet_NaN());
                float* s0 = &layerStart_[size_t(vi) * 3];
                if (std::isnan(s0[0])) std::copy(v.p, v.p + 3, s0);
                float off[3];
                sub(v.p, s0, off);
                float now = dot(off, n), goal = radius * 0.15f * strength * sign;
                if (std::abs(now) < std::abs(goal))
                    for (int k = 0; k < 3; ++k) d[k] = n[k] * (goal - now) * f * 0.5f;
                break;
            }
            case Brush::Pull:
                if (delta)
                    for (int k = 0; k < 3; ++k) d[k] = delta[k] * f;
                break;
            case Brush::Grab:
            case Brush::Mask:
                break;
        }
        if (d[0] == 0 && d[1] == 0 && d[2] == 0) continue;
        record(vi);
        for (int k = 0; k < 3; ++k) v.p[k] += d[k];
        moved.push_back(vi);
    }
    if (moved.empty()) return;
    if (open_) open_->changed = true;
    normalsAround(moved);
    // Detail is added where the brush needs it; only slivers far smaller are joined up, so a mesh made finer stays so.
    if (s_.dynamic) {
        float edge = radius * (0.3f - 0.26f * s_.detail);
        refine(near, edge * 4 / 3, edge * 0.25f);
    }
}

// Detail.

uint32_t Sculpt::newVertex() {
    if (!freeVerts_.empty()) {
        uint32_t v = freeVerts_.back();
        freeVerts_.pop_back();
        return v;
    }
    uint32_t v = uint32_t(verts_.size());
    verts_.push_back(Vertex{});
    vertTris_.emplace_back();
    stamp_.push_back(0);
    touched_.push_back(0);
    if (!layerStart_.empty()) layerStart_.resize(verts_.size() * 3, std::numeric_limits<float>::quiet_NaN());
    if (dirtyVerts_.size() <= v / kBlock) dirtyVerts_.resize(v / kBlock + 1, 0);
    return v;
}

uint32_t Sculpt::newTriangle(const std::array<uint32_t, 3>& t) {
    uint32_t id;
    if (!freeTris_.empty()) {
        id = freeTris_.back();
        freeTris_.pop_back();
        tris_[id] = t;
        triAlive_[id] = 1;
    } else {
        id = uint32_t(tris_.size());
        tris_.push_back(t);
        triAlive_.push_back(1);
        bvh_->triLeaf.push_back(-1);
        if (dirtyTris_.size() <= id / kBlock) dirtyTris_.resize(id / kBlock + 1, 0);
    }
    for (uint32_t v : t) vertTris_[v].push_back(id);
    markTriangle(id);
    topologyChanged_ = true;
    return id;
}

void Sculpt::killTriangle(uint32_t t) {
    for (uint32_t v : tris_[t]) {
        auto& list = vertTris_[v];
        list.erase(std::remove(list.begin(), list.end(), t), list.end());
    }
    bvh_->markTriangle(t);
    tris_[t] = {kDead, kDead, kDead};
    triAlive_[t] = 0;
    if (t < bvh_->triLeaf.size()) bvh_->triLeaf[t] = -1;
    freeTris_.push_back(t);
    markTriangle(t);
    topologyChanged_ = true;
}

void Sculpt::setTriangle(uint32_t t, const std::array<uint32_t, 3>& v) {
    for (uint32_t old : tris_[t])
        if (std::find(v.begin(), v.end(), old) == v.end()) {
            auto& list = vertTris_[old];
            list.erase(std::remove(list.begin(), list.end(), t), list.end());
        }
    for (uint32_t nv : v)
        if (std::find(tris_[t].begin(), tris_[t].end(), nv) == tris_[t].end()) vertTris_[nv].push_back(t);
    tris_[t] = v;
    markTriangle(t);
    bvh_->markTriangle(t);
    topologyChanged_ = true;
}

// The edge from a to b in triangle t, turned to start there: {a, b, other}. False if t doesn't run a to b.
static bool runs(const std::array<uint32_t, 3>& t, uint32_t a, uint32_t b, uint32_t& other) {
    for (int i = 0; i < 3; ++i)
        if (t[size_t(i)] == a && t[size_t((i + 1) % 3)] == b) {
            other = t[size_t((i + 2) % 3)];
            return true;
        }
    return false;
}

bool Sculpt::splitEdge(uint32_t a, uint32_t b) {
    if (triangleCount() + 2 > maxTriangles_) return false;
    uint32_t t1 = UINT32_MAX, t2 = UINT32_MAX, c = 0, d = 0;
    int shared = 0;
    for (uint32_t t : vertTris_[a]) {
        uint32_t o;
        if (runs(tris_[t], a, b, o)) { t1 = t; c = o; ++shared; }
        else if (runs(tris_[t], b, a, o)) { t2 = t; d = o; ++shared; }
    }
    if (shared != 2 || t1 == UINT32_MAX || t2 == UINT32_MAX) return false;
    uint32_t m = newVertex();
    Vertex& vm = verts_[m];
    const Vertex &va = verts_[a], &vb = verts_[b];
    for (int k = 0; k < 3; ++k) {
        vm.p[k] = (va.p[k] + vb.p[k]) / 2;
        vm.n[k] = va.n[k] + vb.n[k];
    }
    normalise(vm.n);
    vm.mask = (va.mask + vb.mask) / 2;
    if (!layerStart_.empty()) {
        for (int k = 0; k < 3; ++k) {
            float sa = layerStart_[size_t(a) * 3 + size_t(k)], sb = layerStart_[size_t(b) * 3 + size_t(k)];
            layerStart_[size_t(m) * 3 + size_t(k)] = std::isnan(sa) || std::isnan(sb) ? std::numeric_limits<float>::quiet_NaN() : (sa + sb) / 2;
        }
    }
    markVertex(m);
    int leaf1 = t1 < bvh_->triLeaf.size() ? bvh_->triLeaf[t1] : -1;
    int leaf2 = t2 < bvh_->triLeaf.size() ? bvh_->triLeaf[t2] : -1;
    setTriangle(t1, {a, m, c});
    setTriangle(t2, {b, m, d});
    uint32_t t3 = newTriangle({m, b, c});
    uint32_t t4 = newTriangle({m, a, d});
    // Into the boxes their halves were in, which are made to fit at the next refit.
    for (auto [t, l] : {std::pair<uint32_t, int>{t3, leaf1}, {t4, leaf2}}) {
        if (l < 0) continue;
        bvh_->leaves[size_t(l)].push_back(t);
        bvh_->triLeaf[t] = l;
        bvh_->markTriangle(t);
        if (bvh_->leaves[size_t(l)].size() > size_t(kLeafSize) * 4) bvh_->grown.push_back(l);
    }
    return true;
}

void Sculpt::faceNormal(uint32_t t, float out[3]) const {
    float e1[3], e2[3];
    sub(verts_[tris_[t][1]].p, verts_[tris_[t][0]].p, e1);
    sub(verts_[tris_[t][2]].p, verts_[tris_[t][0]].p, e2);
    cross(e1, e2, out);
}

bool Sculpt::collapseEdge(uint32_t a, uint32_t b) {
    if (triangleCount() <= 12) return false;
    uint32_t t1 = UINT32_MAX, t2 = UINT32_MAX, c = 0, d = 0;
    for (uint32_t t : vertTris_[a]) {
        uint32_t o;
        if (runs(tris_[t], a, b, o)) { t1 = t; c = o; }
        else if (runs(tris_[t], b, a, o)) { t2 = t; d = o; }
    }
    if (t1 == UINT32_MAX || t2 == UINT32_MAX || c == d) return false;
    // Only the two across the edge may be neighbours of both, or the surface would fold or pinch.
    std::vector<uint32_t> na, nb;
    for (uint32_t t : vertTris_[a])
        for (uint32_t v : tris_[t])
            if (v != a) na.push_back(v);
    for (uint32_t t : vertTris_[b])
        for (uint32_t v : tris_[t])
            if (v != b) nb.push_back(v);
    std::sort(na.begin(), na.end());
    na.erase(std::unique(na.begin(), na.end()), na.end());
    std::sort(nb.begin(), nb.end());
    nb.erase(std::unique(nb.begin(), nb.end()), nb.end());
    std::vector<uint32_t> both;
    std::set_intersection(na.begin(), na.end(), nb.begin(), nb.end(), std::back_inserter(both));
    if (both.size() != 2) return false;
    if (vertTris_[c].size() <= 3 || vertTris_[d].size() <= 3) return false;
    float mid[3];
    for (int k = 0; k < 3; ++k) mid[k] = (verts_[a].p[k] + verts_[b].p[k]) / 2;
    // No triangle may turn over.
    for (uint32_t v : {a, b})
        for (uint32_t t : vertTris_[v]) {
            if (t == t1 || t == t2) continue;
            float before[3], after[3], p[3][3];
            faceNormal(t, before);
            for (int i = 0; i < 3; ++i) {
                uint32_t w = tris_[t][size_t(i)];
                const float* src = (w == a || w == b) ? mid : verts_[w].p;
                std::copy(src, src + 3, p[i]);
            }
            float e1[3], e2[3];
            sub(p[1], p[0], e1);
            sub(p[2], p[0], e2);
            cross(e1, e2, after);
            if (dot(before, after) <= 0.2f * length(before) * length(after)) return false;
        }
    record(a);
    record(b);
    killTriangle(t1);
    killTriangle(t2);
    std::vector<uint32_t> moving = vertTris_[b];
    for (uint32_t t : moving) {
        auto nt = tris_[t];
        for (auto& v : nt)
            if (v == b) v = a;
        setTriangle(t, nt);
    }
    Vertex& va = verts_[a];
    std::copy(mid, mid + 3, va.p);
    va.mask = (va.mask + verts_[b].mask) / 2;
    vertTris_[b].clear();
    freeVerts_.push_back(b);
    markVertex(a);
    return true;
}

void Sculpt::refine(const std::vector<uint32_t>& region, float longest, float shortest) {
    if (longest <= 0) return;
    float long2 = longest * longest, short2 = shortest * shortest;
    std::vector<uint32_t> verts = region;
    for (int pass = 0; pass < 3; ++pass) {
        // Edges round the region, longest first.
        std::vector<std::pair<float, std::pair<uint32_t, uint32_t>>> edges;
        for (uint32_t v : verts)
            for (uint32_t t : vertTris_[v])
                for (int i = 0; i < 3; ++i) {
                    uint32_t p = tris_[t][size_t(i)], q = tris_[t][size_t((i + 1) % 3)];
                    if (p > q) continue;  // Each edge once: the triangle running it low to high.
                    float l2 = distance2(verts_[p].p, verts_[q].p);
                    if (l2 > long2) edges.push_back({l2, {p, q}});
                }
        if (edges.empty()) break;
        std::sort(edges.begin(), edges.end(), [](const auto& x, const auto& y) { return x.first > y.first; });
        edges.erase(std::unique(edges.begin(), edges.end(), [](const auto& x, const auto& y) { return x.second == y.second; }), edges.end());
        bool any = false;
        for (const auto& e : edges) {
            uint32_t p = e.second.first, q = e.second.second;
            if (vertTris_[p].empty() || distance2(verts_[p].p, verts_[q].p) <= long2) continue;
            if (splitEdge(p, q)) any = true;
        }
        if (!any) break;
        // The new points are the region's neighbours now, found through its triangles.
        std::vector<uint32_t> next;
        for (uint32_t v : verts)
            for (uint32_t t : vertTris_[v])
                for (uint32_t o : tris_[t]) next.push_back(o);
        std::sort(next.begin(), next.end());
        next.erase(std::unique(next.begin(), next.end()), next.end());
        verts.swap(next);
    }
    // Short edges joined up.
    if (short2 > 0) {
        std::vector<std::pair<uint32_t, uint32_t>> shorts;
        for (uint32_t v : verts)
            for (uint32_t t : vertTris_[v])
                for (int i = 0; i < 3; ++i) {
                    uint32_t p = tris_[t][size_t(i)], q = tris_[t][size_t((i + 1) % 3)];
                    if (p < q && distance2(verts_[p].p, verts_[q].p) < short2) shorts.push_back({p, q});
                }
        std::sort(shorts.begin(), shorts.end());
        shorts.erase(std::unique(shorts.begin(), shorts.end()), shorts.end());
        for (auto [p, q] : shorts) {
            if (vertTris_[p].empty() || vertTris_[q].empty()) continue;
            if (distance2(verts_[p].p, verts_[q].p) >= short2) continue;
            collapseEdge(p, q);
        }
    }
    if (!topologyChanged_) return;
    // The new points eased across the surface into place, then normals for all that changed.
    std::vector<uint32_t> live;
    for (uint32_t v : verts)
        if (!vertTris_[v].empty()) live.push_back(v);
    relax(live);
    normalsAround(live);
}

void Sculpt::relax(const std::vector<uint32_t>& region) {
    std::vector<std::array<float, 3>> goal(region.size());
    for (size_t i = 0; i < region.size(); ++i) {
        uint32_t v = region[i];
        float sum[3] = {0, 0, 0};
        int count = 0;
        for (uint32_t t : vertTris_[v])
            for (uint32_t o : tris_[t]) {
                if (o == v) continue;
                for (int k = 0; k < 3; ++k) sum[k] += verts_[o].p[k];
                ++count;
            }
        if (!count) {
            for (int k = 0; k < 3; ++k) goal[i][size_t(k)] = verts_[v].p[k];
            continue;
        }
        float d[3];
        for (int k = 0; k < 3; ++k) d[k] = sum[k] / float(count) - verts_[v].p[k];
        // Across the surface only, so the shape stays.
        const float* n = verts_[v].n;
        float up = dot(d, n);
        for (int k = 0; k < 3; ++k) goal[i][size_t(k)] = verts_[v].p[k] + (d[k] - n[k] * up) * 0.5f;
    }
    for (size_t i = 0; i < region.size(); ++i) {
        record(region[i]);
        std::copy(goal[i].begin(), goal[i].end(), verts_[region[i]].p);
        markVertex(region[i]);
    }
}

// Normals and the GPU.

void Sculpt::normalsAround(const std::vector<uint32_t>& moved) {
    if (++stampNow_ == 0) {
        std::fill(stamp_.begin(), stamp_.end(), 0);
        stampNow_ = 1;
    }
    std::vector<uint32_t> verts;
    for (uint32_t v : moved) {
        for (uint32_t t : vertTris_[v]) {
            bvh_->markTriangle(t);
            for (uint32_t o : tris_[t])
                if (stamp_[o] != stampNow_) {
                    stamp_[o] = stampNow_;
                    verts.push_back(o);
                }
        }
        markVertex(v);
    }
    for (uint32_t v : verts) {
        float n[3] = {0, 0, 0}, f[3];
        for (uint32_t t : vertTris_[v]) {
            faceNormal(t, f);
            for (int k = 0; k < 3; ++k) n[k] += f[k];
        }
        normalise(n);
        std::copy(n, n + 3, verts_[v].n);
        markVertex(v);
    }
    // Boxes round what moved.
    Bvh& b = *bvh_;
    for (int l : b.dirty) {
        b.leafDirty[size_t(l)] = 0;
        int node = b.leafNode[size_t(l)];
        auto& nd = b.nodes[size_t(node)];
        std::fill(nd.lo, nd.lo + 3, 1e30f);
        std::fill(nd.hi, nd.hi + 3, -1e30f);
        for (uint32_t t : b.leaves[size_t(l)]) {
            if (!triAlive_[t]) continue;
            for (uint32_t v : tris_[t])
                for (int k = 0; k < 3; ++k) { nd.lo[k] = std::min(nd.lo[k], verts_[v].p[k]); nd.hi[k] = std::max(nd.hi[k], verts_[v].p[k]); }
        }
        for (int p = nd.parent; p >= 0; p = b.nodes[size_t(p)].parent) {
            auto& pn = b.nodes[size_t(p)];
            std::fill(pn.lo, pn.lo + 3, 1e30f);
            std::fill(pn.hi, pn.hi + 3, -1e30f);
            for (int c : {pn.left, pn.right}) {
                if (c < 0) continue;
                for (int k = 0; k < 3; ++k) { pn.lo[k] = std::min(pn.lo[k], b.nodes[size_t(c)].lo[k]); pn.hi[k] = std::max(pn.hi[k], b.nodes[size_t(c)].hi[k]); }
            }
        }
    }
    b.dirty.clear();
}

void Sculpt::markVertex(uint32_t v) {
    if (dirtyVerts_.size() <= v / kBlock) dirtyVerts_.resize(v / kBlock + 1, 0);
    dirtyVerts_[v / kBlock] = 1;
}

void Sculpt::markTriangle(uint32_t t) {
    if (dirtyTris_.size() <= t / kBlock) dirtyTris_.resize(t / kBlock + 1, 0);
    dirtyTris_[t / kBlock] = 1;
}

Sculpt::Dirty Sculpt::takeDirty() {
    Dirty d;
    d.all = dirtyAll_;
    dirtyAll_ = false;
    for (uint32_t i = 0; i < dirtyVerts_.size(); ++i)
        if (dirtyVerts_[i]) { d.vertexBlocks.push_back(i); dirtyVerts_[i] = 0; }
    for (uint32_t i = 0; i < dirtyTris_.size(); ++i)
        if (dirtyTris_[i]) { d.triangleBlocks.push_back(i); dirtyTris_[i] = 0; }
    return d;
}

// Undo.

void Sculpt::startChange(bool wholeMesh) {
    open_.reset(new Change);
    open_->whole = wholeMesh;
    if (wholeMesh) {
        open_->vertsBefore = verts_;
        open_->trisBefore = tris_;
    }
}

void Sculpt::record(uint32_t v) {
    if (!open_ || open_->whole) return;
    if (v >= touched_.size()) touched_.resize(v + 1, 0);
    if (touched_[v] == strokeNo_) return;
    touched_[v] = strokeNo_;
    open_->idx.push_back(v);
    open_->before.push_back(verts_[v]);
}

void Sculpt::finishChange() {
    if (!open_) return;
    std::unique_ptr<Change> c = std::move(open_);
    if (!c->changed && !topologyChanged_) return;
    if (c->whole) {
        c->vertsAfter = verts_;
        c->trisAfter = tris_;
    } else {
        c->after.reserve(c->idx.size());
        for (uint32_t v : c->idx) c->after.push_back(verts_[v]);
    }
    undo_.push_back(std::move(c));
    redo_.clear();
    trimUndo();
}

void Sculpt::trimUndo() {
    size_t total = 0;
    for (const auto& c : undo_) total += c->bytes();
    // About 100 bytes for each triangle the device may sculpt: 50 MB on a slow tablet, more on a desktop.
    const size_t most = std::clamp<size_t>(maxTriangles_ * 100, size_t(48) << 20, size_t(320) << 20);
    while (undo_.size() > 1 && total > most) {
        total -= undo_.front()->bytes();
        undo_.erase(undo_.begin());
    }
}

void Sculpt::restore(const Change& c, bool after) {
    if (c.whole) {
        verts_ = after ? c.vertsAfter : c.vertsBefore;
        tris_ = after ? c.trisAfter : c.trisBefore;
        relink();
        return;
    }
    const auto& to = after ? c.after : c.before;
    std::vector<uint32_t> moved;
    for (size_t i = 0; i < c.idx.size(); ++i) {
        verts_[c.idx[i]] = to[i];
        moved.push_back(c.idx[i]);
    }
    normalsAround(moved);
}

void Sculpt::forget() {
    undo_.clear();
    redo_.clear();
}

void Sculpt::relink() {
    triAlive_.assign(tris_.size(), 0);
    vertTris_.assign(verts_.size(), {});
    freeTris_.clear();
    freeVerts_.clear();
    for (uint32_t t = 0; t < tris_.size(); ++t) {
        if (dead(tris_[t])) { freeTris_.push_back(t); continue; }
        triAlive_[t] = 1;
        for (uint32_t v : tris_[t]) vertTris_[v].push_back(t);
    }
    for (uint32_t v = 0; v < verts_.size(); ++v)
        if (vertTris_[v].empty()) freeVerts_.push_back(v);
    stamp_.assign(verts_.size(), 0);
    touched_.assign(verts_.size(), 0);
    layerStart_.clear();
    rebuildBvh();
    dirtyVerts_.assign(verts_.size() / kBlock + 1, 0);
    dirtyTris_.assign(tris_.size() / kBlock + 1, 0);
    dirtyAll_ = true;
    orderedTriangles_ = tris_.size();
}

void Sculpt::compact() {
    std::vector<uint32_t> where(verts_.size(), UINT32_MAX);
    std::vector<Vertex> verts;
    std::vector<std::array<uint32_t, 3>> tris;
    verts.reserve(verts_.size() - freeVerts_.size());
    tris.reserve(triangleCount());
    for (uint32_t t = 0; t < tris_.size(); ++t) {
        if (!triAlive_[t]) continue;
        std::array<uint32_t, 3> r{};
        for (int i = 0; i < 3; ++i) {
            uint32_t v = tris_[t][size_t(i)];
            if (where[v] == UINT32_MAX) {
                where[v] = uint32_t(verts.size());
                verts.push_back(verts_[v]);
            }
            r[size_t(i)] = where[v];
        }
        tris.push_back(r);
    }
    spaceOrder(verts, tris);
    verts_.swap(verts);
    tris_.swap(tris);
    relink();
}

bool Sculpt::undo() {
    if (stroking_ || undo_.empty()) return false;
    std::unique_ptr<Change> c = std::move(undo_.back());
    undo_.pop_back();
    restore(*c, false);
    redo_.push_back(std::move(c));
    return true;
}

bool Sculpt::redo() {
    if (stroking_ || redo_.empty()) return false;
    std::unique_ptr<Change> c = std::move(redo_.back());
    redo_.pop_back();
    restore(*c, true);
    undo_.push_back(std::move(c));
    return true;
}

void Sculpt::clearMask() {
    ++strokeNo_;
    startChange(false);
    for (uint32_t v = 0; v < verts_.size(); ++v)
        if (verts_[v].mask != 0) {
            record(v);
            verts_[v].mask = 0;
            markVertex(v);
            open_->changed = true;
        }
    finishChange();
}

void Sculpt::invertMask() {
    ++strokeNo_;
    startChange(false);
    for (uint32_t v = 0; v < verts_.size(); ++v) {
        if (vertTris_[v].empty()) continue;
        record(v);
        verts_[v].mask = 1 - verts_[v].mask;
        markVertex(v);
    }
    open_->changed = true;
    finishChange();
}

void Sculpt::evenOut(float edge) {
    if (stroking_ || edge <= 0) return;
    ++strokeNo_;
    topologyChanged_ = false;
    startChange(true);
    for (int pass = 0; pass < 4; ++pass) {
        std::vector<uint32_t> all;
        for (uint32_t v = 0; v < verts_.size(); ++v)
            if (!vertTris_[v].empty()) all.push_back(v);
        refine(all, edge * 4 / 3, edge * 0.6f);
        all.clear();
        for (uint32_t v = 0; v < verts_.size(); ++v)
            if (!vertTris_[v].empty()) all.push_back(v);
        relax(all);
        normalsAround(all);
    }
    compact();
    open_->changed = true;
    finishChange();
}

// Out.

Mesh Sculpt::mesh() const {
    Mesh m;
    std::vector<uint32_t> where(verts_.size(), UINT32_MAX);
    for (uint32_t t = 0; t < tris_.size(); ++t) {
        if (!triAlive_[t]) continue;
        std::array<uint32_t, 3> r{};
        for (int i = 0; i < 3; ++i) {
            uint32_t v = tris_[t][size_t(i)];
            if (where[v] == UINT32_MAX) {
                where[v] = uint32_t(m.vertices.size());
                m.vertices.push_back({verts_[v].p[0], verts_[v].p[1], verts_[v].p[2]});
            }
            r[size_t(i)] = where[v];
        }
        m.triangles.push_back(r);
    }
    return m;
}

std::array<float, 6> Sculpt::bounds() const {
    std::array<float, 6> b{1e30f, 1e30f, 1e30f, -1e30f, -1e30f, -1e30f};
    for (uint32_t v = 0; v < verts_.size(); ++v) {
        if (vertTris_[v].empty()) continue;
        for (int k = 0; k < 3; ++k) {
            b[size_t(k)] = std::min(b[size_t(k)], verts_[v].p[k]);
            b[size_t(k) + 3] = std::max(b[size_t(k) + 3], verts_[v].p[k]);
        }
    }
    return b;
}

float Sculpt::averageEdge() const {
    double sum = 0;
    size_t n = 0;
    for (uint32_t t = 0; t < tris_.size(); t += 7) {
        if (!triAlive_[t]) continue;
        sum += std::sqrt(distance2(verts_[tris_[t][0]].p, verts_[tris_[t][1]].p));
        ++n;
    }
    return n ? float(sum / double(n)) : 1.0f;
}

Mesh Sculpt::sphere(float radius, int levels) {
    const float g = (1 + std::sqrt(5.0f)) / 2;
    std::vector<std::array<float, 3>> v = {
        {-1, g, 0}, {1, g, 0}, {-1, -g, 0}, {1, -g, 0}, {0, -1, g}, {0, 1, g},
        {0, -1, -g}, {0, 1, -g}, {g, 0, -1}, {g, 0, 1}, {-g, 0, -1}, {-g, 0, 1},
    };
    std::vector<std::array<uint32_t, 3>> f = {
        {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
        {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1},
    };
    for (int l = 0; l < levels; ++l) {
        std::map<std::pair<uint32_t, uint32_t>, uint32_t> mid;
        auto middle = [&](uint32_t a, uint32_t b) {
            auto key = std::minmax(a, b);
            auto it = mid.find(key);
            if (it != mid.end()) return it->second;
            uint32_t i = uint32_t(v.size());
            v.push_back({(v[a][0] + v[b][0]) / 2, (v[a][1] + v[b][1]) / 2, (v[a][2] + v[b][2]) / 2});
            mid[key] = i;
            return i;
        };
        std::vector<std::array<uint32_t, 3>> next;
        for (const auto& t : f) {
            uint32_t a = middle(t[0], t[1]), b = middle(t[1], t[2]), c = middle(t[2], t[0]);
            next.push_back({t[0], a, c});
            next.push_back({t[1], b, a});
            next.push_back({t[2], c, b});
            next.push_back({a, b, c});
        }
        f.swap(next);
    }
    Mesh m;
    for (auto& p : v) {
        float l = std::sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        m.vertices.push_back({p[0] / l * radius, p[1] / l * radius, p[2] / l * radius});
    }
    m.triangles = f;
    return m;
}

namespace {
const char kMagic[4] = {'P', 'M', 'S', '1'};
}

std::vector<uint8_t> packMesh(const Mesh& mesh) {
    // Counts, then positions, then corners, little endian as every device here is.
    uint32_t nv = uint32_t(mesh.vertices.size()), nt = uint32_t(mesh.triangles.size());
    std::vector<uint8_t> raw(8 + size_t(nv) * 12 + size_t(nt) * 12);
    std::memcpy(raw.data(), &nv, 4);
    std::memcpy(raw.data() + 4, &nt, 4);
    std::memcpy(raw.data() + 8, mesh.vertices.data(), size_t(nv) * 12);
    std::memcpy(raw.data() + 8 + size_t(nv) * 12, mesh.triangles.data(), size_t(nt) * 12);
    uLongf size = compressBound(uLong(raw.size()));
    std::vector<uint8_t> out(8 + size);
    std::memcpy(out.data(), kMagic, 4);
    uint32_t rawSize = uint32_t(raw.size());
    std::memcpy(out.data() + 4, &rawSize, 4);
    if (compress2(out.data() + 8, &size, raw.data(), uLong(raw.size()), 6) != Z_OK) throw std::runtime_error("Couldn't pack the mesh");
    out.resize(8 + size);
    return out;
}

Mesh unpackMesh(const std::vector<uint8_t>& bytes) {
    if (bytes.size() < 8 || std::memcmp(bytes.data(), kMagic, 4) != 0) throw std::runtime_error("The sculpted mesh is damaged");
    uint32_t rawSize;
    std::memcpy(&rawSize, bytes.data() + 4, 4);
    std::vector<uint8_t> raw(rawSize);
    uLongf size = rawSize;
    if (uncompress(raw.data(), &size, bytes.data() + 8, uLong(bytes.size() - 8)) != Z_OK || size != rawSize || rawSize < 8)
        throw std::runtime_error("The sculpted mesh is damaged");
    uint32_t nv, nt;
    std::memcpy(&nv, raw.data(), 4);
    std::memcpy(&nt, raw.data() + 4, 4);
    if (8 + size_t(nv) * 12 + size_t(nt) * 12 != raw.size()) throw std::runtime_error("The sculpted mesh is damaged");
    Mesh m;
    m.vertices.resize(nv);
    m.triangles.resize(nt);
    std::memcpy(m.vertices.data(), raw.data() + 8, size_t(nv) * 12);
    std::memcpy(m.triangles.data(), raw.data() + 8 + size_t(nv) * 12, size_t(nt) * 12);
    for (const auto& t : m.triangles)
        for (uint32_t v : t)
            if (v >= nv) throw std::runtime_error("The sculpted mesh is damaged");
    return m;
}

}  // namespace pm
