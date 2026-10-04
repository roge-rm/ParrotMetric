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
    /** The design's construction points, which Project brings in as fixed points. */
    private val points: () -> List<com.rm.parrotmetric.sketch.Vec3> = { emptyList() },
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
        typed = null
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

    /** This press placed a shape's first point, so lifting without moving leaves the shape waiting for its next. */
    private var startedShape = false
    /** Where this press snapped, for a line dragged out away from the chain it was continuing. */
    private var pressedAt: Snap? = null

    /**
     * A finger went down. True if the sketch takes the drag (drawing, or
     * moving a point or curve); false leaves the drag free to pan the view.
     *
     * Shapes are dragged out: the press places the first point and lifting
     * places the next, so one drag makes a line, rectangle or circle. Taps
     * work too, one point each.
     */
    fun press(u: Double, v: Double, tol: Double): Boolean {
        dragged = false
        if (isDrawing()) {
            val s = snap(u, v, tol)
            preview = s
            pressedAt = s
            startedShape = false
            if (pending.isEmpty() && tool != SketchTool.Point && tool != SketchTool.Spline) {
                commit(s)
                startedShape = true
            }
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

    /** The mouse moved with no button down: the shape being drawn follows it. Null when it leaves the sketch. */
    fun hover(u: Double?, v: Double, tol: Double) {
        if (u != null) aim = u to v
        preview = when {
            u == null || !isDrawing() -> null
            typed != null -> typedTarget(u, v).let { Snap(it.first, it.second) }
            else -> snap(u, v, tol)
        }
    }

    /** A second finger came down: whatever the first was doing stops. */
    fun cancelPress() {
        if (startedShape) endDrawing()
        startedShape = false
        preview = null
        if (dragged) changed()
        dragging = emptyList()
        dragged = false
    }

    fun move(u: Double, v: Double, tol: Double) {
        if (isDrawing()) {
            aim = u to v
            preview = if (typed != null) typedTarget(u, v).let { Snap(it.first, it.second) } else snap(u, v, tol)
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
        if (isDrawing() && typed != null && pending.isNotEmpty()) {
            // Sizes were typed: the click places the shape at them, towards the pointer.
            aim = u to v
            applyTyped()
            startedShape = false
            pressedAt = null
            return
        }
        if (isDrawing()) {
            val start = pressedAt
            // A line dragged out away from the chain's end starts a new chain where the drag began.
            if (tool == SketchTool.Line && moved && !startedShape && start != null) {
                val end = pending.lastOrNull()
                if (end != null && start.point !== end && hypot(start.u - sketch.x(end), start.v - sketch.y(end)) > tol) {
                    endDrawing()
                    commit(start)
                }
            }
            if (!(startedShape && !moved)) commit(snap(u, v, tol))
            startedShape = false
            pressedAt = null
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

    // How each tool draws; changing one drops a half-drawn shape.
    var rectangleStyle by mutableStateOf(RectangleStyle.Corners)
    var circleStyle by mutableStateOf(CircleStyle.Centre)
    var arcStyle by mutableStateOf(ArcStyle.CentreEnds)
    var polygonStyle by mutableStateOf(PolygonStyle.Inside)
    var slotStyle by mutableStateOf(SlotStyle.Centres)


    // Sizes typed while drawing.

    /** Sizes typed for the shape being drawn, one per field; Tab moves between them and Enter places the shape. */
    class TypedSizes(val labels: List<String>, val angle: List<Boolean>) {
        val texts = mutableStateListOf<String>().apply { repeat(labels.size) { add("") } }
        var active by mutableIntStateOf(0)
    }

    var typed by mutableStateOf<TypedSizes?>(null)
        private set
    /** Where the pointer last was on the plane, which way typed sizes go. */
    private var aim: Pair<Double, Double>? = null

    /** The sizes the shape being drawn can take next, as (label, is an angle), or null if none. */
    private fun sizeFields(): List<Pair<String, Boolean>>? {
        if (pending.isEmpty()) return null
        return when (tool) {
            SketchTool.Line -> listOf("Length" to false, "Angle" to true)
            SketchTool.Rectangle -> when {
                rectangleStyle != RectangleStyle.ThreePoints -> listOf("Width" to false, "Height" to false)
                pending.size == 1 -> listOf("Length" to false, "Angle" to true)
                else -> listOf("Width" to false)
            }
            SketchTool.Circle -> if (circleStyle == CircleStyle.ThreePoints) null else listOf("Diameter" to false)
            SketchTool.Polygon -> listOf("Radius" to false)
            SketchTool.Arc -> when (arcStyle) {
                ArcStyle.ThreePoints -> if (pending.size == 1) listOf("Length" to false) else null
                ArcStyle.CentreEnds -> if (pending.size == 1) listOf("Radius" to false) else listOf("Angle" to true)
                ArcStyle.Tangent -> null
            }
            SketchTool.Slot -> when {
                pending.size >= 2 -> listOf("Width" to false)
                slotStyle == SlotStyle.Middle -> null
                else -> listOf("Length" to false)
            }
            else -> null
        }
    }

    /** A key typed while drawing. True if it went into a size: a digit starts one, then anything printable carries on. */
    fun typeKey(c: Char): Boolean {
        val t = typed ?: run {
            if (!com.rm.parrotmetric.ui.startsNumber(c)) return false
            val fields = sizeFields() ?: return false
            TypedSizes(fields.map { it.first }, fields.map { it.second }).also { typed = it }
        }
        t.texts[t.active] = t.texts[t.active] + c
        followAim()
        return true
    }

    /** Backspace while typing a size. False when there's nothing typed. */
    fun typedBackspace(): Boolean {
        val t = typed ?: return false
        val text = t.texts[t.active]
        if (text.isNotEmpty()) t.texts[t.active] = text.dropLast(1)
        if (t.texts.all { it.isEmpty() }) typed = null
        followAim()
        return true
    }

    /** Tab while typing a size: the next one, or with [back] the one before. */
    fun typedNext(back: Boolean): Boolean {
        val t = typed ?: run {
            val fields = sizeFields() ?: return false
            TypedSizes(fields.map { it.first }, fields.map { it.second }).also { typed = it }
            return true
        }
        val n = t.labels.size
        t.active = (t.active + (if (back) n - 1 else 1)) % n
        return true
    }

    /** Drops what's been typed. False if nothing was. */
    fun dropTyped(): Boolean {
        if (typed == null) return false
        typed = null
        followAim()
        return true
    }

    private fun typedValue(i: Int): Double? {
        val text = typed?.texts?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return null
        return Expression.evaluate(text, names())
    }

    /** Where the next point goes for the pointer at (u, v) with the sizes typed so far. */
    private fun typedTarget(u: Double, v: Double): Pair<Double, Double> {
        if (typed == null || pending.isEmpty()) return u to v
        val p0 = pending.first()
        val x0 = sketch.x(p0); val y0 = sketch.y(p0)
        fun along(cx: Double, cy: Double, r: Double?): Pair<Double, Double> {
            if (r == null) return u to v
            val d = hypot(u - cx, v - cy)
            return if (d < 1e-9) (cx + r) to cy else (cx + (u - cx) / d * r) to (cy + (v - cy) / d * r)
        }
        fun sign(d: Double) = if (d < 0) -1.0 else 1.0
        return when (tool) {
            SketchTool.Line -> {
                val a = pending.last()
                val ax = sketch.x(a); val ay = sketch.y(a)
                val len = typedValue(0) ?: hypot(u - ax, v - ay)
                val angle = typedValue(1)?.let { it * PI / 180 } ?: atan2(v - ay, u - ax)
                (ax + len * cos(angle)) to (ay + len * sin(angle))
            }
            SketchTool.Rectangle -> when {
                rectangleStyle == RectangleStyle.ThreePoints && pending.size == 1 -> {
                    val len = typedValue(0) ?: hypot(u - x0, v - y0)
                    val angle = typedValue(1)?.let { it * PI / 180 } ?: atan2(v - y0, u - x0)
                    (x0 + len * cos(angle)) to (y0 + len * sin(angle))
                }
                rectangleStyle == RectangleStyle.ThreePoints -> {
                    // The far side, the typed width from the first side, on the pointer's side of it.
                    val width = typedValue(0) ?: return u to v
                    val b = pending[1]
                    val dx = sketch.x(b) - x0; val dy = sketch.y(b) - y0
                    val len = hypot(dx, dy).coerceAtLeast(1e-9)
                    val side = sign(dx * (v - y0) - dy * (u - x0))
                    (sketch.x(b) - dy / len * width * side) to (sketch.y(b) + dx / len * width * side)
                }
                else -> {
                    val k = if (rectangleStyle == RectangleStyle.Centre) 0.5 else 1.0
                    val w = typedValue(0)?.let { sign(u - x0) * it * k } ?: (u - x0)
                    val h = typedValue(1)?.let { sign(v - y0) * it * k } ?: (v - y0)
                    (x0 + w) to (y0 + h)
                }
            }
            SketchTool.Circle -> if (circleStyle == CircleStyle.TwoPoints) along(x0, y0, typedValue(0)) else along(x0, y0, typedValue(0)?.let { it / 2 })
            SketchTool.Polygon -> along(x0, y0, typedValue(0))
            SketchTool.Arc -> if (arcStyle == ArcStyle.ThreePoints || pending.size == 1) along(x0, y0, typedValue(0)) else {
                val sweep = typedValue(0) ?: return u to v
                val p1 = pending[1]
                val r = sketch.distance(p0, p1)
                val a = atan2(sketch.y(p1) - y0, sketch.x(p1) - x0) + sweep * PI / 180
                (x0 + r * cos(a)) to (y0 + r * sin(a))
            }
            SketchTool.Slot -> if (pending.size == 1) along(x0, y0, typedValue(0)) else {
                val half = typedValue(0)?.let { it / 2 } ?: return u to v
                val b = pending[1]
                val dx = sketch.x(b) - x0; val dy = sketch.y(b) - y0
                val len = hypot(dx, dy).coerceAtLeast(1e-9)
                val side = sign(dx * (v - y0) - dy * (u - x0))
                (x0 - dy / len * half * side) to (y0 + dx / len * half * side)
            }
            else -> u to v
        }
    }

    /** The pointer's position when there's been none, as with a finger: straight to the right of the last point. */
    private fun aimOrDefault(): Pair<Double, Double> = aim ?: pending.lastOrNull()?.let { (sketch.x(it) + 1.0) to sketch.y(it) } ?: (1.0 to 0.0)

    private fun followAim() {
        if (isDrawing() && pending.isNotEmpty()) aimOrDefault().let { (u, v) -> preview = typedTarget(u, v).let { Snap(it.first, it.second) } }
        version++
    }

    /**
     * Places the next point at the typed sizes, towards the pointer, and holds
     * those sizes with dimensions. False if nothing was typed.
     */
    fun applyTyped(): Boolean {
        val t = typed ?: return false
        val values = t.texts.indices.map { typedValue(it) }
        for (i in t.texts.indices) {
            val v = values[i]
            if (t.texts[i].isNotBlank() && (v == null || (v <= 0 && !t.angle[i]))) {
                message = "That isn't a number"
                return true
            }
        }
        val (u, v) = aimOrDefault()
        val target = typedTarget(u, v)
        val before = sketch.curves.size
        val started = pending.toList()
        val drawing = tool
        typed = null
        commit(Snap(target.first, target.second))
        val made = sketch.curves.drop(before)
        fun hold(d: Constraint.Dimension, i: Int) {
            val text = t.texts[i].trim()
            if (Expression.usesNames(text)) d.expression = text
            addQuietly(d)
        }
        when (drawing) {
            SketchTool.Line -> made.filterIsInstance<Line>().firstOrNull()?.let { l ->
                values[0]?.let { hold(Constraint.Length(l, it), 0) }
                values[1]?.let { a ->
                    val m = ((a % 180) + 180) % 180
                    if (m == 0.0) addQuietly(Constraint.Horizontal(l)) else if (m == 90.0) addQuietly(Constraint.Vertical(l))
                }
            }
            SketchTool.Rectangle -> when {
                rectangleStyle != RectangleStyle.ThreePoints -> made.filterIsInstance<Line>().take(4).takeIf { it.size == 4 }?.let { sides ->
                    values[0]?.let { hold(Constraint.Length(sides[0], it), 0) }
                    values[1]?.let { hold(Constraint.Length(sides[1], it), 1) }
                }
                // The first side, as two points so far.
                started.size == 1 -> pending.getOrNull(1)?.let { p -> values[0]?.let { hold(Constraint.Distance(started[0], p, it), 0) } }
                else -> made.filterIsInstance<Line>().getOrNull(1)?.let { side -> values[0]?.let { hold(Constraint.Length(side, it), 0) } }
            }
            SketchTool.Circle -> made.filterIsInstance<Circle>().firstOrNull()?.let { c -> values[0]?.let { hold(Constraint.Radius(c, true, it), 0) } }
            SketchTool.Polygon -> made.filterIsInstance<Circle>().firstOrNull()?.let { c -> values[0]?.let { hold(Constraint.Radius(c, false, it), 0) } }
            // An overall slot's first two points are only guides, taken away once it's drawn.
            SketchTool.Arc, SketchTool.Slot -> if (started.size == 1 && !(drawing == SketchTool.Slot && slotStyle == SlotStyle.Overall)) {
                // The second point: its distance from the first (a radius, or a length).
                val p = pending.getOrNull(1)
                if (p != null) values[0]?.let { hold(Constraint.Distance(started[0], p, it), 0) }
            } else if (drawing == SketchTool.Slot) {
                made.filterIsInstance<Arc>().firstOrNull()?.let { a -> values[0]?.let { hold(Constraint.Radius(a, false, it / 2), 0) } }
            }
            else -> {}
        }
        changed()
        followAim()
        return true
    }

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
            SketchTool.Rectangle -> when (rectangleStyle) {
                RectangleStyle.Centre -> {
                    val centre = pending.firstOrNull()
                    if (centre == null) {
                        checkpoint()
                        pending += placeForPending(s)
                    } else {
                        val cx = sketch.x(centre); val cy = sketch.y(centre)
                        if (abs(s.u - cx) < 1e-6 || abs(s.v - cy) < 1e-6) return
                        checkpoint()
                        val p3 = place(s)
                        val p1 = sketch.addPoint(2 * cx - sketch.x(p3), 2 * cy - sketch.y(p3))
                        val p2 = sketch.addPoint(sketch.x(p3), sketch.y(p1))
                        val p4 = sketch.addPoint(sketch.x(p1), sketch.y(p3))
                        val sides = listOf(
                            sketch.addLine(p1, p2, construction), sketch.addLine(p2, p3, construction),
                            sketch.addLine(p3, p4, construction), sketch.addLine(p4, p1, construction),
                        )
                        sides.forEachIndexed { i, l -> addQuietly(if (i % 2 == 0) Constraint.Horizontal(l) else Constraint.Vertical(l)) }
                        // A construction diagonal keeps the centre in the middle.
                        addQuietly(Constraint.Midpoint(centre, sketch.addLine(p1, p3, construction = true)))
                        finishShape()
                    }
                }
                RectangleStyle.Corners -> {
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
                        finishShape()
                    }
                }
                RectangleStyle.ThreePoints -> when (pending.size) {
                    0 -> { checkpoint(); pending += placeForPending(s) }
                    1 -> { if (s.point !== pending[0]) pending += placeForPending(s) }
                    else -> {
                        val a = pending[0]; val b = pending[1]
                        val corners = rectangleFromSide(sketch.x(a), sketch.y(a), sketch.x(b), sketch.y(b), s.u, s.v) ?: return
                        val c = sketch.addPoint(corners[2].first, corners[2].second)
                        val d = sketch.addPoint(corners[3].first, corners[3].second)
                        val sides = listOf(
                            sketch.addLine(a, b, construction), sketch.addLine(b, c, construction),
                            sketch.addLine(c, d, construction), sketch.addLine(d, a, construction),
                        )
                        addQuietly(Constraint.Perpendicular(sides[0], sides[1]))
                        addQuietly(Constraint.Parallel(sides[0], sides[2]))
                        addQuietly(Constraint.Parallel(sides[1], sides[3]))
                        finishShape()
                    }
                }
            }
            SketchTool.Circle -> when (circleStyle) {
                CircleStyle.Centre -> {
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
                        finishShape()
                    }
                }
                CircleStyle.TwoPoints -> {
                    val first = pending.firstOrNull()
                    if (first == null) {
                        checkpoint()
                        pending += placeForPending(s)
                    } else {
                        val across = hypot(s.u - sketch.x(first), s.v - sketch.y(first))
                        if (s.point === first || across < 1e-6) return
                        val p = place(s)
                        val centre = sketch.addPoint((sketch.x(first) + sketch.x(p)) / 2, (sketch.y(first) + sketch.y(p)) / 2)
                        val c = sketch.addCircle(centre, across / 2, construction)
                        // The two points are ends of a diameter.
                        addQuietly(Constraint.Midpoint(centre, sketch.addLine(first, p, construction = true)))
                        addQuietly(Constraint.OnCircle(first, c))
                        addQuietly(Constraint.OnCircle(p, c))
                        finishShape()
                    }
                }
                CircleStyle.ThreePoints -> when (pending.size) {
                    0 -> { checkpoint(); pending += placeForPending(s) }
                    1 -> { if (s.point !== pending[0]) pending += placeForPending(s) }
                    else -> {
                        val a = pending[0]; val b = pending[1]
                        val c = circleThrough(sketch.x(a), sketch.y(a), sketch.x(b), sketch.y(b), s.u, s.v) ?: return
                        val p = place(s)
                        val centre = sketch.addPoint(c.first, c.second)
                        val circle = sketch.addCircle(centre, hypot(sketch.x(a) - c.first, sketch.y(a) - c.second), construction)
                        for (q in listOf(a, b, p)) addQuietly(Constraint.OnCircle(q, circle))
                        finishShape()
                    }
                }
            }
            SketchTool.Arc -> when (arcStyle) {
                ArcStyle.ThreePoints -> when (pending.size) {
                    0 -> { checkpoint(); pending += placeForPending(s) }
                    1 -> {
                        if (s.point === pending[0]) return
                        checkpoint()
                        pending += placeForPending(s)
                    }
                    else -> {
                        val a = pending[0]
                        val b = pending[1]
                        val c = circleThrough(sketch.x(a), sketch.y(a), sketch.x(b), sketch.y(b), s.u, s.v) ?: return
                        checkpoint()
                        val centre = sketch.addPoint(c.first, c.second)
                        // Arcs run anticlockwise from start to end, so pick the way that passes the third point.
                        val passes = anticlockwiseBetween(c, sketch.x(a), sketch.y(a), s.u, s.v, sketch.x(b), sketch.y(b))
                        if (passes) sketch.addArc(centre, a, b, construction) else sketch.addArc(centre, b, a, construction)
                        finishShape()
                    }
                }
                ArcStyle.CentreEnds -> when (pending.size) {
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
                        finishShape()
                    }
                }
                ArcStyle.Tangent -> if (pending.isEmpty()) {
                    val start = s.point
                    if (start == null || tangentAt(start) == null) {
                        message = "Start a tangent arc at the end of a line or arc"
                        return
                    }
                    checkpoint()
                    pending += start
                } else {
                    val start = pending[0]
                    if (s.point === start) return
                    val (dir, from) = tangentAt(start) ?: return
                    val (c, r, left) = tangentArc(sketch.x(start), sketch.y(start), dir.first, dir.second, s.u, s.v) ?: return
                    val end = place(s)
                    val centre = sketch.addPoint(c.first, c.second)
                    val arc = if (left) sketch.addArc(centre, start, end, construction) else sketch.addArc(centre, end, start, construction)
                    addQuietly(Constraint.TangentJoin(from, arc, start))
                    finishShape()
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
                    finishShape()
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
                    finishShape()
                }
            }
            SketchTool.Slot -> when (pending.size) {
                0 -> { checkpoint(); pending += placeForPending(s) }
                1 -> { if (s.point !== pending[0]) pending += placeForPending(s) }
                else -> {
                    val p1 = pending[0]; val p2 = pending[1]
                    val (a, b, half) = slotCentres(slotStyle, sketch.x(p1), sketch.y(p1), sketch.x(p2), sketch.y(p2), s.u, s.v) ?: return
                    when (slotStyle) {
                        SlotStyle.Centres -> slot(p1, p2, half)
                        SlotStyle.Overall -> {
                            // The ends placed were only to measure from.
                            for (p in placedForPending) if (sketch.constraints.none { p in it.points() }) sketch.removePoint(p)
                            slot(sketch.addPoint(a.first, a.second), sketch.addPoint(b.first, b.second), half)
                        }
                        SlotStyle.Middle -> addQuietly(Constraint.Midpoint(p1, slot(sketch.addPoint(a.first, a.second), p2, half)))
                    }
                    finishShape()
                }
            }
            else -> {}
        }
        changed()
    }

    /** A shape is done: the points placed for it stay, and the next starts afresh. */
    private fun finishShape() {
        placedForPending.clear()
        pending.clear()
    }

    /**
     * A regular polygon: its corners on a construction circle, or with
     * [PolygonStyle.Outside] its sides touching it, and its sides equal.
     */
    private fun polygon(centre: Point, r: Double, toward: Double) {
        val n = polygonSides.coerceIn(3, 64)
        val outside = polygonStyle == PolygonStyle.Outside
        val circle = sketch.addCircle(centre, r, construction = true)
        val cornerPoints = polygonCorners(sketch.x(centre), sketch.y(centre), r, toward, n, outside)
        // Outside, the corners are on a bigger circle too, which with equal sides keeps it regular.
        val around = if (outside) sketch.addCircle(centre, r / cos(PI / n), construction = true) else circle
        val corners = cornerPoints.map { (x, y) -> sketch.addPoint(x, y).also { addQuietly(Constraint.OnCircle(it, around)) } }
        val sides = (0 until n).map { i -> sketch.addLine(corners[i], corners[(i + 1) % n], construction) }
        for (i in 1 until n) addQuietly(Constraint.Equal(sides[0], sides[i]))
        if (outside) for (side in sides) addQuietly(Constraint.TangentLine(side, circle))
    }

    /** A slot round two centres: an arc at each end joined by two straight sides. Returns the construction line between the centres. */
    private fun slot(a: Point, b: Point, half: Double): Line {
        val ax = sketch.x(a); val ay = sketch.y(a); val bx = sketch.x(b); val by = sketch.y(b)
        val len = hypot(bx - ax, by - ay)
        val nx = -(by - ay) / len * half; val ny = (bx - ax) / len * half
        val a1 = sketch.addPoint(ax + nx, ay + ny); val a2 = sketch.addPoint(ax - nx, ay - ny)
        val b1 = sketch.addPoint(bx + nx, by + ny); val b2 = sketch.addPoint(bx - nx, by - ny)
        val endA = sketch.addArc(a, a1, a2, construction)
        val endB = sketch.addArc(b, b2, b1, construction)
        val side1 = sketch.addLine(a1, b1, construction)
        val side2 = sketch.addLine(a2, b2, construction)
        val middle = sketch.addLine(a, b, construction = true)
        addQuietly(Constraint.TangentJoin(side1, endA, a1))
        addQuietly(Constraint.TangentJoin(side2, endA, a2))
        addQuietly(Constraint.TangentJoin(side1, endB, b1))
        addQuietly(Constraint.TangentJoin(side2, endB, b2))
        addQuietly(Constraint.Equal(endA, endB))
        return middle
    }

    /** Which way a line or arc ending at [p] heads as it leaves p, and which curve it is; for a tangent arc. The newest wins. */
    internal fun tangentAt(p: Point): Pair<Pair<Double, Double>, Curve>? {
        for (c in sketch.curves.toList().asReversed()) {
            if (c is Line && (c.a === p || c.b === p)) {
                val other = if (c.a === p) c.b else c.a
                val dx = sketch.x(p) - sketch.x(other); val dy = sketch.y(p) - sketch.y(other)
                val len = hypot(dx, dy)
                if (len > 1e-9) return (dx / len to dy / len) to c
            }
            if (c is Arc && (c.start === p || c.end === p)) {
                val rx = sketch.x(p) - sketch.x(c.centre); val ry = sketch.y(p) - sketch.y(c.centre)
                val len = hypot(rx, ry)
                if (len < 1e-9) continue
                // Anticlockwise along the arc at p; leaving from its start goes the other way.
                val tx = -ry / len; val ty = rx / len
                return (if (c.end === p) tx to ty else -tx to -ty) to c
            }
        }
        return null
    }

    /** What the tool in hand would draw with its next point at (u, v), to show before it's placed. */
    fun ghosts(u: Double, v: Double): List<Ghost> {
        val p = pending
        if (p.isEmpty()) return emptyList()
        fun x(i: Int) = sketch.x(p[i])
        fun y(i: Int) = sketch.y(p[i])
        val last = p.size - 1
        val toPointer = listOf(Ghost.Seg(x(last), y(last), u, v))
        fun between() = listOf(Ghost.Seg(x(0), y(0), x(1), y(1)))
        fun bow(cx: Double, cy: Double, r: Double, ax: Double, ay: Double, bx: Double, by: Double): Ghost {
            val (a0, a1) = arcAngles(cx, cy, ax, ay, bx, by)
            return Ghost.Bow(cx, cy, r, a0, a1)
        }
        return when (tool) {
            SketchTool.Line -> toPointer
            SketchTool.Rectangle -> when (rectangleStyle) {
                RectangleStyle.Corners -> outline(listOf(x(0) to y(0), u to y(0), u to v, x(0) to v))
                RectangleStyle.Centre -> {
                    val ou = 2 * x(0) - u; val ov = 2 * y(0) - v
                    outline(listOf(u to v, ou to v, ou to ov, u to ov))
                }
                RectangleStyle.ThreePoints -> if (p.size == 1) toPointer
                else rectangleFromSide(x(0), y(0), x(1), y(1), u, v)?.let(::outline) ?: between()
            }
            SketchTool.Circle -> when (circleStyle) {
                CircleStyle.Centre -> listOf(Ghost.Ring(x(0), y(0), hypot(u - x(0), v - y(0))))
                CircleStyle.TwoPoints -> listOf(Ghost.Ring((x(0) + u) / 2, (y(0) + v) / 2, hypot(u - x(0), v - y(0)) / 2))
                CircleStyle.ThreePoints -> if (p.size == 1) toPointer
                else circleThrough(x(0), y(0), x(1), y(1), u, v)?.let { c -> listOf(Ghost.Ring(c.first, c.second, hypot(x(0) - c.first, y(0) - c.second))) } ?: between()
            }
            SketchTool.Arc -> when (arcStyle) {
                ArcStyle.CentreEnds -> if (p.size == 1) listOf(Ghost.Ring(x(0), y(0), hypot(u - x(0), v - y(0)), faint = true)) else {
                    val r = hypot(x(1) - x(0), y(1) - y(0))
                    val a = atan2(v - y(0), u - x(0))
                    listOf(bow(x(0), y(0), r, x(1), y(1), x(0) + r * cos(a), y(0) + r * sin(a)))
                }
                ArcStyle.ThreePoints -> if (p.size == 1) toPointer else circleThrough(x(0), y(0), x(1), y(1), u, v)?.let { c ->
                    val r = hypot(x(0) - c.first, y(0) - c.second)
                    if (anticlockwiseBetween(c, x(0), y(0), u, v, x(1), y(1))) listOf(bow(c.first, c.second, r, x(0), y(0), x(1), y(1)))
                    else listOf(bow(c.first, c.second, r, x(1), y(1), x(0), y(0)))
                } ?: between()
                ArcStyle.Tangent -> {
                    val arc = tangentAt(p[0])?.let { (d, _) -> tangentArc(x(0), y(0), d.first, d.second, u, v) }
                    if (arc == null) toPointer else {
                        val (c, r, left) = arc
                        listOf(if (left) bow(c.first, c.second, r, x(0), y(0), u, v) else bow(c.first, c.second, r, u, v, x(0), y(0)))
                    }
                }
            }
            SketchTool.Polygon -> {
                val r = hypot(u - x(0), v - y(0))
                outline(polygonCorners(x(0), y(0), r, atan2(v - y(0), u - x(0)), polygonSides.coerceIn(3, 64), polygonStyle == PolygonStyle.Outside)) +
                    Ghost.Ring(x(0), y(0), r, faint = true)
            }
            SketchTool.Slot -> if (p.size == 1) toPointer
            else slotCentres(slotStyle, x(0), y(0), x(1), y(1), u, v)?.let { (a, b, half) -> slotGhost(a, b, half) } ?: (between() + Ghost.Seg(x(1), y(1), u, v))
            else -> emptyList()
        }
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
        // Construction points, flattened onto the sketch's plane.
        val lone = points().map { p -> val d = p - plane.origin; d.dot(plane.x) to d.dot(plane.y) }
        val curves = outline?.invoke() ?: if (lone.isEmpty()) run { message = "The face's edges couldn't be found"; return } else emptyList()
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
        for ((u, v) in lone) fixedPoint(u, v)
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
            out += ConstraintChoice("Collinear") { Constraint.Collinear(lines[0], lines[1]) }
            out += ConstraintChoice("Equal") { Constraint.Equal(lines[0], lines[1]) }
        }
        // Curves that already meet at an end join smoothly there; others touch wherever they can.
        fun shared(a: Curve, b: Curve) = a.points().firstOrNull { it in b.points() && (b !is Arc || it === b.start || it === b.end) }
        if (n == 2 && lines.size == 1 && rounds.size == 1) {
            val at = shared(lines[0], rounds[0])
            out += ConstraintChoice("Tangent") { if (at != null) Constraint.TangentJoin(lines[0], rounds[0], at) else Constraint.TangentLine(lines[0], rounds[0]) }
        }
        if (n == 2 && rounds.size == 2) {
            out += ConstraintChoice("Equal") { Constraint.Equal(rounds[0], rounds[1]) }
            out += ConstraintChoice("Concentric") { Constraint.Coincident(centreOf(rounds[0]), centreOf(rounds[1])) }
            val inside = sketch.distance(centreOf(rounds[0]), centreOf(rounds[1])) < maxOf(sizeOf(rounds[0]), sizeOf(rounds[1]))
            val at = shared(rounds[0], rounds[1])
            out += ConstraintChoice("Tangent") { if (at != null) Constraint.TangentJoin(rounds[0], rounds[1], at) else Constraint.TangentCircles(rounds[0], rounds[1], inside) }
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

    fun clearSelection() {
        selection.clear()
        dimensionPicks.clear()
        version++
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
        typed = null
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

/** The centre of the circle through three points, or null if they're in a line. */
internal fun circleThrough(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Pair<Double, Double>? {
    val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if (abs(d) < 1e-9) return null
    val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by; val c2 = cx * cx + cy * cy
    return (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d to (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
}

/** True if going anticlockwise round centre from (ax, ay) passes (mx, my) before reaching (bx, by). */
internal fun anticlockwiseBetween(centre: Pair<Double, Double>, ax: Double, ay: Double, mx: Double, my: Double, bx: Double, by: Double): Boolean {
    fun angle(x: Double, y: Double) = atan2(y - centre.second, x - centre.first)
    fun from(a: Double): Double { var d = a - angle(ax, ay); while (d < 0) d += 2 * PI; return d }
    return from(angle(mx, my)) < from(angle(bx, by))
}
