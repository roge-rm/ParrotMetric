#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "mesh/mesh.h"

namespace pm {

/** A named mesh, one object in a file. */
struct NamedMesh {
    std::string name;
    Mesh mesh;
    /** 0xRRGGBB, or -1 for none. */
    int colour = -1;
};

/** 3MF: a zip of XML, the usual format for slicers. Millimetres; one object per body, with its colour if it has one. */
std::vector<uint8_t> write3mf(const std::vector<NamedMesh>& objects);

/** Every object a 3MF file places on its build plate, moved where it puts them. Throws std::runtime_error. */
std::vector<NamedMesh> read3mf(const std::vector<uint8_t>& data);

/** Wavefront OBJ, one object per body. */
std::string writeObj(const std::vector<NamedMesh>& objects);

/** All the faces in an OBJ file as one mesh, polygons split into triangles. Throws std::runtime_error. */
Mesh readObj(const std::string& text, float weldDistance = 1e-4f);

}  // namespace pm
