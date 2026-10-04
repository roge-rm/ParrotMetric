package com.rm.parrotmetric.ui.sketch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.parrotmetric.sketch.Arc
import com.rm.parrotmetric.sketch.Circle
import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.sketch.Curve
import com.rm.parrotmetric.sketch.Expression
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.Point
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.RegionFinder
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.SketchOps
import com.rm.parrotmetric.sketch.SketchRegion
import com.rm.parrotmetric.sketch.Spline
import com.rm.parrotmetric.sketch.profileCurves
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class SketchTool { Select, Line, Rectangle, Circle, Arc, Point, Spline, Polygon, Slot, Dimension, Trim, Extend }

/** Something in a sketch that can be tapped and selected. */
sealed class SketchItem {
    data class P(val point: Point) : SketchItem()
    data class C(val curve: Curve) : SketchItem()
    data class K(val constraint: Constraint) : SketchItem()
}

/**
 * Where a touch lands once snapped: on an existing point, along a curve, at a
 * line's midpoint, on an axis, or level or upright with the last point drawn.
 */
class Snap(
    val u: Double, val v: Double,
    val point: Point? = null,
    val curve: Curve? = null,
    val midpointOf: Line? = null,
    val axis: Axis? = null,
    val alignedWith: Point? = null,
    val horizontal: Boolean = false,
) {
    enum class Axis { X, Y }
}

/** A dimension being typed: a new one ([make]) or a change to [existing]. Angles are typed in degrees. */
class DimensionEdit(
    val existing: Constraint.Dimension?,
    val make: ((Double) -> Constraint.Dimension)?,
    val initial: Double,
    val isAngle: Boolean,
    val label: String,
    /** For a number that isn't a dimension, such as an offset: does the job, returning null or why it couldn't. */
    val action: ((Double) -> String?)? = null,
)

/** A constraint that fits the selection, offered in the Constrain sheet. */
class ConstraintChoice(val label: String, val make: () -> Constraint)

/**
 * Everything about editing one sketch: the tool in hand, what's selected,
 * drawing in progress, and undo. Positions are in the sketch plane's mm; the
 * overlay converts touches and passes a snapping distance in mm.
 */
class SketchEditor(
    val plane: SketchPlane,
    val name: String,
    val sketch: Sketch,
    private val finder: RegionFinder,
    /** The edges of the face it's on, for Project; null for a sketch that isn't on a face. */
    val outline: (() -> List<ProfileCurve>?)? = null,
    /** The design's parameters, for dimensions typed with names. */
    private val names: () -> Map<String, Double> = { emptyMap() },
) {
    /** Goes up whenever anything changes, so the overlay redraws. */
    var version by mutableIntStateOf(0)
        private set
    var tool by mutableStateOf(SketchTool.Line)
        private set
    /** New curves are drawn as construction curves. */
    var construction by mutableStateOf(false)
    val selection = mutableStateListOf<SketchItem>()
    var message by mutableStateOf<String?>(null)
    var editing by mutableStateOf<DimensionEdit?>(null)
        private set

    var regions: List<SketchRegion> = emptyList()
        private set
    var freedom: Sketch.Freedom = sketch.freedom()
        private set

    /** The finger's snapped position while a drawing tool is pressed. */
    var preview by mutableStateOf<Snap?>(null)
        private set
    /** Points placed so far by the drawing tool. */
    val pending = mutableStateListOf<Point>()
    private val placedForPending = mutableListOf<Point>()

    private val dimensionPicks = mutableListOf<SketchItem>()
    private var dragging: List<Point> = emptyList()
    private var dragFrom: Sketch.Snapshot? = null
    private var dragStart = 0.0 to 0.0
    private var dragged = false

    private val undoStack = ArrayDeque<Sketch.Snapshot>()
    private val redoStack = ArrayDeque<Sketch.Snapshot>()
    val canUndo get() = version >= 0 && undoStack.isNotEmpty()
    val canRedo get() = version >= 0 && redoStack.isNotEmpty()

    init {
        changed()
    }

    fun selectTool(t: SketchTool) {
        endDrawing()
        dimensionPicks.clear()
        editing = null
        if (t != SketchTool.Select) selection.clear()
        tool = t
        version++
    }

    /** Ends a chain of lines or a half-drawn shape; points it left on their own go. A spline in progress is made. */
    fun endDrawing() {
        if (tool == SketchTool.Spline && pending.size >= 2) {
            sketch.addSpline(pending.toList(), construction)
            placedForPending.clear()
        }
        for (p in placedForPending) if (sketch.curves.none { p in it.points() } && sketch.constraints.none { p in it.points() }) sketch.removePoint(p)
        placedForPending.clear()
        pending.clear()
        preview = null
        changed()
    }

    // Touches.

    /**
     * A finger went down. True if the sketch takes the drag (drawing, or
     * moving a point or curve); false leaves the drag free to pan the view.
     */
    fun press(u: Double, v: Double, tol: Double): Boolean {
        dragged = false
        if (isDrawing()) {
            preview = snap(u, v, tol)
            return true
        }
        if (tool == SketchTool.Select) {
            dragging = when (val hit = hitTest(u, v, tol)) {
                is SketchItem.P -> if (hit.point === sketch.origin) emptyList() else listOf(hit.point)
                is SketchItem.C -> hit.curve.points()
                else -> emptyList()
            }
            dragFrom = sketch.snapshot()
            dragStart = u to v
            return dragging.isNotEmpty()
        }
        return false
    }

    /** A second finger came down: whatever the first was doing stops. */
    fun cancelPress() {
        preview = null
        if (dragged) changed()
        dragging = emptyList()
        dragged = false
    }

    fun move(u: Double, v: Double, tol: Double) {
        if (isDrawing()) {
            preview = snap(u, v, tol)
            return
        }
        if (dragging.isEmpty()) return
        val from = dragFrom ?: return
        if (!dragged) {
            dragged = true
            checkpoint(from)
        }
        if (dragging.size == 1) sketch.drag(dragging[0], u, v)
        else sketch.dragAll(dragging, from, u - dragStart.first, v - dragStart.second)
        version++
    }

    /** The finger lifted. [moved] if it went further than a tap. */
    fun release(u: Double, v: Double, tol: Double, moved: Boolean) {
        if (isDrawing()) {
            commit(snap(u, v, tol))
            preview = null
            return
        }
        if (dragged) {
            dragging = emptyList()
            changed()
            return
        }
        dragging = emptyList()
        if (!moved) tap(hitTest(u, v, tol), u, v)
    }

    /** Something was tapped: from [release], or a dimension or glyph the overlay found on screen. */
    fun tap(item: SketchItem?, u: Double = 0.0, v: Double = 0.0) {
        if (tool == SketchTool.Trim || tool == SketchTool.Extend) {
            val c = (item as? SketchItem.C)?.curve ?: return
            checkpoint()
            val why = if (tool == SketchTool.Trim) SketchOps.trim(sketch, c, u, v)
            else if (c is Line) SketchOps.extend(sketch, c, u, v) else "Only lines can be extended"
            if (why != null) {
                undoStack.removeLastOrNull()
                message = why
            }
            selection.clear()
            changed()
            return
        }
        if (tool == SketchTool.Dimension) {
            pickForDimension(item)
            return
        }
        if (item is SketchItem.K && item.constraint is Constraint.Dimension && selection.isEmpty()) {
            val d = item.constraint
            editing = DimensionEdit(d, null, shownValue(d), d is Constraint.Angle, labelOf(d))
            return
        }
        when {
            item == null -> selection.clear()
            item in selection -> selection.remove(item)
            else -> selection += item
        }
        version++
    }

    private fun isDrawing() = tool in setOf(
        SketchTool.Line, SketchTool.Rectangle, SketchTool.Circle, SketchTool.Arc, SketchTool.Point,
        SketchTool.Spline, SketchTool.Polygon, SketchTool.Slot,
    )

    /** How many sides the Polygon tool draws. */
    var polygonSides by mutableIntStateOf(6)

    // Drawing.

    private fun commit(s: Snap) {
        when (tool) {
            SketchTool.Point -> {
                if (s.point != null) return
                checkpoint()
                place(s)
                placedForPending.clear()
            }
            SketchTool.Line -> {
                val start = pending.lastOrNull()
                if (start == null) {
                    checkpoint()
                    pending += placeForPending(s)
                } else {
                    if (s.point === start || hypot(s.u - sketch.x(start), s.v - sketch.y(start)) < 1e-6) return
                    checkpoint()
                    val p = place(s)
                    val line = sketch.addLine(start, p, construction)
                    if (s.alignedWith === start) addQuietly(if (s.horizontal) Constraint.Horizontal(line) else Constraint.Vertical(line))
                    placedForPending.clear()
                    // Closing the chain on its first point ends it.
                    if (p === pending.first()) pending.clear() else {
                        pending.clear()
                        pending += p
                    }
                }
            }
            SketchTool.Rectangle -> {
                val first = pending.firstOrNull()
                if (first == null) {
                    checkpoint()
                    pending += placeForPending(s)
                } else {
                    if (abs(s.u - sketch.x(first)) < 1e-6 || abs(s.v - sketch.y(first)) < 1e-6) return
                    checkpoint()
                    val p3 = place(s)
                    val p2 = sketch.addPoint(sketch.x(p3), sketch.y(first))
                    val p4 = sketch.addPoint(sketch.x(first), sketch.y(p3))
                    val sides = listOf(
                        sketch.addLine(first, p2, construction), sketch.addLine(p2, p3, construction),
                        sketch.addLine(p3, p4, construction), sketch.addLine(p4, first, construction),
                    )
                    sides.forEachIndexed { i, l -> addQuietly(if (i % 2 == 0) Constraint.Horizontal(l) else Constraint.Vertical(l)) }
                    placedForPending.clear()
                    pending.clear()
                }
            }
            SketchTool.Circle -> {
                val centre = pending.firstOrNull()
                if (centre == null) {
                    checkpoint()
                    pending += placeForPending(s)
                } else {
                    val r = hypot(s.u - sketch.x(centre), s.v - sketch.y(centre))
                    if (r < 1e-6) return
                    checkpoint()
                    val c = sketch.addCircle(centre, r, construction)
                    s.point?.let { addQuietly(Constraint.OnCircle(it, c)) }
                    placedForPending.clear()
                    pending.clear()
                }
            }
            SketchTool.Arc -> {
                when (pending.size) {
                    0 -> { checkpoint(); pending += placeForPending(s) }
                    1 -> {
                        if (s.point === pending[0]) return
                        checkpoint()
                        pending += placeForPending(s)
                    }
                    else -> {
                        val centre = pending[0]
                        val start = pending[1]
                        val r = sketch.distance(centre, start)
                        val a = atan2(s.v - sketch.y(centre), s.u - sketch.x(centre))
                        checkpoint()
                        val end = s.point ?: sketch.addPoint(sketch.x(centre) + r * cos(a), sketch.y(centre) + r * sin(a))
                        if (end === start) return
                        sketch.addArc(centre, start, end, construction)
                        placedForPending.clear()
                        pending.clear()
                    }
                }
            }
            SketchTool.Spline -> {
                val first = pending.firstOrNull()
                if (first == null) checkpoint()
                if (s.point != null && s.point === pending.lastOrNull()) return
                val p = placeForPending(s)
                // Tapping the first point again closes the loop and finishes it.
                if (p === first && pending.size >= 3) {
                    sketch.addSpline(pending.toList() + p, construction)
                    placedForPending.clear()
                    pending.clear()
                } else pending += p
            }
            SketchTool.Polygon -> {
                val centre = pending.firstOrNull()
                if (centre == null) {
                    checkpoint()
                    pending += placeForPending(s)
                } else {
                    val r = hypot(s.u - sketch.x(centre), s.v - sketch.y(centre))
                    if (r < 1e-6) return
                    checkpoint()
                    polygon(centre, r, atan2(s.v - sketch.y(centre), s.u - sketch.x(centre)))
                    placedForPending.clear()
                    pending.clear()
                }
            }
            SketchTool.Slot -> {
                when (pending.size) {
                    0 -> { checkpoint(); pending += placeForPending(s) }
                    1 -> {
                        if (s.point === pending[0]) return
                        pending += placeForPending(s)
                    }
                    else -> {
                        val a = pending[0]; val b = pending[1]
                        // Half the width: how far the third tap is from the line between the centres.
                        val dx = sketch.x(b) - sketch.x(a); val dy = sketch.y(b) - sketch.y(a)
                        val len = hypot(dx, dy)
                        val half = kotlin.math.abs(dx * (s.v - sketch.y(a)) - dy * (s.u - sketch.x(a))) / len
                        if (half < 1e-6) return
                        slot(a, b, half)
                        placedForPending.clear()
                        pending.clear()
                    }
                }
            }
            else -> {}
        }
        changed()
    }

    /** A regular polygon round a construction circle, its corners on the circle and its sides equal. */
    private fun polygon(centre: Point, r: Double, startAngle: Double) {
        val n = polygonSides.coerceIn(3, 64)
        val circle = sketch.addCircle(centre, r, construction = true)
        val corners = (0 until n).map { i ->
            val a = startAngle + 2 * PI * i / n
            sketch.addPoint(sketch.x(centre) + r * cos(a), sketch.y(centre) + r * sin(a)).also { addQuietly(Constraint.OnCircle(it, circle)) }
        }
        val sides = (0 until n).map { i -> sketch.addLine(corners[i], corners[(i + 1) % n], construction) }
        for (i in 1 until n) addQuietly(Constraint.Equal(sides[0], sides[i]))
    }

    /** A slot round two centres: an arc at each end joined by two straight sides. */
    private fun slot(a: Point, b: Point, half: Double) {
        val ax = sketch.x(a); val ay = sketch.y(a); val bx = sketch.x(b); val by = sketch.y(b)
        val len = hypot(bx - ax, by - ay)
        val nx = -(by - ay) / len * half; val ny = (bx - ax) / len * half
        val a1 = sketch.addPoint(ax + nx, ay + ny); val a2 = sketch.addPoint(ax - nx, ay - ny)
        val b1 = sketch.addPoint(bx + nx, by + ny); val b2 = sketch.addPoint(bx - nx, by - ny)
        val endA = sketch.addArc(a, a1, a2, construction)
        val endB = sketch.addArc(b, b2, b1, construction)
        val side1 = sketch.addLine(a1, b1, construction)
        val side2 = sketch.addLine(a2, b2, construction)
        sketch.addLine(a, b, construction = true)
        addQuietly(Constraint.TangentLine(side1, endA))
        addQuietly(Constraint.TangentLine(side2, endA))
        addQuietly(Constraint.TangentLine(side1, endB))
        addQuietly(Constraint.TangentLine(side2, endB))
        addQuietly(Constraint.Equal(endA, endB))
    }

    private fun placeForPending(s: Snap): Point {
        val p = place(s)
        if (s.point == null) placedForPending += p
        return p
    }

    /** A point where the snap says, held there by what it snapped to. */
    private fun place(s: Snap): Point {
        s.point?.let { return it }
        val p = sketch.addPoint(s.u, s.v)
        s.curve?.let { addQuietly(if (it is Line) Constraint.OnLine(p, it) else Constraint.OnCircle(p, it)) }
        s.midpointOf?.let { addQuietly(Constraint.Midpoint(p, it)) }
        when (s.axis) {
            Snap.Axis.X -> addQuietly(Constraint.HorizontalPoints(p, sketch.origin))
            Snap.Axis.Y -> addQuietly(Constraint.VerticalPoints(p, sketch.origin))
            null -> {}
        }
        return p
    }

    private fun addQuietly(c: Constraint) {
        sketch.add(c)
    }

    /** Snaps a touch to what's near it, within tol mm. */
    fun snap(u: Double, v: Double, tol: Double): Snap {
        val skip = if (tool == SketchTool.Line) emptySet() else pending.toSet()
        sketch.points.filter { it !in skip }
            .minByOrNull { hypot(sketch.x(it) - u, sketch.y(it) - v) }
            ?.takeIf { hypot(sketch.x(it) - u, sketch.y(it) - v) < tol }
            ?.let { return Snap(sketch.x(it), sketch.y(it), point = it) }

        for (c in sketch.curves) {
            if (c !is Line) continue
            val mx = (sketch.x(c.a) + sketch.x(c.b)) / 2
            val my = (sketch.y(c.a) + sketch.y(c.b)) / 2
            if (hypot(mx - u, my - v) < tol) return Snap(mx, my, midpointOf = c)
        }

        sketch.curves.mapNotNull { c -> nearestOn(c, u, v)?.let { c to it } }
            .minByOrNull { (_, q) -> hypot(q.first - u, q.second - v) }
            ?.takeIf { (_, q) -> hypot(q.first - u, q.second - v) < tol }
            ?.let { (c, q) -> return Snap(q.first, q.second, curve = c) }

        val last = pending.lastOrNull()
        var su = u
        var sv = v
        var axis: Snap.Axis? = null
        if (abs(v) < tol) { sv = 0.0; axis = Snap.Axis.X } else if (abs(u) < tol) { su = 0.0; axis = Snap.Axis.Y }
        if (last != null && tool == SketchTool.Line && axis == null) {
            val dx = u - sketch.x(last)
            val dy = v - sketch.y(last)
            val angle = abs(atan2(dy, dx))
            val off = 4 * PI / 180
            if (angle < off || angle > PI - off) return Snap(u, sketch.y(last), alignedWith = last, horizontal = true)
            if (abs(angle - PI / 2) < off) return Snap(sketch.x(last), v, alignedWith = last, horizontal = false)
        }
        return Snap(su, sv, axis = axis)
    }

    /** The point on a curve nearest (u, v), or null if it's off the end of a line or arc. */
    private fun nearestOn(c: Curve, u: Double, v: Double): Pair<Double, Double>? = when (c) {
        is Line -> {
            val ax = sketch.x(c.a); val ay = sketch.y(c.a)
            val dx = sketch.x(c.b) - ax; val dy = sketch.y(c.b) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((u - ax) * dx + (v - ay) * dy) / len2
            if (t < 0 || t > 1) null else ax + t * dx to ay + t * dy
        }
        is Circle -> onCircle(sketch.x(c.centre), sketch.y(c.centre), sketch.radius(c), u, v)
        is Spline -> sketch.sampleSpline(c).minByOrNull { hypot(it.first - u, it.second - v) }
        is Arc -> {
            val cx = sketch.x(c.centre); val cy = sketch.y(c.centre)
            if (onArc(c, atan2(v - cy, u - cx))) onCircle(cx, cy, sketch.radius(c), u, v) else null
        }
    }

    private fun onCircle(cx: Double, cy: Double, r: Double, u: Double, v: Double): Pair<Double, Double> {
        val a = atan2(v - cy, u - cx)
        return cx + r * cos(a) to cy + r * sin(a)
    }

    private fun onArc(arc: Arc, angle: Double): Boolean {
        val cx = sketch.x(arc.centre); val cy = sketch.y(arc.centre)
        val a0 = atan2(sketch.y(arc.start) - cy, sketch.x(arc.start) - cx)
        var span = atan2(sketch.y(arc.end) - cy, sketch.x(arc.end) - cx) - a0
        while (span <= 0) span += 2 * PI
        var t = angle - a0
        while (t < 0) t += 2 * PI
        return t <= span
    }

    /** What's under (u, v) within tol: points before curves. */
    fun hitTest(u: Double, v: Double, tol: Double): SketchItem? {
        sketch.points.minByOrNull { hypot(sketch.x(it) - u, sketch.y(it) - v) }
            ?.takeIf { hypot(sketch.x(it) - u, sketch.y(it) - v) < tol }
            ?.let { return SketchItem.P(it) }
        return sketch.curves.mapNotNull { c -> nearestOn(c, u, v)?.let { c to hypot(it.first - u, it.second - v) } }
            .minByOrNull { it.second }
            ?.takeIf { it.second < tol }
            ?.let { SketchItem.C(it.first) }
    }

    // Dimensions.

    private fun pickForDimension(item: SketchItem?) {
        if (item is SketchItem.K) {
            val d = item.constraint as? Constraint.Dimension ?: return
            editing = DimensionEdit(d, null, shownValue(d), d is Constraint.Angle, labelOf(d))
            return
        }
        if (item == null) {
            dimensionPicks.clear()
            selection.clear()
            editing = null
            version++
            return
        }
        if (dimensionPicks.size >= 2) dimensionPicks.clear()
        dimensionPicks += item
        selection.clear()
        selection += dimensionPicks
        editing = dimensionFor(dimensionPicks)
        version++
    }

    private fun dimensionFor(picks: List<SketchItem>): DimensionEdit? {
        val points = picks.filterIsInstance<SketchItem.P>().map { it.point }
        val curves = picks.filterIsInstance<SketchItem.C>().map { it.curve }
        if (picks.size == 1 && curves.size == 1) {
            return when (val c = curves[0]) {
                is Line -> DimensionEdit(null, { Constraint.Length(c, it) }, sketch.length(c), false, "Length")
                is Circle -> DimensionEdit(null, { Constraint.Radius(c, true, it) }, 2 * sketch.radius(c), false, "Diameter")
                is Arc -> DimensionEdit(null, { Constraint.Radius(c, false, it) }, sketch.radius(c), false, "Radius")
                is Spline -> null
            }
        }
        if (points.size == 2) return DimensionEdit(null, { Constraint.Distance(points[0], points[1], it) }, sketch.distance(points[0], points[1]), false, "Distance")
        if (points.size == 1 && curves.size == 1 && curves[0] is Line) {
            val l = curves[0] as Line
            val d = abs(signedDistance(points[0], l))
            return DimensionEdit(null, { Constraint.PointLineDistance(points[0], l, it) }, d, false, "Distance")
        }
        if (curves.size == 2 && curves.all { it is Line }) {
            val (l1, l2) = curves.map { it as Line }
            var a = sketch.angle(l2) - sketch.angle(l1)
            while (a <= -PI) a += 2 * PI
            while (a > PI) a -= 2 * PI
            if (abs(sin(a)) < 1e-6) {
                // Parallel: the gap between them.
                val d = abs(signedDistance(l2.a, l1))
                return DimensionEdit(null, { Constraint.PointLineDistance(l2.a, l1, it) }, d, false, "Distance")
            }
            // The smaller angle between them, measured the way they turn.
            val sign = if (a >= 0) 1 else -1
            return DimensionEdit(null, { Constraint.Angle(l1, l2, sign * it) }, abs(a) * 180 / PI, true, "Angle")
        }
        return null
    }

    private fun signedDistance(p: Point, l: Line): Double {
        val dx = sketch.x(l.b) - sketch.x(l.a); val dy = sketch.y(l.b) - sketch.y(l.a)
        return (dx * (sketch.y(p) - sketch.y(l.a)) - dy * (sketch.x(p) - sketch.x(l.a))) / hypot(dx, dy).coerceAtLeast(1e-12)
    }

    private fun labelOf(d: Constraint.Dimension) = when (d) {
        is Constraint.Radius -> if (d.diameter) "Diameter" else "Radius"
        is Constraint.Angle -> "Angle"
        is Constraint.Length -> "Length"
        else -> "Distance"
    }

    /** A dimension's value as typed: degrees for angles. */
    fun shownValue(d: Constraint.Dimension) = if (d is Constraint.Angle) abs(d.value) * 180 / PI else d.value

    /** Applies the typed text. False, with a message, if it can't be read or met. */
    fun commitDimension(text: String): Boolean {
        val edit = editing ?: return false
        val typed = Expression.evaluate(text, names())
        val expression = if (Expression.usesNames(text)) text.trim() else null
        if (typed == null || typed <= 0 && !edit.isAngle) {
            message = "That isn't a number"
            return false
        }
        val value = if (edit.isAngle) typed * PI / 180 else typed
        checkpoint()
        val ok = if (edit.action != null) {
            val why = edit.action.invoke(value)
            if (why != null) message = why
            why == null
        } else if (edit.existing != null) {
            val d = edit.existing
            sketch.setDimension(d, if (d is Constraint.Angle && d.value < 0) -value else value).also { if (it) d.expression = expression }
        } else {
            when (sketch.add(edit.make!!(value).also { it.expression = expression })) {
                Sketch.Added.Yes -> true
                Sketch.Added.AlreadySet -> { message = "That's already set by other constraints"; false }
                Sketch.Added.Conflicts -> { message = "That doesn't fit the other constraints"; false }
            }
        }
        if (!ok) {
            undoStack.removeLastOrNull()
            if (message == null) message = "That doesn't fit the other constraints"
            return false
        }
        cancelDimension()
        changed()
        return true
    }

    fun cancelDimension() {
        editing = null
        dimensionPicks.clear()
        if (tool == SketchTool.Dimension) selection.clear()
        version++
    }

    /** Brings the face's edges into the sketch as fixed curves to draw against. */
    fun projectOutline() {
        val curves = outline?.invoke() ?: run { message = "The face's edges couldn't be found"; return }
        checkpoint()
        fun fixedPoint(u: Double, v: Double): Point {
            sketch.points.firstOrNull { it !== sketch.origin && hypot(sketch.x(it) - u, sketch.y(it) - v) < 1e-6 }?.let { return it }
            return sketch.addPoint(u, v).also { addQuietly(Constraint.Fixed(it, u, v)) }
        }
        for (c in curves) when (c.kind) {
            ProfileCurve.Kind.Line -> sketch.addLine(fixedPoint(c.x1, c.y1), fixedPoint(c.x2, c.y2))
            ProfileCurve.Kind.Circle -> sketch.addCircle(fixedPoint(c.x1, c.y1), c.r).also { addQuietly(Constraint.Radius(it, true, 2 * c.r)) }
            ProfileCurve.Kind.Arc -> sketch.addArc(
                fixedPoint(c.x1, c.y1),
                fixedPoint(c.x1 + c.r * cos(c.a0), c.y1 + c.r * sin(c.a0)),
                fixedPoint(c.x1 + c.r * cos(c.a1), c.y1 + c.r * sin(c.a1)),
            )
            ProfileCurve.Kind.Bezier -> {}
        }
        changed()
    }

    // Offset and corner fillet, which ask for a number first.

    /** Curves selected, for offset. */
    val selectedCurves get() = selection.filterIsInstance<SketchItem.C>().map { it.curve }
    /** The one point selected, for a corner fillet. */
    val selectedCorner get() = selection.singleOrNull()?.let { it as? SketchItem.P }?.point

    fun startOffset() {
        val curves = selectedCurves
        if (curves.isEmpty()) return
        editing = DimensionEdit(null, null, 2.0, false, "Offset", action = { d -> SketchOps.offset(sketch, curves, d).also { if (it == null) selection.clear() } })
    }

    fun startCornerFillet() {
        val p = selectedCorner ?: return
        editing = DimensionEdit(null, null, 2.0, false, "Radius", action = { r -> SketchOps.filletCorner(sketch, p, r).also { if (it == null) selection.clear() } })
    }

    // Constraints and the selection.

    fun constraintChoices(): List<ConstraintChoice> {
        val points = selection.filterIsInstance<SketchItem.P>().map { it.point }
        val lines = selection.filterIsInstance<SketchItem.C>().map { it.curve }.filterIsInstance<Line>()
        val rounds = selection.filterIsInstance<SketchItem.C>().map { it.curve }.filter { it is Circle || it is Arc }
        val n = selection.size
        val out = mutableListOf<ConstraintChoice>()
        if (n == 1 && lines.size == 1) {
            out += ConstraintChoice("Horizontal") { Constraint.Horizontal(lines[0]) }
            out += ConstraintChoice("Vertical") { Constraint.Vertical(lines[0]) }
        }
        if (n == 2 && lines.size == 2) {
            out += ConstraintChoice("Parallel") { Constraint.Parallel(lines[0], lines[1]) }
            out += ConstraintChoice("Perpendicular") { Constraint.Perpendicular(lines[0], lines[1]) }
            out += ConstraintChoice("Equal") { Constraint.Equal(lines[0], lines[1]) }
        }
        if (n == 2 && lines.size == 1 && rounds.size == 1) out += ConstraintChoice("Tangent") { Constraint.TangentLine(lines[0], rounds[0]) }
        if (n == 2 && rounds.size == 2) {
            out += ConstraintChoice("Equal") { Constraint.Equal(rounds[0], rounds[1]) }
            out += ConstraintChoice("Concentric") { Constraint.Coincident(centreOf(rounds[0]), centreOf(rounds[1])) }
            val inside = sketch.distance(centreOf(rounds[0]), centreOf(rounds[1])) < maxOf(sizeOf(rounds[0]), sizeOf(rounds[1]))
            out += ConstraintChoice("Tangent") { Constraint.TangentCircles(rounds[0], rounds[1], inside) }
        }
        if (n == 2 && points.size == 2) {
            out += ConstraintChoice("Coincident") { Constraint.Coincident(points[0], points[1]) }
            out += ConstraintChoice("Horizontal") { Constraint.HorizontalPoints(points[0], points[1]) }
            out += ConstraintChoice("Vertical") { Constraint.VerticalPoints(points[0], points[1]) }
        }
        if (n == 2 && points.size == 1 && lines.size == 1) {
            out += ConstraintChoice("On line") { Constraint.OnLine(points[0], lines[0]) }
            out += ConstraintChoice("Midpoint") { Constraint.Midpoint(points[0], lines[0]) }
        }
        if (n == 2 && points.size == 1 && rounds.size == 1) out += ConstraintChoice("On circle") { Constraint.OnCircle(points[0], rounds[0]) }
        if (n == 3 && points.size == 2 && lines.size == 1) out += ConstraintChoice("Symmetric") { Constraint.Symmetric(points[0], points[1], lines[0]) }
        if (n == 1 && points.size == 1 && points[0] !== sketch.origin) {
            val p = points[0]
            out += ConstraintChoice("Fix") { Constraint.Fixed(p, sketch.x(p), sketch.y(p)) }
        }
        return out
    }

    private fun centreOf(c: Curve) = when (c) {
        is Circle -> c.centre
        is Arc -> c.centre
        is Line -> c.a
        is Spline -> c.through.first()
    }

    private fun sizeOf(c: Curve) = when (c) {
        is Circle -> sketch.radius(c)
        is Arc -> sketch.radius(c)
        is Line -> sketch.length(c)
        is Spline -> 0.0
    }

    fun apply(choice: ConstraintChoice) {
        checkpoint()
        when (sketch.add(choice.make())) {
            Sketch.Added.Yes -> selection.clear()
            Sketch.Added.AlreadySet -> { undoStack.removeLastOrNull(); message = "That's already set by other constraints" }
            Sketch.Added.Conflicts -> { undoStack.removeLastOrNull(); message = "That doesn't fit the other constraints" }
        }
        changed()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        checkpoint()
        for (item in selection.toList()) when (item) {
            is SketchItem.K -> sketch.remove(item.constraint)
            is SketchItem.C -> sketch.remove(item.curve)
            is SketchItem.P -> sketch.removePoint(item.point)
        }
        selection.clear()
        changed()
    }

    /** Makes the selected curves construction curves or back; with none selected, switches what new curves are. */
    fun toggleConstruction() {
        val curves = selection.filterIsInstance<SketchItem.C>().map { it.curve }
        if (curves.isEmpty()) {
            construction = !construction
            return
        }
        checkpoint()
        val to = !curves.all { it.construction }
        for (c in curves) sketch.setConstruction(c, to)
        changed()
    }

    // Undo.

    private fun checkpoint(snapshot: Sketch.Snapshot = sketch.snapshot()) {
        undoStack.addLast(snapshot)
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(sketch.snapshot())
        restore(s)
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(sketch.snapshot())
        restore(s)
    }

    private fun restore(s: Sketch.Snapshot) {
        sketch.restore(s)
        pending.clear()
        placedForPending.clear()
        selection.clear()
        editing = null
        dimensionPicks.clear()
        changed()
    }

    private fun changed() {
        freedom = sketch.freedom()
        regions = finder.find(sketch.profileCurves())
        version++
    }
}
