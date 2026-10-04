#include "sketch/regions.h"
#include "sketch/region_faces.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRepAlgoAPI_Splitter.hxx>
#include <TopTools_ShapeMapHasher.hxx>
#include <NCollection_DataMap.hxx>
#include <BRep_Builder.hxx>
#include <BRepTools_ReShape.hxx>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepGProp.hxx>
#include <BRepTools.hxx>
#include <BRepTopAdaptor_FClass2d.hxx>
#include <BRepTools_WireExplorer.hxx>
#include <Bnd_Box.hxx>
#include <GCPnts_QuasiUniformDeflection.hxx>
#include <GProp_GProps.hxx>
#include <Standard_Failure.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Wire.hxx>
#include <Geom_BezierCurve.hxx>
#include <TColgp_Array1OfPnt.hxx>
#include <gp_Circ.hxx>
#include <gp_Pln.hxx>

#include <algorithm>
#include <cmath>

namespace pm {
namespace {

TopoDS_Edge makeEdge(const SketchCurve& c) {
    if (c.kind == SketchCurve::Bezier) {
        TColgp_Array1OfPnt poles(1, 4);
        poles(1) = gp_Pnt(c.x1, c.y1, 0);
        poles(2) = gp_Pnt(c.cx1, c.cy1, 0);
        poles(3) = gp_Pnt(c.cx2, c.cy2, 0);
        poles(4) = gp_Pnt(c.x2, c.y2, 0);
        if (poles(1).Distance(poles(4)) < 1e-7) return {};
        return BRepBuilderAPI_MakeEdge(new Geom_BezierCurve(poles));
    }
    if (c.kind == SketchCurve::Line) {
        gp_Pnt a(c.x1, c.y1, 0), b(c.x2, c.y2, 0);
        if (a.Distance(b) < 1e-7) return {};
        return BRepBuilderAPI_MakeEdge(a, b);
    }
    if (c.r < 1e-7) return {};
    gp_Circ circ(gp_Ax2(gp_Pnt(c.x1, c.y1, 0), gp_Dir(0, 0, 1)), c.r);
    if (c.kind == SketchCurve::Circle) return BRepBuilderAPI_MakeEdge(circ);
    double a1 = c.a1;
    while (a1 <= c.a0) a1 += 2 * M_PI;
    return BRepBuilderAPI_MakeEdge(circ, c.a0, a1);
}

/** Points along a wire in order, in the face's direction. */
/**
 * The face without curves that end inside it, such as a line poking into a
 * rectangle: they're left in its boundary running both ways, and a face
 * like that can't be extruded.
 */
TopoDS_Face withoutDangling(const TopoDS_Face& face) {
    NCollection_DataMap<TopoDS_Shape, int, TopTools_ShapeMapHasher> uses;
    for (TopExp_Explorer e(face, TopAbs_EDGE); e.More(); e.Next()) {
        if (int* n = uses.ChangeSeek(e.Current())) ++*n;
        else uses.Bind(e.Current(), 1);
    }
    Handle(BRepTools_ReShape) reshape = new BRepTools_ReShape;
    bool any = false;
    for (TopExp_Explorer e(face, TopAbs_EDGE); e.More(); e.Next()) {
        TopAbs_Orientation o = e.Current().Orientation();
        if (uses.Find(e.Current()) > 1 || o == TopAbs_INTERNAL || o == TopAbs_EXTERNAL) {
            reshape->Remove(e.Current());
            any = true;
        }
    }
    if (!any) return face;
    TopoDS_Shape cut = reshape->Apply(face);
    // Wires left with no edges go.
    TopoDS_Face out = TopoDS::Face(face.EmptyCopied());
    BRep_Builder builder;
    for (TopExp_Explorer w(cut, TopAbs_WIRE); w.More(); w.Next()) {
        if (TopExp_Explorer(w.Current(), TopAbs_EDGE).More()) builder.Add(out, w.Current());
    }
    return out;
}

Region::Loop sampleWire(const TopoDS_Wire& wire, const TopoDS_Face& face) {
    Region::Loop loop;
    for (BRepTools_WireExplorer w(wire, face); w.More(); w.Next()) {
        const TopoDS_Edge& e = w.Current();
        BRepAdaptor_Curve curve(e);
        GCPnts_QuasiUniformDeflection sampler(curve, 0.005);
        if (!sampler.IsDone()) continue;
        std::vector<gp_Pnt> pts;
        for (int i = 1; i <= sampler.NbPoints(); ++i) pts.push_back(sampler.Value(i));
        if (e.Orientation() == TopAbs_REVERSED) std::reverse(pts.begin(), pts.end());
        // Each edge's last point is the next one's first.
        for (size_t i = 0; i + 1 < pts.size(); ++i) loop.push_back({float(pts[i].X()), float(pts[i].Y())});
    }
    return loop;
}

}  // namespace

TopoDS_Edge sketchEdge(const SketchCurve& c) { return makeEdge(c); }

std::vector<RegionFace> buildRegionFaces(const std::vector<SketchCurve>& curves) {
    std::vector<RegionFace> regions;
    try {
        TopTools_ListOfShape tools;
        std::vector<std::pair<TopoDS_Edge, int>> inputs;
        Bnd_Box box;
        for (const auto& c : curves) {
            TopoDS_Edge e = makeEdge(c);
            if (e.IsNull()) continue;
            tools.Append(e);
            inputs.push_back({e, c.id});
            BRepBndLib::Add(e, box);
        }
        if (inputs.empty()) return regions;

        // A sheet bigger than everything drawn, cut up by the curves. The
        // pieces that don't reach the sheet's border are the regions.
        double x0, y0, z0, x1, y1, z1;
        box.Get(x0, y0, z0, x1, y1, z1);
        double margin = std::max({x1 - x0, y1 - y0, 1.0});
        x0 -= margin; y0 -= margin; x1 += margin; y1 += margin;
        TopoDS_Face sheet = BRepBuilderAPI_MakeFace(gp_Pln(gp::XOY()), x0, x1, y0, y1);

        BRepAlgoAPI_Splitter splitter;
        TopTools_ListOfShape arguments;
        arguments.Append(sheet);
        splitter.SetArguments(arguments);
        splitter.SetTools(tools);
        splitter.Build();
        if (!splitter.IsDone()) return regions;

        for (TopExp_Explorer f(splitter.Shape(), TopAbs_FACE); f.More(); f.Next()) {
            const TopoDS_Face& face = TopoDS::Face(f.Current());
            Bnd_Box fb;
            BRepBndLib::Add(face, fb);
            double fx0, fy0, fz0, fx1, fy1, fz1;
            fb.Get(fx0, fy0, fz0, fx1, fy1, fz1);
            const double tol = 1e-6 * margin;
            if (fx0 <= x0 + tol || fy0 <= y0 + tol || fx1 >= x1 - tol || fy1 >= y1 - tol) continue;

            RegionFace rf;
            rf.face = withoutDangling(face);
            Region& r = rf.info;
            TopoDS_Wire outer = BRepTools::OuterWire(rf.face);
            r.loops.push_back(sampleWire(outer, rf.face));
            for (TopExp_Explorer w(rf.face, TopAbs_WIRE); w.More(); w.Next()) {
                if (!w.Current().IsSame(outer)) r.loops.push_back(sampleWire(TopoDS::Wire(w.Current()), rf.face));
            }
            // Which input curve each of its edges came from.
            for (TopExp_Explorer e(rf.face, TopAbs_EDGE); e.More(); e.Next()) {
                for (const auto& [input, id] : inputs) {
                    bool from = e.Current().IsSame(input);
                    if (!from) {
                        for (const auto& piece : splitter.Modified(input)) {
                            if (piece.IsSame(e.Current())) { from = true; break; }
                        }
                    }
                    if (from) {
                        r.curveIds.push_back(id);
                        rf.edgeCurves.push_back({TopoDS::Edge(e.Current()), id});
                        break;
                    }
                }
            }
            std::sort(r.curveIds.begin(), r.curveIds.end());
            r.curveIds.erase(std::unique(r.curveIds.begin(), r.curveIds.end()), r.curveIds.end());
            GProp_GProps props;
            BRepGProp::SurfaceProperties(rf.face, props);
            r.area = std::abs(props.Mass());
            // A point inside: the middle of the bounds if it's in, else the first of a finer and finer grid that is.
            BRepTopAdaptor_FClass2d classify(face, 1e-7);
            double u0, u1, v0, v1;
            BRepTools::UVBounds(face, u0, u1, v0, v1);
            bool found = false;
            for (int n = 1; n <= 64 && !found; n *= 2) {
                for (int i = 0; i < n && !found; ++i)
                    for (int j = 0; j < n && !found; ++j) {
                        double u = u0 + (u1 - u0) * (i + 0.5) / n, v = v0 + (v1 - v0) * (j + 0.5) / n;
                        if (classify.Perform(gp_Pnt2d(u, v)) == TopAbs_IN) {
                            r.insideU = u; r.insideV = v; found = true;
                        }
                    }
            }
            regions.push_back(std::move(rf));
        }
    } catch (const Standard_Failure&) {
        regions.clear();
    }
    // Largest first, so a tap on nested regions can prefer the smallest by going backwards.
    std::sort(regions.begin(), regions.end(), [](const RegionFace& a, const RegionFace& b) { return a.info.area > b.info.area; });
    return regions;
}

std::vector<Region> findRegions(const std::vector<SketchCurve>& curves) {
    std::vector<Region> out;
    for (auto& r : buildRegionFaces(curves)) out.push_back(std::move(r.info));
    return out;
}

}  // namespace pm
