#include "model/store.h"

#include <stdexcept>

#include "solid/solid.h"

namespace pm {

MeshBody Body::asMesh(double chord, double angle) const {
    if (mesh) return *mesh;
    Tessellation t;
    t.chord = chord;
    t.angle = angle;
    return MeshBody::fromMesh(Solid::fromShape(solid->shape).tessellate(t));
}

BodyStore::Handle BodyStore::add(Body b) {
    Handle h = next_++;
    bodies_[h] = Entry{std::make_shared<const Body>(std::move(b)), 1};
    return h;
}

bool BodyStore::has(Handle h) const { return bodies_.count(h) > 0; }

const Body& BodyStore::get(Handle h) const {
    auto it = bodies_.find(h);
    if (it == bodies_.end()) throw std::runtime_error("A body went missing");
    return *it->second.body;
}

void BodyStore::retain(Handle h) {
    auto it = bodies_.find(h);
    if (it != bodies_.end()) it->second.count++;
}

void BodyStore::release(Handle h) {
    auto it = bodies_.find(h);
    if (it != bodies_.end() && --it->second.count <= 0) bodies_.erase(it);
}

}  // namespace pm
