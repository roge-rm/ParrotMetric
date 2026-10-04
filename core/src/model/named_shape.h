#pragma once

#include <NCollection_DataMap.hxx>
#include <TopTools_ShapeMapHasher.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>

#include <string>
#include <gp_Pnt.hxx>
#include <optional>
#include <vector>

class BRepBuilderAPI_MakeShape;

namespace pm {

/**
 * A solid whose faces carry names saying how they were made, such as
 * "F3.s12": the side Extrude 3 swept from sketch curve 12. Names are what
 * later features refer to, so they keep pointing at the right face or edge
 * after earlier features change. An edge's name is the names of the two
 * faces it joins, sorted, with "|" between.
 */
struct NamedShape {
    TopoDS_Shape shape;
    NCollection_DataMap<TopoDS_Shape, std::string, TopTools_ShapeMapHasher> names;

    std::string faceName(const TopoDS_Shape& face) const;
    std::string edgeName(const TopoDS_Edge& edge) const;

    /** Every edge with this name; more than one if a face was split. */
    std::vector<TopoDS_Edge> findEdges(const std::string& name) const;
    std::vector<TopoDS_Face> findFaces(const std::string& name) const;

    /** Names of every face and edge, in OCCT map order, as the display numbers them. */
    std::vector<std::string> faceNames() const;
    std::vector<std::string> edgeNames() const;
    /**
     * Names of every corner, in OCCT map order as the display numbers them:
     * the names of two edges that meet there, "a & b".
     */
    std::vector<std::string> cornerNames() const;
    /** Where a corner named by cornerNames is, if it's still there. */
    std::optional<gp_Pnt> findCorner(const std::string& name) const;
};

/**
 * Where a named face or edge is and how big, to find it again by shape when
 * an earlier change has taken its name away: kind (0 face, 1 edge), surface or
 * curve type, centre (x, y, z), direction (a flat face's outward normal or a
 * straight edge's direction; zero otherwise), size (area or length) and the
 * body's size (its box's diagonal). Empty if the body has no such name.
 */
std::vector<double> signatureOf(const NamedShape& body, const std::string& name, bool edge);

/**
 * The name of the face or edge in body most like a signature, or empty if
 * none is close enough to be the same one.
 */
std::string relocate(const NamedShape& body, const std::vector<double>& signature);

/**
 * Carries face names from the inputs of an OCCT operation to its result:
 * faces it kept keep their names, faces it changed pass theirs on. Faces
 * left without a name get "<prefix>.n<k>".
 */
NamedShape carryNames(const std::vector<const NamedShape*>& inputs, BRepBuilderAPI_MakeShape& op, const TopoDS_Shape& result,
                      const std::string& prefix);

}  // namespace pm
