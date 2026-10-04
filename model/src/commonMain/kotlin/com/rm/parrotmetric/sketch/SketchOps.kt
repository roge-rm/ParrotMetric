package com.rm.parrotmetric.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Editing tools that change the shape of what's drawn: trim, extend, offset
 * and corner fillet. Each returns null when it worked, or a short reason.
 * New points where curves cross are held on the curve that crossed.
 */
object SketchOps {
    private const val EPS = 1e-7

    /** Where another curve crosses this one: the place along this curve, the point, and the other curve. */
    private class Crossing(val at: Double, val u: Double, val v: Double, val other: Curve)

    // Places along a curve: a line from 0 at a to 1 at b; a circle by angle; an arc by angle turned from its start.

    private fun Sketch.pointAt(c: Curve, t: Double): Pair<Double, Double> = when (c) {
        is Line -> x(c.a) + t * (x(c.b) - x(c.a)) to y(c.a) + t * (y(c.b) - y(c.a))
        is Circle -> x(c.centre) + radius(c) * cos(t) to y(c.centre) + radius(c) * sin(t)
        is Arc -> {
            val a = startAngle(c) + t
            x(c.centre) + radius(c) * cos(a) to y(c.centre) + radius(c) * sin(a)
        }
        is Spline -> x(c.through.first()) to y(c.through.first())
    }

    private fun Sketch.startAngle(a: Arc) = atan2(y(a.start) - y(a.centre), x(a.start) - x(a.centre))

    private fun Sketch.span(a: Arc): Double {
        var s = atan2(y(a.end) - y(a.centre), x(a.end) - x(a.centre)) - startAngle(a)
        while (s <= 0) s += 2 * PI
        return s
    }

    /** The place along c nearest (u, v). */
    private fun Sketch.placeOf(c: Curve, u: Double, v: Double): Double = when (c) {
        is Line -> {
            val dx = x(c.b) - x(c.a); val dy = y(c.b) - y(c.a)
            ((u - x(c.a)) * dx + (v - y(c.a)) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-12)
        }
        is Circle -> atan2(v - y(c.centre), u - x(c.centre))
        is Arc -> {
            var t = atan2(v - y(c.centre), u - x(c.centre)) - startAngle(c)
            while (t < 0) t += 2 * PI
            t
        }
        is Spline -> 0.0
    }

    /** Is the place within the curve's extent (lines and arcs end; circles don't). */
    private fun Sketch.within(c: Curve, t: Double): Boolean = when (c) {
        is Line -> t >= -EPS && t <= 1 + EPS
        is Circle -> true
        is Arc -> t >= -EPS && t <= span(c) + EPS
        // Splines aren't trimmed or extended to, yet.
        is Spline -> false
    }

    /** Points where two curves' full shapes meet: lines as infinite lines, arcs as whole circles. */
    private fun Sketch.meet(c: Curve, d: Curve): List<Pair<Double, Double>> {
        fun circleOf(k: Curve) = when (k) {
            is Circle -> Triple(x(k.centre), y(k.centre), radius(k))
            is Arc -> Triple(x(k.centre), y(k.centre), radius(k))
            else -> null
        }
        if (c is Spline || d is Spline) return emptyList()
        if (c is Line && d is Line) {
            val (x1, y1) = x(c.a) to y(c.a); val (x2, y2) = x(c.b) to y(c.b)
            val (x3, y3) = x(d.a) to y(d.a); val (x4, y4) = x(d.b) to y(d.b)
            val den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
            if (abs(den) < 1e-12) return emptyList()
            val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
            return listOf(x1 + t * (x2 - x1) to y1 + t * (y2 - y1))
        }
        if (c is Line || d is Line) {
            val l = (if (c is Line) c else d) as Line
            val (cx, cy, r) = circleOf(if (c is Line) d else c)!!
            val ax = x(l.a); val ay = y(l.a)
            val dx = x(l.b) - ax; val dy = y(l.b) - ay
            val fx = ax - cx; val fy = ay - cy
            val a = dx * dx + dy * dy
            val b = 2 * (fx * dx + fy * dy)
            val k = fx * fx + fy * fy - r * r
            val disc = b * b - 4 * a * k
            if (disc < 0 || a < 1e-12) return emptyList()
            val s = sqrt(disc)
            return listOf((-b - s) / (2 * a), (-b + s) / (2 * a)).distinct().map { t -> ax + t * dx to ay + t * dy }
        }
        val (x0, y0, r0) = circleOf(c)!!
        val (x1, y1, r1) = circleOf(d)!!
        val dd = hypot(x1 - x0, y1 - y0)
        if (dd < 1e-12 || dd > r0 + r1 + 1e-9 || dd < abs(r0 - r1) - 1e-9) return emptyList()
        val a = (r0 * r0 - r1 * r1 + dd * dd) / (2 * dd)
        val h = sqrt((r0 * r0 - a * a).coerceAtLeast(0.0))
        val mx = x0 + a * (x1 - x0) / dd; val my = y0 + a * (y1 - y0) / dd
        return listOf(mx + h * (y1 - y0) / dd to my - h * (x1 - x0) / dd, mx - h * (y1 - y0) / dd to my + h * (x1 - x0) / dd).distinct()
    }

    /** Where every other non-construction curve crosses c within both their extents. */
    private fun Sketch.crossings(c: Curve): List<Crossing> {
        val out = mutableListOf<Crossing>()
        for (d in curves) {
            if (d === c) continue
            for ((u, v) in meet(c, d)) {
                val tc = placeOf(c, u, v)
                val td = placeOf(d, u, v)
                if (within(c, tc) && within(d, td)) out += Crossing(tc, u, v, d)
            }
        }
        return out
    }

    /** A point at (u, v) on [other]: an existing point there if there is one, else a new one held on it. */
    private fun Sketch.pointOn(u: Double, v: Double, other: Curve): Point {
        // Not the origin: it never moves, so a point made there would be pinned.
        points.firstOrNull { it !== origin && hypot(x(it) - u, y(it) - v) < 1e-6 }?.let { return it }
        val p = addPoint(u, v)
        add(if (other is Line) Constraint.OnLine(p, other) else Constraint.OnCircle(p, other))
        return p
    }

    /** Level, upright, parallel, perpendicular and collinear constraints of an old line, put on its replacement. */
    private fun Sketch.carry(old: Line, new: Line) {
        for (k in constraints.toList()) when (k) {
            is Constraint.Horizontal -> if (k.line === old) add(Constraint.Horizontal(new))
            is Constraint.Vertical -> if (k.line === old) add(Constraint.Vertical(new))
            is Constraint.Parallel -> if (k.l1 === old) add(Constraint.Parallel(new, k.l2)) else if (k.l2 === old) add(Constraint.Parallel(k.l1, new))
            is Constraint.Perpendicular -> if (k.l1 === old) add(Constraint.Perpendicular(new, k.l2)) else if (k.l2 === old) add(Constraint.Perpendicular(k.l1, new))
            is Constraint.Collinear -> if (k.l1 === old) add(Constraint.Collinear(new, k.l2)) else if (k.l2 === old) add(Constraint.Collinear(k.l1, new))
            else -> {}
        }
    }

    /**
     * Removes the piece of c between the crossings either side of (u, v).
     * A curve nothing crosses goes altogether.
     */
    fun trim(s: Sketch, c: Curve, u: Double, v: Double): String? {
        if (c is Spline) return "Splines can't be trimmed yet"
        val at = s.placeOf(c, u, v)
        val cross = s.crossings(c)
        when (c) {
            is Line, is Arc -> {
                val end = if (c is Arc) s.span(c) else 1.0
                val inner = cross.filter { it.at > EPS * end && it.at < end - EPS * end }
                val before = inner.filter { it.at < at }.maxByOrNull { it.at }
                val after = inner.filter { it.at > at }.minByOrNull { it.at }
                if (before == null && after == null) {
                    s.remove(c)
                    return null
                }
                val pieces = mutableListOf<Pair<Point, Point>>()
                val (startPoint, endPoint) = if (c is Line) c.a to c.b else (c as Arc).start to c.end
                if (before != null) pieces += startPoint to s.pointOn(before.u, before.v, before.other)
                if (after != null) pieces += s.pointOn(after.u, after.v, after.other) to endPoint
                for ((p, q) in pieces) {
                    if (c is Line) s.carry(c, s.addLine(p, q, c.construction))
                    else s.addArc((c as Arc).centre, p, q, c.construction)
                }
                removeKeeping(s, c, pieces.flatMap { listOf(it.first, it.second) })
            }
            is Circle -> {
                if (cross.size < 2) {
                    s.remove(c)
                    return null
                }
                // Keep the circle from the crossing after the tap round to the one before it.
                fun norm(a: Double): Double { var t = a - at; while (t < 0) t += 2 * PI; return t }
                val sorted = cross.sortedBy { norm(it.at) }
                val after = sorted.first()
                val before = sorted.last()
                if (abs(norm(after.at) - norm(before.at)) < 1e-9) return "Trimming needs two crossings on a circle"
                val start = s.pointOn(after.u, after.v, after.other)
                val end = s.pointOn(before.u, before.v, before.other)
                val arc = s.addArc(c.centre, start, end, c.construction)
                for (k in s.constraints.toList()) if (k is Constraint.Radius && k.curve === c) s.add(Constraint.Radius(arc, k.diameter, k.value))
                removeKeeping(s, c, listOf(start, end, c.centre))
            }
            is Spline -> {}
        }
        s.solve()
        return null
    }

    /** Takes a curve out without losing points the new pieces use. */
    private fun removeKeeping(s: Sketch, c: Curve, keep: List<Point>) {
        val kept = keep.toSet()
        val snapshot = s.points.filter { it in kept }
        s.removeCurveOnly(c)
        // Points the old curve alone used, other than the ones kept, go.
        for (p in c.points()) if (p !in kept && p !== s.origin && s.curves.none { p in it.points() } && snapshot.none { it === p }) s.removePoint(p)
    }

    /**
     * Lengthens a line from its end nearest (u, v) until it meets another
     * curve. The end has to be free of other curves.
     */
    fun extend(s: Sketch, l: Line, u: Double, v: Double): String? {
        val fromB = hypot(s.x(l.b) - u, s.y(l.b) - v) < hypot(s.x(l.a) - u, s.y(l.a) - v)
        val end = if (fromB) l.b else l.a
        if (s.curves.any { it !== l && end in it.points() }) return "That end is joined to another curve"
        var best: Pair<Double, Pair<Pair<Double, Double>, Curve>>? = null
        for (d in s.curves) {
            if (d === l) continue
            for ((px, py) in s.meet(l, d)) {
                val t = s.placeOf(l, px, py)
                val beyond = if (fromB) t - 1 else -t
                if (beyond > EPS && s.within(d, s.placeOf(d, px, py)) && (best == null || beyond < best.first)) best = beyond to ((px to py) to d)
            }
        }
        val hit = best ?: return "There's nothing to extend it to"
        val (point, other) = hit.second
        s.move(end, point.first, point.second)
        s.add(if (other is Line) Constraint.OnLine(end, other) else Constraint.OnCircle(end, other))
        s.solve()
        return null
    }

    /**
     * Copies curves a distance away: lines alongside, circles and arcs with
     * the same centre. Positive moves away from the middle of what's copied.
     * Copies of lines that met still meet, at their new corner.
     */
    fun offset(s: Sketch, curves: List<Curve>, distance: Double): String? {
        if (curves.isEmpty()) return "Select the curves to copy"
        if (curves.any { it is Spline }) return "Splines can't be offset yet"
        if (abs(distance) < 1e-9) return "The distance can't be 0"
        val mids = curves.map { s.pointAt(it, if (it is Line) 0.5 else if (it is Arc) s.span(it) / 2 else 0.0) }
        val cx = mids.sumOf { it.first } / mids.size
        val cy = mids.sumOf { it.second } / mids.size

        // Each line's offset as a point and direction.
        class Off(val px: Double, val py: Double, val dx: Double, val dy: Double)
        val lineOffsets = mutableMapOf<Line, Off>()
        for (c in curves) if (c is Line) {
            val dx = s.x(c.b) - s.x(c.a); val dy = s.y(c.b) - s.y(c.a)
            val len = hypot(dx, dy).coerceAtLeast(1e-12)
            var nx = -dy / len; var ny = dx / len
            val (mx, my) = s.pointAt(c, 0.5)
            // Away from the middle, or to the left for a line on its own.
            if (curves.size > 1 && (mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny }
            lineOffsets[c] = Off(s.x(c.a) + nx * distance, s.y(c.a) + ny * distance, dx, dy)
        }
        fun radial(k: Curve) = when (k) { is Circle -> s.radius(k); is Arc -> s.radius(k); else -> 0.0 }
        fun newRadius(k: Curve) = radial(k) + distance

        val moved = mutableMapOf<Point, Point>()
        fun moveOf(p: Point): Point = moved.getOrPut(p) {
            val users = curves.filter { p in it.points() && !(it is Arc && it.centre === p) }
            val lines = users.filterIsInstance<Line>()
            val (u, v) = if (lines.size == 2) {
                val o1 = lineOffsets.getValue(lines[0]); val o2 = lineOffsets.getValue(lines[1])
                val den = o1.dx * o2.dy - o1.dy * o2.dx
                if (abs(den) < 1e-12) o1.px + (s.x(p) - s.x(lines[0].a)) to o1.py + (s.y(p) - s.y(lines[0].a))
                else {
                    val t = ((o2.px - o1.px) * o2.dy - (o2.py - o1.py) * o2.dx) / den
                    o1.px + t * o1.dx to o1.py + t * o1.dy
                }
            } else if (lines.size == 1) {
                val o = lineOffsets.getValue(lines[0])
                o.px + (s.x(p) - s.x(lines[0].a)) to o.py + (s.y(p) - s.y(lines[0].a))
            } else {
                val arc = users.first() as Arc
                val ax = s.x(p) - s.x(arc.centre); val ay = s.y(p) - s.y(arc.centre)
                val r = hypot(ax, ay).coerceAtLeast(1e-12)
                s.x(arc.centre) + ax / r * newRadius(arc) to s.y(arc.centre) + ay / r * newRadius(arc)
            }
            s.addPoint(u, v)
        }
        for (c in curves) if (radial(c) > 0 && newRadius(c) <= 1e-9) return "The copy of a circle or arc would have no size"
        for (c in curves) when (c) {
            is Line -> s.addLine(moveOf(c.a), moveOf(c.b), c.construction).also { s.add(Constraint.Parallel(it, c)) }
            is Circle -> s.addCircle(c.centre, newRadius(c), c.construction)
            is Arc -> s.addArc(c.centre, moveOf(c.start), moveOf(c.end), c.construction)
            is Spline -> {}
        }
        s.solve()
        return null
    }

    /**
     * Rounds the corner where two lines meet at p, with an arc of radius r
     * that touches both. The lines are shortened to meet it.
     */
    fun filletCorner(s: Sketch, p: Point, r: Double): String? {
        if (r <= 0) return "The radius has to be more than 0"
        val lines = s.curves.filter { p in it.points() }
        if (lines.size != 2 || lines.any { it !is Line }) return "Tap a corner where two lines meet"
        val (l1, l2) = lines.map { it as Line }
        val far1 = if (l1.a === p) l1.b else l1.a
        val far2 = if (l2.a === p) l2.b else l2.a
        val px = s.x(p); val py = s.y(p)
        fun unit(q: Point): Pair<Double, Double> {
            val dx = s.x(q) - px; val dy = s.y(q) - py
            val len = hypot(dx, dy)
            return dx / len to dy / len
        }
        val (ux, uy) = unit(far1)
        val (vx, vy) = unit(far2)
        val angle = kotlin.math.acos((ux * vx + uy * vy).coerceIn(-1.0, 1.0))
        if (angle < 1e-6 || abs(angle - PI) < 1e-6) return "The lines are in a straight line"
        val back = r / tan(angle / 2)
        if (back >= s.distance(p, far1) || back >= s.distance(p, far2)) return "The radius is too big for those lines"
        val t1 = s.addPoint(px + ux * back, py + uy * back)
        val t2 = s.addPoint(px + vx * back, py + vy * back)
        val bx = ux + vx; val by = uy + vy
        val blen = hypot(bx, by)
        val d = r / sin(angle / 2)
        val centre = s.addPoint(px + bx / blen * d, py + by / blen * d)
        // The arc goes anticlockwise, so it starts at whichever end that makes it the short way round.
        val cross = ux * vy - uy * vx
        val arc = if (cross > 0) s.addArc(centre, t2, t1) else s.addArc(centre, t1, t2)
        val n1 = s.addLine(far1, t1, l1.construction)
        val n2 = s.addLine(far2, t2, l2.construction)
        s.carry(l1, n1)
        s.carry(l2, n2)
        s.removeCurveOnly(l1)
        s.removeCurveOnly(l2)
        s.removePoint(p)
        s.add(Constraint.TangentJoin(n1, arc, t1))
        s.add(Constraint.TangentJoin(n2, arc, t2))
        s.add(Constraint.Radius(arc, false, r))
        s.solve()
        return null
    }
}
