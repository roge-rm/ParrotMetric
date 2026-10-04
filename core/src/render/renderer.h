#pragma once

#include <chrono>
#include <cstdint>
#include <vector>

#include "display/display_mesh.h"

namespace pm {

/** What a tap landed on. */
struct Pick {
    enum Kind : uint8_t { None, Face, Edge, Vertex };
    Kind kind = None;
    uint32_t body = 0;
    uint32_t index = 0;  // The face, edge or corner number in that body's DisplayMesh.

    bool operator==(const Pick& o) const { return kind == o.kind && body == o.body && index == o.index; }
};

/**
 * Draws bodies shaded with their edges, on OpenGL ES 3 (WebGL 2 has the same
 * calls), and finds what's under a point by drawing face and edge numbers into
 * an offscreen buffer. Every method runs on the GL thread.
 */
class Renderer {
public:
    /** The GL context is new: everything on the GPU has to be made again. */
    void surfaceCreated();
    void surfaceChanged(int width, int height);
    /** Draws a frame. True if it's moving and wants another frame soon. */
    bool draw();

    /** Hides everything on the back side of a plane (ax + by + cz + d < 0), for a section view. Off when off is true. */
    void setClip(float a, float b, float c, float d, bool on) {
        clip_[0] = a; clip_[1] = b; clip_[2] = c; clip_[3] = d;
        clipping_ = on;
    }

    /** Screen pixels per density-independent pixel, for line widths. */
    void setDensity(float density) { density_ = density; }

    /** Replaces what's shown. Uploaded on the next draw. */
    void setBodies(std::vector<DisplayMesh> bodies, bool refit);
    void setSelection(const std::vector<Pick>& selection);
    Pick pick(float x, float y);
    /**
     * What's drawn in the box from (x0, y0) to (x1, y1), screen pixels: with
     * crossing, anything with some of it in the box; else only what's wholly
     * inside it, as far as it can be seen.
     */
    std::vector<Pick> pickBox(float x0, float y0, float x1, float y1, bool crossing);

    /** Turns the camera around the model, in screen pixels dragged. */
    void orbit(float dx, float dy);
    /** Slides the view, in screen pixels dragged. */
    void pan(float dx, float dy);
    /** Zooms by a factor; above 1 moves closer. */
    void zoom(float factor);
    /** Zooms by a factor towards the point under (x, y) on screen, which stays where it is. */
    void zoomAt(float factor, float x, float y);
    /** Frames the whole model again, smoothly. */
    void fit();
    /** Turns smoothly to look from a direction, in radians: yaw round Z from +X, pitch up from the XY plane. */
    void viewFrom(float yaw, float pitch);

    /** The view-projection matrix of the last frame drawn, column-major, for mapping touches. */
    const float* viewProjection() const { return lastViewProjection_; }
    int width() const { return width_; }
    int height() const { return height_; }

    /** The camera's turn, for the orientation cube: yaw and pitch in radians. */
    float yaw() const { return yaw_; }
    float pitch() const { return pitch_; }

private:
    struct Gpu {
        uint32_t faceVao = 0, faceVbo = 0, faceIbo = 0;
        uint32_t edgeVao = 0, edgeVbo = 0, edgeIbo = 0;
        uint32_t faceSelected = 0, edgeSelected = 0;  // R8 textures, one texel per face or edge.
        int faceIndices = 0, edgeIndices = 0;
        float edgeColour[4];
        float faceColour[4];
        bool behind = false;
        uint32_t faceCount = 0, edgeCount = 0;
        uint32_t cornerVao = 0, cornerVbo = 0;
        int cornerCount = 0;
    };

    void upload();
    void uploadSelection();
    void releaseGpu();
    void camera(float* viewProjection, float* normal) const;
    void drawScene(bool ids, const float* vp, const float* normal);
    void ensurePickTarget();
    /** Draws face and edge ids into the pick buffer, which is left bound. */
    void drawIds();
    /** Millimetres per screen pixel at the target's distance. */
    float perPixel() const;
    static Pick fromId(uint32_t v);
    void animate();

    // GL objects.
    uint32_t faceProgram_ = 0, edgeProgram_ = 0, faceIdProgram_ = 0, edgeIdProgram_ = 0;
    uint32_t cornerProgram_ = 0, cornerIdProgram_ = 0;
    uint32_t pickFbo_ = 0, pickColour_ = 0, pickDepth_ = 0;
    int pickWidth_ = 0, pickHeight_ = 0;
    std::vector<Gpu> gpu_;

    int width_ = 1, height_ = 1;
    float lastViewProjection_[16] = {};
    float clip_[4] = {0, 0, 1, 0};
    bool clipping_ = false;
    float density_ = 1;

    std::vector<DisplayMesh> bodies_;
    bool bodiesDirty_ = false;
    std::vector<Pick> selection_;
    bool selectionDirty_ = false;

    // Camera. Z is up, as on a print bed; the camera orbits target_.
    float centre_[3] = {0, 0, 0};
    float radius_ = 10;
    float target_[3] = {0, 0, 0};
    float yaw_ = -0.9f, pitch_ = 0.45f;
    float zoom_ = 1;  // 1 frames the whole model; larger is closer.

    // A smooth move: from the camera as it was to a goal, over kMoveSeconds.
    struct View {
        float target[3], yaw, pitch, zoom;
    };
    bool moving_ = false;
    View from_{}, to_{};
    std::chrono::steady_clock::time_point moveStart_;
};

}  // namespace pm
