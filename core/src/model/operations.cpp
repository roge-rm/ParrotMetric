#include "model/operations.h"

#include <BRepAdaptor_Surface.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepAlgoAPI_Splitter.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepOffsetAPI_DraftAngle.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepPrimAPI_MakeCone.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRep_Builder.hxx>
#include <TopoDS_Compound.hxx>
#include <gp_Pln.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepBndLib.hxx>
#include <BRepGProp.hxx>
#include <Bnd_Box.hxx>
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

NamedShape shell(int id, const NamedShape& body, const std::vector<std::string>& open, double thickness) {
    if (thickness <= 0) throw std::runtime_error("The wall has to be more than 0 thick");
    try {
        NCollection_List<TopoDS_Shape> faces;
        for (const auto& n : open)
            for (const auto& f : body.findFaces(n)) faces.Append(f);
        BRepOffsetAPI_MakeThickSolid op;
        // Negative grows inwards, keeping the outside where it was.
        op.MakeThickSolidByJoin(body.shape, faces, -thickness, 1e-4, BRepOffset_Skin, true, false, GeomAbs_Intersection);
        if (!op.IsDone() || !BRepCheck_Analyzer(op.Shape()).IsValid()) throw std::runtime_error("The walls are too thick for this shape");
        return carryNames({&body}, op, op.Shape(), prefix(id));
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The walls are too thick for this shape");
    }
}

NamedShape draft(int id, const NamedShape& body, const std::vector<std::string>& faces, const std::string& neutral, double angle) {
    try {
        auto base = body.findFaces(neutral);
        if (base.empty()) throw std::runtime_error("The face it pivots on isn't there any more");
        BRepAdaptor_Surface surface(base[0]);
        if (surface.GetType() != GeomAbs_Plane) throw std::runtime_error("It has to pivot on a flat face");
        gp_Pln pln = surface.Plane();
        // Pulled away from the neutral face, into the body, so a positive angle narrows it.
        gp_Dir pull = pln.Axis().Direction();
        if (base[0].Orientation() != TopAbs_REVERSED) pull.Reverse();
        BRepOffsetAPI_DraftAngle op(body.shape);
        int added = 0;
        for (const auto& n : faces)
            for (const auto& f : body.findFaces(n)) {
                op.Add(f, pull, angle, pln);
                if (!op.AddDone()) throw std::runtime_error("A face can't be tilted that way");
                added++;
            }
        if (!added) throw std::runtime_error("The faces to tilt aren't there any more");
        op.Build();
        if (!op.IsDone() || !BRepCheck_Analyzer(op.Shape()).IsValid()) throw std::runtime_error("The faces can't be tilted that far");
        return carryNames({&body}, op, op.Shape(), prefix(id));
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The faces can't be tilted that far");
    }
}

NamedShape transformed(int id, const NamedShape& body, const double m[12], const std::string& tag) {
    gp_Trsf t;
    t.SetValues(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11]);
    try {
        BRepBuilderAPI_Transform op(body.shape, t, true);
        NamedShape out;
        out.shape = op.Shape();
        for (TopExp_Explorer f(body.shape, TopAbs_FACE); f.More(); f.Next()) {
            const auto& moved = op.Modified(f.Current());
            std::string name = prefix(id) + "." + tag + "(" + body.faceName(f.Current()) + ")";
            for (const auto& m2 : moved) out.names.Bind(m2, name);
            if (moved.IsEmpty()) out.names.Bind(op.ModifiedShape(f.Current()), name);
        }
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The body couldn't be moved");
    }
}

std::vector<NamedShape> split(int id, const NamedShape& body, const gp_Pnt& origin, const gp_Dir& normal) {
    try {
        Bnd_Box box;
        BRepBndLib::Add(body.shape, box);
        double size = std::sqrt(box.SquareExtent()) * 2 + 10;
        TopoDS_Face sheet = BRepBuilderAPI_MakeFace(gp_Pln(origin, normal), -size, size, -size, size);
        BRepAlgoAPI_Splitter op;
        TopTools_ListOfShape args, tools;
        args.Append(body.shape);
        tools.Append(sheet);
        op.SetArguments(args);
        op.SetTools(tools);
        op.Build();
        if (!op.IsDone()) throw std::runtime_error("The body couldn't be split");
        NamedShape all = carryNames({&body}, op, op.Shape(), prefix(id));
        std::vector<NamedShape> out;
        for (TopExp_Explorer s(all.shape, TopAbs_SOLID); s.More(); s.Next()) {
            NamedShape piece;
            piece.shape = s.Current();
            for (TopExp_Explorer f(piece.shape, TopAbs_FACE); f.More(); f.Next()) piece.names.Bind(f.Current(), all.faceName(f.Current()));
            out.push_back(std::move(piece));
        }
        if (out.size() < 2) throw std::runtime_error("The plane doesn't cut through the body");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The body couldn't be split");
    }
}

NamedShape holeTool(int id, const gp_Ax3& plane, const std::vector<std::pair<double, double>>& at, double diameter, double depth,
                    HoleKind kind, double topDiameter, double topDepth) {
    if (diameter <= 0) throw std::runtime_error("The hole has to be wider than 0");
    if (at.empty()) throw std::runtime_error("Pick points for the holes");
    if (kind != HoleKind::Simple && topDiameter <= diameter) throw std::runtime_error("The top has to be wider than the hole");
    try {
        const double deep = depth > 0 ? depth : 2000;
        gp_Trsf onPlane;
        onPlane.SetDisplacement(gp_Ax3(gp::XOY()), plane);
        TopoDS_Compound all;
        BRep_Builder b;
        b.MakeCompound(all);
        for (const auto& [u, v] : at) {
            // Built going down from the plane, a little above it so the top is clean.
            gp_Ax2 axis(gp_Pnt(u, v, 0.01), -gp::DZ());
            TopoDS_Shape hole = BRepPrimAPI_MakeCylinder(axis, diameter / 2, deep + 0.01).Shape();
            if (kind == HoleKind::Counterbore) {
                TopoDS_Shape bore = BRepPrimAPI_MakeCylinder(axis, topDiameter / 2, topDepth + 0.01).Shape();
                hole = BRepAlgoAPI_Fuse(hole, bore).Shape();
            } else if (kind == HoleKind::Countersink) {
                double h = (topDiameter - diameter) / 2;  // 90 degrees: as deep as it narrows.
                TopoDS_Shape cone = BRepPrimAPI_MakeCone(axis, topDiameter / 2 + 0.01, diameter / 2, h + 0.01).Shape();
                hole = BRepAlgoAPI_Fuse(hole, cone).Shape();
            }
            b.Add(all, hole.Moved(TopLoc_Location(onPlane)));
        }
        NamedShape out;
        out.shape = all;
        int k = 0;
        for (TopExp_Explorer f(all, TopAbs_FACE); f.More(); f.Next())
            if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + ".h" + std::to_string(k++));
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The hole couldn't be made");
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
