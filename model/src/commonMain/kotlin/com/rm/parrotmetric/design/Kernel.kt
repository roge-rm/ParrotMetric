package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.SketchPlane

/** A failure the person can act on, with a short reason fit to show them. */
class KernelException(message: String) : Exception(message)

/**
 * The geometry, done by the platform (the C++ core). Bodies are handles; each
 * call that makes one returns it retained once, and [release] lets it go.
 * Calls that can't be done throw [KernelException].
 */
interface Kernel {
    fun extrude(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, forward: Double, back: Double): Long
    fun revolve(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long
    /** how is Join, Cut or Intersect. */
    fun combine(id: Int, target: Long, tool: Long, how: Operation): Long
    fun fillet(id: Int, body: Long, edges: List<String>, radius: Double): Long
    fun chamfer(id: Int, body: Long, edges: List<String>, distance: Double): Long
    fun overlaps(a: Long, b: Long): Boolean
    /** A named flat face's centre and outward normal, or null if this body hasn't got it. */
    fun facePlane(body: Long, face: String): DoubleArray?
    fun faceNames(body: Long): List<String>
    fun import(id: Int, data: ByteArray, format: Int): Long
    fun retain(body: Long)
    fun release(body: Long)
}
