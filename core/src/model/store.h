#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <unordered_map>

#include "mesh/mesh_body.h"
#include "model/named_shape.h"

namespace pm {

/** A body: a solid with named faces, or a mesh. */
struct Body {
    std::optional<NamedShape> solid;
    std::optional<MeshBody> mesh;

    bool isMesh() const { return mesh.has_value(); }
    /** As a mesh, tessellating a solid if need be: chord tolerance in mm, angle in radians. */
    MeshBody asMesh(double chord = 0.01, double angle = 0.25) const;
};

/**
 * Bodies held for the app by number, so the Kotlin side can keep the
 * results of each feature without copying shapes. Counted: each holder
 * retains, and the body goes when the last one releases it.
 */
class BodyStore {
public:
    using Handle = int64_t;

    Handle add(Body b);
    const Body& get(Handle h) const;
    void retain(Handle h);
    void release(Handle h);
    size_t size() const { return bodies_.size(); }

private:
    struct Entry {
        std::shared_ptr<const Body> body;
        int count = 1;
    };
    std::unordered_map<Handle, Entry> bodies_;
    Handle next_ = 1;
};

}  // namespace pm
