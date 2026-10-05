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

    /**
     * Goes through edges flattened onto a sketch in a fixed order, asking
     * [point] for each place, so projecting and projecting again make their
     * points in the same order. Curved pieces of one edge become one spline
     * through points along them.
     */
    private fun <P> walk(
        curves: List<ProfileCurve>, point: (Double, Double) -> P,
        line: (P, P) -> Unit, circle: (P, Double) -> Unit, arc: (P, P, P) -> Unit, spline: (List<P>) -> Unit,
    ) {
        for (c in curves) when (c.kind) {
            ProfileCurve.Kind.Line -> line(point(c.x1, c.y1), point(c.x2, c.y2))
            ProfileCurve.Kind.Circle -> circle(point(c.x1, c.y1), c.r)
            ProfileCurve.Kind.Arc -> arc(point(c.x1, c.y1), point(c.x1 + c.r * cos(c.a0), c.y1 + c.r * sin(c.a0)), point(c.x1 + c.r * cos(c.a1), c.y1 + c.r * sin(c.a1)))
            ProfileCurve.Kind.Bezier -> {}
        }
        for ((_, pieces) in curves.filter { it.kind == ProfileCurve.Kind.Bezier }.groupBy { it.id }) {
            val along = mutableListOf<Pair<Double, Double>>()
            for (b in pieces) for (k in 0..3) {
                if (k == 0 && along.isNotEmpty()) continue
                val t = k / 3.0; val w = 1 - t
                along += (w * w * w * b.x1 + 3 * w * w * t * b.cx1 + 3 * w * t * t * b.cx2 + t * t * t * b.x2) to
                    (w * w * w * b.y1 + 3 * w * w * t * b.cy1 + 3 * w * t * t * b.cy2 + t * t * t * b.y2)
            }
            spline(along.map { (u, v) -> point(u, v) })
        }
    }

    /**
     * Brings edges flattened onto the sketch in as fixed curves to draw
     * against, and returns the link that ties them to where they came from
     * (see [ProjectionLink]), which the caller keeps in [Sketch.links] if it
     * should follow. A point already at a place is used, not doubled.
     */
    fun project(s: Sketch, curves: List<ProfileCurve>, section: Boolean, bodies: List<String>, middle: Boolean = false): ProjectionLink {
        val points = mutableListOf<Point>()
        val circles = mutableListOf<Circle>()
        fun fixedPoint(u: Double, v: Double): Point {
            val p = s.points.firstOrNull { it !== s.origin && hypot(s.x(it) - u, s.y(it) - v) < 1e-6 }
                ?: s.addPoint(u, v).also { s.loadConstraint(Constraint.Fixed(it, u, v)) }
            if (p !in points) points += p
            return p
        }
        walk(
            curves, ::fixedPoint,
            line = { a, b -> s.addLine(a, b) },
            circle = { c, r -> s.addCircle(c, r).also { circles += it; s.loadConstraint(Constraint.Radius(it, true, 2 * r)) } },
            arc = { c, a, b -> s.addArc(c, a, b) },
            spline = { ps ->
                val closed = ps.size > 3 && ps.first() === ps.last()
                if (ps.distinct().size >= 2) s.addSpline(if (closed) ps else ps.distinct(), construction = false)
            },
        )
        return ProjectionLink(section, bodies, points, circles, middle)
    }

    /**
     * Moves what [link] brought in to where its edges are now, given the same
     * projection made again. False when the edges changed shape (more or
     * fewer of them), and nothing is moved.
     */
    fun reproject(s: Sketch, link: ProjectionLink, curves: List<ProfileCurve>): Boolean {
        val places = mutableListOf<Pair<Double, Double>>()
        val radii = mutableListOf<Double>()
        fun place(u: Double, v: Double): Int =
            places.indexOfFirst { hypot(it.first - u, it.second - v) < 1e-6 }.takeIf { it >= 0 } ?: places.size.also { places += u to v }
        walk(curves, ::place, line = { _, _ -> }, circle = { _, r -> radii += r }, arc = { _, _, _ -> }, spline = {})
        if (places.size != link.points.size || radii.size != link.circles.size) return false
        var moved = false
        for ((i, p) in link.points.withIndex()) {
            if (s.point(p.id) !== p) continue
            val (u, v) = places[i]
            val fixed = s.constraints.filterIsInstance<Constraint.Fixed>().firstOrNull { it.p === p } ?: continue
            if (abs(fixed.atX - u) < 1e-9 && abs(fixed.atY - v) < 1e-9) continue
            s.remove(fixed)
            s.loadConstraint(Constraint.Fixed(p, u, v))
            moved = true
        }
        for ((i, c) in link.circles.withIndex()) {
            val size = s.constraints.filterIsInstance<Constraint.Radius>().firstOrNull { it.curve === c } ?: continue
            val want = if (size.diameter) 2 * radii[i] else radii[i]
            if (abs(size.value - want) > 1e-9) {
                size.value = want
                moved = true
            }
        }
        if (moved) s.solve()
        return true
    }

    /** Where a curve meets others: a line's or arc's ends, an open spline's first and last points; none for closed shapes. */
    private fun ends(c: Curve): List<Point> = when (c) {
        is Line -> listOf(c.a, c.b)
        is Arc -> listOf(c.start, c.end)
        is Circle -> emptyList()
        is Spline -> if (c.shape == Spline.Shape.Ellipse || c.through.first() === c.through.last()) emptyList() else listOf(c.through.first(), c.through.last())
    }

    /**
     * The curves joined end to end with [start], itself first: a whole
     * outline from one of its pieces. Ends held together by a coincident
     * constraint count as joined. Construction curves only join their own kind.
     */
    fun connected(s: Sketch, start: Curve): List<Curve> {
        val same = HashMap<Point, MutableSet<Point>>()
        for (k in s.constraints) if (k is Constraint.Coincident) {
            same.getOrPut(k.p) { mutableSetOf(k.p) } += k.q
            same.getOrPut(k.q) { mutableSetOf(k.q) } += k.p
        }
        fun joined(p: Point): Set<Point> = same[p] ?: setOf(p)
        val atPoint = HashMap<Point, MutableList<Curve>>()
        for (c in s.curves) if (c.construction == start.construction) for (p in ends(c)) atPoint.getOrPut(p) { mutableListOf() } += c
        val out = LinkedHashSet<Curve>()
        val todo = ArrayDeque(listOf(start))
        while (todo.isNotEmpty()) {
            val c = todo.removeFirst()
            if (!out.add(c)) continue
            for (p in ends(c)) for (q in joined(p)) atPoint[q]?.forEach { if (it !in out) todo += it }
        }
        return out.toList()
    }

    /** Points along a curve close enough together to stand in for it, as when checking it against a box. */
    fun samples(s: Sketch, c: Curve): List<Pair<Double, Double>> = when (c) {
        is Line -> listOf(s.pointAt(c, 0.0), s.pointAt(c, 1.0))
        is Circle -> (0..32).map { s.pointAt(c, 2 * PI * it / 32) }
        is Arc -> { val span = s.span(c); (0..16).map { s.pointAt(c, span * it / 16) } }
        is Spline -> s.sampleSpline(c)
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

    /**
     * What held an old line's direction and place, put on its replacement,
     * which lies along the same infinite line: level, upright, parallel,
     * square, in line, points on it or mirrored across it, tangents, smooth
     * joins at a point it still ends at, and distances and angles to it.
     * The new line runs the same way as the old. Add them once the old line
     * is gone, or they'd look already set.
     */
    private fun Sketch.carried(old: Line, new: Line): List<Constraint> = buildList {
        for (k in constraints) when (k) {
            is Constraint.Horizontal -> if (k.line === old) add(Constraint.Horizontal(new))
            is Constraint.Vertical -> if (k.line === old) add(Constraint.Vertical(new))
            is Constraint.Parallel -> if (k.l1 === old) add(Constraint.Parallel(new, k.l2)) else if (k.l2 === old) add(Constraint.Parallel(k.l1, new))
            is Constraint.Perpendicular -> if (k.l1 === old) add(Constraint.Perpendicular(new, k.l2)) else if (k.l2 === old) add(Constraint.Perpendicular(k.l1, new))
            is Constraint.Collinear -> if (k.l1 === old) add(Constraint.Collinear(new, k.l2)) else if (k.l2 === old) add(Constraint.Collinear(k.l1, new))
            is Constraint.OnLine -> if (k.line === old && k.p !in new.points()) add(Constraint.OnLine(k.p, new))
            is Constraint.Symmetric -> if (k.line === old) add(Constraint.Symmetric(k.p, k.q, new))
            is Constraint.TangentLine -> if (k.line === old) add(Constraint.TangentLine(new, k.curve))
            is Constraint.TangentJoin -> if (k.at in new.points()) {
                if (k.c1 === old) add(Constraint.TangentJoin(new, k.c2, k.at)) else if (k.c2 === old) add(Constraint.TangentJoin(k.c1, new, k.at))
            }
            is Constraint.PointLineDistance -> if (k.line === old) add(Constraint.PointLineDistance(k.p, new, k.value))
            is Constraint.Angle -> if (k.l1 === old) add(Constraint.Angle(new, k.l2, k.value)) else if (k.l2 === old) add(Constraint.Angle(k.l1, new, k.value))
            else -> {}
        }
    }

    /**
     * After a corner at p is rounded or cut: p goes, unless something else
     * holds on to it (a dimension, the origin), when it stays where the
     * lines would meet, so what was measured to the corner still is.
     */
    private fun Sketch.leaveCorner(p: Point, old1: Line, old2: Line, new1: Line, new2: Line) {
        val carried = carried(old1, new1) + carried(old2, new2)
        // A side's length becomes the distance from its far end to the corner.
        val lengths = constraints.filterIsInstance<Constraint.Length>().filter { it.line === old1 || it.line === old2 }
            .map { Constraint.Distance(if (it.line.a === p) it.line.b else it.line.a, p, it.value) }
        val held = p === origin || lengths.isNotEmpty() || constraints.any { k -> p in k.points() && k.curves().none { it === old1 || it === old2 } }
        removeCurveOnly(old1)
        removeCurveOnly(old2)
        if (held) {
            add(Constraint.OnLine(p, new1))
            add(Constraint.OnLine(p, new2))
            lengths.forEach { add(it) }
        } else {
            removePoint(p)
        }
        carried.forEach { add(it) }
    }

    /** A line from q to the far end of [old] from p, running the same way as old. */
    private fun Sketch.shortened(old: Line, p: Point, far: Point, q: Point): Line =
        if (old.a === p) addLine(q, far, old.construction) else addLine(far, q, old.construction)

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
                val carried = mutableListOf<Constraint>()
                for ((p, q) in pieces) {
                    if (c is Line) carried += s.carried(c, s.addLine(p, q, c.construction))
                    else s.addArc((c as Arc).centre, p, q, c.construction)
                }
                removeKeeping(s, c, pieces.flatMap { listOf(it.first, it.second) })
                carried.forEach { s.add(it) }
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
    fun offset(s: Sketch, curves: List<Curve>, distance: Double, expression: String? = null): String? {
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
        val made = LinkedHashMap<Curve, Curve>()
        for (c in curves) when (c) {
            is Line -> s.addLine(moveOf(c.a), moveOf(c.b), c.construction).also { s.add(Constraint.Parallel(it, c)); made[c] = it }
            is Circle -> s.addCircle(c.centre, newRadius(c), c.construction).also { made[c] = it }
            is Arc -> s.addArc(c.centre, moveOf(c.start), moveOf(c.end), c.construction).also { made[c] = it }
            is Spline -> {}
        }
        // Where a line ran smoothly into an arc, square to its radius, the copies do too.
        for ((old, new) in made) if (old is Line && new is Line) for (p in old.points()) {
            val arc = curves.firstOrNull { it is Arc && p in it.points() && p !== it.centre } as Arc? ?: continue
            val dx = s.x(old.b) - s.x(old.a); val dy = s.y(old.b) - s.y(old.a)
            val rx = s.x(p) - s.x(arc.centre); val ry = s.y(p) - s.y(arc.centre)
            if (abs(dx * rx + dy * ry) > 1e-6 * hypot(dx, dy) * hypot(rx, ry)) continue
            s.add(Constraint.TangentJoin(new, made.getValue(arc), moved.getValue(p)))
        }
        // Each line is held its distance from the one it came from, typed as a parameter if it was,
        // so changing the distance moves it; round a smooth outline one of these sets them all.
        for ((old, new) in made) if (old is Line && new is Line) {
            s.add(Constraint.PointLineDistance(new.a, old, abs(distance)).also { it.expression = expression })
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
        val n1 = s.shortened(l1, p, far1, t1)
        val n2 = s.shortened(l2, p, far2, t2)
        s.leaveCorner(p, l1, l2, n1, n2)
        s.add(Constraint.TangentJoin(n1, arc, t1))
        s.add(Constraint.TangentJoin(n2, arc, t2))
        s.add(Constraint.Radius(arc, false, r))
        s.solve()
        return null
    }

    /** Cuts the corner where two lines meet at p with a straight line, d back along each. */
    fun chamferCorner(s: Sketch, p: Point, d: Double): String? {
        if (d <= 0) return "The distance has to be more than 0"
        val lines = s.curves.filter { p in it.points() }
        if (lines.size != 2 || lines.any { it !is Line }) return "Tap a corner where two lines meet"
        val (l1, l2) = lines.map { it as Line }
        val far1 = if (l1.a === p) l1.b else l1.a
        val far2 = if (l2.a === p) l2.b else l2.a
        if (d >= s.distance(p, far1) || d >= s.distance(p, far2)) return "The distance is too big for those lines"
        val px = s.x(p); val py = s.y(p)
        fun back(q: Point): Point {
            val len = s.distance(p, q)
            return s.addPoint(px + (s.x(q) - px) / len * d, py + (s.y(q) - py) / len * d)
        }
        val t1 = back(far1)
        val t2 = back(far2)
        val n1 = s.shortened(l1, p, far1, t1)
        val n2 = s.shortened(l2, p, far2, t2)
        s.addLine(t1, t2, l1.construction && l2.construction)
        s.leaveCorner(p, l1, l2, n1, n2)
        s.solve()
        return null
    }

    /** Splits a line or arc in two at the place nearest (u, v), without taking anything away. */
    fun breakAt(s: Sketch, c: Curve, u: Double, v: Double): String? {
        when (c) {
            is Line -> {
                val t = s.placeOf(c, u, v)
                if (t <= EPS || t >= 1 - EPS) return "Tap away from the ends"
                val (mx, my) = s.pointAt(c, t)
                val m = s.addPoint(mx, my)
                val first = s.addLine(c.a, m, c.construction)
                val second = s.addLine(m, c.b, c.construction)
                val carried = s.carried(c, first) + s.carried(c, second)
                removeKeeping(s, c, listOf(c.a, c.b, m))
                s.add(Constraint.Collinear(first, second))
                carried.forEach { s.add(it) }
            }
            is Arc -> {
                val t = s.placeOf(c, u, v)
                val span = s.span(c)
                if (t <= EPS * span || t >= span * (1 - EPS)) return "Tap away from the ends"
                val (mx, my) = s.pointAt(c, t)
                val m = s.addPoint(mx, my)
                s.addArc(c.centre, c.start, m, c.construction)
                s.addArc(c.centre, m, c.end, c.construction)
                removeKeeping(s, c, listOf(c.centre, c.start, c.end, m))
            }
            is Circle -> return "Break a circle with Trim, where other curves cross it"
            is Spline -> return "Splines can't be broken yet"
        }
        s.solve()
        return null
    }

    /**
     * Copies curves with every point moved by [to]. Radii scale by [scale];
     * [flip] for a mirror image, which turns arcs the other way. Points the
     * curves share stay shared in the copy. Returns each old point's copy.
     */
    fun copy(s: Sketch, curves: List<Curve>, scale: Double, flip: Boolean, to: (Double, Double) -> Pair<Double, Double>): Map<Point, Point> {
        val made = LinkedHashMap<Point, Point>()
        fun of(p: Point) = made.getOrPut(p) { to(s.x(p), s.y(p)).let { (x, y) -> s.addPoint(x, y) } }
        for (c in curves) when (c) {
            is Line -> s.addLine(of(c.a), of(c.b), c.construction)
            is Circle -> s.addCircle(of(c.centre), s.radius(c) * scale, c.construction)
            is Arc -> if (flip) s.addArc(of(c.centre), of(c.end), of(c.start), c.construction) else s.addArc(of(c.centre), of(c.start), of(c.end), c.construction)
            is Spline -> s.addSpline(c.through.map(::of), c.construction, c.shape, c.rho)
        }
        return made
    }

    /**
     * Copies [curves] [count] times along (dx, dy), and with [rows] above 1,
     * that row again [rows] times, each row [rowGap] on from the last, square
     * to the row. The copies stay tied to the originals: the first copy's
     * step across is dimensioned, and so is the first row's, and every other
     * copy repeats those steps, so moving the originals or changing a step
     * moves them all. Circles keep the originals' size.
     */
    fun pattern(s: Sketch, curves: List<Curve>, count: Int, dx: Double, dy: Double, rows: Int = 1, rowGap: Double = 0.0): String? {
        if (curves.isEmpty()) return "Select the curves first"
        if (count < 1 || rows < 1 || count * rows < 2) return "Make at least two"
        if (count > 1 && hypot(dx, dy) < 1e-9) return "The copies would sit on top of each other"
        if (rows > 1 && abs(rowGap) < 1e-9) return "The rows would sit on top of each other"
        val len = hypot(dx, dy)
        // Rows go square to the row, to its left; a row of one goes up.
        val (rx, ry) = if (len < 1e-9) 0.0 to rowGap else -dy / len * rowGap to dx / len * rowGap
        val originals = curves.flatMap { it.points() }.distinct()
        val ref = originals.first()
        val copies = HashMap<Pair<Int, Int>, Map<Point, Point>>()
        copies[0 to 0] = originals.associateWith { it }
        for (j in 0 until rows) for (i in 0 until count) {
            if (i == 0 && j == 0) continue
            val before = s.curves.toSet()
            val made = copy(s, curves, 1.0, false) { x, y -> x + i * dx + j * rx to y + i * dy + j * ry }
            copies[i to j] = made
            val fresh = s.curves.filter { it !in before }
            for ((old, new) in curves.zip(fresh)) if (old is Circle && new is Circle) s.add(Constraint.Equal(new, old))
        }
        // A step held by two sizes: across and up, or one of them level or upright when it's 0.
        fun dimension(from: Point, to: Point, sx: Double, sy: Double) {
            if (abs(sx) > 1e-9) s.add(Constraint.AxisDistance(from, to, false, sx)) else s.add(Constraint.VerticalPoints(from, to))
            if (abs(sy) > 1e-9) s.add(Constraint.AxisDistance(from, to, true, sy)) else s.add(Constraint.HorizontalPoints(from, to))
        }
        val across = copies[1 to 0]?.get(ref)
        val up = copies[0 to 1]?.get(ref)
        across?.let { dimension(ref, it, dx, dy) }
        up?.let { dimension(ref, it, rx, ry) }
        for ((at, map) in copies) {
            val (i, j) = at
            if (i == 0 && j == 0) continue
            val (prev, stepTo) = if (i > 0) copies.getValue(i - 1 to j) to across!! else copies.getValue(0 to j - 1) to up!!
            for (p in originals) {
                val from = prev.getValue(p); val to = map.getValue(p)
                if (from === ref && to === stepTo) continue
                s.add(Constraint.SameStep(from, to, ref, stepTo))
            }
        }
        s.solve()
        return null
    }

    /**
     * Copies curves mirrored across [axis], each copied point held
     * symmetric to its original; points on the axis are shared.
     */
    fun mirror(s: Sketch, curves: List<Curve>, axis: Line): String? {
        val list = curves.filter { it !== axis }
        if (list.isEmpty()) return "Select what to mirror, then the line to mirror it across"
        val ax = s.x(axis.a); val ay = s.y(axis.a)
        val len = s.length(axis)
        if (len < 1e-9) return "The line to mirror across has no length"
        val dx = (s.x(axis.b) - ax) / len; val dy = (s.y(axis.b) - ay) / len
        fun across(x: Double, y: Double): Pair<Double, Double> {
            val along = (x - ax) * dx + (y - ay) * dy
            val fx = ax + dx * along; val fy = ay + dy * along
            return (2 * fx - x) to (2 * fy - y)
        }
        val pairs = copy(s, list, 1.0, true, ::across)
        for ((p, q) in pairs) {
            val off = abs((s.x(p) - ax) * dy - (s.y(p) - ay) * dx)
            if (off < 1e-9) s.add(Constraint.Coincident(p, q)) else s.add(Constraint.Symmetric(p, q, axis))
        }
        for (c in list) if (c is Circle) {
            val copyOfCentre = pairs[c.centre]
            s.curves.lastOrNull { it is Circle && it.centre === copyOfCentre }?.let { s.add(Constraint.Equal(c, it)) }
        }
        s.solve()
        return null
    }

    /** Moves curves' points by [to], radii scaled by [scale], then solves. */
    fun move(s: Sketch, curves: List<Curve>, scale: Double, to: (Double, Double) -> Pair<Double, Double>): String? {
        val points = curves.flatMap { it.points() }.distinct().filter { it !== s.origin }
        if (points.isEmpty()) return "Select what to move"
        val at = points.associateWith { to(s.x(it), s.y(it)) }
        for ((p, xy) in at) s.move(p, xy.first, xy.second)
        if (scale != 1.0) {
            for (c in curves) if (c is Circle) s.setRadius(c, s.radius(c) * scale)
            // Sizes wholly within what's scaled scale with it.
            val inside = points.toSet() + curves.flatMap { it.points() }
            for (k in s.constraints) when (k) {
                is Constraint.Length -> if (k.line in curves) k.value *= scale
                is Constraint.Radius -> if (k.curve in curves) k.value *= scale
                is Constraint.Distance -> if (k.p in inside && k.q in inside) k.value *= scale
                is Constraint.AxisDistance -> if (k.p in inside && k.q in inside) k.value *= scale
                else -> {}
            }
        }
        if (!s.solve()) return "It can't move that way: something holds it"
        return null
    }
}
