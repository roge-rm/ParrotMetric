#include "model/operations.h"
#include "parallel.h"

#include <BRepAdaptor_Curve.hxx>
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

NamedShape extrude(int id, const gp_Ax3& plane, const std::vector<SketchCurve>& curves, const std::vector<RegionPick>& picks,
                   double forward, double back, double taper, double thin) {
    if (std::abs(forward + back) < 1e-6) throw std::runtime_error("The extrude has no length");
    if (thin < 0) throw std::runtime_error("The wall has to be thicker than 0");
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
        for (TopExp_Explorer s(all.shape, TopAbs_SOLID); s.More(); s.Next()) {
            NamedShape piece;
            piece.shape = s.Current();
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
            for (int i = 0; i < n; ++i) line(local(t0 + (t1 - t0) * i / n), local(t0 + (t1 - t0) * (i + 1) / n));
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
        BRepAlgoAPI_Common common(a.shape, b.shape);
        return common.IsDone() && volume(common.Shape()) > 1e-9;
    } catch (const Standard_Failure&) {
        return false;
    }
}

}  // namespace pm
