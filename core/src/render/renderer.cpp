#include "render/renderer.h"

#include "render/gl.h"
#include "sculpt/sculpt.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <string>

namespace pm {

uint32_t targetFramebuffer = 0;
bool desktopGl = false;

namespace {

constexpr float kMoveSeconds = 0.35f;
constexpr float kHalfFov = 0.4f;  // Half of a 46 degree vertical field of view.
constexpr int kSelectionRow = 1024;  // Selection textures are this many texels wide.

// Picked ids: 0 is nothing; otherwise body << 20 | kind << 19 | index, plus one.
constexpr uint32_t kEdgeBit = 1u << 19;
// Corners are edges with this bit too.
constexpr uint32_t kCornerBit = 1u << 18;

const char* kFaceVertex = R"(#version 300 es
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in uint face;
layout(location = 3) in float shade;
uniform mat4 viewProjection;
uniform mat3 view;
uniform sampler2D selected;
out vec3 eyeNormal;
out vec3 world;
out vec3 worldNormal;
out float thick;
flat out float chosen;
void main() {
    eyeNormal = view * normal;
    worldNormal = normal;
    thick = shade;
    world = position;
    chosen = texelFetch(selected, ivec2(int(face) % 1024, int(face) / 1024), 0).r;
    gl_Position = viewProjection * vec4(position, 1.0);
})";

const char* kFaceFragment = R"(#version 300 es
precision mediump float;
in vec3 eyeNormal;
in vec3 world;
in vec3 worldNormal;
in float thick;
flat in float chosen;
uniform vec4 faceColour;
uniform vec4 clip;
uniform bool clipping;
// 0 plain, 1 overhangs steeper than limit (radians from straight down), 2 walls thinner than limit (mm).
uniform int analysis;
uniform float limit;
uniform float bedZ;
uniform bool analysed;
// A sculpted mesh: the shade is how masked it is, shown darker.
uniform bool masking;
out vec4 colour;
void main() {
    if (clipping && dot(vec4(world, 1.0), clip) < 0.0) discard;
    // With a section cut, the inside shows through the cut: flat and warm, like a cut face.
    if (clipping && !gl_FrontFacing && faceColour.a >= 1.0) {
        colour = vec4(0.85, 0.55, 0.38, 1.0);
        return;
    }
    vec3 n = normalize(eyeNormal);
    if (!gl_FrontFacing) n = -n;
    // Sky above and warm floor below, a key light from the upper left and a soft rim.
    float up = n.y * 0.5 + 0.5;
    vec3 ambient = mix(vec3(0.20, 0.19, 0.18), vec3(0.34, 0.40, 0.40), up);
    float key = max(dot(n, normalize(vec3(-0.45, 0.65, 0.62))), 0.0);
    float rim = pow(1.0 - max(n.z, 0.0), 3.0) * 0.18;
    vec3 own = faceColour.rgb;
    if (analysed && analysis == 1) {
        // How far it faces down past the limit; faces on the bed are held up by it.
        float down = -normalize(worldNormal).z;
        float c = cos(limit);
        if (down > c && world.z > bedZ + 0.05) own = mix(vec3(0.95, 0.62, 0.25), vec3(0.86, 0.22, 0.20), clamp((down - c) / max(1.0 - c, 0.01) * 2.0, 0.0, 1.0));
        else own = vec3(0.62, 0.78, 0.70);
    } else if (analysed && analysis == 4) {
        // Bulging out warm, hollow cool, flat between; limit is the radius that shows fully.
        float t = clamp(thick * limit, -1.0, 1.0);
        own = t >= 0.0 ? mix(vec3(0.62, 0.78, 0.70), vec3(0.90, 0.36, 0.22), t) : mix(vec3(0.62, 0.78, 0.70), vec3(0.30, 0.50, 0.90), -t);
    } else if (analysed && analysis == 2) {
        if (thick < limit) own = vec3(0.86, 0.22, 0.20);
        else if (thick < limit * 2.0) own = mix(vec3(0.95, 0.70, 0.25), vec3(0.62, 0.78, 0.70), (thick - limit) / limit);
        else own = vec3(0.62, 0.78, 0.70);
    }
    if (masking) own = mix(own, vec3(0.36, 0.42, 0.52), clamp(thick, 0.0, 1.0) * 0.7);
    vec3 base = mix(own, vec3(1.0, 0.48, 0.24), chosen * 0.55);
    float alpha = faceColour.a < 1.0 ? mix(faceColour.a, 0.55, chosen) : 1.0;
    colour = vec4(base * (ambient + 0.72 * key) + rim * (1.0 - chosen), alpha);
    // Clay being sculpted catches the light a little, which shows the shape's small turns.
    if (masking) colour.rgb += vec3(0.16, 0.15, 0.13) * pow(max(reflect(-normalize(vec3(-0.45, 0.65, 0.62)), n).z, 0.0), 16.0) * (1.0 - thick * 0.8);
    if (analysed && analysis == 3) {
        // Stripes as a row of lights would reflect in it; limit is how many.
        vec3 r = reflect(vec3(0.0, 0.0, -1.0), n);
        float band = step(0.5, fract(r.y * limit * 0.5 + 0.5));
        colour = vec4(mix(vec3(0.08), vec3(0.95), band) * (0.75 + 0.25 * key), alpha);
    }
})";

const char* kEdgeVertex = R"(#version 300 es
layout(location = 0) in vec3 a;
layout(location = 1) in vec3 b;
layout(location = 2) in vec2 corner;  // x: 0 at a, 1 at b. y: which side, -1 or 1.
layout(location = 3) in uint edge;
uniform mat4 viewProjection;
uniform vec2 viewport;
uniform float width;
uniform float selectedWidth;
uniform sampler2D selected;
flat out float chosen;
flat out uint id;
out vec3 world;
void main() {
    world = corner.x < 0.5 ? a : b;
    vec4 pa = viewProjection * vec4(a, 1.0);
    vec4 pb = viewProjection * vec4(b, 1.0);
    vec4 p = corner.x < 0.5 ? pa : pb;
    // The segment widened to a strip a fixed number of pixels across.
    vec2 sa = pa.xy / pa.w * viewport * 0.5;
    vec2 sb = pb.xy / pb.w * viewport * 0.5;
    vec2 d = sb - sa;
    vec2 dir = length(d) > 1e-4 ? normalize(d) : vec2(1.0, 0.0);
    chosen = texelFetch(selected, ivec2(int(edge) % 1024, int(edge) / 1024), 0).r;
    float w = mix(width, selectedWidth, chosen);
    p.xy += vec2(-dir.y, dir.x) * corner.y * w * 0.5 / (viewport * 0.5) * p.w;
    id = edge;
    gl_Position = p;
})";

const char* kEdgeFragment = R"(#version 300 es
precision mediump float;
flat in float chosen;
flat in uint id;
in vec3 world;
uniform vec4 edgeColour;
uniform vec4 clip;
uniform bool clipping;
out vec4 colour;
void main() {
    if (clipping && dot(vec4(world, 1.0), clip) < 0.0) discard;
    colour = mix(edgeColour, vec4(1.0, 0.48, 0.24, 1.0), chosen);
})";

// The id passes write the picked number as colour.
const char* kFaceIdVertex = R"(#version 300 es
layout(location = 0) in vec3 position;
layout(location = 2) in uint face;
uniform mat4 viewProjection;
flat out uint id;
out vec3 world;
void main() {
    id = face;
    world = position;
    gl_Position = viewProjection * vec4(position, 1.0);
})";

// Plain lines in one colour: the wireframe over a mesh being sculpted.
const char* kLineVertex = R"(#version 300 es
layout(location = 0) in vec3 position;
uniform mat4 viewProjection;
void main() {
    gl_Position = viewProjection * vec4(position, 1.0);
})";

const char* kLineFragment = R"(#version 300 es
precision mediump float;
uniform vec4 colour;
out vec4 result;
void main() {
    result = colour;
})";

const char* kIdFragment = R"(#version 300 es
precision highp float;
// Ids use bits up to 23; a fragment shader's ints are mediump unless asked,
// and many phone GPUs keep those to 16 bits, which loses the body and edge bits.
precision highp int;
flat in uint id;
in vec3 world;
uniform uint base;
uniform vec4 clip;
uniform bool clipping;
out vec4 colour;
void main() {
    if (clipping && dot(vec4(world, 1.0), clip) < 0.0) discard;
    uint v = base + id + 1u;
    colour = vec4(float(v & 255u), float((v >> 8) & 255u), float((v >> 16) & 255u), 255.0) / 255.0;
})";

// Corners: points a fixed number of pixels across, numbered by their order.
const char* kCornerVertex = R"(#version 300 es
layout(location = 0) in vec3 position;
uniform mat4 viewProjection;
uniform float size;
flat out uint id;
out vec3 world;
void main() {
    id = uint(gl_VertexID);
    world = position;
    gl_Position = viewProjection * vec4(position, 1.0);
    // A touch nearer than the edges meeting there, so the corner wins where they overlap.
    gl_Position.z -= 0.002 * gl_Position.w;
    gl_PointSize = size;
})";

const char* kCornerFragment = R"(#version 300 es
precision mediump float;
flat in uint id;
in vec3 world;
uniform vec4 cornerColour;
uniform vec4 clip;
uniform bool clipping;
out vec4 colour;
void main() {
    if (clipping && dot(vec4(world, 1.0), clip) < 0.0) discard;
    // Round dots.
    if (length(gl_PointCoord - vec2(0.5)) > 0.5) discard;
    colour = cornerColour;
})";

// Pictures on planes.
const char* kCanvasVertex = R"(#version 300 es
layout(location = 0) in vec3 position;
layout(location = 1) in vec2 corner;
uniform mat4 viewProjection;
out vec2 coord;
void main() {
    coord = corner;
    gl_Position = viewProjection * vec4(position, 1.0);
})";

const char* kCanvasFragment = R"(#version 300 es
precision mediump float;
in vec2 coord;
uniform sampler2D picture;
uniform float opacity;
out vec4 colour;
void main() {
    vec4 c = texture(picture, coord);
    colour = vec4(c.rgb, c.a * opacity);
})";

uint32_t compile(GLenum type, const char* source) {
    GLuint s = glCreateShader(type);
    // Desktop OpenGL takes the same shaders under its own version line.
    std::string text = source;
    if (desktopGl) text.replace(0, text.find('\n'), "#version 330 core");
    const char* p = text.c_str();
    glShaderSource(s, 1, &p, nullptr);
    glCompileShader(s);
    return s;
}

uint32_t link(const char* vertex, const char* fragment) {
    GLuint p = glCreateProgram();
    GLuint vs = compile(GL_VERTEX_SHADER, vertex), fs = compile(GL_FRAGMENT_SHADER, fragment);
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
}

/** Column-major 4x4 multiply, out = a * b. */
void multiply(const float* a, const float* b, float* out) {
    for (int c = 0; c < 4; ++c)
        for (int r = 0; r < 4; ++r) {
            float sum = 0;
            for (int k = 0; k < 4; ++k) sum += a[k * 4 + r] * b[c * 4 + k];
            out[c * 4 + r] = sum;
        }
}

float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }

/** Shortest way round from one angle to another. */
float angleTowards(float from, float to) {
    float d = std::remainder(to - from, 2 * float(M_PI));
    return from + d;
}

uint32_t makeSelectionTexture(uint32_t count) {
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    int rows = int(std::max<uint32_t>(1, (count + kSelectionRow - 1) / kSelectionRow));
    std::vector<uint8_t> zero(size_t(kSelectionRow * rows), 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, kSelectionRow, rows, 0, GL_RED, GL_UNSIGNED_BYTE, zero.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    return t;
}

}  // namespace

void Renderer::surfaceCreated() {
    // The old context and everything in it is gone.
    gpu_.clear();
    pickFbo_ = pickColour_ = pickDepth_ = 0;
    pickWidth_ = pickHeight_ = 0;
    faceProgram_ = link(kFaceVertex, kFaceFragment);
    edgeProgram_ = link(kEdgeVertex, kEdgeFragment);
    faceIdProgram_ = link(kFaceIdVertex, kIdFragment);
    edgeIdProgram_ = link(kEdgeVertex, kIdFragment);
    cornerProgram_ = link(kCornerVertex, kCornerFragment);
    cornerIdProgram_ = link(kCornerVertex, kIdFragment);
    canvasProgram_ = link(kCanvasVertex, kCanvasFragment);
    lineProgram_ = link(kLineVertex, kLineFragment);
    // The old context's textures went with it.
    canvasTextures_.clear();
    canvasVao_ = canvasVbo_ = 0;
    canvasesDirty_ = true;
    sculptVao_ = sculptVbo_ = sculptIbo_ = blankTexture_ = sculptLineVao_ = sculptLineIbo_ = 0;
    sculptVertexRoom_ = sculptTriangleRoom_ = 0;
    // Desktop GL sizes points from the shader only when asked.
    if (desktopGl) glEnable(0x8642);  // GL_PROGRAM_POINT_SIZE
    glEnable(GL_DEPTH_TEST);
    bodiesDirty_ = true;
}

void Renderer::surfaceChanged(int width, int height) {
    width_ = std::max(width, 1);
    height_ = std::max(height, 1);
    glViewport(0, 0, width_, height_);
}

void Renderer::setBodies(std::vector<DisplayMesh> bodies, bool refit) {
    bodies_ = std::move(bodies);
    bodiesDirty_ = true;
    selection_.clear();
    bedZ_ = 1e30f;
    for (const auto& b : bodies_)
        if (b.body)
            for (size_t i = 2; i < b.positions.size(); i += 3) bedZ_ = std::min(bedZ_, b.positions[i]);
    float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
    bool any = false;
    auto take = [&](const std::vector<float>& xyz) {
        for (size_t i = 0; i + 2 < xyz.size(); i += 3) {
            any = true;
            for (int k = 0; k < 3; ++k) {
                lo[k] = std::min(lo[k], xyz[i + k]);
                hi[k] = std::max(hi[k], xyz[i + k]);
            }
        }
    };
    // Faces, and edges too, so a sketch of open lines with no area is framed as well.
    for (const auto& b : bodies_) {
        take(b.positions);
        for (const auto& e : b.edges) take(e.points);
    }
    if (sculpt_) {
        auto box = sculpt_->bounds();
        std::vector<float> corners{box[0], box[1], box[2], box[3], box[4], box[5]};
        take(corners);
    }
    if (any) {
        float d2 = 0;
        for (int k = 0; k < 3; ++k) {
            centre_[k] = (lo[k] + hi[k]) * 0.5f;
            d2 += (hi[k] - lo[k]) * (hi[k] - lo[k]);
        }
        radius_ = std::max(std::sqrt(d2) * 0.5f, 0.1f);
    } else {
        centre_[0] = centre_[1] = centre_[2] = 0;
        radius_ = 40;
    }
    if (refit) fit();
}

void Renderer::setSelection(const std::vector<Pick>& selection) {
    selection_ = selection;
    selectionDirty_ = true;
}

void Renderer::releaseGpu() {
    for (auto& g : gpu_) {
        glDeleteVertexArrays(1, &g.faceVao);
        glDeleteVertexArrays(1, &g.edgeVao);
        glDeleteVertexArrays(1, &g.cornerVao);
        GLuint buffers[5] = {g.faceVbo, g.faceIbo, g.edgeVbo, g.edgeIbo, g.cornerVbo};
        glDeleteBuffers(5, buffers);
        GLuint textures[2] = {g.faceSelected, g.edgeSelected};
        glDeleteTextures(2, textures);
    }
    gpu_.clear();
}

void Renderer::upload() {
    releaseGpu();
    for (const auto& b : bodies_) {
        Gpu g;
        g.faceCount = b.faceCount;
        std::copy(b.edgeColour, b.edgeColour + 4, g.edgeColour);
        std::copy(b.faceColour, b.faceColour + 4, g.faceColour);
        g.behind = b.behind;
        g.body = b.body;
        g.edgeCount = uint32_t(b.edges.size());

        glGenVertexArrays(1, &g.faceVao);
        glBindVertexArray(g.faceVao);
        glGenBuffers(1, &g.faceVbo);
        glBindBuffer(GL_ARRAY_BUFFER, g.faceVbo);
        size_t n = b.vertexCount();
        // Laid out as all positions, then all normals, then all face numbers.
        // Then the thickness at each vertex, a large number where it's not known.
        size_t posBytes = n * 12, faceBytes = n * 4;
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(posBytes * 2 + faceBytes * 2), nullptr, GL_STATIC_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0, GLsizeiptr(posBytes), b.positions.data());
        glBufferSubData(GL_ARRAY_BUFFER, GLintptr(posBytes), GLsizeiptr(posBytes), b.normals.data());
        glBufferSubData(GL_ARRAY_BUFFER, GLintptr(posBytes * 2), GLsizeiptr(faceBytes), b.faceOfVertex.data());
        std::vector<float> thick = b.shade.size() == n ? b.shade : std::vector<float>(n, 1e9f);
        glBufferSubData(GL_ARRAY_BUFFER, GLintptr(posBytes * 2 + faceBytes), GLsizeiptr(faceBytes), thick.data());
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 0, reinterpret_cast<void*>(posBytes));
        glEnableVertexAttribArray(2);
        glVertexAttribIPointer(2, 1, GL_UNSIGNED_INT, 0, reinterpret_cast<void*>(posBytes * 2));
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, 0, reinterpret_cast<void*>(posBytes * 2 + faceBytes));
        glGenBuffers(1, &g.faceIbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.faceIbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(b.indices.size() * 4), b.indices.data(), GL_STATIC_DRAW);
        g.faceIndices = int(b.indices.size());

        // Each edge segment becomes four corners of a strip: a, b, corner, edge number.
        struct Corner {
            float a[3], b[3], corner[2];
            uint32_t edge;
        };
        std::vector<Corner> corners;
        std::vector<uint32_t> idx;
        for (uint32_t e = 0; e < b.edges.size(); ++e) {
            const auto& pts = b.edges[e].points;
            for (size_t i = 0; i + 5 < pts.size(); i += 3) {
                uint32_t base = uint32_t(corners.size());
                for (int k = 0; k < 4; ++k) {
                    Corner c{};
                    std::copy(&pts[i], &pts[i] + 3, c.a);
                    std::copy(&pts[i + 3], &pts[i + 3] + 3, c.b);
                    c.corner[0] = float(k / 2);
                    c.corner[1] = (k % 2) ? 1.0f : -1.0f;
                    c.edge = e;
                    corners.push_back(c);
                }
                idx.insert(idx.end(), {base, base + 1, base + 2, base + 2, base + 1, base + 3});
            }
        }
        glGenVertexArrays(1, &g.edgeVao);
        glBindVertexArray(g.edgeVao);
        glGenBuffers(1, &g.edgeVbo);
        glBindBuffer(GL_ARRAY_BUFFER, g.edgeVbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(corners.size() * sizeof(Corner)), corners.data(), GL_STATIC_DRAW);
        const GLsizei stride = sizeof(Corner);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Corner, a)));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Corner, b)));
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<void*>(offsetof(Corner, corner)));
        glEnableVertexAttribArray(3);
        glVertexAttribIPointer(3, 1, GL_UNSIGNED_INT, stride, reinterpret_cast<void*>(offsetof(Corner, edge)));
        glGenBuffers(1, &g.edgeIbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.edgeIbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(idx.size() * 4), idx.data(), GL_STATIC_DRAW);
        g.edgeIndices = int(idx.size());
        glBindVertexArray(0);

        if (!b.corners.empty()) {
            glGenVertexArrays(1, &g.cornerVao);
            glBindVertexArray(g.cornerVao);
            glGenBuffers(1, &g.cornerVbo);
            glBindBuffer(GL_ARRAY_BUFFER, g.cornerVbo);
            glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(b.corners.size() * 4), b.corners.data(), GL_STATIC_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
            g.cornerCount = int(b.corners.size() / 3);
            glBindVertexArray(0);
        }

        g.faceSelected = makeSelectionTexture(g.faceCount);
        g.edgeSelected = makeSelectionTexture(g.edgeCount);
        gpu_.push_back(g);
    }
    bodiesDirty_ = false;
    selectionDirty_ = true;
}

void Renderer::uploadSelection() {
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    for (uint32_t i = 0; i < gpu_.size(); ++i) {
        const Gpu& g = gpu_[i];
        for (int kind = 0; kind < 2; ++kind) {
            uint32_t count = kind == 0 ? g.faceCount : g.edgeCount;
            int rows = int(std::max<uint32_t>(1, (count + kSelectionRow - 1) / kSelectionRow));
            std::vector<uint8_t> texels(size_t(kSelectionRow * rows), 0);
            for (const Pick& p : selection_) {
                if (p.body == i && p.kind == (kind == 0 ? Pick::Face : Pick::Edge) && p.index < count) texels[p.index] = 255;
            }
            glBindTexture(GL_TEXTURE_2D, kind == 0 ? g.faceSelected : g.edgeSelected);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kSelectionRow, rows, GL_RED, GL_UNSIGNED_BYTE, texels.data());
        }
    }
    selectionDirty_ = false;
}

void Renderer::orbit(float dx, float dy) {
    moving_ = false;
    yaw_ -= dx * 0.008f;
    pitch_ = std::clamp(pitch_ + dy * 0.008f, -1.55f, 1.55f);
}

float Renderer::distance() const {
    // Far enough back that the model's bounding sphere fits the narrower of the
    // two fields of view of the uncovered part, with a little margin.
    float t = std::tan(kHalfFov);
    float w = std::max(float(width_) - covered_[0] - covered_[2], float(width_) * 0.2f);
    float h = std::max(float(height_) - covered_[1] - covered_[3], float(height_) * 0.2f);
    float across = t * w / float(height_);
    float up = t * h / float(height_);
    return radius_ * 1.1f / std::sin(std::atan(std::min(across, up))) / zoom_;
}

float Renderer::perPixel() const {
    return 2 * distance() * std::tan(kHalfFov) / float(height_);
}

bool Renderer::easeCovered() {
    bool moving = false;
    for (int k = 0; k < 4; ++k) {
        float gap = coveredGoal_[k] - covered_[k];
        if (std::abs(gap) < 0.5f) covered_[k] = coveredGoal_[k];
        else {
            covered_[k] += gap * 0.3f;
            moving = true;
        }
    }
    return moving;
}

void Renderer::pan(float dx, float dy) {
    moving_ = false;
    float mm = perPixel();
    float cy = std::cos(yaw_), sy = std::sin(yaw_), cp = std::cos(pitch_), sp = std::sin(pitch_);
    float right[3] = {-sy, cy, 0};
    float up[3] = {-sp * cy, -sp * sy, cp};
    for (int k = 0; k < 3; ++k) target_[k] += (-dx * right[k] + dy * up[k]) * mm;
}

void Renderer::zoom(float factor) {
    moving_ = false;
    zoom_ = std::clamp(zoom_ * factor, 0.05f, 40.0f);
}

void Renderer::zoomAt(float factor, float x, float y) {
    float before = perPixel();
    zoom(factor);
    // The point under (x, y), on the plane through the target facing the
    // view, keeps its place: the target moves towards it as the scale shrinks.
    float shrink = before - perPixel();
    // From the middle of the uncovered part, which is where the target shows.
    float ox = x - (covered_[0] + (float(width_) - covered_[0] - covered_[2]) / 2);
    float oy = y - (covered_[1] + (float(height_) - covered_[1] - covered_[3]) / 2);
    float cy = std::cos(yaw_), sy = std::sin(yaw_), cp = std::cos(pitch_), sp = std::sin(pitch_);
    float right[3] = {-sy, cy, 0};
    float up[3] = {-sp * cy, -sp * sy, cp};
    for (int k = 0; k < 3; ++k) target_[k] += (ox * right[k] - oy * up[k]) * shrink;
}

void Renderer::fit() {
    View goal{{centre_[0], centre_[1], centre_[2]}, yaw_, pitch_, 1};
    from_ = {{target_[0], target_[1], target_[2]}, yaw_, pitch_, zoom_};
    to_ = goal;
    moveStart_ = std::chrono::steady_clock::now();
    moving_ = true;
}

void Renderer::viewFrom(float yaw, float pitch) {
    from_ = {{target_[0], target_[1], target_[2]}, yaw_, pitch_, zoom_};
    to_ = {{centre_[0], centre_[1], centre_[2]}, angleTowards(yaw_, yaw), pitch, 1};
    moveStart_ = std::chrono::steady_clock::now();
    moving_ = true;
}

void Renderer::animate() {
    if (!moving_) return;
    float t = std::chrono::duration<float>(std::chrono::steady_clock::now() - moveStart_).count() / kMoveSeconds;
    float e = ease(std::min(t, 1.0f));
    for (int k = 0; k < 3; ++k) target_[k] = from_.target[k] + (to_.target[k] - from_.target[k]) * e;
    yaw_ = from_.yaw + (to_.yaw - from_.yaw) * e;
    pitch_ = from_.pitch + (to_.pitch - from_.pitch) * e;
    // Zoom eases in log space so it feels even.
    zoom_ = std::exp(std::log(from_.zoom) + (std::log(to_.zoom) - std::log(from_.zoom)) * e);
    if (t >= 1) moving_ = false;
}

void Renderer::camera(float* vp, float* normal) const {
    float t = std::tan(kHalfFov);
    float aspect = float(width_) / float(height_);
    float distance = this->distance();

    float cy = std::cos(yaw_), sy = std::sin(yaw_), cp = std::cos(pitch_), sp = std::sin(pitch_);
    float eye[3] = {target_[0] + distance * cp * cy, target_[1] + distance * cp * sy, target_[2] + distance * sp};
    float f[3] = {-cp * cy, -cp * sy, -sp};
    float s[3] = {-sy, cy, 0};  // f × up, with up = +Z.
    float u[3] = {s[1] * f[2] - s[2] * f[1], s[2] * f[0] - s[0] * f[2], s[0] * f[1] - s[1] * f[0]};

    float view[16] = {s[0], u[0], -f[0], 0, s[1], u[1], -f[1], 0, s[2], u[2], -f[2], 0,
                      -(s[0] * eye[0] + s[1] * eye[1] + s[2] * eye[2]),
                      -(u[0] * eye[0] + u[1] * eye[1] + u[2] * eye[2]),
                      f[0] * eye[0] + f[1] * eye[1] + f[2] * eye[2], 1};
    float panned = std::sqrt((target_[0] - centre_[0]) * (target_[0] - centre_[0]) +
                             (target_[1] - centre_[1]) * (target_[1] - centre_[1]) +
                             (target_[2] - centre_[2]) * (target_[2] - centre_[2]));
    float reach = radius_ * 1.5f + panned;
    float near = std::max(distance - reach, distance * 0.01f), far = distance + reach;
    float proj[16] = {1 / (t * aspect), 0, 0, 0, 0, 1 / t, 0, 0, 0, 0, -(far + near) / (far - near), -1,
                      0, 0, -2 * far * near / (far - near), 0};
    // Shifted so the target shows in the middle of the uncovered part.
    proj[8] = -(covered_[0] - covered_[2]) / float(width_);
    proj[9] = -(covered_[3] - covered_[1]) / float(height_);
    multiply(proj, view, vp);
    float n[9] = {view[0], view[1], view[2], view[4], view[5], view[6], view[8], view[9], view[10]};
    std::copy(n, n + 9, normal);
}

void Renderer::drawScene(bool ids, const float* vp, const float* normal) {
    GLuint faceProgram = ids ? faceIdProgram_ : faceProgram_;
    GLuint edgeProgram = ids ? edgeIdProgram_ : edgeProgram_;

    // Faces sit a little behind their edges so the edges always show.
    glEnable(GL_POLYGON_OFFSET_FILL);
    glPolygonOffset(1.5f, 2.0f);
    glUseProgram(faceProgram);
    glUniformMatrix4fv(glGetUniformLocation(faceProgram, "viewProjection"), 1, GL_FALSE, vp);
    glUniform4fv(glGetUniformLocation(faceProgram, "clip"), 1, clip_);
    glUniform1i(glGetUniformLocation(faceProgram, "clipping"), clipping_ ? 1 : 0);
    if (!ids) {
        glUniformMatrix3fv(glGetUniformLocation(faceProgram, "view"), 1, GL_FALSE, normal);
        glUniform1i(glGetUniformLocation(faceProgram, "selected"), 0);
        glUniform1i(glGetUniformLocation(faceProgram, "analysis"), analysis_);
        glUniform1f(glGetUniformLocation(faceProgram, "limit"), limit_);
        glUniform1f(glGetUniformLocation(faceProgram, "bedZ"), bedZ_);
    }
    // For picking, what's drawn behind (construction planes) goes first and
    // leaves no depth, so bodies and sketch areas anywhere in front of or
    // behind it win, and it's picked only where there's nothing else.
    if (ids) {
        glPolygonOffset(0.0f, 0.0f);
        glDepthMask(GL_FALSE);
        for (uint32_t i = 0; i < gpu_.size(); ++i) {
            const Gpu& g = gpu_[i];
            if (!g.behind || g.faceColour[3] >= 1.0f || g.faceIndices == 0) continue;
            glUniform1ui(glGetUniformLocation(faceProgram, "base"), i << 20);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, g.faceSelected);
            glBindVertexArray(g.faceVao);
            glDrawElements(GL_TRIANGLES, g.faceIndices, GL_UNSIGNED_INT, nullptr);
        }
        glDepthMask(GL_TRUE);
        glPolygonOffset(1.5f, 2.0f);
    }
    // Solid faces first, then see-through ones over them without hiding what's behind.
    // See-through faces (sketch areas) aren't pushed back, so on a face they're
    // sketched on they come out on top, for tapping too.
    for (int pass = 0; pass < 2; ++pass) {
        bool seeThrough = pass == 1;
        if (seeThrough) glPolygonOffset(0.0f, 0.0f);
        if (seeThrough && !ids) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(GL_FALSE);
        }
        for (uint32_t i = 0; i < gpu_.size(); ++i) {
            const Gpu& g = gpu_[i];
            if ((g.faceColour[3] < 1.0f) != seeThrough || g.faceIndices == 0) continue;
            if (ids && g.behind && seeThrough) continue;
            if (seeThrough) glPolygonOffset(g.behind ? 2.0f : 0.0f, g.behind ? 4.0f : 0.0f);
            if (ids) glUniform1ui(glGetUniformLocation(faceProgram, "base"), i << 20);
            else {
                glUniform4fv(glGetUniformLocation(faceProgram, "faceColour"), 1, g.faceColour);
                glUniform1i(glGetUniformLocation(faceProgram, "analysed"), g.body ? 1 : 0);
            }
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, g.faceSelected);
            glBindVertexArray(g.faceVao);
            bool onTop = ids && seeThrough && areasFirst_;
            if (onTop) glDepthFunc(GL_ALWAYS);
            glDrawElements(GL_TRIANGLES, g.faceIndices, GL_UNSIGNED_INT, nullptr);
            if (onTop) glDepthFunc(GL_LESS);
        }
        if (seeThrough && !ids) {
            glDepthMask(GL_TRUE);
            glDisable(GL_BLEND);
        }
    }
    glDisable(GL_POLYGON_OFFSET_FILL);
    if (!ids && sculpt_ && sculptVao_) drawSculpt(vp, normal);

    if (!ids) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }
    if (!ids) drawCanvases(vp);
    glUseProgram(edgeProgram);
    glUniformMatrix4fv(glGetUniformLocation(edgeProgram, "viewProjection"), 1, GL_FALSE, vp);
    glUniform4fv(glGetUniformLocation(edgeProgram, "clip"), 1, clip_);
    glUniform1i(glGetUniformLocation(edgeProgram, "clipping"), clipping_ ? 1 : 0);
    glUniform2f(glGetUniformLocation(edgeProgram, "viewport"), float(width_), float(height_));
    // The id pass draws edges a little wider than they show; pickNear reaches the rest of the way.
    glUniform1f(glGetUniformLocation(edgeProgram, "width"), (ids ? 4.0f : 1.6f) * density_);
    glUniform1f(glGetUniformLocation(edgeProgram, "selectedWidth"), (ids ? 4.0f : 3.5f) * density_);
    glUniform1i(glGetUniformLocation(edgeProgram, "selected"), 0);
    for (uint32_t i = 0; i < gpu_.size(); ++i) {
        const Gpu& g = gpu_[i];
        if (ids) glUniform1ui(glGetUniformLocation(edgeProgram, "base"), (i << 20) | kEdgeBit);
        else glUniform4fv(glGetUniformLocation(edgeProgram, "edgeColour"), 1, g.edgeColour);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g.edgeSelected);
        glBindVertexArray(g.edgeVao);
        glDrawElements(GL_TRIANGLES, g.edgeIndices, GL_UNSIGNED_INT, nullptr);
    }
    glBindVertexArray(0);

    // Corners, over the edges they end: small dots, a finger wide to pick, and bigger when picked.
    GLuint cornerProgram = ids ? cornerIdProgram_ : cornerProgram_;
    glUseProgram(cornerProgram);
    glUniformMatrix4fv(glGetUniformLocation(cornerProgram, "viewProjection"), 1, GL_FALSE, vp);
    glUniform4fv(glGetUniformLocation(cornerProgram, "clip"), 1, clip_);
    glUniform1i(glGetUniformLocation(cornerProgram, "clipping"), clipping_ ? 1 : 0);
    glDepthFunc(GL_LEQUAL);
    for (uint32_t i = 0; i < gpu_.size(); ++i) {
        const Gpu& g = gpu_[i];
        if (g.cornerCount == 0) continue;
        glBindVertexArray(g.cornerVao);
        if (ids) {
            glUniform1ui(glGetUniformLocation(cornerProgram, "base"), (i << 20) | kEdgeBit | kCornerBit);
            glUniform1f(glGetUniformLocation(cornerProgram, "size"), 18.0f * density_);
            glDrawArrays(GL_POINTS, 0, g.cornerCount);
            continue;
        }
        // Lighter than the edges, so they show where edges meet.
        const float dot[4] = {0.55f, 0.62f, 0.60f, 0.95f};
        glUniform4fv(glGetUniformLocation(cornerProgram, "cornerColour"), 1, dot);
        glUniform1f(glGetUniformLocation(cornerProgram, "size"), 5.0f * density_);
        glDrawArrays(GL_POINTS, 0, g.cornerCount);
        const float picked[4] = {1.0f, 0.48f, 0.24f, 1.0f};
        glUniform4fv(glGetUniformLocation(cornerProgram, "cornerColour"), 1, picked);
        glUniform1f(glGetUniformLocation(cornerProgram, "size"), 10.0f * density_);
        for (const auto& p : selection_)
            if (p.kind == Pick::Vertex && p.body == i && int(p.index) < g.cornerCount) glDrawArrays(GL_POINTS, GLint(p.index), 1);
    }
    glDepthFunc(GL_LESS);
    glBindVertexArray(0);
    if (!ids) glDisable(GL_BLEND);
}

void Renderer::setSculpt(Sculpt* sculpt, bool refit) {
    sculpt_ = sculpt;
    if (!sculpt) return;
    // Sent whole on the next frame.
    sculpt->takeDirty();
    sculptVertexRoom_ = sculptTriangleRoom_ = 0;
    auto box = sculpt->bounds();
    float d2 = 0;
    for (int k = 0; k < 3; ++k) {
        centre_[k] = (box[size_t(k)] + box[size_t(k) + 3]) * 0.5f;
        d2 += (box[size_t(k) + 3] - box[size_t(k)]) * (box[size_t(k) + 3] - box[size_t(k)]);
    }
    radius_ = std::max(std::sqrt(d2) * 0.5f, 0.1f);
    if (refit) fit();
}

void Renderer::uploadSculpt() {
    Sculpt& s = *sculpt_;
    const auto& v = s.vertices();
    const auto& t = s.triangles();
    auto dirty = s.takeDirty();
    const size_t vertexBytes = sizeof(Sculpt::Vertex);
    if (!sculptVao_ || v.size() > sculptVertexRoom_ || t.size() > sculptTriangleRoom_) {
        if (!sculptVao_) {
            glGenVertexArrays(1, &sculptVao_);
            glGenBuffers(1, &sculptVbo_);
            glGenBuffers(1, &sculptIbo_);
            blankTexture_ = makeSelectionTexture(1);
        }
        // Room to grow as detail is added, so a stroke doesn't send it all every frame.
        sculptVertexRoom_ = v.size() + v.size() / 2 + 4096;
        sculptTriangleRoom_ = t.size() + t.size() / 2 + 8192;
        glBindVertexArray(sculptVao_);
        glBindBuffer(GL_ARRAY_BUFFER, sculptVbo_);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(sculptVertexRoom_ * vertexBytes), nullptr, GL_DYNAMIC_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0, GLsizeiptr(v.size() * vertexBytes), v.data());
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, GLsizei(vertexBytes), reinterpret_cast<void*>(offsetof(Sculpt::Vertex, p)));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, GLsizei(vertexBytes), reinterpret_cast<void*>(offsetof(Sculpt::Vertex, n)));
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, GLsizei(vertexBytes), reinterpret_cast<void*>(offsetof(Sculpt::Vertex, mask)));
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, sculptIbo_);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(sculptTriangleRoom_ * 12), nullptr, GL_DYNAMIC_DRAW);
        glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, GLsizeiptr(t.size() * 12), t.data());
        // The wireframe: each triangle's three sides as lines, on the same points.
        if (!sculptLineVao_) {
            glGenVertexArrays(1, &sculptLineVao_);
            glGenBuffers(1, &sculptLineIbo_);
        }
        glBindVertexArray(sculptLineVao_);
        glBindBuffer(GL_ARRAY_BUFFER, sculptVbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, GLsizei(vertexBytes), reinterpret_cast<void*>(offsetof(Sculpt::Vertex, p)));
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, sculptLineIbo_);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(sculptTriangleRoom_ * 24), nullptr, GL_DYNAMIC_DRAW);
        uploadLines(0, t.size());
        glBindVertexArray(0);
        return;
    }
    glBindVertexArray(sculptVao_);
    glBindBuffer(GL_ARRAY_BUFFER, sculptVbo_);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, sculptIbo_);
    if (dirty.all) {
        glBufferSubData(GL_ARRAY_BUFFER, 0, GLsizeiptr(v.size() * vertexBytes), v.data());
        glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, GLsizeiptr(t.size() * 12), t.data());
        glBindVertexArray(sculptLineVao_);
        uploadLines(0, t.size());
    } else {
        for (uint32_t b : dirty.vertexBlocks) {
            size_t from = size_t(b) * Sculpt::kBlock;
            if (from >= v.size()) continue;
            size_t count = std::min<size_t>(Sculpt::kBlock, v.size() - from);
            glBufferSubData(GL_ARRAY_BUFFER, GLintptr(from * vertexBytes), GLsizeiptr(count * vertexBytes), &v[from]);
        }
        for (uint32_t b : dirty.triangleBlocks) {
            size_t from = size_t(b) * Sculpt::kBlock;
            if (from >= t.size()) continue;
            size_t count = std::min<size_t>(Sculpt::kBlock, t.size() - from);
            glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, GLintptr(from * 12), GLsizeiptr(count * 12), &t[from]);
        }
        glBindVertexArray(sculptLineVao_);
        for (uint32_t b : dirty.triangleBlocks) {
            size_t from = size_t(b) * Sculpt::kBlock;
            if (from >= t.size()) continue;
            uploadLines(from, std::min<size_t>(Sculpt::kBlock, t.size() - from));
        }
    }
    glBindVertexArray(0);
}

void Renderer::uploadLines(size_t from, size_t count) {
    // With the line VAO bound, so its element buffer is the one written.
    const auto& t = sculpt_->triangles();
    std::vector<uint32_t> lines(count * 6);
    for (size_t i = 0; i < count; ++i) {
        const auto& tr = t[from + i];
        uint32_t* l = &lines[i * 6];
        l[0] = tr[0]; l[1] = tr[1]; l[2] = tr[1]; l[3] = tr[2]; l[4] = tr[2]; l[5] = tr[0];
    }
    glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, GLintptr(from * 24), GLsizeiptr(lines.size() * 4), lines.data());
}

void Renderer::drawSculpt(const float* vp, const float* normal) {
    glUseProgram(faceProgram_);
    glUniformMatrix4fv(glGetUniformLocation(faceProgram_, "viewProjection"), 1, GL_FALSE, vp);
    glUniformMatrix3fv(glGetUniformLocation(faceProgram_, "view"), 1, GL_FALSE, normal);
    glUniform4fv(glGetUniformLocation(faceProgram_, "clip"), 1, clip_);
    glUniform1i(glGetUniformLocation(faceProgram_, "clipping"), clipping_ ? 1 : 0);
    glUniform1i(glGetUniformLocation(faceProgram_, "selected"), 0);
    glUniform1i(glGetUniformLocation(faceProgram_, "analysed"), 0);
    glUniform1i(glGetUniformLocation(faceProgram_, "masking"), 1);
    // Clay, grey stone, white porcelain or terracotta.
    static const float looks[4][4] = {{0.82f, 0.74f, 0.66f, 1}, {0.66f, 0.68f, 0.70f, 1}, {0.93f, 0.92f, 0.90f, 1}, {0.80f, 0.48f, 0.36f, 1}};
    glUniform4fv(glGetUniformLocation(faceProgram_, "faceColour"), 1, looks[std::clamp(sculptLook_, 0, 3)]);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, blankTexture_);
    glBindVertexArray(sculptVao_);
    glVertexAttribI4ui(2, 0, 0, 0, 0);
    // Pushed back a touch under a wireframe, so its lines show.
    if (sculptWire_) {
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.0f, 1.0f);
    }
    glDrawElements(GL_TRIANGLES, GLsizei(sculpt_->triangles().size() * 3), GL_UNSIGNED_INT, nullptr);
    glUniform1i(glGetUniformLocation(faceProgram_, "masking"), 0);
    if (sculptWire_ && sculptLineVao_) {
        glDisable(GL_POLYGON_OFFSET_FILL);
        glUseProgram(lineProgram_);
        glUniformMatrix4fv(glGetUniformLocation(lineProgram_, "viewProjection"), 1, GL_FALSE, vp);
        const float ink[4] = {0.15f, 0.17f, 0.17f, 0.55f};
        glUniform4fv(glGetUniformLocation(lineProgram_, "colour"), 1, ink);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthFunc(GL_LEQUAL);
        glBindVertexArray(sculptLineVao_);
        glDrawElements(GL_LINES, GLsizei(sculpt_->triangles().size() * 6), GL_UNSIGNED_INT, nullptr);
        glDepthFunc(GL_LESS);
        glDisable(GL_BLEND);
    }
    glBindVertexArray(0);
}

bool Renderer::draw() {
    if (sculpt_) uploadSculpt();
    if (bodiesDirty_) upload();
    if (selectionDirty_) uploadSelection();
    animate();
    bool easing = easeCovered();
    glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer);
    glViewport(0, 0, width_, height_);
    glClearColor(0.102f, 0.125f, 0.122f, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    float vp[16], normal[9];
    camera(vp, normal);
    std::copy(vp, vp + 16, lastViewProjection_);
    drawScene(false, vp, normal);
    return moving_ || easing;
}

void Renderer::ensurePickTarget() {
    if (pickFbo_ && pickWidth_ == width_ && pickHeight_ == height_) return;
    if (pickFbo_) {
        glDeleteFramebuffers(1, &pickFbo_);
        glDeleteTextures(1, &pickColour_);
        glDeleteRenderbuffers(1, &pickDepth_);
    }
    glGenFramebuffers(1, &pickFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, pickFbo_);
    glGenTextures(1, &pickColour_);
    glBindTexture(GL_TEXTURE_2D, pickColour_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width_, height_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, pickColour_, 0);
    glGenRenderbuffers(1, &pickDepth_);
    glBindRenderbuffer(GL_RENDERBUFFER, pickDepth_);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width_, height_);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, pickDepth_);
    pickWidth_ = width_;
    pickHeight_ = height_;
}

void Renderer::drawIds() {
    if (bodiesDirty_) upload();
    ensurePickTarget();
    glBindFramebuffer(GL_FRAMEBUFFER, pickFbo_);
    glViewport(0, 0, width_, height_);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    float vp[16], normal[9];
    camera(vp, normal);
    drawScene(true, vp, normal);
}

Pick Renderer::pick(float x, float y) {
    drawIds();
    uint8_t px[4] = {0, 0, 0, 0};
    int ix = std::clamp(int(x), 0, width_ - 1), iy = std::clamp(height_ - 1 - int(y), 0, height_ - 1);
    glReadPixels(ix, iy, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer);
    return fromId(uint32_t(px[0]) | uint32_t(px[1]) << 8 | uint32_t(px[2]) << 16);
}

Pick Renderer::pickNear(float x, float y, float reach, const std::function<bool(const Pick&)>& accept) {
    drawIds();
    int r = std::max(0, int(reach * density_));
    int cx = std::clamp(int(x), 0, width_ - 1), cy = std::clamp(height_ - 1 - int(y), 0, height_ - 1);
    int x0 = std::max(0, cx - r), x1 = std::min(width_ - 1, cx + r);
    int y0 = std::max(0, cy - r), y1 = std::min(height_ - 1, cy + r);
    int w = x1 - x0 + 1, h = y1 - y0 + 1;
    std::vector<uint8_t> px(size_t(w) * size_t(h) * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(x0, y0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer);
    // The nearest corner, edge and face; corners only from closer in, so an edge's end still picks the edge.
    Pick best[3], centre;
    int bestD[3] = {INT32_MAX, INT32_MAX, INT32_MAX};
    int reach2 = r * r, cornerReach2 = reach2 * 4 / 10;
    for (int row = 0; row < h; ++row)
        for (int col = 0; col < w; ++col) {
            const uint8_t* q = px.data() + (size_t(row) * size_t(w) + size_t(col)) * 4;
            uint32_t v = uint32_t(q[0]) | uint32_t(q[1]) << 8 | uint32_t(q[2]) << 16;
            if (v == 0) continue;
            int dx = x0 + col - cx, dy = y0 + row - cy, d2 = dx * dx + dy * dy;
            if (d2 > reach2) continue;
            Pick p = fromId(v);
            if (!accept(p)) continue;
            if (d2 == 0) centre = p;
            int slot = p.kind == Pick::Vertex ? 0 : p.kind == Pick::Edge ? 1 : 2;
            if (slot == 0 && d2 > cornerReach2) continue;
            if (d2 < bestD[slot]) bestD[slot] = d2, best[slot] = p;
        }
    if (best[0].kind != Pick::None) return best[0];
    // Over a face, an edge has to be closer to win, so a narrow face like a wall's top can still be picked.
    bool onFace = centre.kind != Pick::None && centre.kind != Pick::Edge && centre.kind != Pick::Vertex;
    if (best[1].kind != Pick::None && (!onFace || bestD[1] * 4 <= reach2)) return best[1];
    if (centre.kind != Pick::None) return centre;
    return best[2];
}

std::vector<Pick> Renderer::pickBox(float x0, float y0, float x1, float y1, bool crossing) {
    drawIds();
    std::vector<uint8_t> px(size_t(width_) * size_t(height_) * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer);
    int left = std::clamp(int(std::min(x0, x1)), 0, width_ - 1), right = std::clamp(int(std::max(x0, x1)), 0, width_ - 1);
    int top = std::clamp(int(std::min(y0, y1)), 0, height_ - 1), bottom = std::clamp(int(std::max(y0, y1)), 0, height_ - 1);
    // Ids seen inside the box, and (unless crossing) those also seen outside it.
    std::vector<uint32_t> inside, outside;
    for (int row = 0; row < height_; ++row) {
        int y = height_ - 1 - row;  // GL rows run bottom up.
        bool rowIn = y >= top && y <= bottom;
        for (int x = 0; x < width_; ++x) {
            const uint8_t* p = px.data() + (size_t(row) * size_t(width_) + size_t(x)) * 4;
            uint32_t v = uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16;
            if (v == 0) continue;
            if (rowIn && x >= left && x <= right) inside.push_back(v);
            else if (!crossing) outside.push_back(v);
        }
    }
    std::sort(inside.begin(), inside.end());
    inside.erase(std::unique(inside.begin(), inside.end()), inside.end());
    std::sort(outside.begin(), outside.end());
    outside.erase(std::unique(outside.begin(), outside.end()), outside.end());
    std::vector<Pick> out;
    for (uint32_t v : inside)
        if (crossing || !std::binary_search(outside.begin(), outside.end(), v)) out.push_back(fromId(v));
    return out;
}

void Renderer::setCanvases(std::vector<Canvas> canvases) {
    canvases_ = std::move(canvases);
    canvasesDirty_ = true;
    // With nothing else to show, the view frames the pictures.
    bool bodies = false;
    for (const auto& b : bodies_) bodies = bodies || !b.positions.empty();
    if (bodies || canvases_.empty()) return;
    float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
    for (const auto& c : canvases_)
        for (int k = 0; k < 12; ++k) {
            lo[k % 3] = std::min(lo[k % 3], c.corners[k]);
            hi[k % 3] = std::max(hi[k % 3], c.corners[k]);
        }
    float d2 = 0;
    for (int k = 0; k < 3; ++k) {
        centre_[k] = (lo[k] + hi[k]) * 0.5f;
        d2 += (hi[k] - lo[k]) * (hi[k] - lo[k]);
    }
    radius_ = std::max(std::sqrt(d2) * 0.5f, 0.1f);
}

void Renderer::drawCanvases(const float* vp) {
    if (canvasesDirty_) {
        if (!canvasTextures_.empty()) glDeleteTextures(GLsizei(canvasTextures_.size()), canvasTextures_.data());
        canvasTextures_.assign(canvases_.size(), 0);
        if (!canvasTextures_.empty()) glGenTextures(GLsizei(canvasTextures_.size()), canvasTextures_.data());
        for (size_t i = 0; i < canvases_.size(); ++i) {
            const Canvas& c = canvases_[i];
            glBindTexture(GL_TEXTURE_2D, canvasTextures_[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, c.width, c.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, c.rgba->data());
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        if (canvasVao_ == 0) {
            glGenVertexArrays(1, &canvasVao_);
            glGenBuffers(1, &canvasVbo_);
        }
        canvasesDirty_ = false;
    }
    if (canvases_.empty()) return;
    glUseProgram(canvasProgram_);
    glUniformMatrix4fv(glGetUniformLocation(canvasProgram_, "viewProjection"), 1, GL_FALSE, vp);
    glUniform1i(glGetUniformLocation(canvasProgram_, "picture"), 0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glBindVertexArray(canvasVao_);
    glBindBuffer(GL_ARRAY_BUFFER, canvasVbo_);
    for (size_t i = 0; i < canvases_.size(); ++i) {
        const float* k = canvases_[i].corners;
        // Two triangles, position then where in the picture; its top row is v = 0.
        const float quad[30] = {
            k[0], k[1], k[2], 0, 1,  k[3], k[4], k[5], 1, 1,  k[6], k[7], k[8], 1, 0,
            k[0], k[1], k[2], 0, 1,  k[6], k[7], k[8], 1, 0,  k[9], k[10], k[11], 0, 0,
        };
        glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 20, nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 20, reinterpret_cast<void*>(12));
        glUniform1f(glGetUniformLocation(canvasProgram_, "opacity"), canvases_[i].opacity);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, canvasTextures_[i]);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }
    glBindVertexArray(0);
    // Blending stays on: the edges drawn next blend too.
    glDepthMask(GL_TRUE);
}

Pick Renderer::fromId(uint32_t v) {
    if (v == 0) return {};
    v -= 1;
    Pick p;
    p.kind = (v & kEdgeBit) ? ((v & kCornerBit) ? Pick::Vertex : Pick::Edge) : Pick::Face;
    p.body = v >> 20;
    p.index = v & (p.kind == Pick::Face ? kEdgeBit - 1 : kCornerBit - 1);
    return p;
}

}  // namespace pm
