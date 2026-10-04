package com.rm.parrotmetric.sketch

import kotlin.math.atan2

/**
 * A closed area of a sketch, found by the geometry kernel. Loops are x, y
 * pairs; the outline first, then holes. (insideU, insideV) is a point in it.
 */
class SketchRegion(val loops: List<FloatArray>, val curveIds: List<Int>, val area: Double, val insideU: Double, val insideV: Double)

/** Finds a sketch's closed regions. The platform supplies it, from the C++ core. */
fun interface RegionFinder {
    fun find(curves: List<ProfileCurve>): List<SketchRegion>
}

/** A curve as the region finder takes it: a line from (x1, y1) to (x2, y2), or a circle or arc round (x1, y1). */
data class ProfileCurve(
    val kind: Kind, val id: Int,
    val x1: Double, val y1: Double, val x2: Double = 0.0, val y2: Double = 0.0,
    val r: Double = 0.0, val a0: Double = 0.0, val a1: Double = 0.0,
    val cx1: Double = 0.0, val cy1: Double = 0.0, val cx2: Double = 0.0, val cy2: Double = 0.0,
) {
    /** Bezier: a cubic piece of a spline, from (x1, y1) to (x2, y2) with controls (cx1, cy1) and (cx2, cy2). */
    enum class Kind { Line, Circle, Arc, Bezier }
}

/** The sketch's curves that make up profiles: all but construction curves. A spline gives one Bezier per piece. */
fun Sketch.profileCurves(): List<ProfileCurve> = curves.filter { !it.construction }.flatMap { c ->
    if (c is Spline) return@flatMap bezierPieces(c).map { b ->
        ProfileCurve(ProfileCurve.Kind.Bezier, c.id, b[0], b[1], b[6], b[7], cx1 = b[2], cy1 = b[3], cx2 = b[4], cy2 = b[5])
    }
    listOf(when (c) {
        is Line -> ProfileCurve(ProfileCurve.Kind.Line, c.id, x(c.a), y(c.a), x(c.b), y(c.b))
        is Circle -> ProfileCurve(ProfileCurve.Kind.Circle, c.id, x(c.centre), y(c.centre), r = radius(c))
        is Arc -> ProfileCurve(
            ProfileCurve.Kind.Arc, c.id, x(c.centre), y(c.centre), r = radius(c),
            a0 = atan2(y(c.start) - y(c.centre), x(c.start) - x(c.centre)),
            a1 = atan2(y(c.end) - y(c.centre), x(c.end) - x(c.centre)),
        )
        is Spline -> error("handled above")
    })
}
