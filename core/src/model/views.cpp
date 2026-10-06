#include "model/views.h"

#include <HLRAlgo_Projector.hxx>
#include <HLRBRep_Algo.hxx>
#include <HLRBRep_HLRToShape.hxx>
#include <HLRBRep_PolyAlgo.hxx>
#include <HLRBRep_PolyHLRToShape.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Builder.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <BRepBuilderAPI_Copy.hxx>
#include <TopoDS_Compound.hxx>
#include <Standard_Failure.hxx>
#include <gp_Ax2.hxx>
#include <gp_Ax3.hxx>

#include <algorithm>
#include <cmath>
#include <stdexcept>

#include "model/operations.h"

namespace pm {

namespace {

/** How far (x, y) is from the curve. */
double distanceTo(const SketchCurve& c, double x, double y) {
    if (c.kind == SketchCurve::Line) {
        double dx = c.x2 - c.x1, dy = c.y2 - c.y1, l2 = dx * dx + dy * dy;
        double t = l2 > 0 ? std::clamp(((x - c.x1) * dx + (y - c.y1) * dy) / l2, 0.0, 1.0) : 0;
        return std::hypot(x - (c.x1 + t * dx), y - (c.y1 + t * dy));
    }
    double off = std::abs(std::hypot(x - c.x1, y - c.y1) - c.r);
    if (c.kind == SketchCurve::Circle) return off;
    double a = std::atan2(y - c.y1, x - c.x1);
    auto span = [](double from, double to) { double d = to - from; while (d < 0) d += 2 * M_PI; while (d >= 2 * M_PI) d -= 2 * M_PI; return d; };
    if (span(c.a0, a) <= span(c.a0, c.a1) + 1e-9) return off;
    double ex = std::min(std::hypot(x - (c.x1 + c.r * std::cos(c.a0)), y - (c.y1 + c.r * std::sin(c.a0))),
                         std::hypot(x - (c.x1 + c.r * std::cos(c.a1)), y - (c.y1 + c.r * std::sin(c.a1))));
    return ex;
}

/** Points along the curve: its ends and a few between. */
std::vector<std::pair<double, double>> samples(const SketchCurve& c) {
    std::vector<std::pair<double, double>> out;
    for (int i = 0; i <= 4; ++i) {
        double t = i / 4.0;
        if (c.kind == SketchCurve::Line) out.push_back({c.x1 + t * (c.x2 - c.x1), c.y1 + t * (c.y2 - c.y1)});
        else {
            double a1 = c.a1;
            while (a1 < c.a0) a1 += 2 * M_PI;
            double a = c.kind == SketchCurve::Circle ? t * 2 * M_PI : c.a0 + t * (a1 - c.a0);
            out.push_back({c.x1 + c.r * std::cos(a), c.y1 + c.r * std::sin(a)});
        }
    }
    return out;
}

/** Hidden lines that don't lie on seen ones: a box's back edges behind its front ones aren't drawn. */
std::vector<SketchCurve> uncovered(const std::vector<SketchCurve>& behind, const std::vector<SketchCurve>& seen) {
    const double tol = 1e-3;
    std::vector<SketchCurve> out;
    for (const auto& h : behind) {
        bool covered = true;
        for (auto [x, y] : samples(h)) {
            bool on = false;
            for (const auto& v : seen) if (distanceTo(v, x, y) < tol) { on = true; break; }
            if (!on) { covered = false; break; }
        }
        if (!covered) out.push_back(h);
    }
    return out;
}

}  // namespace

ProjectedView projectView(const std::vector<TopoDS_Shape>& shapes, const gp_Dir& towardsViewer, const gp_Dir& right, bool hidden) {
    ProjectedView out;
    if (shapes.empty()) return out;
    try {
        Handle(HLRBRep_Algo) algo = new HLRBRep_Algo();
        for (const auto& s : shapes) algo->Add(s);
        algo->Projector(HLRAlgo_Projector(gp_Ax2(gp::Origin(), towardsViewer, right)));
        algo->Update();
        algo->Hide();
        HLRBRep_HLRToShape lines(algo);
        // The projection comes out flat in the view's x, y.
        const gp_Ax3 flat(gp::XOY());
        auto add = [&](const TopoDS_Shape& s, std::vector<SketchCurve>& to) {
            if (s.IsNull()) return;
            auto c = curvesOnPlane(s, flat);
            to.insert(to.end(), c.begin(), c.end());
        };
        // Sharp edges and outlines; smooth joins between faces aren't drawn.
        add(lines.VCompound(), out.visible);
        add(lines.OutLineVCompound(), out.visible);
        if (hidden) {
            std::vector<SketchCurve> behind;
            add(lines.HCompound(), behind);
            add(lines.OutLineHCompound(), behind);
            out.hidden = uncovered(behind, out.visible);
        }
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The view couldn't be worked out");
    }
    return out;
}

ProjectedView projectViewFast(const std::vector<TopoDS_Shape>& shapes, const gp_Dir& towardsViewer, const gp_Dir& right, bool hidden, double tolerance) {
    ProjectedView out;
    if (shapes.empty()) return out;
    try {
        BRep_Builder b;
        TopoDS_Compound all;
        b.MakeCompound(all);
        // A copy, meshed for this: the bodies' own triangles are the display's.
        for (const auto& s : shapes) b.Add(all, BRepBuilderAPI_Copy(s, true, false).Shape());
        BRepMesh_IncrementalMesh(all, tolerance, false, 0.3, true);
        Handle(HLRBRep_PolyAlgo) algo = new HLRBRep_PolyAlgo();
        algo->Load(all);
        algo->Projector(HLRAlgo_Projector(gp_Ax2(gp::Origin(), towardsViewer, right)));
        algo->Update();
        HLRBRep_PolyHLRToShape lines;
        lines.Update(algo);
        const gp_Ax3 flat(gp::XOY());
        auto add = [&](const TopoDS_Shape& s, std::vector<SketchCurve>& to) {
            if (s.IsNull()) return;
            auto c = curvesOnPlane(s, flat);
            to.insert(to.end(), c.begin(), c.end());
        };
        add(lines.VCompound(), out.visible);
        add(lines.OutLineVCompound(), out.visible);
        if (hidden) {
            std::vector<SketchCurve> behind;
            add(lines.HCompound(), behind);
            add(lines.OutLineHCompound(), behind);
            out.hidden = uncovered(behind, out.visible);
        }
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The view couldn't be worked out");
    }
    return out;
}

double viewEffort(const std::vector<TopoDS_Shape>& shapes) {
    double effort = 0;
    for (const auto& s : shapes)
        for (TopExp_Explorer f(s, TopAbs_FACE); f.More(); f.Next()) {
            BRepAdaptor_Surface surface(TopoDS::Face(f.Current()), false);
            switch (surface.GetType()) {
                case GeomAbs_BSplineSurface: effort += double(surface.NbUPoles()) * surface.NbVPoles(); break;
                case GeomAbs_BezierSurface: effort += double(surface.NbUPoles()) * surface.NbVPoles(); break;
                case GeomAbs_Plane: case GeomAbs_Cylinder: case GeomAbs_Cone: case GeomAbs_Sphere: case GeomAbs_Torus: effort += 1; break;
                default: effort += 50;
            }
        }
    return effort;
}

}  // namespace pm
