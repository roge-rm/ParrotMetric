package com.rm.parrotmetric.sketch

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * A 2D sketch on a plane: points, lines, circles and arcs held in place by
 * constraints. Positions are in millimetres in the plane's own x and y.
 *
 * Every coordinate and radius is a slot in [values]; constraints are equations
 * over those slots, and [solve] moves the slots until all of them hold.
 */
class Sketch {
    internal val values = mutableListOf<Double>()
    private var nextId = 1

    private val pointMap = LinkedHashMap<Int, Point>()
    private val curveMap = LinkedHashMap<Int, Curve>()
    private val constraintList = mutableListOf<Constraint>()
    private val textMap = LinkedHashMap<Int, SketchText>()

    /** The plane's origin. It never moves. */
    val origin: Point = Point(0, slot(0.0), slot(0.0)).also { pointMap[0] = it }

    val points: Collection<Point> get() = pointMap.values
    val curves: Collection<Curve> get() = curveMap.values
    val constraints: List<Constraint> get() = constraintList
    val texts: Collection<SketchText> get() = textMap.values

    /** What Project brought in, each tied to where it came from so it can follow it. */
    val links = mutableListOf<ProjectionLink>()

    /**
     * Adds text at [anchor]. Its [outline] (from the font, at (0, 0)) is
     * worked out by whoever sets the text, as the sketch has no fonts.
     */
    fun addText(anchor: Point, text: String, height: Double, bold: Boolean, angle: Double, outline: List<ProfileCurve>): SketchText =
        SketchText(nextId++, anchor, text, height, bold, angle, outline).also { textMap[it.id] = it }

    /** Puts [new] in place of the text with its id. */
    fun replaceText(new: SketchText) { if (textMap.containsKey(new.id)) textMap[new.id] = new }

    fun removeText(t: SketchText) { textMap.remove(t.id) }

    internal fun loadText(t: SketchText) {
        textMap[t.id] = t
        if (t.id >= nextId) nextId = t.id + 1
    }

    private fun slot(v: Double): Int {
        values += v
        return values.size - 1
    }

    fun point(id: Int) = pointMap[id]
    fun curve(id: Int) = curveMap[id]

    fun x(p: Point) = values[p.x]
    fun y(p: Point) = values[p.y]
    fun radius(c: Circle) = values[c.r]
    fun radius(a: Arc) = hypot(x(a.start) - x(a.centre), y(a.start) - y(a.centre))

    fun addPoint(x: Double, y: Double): Point = Point(nextId++, slot(x), slot(y)).also { pointMap[it.id] = it }

    fun addLine(a: Point, b: Point, construction: Boolean = false): Line =
        Line(nextId++, a, b, construction).also { curveMap[it.id] = it }

    fun addCircle(centre: Point, radius: Double, construction: Boolean = false): Circle =
        Circle(nextId++, centre, slot(radius), construction).also { curveMap[it.id] = it }

    /** An arc anticlockwise from start to end round centre. Start and end are kept the same distance from the centre. */
    fun addArc(centre: Point, start: Point, end: Point, construction: Boolean = false): Arc {
        val arc = Arc(nextId++, centre, start, end, construction)
        curveMap[arc.id] = arc
        constraintList += Constraint.ArcRadius(arc)
        return arc
    }

    /** A curve shaped by its points; see [Spline.Shape] for what each shape takes. An ellipse keeps its axes square. */
    fun addSpline(through: List<Point>, construction: Boolean = false, shape: Spline.Shape = Spline.Shape.Through, rho: Double = 0.5): Spline {
        val sp = Spline(nextId++, through, construction, shape)
        sp.rho = rho
        curveMap[sp.id] = sp
        if (shape == Spline.Shape.Ellipse) constraintList += Constraint.EllipseAxes(sp)
        return sp
    }

    internal fun loadSpline(id: Int, through: List<Point>, construction: Boolean, shape: Spline.Shape = Spline.Shape.Through, rho: Double = 0.5) {
        val sp = Spline(id, through, construction, shape)
        sp.rho = rho
        loadCurve(sp)
        if (shape == Spline.Shape.Ellipse) constraintList += Constraint.EllipseAxes(sp)
    }

    /**
     * A spline's pieces as cubic Bezier curves: start, first control,
     * second control, end, as x y pairs. Through points it's Catmull-Rom, so
     * the curve passes through each of them; the other shapes are in
     * [Spline.Shape].
     */
    fun bezierPieces(sp: Spline): List<DoubleArray> = when (sp.shape) {
        Spline.Shape.Through -> throughPieces(sp)
        Spline.Shape.Control -> controlPieces(sp)
        Spline.Shape.Ellipse -> ellipsePieces(sp)
        Spline.Shape.Conic -> conicPieces(sp)
    }

    /**
     * A cubic B-spline on the control points, uniform, passing through the
     * first and last. Closed, it goes round with no ends.
     */
    private fun controlPieces(sp: Spline): List<DoubleArray> {
        val pts = sp.through.map { x(it) to y(it) }
        val ring = if (sp.closed) pts.dropLast(1) else pts
        if (ring.size < 2) return emptyList()
        // Open: the end points three times over, so the curve starts and ends on them.
        val run = if (sp.closed) ring + ring.take(3) else listOf(ring.first(), ring.first()) + ring + listOf(ring.last(), ring.last())
        return (0..run.size - 4).map { i ->
            val (p0, p1, p2, p3) = run.subList(i, i + 4)
            doubleArrayOf(
                (p0.first + 4 * p1.first + p2.first) / 6, (p0.second + 4 * p1.second + p2.second) / 6,
                (2 * p1.first + p2.first) / 3, (2 * p1.second + p2.second) / 3,
                (p1.first + 2 * p2.first) / 3, (p1.second + 2 * p2.second) / 3,
                (p1.first + 4 * p2.first + p3.first) / 6, (p1.second + 4 * p2.second + p3.second) / 6,
            )
        }.filter { b -> hypot(b[6] - b[0], b[7] - b[1]) + hypot(b[2] - b[0], b[3] - b[1]) > 1e-12 }
    }

    /** An ellipse round its first point through the other two, the ends of its axes, in eight pieces. */
    private fun ellipsePieces(sp: Spline): List<DoubleArray> {
        if (sp.through.size < 3) return emptyList()
        val cx = x(sp.through[0]); val cy = y(sp.through[0])
        val ax = x(sp.through[1]) - cx; val ay = y(sp.through[1]) - cy
        val bx = x(sp.through[2]) - cx; val by = y(sp.through[2]) - cy
        // A circle's eighth, laid on the axes.
        val step = PI / 4
        val k = 4.0 / 3 * tan(step / 4)
        fun at(u: Double, v: Double) = doubleArrayOf(cx + ax * u + bx * v, cy + ay * u + by * v)
        return (0 until 8).map { i ->
            val t0 = i * step; val t1 = t0 + step
            val p0 = at(cos(t0), sin(t0))
            val p1 = at(cos(t0) - k * sin(t0), sin(t0) + k * cos(t0))
            val p2 = at(cos(t1) + k * sin(t1), sin(t1) - k * cos(t1))
            val p3 = at(cos(t1), sin(t1))
            doubleArrayOf(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1])
        }
    }

    /**
     * A conic from the first point to the third, pulled towards the second
     * by [Spline.rho]: in eight pieces, each a cubic matching the conic's
     * ends and directions.
     */
    private fun conicPieces(sp: Spline): List<DoubleArray> {
        if (sp.through.size < 3) return emptyList()
        val (a, s, b) = sp.through.take(3).map { x(it) to y(it) }
        val rho = sp.rho.coerceIn(0.01, 0.99)
        val w = rho / (1 - rho)
        fun point(t: Double): DoubleArray {
            val u = 1 - t
            val d = u * u + 2 * t * u * w + t * t
            return doubleArrayOf((u * u * a.first + 2 * t * u * w * s.first + t * t * b.first) / d, (u * u * a.second + 2 * t * u * w * s.second + t * t * b.second) / d)
        }
        fun slope(t: Double): DoubleArray {
            val u = 1 - t
            val d = u * u + 2 * t * u * w + t * t
            val dd = -2 * u + 2 * w * (1 - 2 * t) + 2 * t
            val p = point(t)
            val nx = -2 * u * a.first + 2 * w * (1 - 2 * t) * s.first + 2 * t * b.first
            val ny = -2 * u * a.second + 2 * w * (1 - 2 * t) * s.second + 2 * t * b.second
            return doubleArrayOf((nx - p[0] * dd) / d, (ny - p[1] * dd) / d)
        }
        val n = 8
        return (0 until n).map { i ->
            val t0 = i.toDouble() / n; val t1 = (i + 1.0) / n; val h = (t1 - t0) / 3
            val p0 = point(t0); val p3 = point(t1); val d0 = slope(t0); val d1 = slope(t1)
            doubleArrayOf(p0[0], p0[1], p0[0] + d0[0] * h, p0[1] + d0[1] * h, p3[0] - d1[0] * h, p3[1] - d1[1] * h, p3[0], p3[1])
        }
    }

    private fun throughPieces(sp: Spline): List<DoubleArray> {
        val pts = sp.through.map { x(it) to y(it) }
        val closed = sp.closed
        val ring = if (closed) pts.dropLast(1) else pts
        val n = ring.size
        if (n < 2) return emptyList()
        fun at(i: Int) = if (closed) ring[((i % n) + n) % n] else ring[i.coerceIn(0, n - 1)]
        val pieces = if (closed) n else n - 1
        return (0 until pieces).map { i ->
            val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
            doubleArrayOf(
                p1.first, p1.second,
                p1.first + (p2.first - p0.first) / 6, p1.second + (p2.second - p0.second) / 6,
                p2.first - (p3.first - p1.first) / 6, p2.second - (p3.second - p1.second) / 6,
                p2.first, p2.second,
            )
        }
    }

    /** Points along a spline, for drawing and for finding what's near it. */
    fun sampleSpline(sp: Spline, perPiece: Int = 16): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for (b in bezierPieces(sp)) for (k in 0..perPiece) {
            if (k == 0 && out.isNotEmpty()) continue
            val t = k.toDouble() / perPiece
            val u = 1 - t
            val w0 = u * u * u; val w1 = 3 * u * u * t; val w2 = 3 * u * t * t; val w3 = t * t * t
            out += (w0 * b[0] + w1 * b[2] + w2 * b[4] + w3 * b[6]) to (w0 * b[1] + w1 * b[3] + w2 * b[5] + w3 * b[7])
        }
        return out
    }

    fun setConstruction(curve: Curve, construction: Boolean) {
        curve.construction = construction
    }

    /** Moves a point without solving, as while drawing. */
    fun move(p: Point, x: Double, y: Double) {
        if (p === origin) return
        values[p.x] = x
        values[p.y] = y
    }

    fun setRadius(c: Circle, r: Double) {
        values[c.r] = r
    }

    /**
     * Where holes go: points that aren't part of a drawn curve, so lone points
     * and the corners and centres of construction curves. The origin only when
     * construction uses it, as a construction circle round it does.
     */
    fun holePoints(): List<Point> = points.filter { p ->
        (p !== origin || curves.any { it.construction && p in it.points() }) && curves.none { !it.construction && p in it.points() }
    }

    /**
     * Removes a curve and every constraint on it, and its points if nothing
     * else uses them.
     */
    fun remove(curve: Curve) {
        curveMap.remove(curve.id)
        constraintList.removeAll { curve in it.curves() }
        for (p in curve.points()) if (p !== origin && curves.none { p in it.points() }) removePoint(p)
    }

    /** Removes a curve and the constraints on it, keeping its points. */
    fun removeCurveOnly(curve: Curve) {
        curveMap.remove(curve.id)
        constraintList.removeAll { curve in it.curves() }
    }

    fun removePoint(p: Point) {
        if (p === origin) return
        for (c in curves.filter { p in it.points() }) remove(c)
        pointMap.remove(p.id)
        constraintList.removeAll { p in it.points() }
        textMap.values.removeAll { it.anchor === p }
    }

    fun remove(constraint: Constraint) {
        if (constraint !is Constraint.ArcRadius && constraint !is Constraint.EllipseAxes) constraintList.remove(constraint)
    }

    /** What happened to a constraint offered to [add]. */
    enum class Added { Yes, AlreadySet, Conflicts }

    /**
     * Adds a constraint and solves. A constraint that doesn't pin anything
     * new isn't added: AlreadySet if it holds anyway, Conflicts if it can't.
     * A constraint that can't be met by moving things isn't added either.
     */
    fun add(c: Constraint): Added {
        val before = Solver(this).rank()
        constraintList += c
        val after = Solver(this).rank()
        if (after == before) {
            constraintList.removeAt(constraintList.size - 1)
            val res = c.residuals(::value)
            return if (res.all { kotlin.math.abs(it) < 1e-7 }) Added.AlreadySet else Added.Conflicts
        }
        val saved = values.toList()
        if (!solveWithoutCollapsing()) {
            constraintList.removeAt(constraintList.size - 1)
            for (i in saved.indices) values[i] = saved[i]
            return Added.Conflicts
        }
        return Added.Yes
    }

    /** Changes a dimension's value and solves. False, with the old value back, if it can't be met. */
    fun setDimension(d: Constraint.Dimension, value: Double): Boolean {
        val old = d.value
        val saved = values.toList()
        d.value = value
        if (solveWithoutCollapsing()) return true
        d.value = old
        for (i in saved.indices) values[i] = saved[i]
        return false
    }

    /**
     * Solves, and fails if that shrank a line or circle to nothing: a level
     * line made upright as well, say, which only a line of no length can be.
     */
    private fun solveWithoutCollapsing(): Boolean {
        val collapsed = curves.filter { size(it) < 1e-6 }.toSet()
        if (!Solver(this).solve()) return false
        return curves.none { it !in collapsed && size(it) < 1e-6 }
    }

    private fun size(c: Curve) = Constraint.size(c, ::value)

    /** Solves all constraints. False if they can't all be met. */
    fun solve(): Boolean = Solver(this).solve()

    /**
     * Moves a point towards (x, y) as far as the constraints let it, moving
     * the rest of the sketch as little as it can.
     */
    fun drag(p: Point, x: Double, y: Double): Boolean {
        if (p === origin) return true
        return Solver(this).solve(dragged = listOf(p.x, p.y), targets = listOf(x, y))
    }

    /** Moves several points together by (dx, dy) from where they were at [from], as far as the constraints let them. */
    fun dragAll(points: List<Point>, from: Snapshot, dx: Double, dy: Double): Boolean {
        val moving = points.filter { it !== origin }.distinct()
        if (moving.isEmpty()) return true
        val slots = moving.flatMap { listOf(it.x, it.y) }
        val targets = moving.flatMap { listOf(from.values[it.x] + dx, from.values[it.y] + dy) }
        return Solver(this).solve(dragged = slots, targets = targets)
    }

    /** Which parts can still move, and how many ways the sketch can move in all. */
    fun freedom(): Freedom = Solver(this).freedom()

    internal fun value(i: Int) = values[i]

    // Reading a sketch back from a file, with the ids it was saved with.

    internal fun loadPoint(id: Int, x: Double, y: Double): Point {
        if (id == 0) return origin
        val p = Point(id, slot(x), slot(y))
        pointMap[id] = p
        nextId = maxOf(nextId, id + 1)
        return p
    }

    internal fun loadCurve(c: Curve) {
        curveMap[c.id] = c
        nextId = maxOf(nextId, c.id + 1)
    }

    internal fun loadLine(id: Int, a: Point, b: Point, construction: Boolean) = loadCurve(Line(id, a, b, construction))
    internal fun loadCircle(id: Int, centre: Point, r: Double, construction: Boolean) = loadCurve(Circle(id, centre, slot(r), construction))
    internal fun loadArc(id: Int, centre: Point, start: Point, end: Point, construction: Boolean) {
        val arc = Arc(id, centre, start, end, construction)
        loadCurve(arc)
        constraintList += Constraint.ArcRadius(arc)
    }

    internal fun loadConstraint(c: Constraint) {
        constraintList += c
    }

    /** Everything about the sketch as it is now, to go back to with [restore]. */
    class Snapshot internal constructor(
        internal val values: List<Double>,
        internal val points: Map<Int, Point>,
        internal val curves: Map<Int, Curve>,
        internal val constraints: List<Constraint>,
        internal val dimensions: Map<Constraint.Dimension, Double>,
        internal val construction: Map<Curve, Boolean>,
        internal val nextId: Int,
        internal val texts: Map<Int, SketchText> = emptyMap(),
        internal val links: List<ProjectionLink> = emptyList(),
        internal val rhos: Map<Spline, Double> = emptyMap(),
    )

    fun snapshot() = Snapshot(
        values.toList(), LinkedHashMap(pointMap), LinkedHashMap(curveMap), constraintList.toList(),
        constraintList.filterIsInstance<Constraint.Dimension>().associateWith { it.value },
        curveMap.values.associateWith { it.construction },
        nextId,
        LinkedHashMap(textMap),
        links.toList(),
        curveMap.values.filterIsInstance<Spline>().associateWith { it.rho },
    )

    fun restore(s: Snapshot) {
        values.clear(); values += s.values
        pointMap.clear(); pointMap.putAll(s.points)
        curveMap.clear(); curveMap.putAll(s.curves)
        constraintList.clear(); constraintList += s.constraints
        for ((d, v) in s.dimensions) d.value = v
        for ((c, b) in s.construction) c.construction = b
        for ((c, r) in s.rhos) c.rho = r
        textMap.clear(); textMap.putAll(s.texts)
        links.clear(); links += s.links
        nextId = s.nextId
    }

    /** Points and curves sorted out by whether constraints still let them move. */
    class Freedom(val count: Int, val freePoints: Set<Point>, val freeCurves: Set<Curve>)

    // Measurements used when making dimensions from what's drawn.
    fun length(l: Line) = hypot(x(l.b) - x(l.a), y(l.b) - y(l.a))
    fun distance(p: Point, q: Point) = hypot(x(q) - x(p), y(q) - y(p))
    fun angle(l: Line) = atan2(y(l.b) - y(l.a), x(l.b) - x(l.a))
}

class Point internal constructor(val id: Int, internal val x: Int, internal val y: Int)

sealed class Curve(val id: Int, var construction: Boolean) {
    abstract fun points(): List<Point>
}

class Line internal constructor(id: Int, val a: Point, val b: Point, construction: Boolean) : Curve(id, construction) {
    override fun points() = listOf(a, b)
}

class Circle internal constructor(id: Int, val centre: Point, internal val r: Int, construction: Boolean) : Curve(id, construction) {
    override fun points() = listOf(centre)
}

class Arc internal constructor(id: Int, val centre: Point, val start: Point, val end: Point, construction: Boolean) : Curve(id, construction) {
    override fun points() = listOf(centre, start, end)
}

/**
 * A smooth curve shaped by its points, as [shape] says. Through and Control
 * close into a loop when the last point is the first.
 */
class Spline internal constructor(id: Int, val through: List<Point>, construction: Boolean, val shape: Shape = Shape.Through) : Curve(id, construction) {
    enum class Shape {
        /** Through each point in order. */
        Through,
        /** Pulled towards each point in turn, through only the first and last. */
        Control,
        /** The centre, then the ends of its two axes. */
        Ellipse,
        /** From the first point to the third, bent towards the second by [rho]. */
        Conic,
    }

    /** A conic's fullness: towards 0 it flattens to a straight line, 0.5 is a parabola, towards 1 it reaches into the corner. */
    var rho = 0.5

    override fun points() = through.distinct()
    val closed get() = shape == Shape.Ellipse || shape != Shape.Conic && through.size > 2 && through.first() === through.last()
}

/**
 * Curves brought in by Project, tied to what they came from: the outline of
 * the face the sketch is on, or with [section], where the bodies labelled
 * [bodies] cross the sketch's plane. [points] are its points in the order the
 * projection placed them, [circles] its circles in order, so the same
 * projection made again can move each to where its edge is now. With
 * [middle], each body is cut through its own middle, along the sketch's
 * normal, so a board above or below the sketch comes in by its outline.
 */
class ProjectionLink(val section: Boolean, val bodies: List<String>, val points: List<Point>, val circles: List<Circle>, val middle: Boolean = false)

/** Edges ready to project into a sketch: flattened onto it, and where they came from, as for [ProjectionLink]. */
class ProjectedOutline(val curves: List<ProfileCurve>, val section: Boolean, val bodies: List<String>, val middle: Boolean = false)
