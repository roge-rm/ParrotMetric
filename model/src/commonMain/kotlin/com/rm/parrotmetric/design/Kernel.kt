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
    fun fillet(id: Int, body: Long, edges: List<String>, radius: Double): Long
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
    fun convertToSolid(id: Int, body: Long): Long
    /** The middle of a body. */
    fun centre(body: Long): Vec3
    /** A simple solid standing on a plane at (u, v); kind is PrimitiveKind's ordinal, sizes as PrimitiveFeature has them. */
    fun primitive(id: Int, plane: SketchPlane, kind: Int, u: Double, v: Double, a: Double, b: Double, c: Double): Long
    /** The box round a body: x, y, z low, then high. */
    fun bounds(body: Long): DoubleArray
    /** A body's volume (mm³), surface area (mm²) and centre of mass x, y, z. */
    fun properties(body: Long): DoubleArray? = null
    /** A body cut where another body's surface passes through it; the tool is left as it is. */
    fun splitBy(id: Int, body: Long, tool: Long): List<Long>
    /** How much two bodies overlap, mm³. */
    fun overlapVolume(a: Long, b: Long): Double = 0.0
    /** Where a named corner of a body is (PointRef.Corner), or null if it hasn't got it. */
    fun corner(body: Long, name: String): Vec3? = null
    /**
     * What shape a named edge or face is: kind, then a point, a direction
     * and a size. 0 a straight edge (start, along it, length); 1 a round edge
     * (centre, axis, radius); 2 a cylinder or cone (a point on its axis, the
     * axis, radius); 3 a sphere (centre, -, radius); 4 a flat face (middle,
     * normal, 0). Null if it's none of these or the body hasn't got it.
     */
    fun shapeOf(body: Long, name: String, edge: Boolean): DoubleArray? = null
    /** The point a fraction t along a named edge, then the edge's direction there; null if the body hasn't got it. */
    fun alongEdge(body: Long, name: String, t: Double): DoubleArray? = null
    /** Sketch areas swept along a path. */
    fun sweep(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, path: KernelPath): Long
    /** A round tube along a path; hollow when inner (a diameter) is more than 0. */
    fun pipe(id: Int, path: KernelPath, diameter: Double, inner: Double): Long
    fun coil(id: Int, plane: SketchPlane, u: Double, v: Double, diameter: Double, pitch: Double, turns: Double, section: Double, square: Boolean): Long
    /** The body with an ISO metric thread cut into a round face. */
    fun thread(id: Int, body: Long, face: String, pitch: Double): Long
    /** A solid through one area of each sketch, in order. */
    fun loft(id: Int, sections: List<Triple<SketchPlane, List<ProfileCurve>, RegionRef>>, ruled: Boolean): Long
    fun retain(body: Long)
    fun release(body: Long)
    /** Where a named face (or edge) of a body is and how big, to find it again by shape; null if the body hasn't got it. */
    fun signature(body: Long, name: String, edge: Boolean): DoubleArray? = null
    /** The face or edge of a body most like a signature, or null if none is close enough to be the same one. */
    fun relocate(body: Long, signature: DoubleArray): String? = null
}
