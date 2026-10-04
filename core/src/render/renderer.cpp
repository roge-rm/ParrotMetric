#include "render/renderer.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace pm {
namespace {

constexpr float kMoveSeconds = 0.35f;
constexpr float kHalfFov = 0.4f;  // Half of a 46 degree vertical field of view.
constexpr int kSelectionRow = 1024;  // Selection textures are this many texels wide.

// Picked ids: 0 is nothing; otherwise body << 20 | kind << 19 | index, plus one.
constexpr uint32_t kEdgeBit = 1u << 19;

const char* kFaceVertex = R"(#version 300 es
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in uint face;
uniform mat4 viewProjection;
uniform mat3 view;
uniform sampler2D selected;
out vec3 eyeNormal;
out vec3 world;
flat out float chosen;
void main() {
    eyeNormal = view * normal;
    world = position;
    chosen = texelFetch(selected, ivec2(int(face) % 1024, int(face) / 1024), 0).r;
    gl_Position = viewProjection * vec4(position, 1.0);
})";

const char* kFaceFragment = R"(#version 300 es
precision mediump float;
in vec3 eyeNormal;
in vec3 world;
flat in float chosen;
uniform vec4 faceColour;
uniform vec4 clip;
uniform bool clipping;
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
    vec3 base = mix(faceColour.rgb, vec3(1.0, 0.48, 0.24), chosen * 0.55);
    float alpha = faceColour.a < 1.0 ? mix(faceColour.a, 0.55, chosen) : 1.0;
    colour = vec4(base * (ambient + 0.72 * key) + rim * (1.0 - chosen), alpha);
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

const char* kIdFragment = R"(#version 300 es
precision highp float;
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

uint32_t compile(GLenum type, const char* source) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &source, nullptr);
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
    float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
    bool any = false;
    for (const auto& b : bodies_) {
        for (size_t i = 0; i < b.positions.size(); i += 3) {
            any = true;
            for (int k = 0; k < 3; ++k) {
                lo[k] = std::min(lo[k], b.positions[i + k]);
                hi[k] = std::max(hi[k], b.positions[i + k]);
            }
        }
    }
    if (any) {
        float d2 = 0;
        for (int k = 0; k < 3; ++k) {
            centre_[k] = (lo[k] + hi[k]) * 0.5f;
            d2 += (hi[k] - lo[k]) * (hi[k] - lo[k]);
        }
        radius_ = std::max(std::sqrt(d2) * 0.5f, 0.1f);
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
        GLuint buffers[4] = {g.faceVbo, g.faceIbo, g.edgeVbo, g.edgeIbo};
        glDeleteBuffers(4, buffers);
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
        g.edgeCount = uint32_t(b.edges.size());

        glGenVertexArrays(1, &g.faceVao);
        glBindVertexArray(g.faceVao);
        glGenBuffers(1, &g.faceVbo);
        glBindBuffer(GL_ARRAY_BUFFER, g.faceVbo);
        size_t n = b.vertexCount();
        // Laid out as all positions, then all normals, then all face numbers.
        size_t posBytes = n * 12, faceBytes = n * 4;
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(posBytes * 2 + faceBytes), nullptr, GL_STATIC_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0, GLsizeiptr(posBytes), b.positions.data());
        glBufferSubData(GL_ARRAY_BUFFER, GLintptr(posBytes), GLsizeiptr(posBytes), b.normals.data());
        glBufferSubData(GL_ARRAY_BUFFER, GLintptr(posBytes * 2), GLsizeiptr(faceBytes), b.faceOfVertex.data());
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 0, reinterpret_cast<void*>(posBytes));
        glEnableVertexAttribArray(2);
        glVertexAttribIPointer(2, 1, GL_UNSIGNED_INT, 0, reinterpret_cast<void*>(posBytes * 2));
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

void Renderer::pan(float dx, float dy) {
    moving_ = false;
    // Pixels to millimetres at the target's distance.
    float distance = radius_ * 1.1f / std::sin(std::atan(std::tan(kHalfFov) * std::min(float(width_) / float(height_), 1.0f))) / zoom_;
    float perPixel = 2 * distance * std::tan(kHalfFov) / float(height_);
    float cy = std::cos(yaw_), sy = std::sin(yaw_), cp = std::cos(pitch_), sp = std::sin(pitch_);
    float right[3] = {-sy, cy, 0};
    float up[3] = {-sp * cy, -sp * sy, cp};
    for (int k = 0; k < 3; ++k) target_[k] += (-dx * right[k] + dy * up[k]) * perPixel;
}

void Renderer::zoom(float factor) {
    moving_ = false;
    zoom_ = std::clamp(zoom_ * factor, 0.05f, 40.0f);
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
    // Far enough back that the model's bounding sphere fits the narrower of
    // the two fields of view, with a little margin.
    float t = std::tan(kHalfFov);
    float aspect = float(width_) / float(height_);
    float distance = radius_ * 1.1f / std::sin(std::atan(t * std::min(aspect, 1.0f))) / zoom_;

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
            if (seeThrough) glPolygonOffset(g.behind ? 2.0f : 0.0f, g.behind ? 4.0f : 0.0f);
            if (ids) glUniform1ui(glGetUniformLocation(faceProgram, "base"), i << 20);
            else glUniform4fv(glGetUniformLocation(faceProgram, "faceColour"), 1, g.faceColour);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, g.faceSelected);
            glBindVertexArray(g.faceVao);
            glDrawElements(GL_TRIANGLES, g.faceIndices, GL_UNSIGNED_INT, nullptr);
        }
        if (seeThrough && !ids) {
            glDepthMask(GL_TRUE);
            glDisable(GL_BLEND);
        }
    }
    glDisable(GL_POLYGON_OFFSET_FILL);

    if (!ids) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }
    glUseProgram(edgeProgram);
    glUniformMatrix4fv(glGetUniformLocation(edgeProgram, "viewProjection"), 1, GL_FALSE, vp);
    glUniform4fv(glGetUniformLocation(edgeProgram, "clip"), 1, clip_);
    glUniform1i(glGetUniformLocation(edgeProgram, "clipping"), clipping_ ? 1 : 0);
    glUniform2f(glGetUniformLocation(edgeProgram, "viewport"), float(width_), float(height_));
    // Edges are easier to hit than to see: the id pass draws them a finger wide.
    glUniform1f(glGetUniformLocation(edgeProgram, "width"), (ids ? 14.0f : 1.6f) * density_);
    glUniform1f(glGetUniformLocation(edgeProgram, "selectedWidth"), (ids ? 14.0f : 3.5f) * density_);
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
    if (!ids) glDisable(GL_BLEND);
}

bool Renderer::draw() {
    if (bodiesDirty_) upload();
    if (selectionDirty_) uploadSelection();
    animate();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width_, height_);
    glClearColor(0.102f, 0.125f, 0.122f, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    float vp[16], normal[9];
    camera(vp, normal);
    std::copy(vp, vp + 16, lastViewProjection_);
    drawScene(false, vp, normal);
    return moving_;
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

Pick Renderer::pick(float x, float y) {
    if (bodiesDirty_) upload();
    ensurePickTarget();
    glBindFramebuffer(GL_FRAMEBUFFER, pickFbo_);
    glViewport(0, 0, width_, height_);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    float vp[16], normal[9];
    camera(vp, normal);
    drawScene(true, vp, normal);

    uint8_t px[4] = {0, 0, 0, 0};
    int ix = std::clamp(int(x), 0, width_ - 1), iy = std::clamp(height_ - 1 - int(y), 0, height_ - 1);
    glReadPixels(ix, iy, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    uint32_t v = uint32_t(px[0]) | uint32_t(px[1]) << 8 | uint32_t(px[2]) << 16;
    if (v == 0) return {};
    v -= 1;
    Pick p;
    p.kind = (v & kEdgeBit) ? Pick::Edge : Pick::Face;
    p.body = v >> 20;
    p.index = v & (kEdgeBit - 1);
    return p;
}

}  // namespace pm
