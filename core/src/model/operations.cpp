#include "model/operations.h"
#include "parallel.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BOPAlgo_Options.hxx>
#include <BRepOffset_Analyse.hxx>
#include <BRepOffsetAPI_MakeFilling.hxx>
#include <GeomAPI_ProjectPointOnSurf.hxx>
#include <GCPnts_AbscissaPoint.hxx>
#include <BRepOffset_MakeOffset.hxx>
#include <BRepAlgoAPI_Defeaturing.hxx>
#include <gp_Lin2d.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <TopTools_HSequenceOfShape.hxx>
#include <ShapeAnalysis_FreeBounds.hxx>
#include <Geom_CylindricalSurface.hxx>
#include <Geom2d_TrimmedCurve.hxx>
#include <Geom2d_Line.hxx>
#include <BRepOffsetAPI_ThruSections.hxx>
#include <BRepOffsetAPI_MakePipeShell.hxx>
#include <GeomAPI_Interpolate.hxx>
#include <GC_MakeArcOfCircle.hxx>
#include <BRepBuilderAPI_MakeVertex.hxx>
#include <BRepTools_WireExplorer.hxx>
#include <BRepBuilderAPI_Copy.hxx>
#include <TColgp_HArray1OfPnt.hxx>
#include <BRepOffsetAPI_MakePipe.hxx>
#include <BRepLib.hxx>
#include <BRepAdaptor_CompCurve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepAlgoAPI_Section.hxx>
#include <BRepAlgoAPI_Splitter.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_MakeSolid.hxx>
#include <BRepBuilderAPI_Sewing.hxx>
#include <BRepClass3d_SolidClassifier.hxx>
#include <BRep_Tool.hxx>
#include <ShapeUpgrade_UnifySameDomain.hxx>
#include <BRepTools_History.hxx>
#include <TopoDS_Shell.hxx>
#include <gp_Circ.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_GTransform.hxx>
#include <BRepTools.hxx>
#include <BRepTools_Modification.hxx>
#include <BRepTools_Modifier.hxx>
#include <ElCLib.hxx>
#include <GeomConvert_CurveToAnaCurve.hxx>
#include <GeomConvert_SurfToAnaSurf.hxx>
#include <GeomLProp_SLProps.hxx>
#include <GeomProjLib.hxx>
#include <Geom_BSplineCurve.hxx>
#include <Geom_BSplineSurface.hxx>
#include <Geom_Line.hxx>
#include <Geom_Plane.hxx>
#include <Geom_TrimmedCurve.hxx>
#include <BRepBuilderAPI_NurbsConvert.hxx>
#include <ShapeFix_Shape.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <gp_GTrsf.hxx>
#include <BRepOffsetAPI_DraftAngle.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepPrimAPI_MakeCone.hxx>
#include <Precision.hxx>
#include <ShapeFix_Face.hxx>
#include <BRepPrimAPI_MakeTorus.hxx>
#include <BRepPrimAPI_MakeSphere.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepOffsetAPI_MakeOffset.hxx>
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
#include <TopTools_MapOfShape.hxx>
#include <TopoDS.hxx>
#include <gp_Ax1.hxx>
#include <gp_Trsf.hxx>

#include <BRepTopAdaptor_FClass2d.hxx>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <memory>
#include <limits>
#include <stdexcept>

#include "mesh/mesh.h"
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
    if (pieces.size() < 2) return out;
    // Areas side by side leave seams across their ends: merged, each end is one face again. Only faces
    // with the same name merge, so every name a later step can refer to is still there.
    ShapeUpgrade_UnifySameDomain unify(out.shape, true, true, false);
    TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
    TopExp::MapShapesAndAncestors(out.shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);
    for (int i = 1; i <= edgeFaces.Extent(); ++i) {
        const auto& faces = edgeFaces(i);
        std::string first;
        bool differ = false;
        for (const auto& f : faces) {
            std::string n = out.faceName(f);
            if (first.empty()) first = n;
            else if (n != first) differ = true;
        }
        if (differ || first.empty()) unify.KeepShape(edgeFaces.FindKey(i));
    }
    unify.Build();
    if (unify.Shape().IsNull() || !BRepCheck_Analyzer(unify.Shape()).IsValid()) return out;
    NamedShape merged;
    merged.shape = unify.Shape();
    TopTools_IndexedMapOfShape present;
    TopExp::MapShapes(merged.shape, TopAbs_FACE, present);
    Handle(BRepTools_History) history = unify.History();
    for (TopExp_Explorer f(out.shape, TopAbs_FACE); f.More(); f.Next()) {
        std::string name = out.faceName(f.Current());
        if (name.empty()) continue;
        if (present.Contains(f.Current())) {
            if (!merged.names.IsBound(f.Current())) merged.names.Bind(f.Current(), name);
            continue;
        }
        for (const auto& m : history->Modified(f.Current()))
            if (present.Contains(m) && !merged.names.IsBound(m)) merged.names.Bind(m, name);
    }
    int k = 0;
    for (int i = 1; i <= present.Extent(); ++i)
        if (!merged.names.IsBound(present(i))) merged.names.Bind(present(i), prefix(id) + ".n" + std::to_string(k++));
    return merged;
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

namespace {

/**
 * Names through an operation that changes faces in place, as a draft does:
 * OCCT reports those through ModifiedShape, so faces carryNames had to give
 * new names get their old ones back.
 */
NamedShape keepNames(int id, const NamedShape& body, BRepBuilderAPI_ModifyShape& op) {
    NamedShape out = carryNames({&body}, op, op.Shape(), prefix(id));
    TopTools_IndexedMapOfShape present;
    TopExp::MapShapes(out.shape, TopAbs_FACE, present);
    std::string made = prefix(id) + ".n";
    for (TopExp_Explorer f(body.shape, TopAbs_FACE); f.More(); f.Next()) {
        std::string name = body.faceName(f.Current());
        if (name.empty()) continue;
        TopoDS_Shape now;
        try {
            now = op.ModifiedShape(f.Current());
        } catch (const Standard_Failure&) {
            continue;
        }
        if (!present.Contains(now)) continue;
        std::string* bound = out.names.ChangeSeek(now);
        if (!bound) out.names.Bind(now, name);
        else if (bound->rfind(made, 0) == 0) *bound = name;
    }
    return out;
}

/** Leans the sides of extrude `id` in by angle going along the plane's normal, pivoting at the plane. */
NamedShape taperSides(int id, const NamedShape& body, const gp_Ax3& plane, double angle) {
    if (std::abs(angle) >= M_PI / 2) throw std::runtime_error("The taper has to be less than 90°");
    BRepOffsetAPI_DraftAngle op(body.shape);
    gp_Pln pivot(plane.Location(), plane.Direction());
    std::string side = prefix(id) + ".s";
    for (TopExp_Explorer f(body.shape, TopAbs_FACE); f.More(); f.Next()) {
        // Sides only: F<id>.s<curve>, not F<id>.start.
        std::string name = body.faceName(f.Current());
        if (name.rfind(side, 0) != 0 || name.size() <= side.size() || !std::isdigit(static_cast<unsigned char>(name[side.size()]))) continue;
        op.Add(TopoDS::Face(f.Current()), plane.Direction(), angle, pivot);
        if (!op.AddDone()) throw std::runtime_error("A side can't be tapered");
    }
    op.Build();
    if (!op.IsDone() || !BRepCheck_Analyzer(op.Shape()).IsValid()) throw std::runtime_error("The taper is too steep for this shape");
    return keepNames(id, body, op);
}

}  // namespace

namespace {

/** A region face with only a wall [thin] thick left inside its edges. */
TopoDS_Face thinned(const TopoDS_Face& face, double thin) {
    BRepOffsetAPI_MakeOffset offset(face, GeomAbs_Arc);
    offset.Perform(-thin);
    if (!offset.IsDone()) throw std::runtime_error("The wall is too thick for the sketch");
    // The inside of the wall: the offset wires as a face on the same plane.
    TopoDS_Shape wires = offset.Shape();
    BRepBuilderAPI_MakeFace inner(BRep_Tool::Surface(face), Precision::Confusion());
    bool any = false;
    for (TopExp_Explorer w(wires, TopAbs_WIRE); w.More(); w.Next()) {
        inner.Add(TopoDS::Wire(w.Current()));
        any = true;
    }
    if (!any || !inner.IsDone()) throw std::runtime_error("The wall is too thick for the sketch");
    TopoDS_Face hollow = inner.Face();
    ShapeFix_Face fix(hollow);
    fix.Perform();
    hollow = fix.Face();
    BRepAlgoAPI_Cut cut(face, hollow);
    if (!cut.IsDone()) throw std::runtime_error("The wall is too thick for the sketch");
    for (TopExp_Explorer f(cut.Shape(), TopAbs_FACE); f.More(); f.Next()) return TopoDS::Face(f.Current());
    throw std::runtime_error("The wall is too thick for the sketch");
}

}  // namespace

NamedShape openWall(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, double forward, double back, double thin);

NamedShape extrude(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double forward, double back, double taper, double thin) {
    if (std::abs(forward + back) < 1e-6) throw std::runtime_error("The extrude has no length");
    if (thin < 0) throw std::runtime_error("The wall has to be thicker than 0");
    if (picks.empty() && thin > 0) {
        NamedShape out = openWall(id, plane, curves, forward, back, thin);
        if (std::abs(taper) > 1e-9) out = taperSides(id, out, plane, taper);
        return out;
    }
    try {
        auto regions = buildRegionFaces(curves);
        std::vector<NamedShape> pieces;
        gp_Trsf onPlane = placeOn(plane);
        gp_Trsf shift;
        shift.SetTranslation(gp_Vec(plane.Direction()) * -back);
        TopLoc_Location loc(shift * onPlane);
        gp_Vec sweep = gp_Vec(plane.Direction()) * (forward + back);
        for (const RegionFace* r : choose(regions, picks)) {
            TopoDS_Face face = TopoDS::Face((thin > 0 ? thinned(r->face, thin) : r->face).Moved(loc));
            BRepPrimAPI_MakePrism prism(face, sweep);
            if (!prism.IsDone()) throw std::runtime_error("The extrude couldn't be made");
            pieces.push_back(nameSweep(id, prism, *r, loc));
        }
        NamedShape out = fuseAll(id, std::move(pieces));
        check(out.shape, "The extrude couldn't be made");
        if (std::abs(taper) > 1e-9) out = taperSides(id, out, plane, taper);
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
            case Combine::Join: op = std::make_unique<BRepAlgoAPI_Fuse>(); break;
            case Combine::Cut: op = std::make_unique<BRepAlgoAPI_Cut>(); break;
            case Combine::Intersect: op = std::make_unique<BRepAlgoAPI_Common>(); break;
        }
        TopTools_ListOfShape args, tools;
        args.Append(target.shape);
        tools.Append(tool.shape);
        op->SetArguments(args);
        op->SetTools(tools);
        op->SetRunParallel(useCores());
        op->Build();
        if (!op->IsDone()) throw std::runtime_error("The bodies couldn't be combined");
        // Checked only when OCCT warns about something: clean results skip the cost.
        if (op->HasWarnings() && !BRepCheck_Analyzer(op->Shape()).IsValid()) {
            // Surfaces that cross at a shallow angle, as crossed helical grooves do, can come out broken;
            // a small fuzzy tolerance usually sorts them out.
            op->SetFuzzyValue(1e-4);
            op->Build();
            if (!op->IsDone()) throw std::runtime_error("The bodies couldn't be combined");
        }
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

NamedShape fillet(int id, const NamedShape& body, const std::vector<std::string>& edges, FilletKind kind, double size, double second) {
    if (kind == FilletKind::Constant) return fillet(id, body, edges, size);
    if (size <= 0 || (kind == FilletKind::Variable && second <= 0)) throw std::runtime_error("Sizes have to be more than 0");
    try {
        BRepFilletAPI_MakeFillet op(body.shape);
        TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
        TopExp::MapShapesAndAncestors(body.shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);
        std::vector<std::pair<TopoDS_Edge, std::string>> added;
        for (const auto& name : edges) {
            for (const auto& e : body.findEdges(name)) {
                if (kind == FilletKind::Variable) {
                    op.Add(size, second, e);
                } else {
                    // The radius that makes the rounding size across: from the angle between the faces at the edge's middle.
                    const TopTools_ListOfShape& faces = edgeFaces.FindFromKey(e);
                    if (faces.Extent() < 2) continue;
                    BRepAdaptor_Curve curve(e);
                    gp_Pnt mid = curve.Value((curve.FirstParameter() + curve.LastParameter()) / 2);
                    auto normal = [&](const TopoDS_Face& f) {
                        BRepAdaptor_Surface s(f);
                        GeomAPI_ProjectPointOnSurf onto(mid, BRep_Tool::Surface(f));
                        double u = 0, v = 0;
                        if (onto.NbPoints() > 0) onto.LowerDistanceParameters(u, v);
                        gp_Pnt p;
                        gp_Vec du, dv;
                        s.D1(u, v, p, du, dv);
                        gp_Vec n = du.Crossed(dv);
                        if (f.Orientation() == TopAbs_REVERSED) n.Reverse();
                        return n;
                    };
                    double turn = normal(TopoDS::Face(faces.First())).Angle(normal(TopoDS::Face(faces.Last())));
                    if (turn < 1e-6) continue;
                    op.Add(size / (2 * std::sin(turn / 2)), e);
                }
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

NamedShape offsetFaces(int id, const NamedShape& body, const std::vector<std::string>& faces, double distance) {
    if (std::abs(distance) < 1e-9) throw std::runtime_error("Move the faces by more than 0");
    try {
        NamedShape current = body;
        for (const auto& name : faces) {
            auto found = current.findFaces(name);
            if (found.empty()) throw std::runtime_error("A face to move isn't there any more");
            const TopoDS_Face& face = found[0];
            BRepAdaptor_Surface surface(face);
            if (surface.GetType() != GeomAbs_Plane) {
                // Curved: OCCT's offset, this face only.
                BRepOffset_MakeOffset offset;
                offset.Initialize(current.shape, 0, 1e-6, BRepOffset_Skin, false, false, GeomAbs_Intersection);
                offset.SetOffsetOnFace(face, distance);
                offset.MakeOffsetShape();
                if (!offset.IsDone()) throw std::runtime_error("That face can't be moved that far");
                NamedShape moved;
                moved.shape = offset.Shape();
                int k = 0;
                for (TopExp_Explorer f(moved.shape, TopAbs_FACE); f.More(); f.Next()) {
                    // Faces whose image came from an original keep its name.
                    std::string n;
                    for (TopExp_Explorer o(current.shape, TopAbs_FACE); o.More() && n.empty(); o.Next())
                        if (offset.OffsetFacesFromShapes().HasImage(o.Current())) {
                            TopTools_ListOfShape images;
                            offset.OffsetFacesFromShapes().LastImage(o.Current(), images);
                            for (const auto& im : images) if (im.IsSame(f.Current())) n = current.faceName(o.Current());
                        }
                    moved.names.Bind(f.Current(), n.empty() ? prefix(id) + ".o" + std::to_string(k++) : n);
                }
                check(moved.shape, "That face can't be moved that far");
                current = std::move(moved);
                continue;
            }
            // Flat: a slab on the face, joined on or cut away.
            gp_Dir n = surface.Plane().Axis().Direction();
            if (face.Orientation() == TopAbs_REVERSED) n.Reverse();
            BRepPrimAPI_MakePrism slab(face, gp_Vec(n) * distance);
            if (!slab.IsDone()) throw std::runtime_error("That face can't be moved");
            NamedShape tool;
            tool.shape = slab.Shape();
            int k = 0;
            TopoDS_Shape far = slab.LastShape();
            for (TopExp_Explorer f(tool.shape, TopAbs_FACE); f.More(); f.Next()) {
                bool end = false;
                for (TopExp_Explorer l(far, TopAbs_FACE); l.More(); l.Next()) end = end || l.Current().IsSame(f.Current());
                tool.names.Bind(f.Current(), end ? name : prefix(id) + ".o" + std::to_string(k++));
            }
            NamedShape joined = combine(id, current, tool, distance > 0 ? Combine::Join : Combine::Cut);
            // A push leaves the slab's far face as the new face; it keeps the old name, which combine gave a new one.
            current = std::move(joined);
        }
        return current;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("That face can't be moved that far");
    }
}

NamedShape deleteFaces(int id, const NamedShape& body, const std::vector<std::string>& faces) {
    try {
        BRepAlgoAPI_Defeaturing op;
        op.SetShape(body.shape);
        bool any = false;
        for (const auto& name : faces)
            for (const auto& f : body.findFaces(name)) { op.AddFaceToRemove(f); any = true; }
        if (!any) throw std::runtime_error("The faces to take away aren't there any more");
        op.SetRunParallel(useCores());
        op.Build();
        if (!op.IsDone()) throw std::runtime_error("Those faces can't be taken away: the faces round them can't close the gap");
        NamedShape out = carryNames({&body}, op, op.Shape(), prefix(id));
        check(out.shape, "Those faces can't be taken away");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("Those faces can't be taken away");
    }
}

std::vector<std::array<double, 12>> pathPlaces(const TopoDS_Wire& path, int count, double spacing, bool turn, bool reverse) {
    if (count < 1 || count > 500) throw std::runtime_error("Use between 1 and 500 copies");
    try {
        BRepAdaptor_CompCurve curve(path);
        const double length = GCPnts_AbscissaPoint::Length(curve);
        if (length < 1e-9) throw std::runtime_error("The path has no length");
        const double step = spacing > 0 ? spacing : (count > 1 ? length / (count - 1) : 0);
        if (step * (count - 1) > length + 1e-6) throw std::runtime_error("The copies don't fit on the path at that spacing");
        auto at = [&](double s, gp_Pnt& p, gp_Vec& t) {
            s = std::min(s, length);
            GCPnts_AbscissaPoint along(curve, reverse ? length - s : s, curve.FirstParameter());
            curve.D1(along.Parameter(), p, t);
            if (reverse) t.Reverse();
        };
        gp_Pnt p0;
        gp_Vec t0;
        at(0, p0, t0);
        std::vector<std::array<double, 12>> out;
        for (int i = 0; i < count; ++i) {
            gp_Pnt p;
            gp_Vec t;
            at(i * step, p, t);
            gp_Trsf m;
            if (turn && t.Magnitude() > 1e-12 && t0.Magnitude() > 1e-12) {
                gp_Dir a(t0), b(t);
                gp_Vec axis = gp_Vec(a).Crossed(gp_Vec(b));
                if (axis.Magnitude() > 1e-12) m.SetRotation(gp_Ax1(p0, gp_Dir(axis)), a.Angle(b));
                else if (a.Dot(b) < 0) m.SetRotation(gp_Ax1(p0, gp_Dir(gp_Vec(a).Crossed(std::abs(a.X()) < 0.9 ? gp_Vec(1, 0, 0) : gp_Vec(0, 1, 0)))), M_PI);
            }
            gp_Trsf move;
            move.SetTranslation(p0, p);
            gp_Trsf all = move * m;
            std::array<double, 12> r{};
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 3; ++col) r[size_t(row * 4 + col)] = all.Value(row + 1, col + 1);
                r[size_t(row * 4 + 3)] = all.Value(row + 1, 4);
            }
            out.push_back(r);
        }
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The copies couldn't be placed along the path");
    }
}

NamedShape chamfer(int id, const NamedShape& body, const std::vector<std::string>& edges, double distance, ChamferKind kind,
                   double second, bool flip) {
    if (distance <= 0) throw std::runtime_error("The distance has to be more than 0");
    if (kind == ChamferKind::TwoDistances && second <= 0) throw std::runtime_error("The second distance has to be more than 0");
    if (kind == ChamferKind::DistanceAngle && (second <= 0 || second >= M_PI / 2)) throw std::runtime_error("The angle has to be between 0° and 90°");
    try {
        BRepFilletAPI_MakeChamfer op(body.shape);
        TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
        TopExp::MapShapesAndAncestors(body.shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);
        std::vector<std::pair<TopoDS_Edge, std::string>> added;
        for (const auto& name : edges) {
            for (const auto& e : body.findEdges(name)) {
                if (kind == ChamferKind::Equal) {
                    op.Add(distance, e);
                } else {
                    // The face that gets `distance`: the edge's first, or its other one if flipped.
                    const TopTools_ListOfShape& faces = edgeFaces.FindFromKey(e);
                    TopoDS_Face face = TopoDS::Face(flip && faces.Extent() > 1 ? faces.Last() : faces.First());
                    if (kind == ChamferKind::TwoDistances) op.Add(distance, second, e, face);
                    else op.AddDA(distance, second, e, face);
                }
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
        // Where the body has no concave edges, rounded joins give the same walls as
        // sharp ones, and work out about twice as fast on curved bodies. With a
        // concave edge they'd round the inside of the wall there, so not then.
        bool concave = false;
        {
            BRepOffset_Analyse edges(body.shape, 0.01);
            TopTools_IndexedMapOfShape all;
            TopExp::MapShapes(body.shape, TopAbs_EDGE, all);
            for (int i = 1; i <= all.Extent() && !concave; ++i)
                for (const auto& kind : edges.Type(TopoDS::Edge(all(i))))
                    if (kind.Type() == ChFiDS_Concave) concave = true;
        }
        // OCCT's booleans inside the offset follow the cores setting.
        BOPAlgo_Options::SetParallelMode(useCores());
        BRepOffsetAPI_MakeThickSolid op;
        // Negative grows inwards, keeping the outside where it was.
        op.MakeThickSolidByJoin(body.shape, faces, -thickness, 1e-4, BRepOffset_Skin, true, false, concave ? GeomAbs_Intersection : GeomAbs_Arc);
        if (!op.IsDone() || !BRepCheck_Analyzer(op.Shape()).IsValid()) throw std::runtime_error("The walls are too thick for this shape");
        NamedShape out = carryNames({&body}, op, op.Shape(), prefix(id));
        // Each inside face is named after the outside face it lines, F<id>.in(<that name>):
        // the order the offset makes them in changes from run to run, so numbers wouldn't hold.
        TopTools_IndexedMapOfShape present;
        TopExp::MapShapes(out.shape, TopAbs_FACE, present);
        const std::string made = prefix(id) + ".n";
        for (TopExp_Explorer f(body.shape, TopAbs_FACE); f.More(); f.Next()) {
            std::string name = body.faceName(f.Current());
            if (name.empty()) continue;
            for (const auto& g : op.Generated(f.Current())) {
                if (!present.Contains(g)) continue;
                std::string inside = prefix(id) + ".in(" + name + ")";
                std::string* bound = out.names.ChangeSeek(g);
                if (!bound) out.names.Bind(g, inside);
                else if (bound->rfind(made, 0) == 0) *bound = inside;
            }
        }
        return out;
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
        return keepNames(id, body, op);
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The faces can't be tilted that far");
    }
}

namespace {

/** True if the 3x3 part only turns, mirrors and scales evenly, as gp_Trsf can. */
bool similarity(const double m[12]) {
    double c[3][3];
    for (int r = 0; r < 3; ++r)
        for (int k = 0; k < 3; ++k) c[r][k] = m[r * 4 + k];
    double s = c[0][0] * c[0][0] + c[1][0] * c[1][0] + c[2][0] * c[2][0];
    for (int a = 0; a < 3; ++a)
        for (int b = 0; b < 3; ++b) {
            double dot = c[0][a] * c[0][b] + c[1][a] * c[1][b] + c[2][a] * c[2][b];
            if (std::abs(dot - (a == b ? s : 0)) > 1e-9 * std::max(1.0, s)) return false;
        }
    return true;
}

/**
 * Turns spline faces that are flat back into planes, and spline edges that
 * are straight, with planes on both sides, back into lines. An uneven scale
 * leaves everything as splines, which later steps can't sketch on, measure
 * or project neatly.
 */
class Flatten : public BRepTools_Modification {
public:
    Flatten(const TopoDS_Shape& shape, double tol) {
        for (TopExp_Explorer f(shape, TopAbs_FACE); f.More(); f.Next()) {
            const TopoDS_Face& face = TopoDS::Face(f.Current());
            if (auto plane = planeFor(face, tol)) planes_.Bind(face, plane);
        }
        TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
        TopExp::MapShapesAndAncestors(shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);
        for (int i = 1; i <= edgeFaces.Extent(); ++i) {
            const TopoDS_Edge& e = TopoDS::Edge(edgeFaces.FindKey(i));
            bool flatAround = !edgeFaces(i).IsEmpty();
            for (const auto& face : edgeFaces(i)) flatAround = flatAround && planes_.IsBound(face);
            if (!flatAround || BRep_Tool::Degenerated(e)) continue;
            if (auto line = lineFor(e, tol)) lines_.Bind(e, line);
        }
    }

    bool changed() const { return !planes_.IsEmpty(); }

    bool NewSurface(const TopoDS_Face& F, occ::handle<Geom_Surface>& S, TopLoc_Location& L, double& Tol, bool& RevWires,
                    bool& RevFace) override {
        if (!planes_.IsBound(F)) return false;
        BRep_Tool::Surface(F, L);
        S = planes_.Find(F);
        Tol = BRep_Tool::Tolerance(F);
        RevWires = RevFace = false;
        return true;
    }

    bool NewCurve(const TopoDS_Edge& E, occ::handle<Geom_Curve>& C, TopLoc_Location& L, double& Tol) override {
        if (!lines_.IsBound(E)) return false;
        double f, l;
        BRep_Tool::Curve(E, L, f, l);
        C = lines_.Find(E);
        Tol = BRep_Tool::Tolerance(E);
        return true;
    }

    bool NewPoint(const TopoDS_Vertex&, gp_Pnt&, double&) override { return false; }

    bool NewCurve2d(const TopoDS_Edge& E, const TopoDS_Face& F, const TopoDS_Edge&, const TopoDS_Face&, occ::handle<Geom2d_Curve>& C,
                    double& Tol) override {
        if (!planes_.IsBound(F)) return false;
        double f, l;
        TopLoc_Location loc;
        occ::handle<Geom_Curve> curve;
        if (lines_.IsBound(E)) {
            curve = lines_.Find(E);
            TopoDS_Vertex v1, v2;
            TopExp::Vertices(E, v1, v2);
            gp_Lin lin = occ::down_cast<Geom_Line>(curve)->Lin();
            f = ElCLib::Parameter(lin, BRep_Tool::Pnt(v1));
            l = ElCLib::Parameter(lin, BRep_Tool::Pnt(v2));
        } else {
            curve = BRep_Tool::Curve(E, loc, f, l);
            if (curve.IsNull()) return false;
        }
        C = GeomProjLib::Curve2d(curve, f, l, planes_.Find(F));
        Tol = BRep_Tool::Tolerance(E);
        return !C.IsNull();
    }

    bool NewParameter(const TopoDS_Vertex& V, const TopoDS_Edge& E, double& P, double& Tol) override {
        if (!lines_.IsBound(E)) return false;
        P = ElCLib::Parameter(occ::down_cast<Geom_Line>(lines_.Find(E))->Lin(), BRep_Tool::Pnt(V));
        Tol = BRep_Tool::Tolerance(V);
        return true;
    }

    GeomAbs_Shape Continuity(const TopoDS_Edge& E, const TopoDS_Face& F1, const TopoDS_Face& F2, const TopoDS_Edge&, const TopoDS_Face&,
                             const TopoDS_Face&) override {
        return BRep_Tool::Continuity(E, F1, F2);
    }

private:
    /** The plane a flat spline face lies in, facing the same way, or null. */
    static occ::handle<Geom_Plane> planeFor(const TopoDS_Face& face, double tol) {
        TopLoc_Location loc;
        occ::handle<Geom_Surface> s = BRep_Tool::Surface(face, loc);
        if (s.IsNull() || !s->IsKind(STANDARD_TYPE(Geom_BSplineSurface))) return nullptr;
        GeomConvert_SurfToAnaSurf convert(s);
        auto plane = occ::down_cast<Geom_Plane>(convert.ConvertToAnalytical(tol));
        if (plane.IsNull()) return nullptr;
        double u0, u1, v0, v1;
        BRepTools::UVBounds(face, u0, u1, v0, v1);
        GeomLProp_SLProps props(s, (u0 + u1) / 2, (v0 + v1) / 2, 1, tol);
        if (props.IsNormalDefined() && props.Normal().Dot(plane->Axis().Direction()) < 0) plane->UReverse();
        return plane;
    }

    /** The line a straight spline edge lies on, running the same way, or null. */
    static occ::handle<Geom_Line> lineFor(const TopoDS_Edge& edge, double tol) {
        double f, l;
        TopLoc_Location loc;
        occ::handle<Geom_Curve> c = BRep_Tool::Curve(edge, loc, f, l);
        if (auto trimmed = occ::down_cast<Geom_TrimmedCurve>(c)) c = trimmed->BasisCurve();
        if (c.IsNull() || !c->IsKind(STANDARD_TYPE(Geom_BSplineCurve))) return nullptr;
        double cf, cl, gap = 0;
        auto line = occ::down_cast<Geom_Line>(GeomConvert_CurveToAnaCurve::ComputeCurve(c, tol, f, l, cf, cl, gap, GeomConvert_MinGap, GeomAbs_Line));
        if (line.IsNull() || gap > tol) return nullptr;
        gp_Lin lin = line->Lin();
        if (ElCLib::Parameter(lin, c->Value(f)) > ElCLib::Parameter(lin, c->Value(l))) line->Reverse();
        return line;
    }

    NCollection_DataMap<TopoDS_Shape, occ::handle<Geom_Plane>, TopTools_ShapeMapHasher> planes_;
    NCollection_DataMap<TopoDS_Shape, occ::handle<Geom_Line>, TopTools_ShapeMapHasher> lines_;
};

/** body with its flat spline faces made planes again (see Flatten), or as it was if that doesn't come out valid. */
NamedShape flattened(const NamedShape& body) {
    try {
        occ::handle<Flatten> flatten = new Flatten(body.shape, 1e-6);
        if (!flatten->changed()) return body;
        BRepTools_Modifier modifier(body.shape, flatten);
        if (!modifier.IsDone()) return body;
        NamedShape out;
        out.shape = modifier.ModifiedShape(body.shape);
        if (out.shape.IsNull() || !BRepCheck_Analyzer(out.shape).IsValid()) return body;
        for (TopExp_Explorer f(body.shape, TopAbs_FACE); f.More(); f.Next())
            out.names.Bind(modifier.ModifiedShape(f.Current()), body.faceName(f.Current()));
        return out;
    } catch (const Standard_Failure&) {
        return body;
    }
}

template <class Op>
NamedShape named(int id, const NamedShape& body, Op& op, const std::string& tag) {
    NamedShape out;
    out.shape = op.Shape();
    for (TopExp_Explorer f(body.shape, TopAbs_FACE); f.More(); f.Next()) {
        const auto& moved = op.Modified(f.Current());
        std::string name = prefix(id) + "." + tag + "(" + body.faceName(f.Current()) + ")";
        for (const auto& m2 : moved) out.names.Bind(m2, name);
        if (moved.IsEmpty()) out.names.Bind(op.ModifiedShape(f.Current()), name);
    }
    return out;
}

}  // namespace

NamedShape transformed(int id, const NamedShape& body, const double m[12], const std::string& tag) {
    try {
        if (!similarity(m)) {
            // Scaled unevenly: OCCT's general transform, which turns surfaces into splines.
            gp_GTrsf g;
            g.SetVectorialPart(gp_Mat(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]));
            g.SetTranslationPart(gp_XYZ(m[3], m[7], m[11]));
            BRepBuilderAPI_GTransform op(body.shape, g, true);
            NamedShape out = named(id, body, op, tag);
            if (out.shape.IsNull() || !BRepCheck_Analyzer(out.shape).IsValid()) {
                // Some shapes come out invalid: turned into splines first they scale cleanly.
                BRepBuilderAPI_NurbsConvert nurbs(body.shape, true);
                NamedShape spline;
                spline.shape = nurbs.Shape();
                for (TopExp_Explorer f(body.shape, TopAbs_FACE); f.More(); f.Next())
                    spline.names.Bind(nurbs.ModifiedShape(f.Current()), body.faceName(f.Current()));
                BRepBuilderAPI_GTransform again(spline.shape, g, true);
                out = named(id, spline, again, tag);
            }
            if (!out.shape.IsNull() && !BRepCheck_Analyzer(out.shape).IsValid()) {
                // Last, OCCT's own repair. Its faces get new names.
                ShapeFix_Shape fix(out.shape);
                fix.Perform();
                if (BRepCheck_Analyzer(fix.Shape()).IsValid()) {
                    out = NamedShape();
                    out.shape = fix.Shape();
                    int k = 0;
                    for (TopExp_Explorer f(out.shape, TopAbs_FACE); f.More(); f.Next())
                        if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + ".n" + std::to_string(k++));
                }
            }
            check(out.shape, "The body couldn't be scaled that way");
            return flattened(out);
        }
        gp_Trsf t;
        t.SetValues(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11]);
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

namespace {

/** The separate pieces of a shape: its solids, or for surfaces, faces joined at their edges, but not across [cut]. */
std::vector<TopoDS_Shape> piecesOf(const TopoDS_Shape& shape, const TopTools_ListOfShape& cut) {
    TopTools_MapOfShape across;
    for (const auto& e : cut) across.Add(e);
    std::vector<TopoDS_Shape> out;
    for (TopExp_Explorer s(shape, TopAbs_SOLID); s.More(); s.Next()) out.push_back(s.Current());
    if (!out.empty()) return out;
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(shape, TopAbs_FACE, faces);
    TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
    TopExp::MapShapesAndAncestors(shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);
    std::vector<int> group(size_t(faces.Extent()) + 1, 0);
    int groups = 0;
    for (int i = 1; i <= faces.Extent(); ++i) {
        if (group[size_t(i)]) continue;
        ++groups;
        std::vector<int> todo{i};
        group[size_t(i)] = groups;
        while (!todo.empty()) {
            int f = todo.back();
            todo.pop_back();
            for (TopExp_Explorer e(faces(f), TopAbs_EDGE); e.More(); e.Next()) {
                int k = edgeFaces.FindIndex(e.Current());
                if (k == 0 || across.Contains(e.Current())) continue;
                for (const auto& g : edgeFaces(k)) {
                    int j = faces.FindIndex(g);
                    if (j > 0 && !group[size_t(j)]) {
                        group[size_t(j)] = groups;
                        todo.push_back(j);
                    }
                }
            }
        }
    }
    BRep_Builder b;
    for (int g = 1; g <= groups; ++g) {
        TopoDS_Compound c;
        b.MakeCompound(c);
        for (int i = 1; i <= faces.Extent(); ++i)
            if (group[size_t(i)] == g) b.Add(c, faces(i));
        out.push_back(c);
    }
    return out;
}

}  // namespace

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
        op.SetRunParallel(useCores());
        op.Build();
        if (!op.IsDone()) throw std::runtime_error("The body couldn't be split");
        NamedShape all = carryNames({&body}, op, op.Shape(), prefix(id));
        std::vector<NamedShape> out;
        for (const TopoDS_Shape& part : piecesOf(all.shape, op.SectionEdges())) {
            NamedShape piece;
            piece.shape = part;
            for (TopExp_Explorer f(piece.shape, TopAbs_FACE); f.More(); f.Next()) piece.names.Bind(f.Current(), all.faceName(f.Current()));
            out.push_back(std::move(piece));
        }
        if (out.size() < 2) throw std::runtime_error("The plane doesn't cut through the body");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The body couldn't be split");
    }
}

std::vector<NamedShape> splitBy(int id, const NamedShape& body, const NamedShape& tool) {
    try {
        BRepAlgoAPI_Splitter op;
        TopTools_ListOfShape args, tools;
        args.Append(body.shape);
        tools.Append(tool.shape);
        op.SetArguments(args);
        op.SetTools(tools);
        op.SetRunParallel(useCores());
        op.Build();
        if (!op.IsDone()) throw std::runtime_error("The body couldn't be split");
        NamedShape all = carryNames({&body, &tool}, op, op.Shape(), prefix(id));
        std::vector<NamedShape> out;
        for (const TopoDS_Shape& part : piecesOf(all.shape, op.SectionEdges())) {
            NamedShape piece;
            piece.shape = part;
            for (TopExp_Explorer f(piece.shape, TopAbs_FACE); f.More(); f.Next()) piece.names.Bind(f.Current(), all.faceName(f.Current()));
            out.push_back(std::move(piece));
        }
        if (out.size() < 2) throw std::runtime_error("The bodies don't cross");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The body couldn't be split");
    }
}

double overlapVolume(const NamedShape& a, const NamedShape& b) {
    try {
        Bnd_Box ba, bb;
        BRepBndLib::Add(a.shape, ba);
        BRepBndLib::Add(b.shape, bb);
        if (ba.IsOut(bb)) return 0;
        BRepAlgoAPI_Common common;
        TopTools_ListOfShape args, tools;
        args.Append(a.shape);
        tools.Append(b.shape);
        common.SetArguments(args);
        common.SetTools(tools);
        common.SetRunParallel(useCores());
        common.Build();
        return common.IsDone() ? volume(common.Shape()) : 0;
    } catch (const Standard_Failure&) {
        return 0;
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

NamedShape primitive(int id, const gp_Ax3& plane, Primitive kind, double u, double v, double a, double b, double c) {
    auto positive = [](double x) { if (x <= 0) throw std::runtime_error("Sizes have to be more than 0"); };
    positive(a);
    try {
        // Built standing on the origin, then placed.
        TopoDS_Shape shape;
        switch (kind) {
            case Primitive::Box:
                positive(b); positive(c);
                shape = BRepPrimAPI_MakeBox(gp_Pnt(-a / 2, -b / 2, 0), a, b, c).Shape();
                break;
            case Primitive::Cylinder:
                positive(b);
                shape = BRepPrimAPI_MakeCylinder(a / 2, b).Shape();
                break;
            case Primitive::Sphere:
                shape = BRepPrimAPI_MakeSphere(gp_Pnt(0, 0, a / 2), a / 2).Shape();
                break;
            case Primitive::Torus:
                positive(b);
                if (b >= a) throw std::runtime_error("The tube has to be narrower than the ring");
                shape = BRepPrimAPI_MakeTorus(gp_Ax2(gp_Pnt(0, 0, b / 2), gp::DZ()), a / 2, b / 2).Shape();
                break;
            case Primitive::Cone:
                positive(c);
                if (b < 0) throw std::runtime_error("The top can't be less than 0");
                if (std::abs(a - b) < 1e-9) throw std::runtime_error("Make the top and base different, or use a cylinder");
                shape = BRepPrimAPI_MakeCone(a / 2, b / 2, c).Shape();
                break;
        }
        // Names by which way each flat face looks, worked out before moving.
        std::vector<std::string> names;
        for (TopExp_Explorer f(shape, TopAbs_FACE); f.More(); f.Next()) {
            const TopoDS_Face& face = TopoDS::Face(f.Current());
            BRepAdaptor_Surface surface(face);
            std::string side = "side";
            if (surface.GetType() == GeomAbs_Plane) {
                gp_Dir n = surface.Plane().Axis().Direction();
                if (face.Orientation() == TopAbs_REVERSED) n.Reverse();
                if (n.Z() > 0.5) side = "end";
                else if (n.Z() < -0.5) side = "start";
                else if (n.X() > 0.5) side = "x1";
                else if (n.X() < -0.5) side = "x0";
                else if (n.Y() > 0.5) side = "y1";
                else side = "y0";
            }
            names.push_back(prefix(id) + "." + side);
        }
        gp_Trsf at;
        at.SetTranslation(gp_Vec(u, v, 0));
        NamedShape out;
        out.shape = shape.Moved(TopLoc_Location(placeOn(plane) * at));
        size_t i = 0;
        for (TopExp_Explorer f(out.shape, TopAbs_FACE); f.More(); f.Next()) out.names.Bind(f.Current(), names[i++]);
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The shape couldn't be made");
    }
}

std::array<double, 6> bounds(const NamedShape& body) {
    Bnd_Box box;
    BRepBndLib::Add(body.shape, box);
    if (box.IsVoid()) return {0, 0, 0, 0, 0, 0};
    double x0, y0, z0, x1, y1, z1;
    box.Get(x0, y0, z0, x1, y1, z1);
    return {x0, y0, z0, x1, y1, z1};
}

std::vector<SketchCurve> curvesOnPlane(const TopoDS_Shape& edges, const gp_Ax3& plane) {
    gp_Trsf toPlane;
    toPlane.SetTransformation(plane);  // World to the plane's own coordinates.
    std::vector<SketchCurve> out;
    auto line = [&](const gp_Pnt& a, const gp_Pnt& b) {
        if (a.Distance(b) < 1e-7) return;
        SketchCurve c;
        c.kind = SketchCurve::Line; c.x1 = a.X(); c.y1 = a.Y(); c.x2 = b.X(); c.y2 = b.Y();
        out.push_back(c);
    };
    for (TopExp_Explorer e(edges, TopAbs_EDGE); e.More(); e.Next()) {
        const TopoDS_Edge& edge = TopoDS::Edge(e.Current());
        if (BRep_Tool::Degenerated(edge)) continue;
        BRepAdaptor_Curve curve(edge);
        auto local = [&](double t) { return curve.Value(t).Transformed(toPlane); };
        double t0 = curve.FirstParameter(), t1 = curve.LastParameter();
        if (curve.GetType() == GeomAbs_Line) {
            line(local(t0), local(t1));
        } else if (curve.GetType() == GeomAbs_Circle) {
            gp_Circ circ = curve.Circle();
            gp_Pnt centre = circ.Location().Transformed(toPlane);
            SketchCurve c;
            c.x1 = centre.X(); c.y1 = centre.Y(); c.r = circ.Radius();
            if (std::abs(t1 - t0 - 2 * M_PI) < 1e-9) {
                c.kind = SketchCurve::Circle;
            } else {
                gp_Pnt a = local(t0), b = local(t1), m = local((t0 + t1) / 2);
                double a0 = std::atan2(a.Y() - c.y1, a.X() - c.x1), a1 = std::atan2(b.Y() - c.y1, b.X() - c.x1);
                double am = std::atan2(m.Y() - c.y1, m.X() - c.x1);
                // Arcs go anticlockwise; if the middle isn't on the way round from a to b, swap the ends.
                auto span = [](double from, double to) { double d = to - from; while (d < 0) d += 2 * M_PI; return d; };
                if (span(a0, am) > span(a0, a1)) std::swap(a0, a1);
                c.kind = SketchCurve::Arc; c.a0 = a0; c.a1 = a1;
            }
            out.push_back(c);
        } else {
            const int n = 24;
            std::vector<gp_Pnt> pts;
            for (int i = 0; i <= n; ++i) pts.push_back(local(t0 + (t1 - t0) * i / n));
            // A curve that's straight, as a cylinder's outline seen side on, is one line.
            gp_Vec chord(pts.front(), pts.back());
            bool straight = chord.Magnitude() > 1e-7;
            if (straight) {
                gp_Dir u(chord);
                for (const auto& p : pts) {
                    if (gp_Vec(pts.front(), p).Crossed(gp_Vec(u)).Magnitude() > 1e-6 * std::max(1.0, chord.Magnitude())) { straight = false; break; }
                }
            }
            if (straight) line(pts.front(), pts.back());
            else for (int i = 0; i < n; ++i) line(pts[i], pts[i + 1]);
        }
    }
    return out;
}

std::vector<SketchCurve> section(const NamedShape& body, const gp_Ax3& plane) {
    try {
        BRepAlgoAPI_Section cut(body.shape, gp_Pln(plane));
        if (!cut.IsDone()) return {};
        return curvesOnPlane(cut.Shape(), plane);
    } catch (const Standard_Failure&) {
        return {};
    }
}

NamedShape meshToSolid(int id, const Mesh& mesh) {
    try {
        BRepBuilderAPI_Sewing sew(1e-4);
        for (const auto& t : mesh.triangles) {
            gp_Pnt p[3];
            for (int k = 0; k < 3; ++k) p[k] = gp_Pnt(mesh.vertices[t[k]][0], mesh.vertices[t[k]][1], mesh.vertices[t[k]][2]);
            if (p[0].Distance(p[1]) < 1e-9 || p[1].Distance(p[2]) < 1e-9 || p[0].Distance(p[2]) < 1e-9) continue;
            BRepBuilderAPI_MakePolygon poly(p[0], p[1], p[2], true);
            BRepBuilderAPI_MakeFace face(poly.Wire(), true);
            if (face.IsDone()) sew.Add(face.Face());
        }
        sew.Perform();
        TopoDS_Shape sewn = sew.SewedShape();
        TopoDS_Shell shell;
        for (TopExp_Explorer s(sewn, TopAbs_SHELL); s.More(); s.Next()) { shell = TopoDS::Shell(s.Current()); break; }
        if (shell.IsNull()) throw std::runtime_error("The mesh doesn't close up into a solid");
        BRepBuilderAPI_MakeSolid solid(shell);
        if (!solid.IsDone()) throw std::runtime_error("The mesh doesn't close up into a solid");
        // Merge flat neighbours into single faces.
        ShapeUpgrade_UnifySameDomain unify(solid.Solid(), true, true, true);
        unify.Build();
        TopoDS_Shape result = unify.Shape();
        BRepClass3d_SolidClassifier inside(result);
        inside.PerformInfinitePoint(1e-6);
        if (inside.State() == TopAbs_IN) result.Reverse();  // It faced inwards.
        if (!BRepCheck_Analyzer(result).IsValid()) throw std::runtime_error("The mesh doesn't close up into a solid");
        NamedShape out;
        out.shape = result;
        int k = 0;
        for (TopExp_Explorer f(result, TopAbs_FACE); f.More(); f.Next())
            if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + ".i" + std::to_string(k++));
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The mesh couldn't be made into a solid");
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
        Bnd_Box ba, bb;
        BRepBndLib::Add(a.shape, ba);
        BRepBndLib::Add(b.shape, bb);
        if (ba.IsOut(bb)) return false;
        BRepAlgoAPI_Common common(a.shape, b.shape);
        if (common.IsDone() && volume(common.Shape()) > 1e-9) return true;
        // Touching: a post standing on a floor. Joined over a face they make one solid; over an edge or a corner, two.
        BRepExtrema_DistShapeShape gap(a.shape, b.shape);
        if (!gap.IsDone() || gap.Value() > 1e-6) return false;
        BRepAlgoAPI_Fuse fuse(a.shape, b.shape);
        if (!fuse.IsDone()) return false;
        int solids = 0;
        for (TopExp_Explorer e(fuse.Shape(), TopAbs_SOLID); e.More(); e.Next()) solids++;
        return solids == 1;
    } catch (const Standard_Failure&) {
        return false;
    }
}

// Sweeps, pipes, coils, threads and lofts.

namespace {

/** Edges joined into one wire, in order. */
TopoDS_Wire chain(const std::vector<TopoDS_Edge>& edges) {
    if (edges.empty()) throw std::runtime_error("The path is empty");
    Handle(TopTools_HSequenceOfShape) in = new TopTools_HSequenceOfShape;
    for (const auto& e : edges) in->Append(e);
    Handle(TopTools_HSequenceOfShape) wires;
    ShapeAnalysis_FreeBounds::ConnectEdgesToWires(in, 1e-4, false, wires);
    if (wires.IsNull() || wires->Length() != 1) throw std::runtime_error("The path has gaps or branches");
    return TopoDS::Wire(wires->Value(1));
}

/** Where a path starts and which way it heads there. */
std::pair<gp_Pnt, gp_Dir> pathStart(const TopoDS_Wire& path) {
    BRepAdaptor_CompCurve c(path);
    gp_Pnt p;
    gp_Vec t;
    c.D1(c.FirstParameter(), p, t);
    if (t.Magnitude() < 1e-12) throw std::runtime_error("The path has no length");
    return {p, gp_Dir(t)};
}

/** A helix round the axis through (0, 0) along z of the frame, of radius r, rising pitch a turn, from height z0 for turns turns. */
TopoDS_Wire helix(const gp_Ax3& frame, double r, double pitch, double turns, double z0) {
    Handle(Geom_CylindricalSurface) cylinder = new Geom_CylindricalSurface(frame, r);
    gp_Lin2d line(gp_Pnt2d(0, z0), gp_Dir2d(2 * M_PI, pitch));
    double length = turns * std::hypot(2 * M_PI, pitch);
    // A turn per edge: a thread cuts in about two thirds of the time it takes along one long edge.
    Handle(Geom2d_Line) carrier = new Geom2d_Line(line);
    const int pieces = std::max(1, int(std::ceil(turns)));
    BRepBuilderAPI_MakeWire wire;
    for (int i = 0; i < pieces; ++i) {
        TopoDS_Edge edge = BRepBuilderAPI_MakeEdge(carrier, cylinder, length * i / pieces, length * (i + 1) / pieces);
        BRepLib::BuildCurves3d(edge);
        wire.Add(edge);
    }
    return wire.Wire();
}

/** A closed polygon as a wire. */
TopoDS_Wire polygon(const std::vector<gp_Pnt>& points) {
    BRepBuilderAPI_MakePolygon p;
    for (const auto& q : points) p.Add(q);
    p.Close();
    return p.Wire();
}

/**
 * A profile swept along a helix, keeping its slant to the axis, as a coil's
 * wire or a thread's groove is.
 */
TopoDS_Shape alongHelix(const TopoDS_Wire& path, const TopoDS_Wire& profile, const gp_Dir& axis) {
    BRepOffsetAPI_MakePipeShell shell(path);
    shell.SetMode(axis);  // The binormal stays along the axis.
    shell.Add(profile, false, false);
    shell.Build();
    if (!shell.IsDone() || !shell.MakeSolid()) throw std::runtime_error("The coil couldn't be made");
    return shell.Shape();
}

/** Names every face of a new shape: the first shapes' faces, the last's, and the rest by [rest]. */
NamedShape nameAll(int id, const TopoDS_Shape& shape, const TopoDS_Shape& first, const TopoDS_Shape& last, const std::string& rest) {
    NamedShape out;
    out.shape = shape;
    if (!first.IsNull())
        for (TopExp_Explorer f(first, TopAbs_FACE); f.More(); f.Next()) out.names.Bind(f.Current(), prefix(id) + ".start");
    if (!last.IsNull())
        for (TopExp_Explorer f(last, TopAbs_FACE); f.More(); f.Next()) out.names.Bind(f.Current(), prefix(id) + ".end");
    int k = 0;
    for (TopExp_Explorer f(shape, TopAbs_FACE); f.More(); f.Next())
        if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + "." + rest + std::to_string(k++));
    return out;
}

}  // namespace

TopoDS_Wire pathFromSketch(const gp_Ax3& plane, const std::vector<SketchCurve>& curves) {
    TopLoc_Location loc(placeOn(plane));
    std::vector<TopoDS_Edge> edges;
    for (const auto& c : curves) {
        TopoDS_Edge e = sketchEdge(c);
        if (!e.IsNull()) edges.push_back(TopoDS::Edge(e.Moved(loc)));
    }
    return chain(edges);
}

TopoDS_Wire pathFromEdges(const std::vector<TopoDS_Edge>& edges) { return chain(edges); }

/** An open line in a sketch made into a wall [thin] thick, centred on the line with round ends, and extruded. */
NamedShape openWall(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, double forward, double back, double thin) {
    try {
        std::vector<TopoDS_Edge> edges;
        for (const auto& c : curves) {
            TopoDS_Edge e = sketchEdge(c);
            if (!e.IsNull()) edges.push_back(e);
        }
        TopoDS_Wire path = chain(edges);
        if (path.Closed()) throw std::runtime_error("Tap the areas to extrude");
        // On the sketch's plane given outright: a straight line alone doesn't fix one.
        BRepOffsetAPI_MakeOffset offset;
        offset.Init(BRepBuilderAPI_MakeFace(gp_Pln(gp::XOY())).Face(), GeomAbs_Arc, false);
        offset.AddWire(path);
        offset.Perform(thin / 2);
        if (!offset.IsDone()) throw std::runtime_error("The wall couldn't be laid out along the line");
        TopoDS_Wire outline;
        for (TopExp_Explorer w(offset.Shape(), TopAbs_WIRE); w.More() && outline.IsNull(); w.Next()) outline = TopoDS::Wire(w.Current());
        if (outline.IsNull()) throw std::runtime_error("The wall couldn't be laid out along the line");
        BRepBuilderAPI_MakeFace face(gp_Pln(gp::XOY()), outline);
        if (!face.IsDone()) throw std::runtime_error("The wall couldn't be laid out along the line");
        gp_Trsf shift;
        shift.SetTranslation(gp_Vec(plane.Direction()) * -back);
        TopoDS_Face placed = TopoDS::Face(face.Face().Moved(TopLoc_Location(shift * placeOn(plane))));
        BRepPrimAPI_MakePrism prism(placed, gp_Vec(plane.Direction()) * (forward + back));
        if (!prism.IsDone()) throw std::runtime_error("The extrude couldn't be made");
        NamedShape out = nameAll(id, prism.Shape(), prism.FirstShape(), prism.LastShape(), "w");
        check(out.shape, "The extrude couldn't be made");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The wall couldn't be laid out along the line");
    }
}

NamedShape sweep(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                 const TopoDS_Wire& path) {
    try {
        auto regions = buildRegionFaces(curves);
        TopLoc_Location loc(placeOn(plane));
        std::vector<NamedShape> pieces;
        for (const RegionFace* r : choose(regions, picks)) {
            TopoDS_Face face = TopoDS::Face(r->face.Moved(loc));
            BRepOffsetAPI_MakePipe pipe(path, face);
            pipe.Build();
            if (!pipe.IsDone()) throw std::runtime_error("The sweep couldn't be made");
            pieces.push_back(nameSweep(id, pipe, *r, loc));
        }
        NamedShape out = fuseAll(id, std::move(pieces));
        check(out.shape, "The sweep crosses itself");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The sweep couldn't be made");
    }
}

NamedShape pipe(int id, const TopoDS_Wire& path, double diameter, double inner) {
    if (diameter <= 0) throw std::runtime_error("The pipe has to be wider than 0");
    if (inner >= diameter) throw std::runtime_error("The hole has to be narrower than the pipe");
    try {
        auto [start, heading] = pathStart(path);
        gp_Ax2 at(start, heading);
        BRepBuilderAPI_MakeFace ring(BRepBuilderAPI_MakeWire(BRepBuilderAPI_MakeEdge(gp_Circ(at, diameter / 2))).Wire());
        if (inner > 0) {
            TopoDS_Wire hole = BRepBuilderAPI_MakeWire(BRepBuilderAPI_MakeEdge(gp_Circ(at, inner / 2))).Wire();
            hole.Reverse();
            ring.Add(hole);
        }
        BRepOffsetAPI_MakePipe tube(path, ring.Face());
        tube.Build();
        if (!tube.IsDone()) throw std::runtime_error("The pipe couldn't be made");
        NamedShape out = nameAll(id, tube.Shape(), tube.FirstShape(), tube.LastShape(), "p");
        check(out.shape, "The pipe crosses itself");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The pipe couldn't be made");
    }
}

NamedShape coil(int id, const gp_Ax3& plane, double u, double v, double diameter, double pitch, double turns, double section, bool square) {
    if (diameter <= 0 || section <= 0) throw std::runtime_error("Sizes have to be more than 0");
    if (turns <= 0) throw std::runtime_error("It needs more than 0 turns");
    if (section >= diameter) throw std::runtime_error("The wire has to be narrower than the coil");
    if (pitch < section) throw std::runtime_error("The turns would run into each other: make the pitch at least the wire's size");
    try {
        gp_Trsf at;
        at.SetTranslation(gp_Vec(u, v, 0));
        gp_Trsf place = placeOn(plane) * at;
        gp_Ax3 frame(gp_Pnt(0, 0, 0).Transformed(place), gp::DZ().Transformed(place), gp::DX().Transformed(place));
        // A helix lying exactly on a cylinder's face makes booleans with it fail, as with a groove cut
        // round a knob at the knob's size, so it sits a hair outside.
        const double r = diameter / 2 + 1e-3;
        // The wire's section, starting at the helix's start (r along x, at the height of half the wire),
        // in the plane through the axis.
        const double z0 = section / 2;
        TopoDS_Wire path = helix(frame, r, pitch, turns, z0);
        gp_Pnt middle = gp_Pnt(r, 0, z0).Transformed(place);
        gp_Dir out = gp::DX().Transformed(place), up = gp::DZ().Transformed(place);
        TopoDS_Wire profile;
        if (square) {
            double h = section / 2;
            auto p = [&](double a, double b) { return middle.Translated(gp_Vec(out) * a + gp_Vec(up) * b); };
            profile = polygon({p(-h, -h), p(h, -h), p(h, h), p(-h, h)});
        } else {
            profile = BRepBuilderAPI_MakeWire(BRepBuilderAPI_MakeEdge(gp_Circ(gp_Ax2(middle, out.Crossed(up)), section / 2)));
        }
        NamedShape result = nameAll(id, alongHelix(path, profile, up), TopoDS_Shape(), TopoDS_Shape(), "c");
        return result;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The coil couldn't be made");
    }
}

NamedShape rib(int id, const NamedShape& body, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, double thickness, bool flip, bool web, bool retried) {
    if (thickness <= 0) throw std::runtime_error("The thickness has to be more than 0");
    TopoDS_Wire wire = pathFromSketch(plane, curves);
    if (wire.Closed()) throw std::runtime_error("Draw an open line, not a closed shape");
    try {
        // Far enough to cross the whole body from anywhere on the curves.
        Bnd_Box box;
        BRepBndLib::Add(body.shape, box);
        BRepBndLib::Add(wire, box);
        const double far = std::sqrt(box.SquareExtent()) * 2 + 10;
        const gp_Dir n = plane.Direction();
        TopoDS_Vertex v0, v1;
        TopExp::Vertices(wire, v0, v1);
        const gp_Pnt start = BRep_Tool::Pnt(v0), end = BRep_Tool::Pnt(v1);
        gp_Dir grow;
        TopoDS_Shape slab;
        if (!web) {
            gp_Vec run(start, end);
            if (run.Magnitude() < 1e-9) throw std::runtime_error("The line's ends meet; draw an open line");
            grow = gp_Dir(gp_Vec(n).Crossed(run));
            if (flip) grow.Reverse();
            // The curves swept across the plane make a flat face, thickened either side of the plane.
            gp_Trsf out;
            out.SetTranslation(gp_Vec(grow) * far);
            TopoDS_Wire moved = TopoDS::Wire(wire.Moved(TopLoc_Location(out)));
            BRepBuilderAPI_MakeWire outline;
            outline.Add(wire);
            outline.Add(BRepBuilderAPI_MakeEdge(end, end.Translated(gp_Vec(grow) * far)).Edge());
            outline.Add(TopoDS::Wire(moved.Reversed()));
            outline.Add(BRepBuilderAPI_MakeEdge(start.Translated(gp_Vec(grow) * far), start).Edge());
            if (!outline.IsDone()) throw std::runtime_error("The rib couldn't be laid out");
            BRepBuilderAPI_MakeFace face(gp_Pln(plane), outline.Wire());
            if (!face.IsDone()) throw std::runtime_error("The rib couldn't be laid out");
            gp_Trsf down;
            down.SetTranslation(gp_Vec(n) * (-thickness / 2));
            slab = BRepPrimAPI_MakePrism(face.Face().Moved(TopLoc_Location(down)), gp_Vec(n) * thickness).Shape();
        } else {
            // The curves grown straight out of the plane into a sheet, thickened to either side.
            grow = flip ? n.Reversed() : n;
            TopoDS_Shape sheet = BRepPrimAPI_MakePrism(wire, gp_Vec(grow) * far).Shape();
            BRepAlgoAPI_Fuse both;
            TopTools_ListOfShape sides, tools;
            for (double side : {thickness / 2, -thickness / 2}) {
                BRepOffsetAPI_MakeThickSolid thick;
                thick.MakeThickSolidBySimple(sheet, side);
                if (!thick.IsDone()) throw std::runtime_error("The web couldn't be laid out");
                // The negative side comes out inside out.
                TopoDS_Shape made = thick.Shape();
                for (TopExp_Explorer so(made, TopAbs_SOLID); so.More(); so.Next()) {
                    TopoDS_Solid solid = TopoDS::Solid(so.Current());
                    BRepLib::OrientClosedSolid(solid);
                    (sides.IsEmpty() ? sides : tools).Append(solid);
                }
            }
            both.SetArguments(sides);
            both.SetTools(tools);
            both.Build();
            if (!both.IsDone()) throw std::runtime_error("The web couldn't be laid out");
            ShapeUpgrade_UnifySameDomain tidy(both.Shape());
            tidy.Build();
            slab = tidy.Shape();
        }
        // What's outside the body, from the curves up to where it meets the body.
        BRepAlgoAPI_Cut outside(slab, body.shape);
        outside.SetRunParallel(useCores());
        outside.Build();
        if (!outside.IsDone()) throw std::runtime_error("The rib couldn't be fitted to the body");
        std::vector<TopoDS_Shape> kept;
        bool tooFar = false;
        for (TopExp_Explorer s(outside.Shape(), TopAbs_SOLID); s.More(); s.Next()) {
            BRepExtrema_DistShapeShape gap(s.Current(), wire);
            if (!gap.IsDone() || gap.Value() > 1e-4) continue;
            Bnd_Box piece;
            BRepBndLib::Add(s.Current(), piece);
            double lo[3], hi[3];
            piece.Get(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
            double reach = 0;
            for (int i = 0; i < 8; ++i) {
                gp_Pnt c(i & 1 ? hi[0] : lo[0], i & 2 ? hi[1] : lo[1], i & 4 ? hi[2] : lo[2]);
                double along = 0;
                for (TopExp_Explorer v(wire, TopAbs_VERTEX); v.More(); v.Next())
                    along = std::max(along, gp_Vec(BRep_Tool::Pnt(TopoDS::Vertex(v.Current())), c).Dot(gp_Vec(grow)));
                reach = std::max(reach, along);
            }
            if (reach > far * 0.9) tooFar = true;
            else kept.push_back(s.Current());
        }
        // Grown away from the body, it tries the other side.
        if (kept.empty() && tooFar && !retried) return rib(id, body, plane, curves, thickness, !flip, web, true);
        if (kept.empty()) throw std::runtime_error(tooFar ? "It doesn't meet the body that way" : "It doesn't touch the body");
        NamedShape out = body;
        for (size_t i = 0; i < kept.size(); ++i)
            out = combine(id, out, nameAll(id, kept[i], TopoDS_Shape(), TopoDS_Shape(), i == 0 ? "w" : "w" + std::to_string(i) + "."), Combine::Join);
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The rib couldn't be made");
    }
}

NamedShape emboss(int id, const NamedShape& body, const std::string& face, const gp_Ax3& plane, const std::vector<SketchCurve>& curves,
                  const std::vector<RegionPick>& picks, double depth, bool sink) {
    if (depth <= 0) throw std::runtime_error("The depth has to be more than 0");
    auto found = body.findFaces(face);
    if (found.empty()) throw std::runtime_error("The face it's on isn't there any more");
    try {
        Bnd_Box box;
        BRepBndLib::Add(body.shape, box);
        const double far = std::sqrt(box.SquareExtent()) * 2 + 10;
        const gp_Vec n(plane.Direction());
        // The layer the depth makes over (or under) the face.
        BRep_Builder b;
        BRepOffsetAPI_MakeThickSolid thick;
        thick.MakeThickSolidBySimple(found[0], sink ? -depth : depth);
        if (!thick.IsDone()) throw std::runtime_error("The face can't take that depth");
        TopoDS_Compound layer;
        b.MakeCompound(layer);
        for (TopExp_Explorer s(thick.Shape(), TopAbs_SOLID); s.More(); s.Next()) {
            TopoDS_Solid solid = TopoDS::Solid(s.Current());
            BRepLib::OrientClosedSolid(solid);
            b.Add(layer, solid);
        }
        // Each area as a column right through, along the sketch's normal, where it
        // crosses the layer; only the nearest crossing on the side the sketch faces,
        // or if there's none that side, the nearest behind it.
        auto regions = buildRegionFaces(curves);
        TopLoc_Location loc(placeOn(plane));
        gp_Trsf back;
        back.SetTranslation(n * -far);
        TopoDS_Compound pieces;
        b.MakeCompound(pieces);
        int count = 0;
        for (const RegionFace* r : choose(regions, picks)) {
            ++count;
            TopoDS_Shape start = r->face.Moved(loc).Moved(TopLoc_Location(back));
            BRepAlgoAPI_Common where(BRepPrimAPI_MakePrism(start, n * (2 * far)).Shape(), layer);
            where.SetRunParallel(useCores());
            where.Build();
            if (!where.IsDone()) throw std::runtime_error("The areas couldn't be laid on the face");
            TopoDS_Shape nearest;
            double best = 1e300;
            for (TopExp_Explorer s(where.Shape(), TopAbs_SOLID); s.More(); s.Next()) {
                GProp_GProps g;
                BRepGProp::VolumeProperties(s.Current(), g);
                double along = gp_Vec(plane.Location(), g.CentreOfMass()).Dot(n);
                double d = along >= 0 ? along : far - along;
                if (d < best) { best = d; nearest = s.Current(); }
            }
            if (!nearest.IsNull()) b.Add(pieces, nearest);
        }
        if (count == 0) throw std::runtime_error("Pick an area of the sketch");
        TopoDS_Shape piece = pieces;
        if (volume(piece) < 1e-9) throw std::runtime_error("The areas don't land on that face");
        return combine(id, body, nameAll(id, piece, TopoDS_Shape(), TopoDS_Shape(), "e"), sink ? Combine::Cut : Combine::Join);
    } catch (const Standard_Failure&) {
        throw std::runtime_error("It couldn't be embossed");
    }
}

NamedShape patch(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks) {
    try {
        auto regions = buildRegionFaces(curves);
        TopLoc_Location loc(placeOn(plane));
        BRep_Builder b;
        TopoDS_Compound all;
        b.MakeCompound(all);
        int n = 0;
        for (const RegionFace* r : choose(regions, picks)) {
            b.Add(all, r->face.Moved(loc));
            ++n;
        }
        if (n == 0) throw std::runtime_error("Pick an area of the sketch");
        TopoDS_Shape shape = all;
        if (n > 1) {
            BRepBuilderAPI_Sewing sew;
            sew.Add(all);
            sew.Perform();
            shape = sew.SewedShape();
        } else {
            shape = TopExp_Explorer(all, TopAbs_FACE).Current();
        }
        return nameAll(id, shape, TopoDS_Shape(), TopoDS_Shape(), "a");
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The patch couldn't be made");
    }
}

NamedShape patchEdges(int id, const NamedShape& body, const std::vector<std::string>& edges) {
    if (edges.size() < 2) throw std::runtime_error("Pick the edges round the gap");
    try {
        BRepOffsetAPI_MakeFilling fill;
        for (const auto& name : edges) {
            auto found = body.findEdges(name);
            if (found.empty()) throw std::runtime_error("An edge of it isn't there any more");
            for (const auto& e : found) fill.Add(e, GeomAbs_C0);
        }
        fill.Build();
        if (!fill.IsDone()) throw std::runtime_error("Those edges don't close round a gap");
        NamedShape out = nameAll(id, fill.Shape(), TopoDS_Shape(), TopoDS_Shape(), "a");
        check(out.shape, "The patch couldn't be made");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The patch couldn't be made");
    }
}

NamedShape gather(const std::vector<NamedShape>& parts) {
    BRep_Builder b;
    TopoDS_Compound all;
    b.MakeCompound(all);
    NamedShape out;
    for (const auto& p : parts) {
        b.Add(all, p.shape);
        for (NCollection_DataMap<TopoDS_Shape, std::string, TopTools_ShapeMapHasher>::Iterator it(p.names); it.More(); it.Next())
            out.names.Bind(it.Key(), it.Value());
    }
    out.shape = all;
    return out;
}

NamedShape stitch(int id, const std::vector<NamedShape>& parts) {
    if (parts.size() < 2) throw std::runtime_error("Pick at least two surfaces");
    try {
        BRepBuilderAPI_Sewing sew(1e-3);
        for (const auto& p : parts) sew.Add(p.shape);
        sew.Perform();
        TopoDS_Shape sewn = sew.SewedShape();
        // Closed all round, it's a solid.
        TopoDS_Shape shape = sewn;
        if (sewn.ShapeType() == TopAbs_SHELL && BRep_Tool::IsClosed(sewn)) {
            BRepBuilderAPI_MakeSolid solid(TopoDS::Shell(sewn));
            if (solid.IsDone()) {
                TopoDS_Solid s = TopoDS::Solid(solid.Shape());
                BRepLib::OrientClosedSolid(s);
                shape = s;
            }
        }
        NamedShape out;
        out.shape = shape;
        // Each face by the name it had, through the sewing's changes.
        for (const auto& p : parts)
            for (TopExp_Explorer f(p.shape, TopAbs_FACE); f.More(); f.Next()) {
                if (!p.names.IsBound(f.Current())) continue;
                TopoDS_Shape now = sew.IsModified(f.Current()) ? sew.Modified(f.Current()) : f.Current();
                for (TopExp_Explorer g(now, TopAbs_FACE); g.More(); g.Next())
                    for (TopExp_Explorer h(shape, TopAbs_FACE); h.More(); h.Next())
                        if (h.Current().IsSame(g.Current()) && !out.names.IsBound(h.Current())) out.names.Bind(h.Current(), p.names.Find(f.Current()));
            }
        int k = 0;
        for (TopExp_Explorer f(shape, TopAbs_FACE); f.More(); f.Next())
            if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + ".a" + std::to_string(k++));
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The surfaces couldn't be stitched");
    }
}

NamedShape thicken(int id, const NamedShape& surface, double thickness, bool both) {
    if (thickness <= 0) throw std::runtime_error("The thickness has to be more than 0");
    try {
        auto thick = [&](double by) {
            BRepOffsetAPI_MakeThickSolid t;
            t.MakeThickSolidBySimple(surface.shape, by);
            if (!t.IsDone()) throw std::runtime_error("It can't be made that thick");
            TopoDS_Shape made = t.Shape();
            // A negative offset comes out inside out.
            BRep_Builder b;
            TopoDS_Compound fixed;
            b.MakeCompound(fixed);
            int n = 0;
            for (TopExp_Explorer s(made, TopAbs_SOLID); s.More(); s.Next()) {
                TopoDS_Solid solid = TopoDS::Solid(s.Current());
                BRepLib::OrientClosedSolid(solid);
                b.Add(fixed, solid);
                ++n;
            }
            if (n == 0) throw std::runtime_error("It can't be made that thick");
            return n == 1 ? TopoDS_Shape(TopExp_Explorer(fixed, TopAbs_SOLID).Current()) : TopoDS_Shape(fixed);
        };
        TopoDS_Shape shape;
        if (both) {
            BRepAlgoAPI_Fuse fuse(thick(thickness / 2), thick(-thickness / 2));
            fuse.Build();
            if (!fuse.IsDone()) throw std::runtime_error("It can't be made that thick");
            ShapeUpgrade_UnifySameDomain tidy(fuse.Shape());
            tidy.Build();
            shape = tidy.Shape();
        } else {
            shape = thick(thickness);
        }
        NamedShape out = nameAll(id, shape, TopoDS_Shape(), TopoDS_Shape(), "k");
        check(out.shape, "It can't be made that thick");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("It can't be made that thick");
    }
}

NamedShape thread(int id, const NamedShape& body, const std::string& face, double pitch, double clearance) {
    if (pitch <= 0) throw std::runtime_error("The pitch has to be more than 0");
    if (clearance < 0) throw std::runtime_error("The clearance can't be less than 0");
    auto faces = body.findFaces(face);
    if (faces.empty()) throw std::runtime_error("The face isn't there any more");
    try {
        const TopoDS_Face& f = faces[0];
        BRepAdaptor_Surface surface(f);
        if (surface.GetType() != GeomAbs_Cylinder) throw std::runtime_error("Threads go on round faces");
        gp_Cylinder cyl = surface.Cylinder();
        const double r0 = cyl.Radius();
        double u0, u1, v0, v1;
        BRepTools::UVBounds(f, u0, u1, v0, v1);
        // A hole if the face looks in towards the axis.
        gp_Pnt p;
        gp_Vec du, dv;
        surface.D1((u0 + u1) / 2, (v0 + v1) / 2, p, du, dv);
        gp_Vec normal = du.Crossed(dv);
        if (f.Orientation() == TopAbs_REVERSED) normal.Reverse();
        gp_Vec radial(cyl.Axis().Location(), p);
        radial -= gp_Vec(cyl.Axis().Direction()) * radial.Dot(gp_Vec(cyl.Axis().Direction()));
        const bool hole = normal.Dot(radial) < 0;
        const double r = r0;
        if (clearance > 0) {
            // The face moves away from the mating part first, a hole wider or a shaft thinner, keeping its name; then the thread goes on that.
            const double moved = hole ? r0 + clearance : r0 - clearance;
            if (moved <= 0) throw std::runtime_error("The clearance is more than the shaft");
            gp_Ax3 at3(cyl.Axis().Location(), cyl.Axis().Direction(), cyl.XAxis().Direction());
            gp_Ax2 axis(at3.Location().Translated(gp_Vec(at3.Direction()) * v0), at3.Direction(), at3.XDirection());
            const double outer = r0 + 0.2 * pitch;
            TopoDS_Shape ease = hole ? BRepPrimAPI_MakeCylinder(axis, moved, v1 - v0).Shape()
                                     : BRepAlgoAPI_Cut(BRepPrimAPI_MakeCylinder(axis, outer, v1 - v0).Shape(),
                                                       BRepPrimAPI_MakeCylinder(axis, moved, v1 - v0).Shape()).Shape();
            BRepAlgoAPI_Cut cut(body.shape, ease);
            if (!cut.IsDone()) throw std::runtime_error("The thread couldn't be cut");
            NamedShape eased = carryNames({&body}, cut, cut.Shape(), prefix(id));
            NamedShape renamed;
            renamed.shape = eased.shape;
            for (TopExp_Explorer e(eased.shape, TopAbs_FACE); e.More(); e.Next()) {
                BRepAdaptor_Surface s(TopoDS::Face(e.Current()));
                bool isMoved = s.GetType() == GeomAbs_Cylinder && std::abs(s.Cylinder().Radius() - moved) < 1e-6;
                renamed.names.Bind(e.Current(), isMoved ? face : eased.faceName(e.Current()));
            }
            return thread(id, renamed, face, pitch, 0);
        }
        // ISO metric basic profile, H the 60° triangle's height. A shaft's groove is 7/8 of a pitch wide at the shaft
        // and goes in 17H/24; a hole's (made at the nut size) is 3/4 of a pitch wide at the hole and goes out 5H/8
        // to the bolt's size, so the two mesh exactly. Each starts a little outside the face so the cut is clean.
        const double H = pitch * std::sqrt(3.0) / 2;
        const double depth = hole ? 5 * H / 8 : 17 * H / 24;
        const double over = 0.1 * pitch;
        const double flank = std::tan(M_PI / 6);
        const double apex = hole ? r + 3 * H / 4 : r - 7 * H / 8;
        auto halfAt = [&](double radius) { return flank * std::abs(apex - radius); };
        // Laid out the same way for every face on the same axis, whichever way the face runs, so threads are all
        // right-handed, and a hole's thread is half a turn round from a shaft's, its ridges in the shaft's grooves.
        gp_Dir dir = cyl.Axis().Direction();
        if (dir.XYZ().Dot(gp_XYZ(0.3, 0.5, 0.81)) < 0) dir.Reverse();
        const gp_Pnt loc = cyl.Axis().Location();
        const gp_Pnt origin = loc.Translated(gp_Vec(dir) * gp_Vec(loc, gp::Origin()).Dot(gp_Vec(dir)));
        auto height = [&](double v) { return gp_Vec(origin, loc.Translated(gp_Vec(cyl.Axis().Direction()) * v)).Dot(gp_Vec(dir)); };
        const double lo = std::min(height(v0), height(v1)), hi = std::max(height(v0), height(v1));
        gp_Vec across = gp_Vec(gp::DX()) - gp_Vec(dir) * gp_Vec(gp::DX()).Dot(gp_Vec(dir));
        if (across.Magnitude() < 0.1) across = gp_Vec(gp::DY()) - gp_Vec(dir) * gp_Vec(gp::DY()).Dot(gp_Vec(dir));
        const double phase = 2 * M_PI * lo / pitch + (hole ? M_PI : 0);
        gp_Ax3 frame(origin, dir, gp_Dir(across.Rotated(gp_Ax1(origin, dir), phase)));
        const double turns = (hi - lo) / pitch;
        if (turns < 0.5) throw std::runtime_error("The face is too short for that pitch");
        // A pitch past each end, then trimmed to the face, so the groove runs the face's full height all round.
        TopoDS_Wire path = helix(frame, r, pitch, turns + 2, lo - pitch);
        gp_Pnt base = origin.Translated(gp_Vec(dir) * (lo - pitch));
        gp_Vec out(frame.XDirection()), up(frame.Direction());
        auto at = [&](double radius, double height) { return base.Translated(out * radius + up * height); };
        // Point in from the surface, or out for a hole; the wide end just outside the material.
        const double rimR = hole ? r - over : r + over, rootR = hole ? r + depth : r - depth;
        TopoDS_Wire groove = polygon({at(rimR, -halfAt(rimR)), at(rimR, halfAt(rimR)), at(rootR, halfAt(rootR)), at(rootR, -halfAt(rootR))});
        TopoDS_Shape swept = alongHelix(path, groove, frame.Direction());
        gp_Ax2 band(origin.Translated(gp_Vec(dir) * lo), dir);
        TopoDS_Shape cutter = swept;
        // Trimmed only where there's material just past an end, as a shoulder under a neck; a free end needs none.
        bool next = false;
        const double mid = hole ? r + depth / 2 : r - depth / 2;
        for (double h : {lo - 0.05, hi + 0.05})
            for (int i = 0; i < 8 && !next; ++i) {
                gp_Pnt p = origin.Translated(gp_Vec(dir) * h + gp_Vec(across.Normalized()).Rotated(gp_Ax1(gp::Origin(), dir), i * M_PI / 4) * mid);
                BRepClass3d_SolidClassifier inside(body.shape, p, 1e-6);
                next = inside.State() == TopAbs_IN;
            }
        if (next) {
            BRepAlgoAPI_Common trimmed(swept, BRepPrimAPI_MakeCylinder(band, r + depth + over + pitch, hi - lo).Shape());
            if (!trimmed.IsDone()) throw std::runtime_error("The thread couldn't be cut");
            cutter = trimmed.Shape();
        }
        BRepAlgoAPI_Cut cut;
        TopTools_ListOfShape args, tools;
        args.Append(body.shape);
        tools.Append(cutter);

        cut.SetArguments(args);
        cut.SetTools(tools);
        cut.SetRunParallel(useCores());
        cut.Build();
        if (!cut.IsDone()) throw std::runtime_error("The thread couldn't be cut");
        NamedShape out2 = carryNames({&body}, cut, cut.Shape(), prefix(id));
        // The groove's faces, which carryNames numbered, all go by one name.
        NamedShape named;
        named.shape = out2.shape;
        int k = 0;
        for (TopExp_Explorer e(out2.shape, TopAbs_FACE); e.More(); e.Next()) {
            std::string n = out2.faceName(e.Current());
            bool fresh = n.rfind(prefix(id) + ".", 0) == 0;
            named.names.Bind(e.Current(), fresh ? prefix(id) + ".t" + std::to_string(k++) : n);
        }
        check(named.shape, "The thread couldn't be cut");
        return named;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The thread couldn't be cut");
    }
}

NamedShape loft(int id, const std::vector<LoftProfile>& profiles, bool ruled, double twist, const TopoDS_Wire* guide) {
    if (profiles.size() < 2) throw std::runtime_error("Pick areas in at least two sketches");
    try {
        // Each area's outline where it is, turned its share of the twist about its middle.
        std::vector<TopoDS_Wire> wires;
        std::vector<gp_Pnt> middles;
        std::vector<std::pair<TopoDS_Edge, int>> firstEdges;
        for (size_t i = 0; i < profiles.size(); ++i) {
            const auto& pr = profiles[i];
            auto regions = buildRegionFaces(pr.curves);
            const RegionFace* r = choose(regions, {pr.pick})[0];
            TopLoc_Location loc(placeOn(pr.plane));
            TopoDS_Face face = TopoDS::Face(r->face.Moved(loc));
            GProp_GProps area;
            BRepGProp::SurfaceProperties(face, area);
            gp_Pnt middle = area.CentreOfMass();
            gp_Trsf turn;
            double share = twist * double(i) / double(profiles.size() - 1);
            if (std::abs(share) > 1e-12) turn.SetRotation(gp_Ax1(middle, pr.plane.Direction()), share);
            TopLoc_Location turned(turn);
            wires.push_back(TopoDS::Wire(BRepTools::OuterWire(face).Moved(turned)));
            middles.push_back(middle);
            if (i == 0) for (const auto& [e, c] : r->edgeCurves) firstEdges.push_back({TopoDS::Edge(e.Moved(loc).Moved(turned)), c});
        }
        NamedShape out;
        if (guide) {
            // Along a path through the middles: straight between two, a smooth curve through more.
            BRepBuilderAPI_MakeWire spine;
            if (middles.size() == 2) {
                spine.Add(BRepBuilderAPI_MakeEdge(middles[0], middles[1]).Edge());
            } else {
                Handle(TColgp_HArray1OfPnt) pts = new TColgp_HArray1OfPnt(1, int(middles.size()));
                for (size_t i = 0; i < middles.size(); ++i) pts->SetValue(int(i) + 1, middles[i]);
                GeomAPI_Interpolate through(pts, false, Precision::Confusion());
                through.Perform();
                if (!through.IsDone()) throw std::runtime_error("The loft couldn't be made");
                spine.Add(BRepBuilderAPI_MakeEdge(through.Curve()).Edge());
            }
            GProp_GProps along;
            BRepGProp::LinearProperties(spine.Wire(), along);
            double spineLength = along.Mass();
            auto middleOf = [](const TopoDS_Wire& w) {
                BRepAdaptor_CompCurve c(w);
                return c.Value((c.FirstParameter() + c.LastParameter()) / 2);
            };
            // Each outline's edges in order, with curves in space, not only on their sketch's plane.
            std::vector<std::vector<TopoDS_Edge>> edges;
            size_t most = 1;
            for (const auto& w : wires) {
                TopoDS_Wire copy = TopoDS::Wire(BRepBuilderAPI_Copy(w).Shape());
                BRepLib::BuildCurves3d(copy);
                std::vector<TopoDS_Edge> list;
                for (BRepTools_WireExplorer e(copy); e.More(); e.Next()) list.push_back(e.Current());
                most = std::max(most, list.size());
                edges.push_back(list);
            }
            // OCCT's sweep along a guide builds from some starting points round the outlines and not
            // others, so each is tried, both ways round, until one works.
            std::unique_ptr<BRepOffsetAPI_MakePipeShell> shell;
            for (int way = 0; way < 2 && !shell; ++way)
                for (size_t k = 0; k < most && !shell; ++k) {
                    try {
                        auto attempt = std::make_unique<BRepOffsetAPI_MakePipeShell>(spine.Wire());
                        attempt->SetMode(*guide, true, BRepFill_ContactOnBorder);
                        for (const auto& list : edges) {
                            BRepBuilderAPI_MakeWire w;
                            for (size_t j = 0; j < list.size(); ++j) w.Add(list[(j + k) % list.size()]);
                            TopoDS_Wire outline = w.Wire();
                            attempt->Add(way ? TopoDS::Wire(outline.Reversed()) : outline, false, false);
                        }
                        attempt->Build();
                        if (!attempt->IsDone() || !attempt->MakeSolid()) continue;
                        // Some build without following the guide; the right one has the guide's middle on its surface.
                        BRepExtrema_DistShapeShape gap(BRepBuilderAPI_MakeVertex(middleOf(*guide)).Vertex(), attempt->Shape());
                        if (gap.IsDone() && gap.Value() < 0.01 * std::max(1.0, spineLength)) shell = std::move(attempt);
                    } catch (const Standard_Failure&) {
                    }
                }
            if (!shell) throw std::runtime_error("The loft couldn't follow the guide; it has to run beside the areas from the first to the last");
            NamedShape all = nameAll(id, shell->Shape(), shell->FirstShape(), shell->LastShape(), "n");
            check(all.shape, "The loft crosses itself");
            return all;
        }
        BRepOffsetAPI_ThruSections thru(true, ruled);
        for (const auto& w : wires) thru.AddWire(w);
        thru.CheckCompatibility(true);
        thru.Build();
        if (!thru.IsDone()) throw std::runtime_error("The loft couldn't be made");
        out.shape = thru.Shape();
        // Sides by the curves of the first area they rise from.
        for (const auto& [e, c] : firstEdges) {
            TopoDS_Shape side = thru.GeneratedFace(e);
            if (!side.IsNull() && !out.names.IsBound(side)) out.names.Bind(side, prefix(id) + ".s" + std::to_string(c));
        }
        NamedShape all = nameAll(id, out.shape, thru.FirstShape(), thru.LastShape(), "n");
        for (TopExp_Explorer f(out.shape, TopAbs_FACE); f.More(); f.Next())
            if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), all.faceName(f.Current()));
        check(out.shape, "The loft crosses itself");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The loft couldn't be made");
    }
}

NamedShape gear(int id, const gp_Ax3& plane, double u, double v, double turn, double module, int teeth, double pressureAngle, double thickness,
                double helix, bool herringbone, double bore, double clearance) {
    if (module <= 0 || thickness <= 0) throw std::runtime_error("Sizes have to be more than 0");
    if (teeth < 6 || teeth > 400) throw std::runtime_error("A gear needs 6 to 400 teeth");
    if (pressureAngle < 10 * M_PI / 180 || pressureAngle > 35 * M_PI / 180) throw std::runtime_error("The pressure angle has to be 10° to 35°");
    if (std::abs(helix) > 60 * M_PI / 180) throw std::runtime_error("The helix angle can be at most 60°");
    const double rp = module * teeth / 2, rb = rp * std::cos(pressureAngle);
    const double ra = rp + module, rf = rp - 1.25 * module;
    if (bore < 0 || bore / 2 >= rf - module / 2) throw std::runtime_error("The bore is too big for the gear");
    if (clearance < 0 || clearance >= module) throw std::runtime_error("The clearance has to be less than the module");
    // Half a tooth's angle at radius r: at the pitch circle half of 360° / teeth, less half the clearance,
    // then along the involute.
    auto inv = [](double a) { return std::tan(a) - a; };
    const double halfAtPitch = M_PI / (2 * teeth) - clearance / (2 * rp) + inv(pressureAngle);
    auto half = [&](double r) { return halfAtPitch - inv(std::acos(std::min(1.0, rb / r))); };
    if (half(ra) <= 0) throw std::runtime_error("The teeth come to a point; use less clearance");
    if (2 * half(std::max(rb, rf)) >= 2 * M_PI / teeth) throw std::runtime_error("The teeth are too close; use more of them");
    try {
        auto at = [](double r, double a) { return gp_Pnt(r * std::cos(a), r * std::sin(a), 0); };
        auto line = [](const gp_Pnt& a, const gp_Pnt& b) { return BRepBuilderAPI_MakeEdge(a, b).Edge(); };
        auto arc = [&](double r, double a, double b) {
            return BRepBuilderAPI_MakeEdge(GC_MakeArcOfCircle(at(r, a), at(r, (a + b) / 2), at(r, b)).Value()).Edge();
        };
        // One involute flank, from the base circle (or the root, if that's further out) to the tip, at angle
        // sign * half(r) about the tooth's middle at angle c.
        const double r0 = std::max(rb, rf);
        auto flank = [&](double c, double sign, bool outward) {
            const int n = 9;
            Handle(TColgp_HArray1OfPnt) pts = new TColgp_HArray1OfPnt(1, n);
            for (int i = 0; i < n; ++i) {
                double r = r0 + (ra - r0) * i / (n - 1);
                pts->SetValue(outward ? i + 1 : n - i, at(r, c + sign * half(r)));
            }
            GeomAPI_Interpolate curve(pts, false, 1e-7);
            curve.Perform();
            return BRepBuilderAPI_MakeEdge(curve.Curve()).Edge();
        };
        BRepBuilderAPI_MakeWire outline;
        const double step = 2 * M_PI / teeth, h0 = half(r0), ha = half(ra);
        for (int k = 0; k < teeth; ++k) {
            double c = k * step;
            if (rf < rb) outline.Add(line(at(rf, c - h0), at(rb, c - h0)));
            outline.Add(flank(c, -1, true));
            outline.Add(arc(ra, c - ha, c + ha));
            outline.Add(flank(c, 1, false));
            if (rf < rb) outline.Add(line(at(rb, c + h0), at(rf, c + h0)));
            outline.Add(arc(rf, c + h0, c + step - h0));
        }
        if (!outline.IsDone()) throw std::runtime_error("The gear couldn't be made");
        TopoDS_Wire wire = outline.Wire();
        // Helical teeth turn by the helix's lead over the height: tan(helix) * height / pitch radius.
        auto turned = [&](double z, double a) {
            gp_Trsf r, up;
            r.SetRotation(gp::OZ(), a);
            up.SetTranslation(gp_Vec(0, 0, z));
            return TopoDS::Wire(BRepBuilderAPI_Transform(wire, up * r, true).Shape());
        };
        auto twisted = [&](double z0, double z1, double a0, double a1) {
            BRepOffsetAPI_ThruSections thru(true, false);
            const int n = 6;
            for (int i = 0; i <= n; ++i) thru.AddWire(turned(z0 + (z1 - z0) * i / n, a0 + (a1 - a0) * i / n));
            thru.CheckCompatibility(false);
            thru.Build();
            if (!thru.IsDone()) throw std::runtime_error("The gear couldn't be made");
            return thru.Shape();
        };
        TopoDS_Shape body;
        const double lead = std::tan(helix) / rp;
        if (std::abs(helix) < 1e-9) {
            body = BRepPrimAPI_MakePrism(BRepBuilderAPI_MakeFace(wire).Face(), gp_Vec(0, 0, thickness)).Shape();
        } else if (herringbone) {
            // The lower half, and the upper as its mirror image, sewn together where they meet in the middle.
            double mid = thickness / 2;
            BRepOffsetAPI_ThruSections thru(true, false);
            const int n = 3;
            for (int i = 0; i <= n; ++i) thru.AddWire(turned(mid * i / n, lead * mid * i / n));
            thru.CheckCompatibility(false);
            thru.Build();
            if (!thru.IsDone()) throw std::runtime_error("The gear couldn't be made");
            gp_Trsf flip;
            flip.SetMirror(gp_Ax2(gp_Pnt(0, 0, mid), gp::DZ()));
            BRepBuilderAPI_Transform upper(thru.Shape(), flip, true);
            TopoDS_Shape cap = thru.LastShape(), upperCap = upper.ModifiedShape(cap);
            BRepBuilderAPI_Sewing sew(1e-6);
            for (TopExp_Explorer f(thru.Shape(), TopAbs_FACE); f.More(); f.Next())
                if (!f.Current().IsSame(cap)) sew.Add(f.Current());
            for (TopExp_Explorer f(upper.Shape(), TopAbs_FACE); f.More(); f.Next())
                if (!f.Current().IsSame(upperCap)) sew.Add(f.Current());
            sew.Perform();
            TopExp_Explorer shell(sew.SewedShape(), TopAbs_SHELL);
            if (!shell.More()) throw std::runtime_error("The gear couldn't be made");
            BRepBuilderAPI_MakeSolid solid(TopoDS::Shell(shell.Current()));
            if (!solid.IsDone()) throw std::runtime_error("The gear couldn't be made");
            TopoDS_Solid made = solid.Solid();
            BRepLib::OrientClosedSolid(made);
            body = made;
        } else {
            body = twisted(0, thickness, 0, lead * thickness);
        }
        if (bore > 0) {
            BRepAlgoAPI_Cut hole(body, BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(0, 0, -1), gp::DZ()), bore / 2, thickness + 2).Shape());
            if (!hole.IsDone()) throw std::runtime_error("The gear couldn't be made");
            body = hole.Shape();
        }
        gp_Trsf move, spin;
        move.SetTranslation(gp_Vec(u, v, 0));
        spin.SetRotation(gp::OZ(), turn);
        body = BRepBuilderAPI_Transform(body, placeOn(plane) * move * spin, true).Shape();
        NamedShape out = nameAll(id, body, TopoDS_Shape(), TopoDS_Shape(), "g");
        check(out.shape, "The gear couldn't be made");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The gear couldn't be made");
    }
}

NamedShape fastener(int id, const gp_Ax3& seat, Fastener kind, double d, double length, double head, double headHeight, double socket,
                    double angle) {
    if (d <= 0 || head <= d || headHeight <= 0) throw std::runtime_error("Sizes have to be more than 0, the head bigger than the shank");
    bool screw = kind == Fastener::SocketCap || kind == Fastener::HexBolt || kind == Fastener::Countersunk;
    if (screw && length <= 0) throw std::runtime_error("The length has to be more than 0");
    try {
        auto cylinder = [](double r, double z0, double z1) {
            return BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(0, 0, z0), gp::DZ()), r, z1 - z0).Shape();
        };
        // A hexagon [across] its flats, from z0 to z1.
        auto hexagon = [](double across, double z0, double z1) {
            std::vector<gp_Pnt> corners;
            double r = across / std::sqrt(3.0);
            for (int k = 0; k < 6; ++k) corners.push_back(gp_Pnt(r * std::cos(k * M_PI / 3), r * std::sin(k * M_PI / 3), z0));
            return BRepPrimAPI_MakePrism(BRepBuilderAPI_MakeFace(polygon(corners)).Face(), gp_Vec(0, 0, z1 - z0)).Shape();
        };
        auto fuse = [](const TopoDS_Shape& a, const TopoDS_Shape& b) {
            BRepAlgoAPI_Fuse f(a, b);
            if (!f.IsDone()) throw std::runtime_error("The fastener couldn't be made");
            ShapeUpgrade_UnifySameDomain tidy(f.Shape(), true, true, true);
            tidy.Build();
            return tidy.Shape();
        };
        auto cut = [](const TopoDS_Shape& a, const TopoDS_Shape& b) {
            BRepAlgoAPI_Cut c(a, b);
            if (!c.IsDone()) throw std::runtime_error("The fastener couldn't be made");
            return c.Shape();
        };
        // The seat is z = 0: heads, nuts and washers stand above it, shanks go down into the hole.
        TopoDS_Shape shape;
        switch (kind) {
            case Fastener::SocketCap:
                shape = fuse(cylinder(head / 2, 0, headHeight), cylinder(d / 2, -length, 0));
                if (socket > 0) shape = cut(shape, hexagon(socket, headHeight * 0.4, headHeight + 1));
                break;
            case Fastener::HexBolt:
                shape = fuse(hexagon(head, 0, headHeight), cylinder(d / 2, -length, 0));
                break;
            case Fastener::Countersunk: {
                // The head's top is flush with the seat; its cone narrows to the shank at [angle] across.
                double sink = (head - d) / 2 / std::tan(angle / 2);
                if (sink >= length) throw std::runtime_error("The screw is shorter than its head");
                TopoDS_Shape cone = BRepPrimAPI_MakeCone(gp_Ax2(gp_Pnt(0, 0, -sink), gp::DZ()), d / 2, head / 2, sink).Shape();
                shape = fuse(cone, cylinder(d / 2, -length, -sink));
                if (socket > 0) shape = cut(shape, hexagon(socket, -sink * 0.7, 1));
                break;
            }
            case Fastener::Nut:
                shape = cut(hexagon(head, 0, headHeight), cylinder(d / 2, -1, headHeight + 1));
                break;
            case Fastener::Washer:
                shape = cut(cylinder(head / 2, 0, headHeight), cylinder(d / 2, -1, headHeight + 1));
                break;
        }
        shape = BRepBuilderAPI_Transform(shape, placeOn(seat), true).Shape();
        // The face the thread goes on is named "thread"; the rest are numbered.
        NamedShape out;
        out.shape = shape;
        int k = 0;
        for (TopExp_Explorer f(shape, TopAbs_FACE); f.More(); f.Next()) {
            if (out.names.IsBound(f.Current())) continue;
            BRepAdaptor_Surface s(TopoDS::Face(f.Current()));
            bool threaded = kind != Fastener::Washer && s.GetType() == GeomAbs_Cylinder && std::abs(s.Cylinder().Radius() - d / 2) < 1e-6;
            out.names.Bind(f.Current(), prefix(id) + (threaded ? ".thread" : ".f" + std::to_string(k++)));
        }
        check(out.shape, "The fastener couldn't be made");
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The fastener couldn't be made");
    }
}

namespace {

/** A flat face grown outward by d mm (shrunk when d is less than 0), corners going round as they grow. */
TopoDS_Face grown(const TopoDS_Face& face, double d) {
    if (std::abs(d) < 1e-9) return face;
    BRepOffsetAPI_MakeOffset offset(face, GeomAbs_Arc);
    offset.Perform(d);
    if (!offset.IsDone()) throw std::runtime_error("The lip doesn't fit the opening");
    BRepBuilderAPI_MakeFace out(BRep_Tool::Surface(face), Precision::Confusion());
    bool any = false;
    for (TopExp_Explorer w(offset.Shape(), TopAbs_WIRE); w.More(); w.Next()) {
        out.Add(TopoDS::Wire(w.Current()));
        any = true;
    }
    if (!any || !out.IsDone()) throw std::runtime_error("The lip doesn't fit the opening");
    ShapeFix_Face fix(out.Face());
    fix.Perform();
    return fix.Face();
}

}  // namespace

NamedShape lipTool(int id, const NamedShape& body, const std::string& face, double inside, double outside, double height, const std::string& tag) {
    if (height <= 0) throw std::runtime_error("The lip has to be taller than 0");
    if (outside <= inside) throw std::runtime_error("The lip has to be wider than 0");
    auto faces = body.findFaces(face);
    if (faces.empty()) throw std::runtime_error("The rim isn't there any more");
    const TopoDS_Face& rim = faces[0];
    BRepAdaptor_Surface surface(rim);
    if (surface.GetType() != GeomAbs_Plane) throw std::runtime_error("The rim has to be flat");
    gp_Pln pln = surface.Plane();
    gp_Dir n = pln.Axis().Direction();
    if (rim.Orientation() == TopAbs_REVERSED) n.Reverse();
    try {
        TopoDS_Wire outer = BRepTools::OuterWire(rim);
        TopoDS_Compound all;
        BRep_Builder b;
        b.MakeCompound(all);
        bool any = false;
        // Each opening in the rim gets a ring round it, from inside to outside mm out from its edge.
        for (TopExp_Explorer w(rim, TopAbs_WIRE); w.More(); w.Next()) {
            TopoDS_Wire wire = TopoDS::Wire(w.Current());
            if (wire.IsSame(outer)) continue;
            BRepBuilderAPI_MakeFace make(pln, wire, Standard_True);
            if (!make.IsDone()) continue;
            ShapeFix_Face fix(make.Face());
            fix.Perform();
            TopoDS_Face opening = fix.Face();
            // Only real openings: just behind the face there's no material, as there is under a boss on a floor.
            GProp_GProps props;
            BRepGProp::SurfaceProperties(opening, props);
            BRepClass3d_SolidClassifier behind(body.shape, props.CentreOfMass().Translated(gp_Vec(n) * -0.01), 1e-6);
            if (behind.State() == TopAbs_IN) continue;
            BRepAlgoAPI_Cut ring(grown(opening, outside), grown(opening, inside));
            if (!ring.IsDone()) throw std::runtime_error("The lip doesn't fit the opening");
            b.Add(all, BRepPrimAPI_MakePrism(ring.Shape(), gp_Vec(n) * height).Shape());
            any = true;
        }
        if (!any) throw std::runtime_error("Pick the top of a wall, round an opening");
        NamedShape out;
        out.shape = all;
        int k = 0;
        for (TopExp_Explorer f(all, TopAbs_FACE); f.More(); f.Next())
            if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + "." + tag + std::to_string(k++));
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The lip couldn't be made");
    }
}

std::vector<int> tangentChain(const TopoDS_Shape& shape, int edge) {
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(shape, TopAbs_EDGE, edges);
    if (edge < 0 || edge >= edges.Extent()) return {};
    TopTools_IndexedDataMapOfShapeListOfShape byVertex;
    TopExp::MapShapesAndAncestors(shape, TopAbs_VERTEX, TopAbs_EDGE, byVertex);
    // Which way an edge runs where it reaches a vertex.
    auto along = [](const TopoDS_Edge& e, const TopoDS_Vertex& v) {
        BRepAdaptor_Curve c(e);
        gp_Pnt p;
        gp_Vec d;
        c.D1(BRep_Tool::Parameter(v, e), p, d);
        return d;
    };
    std::vector<int> out{edge}, todo{edge};
    std::vector<bool> seen(size_t(edges.Extent()), false);
    seen[size_t(edge)] = true;
    try {
        while (!todo.empty()) {
            int k = todo.back();
            todo.pop_back();
            TopoDS_Edge e = TopoDS::Edge(edges(k + 1));
            if (BRep_Tool::Degenerated(e)) continue;
            TopoDS_Vertex ends[2];
            TopExp::Vertices(e, ends[0], ends[1]);
            for (const auto& v : ends) {
                if (v.IsNull() || !byVertex.Contains(v)) continue;
                gp_Vec a = along(e, v);
                if (a.Magnitude() < 1e-12) continue;
                for (const TopoDS_Shape& s : byVertex.FindFromKey(v)) {
                    int j = edges.FindIndex(s) - 1;
                    if (j < 0 || seen[size_t(j)]) continue;
                    TopoDS_Edge o = TopoDS::Edge(s);
                    if (BRep_Tool::Degenerated(o)) continue;
                    gp_Vec b = along(o, v);
                    if (b.Magnitude() < 1e-12) continue;
                    // Running on smoothly, within 2 degrees.
                    if (a.Crossed(b).Magnitude() > std::sin(2 * M_PI / 180) * a.Magnitude() * b.Magnitude()) continue;
                    seen[size_t(j)] = true;
                    out.push_back(j);
                    todo.push_back(j);
                }
            }
        }
    } catch (const Standard_Failure&) {
    }
    return out;
}

NamedShape snapFitTool(int id, const gp_Ax3& plane, const std::vector<std::pair<double, double>>& at, const gp_Pnt& middle,
                       double length, double width, double thickness, double overhang, double catchHeight, double gap, bool catchPart,
                       const std::string& tag) {
    if (at.empty()) throw std::runtime_error("Put points in the sketch where the clips go");
    if (length <= 0 || width <= 0 || thickness <= 0 || overhang <= 0 || catchHeight <= 0) throw std::runtime_error("Sizes have to be more than 0");
    if (catchHeight >= length) throw std::runtime_error("The catch has to be shorter than the clip");
    try {
        const gp_Vec n(plane.Direction());
        TopoDS_Compound all;
        BRep_Builder b;
        b.MakeCompound(all);
        for (const auto& [u, v] : at) {
            gp_Pnt o = plane.Location().Translated(gp_Vec(plane.XDirection()) * u + gp_Vec(plane.YDirection()) * v);
            // The hook points away from the middle of the part, along the face.
            gp_Vec away(middle, o);
            away -= n * away.Dot(n);
            gp_Vec a = away.Magnitude() > 1e-6 ? away.Normalized() : gp_Vec(plane.XDirection());
            gp_Vec side = n.Crossed(a);
            auto at3 = [&](double across, double up, double sideways) {
                return o.Translated(a * across + n * up + side * sideways);
            };
            TopoDS_Shape part;
            if (!catchPart) {
                // The beam stands out of the face with its outside on the point; the hook's flat catch faces the face, its slope the tip.
                TopoDS_Shape beam = BRepPrimAPI_MakeBox(gp_Ax2(at3(-thickness, 0, -width / 2), gp_Dir(n), gp_Dir(a)), thickness, width, length).Shape();
                TopoDS_Wire tri = polygon({at3(0, length - catchHeight, -width / 2), at3(overhang, length - catchHeight, -width / 2), at3(0, length, -width / 2)});
                TopoDS_Shape hook = BRepPrimAPI_MakePrism(BRepBuilderAPI_MakeFace(tri).Face(), side * width).Shape();
                part = BRepAlgoAPI_Fuse(beam, hook).Shape();
            } else {
                // Where the hook rests, with the gap round it.
                part = BRepPrimAPI_MakeBox(gp_Ax2(at3(-gap, length - catchHeight - gap, -width / 2 - gap), gp_Dir(n), gp_Dir(a)),
                                           overhang + 2 * gap, width + 2 * gap, catchHeight + 2 * gap).Shape();
            }
            b.Add(all, part);
        }
        NamedShape out;
        out.shape = all;
        int k = 0;
        for (TopExp_Explorer f(all, TopAbs_FACE); f.More(); f.Next())
            if (!out.names.IsBound(f.Current())) out.names.Bind(f.Current(), prefix(id) + "." + tag + std::to_string(k++));
        return out;
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The clip couldn't be made");
    }
}

}  // namespace pm
