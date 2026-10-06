#include "mesh/mesh_body.h"

#include <manifold/manifold.h>
#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <numeric>

#include <stdexcept>

namespace pm {
namespace {

const char* errorText(manifold::Manifold::Error e) {
    using E = manifold::Manifold::Error;
    switch (e) {
        case E::NotManifold: return "The mesh has holes or edges shared by more than two triangles";
        case E::NonFiniteVertex: return "The mesh has a corner at an invalid position";
        case E::VertexOutOfBounds: return "The mesh refers to a corner that isn't there";
        default: return "The mesh couldn't be used as a body";
    }
}

}  // namespace

MeshBody::MeshBody(const manifold::Manifold& m) : m_(new manifold::Manifold(m)) {}
MeshBody::MeshBody(const MeshBody& o) : m_(new manifold::Manifold(*o.m_)) {}
MeshBody& MeshBody::operator=(const MeshBody& o) {
    *m_ = *o.m_;
    return *this;
}
MeshBody::~MeshBody() { delete m_; }

MeshBody MeshBody::fromMesh(const Mesh& mesh) {
    manifold::MeshGL gl;
    gl.numProp = 3;
    gl.vertProperties.reserve(mesh.vertices.size() * 3);
    for (const auto& v : mesh.vertices) gl.vertProperties.insert(gl.vertProperties.end(), v.begin(), v.end());
    gl.triVerts.reserve(mesh.triangles.size() * 3);
    for (const auto& t : mesh.triangles) gl.triVerts.insert(gl.triVerts.end(), t.begin(), t.end());
    manifold::Manifold m(gl);
    if (m.Status() != manifold::Manifold::Error::NoError) throw std::runtime_error(errorText(m.Status()));
    return MeshBody(m);
}

MeshBody MeshBody::box(float x, float y, float z) { return MeshBody(manifold::Manifold::Cube({x, y, z})); }

MeshBody MeshBody::boolean(const MeshBody& tool, BooleanOp op) const {
    switch (op) {
        case BooleanOp::Join: return MeshBody(*m_ + *tool.m_);
        case BooleanOp::Cut: return MeshBody(*m_ - *tool.m_);
        case BooleanOp::Intersect: return MeshBody(*m_ ^ *tool.m_);
    }
    return *this;
}

MeshBody MeshBody::translated(float x, float y, float z) const { return MeshBody(m_->Translate({x, y, z})); }

Mesh MeshBody::toMesh() const {
    manifold::MeshGL gl = m_->GetMeshGL();
    Mesh mesh;
    mesh.vertices.resize(gl.NumVert());
    for (size_t i = 0; i < mesh.vertices.size(); ++i) {
        const float* p = &gl.vertProperties[i * gl.numProp];
        mesh.vertices[i] = {p[0], p[1], p[2]};
    }
    mesh.triangles.resize(gl.NumTri());
    for (size_t i = 0; i < mesh.triangles.size(); ++i) {
        mesh.triangles[i] = {gl.triVerts[i * 3], gl.triVerts[i * 3 + 1], gl.triVerts[i * 3 + 2]};
    }
    return mesh;
}

MeshBody MeshBody::transformed(const double m[12]) const {
    manifold::mat3x4 t({m[0], m[4], m[8]}, {m[1], m[5], m[9]}, {m[2], m[6], m[10]}, {m[3], m[7], m[11]});
    return MeshBody(m_->Transform(t));
}

std::pair<MeshBody, MeshBody> MeshBody::split(const double origin[3], const double normal[3]) const {
    manifold::vec3 n(normal[0], normal[1], normal[2]);
    double offset = origin[0] * normal[0] + origin[1] * normal[1] + origin[2] * normal[2];
    auto [front, back] = m_->SplitByPlane(n, offset);
    return {MeshBody(front), MeshBody(back)};
}

bool MeshBody::empty() const { return m_->IsEmpty(); }

MeshBody MeshBody::reduced(double tolerance) const {
    if (tolerance <= 0) throw std::runtime_error("The tolerance has to be more than 0");
    return MeshBody(m_->Simplify(tolerance));
}

MeshBody MeshBody::remeshed(double length) const {
    if (length <= 0) throw std::runtime_error("The edge length has to be more than 0");
    // About the size of the body's box divided by the length, cubed and squared; kept from running away.
    auto b = bounds();
    double extent = std::max({b[3] - b[0], b[4] - b[1], b[5] - b[2]});
    if (extent / length > 2000) throw std::runtime_error("That would make too many triangles; use a longer edge");
    return MeshBody(m_->RefineToLength(length));
}

MeshBody MeshBody::smoothed(double sharpAngle, int steps) const {
    if (steps < 1 || steps > 6) throw std::runtime_error("Use between 1 and 6 steps");
    if (triangleCount() * size_t(steps) * size_t(steps) > 4000000) throw std::runtime_error("That would make too many triangles");
    // Manifold keeps the faces it groups as flat flat, and it groups near-flat neighbours;
    // each triangle its own face lets them all curve.
    manifold::MeshGL gl = m_->GetMeshGL();
    gl.faceID.resize(gl.NumTri());
    std::iota(gl.faceID.begin(), gl.faceID.end(), 0u);
    gl.runIndex.clear();
    gl.runOriginalID.clear();
    gl.runTransform.clear();
    manifold::Manifold apart(gl);
    if (apart.Status() != manifold::Manifold::Error::NoError) throw std::runtime_error("The mesh couldn't be smoothed");
    return MeshBody(apart.SmoothOut(sharpAngle, 0).Refine(steps));
}

std::vector<std::vector<std::array<double, 2>>> MeshBody::slice(const double o[3], const double x[3], const double y[3]) const {
    // Into the plane's own coordinates (the inverse of its frame, a rotation), so the plane is z = 0.
    double n[3] = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
    auto dot = [](const double a[3], const double b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; };
    manifold::mat3x4 t({x[0], y[0], n[0]}, {x[1], y[1], n[1]}, {x[2], y[2], n[2]}, {-dot(x, o), -dot(y, o), -dot(n, o)});
    std::vector<std::vector<std::array<double, 2>>> out;
    for (const auto& poly : m_->Transform(t).Slice(0)) {
        std::vector<std::array<double, 2>> loop;
        for (const auto& p : poly) loop.push_back({p.x, p.y});
        out.push_back(std::move(loop));
    }
    return out;
}

std::array<double, 3> MeshBody::centre() const {
    auto box = m_->BoundingBox();
    return {(box.min.x + box.max.x) / 2, (box.min.y + box.max.y) / 2, (box.min.z + box.max.z) / 2};
}

std::array<double, 6> MeshBody::bounds() const {
    auto box = m_->BoundingBox();
    return {box.min.x, box.min.y, box.min.z, box.max.x, box.max.y, box.max.z};
}

double MeshBody::volume() const { return m_->Volume(); }
size_t MeshBody::triangleCount() const { return m_->NumTri(); }

}  // namespace pm

namespace pm {

namespace {

/** Squared distances along one row to the nearest zero in f, in cells (Felzenszwalb and Huttenlocher). */
void distanceRow(const std::vector<double>& f, std::vector<double>& d, std::vector<int>& v, std::vector<double>& z) {
    const int n = int(f.size());
    const double inf = std::numeric_limits<double>::infinity();
    int k = 0;
    v[0] = 0;
    z[0] = -inf;
    z[1] = inf;
    for (int q = 1; q < n; ++q) {
        if (f[q] == inf) continue;
        while (true) {
            if (f[v[k]] == inf) { v[k] = q; z[k] = -inf; z[k + 1] = inf; break; }
            double s = ((f[q] + double(q) * q) - (f[v[k]] + double(v[k]) * v[k])) / (2.0 * q - 2.0 * v[k]);
            if (s <= z[k]) {
                if (k == 0) { v[0] = q; z[0] = -inf; z[1] = inf; break; }
                --k;
                continue;
            }
            ++k;
            v[k] = q;
            z[k] = s;
            z[k + 1] = inf;
            break;
        }
    }
    k = 0;
    for (int q = 0; q < n; ++q) {
        while (z[k + 1] < q) ++k;
        d[q] = f[v[k]] == inf ? inf : (double(q) - v[k]) * (q - v[k]) + f[v[k]];
    }
}

}  // namespace

MeshBody MeshBody::hollowed(double thickness, size_t cells) const {
    if (thickness <= 0) throw std::runtime_error("The wall has to be more than 0 thick");
    auto b = bounds();
    double ext[3] = {b[3] - b[0], b[4] - b[1], b[5] - b[2]};
    if (std::min({ext[0], ext[1], ext[2]}) <= 2 * thickness) throw std::runtime_error("The walls would fill it: make them thinner");
    // A cell a quarter of the wall, or bigger if the grid would have too many.
    double h = thickness / 4;
    auto count = [&](double size) { return (ext[0] / size + 4) * (ext[1] / size + 4) * (ext[2] / size + 4); };
    while (count(h) > double(cells)) h *= 1.1;
    if (h > thickness / 2) throw std::runtime_error("It's too big for walls that thin: make them thicker");
    const int nx = int(ext[0] / h) + 4, ny = int(ext[1] / h) + 4, nz = int(ext[2] / h) + 4;
    const double x0 = b[0] - 1.5 * h, y0 = b[1] - 1.5 * h, z0 = b[2] - 1.5 * h;
    auto at = [&](int i, int j, int k) { return (size_t(k) * ny + j) * nx + i; };

    // Inside or out: up each column, crossing the surface turns inside on and off. The columns are
    // nudged off the grid so they don't run exactly through edges.
    Mesh mesh = toMesh();
    const double jx = 0.0137 * h, jy = 0.0291 * h;
    std::vector<std::vector<float>> crossings(size_t(nx) * ny);
    for (const auto& t : mesh.triangles) {
        const auto& a = mesh.vertices[t[0]];
        const auto& p = mesh.vertices[t[1]];
        const auto& c = mesh.vertices[t[2]];
        double lox = std::min({a[0], p[0], c[0]}), hix = std::max({a[0], p[0], c[0]});
        double loy = std::min({a[1], p[1], c[1]}), hiy = std::max({a[1], p[1], c[1]});
        int i0 = std::max(0, int(std::ceil((lox - x0 - jx) / h))), i1 = std::min(nx - 1, int(std::floor((hix - x0 - jx) / h)));
        int j0 = std::max(0, int(std::ceil((loy - y0 - jy) / h))), j1 = std::min(ny - 1, int(std::floor((hiy - y0 - jy) / h)));
        double det = (p[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (p[1] - a[1]);
        if (std::abs(det) < 1e-12) continue;
        for (int j = j0; j <= j1; ++j)
            for (int i = i0; i <= i1; ++i) {
                double x = x0 + i * h + jx, y = y0 + j * h + jy;
                double u = ((x - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (y - a[1])) / det;
                double w = ((p[0] - a[0]) * (y - a[1]) - (x - a[0]) * (p[1] - a[1])) / det;
                if (u < 0 || w < 0 || u + w > 1) continue;
                crossings[size_t(j) * nx + i].push_back(float(a[2] + u * (p[2] - a[2]) + w * (c[2] - a[2])));
            }
    }
    const double inf = std::numeric_limits<double>::infinity();
    // Outside cells are where the distance is measured from: 0 there, unknown inside.
    std::vector<double> dist(size_t(nx) * ny * nz, 0.0);
    for (int j = 0; j < ny; ++j)
        for (int i = 0; i < nx; ++i) {
            auto& zs = crossings[size_t(j) * nx + i];
            std::sort(zs.begin(), zs.end());
            for (size_t m = 0; m + 1 < zs.size(); m += 2) {
                int k0 = std::max(0, int(std::ceil((zs[m] - z0) / h))), k1 = std::min(nz - 1, int(std::floor((zs[m + 1] - z0) / h)));
                for (int k = k0; k <= k1; ++k) dist[at(i, j, k)] = inf;
            }
        }
    // Squared distances in cells to the nearest outside cell, a pass along each axis.
    int longest = std::max({nx, ny, nz});
    std::vector<double> f(longest), d(longest), z(longest + 1);
    std::vector<int> v(longest);
    auto pass = [&](int n, auto index, int a1, int a2) {
        f.resize(n); d.resize(n);
        for (int p = 0; p < a1; ++p)
            for (int q = 0; q < a2; ++q) {
                for (int r = 0; r < n; ++r) f[r] = dist[index(r, p, q)];
                distanceRow(f, d, v, z);
                for (int r = 0; r < n; ++r) dist[index(r, p, q)] = d[r];
            }
    };
    pass(nx, [&](int r, int p, int q) { return at(r, p, q); }, ny, nz);
    pass(ny, [&](int r, int p, int q) { return at(p, r, q); }, nx, nz);
    pass(nz, [&](int r, int p, int q) { return at(p, q, r); }, nx, ny);
    // The surface lies about half a cell out from the last inside cell.
    for (auto& e : dist) e = e > 0 ? std::sqrt(e) * h - h / 2 : -h / 2;
    // Evened out over each cell's neighbours along each axis in turn, so the inside isn't stepped like the grid.
    std::vector<double> row(longest);
    auto blur = [&](int n, auto index, int a1, int a2) {
        for (int p = 0; p < a1; ++p)
            for (int q = 0; q < a2; ++q) {
                for (int r = 0; r < n; ++r) row[r] = dist[index(r, p, q)];
                for (int r = 1; r + 1 < n; ++r) dist[index(r, p, q)] = (row[r - 1] + 2 * row[r] + row[r + 1]) / 4;
            }
    };
    for (int round = 0; round < 2; ++round) {
        blur(nx, [&](int r, int p, int q) { return at(r, p, q); }, ny, nz);
        blur(ny, [&](int r, int p, int q) { return at(p, r, q); }, nx, nz);
        blur(nz, [&](int r, int p, int q) { return at(p, q, r); }, nx, ny);
    }
    auto sdf = [&](manifold::vec3 p) {
        double fx = (p.x - x0) / h, fy = (p.y - y0) / h, fz = (p.z - z0) / h;
        int i = std::clamp(int(std::floor(fx)), 0, nx - 2), j = std::clamp(int(std::floor(fy)), 0, ny - 2), k = std::clamp(int(std::floor(fz)), 0, nz - 2);
        double u = std::clamp(fx - i, 0.0, 1.0), w = std::clamp(fy - j, 0.0, 1.0), s = std::clamp(fz - k, 0.0, 1.0);
        auto lerp = [](double a, double c, double t) { return a + (c - a) * t; };
        double c00 = lerp(dist[at(i, j, k)], dist[at(i + 1, j, k)], u), c10 = lerp(dist[at(i, j + 1, k)], dist[at(i + 1, j + 1, k)], u);
        double c01 = lerp(dist[at(i, j, k + 1)], dist[at(i + 1, j, k + 1)], u), c11 = lerp(dist[at(i, j + 1, k + 1)], dist[at(i + 1, j + 1, k + 1)], u);
        return lerp(lerp(c00, c10, w), lerp(c01, c11, w), s) - thickness;
    };
    manifold::Box box({b[0], b[1], b[2]}, {b[3], b[4], b[5]});
    manifold::Manifold inside = manifold::Manifold::LevelSet(sdf, box, h, 0, -1, false);
    if (inside.IsEmpty()) throw std::runtime_error("There's no room inside for walls that thick");
    // The grid's surface has far more triangles than its roughness warrants.
    inside = inside.Simplify(h / 4);
    return MeshBody(*m_ - inside);
}

MeshBody MeshBody::erased(const std::vector<std::array<double, 4>>& spots) const {
    Mesh mesh = toMesh();
    auto near = [&](uint32_t vi) {
        const auto& p = mesh.vertices[vi];
        for (const auto& s : spots) {
            double dx = p[0] - s[0], dy = p[1] - s[1], dz = p[2] - s[2];
            if (dx * dx + dy * dy + dz * dz <= s[3] * s[3]) return true;
        }
        return false;
    };
    std::vector<std::array<uint32_t, 3>> kept;
    for (const auto& t : mesh.triangles)
        if (!near(t[0]) && !near(t[1]) && !near(t[2])) kept.push_back(t);
    if (kept.size() == mesh.triangles.size()) throw std::runtime_error("There's nothing to erase there");
    if (kept.empty()) throw std::runtime_error("That would erase all of it");
    // Edges with a triangle on one side only; walking them backwards goes round each hole.
    std::map<std::pair<uint32_t, uint32_t>, int> edges;
    for (const auto& t : kept)
        for (int e = 0; e < 3; ++e) edges[{t[e], t[(e + 1) % 3]}]++;
    std::map<uint32_t, uint32_t> next;
    for (const auto& [e, n] : edges) {
        if (edges.count({e.second, e.first})) continue;
        if (next.count(e.second)) throw std::runtime_error("The hole's edge touches itself: erase a little more or less");
        next[e.second] = e.first;
    }
    std::vector<std::array<float, 3>>& verts = mesh.vertices;
    std::vector<std::array<uint32_t, 3>> tris = kept;
    std::vector<uint32_t> inner;   // Patch points that are smoothed.
    std::vector<std::vector<uint32_t>> around;  // Their neighbours.
    while (!next.empty()) {
        std::vector<uint32_t> loop;
        uint32_t start = next.begin()->first, at = start;
        do {
            loop.push_back(at);
            auto it = next.find(at);
            if (it == next.end()) throw std::runtime_error("The hole's edge doesn't close: erase a little more or less");
            at = it->second;
            next.erase(it);
        } while (at != start);
        const size_t n = loop.size();
        if (n < 3) continue;
        std::array<double, 3> c{0, 0, 0};
        for (uint32_t i : loop) for (int k = 0; k < 3; ++k) c[k] += verts[i][k] / n;
        // Rings from the edge in to the middle, as many as it takes to keep the triangles about even.
        int rings = std::clamp(int(n / 6), 1, 12);
        std::vector<uint32_t> outer = loop;
        for (int r = 1; r < rings; ++r) {
            double t = double(r) / rings;
            std::vector<uint32_t> ring;
            for (uint32_t i : loop) {
                ring.push_back(uint32_t(verts.size()));
                verts.push_back({float(verts[i][0] + (c[0] - verts[i][0]) * t), float(verts[i][1] + (c[1] - verts[i][1]) * t), float(verts[i][2] + (c[2] - verts[i][2]) * t)});
            }
            for (size_t i = 0; i < n; ++i) {
                size_t j = (i + 1) % n;
                tris.push_back({outer[i], outer[j], ring[j]});
                tris.push_back({outer[i], ring[j], ring[i]});
            }
            inner.insert(inner.end(), ring.begin(), ring.end());
            outer = ring;
        }
        uint32_t middle = uint32_t(verts.size());
        verts.push_back({float(c[0]), float(c[1]), float(c[2])});
        inner.push_back(middle);
        for (size_t i = 0; i < n; ++i) tris.push_back({outer[i], outer[(i + 1) % n], middle});
    }
    // The patch is first spread flat between its edges (each point at its neighbours' average),
    // then bent so its curve carries on from the surface round it: its points move against the
    // second difference of the averages, the mesh outside held where it is.
    std::vector<std::vector<uint32_t>> links(verts.size());
    for (const auto& t : tris)
        for (int e = 0; e < 3; ++e) {
            links[t[e]].push_back(t[(e + 1) % 3]);
            links[t[e]].push_back(t[(e + 2) % 3]);
        }
    auto offAverage = [&](uint32_t i) {
        std::array<double, 3> s{0, 0, 0};
        for (uint32_t o : links[i]) for (int k = 0; k < 3; ++k) s[k] += verts[o][k];
        for (int k = 0; k < 3; ++k) s[k] = s[k] / links[i].size() - verts[i][k];
        return s;
    };
    for (int pass = 0; pass < 60; ++pass)
        for (uint32_t i : inner) {
            auto d = offAverage(i);
            for (int k = 0; k < 3; ++k) verts[i][k] = float(verts[i][k] + d[k]);
        }
    // The points whose averages the patch's depend on: the patch and the ring round it.
    std::vector<uint8_t> inPatch(verts.size(), 0);
    for (uint32_t i : inner) inPatch[i] = 1;
    std::vector<uint32_t> touched;
    for (uint32_t i : inner) {
        touched.push_back(i);
        for (uint32_t o : links[i]) if (!inPatch[o]) { inPatch[o] = 2; touched.push_back(o); }
    }
    std::vector<std::array<double, 3>> lap(verts.size());
    for (int pass = 0; pass < 400; ++pass) {
        for (uint32_t i : touched) lap[i] = offAverage(i);
        for (uint32_t i : inner) {
            std::array<double, 3> s{0, 0, 0};
            for (uint32_t o : links[i]) for (int k = 0; k < 3; ++k) s[k] += lap[o][k];
            for (int k = 0; k < 3; ++k) verts[i][k] = float(verts[i][k] - 0.2 * (s[k] / links[i].size() - lap[i][k]));
        }
    }
    Mesh out;
    out.vertices = verts;
    out.triangles = tris;
    return fromMesh(out);
}

std::vector<MeshBody> MeshBody::parts() const {
    // Pieces not joined to each other; a hollow's inside surface is one too, facing in, and goes
    // back with the smallest piece round it.
    std::vector<manifold::Manifold> solid, voids;
    for (const auto& m : m_->Decompose()) (m.Volume() >= 0 ? solid : voids).push_back(m);
    std::vector<std::vector<manifold::Manifold>> with(solid.size());
    for (const auto& v : voids) {
        manifold::Box vb = v.BoundingBox();
        int best = -1;
        for (size_t i = 0; i < solid.size(); ++i) {
            manifold::Box sb = solid[i].BoundingBox();
            if (!sb.Contains(vb)) continue;
            if (best < 0 || solid[i].Volume() < solid[best].Volume()) best = int(i);
        }
        if (best >= 0) with[best].push_back(v);
    }
    std::vector<MeshBody> out;
    for (size_t i = 0; i < solid.size(); ++i) {
        if (with[i].empty()) {
            out.push_back(MeshBody(solid[i]));
            continue;
        }
        // Put together as they are, without a boolean: they don't touch.
        Mesh joined = MeshBody(solid[i]).toMesh();
        for (const auto& v : with[i]) {
            Mesh m = MeshBody(v).toMesh();
            uint32_t base = uint32_t(joined.vertices.size());
            joined.vertices.insert(joined.vertices.end(), m.vertices.begin(), m.vertices.end());
            for (auto t : m.triangles) joined.triangles.push_back({t[0] + base, t[1] + base, t[2] + base});
        }
        out.push_back(fromMesh(joined));
    }
    std::sort(out.begin(), out.end(), [](const MeshBody& a, const MeshBody& b) { return a.volume() > b.volume(); });
    return out;
}

}  // namespace pm
