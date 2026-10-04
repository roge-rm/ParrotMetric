package com.rm.parrotmetric.ui.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// How each drawing tool can draw, and the geometry each way needs, shared
// by drawing a shape and showing it before it's placed.

enum class RectangleStyle(val label: String) { Corners("Corner to corner"), Centre("From the centre"), ThreePoints("Three points") }
enum class CircleStyle(val label: String) { Centre("Centre"), TwoPoints("Two points"), ThreePoints("Three points") }
enum class ArcStyle(val label: String) { CentreEnds("Centre, then ends"), ThreePoints("Three points"), Tangent("Tangent") }
enum class PolygonStyle(val label: String) { Inside("Corners on the circle"), Outside("Sides on the circle") }
enum class SlotStyle(val label: String) { Centres("Centre to centre"), Overall("End to end"), Middle("From the middle") }

/** The next of an enum's values, round to the first. */
internal inline fun <reified T : Enum<T>> T.next(): T = enumValues<T>().let { it[(ordinal + 1) % it.size] }

/** A shape about to be drawn, in the sketch plane's mm, for showing it. */
sealed class Ghost {
    data class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Ghost()
    /** A circle; [faint] for one only to show where an arc will lie. */
    data class Ring(val cx: Double, val cy: Double, val r: Double, val faint: Boolean = false) : Ghost()
    /** An arc, anticlockwise from angle [a0] to [a1], radians. */
    data class Bow(val cx: Double, val cy: Double, val r: Double, val a0: Double, val a1: Double) : Ghost()
}

/** A closed polygon as segments. */
internal fun outline(points: List<Pair<Double, Double>>): List<Ghost> =
    points.indices.map { i -> val a = points[i]; val b = points[(i + 1) % points.size]; Ghost.Seg(a.first, a.second, b.first, b.second) }

/**
 * A rectangle from one side, (x1, y1) to (x2, y2), and a point giving how far
 * the opposite side is: its corners in order, or null if it has no size.
 */
internal fun rectangleFromSide(x1: Double, y1: Double, x2: Double, y2: Double, u: Double, v: Double): List<Pair<Double, Double>>? {
    val len = hypot(x2 - x1, y2 - y1)
    if (len < 1e-9) return null
    val nx = -(y2 - y1) / len; val ny = (x2 - x1) / len
    val w = (u - x1) * nx + (v - y1) * ny
    if (abs(w) < 1e-6) return null
    return listOf(x1 to y1, x2 to y2, (x2 + nx * w) to (y2 + ny * w), (x1 + nx * w) to (y1 + ny * w))
}

/**
 * The arc leaving (sx, sy) heading along (dx, dy) and ending at (ex, ey):
 * its centre, radius and whether it turns anticlockwise. Null if the end is
 * straight ahead, where only a line would do.
 */
internal fun tangentArc(sx: Double, sy: Double, dx: Double, dy: Double, ex: Double, ey: Double): Triple<Pair<Double, Double>, Double, Boolean>? {
    // The centre is on the normal at the start, as far from the end as from the start.
    val nx = -dy; val ny = dx
    val wx = ex - sx; val wy = ey - sy
    val across = 2 * (wx * nx + wy * ny)
    if (abs(across) < 1e-9) return null
    // Signed: positive turns left (anticlockwise).
    val r = (wx * wx + wy * wy) / across
    return Triple(sx + nx * r to sy + ny * r, abs(r), r > 0)
}

/** The angles of an arc from (ax, ay) anticlockwise to (bx, by) round a centre: a0, then a1 above it. */
internal fun arcAngles(cx: Double, cy: Double, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
    val a0 = atan2(ay - cy, ax - cx)
    var a1 = atan2(by - cy, bx - cx)
    while (a1 <= a0) a1 += 2 * PI
    return a0 to a1
}

/**
 * A regular polygon round (cx, cy): with its corners on a circle of radius
 * [r], or with its sides touching it when [outside]. [toward] is the angle of
 * a corner, or of the middle of a side when outside.
 */
internal fun polygonCorners(cx: Double, cy: Double, r: Double, toward: Double, n: Int, outside: Boolean): List<Pair<Double, Double>> {
    val reach = if (outside) r / cos(PI / n) else r
    val first = if (outside) toward + PI / n else toward
    return (0 until n).map { i -> val a = first + 2 * PI * i / n; (cx + reach * cos(a)) to (cy + reach * sin(a)) }
}

/**
 * A slot's two end centres and half its width, from the points placed and
 * where the width is set (u, v): for [SlotStyle.Centres] the two points are
 * the centres, for [SlotStyle.Overall] the far ends, for [SlotStyle.Middle]
 * the middle and one centre. Null if it has no size.
 */
internal fun slotCentres(style: SlotStyle, x1: Double, y1: Double, x2: Double, y2: Double, u: Double, v: Double): Triple<Pair<Double, Double>, Pair<Double, Double>, Double>? {
    val len = hypot(x2 - x1, y2 - y1)
    if (len < 1e-9) return null
    val dx = (x2 - x1) / len; val dy = (y2 - y1) / len
    val half = abs(dx * (v - y1) - dy * (u - x1))
    if (half < 1e-6) return null
    return when (style) {
        SlotStyle.Centres -> Triple(x1 to y1, x2 to y2, half)
        SlotStyle.Overall -> if (len <= 2 * half + 1e-6) null else Triple((x1 + dx * half) to (y1 + dy * half), (x2 - dx * half) to (y2 - dy * half), half)
        SlotStyle.Middle -> Triple((2 * x1 - x2) to (2 * y1 - y2), x2 to y2, half)
    }
}

/** A slot's outline: an arc round each centre and the two sides. */
internal fun slotGhost(a: Pair<Double, Double>, b: Pair<Double, Double>, half: Double): List<Ghost> {
    val len = hypot(b.first - a.first, b.second - a.second).coerceAtLeast(1e-9)
    val nx = -(b.second - a.second) / len * half; val ny = (b.first - a.first) / len * half
    val along = atan2(b.second - a.second, b.first - a.first)
    return listOf(
        Ghost.Seg(a.first + nx, a.second + ny, b.first + nx, b.second + ny),
        Ghost.Seg(a.first - nx, a.second - ny, b.first - nx, b.second - ny),
        Ghost.Bow(a.first, a.second, half, along + PI / 2, along + 3 * PI / 2),
        Ghost.Bow(b.first, b.second, half, along - PI / 2, along + PI / 2),
    )
}
