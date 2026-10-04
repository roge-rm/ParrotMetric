package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3

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
    fun retain(body: Long)
    fun release(body: Long)
    /** Where a named face (or edge) of a body is and how big, to find it again by shape; null if the body hasn't got it. */
    fun signature(body: Long, name: String, edge: Boolean): DoubleArray? = null
    /** The face or edge of a body most like a signature, or null if none is close enough to be the same one. */
    fun relocate(body: Long, signature: DoubleArray): String? = null
}
