#include "solid/solid.h"

#include <BRepBuilderAPI_MakeShape.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepGProp.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRep_Tool.hxx>
#include <GProp_GProps.hxx>
#include <Poly_Triangulation.hxx>
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

double Solid::volume() const {
    GProp_GProps props;
    BRepGProp::VolumeProperties(*shape_, props);
    return props.Mass();
}

}  // namespace pm
