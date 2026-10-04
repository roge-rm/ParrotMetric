package com.rm.parrotmetric.sketch

import kotlin.math.atan2

/** A closed area of a sketch, found by the geometry kernel. Loops are x, y pairs; the outline first, then holes. */
class SketchRegion(val loops: List<FloatArray>, val curveIds: List<Int>, val area: Double)

/** Finds a sketch's closed regions. The platform supplies it, from the C++ core. */
fun interface RegionFinder {
    fun find(curves: List<ProfileCurve>): List<SketchRegion>
}

/** A curve as the region finder takes it: a line from (x1, y1) to (x2, y2), or a circle or arc round (x1, y1). */
class ProfileCurve(
    val kind: Kind, val id: Int,
    val x1: Double, val y1: Double, val x2: Double = 0.0, val y2: Double = 0.0,
    val r: Double = 0.0, val a0: Double = 0.0, val a1: Double = 0.0,
) {
    enum class Kind { Line, Circle, Arc }
}

/** The sketch's curves that make up profiles: all but construction curves. */
fun Sketch.profileCurves(): List<ProfileCurve> = curves.filter { !it.construction }.map { c ->
    when (c) {
        is Line -> ProfileCurve(ProfileCurve.Kind.Line, c.id, x(c.a), y(c.a), x(c.b), y(c.b))
        is Circle -> ProfileCurve(ProfileCurve.Kind.Circle, c.id, x(c.centre), y(c.centre), r = radius(c))
        is Arc -> ProfileCurve(
            ProfileCurve.Kind.Arc, c.id, x(c.centre), y(c.centre), r = radius(c),
            a0 = atan2(y(c.start) - y(c.centre), x(c.start) - x(c.centre)),
            a1 = atan2(y(c.end) - y(c.centre), x(c.end) - x(c.centre)),
        )
    }
}
