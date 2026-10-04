#pragma once

#include <cstdint>

#include "mesh/mesh.h"

namespace pm {

/**
 * Draws meshes shaded, with an orbiting camera, on OpenGL ES 3 (WebGL 2 has
 * the same calls). Every method runs on the GL thread.
 */
class Renderer {
public:
    /** The GL context is new: everything on the GPU has to be made again. */
    void surfaceCreated();
    void surfaceChanged(int width, int height);
    void draw();

    /** Replaces what's shown. Uploaded on the next draw. */
    void setMesh(const Mesh& mesh);

    /** Turns the camera around the model, in screen pixels dragged. */
    void orbit(float dx, float dy);
    /** Zooms by a factor; above 1 moves closer. */
    void zoom(float factor);
    /** Frames the whole model again. */
    void fit();

private:
    void upload();

    uint32_t program_ = 0, vao_ = 0, vbo_ = 0;
    int vertexCount_ = 0;
    int width_ = 1, height_ = 1;

    std::vector<float> pending_;  // Interleaved position and normal, waiting for upload.
    bool dirty_ = false;

    float centre_[3] = {0, 0, 0};
    float radius_ = 10;
    float yaw_ = 0.6f, pitch_ = 0.5f;  // Radians.
    float zoom_ = 1;  // 1 frames the whole model; larger is closer.
};

}  // namespace pm
