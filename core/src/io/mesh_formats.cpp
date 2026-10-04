#include "io/mesh_formats.h"

#include <cstdio>

#include <zlib.h>

#include <array>
#include <cctype>
#include <cmath>
#include <functional>
#include <cstring>
#include <map>
#include <sstream>
#include <stdexcept>

namespace pm {
namespace {

// Zip, just enough for 3MF: deflated entries, no encryption or zip64.

void put16(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back(uint8_t(v));
    b.push_back(uint8_t(v >> 8));
}

void put32(std::vector<uint8_t>& b, uint32_t v) {
    put16(b, v & 0xFFFF);
    put16(b, v >> 16);
}

uint32_t get16(const std::vector<uint8_t>& b, size_t i) {
    if (i + 2 > b.size()) throw std::runtime_error("The 3MF file is cut short");
    return uint32_t(b[i]) | uint32_t(b[i + 1]) << 8;
}

uint32_t get32(const std::vector<uint8_t>& b, size_t i) { return get16(b, i) | get16(b, i + 2) << 16; }

std::vector<uint8_t> deflateRaw(const std::string& s) {
    z_stream z{};
    deflateInit2(&z, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY);
    std::vector<uint8_t> out(deflateBound(&z, uLong(s.size())));
    z.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(s.data()));
    z.avail_in = uInt(s.size());
    z.next_out = out.data();
    z.avail_out = uInt(out.size());
    deflate(&z, Z_FINISH);
    out.resize(z.total_out);
    deflateEnd(&z);
    return out;
}

std::string inflateRaw(const uint8_t* data, size_t size, size_t expected) {
    std::string out(expected, '\0');
    z_stream z{};
    inflateInit2(&z, -15);
    z.next_in = const_cast<Bytef*>(data);
    z.avail_in = uInt(size);
    z.next_out = reinterpret_cast<Bytef*>(out.data());
    z.avail_out = uInt(out.size());
    int r = inflate(&z, Z_FINISH);
    inflateEnd(&z);
    if (r != Z_STREAM_END) throw std::runtime_error("Part of the 3MF file couldn't be unpacked");
    return out;
}

std::vector<uint8_t> zip(const std::vector<std::pair<std::string, std::string>>& files) {
    std::vector<uint8_t> out, central;
    for (const auto& [name, text] : files) {
        auto packed = deflateRaw(text);
        uint32_t crc = uint32_t(crc32(0, reinterpret_cast<const Bytef*>(text.data()), uInt(text.size())));
        uint32_t offset = uint32_t(out.size());
        put32(out, 0x04034b50);
        put16(out, 20); put16(out, 0); put16(out, 8); put16(out, 0); put16(out, 0x21);
        put32(out, crc); put32(out, uint32_t(packed.size())); put32(out, uint32_t(text.size()));
        put16(out, uint16_t(name.size())); put16(out, 0);
        out.insert(out.end(), name.begin(), name.end());
        out.insert(out.end(), packed.begin(), packed.end());

        put32(central, 0x02014b50);
        put16(central, 20); put16(central, 20); put16(central, 0); put16(central, 8); put16(central, 0); put16(central, 0x21);
        put32(central, crc); put32(central, uint32_t(packed.size())); put32(central, uint32_t(text.size()));
        put16(central, uint16_t(name.size())); put16(central, 0); put16(central, 0); put16(central, 0); put16(central, 0);
        put32(central, 0); put32(central, offset);
        central.insert(central.end(), name.begin(), name.end());
    }
    uint32_t start = uint32_t(out.size());
    out.insert(out.end(), central.begin(), central.end());
    put32(out, 0x06054b50);
    put16(out, 0); put16(out, 0);
    put16(out, uint16_t(files.size())); put16(out, uint16_t(files.size()));
    put32(out, uint32_t(central.size())); put32(out, start);
    put16(out, 0);
    return out;
}

/** The zip's files by name, unpacked. */
std::map<std::string, std::string> unzip(const std::vector<uint8_t>& b) {
    // The end record is at the end, before any comment.
    size_t end = std::string::npos;
    for (size_t i = b.size() >= 22 ? b.size() - 22 : 0; i + 1 > 0 && i + 22 <= b.size(); --i) {
        if (get32(b, i) == 0x06054b50) { end = i; break; }
        if (i == 0) break;
    }
    if (end == std::string::npos) throw std::runtime_error("Not a 3MF file");
    uint32_t count = get16(b, end + 10);
    size_t at = get32(b, end + 16);
    std::map<std::string, std::string> out;
    for (uint32_t k = 0; k < count; ++k) {
        if (get32(b, at) != 0x02014b50) throw std::runtime_error("The 3MF file is damaged");
        uint32_t method = get16(b, at + 10);
        uint32_t packed = get32(b, at + 20), size = get32(b, at + 24);
        uint32_t nlen = get16(b, at + 28), xlen = get16(b, at + 30), clen = get16(b, at + 32);
        uint32_t local = get32(b, at + 42);
        std::string name(reinterpret_cast<const char*>(&b[at + 46]), nlen);
        at += 46 + nlen + xlen + clen;
        size_t data = local + 30 + get16(b, local + 26) + get16(b, local + 28);
        if (data + packed > b.size()) throw std::runtime_error("The 3MF file is cut short");
        if (method == 0) out[name] = std::string(reinterpret_cast<const char*>(&b[data]), packed);
        else if (method == 8) out[name] = inflateRaw(&b[data], packed, size);
    }
    return out;
}

// A little XML reading: tags and their attributes, in order. 3MF needs no more.

struct Tag {
    std::string name;
    std::map<std::string, std::string> attrs;
    bool closing = false;
};

std::vector<Tag> tags(const std::string& xml) {
    std::vector<Tag> out;
    size_t i = 0;
    while ((i = xml.find('<', i)) != std::string::npos) {
        size_t end = xml.find('>', i);
        if (end == std::string::npos) break;
        std::string inner = xml.substr(i + 1, end - i - 1);
        i = end + 1;
        if (inner.empty() || inner[0] == '?' || inner[0] == '!') continue;
        Tag t;
        size_t p = 0;
        if (inner[0] == '/') { t.closing = true; p = 1; }
        size_t n = inner.find_first_of(" \t\r\n/", p);
        t.name = inner.substr(p, n == std::string::npos ? std::string::npos : n - p);
        // Drop any namespace prefix: "m:vertex" is a vertex.
        if (auto c = t.name.find(':'); c != std::string::npos) t.name = t.name.substr(c + 1);
        while (n != std::string::npos && n < inner.size()) {
            size_t eq = inner.find('=', n);
            if (eq == std::string::npos) break;
            size_t k0 = inner.find_first_not_of(" \t\r\n", n);
            std::string key = inner.substr(k0, eq - k0);
            while (!key.empty() && isspace(uint8_t(key.back()))) key.pop_back();
            size_t q = inner.find_first_of("\"'", eq);
            if (q == std::string::npos) break;
            size_t q2 = inner.find(inner[q], q + 1);
            if (q2 == std::string::npos) break;
            t.attrs[key] = inner.substr(q + 1, q2 - q - 1);
            n = q2 + 1;
        }
        out.push_back(std::move(t));
    }
    return out;
}

/** A 3MF transform: twelve numbers, three per row of x, y, z and then the move. */
using Transform = std::array<double, 12>;
constexpr Transform kIdentity = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};

Transform parseTransform(const std::map<std::string, std::string>& attrs) {
    auto it = attrs.find("transform");
    if (it == attrs.end()) return kIdentity;
    std::istringstream in(it->second);
    Transform t{};
    for (double& v : t) in >> v;
    return in ? t : kIdentity;
}

/** a then b, as 3MF composes them (row vectors). */
Transform compose(const Transform& a, const Transform& b) {
    Transform r{};
    for (int row = 0; row < 4; ++row)
        for (int col = 0; col < 3; ++col) {
            double s = row == 3 ? b[9 + col] : 0;
            for (int k = 0; k < 3; ++k) s += a[row * 3 + k] * b[k * 3 + col];
            r[row * 3 + col] = s;
        }
    return r;
}

}  // namespace

std::vector<uint8_t> write3mf(const std::vector<NamedMesh>& objects) {
    std::ostringstream m;
    m.precision(9);
    m << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n"
         " <resources>\n";
    // Colours as base materials, one per object that has one; the objects are numbered after them.
    bool coloured = false;
    for (const auto& o : objects) coloured = coloured || o.colour >= 0;
    const size_t first = coloured ? 2 : 1;
    if (coloured) {
        m << "  <basematerials id=\"1\">\n";
        for (const auto& o : objects) {
            char hex[8];
            std::snprintf(hex, sizeof hex, "#%06X", unsigned(o.colour < 0 ? 0xCCCCCC : o.colour) & 0xFFFFFFu);
            m << "   <base name=\"" << hex << "\" displaycolor=\"" << hex << "\"/>\n";
        }
        m << "  </basematerials>\n";
    }
    for (size_t i = 0; i < objects.size(); ++i) {
        std::string name;
        for (char c : objects[i].name) {
            if (c == '&') name += "&amp;"; else if (c == '<') name += "&lt;"; else if (c == '"') name += "&quot;"; else name += c;
        }
        m << "  <object id=\"" << i + first << "\" name=\"" << name << "\" type=\"model\"";
        if (coloured) m << " pid=\"1\" pindex=\"" << i << "\"";
        m << ">\n   <mesh>\n    <vertices>\n";
        for (const auto& v : objects[i].mesh.vertices) m << "     <vertex x=\"" << v[0] << "\" y=\"" << v[1] << "\" z=\"" << v[2] << "\"/>\n";
        m << "    </vertices>\n    <triangles>\n";
        for (const auto& t : objects[i].mesh.triangles) m << "     <triangle v1=\"" << t[0] << "\" v2=\"" << t[1] << "\" v3=\"" << t[2] << "\"/>\n";
        m << "    </triangles>\n   </mesh>\n  </object>\n";
    }
    m << " </resources>\n <build>\n";
    for (size_t i = 0; i < objects.size(); ++i) m << "  <item objectid=\"" << i + first << "\"/>\n";
    m << " </build>\n</model>\n";
    return zip({
        {"[Content_Types].xml",
         "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
         "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
         "<Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>\n"},
        {"_rels/.rels",
         "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
         "<Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>\n"},
        {"3D/3dmodel.model", m.str()},
    });
}

std::vector<NamedMesh> read3mf(const std::vector<uint8_t>& data) {
    auto files = unzip(data);
    std::string model;
    for (const auto& [name, text] : files) {
        if (name.size() > 6 && name.compare(name.size() - 6, 6, ".model") == 0) { model = text; break; }
    }
    if (model.empty()) throw std::runtime_error("The 3MF file has no model in it");

    struct Object {
        std::string name;
        Mesh mesh;
        std::vector<std::pair<int, Transform>> parts;  // Components: other objects, placed.
    };
    std::map<int, Object> objects;
    std::vector<std::pair<int, Transform>> build;
    Object* current = nullptr;
    bool inBuild = false;
    for (const auto& t : tags(model)) {
        if (t.name == "object" && !t.closing) {
            int id = std::stoi(t.attrs.count("id") ? t.attrs.at("id") : "0");
            current = &objects[id];
            current->name = t.attrs.count("name") ? t.attrs.at("name") : "Object " + std::to_string(id);
        } else if (t.name == "object" && t.closing) {
            current = nullptr;
        } else if (t.name == "vertex" && current) {
            current->mesh.vertices.push_back({std::stof(t.attrs.at("x")), std::stof(t.attrs.at("y")), std::stof(t.attrs.at("z"))});
        } else if (t.name == "triangle" && current) {
            current->mesh.triangles.push_back({uint32_t(std::stoul(t.attrs.at("v1"))), uint32_t(std::stoul(t.attrs.at("v2"))), uint32_t(std::stoul(t.attrs.at("v3")))});
        } else if (t.name == "component" && current) {
            current->parts.push_back({std::stoi(t.attrs.at("objectid")), parseTransform(t.attrs)});
        } else if (t.name == "build") {
            inBuild = !t.closing;
        } else if (t.name == "item" && inBuild) {
            build.push_back({std::stoi(t.attrs.at("objectid")), parseTransform(t.attrs)});
        }
    }
    std::vector<NamedMesh> out;
    // Each build item, with any components it's made of, moved into place.
    std::function<void(int, const Transform&, Mesh&, int)> place = [&](int id, const Transform& tr, Mesh& into, int depth) {
        auto it = objects.find(id);
        if (it == objects.end() || depth > 8) return;
        const Mesh& m = it->second.mesh;
        uint32_t base = uint32_t(into.vertices.size());
        for (const auto& v : m.vertices) {
            into.vertices.push_back({float(v[0] * tr[0] + v[1] * tr[3] + v[2] * tr[6] + tr[9]),
                                     float(v[0] * tr[1] + v[1] * tr[4] + v[2] * tr[7] + tr[10]),
                                     float(v[0] * tr[2] + v[1] * tr[5] + v[2] * tr[8] + tr[11])});
        }
        for (const auto& t : m.triangles) {
            if (t[0] >= m.vertices.size() || t[1] >= m.vertices.size() || t[2] >= m.vertices.size()) throw std::runtime_error("The 3MF file refers to a corner that isn't there");
            into.triangles.push_back({t[0] + base, t[1] + base, t[2] + base});
        }
        for (const auto& [part, ptr] : it->second.parts) place(part, compose(ptr, tr), into, depth + 1);
    };
    if (build.empty()) for (const auto& [id, o] : objects) build.push_back({id, kIdentity});
    for (const auto& [id, tr] : build) {
        NamedMesh nm;
        nm.name = objects.count(id) ? objects[id].name : "Object";
        place(id, tr, nm.mesh, 0);
        if (!nm.mesh.triangles.empty()) out.push_back(std::move(nm));
    }
    if (out.empty()) throw std::runtime_error("The 3MF file has nothing on its build plate");
    return out;
}

std::string writeObj(const std::vector<NamedMesh>& objects) {
    std::ostringstream o;
    o.precision(9);
    o << "# ParrotMetric, millimetres\n";
    size_t base = 1;
    for (const auto& nm : objects) {
        o << "o " << nm.name << "\n";
        for (const auto& v : nm.mesh.vertices) o << "v " << v[0] << " " << v[1] << " " << v[2] << "\n";
        for (const auto& t : nm.mesh.triangles) o << "f " << t[0] + base << " " << t[1] + base << " " << t[2] + base << "\n";
        base += nm.mesh.vertices.size();
    }
    return o.str();
}

Mesh readObj(const std::string& text, float weldDistance) {
    std::vector<std::array<float, 3>> v;
    MeshBuilder builder(weldDistance);
    std::istringstream in(text);
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream l(line);
        std::string kind;
        l >> kind;
        if (kind == "v") {
            std::array<float, 3> p{};
            l >> p[0] >> p[1] >> p[2];
            v.push_back(p);
        } else if (kind == "f") {
            std::vector<long> idx;
            std::string tok;
            while (l >> tok) {
                long i = std::stol(tok.substr(0, tok.find('/')));
                idx.push_back(i < 0 ? long(v.size()) + i : i - 1);
            }
            for (long i : idx) if (i < 0 || size_t(i) >= v.size()) throw std::runtime_error("The OBJ file refers to a corner that isn't there");
            // Polygons as fans from their first corner.
            for (size_t k = 1; k + 1 < idx.size(); ++k) builder.triangle(v[size_t(idx[0])], v[size_t(idx[k])], v[size_t(idx[k + 1])]);
        }
    }
    Mesh m = builder.take();
    if (m.triangles.empty()) throw std::runtime_error("The OBJ file has no faces");
    return m;
}

}  // namespace pm
