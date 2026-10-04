#include "model/operations.h"

#include <BRepAdaptor_Surface.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepPrimAPI_MakeRevol.hxx>
#include <GProp_GProps.hxx>
#include <Standard_Failure.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>
#include <gp_Ax1.hxx>
#include <gp_Trsf.hxx>

#include <BRepTopAdaptor_FClass2d.hxx>
#include <algorithm>
#include <cmath>
#include <memory>
#include <limits>
#include <stdexcept>

#include "sketch/region_faces.h"

namespace pm {
namespace {

std::string prefix(int id) { return "F" + std::to_string(id); }

/**
 * The regions picked, chosen again from the sketch as it is now: the one with
 * the same curves round it, or if that's not one region, the one under the
 * point that was picked.
 */
std::vector<const RegionFace*> choose(const std::vector<RegionFace>& regions, const std::vector<RegionPick>& picks) {
    std::vector<const RegionFace*> out;
    for (const auto& pick : picks) {
        std::vector<const RegionFace*> same, all;
        for (const auto& r : regions) {
            all.push_back(&r);
            if (r.info.curveIds == pick.curveIds) same.push_back(&r);
        }
        const RegionFace* best = same.size() == 1 ? same[0] : nullptr;
        if (!best) {
            for (const RegionFace* r : same.empty() ? all : same) {
                BRepTopAdaptor_FClass2d inside(r->face, 1e-7);
                if (inside.Perform(gp_Pnt2d(pick.u, pick.v)) == TopAbs_IN) { best = r; break; }
            }
        }
        if (!best && !same.empty()) best = same[0];
        if (!best) throw std::runtime_error("A picked area of the sketch isn't closed any more");
        if (std::find(out.begin(), out.end(), best) == out.end()) out.push_back(best);
    }
    if (out.empty()) throw std::runtime_error("Pick an area of the sketch");
    return out;
}

gp_Trsf placeOn(const gp_Ax3& plane) {
    gp_Trsf t;
    t.SetDisplacement(gp_Ax3(gp::XOY()), plane);
    return t;
}

/** Fuses the pieces of a feature into one shape, keeping their names. */
NamedShape fuseAll(int id, std::vector<NamedShape> pieces) {
    NamedShape out = std::move(pieces[0]);
    for (size_t i = 1; i < pieces.size(); ++i) {
        BRepAlgoAPI_Fuse fuse(out.shape, pieces[i].shape);
        if (!fuse.IsDone()) throw std::runtime_error("The areas couldn't be joined");
        out = carryNames({&out, &pieces[i]}, fuse, fuse.Shape(), prefix(id));
    }
    return out;
}

/** A swept region's faces: sides by the curve their edge came from, and the two ends. */
template <class Sweep>
NamedShape nameSweep(int id, Sweep& sweep, const RegionFace& region, const TopLoc_Location& loc) {
    NamedShape out;
    out.shape = sweep.Shape();
    for (const auto& [edge, curve] : region.edgeCurves) {
        for (const auto& f : sweep.Generated(edge.Moved(loc))) {
            if (!out.names.IsBound(f)) out.names.Bind(f, prefix(id) + ".s" + std::to_string(curve));
        }
    }
    if (!sweep.FirstShape().IsNull())
        for (TopExp_Explorer f(sweep.FirstShape(), TopAbs_FACE); f.More(); f.Next()) out.names.Bind(f.Current(), prefix(id) + ".start");
    if (!sweep.LastShape().IsNull())
        for (TopExp_Explorer f(sweep.LastShape(), TopAbs_FACE); f.More(); f.Next()) out.names.Bind(f.Current(), prefix(id) + ".end");
    int k = 0;
    for (TopExp_Explorer f(out.shape, TopAbs_FACE); f.More(); f.Next())
        if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + ".n" + std::to_string(k++));
    return out;
}

void check(const TopoDS_Shape& s, const char* why) {
    if (s.IsNull() || !BRepCheck_Analyzer(s).IsValid()) throw std::runtime_error(why);
}

double volume(const TopoDS_Shape& s) {
    GProp_GProps props;
    BRepGProp::VolumeProperties(s, props);
    return std::abs(props.Mass());
}

}  // namespace

NamedShape extrude(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double forward, double back) {
    if (std::abs(forward + back) < 1e-6) throw std::runtime_error("The extrude has no length");
    try {
        auto regions = buildRegionFaces(curves);
        std::vector<NamedShape> pieces;
        gp_Trsf onPlane = placeOn(plane);
        gp_Trsf shift;
        shift.SetTranslation(gp_Vec(plane.Direction()) * -back);
        TopLoc_Location loc(shift * onPlane);
        gp_Vec sweep = gp_Vec(plane.Direction()) * (forward + back);
        for (const RegionFace* r : choose(regions, picks)) {
            TopoDS_Face face = TopoDS::Face(r->face.Moved(loc));
            BRepPrimAPI_MakePrism prism(face, sweep);
            if (!prism.IsDone()) throw std::runtime_error("The extrude couldn't be made");
            pieces.push_back(nameSweep(id, prism, *r, loc));
        }
        NamedShape out = fuseAll(id, std::move(pieces));
        check(out.shape, "The extrude couldn't be made");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The extrude couldn't be made");
    }
}

NamedShape revolve(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double ax, double ay, double dx, double dy, double angle) {
    if (std::abs(angle) < 1e-9) throw std::runtime_error("The revolve has no angle");
    try {
        auto regions = buildRegionFaces(curves);
        gp_Trsf onPlane = placeOn(plane);
        TopLoc_Location loc(onPlane);
        gp_Ax1 axis(gp_Pnt(ax, ay, 0).Transformed(onPlane), gp_Dir(dx, dy, 0).Transformed(onPlane));
        std::vector<NamedShape> pieces;
        for (const RegionFace* r : choose(regions, picks)) {
            TopoDS_Face face = TopoDS::Face(r->face.Moved(loc));
            BRepPrimAPI_MakeRevol revol(face, axis, angle);
            if (!revol.IsDone()) throw std::runtime_error("The revolve couldn't be made");
            pieces.push_back(nameSweep(id, revol, *r, loc));
        }
        NamedShape out = fuseAll(id, std::move(pieces));
        check(out.shape, "The revolve crosses its own axis");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The revolve couldn't be made");
    }
}

NamedShape combine(int id, const NamedShape& target, const NamedShape& tool, Combine how) {
    try {
        std::unique_ptr<BRepAlgoAPI_BooleanOperation> op;
        switch (how) {
            case Combine::Join: op = std::make_unique<BRepAlgoAPI_Fuse>(target.shape, tool.shape); break;
            case Combine::Cut: op = std::make_unique<BRepAlgoAPI_Cut>(target.shape, tool.shape); break;
            case Combine::Intersect: op = std::make_unique<BRepAlgoAPI_Common>(target.shape, tool.shape); break;
        }
        if (!op->IsDone()) throw std::runtime_error("The bodies couldn't be combined");
        NamedShape out = carryNames({&target, &tool}, *op, op->Shape(), prefix(id));
        if (volume(out.shape) < 1e-9) throw std::runtime_error(how == Combine::Cut ? "The cut removes the whole body" : "The bodies don't overlap");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The bodies couldn't be combined");
    }
}

NamedShape fillet(int id, const NamedShape& body, const std::vector<std::string>& edges, double radius) {
    if (radius <= 0) throw std::runtime_error("The radius has to be more than 0");
    try {
        BRepFilletAPI_MakeFillet op(body.shape);
        std::vector<std::pair<TopoDS_Edge, std::string>> added;
        for (const auto& name : edges) {
            for (const auto& e : body.findEdges(name)) {
                op.Add(radius, e);
                added.push_back({e, name});
            }
        }
        if (added.empty()) throw std::runtime_error("The edges to round aren't there any more");
        op.Build();
        if (!op.IsDone() || !BRepCheck_Analyzer(op.Shape()).IsValid()) throw std::runtime_error("The fillet doesn't fit");
        NamedShape out = carryNames({&body}, op, op.Shape(), prefix(id));
        for (const auto& [e, name] : added)
            for (const auto& f : op.Generated(e)) out.names.Bind(f, prefix(id) + ".r(" + name + ")");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The fillet doesn't fit");
    }
}

NamedShape chamfer(int id, const NamedShape& body, const std::vector<std::string>& edges, double distance) {
    if (distance <= 0) throw std::runtime_error("The distance has to be more than 0");
    try {
        BRepFilletAPI_MakeChamfer op(body.shape);
        std::vector<std::pair<TopoDS_Edge, std::string>> added;
        for (const auto& name : edges) {
            for (const auto& e : body.findEdges(name)) {
                op.Add(distance, e);
                added.push_back({e, name});
            }
        }
        if (added.empty()) throw std::runtime_error("The edges to bevel aren't there any more");
        op.Build();
        if (!op.IsDone() || !BRepCheck_Analyzer(op.Shape()).IsValid()) throw std::runtime_error("The chamfer doesn't fit");
        NamedShape out = carryNames({&body}, op, op.Shape(), prefix(id));
        for (const auto& [e, name] : added)
            for (const auto& f : op.Generated(e)) out.names.Bind(f, prefix(id) + ".c(" + name + ")");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The chamfer doesn't fit");
    }
}

gp_Ax3 facePlane(const NamedShape& body, const std::string& name) {
    auto faces = body.findFaces(name);
    if (faces.empty()) throw std::runtime_error("The face to sketch on isn't there any more");
    const TopoDS_Face& f = faces[0];
    BRepAdaptor_Surface surface(f);
    if (surface.GetType() != GeomAbs_Plane) throw std::runtime_error("The face to sketch on isn't flat any more");
    GProp_GProps props;
    BRepGProp::SurfaceProperties(f, props);
    gp_Pln pln = surface.Plane();
    gp_Dir n = pln.Axis().Direction();
    if (f.Orientation() == TopAbs_REVERSED) n.Reverse();
    return gp_Ax3(props.CentreOfMass(), n);
}

bool overlaps(const NamedShape& a, const NamedShape& b) {
    try {
        BRepAlgoAPI_Common common(a.shape, b.shape);
        return common.IsDone() && volume(common.Shape()) > 1e-9;
    } catch (const Standard_Failure&) {
        return false;
    }
}

}  // namespace pm
