package com.rm.parrotmetric.sketch

import kotlin.math.atan2
import kotlin.math.hypot

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

    /** The plane's origin. It never moves. */
    val origin: Point = Point(0, slot(0.0), slot(0.0)).also { pointMap[0] = it }

    val points: Collection<Point> get() = pointMap.values
    val curves: Collection<Curve> get() = curveMap.values
    val constraints: List<Constraint> get() = constraintList

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
    }

    fun remove(constraint: Constraint) {
        if (constraint !is Constraint.ArcRadius) constraintList.remove(constraint)
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

    /** Everything about the sketch as it is now, to go back to with [restore]. */
    class Snapshot internal constructor(
        internal val values: List<Double>,
        internal val points: Map<Int, Point>,
        internal val curves: Map<Int, Curve>,
        internal val constraints: List<Constraint>,
        internal val dimensions: Map<Constraint.Dimension, Double>,
        internal val construction: Map<Curve, Boolean>,
        internal val nextId: Int,
    )

    fun snapshot() = Snapshot(
        values.toList(), LinkedHashMap(pointMap), LinkedHashMap(curveMap), constraintList.toList(),
        constraintList.filterIsInstance<Constraint.Dimension>().associateWith { it.value },
        curveMap.values.associateWith { it.construction },
        nextId,
    )

    fun restore(s: Snapshot) {
        values.clear(); values += s.values
        pointMap.clear(); pointMap.putAll(s.points)
        curveMap.clear(); curveMap.putAll(s.curves)
        constraintList.clear(); constraintList += s.constraints
        for ((d, v) in s.dimensions) d.value = v
        for ((c, b) in s.construction) c.construction = b
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
