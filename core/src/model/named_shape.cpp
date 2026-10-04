#include "model/named_shape.h"

#include <BRepBuilderAPI_MakeShape.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>

#include <algorithm>

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
