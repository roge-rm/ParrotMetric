package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3

/** A path for the kernel: curves on a sketch plane when there are any, else named edges of a body. */
class KernelPath(val plane: SketchPlane?, val curves: List<ProfileCurve>, val body: Long, val edges: List<String>)

/** A failure the person can act on, with a short reason fit to show them. */
class KernelException(message: String) : Exception(message)

/**
 * The geometry, done by the platform (the C++ core). Bodies are handles; each
 * call that makes one returns it retained once, and [release] lets it go.
 * Calls that can't be done throw [KernelException].
 */
interface Kernel {
    /**
     * taper in radians leans the sides in going forward, pivoting at the
     * plane; thin (mm, 0 for solid) keeps only a wall that thick inside the
     * areas' edges.
     */
    fun extrude(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, forward: Double, back: Double, taper: Double = 0.0, thin: Double = 0.0): Long
    fun revolve(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long
    /** how is Join, Cut or Intersect. */
    fun combine(id: Int, target: Long, tool: Long, how: Operation): Long
    /** kind is FilletKind's ordinal; second is the end radius of a variable fillet. */
    fun fillet(id: Int, body: Long, edges: List<String>, radius: Double, kind: Int = 0, second: Double = 0.0): Long
    /** The body with faces moved along their normals, out when distance is more than 0. */
    fun offsetFaces(id: Int, body: Long, faces: List<String>, distance: Double): Long = throw KernelException("Not here")
    /** A mesh body changed as MeshEditFeature says; kind is MeshEdit's ordinal. A solid is made a mesh first. */
    fun meshEdit(id: Int, body: Long, kind: Int, size: Double, steps: Int): Long = throw KernelException("Not here")
    /** A Sculpt step's body: as saved, or made again on [input] (the body it was made from, 0 for none) if that changed since. */
    fun sculptedBody(id: Int, packed: ByteArray, input: Long): Long = throw KernelException("Not here")
    /** A rib or web from open curves, grown until it meets the body and joined to it; see RibFeature. */
    fun rib(id: Int, body: Long, plane: SketchPlane, curves: List<ProfileCurve>, thickness: Double, flip: Boolean, web: Boolean): Long = throw KernelException("Not here")
    /** Sketch areas projected onto a face of the body along the sketch's normal, raised or sunk; see EmbossFeature. */
    fun emboss(id: Int, body: Long, face: String, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, depth: Double, sink: Boolean): Long =
        throw KernelException("Not here")
    /** Sketch areas as flat surfaces. */
    fun patch(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>): Long = throw KernelException("Not here")
    /** A surface filling a closed loop of a body's edges. */
    fun patchEdges(id: Int, body: Long, edges: List<String>): Long = throw KernelException("Not here")
    /** Surfaces sewn into one, a solid if they close round. */
    fun stitch(id: Int, bodies: List<Long>): Long = throw KernelException("Not here")
    /** Several shapes held as one, to use as one tool; null where a kernel can't. */
    fun gather(bodies: List<Long>): Long? = null
    /** A surface made thick: to one side, or both. */
    fun thicken(id: Int, body: Long, thickness: Double, both: Boolean): Long = throw KernelException("Not here")
    /** The body with faces taken away and the gap closed. */
    fun deleteFaces(id: Int, body: Long, faces: List<String>): Long = throw KernelException("Not here")
    /** Where copies go along a path, 3x4 matrices from its start, the first being the start; see PatternFeature. */
    fun pathPlaces(path: KernelPath, count: Int, spacing: Double, turn: Boolean, reverse: Boolean = false): List<DoubleArray> = throw KernelException("Not here")
    /** kind is ChamferKind's ordinal; second is a distance or an angle in radians; see ChamferFeature. */
    fun chamfer(id: Int, body: Long, edges: List<String>, distance: Double, kind: Int = 0, second: Double = 0.0, flip: Boolean = false): Long
    fun overlaps(a: Long, b: Long): Boolean
    /** A named flat face's centre and outward normal, or null if this body hasn't got it. */
    fun facePlane(body: Long, face: String): DoubleArray?
    fun faceNames(body: Long): List<String>
    fun import(id: Int, data: ByteArray, format: Int): Long
    fun shell(id: Int, body: Long, open: List<String>, thickness: Double): Long
    fun draft(id: Int, body: Long, faces: List<String>, neutral: String, angle: Double): Long
    /** A moved copy, solid or mesh. m is 3x4, rows of rotation then translation. */
    fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long
    /** The pieces either side of a plane through origin. */
    fun split(id: Int, body: Long, origin: Vec3, normal: Vec3): List<Long>
    /** The shape holes at these plane points take out; kind is HoleKind's ordinal. */
    fun holeTool(id: Int, plane: SketchPlane, at: List<Pair<Double, Double>>, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double): Long
    /**
     * Snap-fit clips standing out of a plane at points (u, v) on it, hooks pointing away from
     * [middle]; with [catchPart], the recesses they rest in. Faces F<id>.<tag><n>.
     */
    fun snapFitTool(id: Int, plane: SketchPlane, at: List<Pair<Double, Double>>, middle: Vec3, sizes: SnapFitSizes, catchPart: Boolean, tag: String): Long =
        throw KernelException("Not here")
    fun convertToSolid(id: Int, body: Long): Long
    /** The middle of a body. */
    fun centre(body: Long): Vec3
    /** A simple solid standing on a plane at (u, v); kind is PrimitiveKind's ordinal, sizes as PrimitiveFeature has them. */
    fun primitive(id: Int, plane: SketchPlane, kind: Int, u: Double, v: Double, a: Double, b: Double, c: Double): Long
    /** The box round a body: x, y, z low, then high. */
    fun bounds(body: Long): DoubleArray
    /** A body's volume (mm³), surface area (mm²) and centre of mass x, y, z. */
    fun properties(body: Long): DoubleArray? = null
    /** A mesh body with the triangles near each spot (x, y, z and radius, four numbers each) taken away and the holes filled. */
    fun meshErase(id: Int, body: Long, spots: List<Double>): Long = throw KernelException("Not here")
    /**
     * Open sketch lines made a surface: extruded [forward] and [back] mm, or with [revolve] turned [angle]
     * radians round the line through (axis[0], axis[1]) along (axis[2], axis[3]). Faces are F<id>.s<curve id>.
     */
    fun surfaceFromLines(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, revolve: Boolean, forward: Double, back: Double, axis: List<Double>, angle: Double): Long =
        throw KernelException("Not here")
    /** A copy of a body's faces moved [distance] mm out along their normals, as a new surface. */
    fun offsetSurface(id: Int, body: Long, faces: List<String>, distance: Double): Long = throw KernelException("Not here")
    /** The body with [faces] moved onto [targetFace] of the body [target], grown or cut back to meet it; see ReplaceFaceFeature. */
    fun replaceFaces(id: Int, body: Long, faces: List<String>, target: Long, targetFace: String): Long = throw KernelException("Not here")
    /** Each separate piece of a body, biggest first; throws if it's all one. */
    fun separate(id: Int, body: Long): List<Long> = throw KernelException("Not here")
    /** A body cut where another body's surface passes through it; the tool is left as it is. */
    fun splitBy(id: Int, body: Long, tool: Long): List<Long>
    /** How much two bodies overlap, mm³. */
    fun overlapVolume(a: Long, b: Long): Double = 0.0
    /** Where bodies cross a plane, flattened onto it; null where that can't be worked out here. */
    fun section(bodies: List<Long>, plane: com.rm.parrotmetric.sketch.SketchPlane): List<com.rm.parrotmetric.sketch.ProfileCurve>? = null
    /**
     * A drawing's view of the solid bodies, looking back along [towards] with [right] across: the
     * curves seen, then those hidden (only when asked), in the view's x, y, mm. Null where it can't.
     * [fast] above 0 works from triangles at that tolerance, mm: much quicker for threads and knurls;
     * 0 is the exact way, and below 0 lets the core choose.
     */
    fun projectView(bodies: List<Long>, towards: com.rm.parrotmetric.sketch.Vec3, right: com.rm.parrotmetric.sketch.Vec3, hidden: Boolean, fast: Double = -1.0):
        Pair<List<com.rm.parrotmetric.sketch.ProfileCurve>, List<com.rm.parrotmetric.sketch.ProfileCurve>>? = null
    /** A named face's edges flattened onto a plane; null where that can't be worked out here. */
    fun faceOutline(body: Long, face: String, plane: com.rm.parrotmetric.sketch.SketchPlane): List<com.rm.parrotmetric.sketch.ProfileCurve>? = null
    /** Where a named corner of a body is (PointRef.Corner), or null if it hasn't got it. */
    fun corner(body: Long, name: String): Vec3? = null
    /**
     * What shape a named edge or face is: kind, then a point, a direction
     * and a size. 0 a straight edge (start, along it, length); 1 a round edge
     * (centre, axis, radius); 2 a cylinder or cone (a point on its axis, the
     * axis, radius, 1 if it's a hole, where it starts and ends along the axis
     * from that point, then 1 if it's a cone); 3 a sphere (centre, -, radius); 4 a
     * flat face (middle, normal, 0). Null if it's none of these or the body hasn't got it.
     */
    fun shapeOf(body: Long, name: String, edge: Boolean): DoubleArray? = null
    /** The point a fraction t along a named edge, then the edge's direction there; null if the body hasn't got it. */
    fun alongEdge(body: Long, name: String, t: Double): DoubleArray? = null
    /** Sketch areas swept along a path. */
    fun sweep(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, path: KernelPath): Long
    /** A round tube along a path; hollow when inner (a diameter) is more than 0. */
    fun pipe(id: Int, path: KernelPath, diameter: Double, inner: Double): Long
    fun gear(id: Int, plane: SketchPlane, u: Double, v: Double, turn: Double, module: Double, teeth: Int, pressureAngle: Double, thickness: Double,
             helix: Double, herringbone: Boolean, bore: Double, clearance: Double): Long
    /** A screw, nut or washer standing on [seat] (see pm::fastener); [kind] is FastenerKind's ordinal. */
    fun fastener(id: Int, seat: SketchPlane, kind: Int, d: Double, length: Double, head: Double, headHeight: Double, socket: Double, angle: Double): Long =
        throw KernelException("Not here")
    fun coil(id: Int, plane: SketchPlane, u: Double, v: Double, diameter: Double, pitch: Double, turns: Double, section: Double, square: Boolean): Long
    /** The body with an ISO metric thread cut into a round face. */
    /** [clearance] moves the face away from the mating part first: a hole wider, a shaft thinner. */
    fun thread(id: Int, body: Long, face: String, pitch: Double, clearance: Double = 0.0): Long
    /**
     * Rings round the openings in a flat rim face, standing [height] out of it, from
     * [inside] to [outside] mm out from each opening's edge. Faces are F<id>.<tag><n>.
     */
    fun lipTool(id: Int, body: Long, face: String, inside: Double, outside: Double, height: Double, tag: String): Long
    /** A solid through one area of each sketch, in order. */
    fun loft(id: Int, sections: List<Triple<SketchPlane, List<ProfileCurve>, RegionRef>>, ruled: Boolean, twist: Double = 0.0, guide: KernelPath? = null): Long
    fun retain(body: Long)
    fun release(body: Long)
    /** Where a named face (or edge) of a body is and how big, to find it again by shape; null if the body hasn't got it. */
    fun signature(body: Long, name: String, edge: Boolean): DoubleArray? = null
    /** The face or edge of a body most like a signature, or null if none is close enough to be the same one. */
    fun relocate(body: Long, signature: DoubleArray): String? = null
}
