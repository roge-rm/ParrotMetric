#include "model/named_shape.h"

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_MakeShape.hxx>
#include <BRepGProp.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <GProp_GProps.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Vertex.hxx>

#include <algorithm>
#include <cmath>
#include <limits>

namespace pm {

std::string NamedShape::faceName(const TopoDS_Shape& face) const {
    const std::string* n = names.Seek(face);
    return n ? *n : std::string();
}

std::string NamedShape::edgeName(const TopoDS_Edge& edge) const {
    TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
    TopExp::MapShapesAndAncestors(shape, TopAbs_EDGE, TopAbs_FACE, edgeFaces);
    const TopTools_ListOfShape* faces = edgeFaces.Seek(edge);
    if (!faces) return {};
    std::vector<std::string> parts;
    for (const auto& f : *faces) parts.push_back(faceName(f));
    std::sort(parts.begin(), parts.end());
    parts.erase(std::unique(parts.begin(), parts.end()), parts.end());
    std::string out;
    for (const auto& p : parts) out += (out.empty() ? "" : "|") + p;
    return out;
}

std::vector<std::string> NamedShape::faceNames() const {
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(shape, TopAbs_FACE, faces);
    std::vector<std::string> out;
    for (int i = 1; i <= faces.Extent(); ++i) out.push_back(faceName(faces(i)));
    return out;
}

std::vector<std::string> NamedShape::edgeNames() const {
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(shape, TopAbs_EDGE, edges);
    std::vector<std::string> out;
    for (int i = 1; i <= edges.Extent(); ++i) out.push_back(edgeName(TopoDS::Edge(edges(i))));
    return out;
}

std::vector<std::string> NamedShape::cornerNames() const {
    TopTools_IndexedMapOfShape corners;
    TopExp::MapShapes(shape, TopAbs_VERTEX, corners);
    TopTools_IndexedDataMapOfShapeListOfShape edgesAt, facesOf;
    TopExp::MapShapesAndAncestors(shape, TopAbs_VERTEX, TopAbs_EDGE, edgesAt);
    TopExp::MapShapesAndAncestors(shape, TopAbs_EDGE, TopAbs_FACE, facesOf);
    // Seams and edges that close on themselves (a circle) don't make corners.
    auto counts = [&](const TopoDS_Edge& e) {
        if (BRep_Tool::Degenerated(e) || BRep_Tool::IsClosed(e)) return false;
        for (const auto& f : facesOf.FindFromKey(e))
            if (BRep_Tool::IsClosed(e, TopoDS::Face(f))) return false;
        return true;
    };
    std::vector<std::string> out;
    for (int i = 1; i <= corners.Extent(); ++i) {
        // The first two different edge names there, in order, so the name doesn't depend on map order.
        std::vector<std::string> names;
        for (const auto& e : edgesAt.FindFromKey(corners(i))) {
            if (!counts(TopoDS::Edge(e))) continue;
            std::string n = edgeName(TopoDS::Edge(e));
            if (!n.empty() && std::find(names.begin(), names.end(), n) == names.end()) names.push_back(n);
        }
        std::sort(names.begin(), names.end());
        out.push_back(names.size() >= 2 ? names[0] + " & " + names[1] : std::string());
    }
    return out;
}

std::optional<gp_Pnt> NamedShape::findCorner(const std::string& name) const {
    size_t split = name.find(" & ");
    if (split == std::string::npos) return std::nullopt;
    auto first = findEdges(name.substr(0, split));
    auto second = findEdges(name.substr(split + 3));
    for (const auto& a : first)
        for (const auto& b : second) {
            TopoDS_Vertex v;
            if (TopExp::CommonVertex(a, b, v)) return BRep_Tool::Pnt(v);
        }
    return std::nullopt;
}

std::vector<TopoDS_Edge> NamedShape::findEdges(const std::string& name) const {
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(shape, TopAbs_EDGE, edges);
    std::vector<TopoDS_Edge> out;
    for (int i = 1; i <= edges.Extent(); ++i) {
        const TopoDS_Edge& e = TopoDS::Edge(edges(i));
        if (edgeName(e) == name) out.push_back(e);
    }
    return out;
}

std::vector<TopoDS_Face> NamedShape::findFaces(const std::string& name) const {
    std::vector<TopoDS_Face> out;
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(shape, TopAbs_FACE, faces);
    for (int i = 1; i <= faces.Extent(); ++i) {
        if (faceName(faces(i)) == name) out.push_back(TopoDS::Face(faces(i)));
    }
    return out;
}

namespace {

double diagonal(const TopoDS_Shape& shape) {
    Bnd_Box box;
    BRepBndLib::Add(shape, box);
    if (box.IsVoid()) return 1;
    return std::max(std::sqrt(box.SquareExtent()), 1e-6);
}

std::vector<double> faceSignature(const TopoDS_Face& f, double diag) {
    GProp_GProps props;
    BRepGProp::SurfaceProperties(f, props);
    gp_Pnt c = props.CentreOfMass();
    BRepAdaptor_Surface surface(f);
    gp_Dir n(0, 0, 1);
    bool flat = surface.GetType() == GeomAbs_Plane;
    if (flat) {
        n = surface.Plane().Axis().Direction();
        if (f.Orientation() == TopAbs_REVERSED) n.Reverse();
    }
    return {0, double(surface.GetType()), c.X(), c.Y(), c.Z(), flat ? n.X() : 0, flat ? n.Y() : 0, flat ? n.Z() : 0, props.Mass(), diag};
}

std::vector<double> edgeSignature(const TopoDS_Edge& e, double diag) {
    BRepAdaptor_Curve curve(e);
    double mid = (curve.FirstParameter() + curve.LastParameter()) / 2;
    gp_Pnt p;
    gp_Vec d;
    curve.D1(mid, p, d);
    GProp_GProps props;
    BRepGProp::LinearProperties(e, props);
    bool straight = curve.GetType() == GeomAbs_Line && d.Magnitude() > 1e-12;
    if (straight) d.Normalize();
    return {1, double(curve.GetType()), p.X(), p.Y(), p.Z(), straight ? d.X() : 0, straight ? d.Y() : 0, straight ? d.Z() : 0, props.Mass(), diag};
}

/**
 * How unlike two signatures are: 0 for the same place, direction and size.
 * Distance counts against the body's size, a turned direction and a changed
 * size less. Faces facing the other way count as turned; edges either way.
 */
double unlike(const std::vector<double>& a, const std::vector<double>& b) {
    if (a.size() < 10 || b.size() < 10 || a[0] != b[0] || a[1] != b[1]) return std::numeric_limits<double>::infinity();
    double diag = std::max(a[9], 1e-6);
    double gap = std::sqrt((a[2] - b[2]) * (a[2] - b[2]) + (a[3] - b[3]) * (a[3] - b[3]) + (a[4] - b[4]) * (a[4] - b[4])) / diag;
    double turn = 0;
    bool aDir = a[5] != 0 || a[6] != 0 || a[7] != 0, bDir = b[5] != 0 || b[6] != 0 || b[7] != 0;
    if (aDir && bDir) {
        double dot = a[5] * b[5] + a[6] * b[6] + a[7] * b[7];
        turn = 1 - (a[0] == 1 ? std::abs(dot) : dot);
    }
    double size = (a[8] > 1e-12 && b[8] > 1e-12) ? std::abs(std::log(b[8] / a[8])) : 1;
    return gap + 0.5 * turn + 0.3 * size;
}

// Below this a match counts as the same face or edge: about a tenth of the
// body away, or the same place a bit bigger or smaller.
constexpr double kSameEnough = 0.25;

}  // namespace

std::vector<double> signatureOf(const NamedShape& body, const std::string& name, bool edge) {
    double diag = diagonal(body.shape);
    if (edge) {
        auto edges = body.findEdges(name);
        return edges.empty() ? std::vector<double>{} : edgeSignature(edges[0], diag);
    }
    auto faces = body.findFaces(name);
    return faces.empty() ? std::vector<double>{} : faceSignature(faces[0], diag);
}

std::string relocate(const NamedShape& body, const std::vector<double>& signature) {
    if (signature.size() < 10) return {};
    double diag = diagonal(body.shape);
    bool edge = signature[0] == 1;
    TopTools_IndexedMapOfShape items;
    TopExp::MapShapes(body.shape, edge ? TopAbs_EDGE : TopAbs_FACE, items);
    double best = kSameEnough;
    std::string found;
    for (int i = 1; i <= items.Extent(); ++i) {
        if (edge && BRep_Tool::Degenerated(TopoDS::Edge(items(i)))) continue;
        auto s = edge ? edgeSignature(TopoDS::Edge(items(i)), diag) : faceSignature(TopoDS::Face(items(i)), diag);
        double u = unlike(signature, s);
        if (u < best) {
            std::string name = edge ? body.edgeName(TopoDS::Edge(items(i))) : body.faceName(items(i));
            if (name.empty()) continue;
            best = u;
            found = name;
        }
    }
    return found;
}

NamedShape carryNames(const std::vector<const NamedShape*>& inputs, BRepBuilderAPI_MakeShape& op, const TopoDS_Shape& result,
                      const std::string& prefix) {
    NamedShape out;
    out.shape = result;
    TopTools_IndexedMapOfShape present;
    TopExp::MapShapes(result, TopAbs_FACE, present);
    for (const NamedShape* in : inputs) {
        for (TopExp_Explorer f(in->shape, TopAbs_FACE); f.More(); f.Next()) {
            const TopoDS_Shape& face = f.Current();
            std::string name = in->faceName(face);
            if (name.empty() || op.IsDeleted(face)) continue;
            const TopTools_ListOfShape& changed = op.Modified(face);
            if (changed.IsEmpty()) {
                if (present.Contains(face) && !out.names.IsBound(face)) out.names.Bind(face, name);
            } else {
                for (const auto& m : changed) {
                    if (present.Contains(m) && !out.names.IsBound(m)) out.names.Bind(m, name);
                }
            }
        }
    }
    int k = 0;
    for (int i = 1; i <= present.Extent(); ++i) {
        if (!out.names.IsBound(present(i))) out.names.Bind(present(i), prefix + ".n" + std::to_string(k++));
    }
    return out;
}

}  // namespace pm
