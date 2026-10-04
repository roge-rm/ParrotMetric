#include "solid/solid.h"

#include <BRepBuilderAPI_MakeShape.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepGProp.hxx>
#include <BRepGProp_Face.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRep_Tool.hxx>
#include <GProp_GProps.hxx>
#include <Poly_PolygonOnTriangulation.hxx>
#include <Poly_Triangulation.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <Standard_Failure.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Shape.hxx>

#include <stdexcept>

namespace pm {

Solid::Solid(const TopoDS_Shape& shape) : shape_(std::make_shared<TopoDS_Shape>(shape)) {}

Solid Solid::box(double x, double y, double z) { return Solid(BRepPrimAPI_MakeBox(x, y, z).Shape()); }

Solid Solid::filletAllEdges(double radius) const {
    try {
        BRepFilletAPI_MakeFillet fillet(*shape_);
        // Each edge once; the explorer would visit it from both its faces.
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(*shape_, TopAbs_EDGE, edges);
        for (int i = 1; i <= edges.Extent(); ++i) fillet.Add(radius, TopoDS::Edge(edges(i)));
        fillet.Build();
        // A fillet that's too big can still come back "done" with faces that
        // cross, so the result is checked too.
        if (!fillet.IsDone() || !BRepCheck_Analyzer(fillet.Shape()).IsValid())
            throw std::runtime_error("The fillet doesn't fit");
        return Solid(fillet.Shape());
    } catch (const Standard_Failure&) {
        throw std::runtime_error("The fillet doesn't fit");
    }
}

Mesh Solid::tessellate(const Tessellation& t) const {
    // Meshes the shape in place: OCCT keeps the triangles on the faces, so a
    // second call with the same settings costs nothing.
    BRepMesh_IncrementalMesh(*shape_, t.chord, false, t.angle, false);
    // Faces get their own nodes; the ones along a shared edge sit at the same
    // place, so welding gives a closed mesh.
    MeshBuilder builder(float(t.chord * 0.01));
    for (TopExp_Explorer e(*shape_, TopAbs_FACE); e.More(); e.Next()) {
        const TopoDS_Face& face = TopoDS::Face(e.Current());
        TopLoc_Location loc;
        Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, loc);
        if (tri.IsNull()) continue;
        const gp_Trsf& trsf = loc.Transformation();
        bool reversed = face.Orientation() == TopAbs_REVERSED;
        for (int i = 1; i <= tri->NbTriangles(); ++i) {
            int n[3];
            tri->Triangle(i).Get(n[0], n[1], n[2]);
            std::array<float, 3> p[3];
            for (int k = 0; k < 3; ++k) {
                gp_Pnt q = tri->Node(n[k]).Transformed(trsf);
                p[k] = {float(q.X()), float(q.Y()), float(q.Z())};
            }
            if (reversed) builder.triangle(p[0], p[2], p[1]);
            else builder.triangle(p[0], p[1], p[2]);
        }
    }
    return builder.take();
}

DisplayMesh Solid::display(const Tessellation& t) const {
    BRepMesh_IncrementalMesh(*shape_, t.chord, false, t.angle, false);
    DisplayMesh d;
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(*shape_, TopAbs_FACE, faces);
    d.faceCount = uint32_t(faces.Extent());
    for (int f = 1; f <= faces.Extent(); ++f) {
        const TopoDS_Face& face = TopoDS::Face(faces(f));
        TopLoc_Location loc;
        Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, loc);
        if (tri.IsNull()) continue;
        if (!tri->HasNormals()) tri->ComputeNormals();
        const gp_Trsf& trsf = loc.Transformation();
        bool reversed = face.Orientation() == TopAbs_REVERSED;
        uint32_t base = uint32_t(d.vertexCount());
        for (int i = 1; i <= tri->NbNodes(); ++i) {
            gp_Pnt p = tri->Node(i).Transformed(trsf);
            gp_Dir n = tri->Normal(i).Transformed(trsf);
            if (reversed) n.Reverse();
            d.positions.insert(d.positions.end(), {float(p.X()), float(p.Y()), float(p.Z())});
            d.normals.insert(d.normals.end(), {float(n.X()), float(n.Y()), float(n.Z())});
            d.faceOfVertex.push_back(uint32_t(f - 1));
        }
        for (int i = 1; i <= tri->NbTriangles(); ++i) {
            int a, b, c;
            tri->Triangle(i).Get(a, b, c);
            if (reversed) std::swap(b, c);
            d.indices.insert(d.indices.end(), {base + a - 1, base + b - 1, base + c - 1});
        }
    }

    // Each edge's points, from its polygon on the triangulation of one of its faces.
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(*shape_, TopAbs_EDGE, edges);
    TopTools_IndexedDataMapOfShapeListOfShape edgeFaces;
    TopExp::MapShapesAndAncestors(*shape_, TopAbs_EDGE, TopAbs_FACE, edgeFaces);
    d.edges.resize(size_t(edges.Extent()));
    for (int e = 1; e <= edges.Extent(); ++e) {
        const TopoDS_Edge& edge = TopoDS::Edge(edges(e));
        const TopTools_ListOfShape& owners = edgeFaces.FindFromKey(edge);
        // Seams (where a surface wraps round onto itself) and collapsed edges
        // aren't edges anyone sees; they keep their number with no points.
        if (owners.IsEmpty() || BRep_Tool::Degenerated(edge) || BRep_Tool::IsClosed(edge, TopoDS::Face(owners.First())))
            continue;
        TopLoc_Location loc;
        Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(TopoDS::Face(owners.First()), loc);
        if (tri.IsNull()) continue;
        Handle(Poly_PolygonOnTriangulation) poly = BRep_Tool::PolygonOnTriangulation(edge, tri, loc);
        if (poly.IsNull()) continue;
        const gp_Trsf& trsf = loc.Transformation();
        auto& points = d.edges[size_t(e - 1)].points;
        for (int i = 1; i <= poly->NbNodes(); ++i) {
            gp_Pnt p = tri->Node(poly->Node(i)).Transformed(trsf);
            points.insert(points.end(), {float(p.X()), float(p.Y()), float(p.Z())});
        }
    }
    return d;
}

std::vector<double> Solid::facePlane(uint32_t face) const {
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(*shape_, TopAbs_FACE, faces);
    if (face >= uint32_t(faces.Extent())) return {};
    const TopoDS_Face& f = TopoDS::Face(faces(int(face) + 1));
    BRepAdaptor_Surface surface(f);
    if (surface.GetType() != GeomAbs_Plane) return {};
    GProp_GProps props;
    BRepGProp::SurfaceProperties(f, props);
    gp_Pnt c = props.CentreOfMass();
    gp_Dir n = surface.Plane().Axis().Direction();
    if (f.Orientation() == TopAbs_REVERSED) n.Reverse();
    return {c.X(), c.Y(), c.Z(), n.X(), n.Y(), n.Z()};
}

double Solid::volume() const {
    GProp_GProps props;
    BRepGProp::VolumeProperties(*shape_, props);
    return props.Mass();
}

}  // namespace pm
