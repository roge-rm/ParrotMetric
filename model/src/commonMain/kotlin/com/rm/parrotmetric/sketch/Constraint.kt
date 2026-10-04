package com.rm.parrotmetric.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * An equation the sketch has to meet. Each one gives residuals, which are
 * zero when it holds; lengths are in millimetres and angles in radians.
 */
sealed class Constraint {
    /** The points it involves, for showing it and for removing it with them. */
    abstract fun points(): List<Point>
    open fun curves(): List<Curve> = emptyList()
    internal abstract fun residuals(v: (Int) -> Double): DoubleArray

    /** Every value slot the residuals read. */
    internal fun slots(): IntArray {
        val s = mutableListOf<Int>()
        for (p in points()) { s += p.x; s += p.y }
        for (c in curves()) if (c is Circle) s += c.r
        return s.distinct().toIntArray()
    }

    /** A constraint with a number you can change: a dimension. */
    sealed class Dimension : Constraint() {
        abstract var value: Double
    }

    // Shorthands for reading positions.
    protected fun Point.px(v: (Int) -> Double) = v(x)
    protected fun Point.py(v: (Int) -> Double) = v(y)

    class Coincident(val p: Point, val q: Point) : Constraint() {
        override fun points() = listOf(p, q)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(p.px(v) - q.px(v), p.py(v) - q.py(v))
    }

    class Horizontal(val line: Line) : Constraint() {
        override fun points() = line.points()
        override fun curves() = listOf(line)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(line.a.py(v) - line.b.py(v))
    }

    class Vertical(val line: Line) : Constraint() {
        override fun points() = line.points()
        override fun curves() = listOf(line)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(line.a.px(v) - line.b.px(v))
    }

    /** Two points level with each other. */
    class HorizontalPoints(val p: Point, val q: Point) : Constraint() {
        override fun points() = listOf(p, q)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(p.py(v) - q.py(v))
    }

    /** Two points one above the other. */
    class VerticalPoints(val p: Point, val q: Point) : Constraint() {
        override fun points() = listOf(p, q)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(p.px(v) - q.px(v))
    }

    class Parallel(val l1: Line, val l2: Line) : Constraint() {
        override fun points() = l1.points() + l2.points()
        override fun curves() = listOf(l1, l2)
        override fun residuals(v: (Int) -> Double): DoubleArray {
            val (ax, ay) = dir(l1, v); val (bx, by) = dir(l2, v)
            return doubleArrayOf(ax * by - ay * bx)
        }
    }

    class Perpendicular(val l1: Line, val l2: Line) : Constraint() {
        override fun points() = l1.points() + l2.points()
        override fun curves() = listOf(l1, l2)
        override fun residuals(v: (Int) -> Double): DoubleArray {
            val (ax, ay) = dir(l1, v); val (bx, by) = dir(l2, v)
            return doubleArrayOf(ax * bx + ay * by)
        }
    }

    /** Two lines the same length, or two circles or arcs the same radius. */
    class Equal(val c1: Curve, val c2: Curve) : Constraint() {
        override fun points() = c1.points() + c2.points()
        override fun curves() = listOf(c1, c2)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(size(c1, v) - size(c2, v))
    }

    /** A point held where it is. */
    class Fixed(val p: Point, val atX: Double, val atY: Double) : Constraint() {
        override fun points() = listOf(p)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(p.px(v) - atX, p.py(v) - atY)
    }

    class Midpoint(val p: Point, val line: Line) : Constraint() {
        override fun points() = listOf(p) + line.points()
        override fun curves() = listOf(line)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(
            p.px(v) - (line.a.px(v) + line.b.px(v)) / 2,
            p.py(v) - (line.a.py(v) + line.b.py(v)) / 2,
        )
    }

    /** A point on a line, or on the infinite line through it. */
    class OnLine(val p: Point, val line: Line) : Constraint() {
        override fun points() = listOf(p) + line.points()
        override fun curves() = listOf(line)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(signedDistance(p, line, v))
    }

    /** A point on a circle or arc. */
    class OnCircle(val p: Point, val curve: Curve) : Constraint() {
        override fun points() = listOf(p) + curve.points()
        override fun curves() = listOf(curve)
        override fun residuals(v: (Int) -> Double): DoubleArray {
            val c = centre(curve)
            return doubleArrayOf(hypot(p.px(v) - c.px(v), p.py(v) - c.py(v)) - size(curve, v))
        }
    }

    /** A line touching a circle or arc. */
    class TangentLine(val line: Line, val curve: Curve) : Constraint() {
        override fun points() = line.points() + curve.points()
        override fun curves() = listOf(line, curve)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(abs(signedDistance(centre(curve), line, v)) - size(curve, v))
    }

    /** Two circles or arcs touching, from outside or (inside = true) one within the other. */
    class TangentCircles(val c1: Curve, val c2: Curve, val inside: Boolean) : Constraint() {
        override fun points() = c1.points() + c2.points()
        override fun curves() = listOf(c1, c2)
        override fun residuals(v: (Int) -> Double): DoubleArray {
            val a = centre(c1); val b = centre(c2)
            val d = hypot(a.px(v) - b.px(v), a.py(v) - b.py(v))
            val r1 = size(c1, v); val r2 = size(c2, v)
            return doubleArrayOf(if (inside) d - abs(r1 - r2) else d - (r1 + r2))
        }
    }

    /** Two points mirrored across a line. */
    class Symmetric(val p: Point, val q: Point, val line: Line) : Constraint() {
        override fun points() = listOf(p, q) + line.points()
        override fun curves() = listOf(line)
        override fun residuals(v: (Int) -> Double): DoubleArray {
            val (dx, dy) = dir(line, v)
            val mx = (p.px(v) + q.px(v)) / 2; val my = (p.py(v) + q.py(v)) / 2
            val ax = line.a.px(v); val ay = line.a.py(v)
            return doubleArrayOf(
                dx * (my - ay) - dy * (mx - ax),
                (q.px(v) - p.px(v)) * dx + (q.py(v) - p.py(v)) * dy,
            )
        }
    }

    /** An arc's start and end at the same distance from its centre. Added with every arc. */
    class ArcRadius internal constructor(val arc: Arc) : Constraint() {
        override fun points() = arc.points()
        override fun curves() = listOf(arc)
        override fun residuals(v: (Int) -> Double): DoubleArray {
            val c = arc.centre
            return doubleArrayOf(
                hypot(arc.start.px(v) - c.px(v), arc.start.py(v) - c.py(v)) -
                    hypot(arc.end.px(v) - c.px(v), arc.end.py(v) - c.py(v)),
            )
        }
    }

    /** The straight distance between two points. */
    class Distance(val p: Point, val q: Point, override var value: Double) : Dimension() {
        override fun points() = listOf(p, q)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(hypot(q.px(v) - p.px(v), q.py(v) - p.py(v)) - value)
    }

    /** A line's length. */
    class Length(val line: Line, override var value: Double) : Dimension() {
        override fun points() = line.points()
        override fun curves() = listOf(line)
        override fun residuals(v: (Int) -> Double) =
            doubleArrayOf(hypot(line.b.px(v) - line.a.px(v), line.b.py(v) - line.a.py(v)) - value)
    }

    /** How far apart two points are along x (q right of p by value), or along y with vertical = true. */
    class AxisDistance(val p: Point, val q: Point, val vertical: Boolean, override var value: Double) : Dimension() {
        override fun points() = listOf(p, q)
        override fun residuals(v: (Int) -> Double) =
            doubleArrayOf(if (vertical) q.py(v) - p.py(v) - value else q.px(v) - p.px(v) - value)
    }

    /** How far a point is from the line through a line. */
    class PointLineDistance(val p: Point, val line: Line, override var value: Double) : Dimension() {
        override fun points() = listOf(p) + line.points()
        override fun curves() = listOf(line)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(abs(signedDistance(p, line, v)) - value)
    }

    /** A circle's or arc's radius, shown as a diameter if diameter = true. The value is what's shown. */
    class Radius(val curve: Curve, val diameter: Boolean, override var value: Double) : Dimension() {
        override fun points() = curve.points()
        override fun curves() = listOf(curve)
        override fun residuals(v: (Int) -> Double) = doubleArrayOf(size(curve, v) * (if (diameter) 2 else 1) - value)
    }

    /** The angle in radians turning from l1's direction to l2's, anticlockwise. */
    class Angle(val l1: Line, val l2: Line, override var value: Double) : Dimension() {
        override fun points() = l1.points() + l2.points()
        override fun curves() = listOf(l1, l2)
        override fun residuals(v: (Int) -> Double): DoubleArray {
            val (ax, ay) = dir(l1, v); val (bx, by) = dir(l2, v)
            var d = atan2(ax * by - ay * bx, ax * bx + ay * by) - value
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            return doubleArrayOf(d)
        }
    }

    internal companion object {
        /** A line's unit direction; (1, 0) if it has no length. */
        fun dir(l: Line, v: (Int) -> Double): Pair<Double, Double> {
            val dx = v(l.b.x) - v(l.a.x); val dy = v(l.b.y) - v(l.a.y)
            val len = hypot(dx, dy)
            return if (len < 1e-12) 1.0 to 0.0 else dx / len to dy / len
        }

        /** Distance of p from the line through l, positive on its left. */
        fun signedDistance(p: Point, l: Line, v: (Int) -> Double): Double {
            val dx = v(l.b.x) - v(l.a.x); val dy = v(l.b.y) - v(l.a.y)
            val len = max(hypot(dx, dy), 1e-12)
            return (dx * (v(p.y) - v(l.a.y)) - dy * (v(p.x) - v(l.a.x))) / len
        }

        fun centre(c: Curve): Point = when (c) {
            is Circle -> c.centre
            is Arc -> c.centre
            is Line -> c.a
            is Spline -> c.through.first()
        }

        /** A line's length, or a circle's or arc's radius. */
        fun size(c: Curve, v: (Int) -> Double): Double = when (c) {
            is Line -> hypot(v(c.b.x) - v(c.a.x), v(c.b.y) - v(c.a.y))
            is Circle -> v(c.r)
            is Arc -> hypot(v(c.start.x) - v(c.centre.x), v(c.start.y) - v(c.centre.y))
            is Spline -> 0.0
        }
    }
}
