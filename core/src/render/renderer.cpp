#include "render/renderer.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>

namespace pm {
namespace {

const char* kVertex = R"(#version 300 es
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
uniform mat4 viewProjection;
uniform mat3 view;
out vec3 eyeNormal;
void main() {
    eyeNormal = view * normal;
    gl_Position = viewProjection * vec4(position, 1.0);
})";

const char* kFragment = R"(#version 300 es
precision mediump float;
in vec3 eyeNormal;
out vec4 colour;
void main() {
    vec3 n = normalize(eyeNormal);
    // A key light from the upper left and a little fill from below.
    float key = max(dot(n, normalize(vec3(-0.4, 0.6, 0.7))), 0.0);
    float fill = max(dot(n, vec3(0.0, -0.6, 0.8)), 0.0) * 0.25;
    vec3 base = vec3(0.62, 0.66, 0.72);
    colour = vec4(base * (0.25 + 0.75 * key + fill), 1.0);
})";

uint32_t compile(GLenum type, const char* source) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &source, nullptr);
    glCompileShader(s);
    return s;
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

}  // namespace

void Renderer::surfaceCreated() {
    program_ = glCreateProgram();
    GLuint vs = compile(GL_VERTEX_SHADER, kVertex), fs = compile(GL_FRAGMENT_SHADER, kFragment);
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_CULL_FACE);
    // The old context's buffers are gone, so the mesh goes up again.
    dirty_ = !pending_.empty() || vertexCount_ > 0;
}

void Renderer::surfaceChanged(int width, int height) {
    width_ = std::max(width, 1);
    height_ = std::max(height, 1);
    glViewport(0, 0, width_, height_);
}

void Renderer::setMesh(const Mesh& mesh) {
    // Flat shaded: each triangle gets its own corners with the face normal.
    pending_.clear();
    pending_.reserve(mesh.triangles.size() * 18);
    float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
    for (const auto& t : mesh.triangles) {
        const auto& a = mesh.vertices[t[0]];
        const auto& b = mesh.vertices[t[1]];
        const auto& c = mesh.vertices[t[2]];
        float u[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float w[3] = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        float n[3] = {u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0]};
        float len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len > 0) for (float& x : n) x /= len;
        for (const auto* p : {&a, &b, &c}) {
            pending_.insert(pending_.end(), {(*p)[0], (*p)[1], (*p)[2], n[0], n[1], n[2]});
            for (int i = 0; i < 3; ++i) {
                lo[i] = std::min(lo[i], (*p)[i]);
                hi[i] = std::max(hi[i], (*p)[i]);
            }
        }
    }
    if (!mesh.triangles.empty()) {
        float d2 = 0;
        for (int i = 0; i < 3; ++i) {
            centre_[i] = (lo[i] + hi[i]) * 0.5f;
            d2 += (hi[i] - lo[i]) * (hi[i] - lo[i]);
        }
        radius_ = std::max(std::sqrt(d2) * 0.5f, 0.1f);
    }
    dirty_ = true;
    fit();
}

void Renderer::upload() {
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(pending_.size() * sizeof(float)), pending_.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 24, nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 24, reinterpret_cast<void*>(12));
    vertexCount_ = int(pending_.size() / 6);
    dirty_ = false;
}

void Renderer::orbit(float dx, float dy) {
    yaw_ -= dx * 0.008f;
    pitch_ = std::clamp(pitch_ + dy * 0.008f, -1.55f, 1.55f);
}

void Renderer::zoom(float factor) { zoom_ = std::clamp(zoom_ * factor, 0.05f, 20.0f); }

void Renderer::fit() { zoom_ = 1; }

void Renderer::draw() {
    if (dirty_) upload();
    glClearColor(0.11f, 0.12f, 0.14f, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    if (vertexCount_ == 0) return;

    // Far enough back that the model's bounding sphere fits the narrower of
    // the two fields of view, with a little margin.
    float t = std::tan(0.4f);  // Half of a 46 degree vertical field of view.
    float aspect = float(width_) / float(height_);
    float halfFov = std::atan(t * std::min(aspect, 1.0f));
    float distance_ = radius_ * 1.1f / std::sin(halfFov) / zoom_;

    // Z is up, as on a print bed. The camera orbits the model's centre.
    float cy = std::cos(yaw_), sy = std::sin(yaw_), cp = std::cos(pitch_), sp = std::sin(pitch_);
    float eye[3] = {centre_[0] + distance_ * cp * cy, centre_[1] + distance_ * cp * sy, centre_[2] + distance_ * sp};
    float f[3] = {centre_[0] - eye[0], centre_[1] - eye[1], centre_[2] - eye[2]};
    float fl = std::sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
    for (float& x : f) x /= fl;
    float s[3] = {f[1], -f[0], 0};  // f × up, with up = +Z.
    float sl = std::sqrt(s[0] * s[0] + s[1] * s[1]);
    s[0] /= sl;
    s[1] /= sl;
    float u[3] = {s[1] * f[2] - s[2] * f[1], s[2] * f[0] - s[0] * f[2], s[0] * f[1] - s[1] * f[0]};

    float view[16] = {s[0], u[0], -f[0], 0, s[1], u[1], -f[1], 0, s[2], u[2], -f[2], 0,
                      -(s[0] * eye[0] + s[1] * eye[1] + s[2] * eye[2]),
                      -(u[0] * eye[0] + u[1] * eye[1] + u[2] * eye[2]),
                      f[0] * eye[0] + f[1] * eye[1] + f[2] * eye[2], 1};
    float near = std::max(distance_ - radius_ * 1.5f, distance_ * 0.01f), far = distance_ + radius_ * 1.5f;
    float proj[16] = {1 / (t * aspect), 0, 0, 0, 0, 1 / t, 0, 0, 0, 0, -(far + near) / (far - near), -1,
                      0, 0, -2 * far * near / (far - near), 0};
    float vp[16];
    multiply(proj, view, vp);
    float normal[9] = {view[0], view[1], view[2], view[4], view[5], view[6], view[8], view[9], view[10]};

    glUseProgram(program_);
    glUniformMatrix4fv(glGetUniformLocation(program_, "viewProjection"), 1, GL_FALSE, vp);
    glUniformMatrix3fv(glGetUniformLocation(program_, "view"), 1, GL_FALSE, normal);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, vertexCount_);
}

}  // namespace pm
