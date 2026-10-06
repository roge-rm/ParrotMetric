#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "mesh/mesh.h"

namespace pm {

/** What a stroke does to the surface. */
enum class Brush : int {
    Draw,     // pushes the surface out along its normal
    Clay,     // builds up flat layers, like adding clay
    Crease,   // cuts a sharp groove, pinching the sides in
    Smooth,   // evens out bumps
    Flatten,  // presses towards a flat plane
    Inflate,  // swells each point out along its own normal
    Pinch,    // pulls points in towards the brush's middle
    Grab,     // drags what was under the brush when the stroke began
    Pull,     // drags the surface along with the brush, stretching it out
    Layer,    // raises to a set height and no further in one stroke
    Mask,     // paints a mask that other brushes leave alone
};

struct BrushSettings {
    Brush brush = Brush::Draw;
    float radius = 40;       // Screen pixels.
    float strength = 0.5f;   // 0 to 1.
    bool invert = false;     // Push in rather than out, rub the mask off, and so on.
    int mirror = 0;          // Bits: 1 across x, 2 across y, 4 across z, through the mirror centre.
    bool dynamic = true;     // Split and join triangles under the brush to keep detail even.
    float detail = 0.5f;     // 0 coarse to 1 fine: triangles from 30% of the brush's radius across down to 4%.
    bool pressureSize = false;
    bool pressureStrength = true;
};

/**
 * A mesh being sculpted. Strokes come in as screen points with the camera
 * they were made under; the surface changes at once, and the parts that
 * changed are listed for the renderer to send to the GPU.
 *
 * Vertices and triangles keep their slots for the whole session so the GPU
 * copies can be updated in place: a triangle taken away becomes {0, 0, 0},
 * which draws nothing, and its slot is used again. mesh() gives a compact
 * copy. Not thread safe: the caller holds a lock around every call.
 */
class Sculpt {
public:
    /** As drawn: position, normal and how masked it is (0 to 1). */
    struct Vertex {
        float p[3];
        float n[3];
        float mask;
    };

    /** The mesh to sculpt, closed and welded, with each point's mask if given. Throws if it has no triangles. */
    explicit Sculpt(const Mesh& mesh, size_t maxTriangles = 2000000, const std::vector<float>* mask = nullptr);

    /**
     * A body's mesh got ready to sculpt: a solid's triangles made even (a
     * mesh's are kept), mirrored through its middle, and remembering what it
     * was made from so its strokes can be made again on a changed body.
     */
    static std::unique_ptr<Sculpt> fromBody(const Mesh& mesh, bool solid, size_t maxTriangles);
    /** A session as save() kept it; older files hold only a mesh. Throws if the bytes are damaged. */
    static std::unique_ptr<Sculpt> load(const std::vector<uint8_t>& bytes, size_t maxTriangles);
    /**
     * Everything to carry on later: the mesh with its mask, the strokes and
     * other changes that made it, and what it was made from. Deflated.
     */
    std::vector<uint8_t> save() const;
    /**
     * The mesh a saved session gives for the body it was made from, now
     * [input] (a solid if [solid]): the saved mesh while that's unchanged, else
     * the strokes made again on it. With no input, the saved mesh.
     */
    static Mesh result(const std::vector<uint8_t>& bytes, const Mesh* input, bool solid);
    /**
     * Carries on a saved session: as it was, or if the body it was made from
     * has since changed into [input], made again from that with the same strokes.
     */
    static std::unique_ptr<Sculpt> resume(const std::vector<uint8_t>& bytes, const Mesh* input, bool solid, size_t maxTriangles);
    /** Whether a saved session was made from a body that's changed, so loading it means making its strokes again. */
    static bool changedSince(const std::vector<uint8_t>& bytes, const Mesh& input);
    /** Makes the changes in a saved record again, as part of what this was made from. */
    void replay(const std::vector<uint8_t>& log);
    /** A number that changes when the mesh does, to tell a body has changed since. */
    static uint64_t fingerprint(const Mesh& mesh);
    ~Sculpt();
    Sculpt(const Sculpt&) = delete;
    Sculpt& operator=(const Sculpt&) = delete;

    /** The camera strokes are made under: column-major view-projection, viewport in pixels. */
    void setCamera(const float viewProjection[16], int width, int height);
    /** Whether the point (x, y) on screen is over the surface. */
    bool hit(float x, float y) const;

    /** Where the mirror planes cross; the origin unless set. */
    void setMirrorCentre(float x, float y, float z) { mirrorAt_[0] = x; mirrorAt_[1] = y; mirrorAt_[2] = z; }

    /** Starts a stroke at (x, y). False, doing nothing, if it's off the surface (Grab and Pull need to start on it). */
    bool begin(float x, float y, float pressure, const BrushSettings& settings);
    void move(float x, float y, float pressure);
    void end();
    bool stroking() const { return stroking_; }

    bool undo();
    bool redo();
    bool canUndo() const { return !undo_.empty(); }
    bool canRedo() const { return !redo_.empty(); }
    /** Forgets undo and redo, as after setting up. */
    void forget();

    void clearMask();
    void invertMask();
    /** Remeshes all of it to even triangles about [edge] mm across, as one undoable step. */
    void evenOut(float edge);

    /** A compact copy, without the slots left empty. */
    Mesh mesh() const;
    size_t triangleCount() const { return tris_.size() - freeTris_.size(); }
    /** The box round it: x, y, z low, then high. */
    std::array<float, 6> bounds() const;
    /** The average edge length, mm. */
    float averageEdge() const;

    const std::vector<Vertex>& vertices() const { return verts_; }
    const std::vector<std::array<uint32_t, 3>>& triangles() const { return tris_; }

    /** What changed since the last call, in blocks of kBlock slots; all means send everything (the arrays grew). */
    struct Dirty {
        std::vector<uint32_t> vertexBlocks, triangleBlocks;
        bool all = false;
    };
    Dirty takeDirty();
    static constexpr uint32_t kBlock = 1024;

    /** A 4x4 matrix's inverse; false if it hasn't one. */
    static bool invert(const float m[16], float inv[16]);

    /** An icosphere of the given radius, split [levels] times (20 * 4^levels triangles). */
    static Mesh sphere(float radius, int levels);

private:
    struct Bvh;
    struct Change;

    // Topology.
    std::vector<Vertex> verts_;
    std::vector<std::array<uint32_t, 3>> tris_;
    std::vector<uint8_t> triAlive_;
    std::vector<std::vector<uint32_t>> vertTris_;
    std::vector<uint32_t> freeTris_, freeVerts_;
    size_t maxTriangles_;

    // Finding things.
    std::unique_ptr<Bvh> bvh_;
    void rebuildBvh();
    /** Builds a tree over [ids] into [root], an empty node or a leaf being split. */
    void grow(std::vector<uint32_t>& ids, int root);
    /** Leaves that took too many new triangles become small trees of their own. */
    void splitGrownLeaves();
    bool raycast(const float from[3], const float dir[3], float out[3]) const;
    void within(const float centre[3], float radius, std::vector<uint32_t>& out) const;

    // Camera.
    float vp_[16] = {}, inv_[16] = {};
    int width_ = 1, height_ = 1;
    void ray(float x, float y, float from[3], float dir[3]) const;
    float worldRadius(const float at[3], float pixels) const;
    bool onViewPlane(float x, float y, const float through[3], float out[3]) const;

    // The stroke.
    BrushSettings s_;
    bool stroking_ = false;
    float lastX_ = 0, lastY_ = 0, lastPressure_ = 1, travelled_ = 0;
    float dabX_ = 0, dabY_ = 0;  // Where the last step along the stroke was.
    float grabFrom_[3] = {}, pullAt_[3] = {};
    float mirrorAt_[3] = {};
    /** A point across the mirror planes in the bits of m. */
    void mirroredPoint(const float* p, int m, float* o) const;
    struct Held {
        uint32_t v;
        float w;
        float start[3];
        int mirror;
    };
    std::vector<Held> held_;
    std::vector<float> layerStart_;      // Per vertex, x y z at the stroke's start, for Layer.
    std::vector<uint32_t> stamp_;        // Per vertex, which query last saw it.
    mutable uint32_t stampNow_ = 0;
    void dab(float x, float y, float pressure);
    void apply(const float centre[3], const float normal[3], float radius, float strength, int mirror, const float delta[3]);
    void grabTo(float x, float y);

    // Detail.
    bool splitEdge(uint32_t a, uint32_t b);
    bool collapseEdge(uint32_t a, uint32_t b);
    void refine(const std::vector<uint32_t>& region, float longest, float shortest);
    void relax(const std::vector<uint32_t>& region);
    /** On a sharp edge or corner. */
    bool sharp(uint32_t v) const;
    uint32_t newVertex();
    uint32_t newTriangle(const std::array<uint32_t, 3>& t);
    void killTriangle(uint32_t t);
    void setTriangle(uint32_t t, const std::array<uint32_t, 3>& v);
    bool topologyChanged_ = false;
    size_t orderedTriangles_ = 0;  // How many slots there were when last put in order.

    // Normals and the GPU.
    void normalsAround(const std::vector<uint32_t>& moved);
    void faceNormal(uint32_t t, float out[3]) const;
    void markVertex(uint32_t v);
    void markTriangle(uint32_t t);
    std::vector<uint8_t> dirtyVerts_, dirtyTris_;
    bool dirtyAll_ = true;

    // What made it, for making it again: from the body with fingerprint base_ (0, a shape of its own),
    // the changes no longer in undo_ in settled_, and the stroke being made.
    uint64_t base_ = 0;
    std::vector<uint8_t> settled_;
    std::vector<float> strokePoints_;
    float strokeCamera_[16] = {};
    int strokeWidth_ = 1, strokeHeight_ = 1;
    bool replaying_ = false;
    /** The record of every change kept: settled_, then each undoable one's in order. */
    std::vector<uint8_t> log() const;
    /** A change's record, kept with its undo step. */
    void note(std::vector<uint8_t> op);

    // Undo, a stroke at a time.
    std::vector<std::unique_ptr<Change>> undo_, redo_;
    std::unique_ptr<Change> open_;
    std::vector<uint32_t> touched_;  // Per vertex: the stroke number it was last recorded in.
    uint32_t strokeNo_ = 0;
    void record(uint32_t v);
    void startChange(bool wholeMesh);
    void finishChange();
    void restore(const Change& c, bool after);
    void trimUndo();

    void reset(const Mesh& mesh, const std::vector<float>* mask = nullptr);
    /** A compact copy and each of its points' mask. */
    Mesh mesh(std::vector<float>* mask) const;
    /** Packs the slots up, so taken away triangles aren't drawn as empty ones. */
    void compact();
    /** Everything worked out from verts_ and tris_ again: links, free slots, boxes; all sent again. */
    void relink();
};

/** A mesh as compact bytes, deflated, for keeping in a design file. */
std::vector<uint8_t> packMesh(const Mesh& mesh);
/** The mesh from packMesh's bytes. Throws if they aren't. */
Mesh unpackMesh(const std::vector<uint8_t>& bytes);

}  // namespace pm
