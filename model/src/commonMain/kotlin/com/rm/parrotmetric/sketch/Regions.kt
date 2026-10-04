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

/**
 * Text in a sketch: [outline] is the text at (0, 0) on its baseline, put at
 * [anchor] and turned [angle] radians round it.
 */
class SketchText(
    val id: Int,
    val anchor: Point,
    val text: String,
    val height: Double,
    val bold: Boolean,
    val angle: Double,
    val outline: List<ProfileCurve>,
)

/** A text's outline where it is in the sketch. Its curves are numbered apart from the sketch's own. */
fun Sketch.placedOutline(t: SketchText): List<ProfileCurve> {
    val ax = x(t.anchor); val ay = y(t.anchor)
    val c = kotlin.math.cos(t.angle); val s = kotlin.math.sin(t.angle)
    fun px(x: Double, y: Double) = ax + x * c - y * s
    fun py(x: Double, y: Double) = ay + x * s + y * c
    return t.outline.mapIndexed { k, p ->
        p.copy(
            id = textCurveBase + t.id * 10_000 + k,
            x1 = px(p.x1, p.y1), y1 = py(p.x1, p.y1), x2 = px(p.x2, p.y2), y2 = py(p.x2, p.y2),
            cx1 = px(p.cx1, p.cy1), cy1 = py(p.cx1, p.cy1), cx2 = px(p.cx2, p.cy2), cy2 = py(p.cx2, p.cy2),
        )
    }
}

/** Where text curves' numbers start, above any sketch curve's. */
const val textCurveBase = 1_000_000

/** The sketch's curves that make up profiles: all but construction curves, and its text. A spline gives one Bezier per piece. */
fun Sketch.profileCurves(): List<ProfileCurve> = ownProfileCurves() + texts.flatMap { placedOutline(it) }

private fun Sketch.ownProfileCurves(): List<ProfileCurve> = curves.filter { !it.construction }.flatMap { c ->
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
