package com.rm.parrotmetric.ui.drawing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.parrotmetric.drawing.DimensionKind
import com.rm.parrotmetric.drawing.Drawing
import com.rm.parrotmetric.drawing.DrawingDimension
import com.rm.parrotmetric.drawing.DrawingExport
import com.rm.parrotmetric.drawing.DrawingNote
import com.rm.parrotmetric.drawing.DrawingView
import com.rm.parrotmetric.drawing.Mark
import com.rm.parrotmetric.drawing.Sheet
import com.rm.parrotmetric.drawing.ViewGeometry
import com.rm.parrotmetric.drawing.ViewSide
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.ui.design.DesignEditor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the drawing screen's taps do. */
enum class DrawingTool { Select, Dimension, Note }

/** Something picked on the sheet. */
sealed class DrawingPick {
    data class View(val id: Int) : DrawingPick()
    data class Dimension(val id: Int) : DrawingPick()
    data class Note(val id: Int) : DrawingPick()
}

/**
 * The drawing screen's state: the views worked out from the model, what's picked, and the tool. Sheet
 * points are mm from the sheet's lower left, y up. Changes go through [editor] so undo takes them back.
 */
class DrawingState(val editor: DesignEditor) {
    val drawing: Drawing get() = editor.design.drawing ?: Drawing()

    /** Each side's view of the model as it is now. */
    val geometry = mutableStateMapOf<ViewSide, ViewGeometry>()

    var tool by mutableStateOf(DrawingTool.Select)
    var picked by mutableStateOf<DrawingPick?>(null)

    /** The first point of a dimension being made: view id and model x, y. */
    var firstPoint by mutableStateOf<Triple<Int, Double, Double>?>(null)
    var message by mutableStateOf<String?>(null)

    /** A note being written: where, and the note it replaces if it's being changed. */
    var noteAt by mutableStateOf<Triple<Double, Double, DrawingNote?>?>(null)

    fun geometryOf(v: DrawingView) = geometry[v.side]

    /** Every view's geometry by view id, for [Sheet.marks]. */
    fun byView(): Map<Int, ViewGeometry> = drawing.views.mapNotNull { v -> geometry[v.side]?.let { v.id to it } }.toMap()

    fun marks(date: String): List<Mark> = Sheet.marks(drawing, byView(), date)

    /** Works out the views the drawing uses, and those [also] asks for, from the model as built now. */
    suspend fun project(also: List<ViewSide> = emptyList()) {
        for (side in (drawing.views.map { it.side } + also).distinct()) {
            editor.projectView(side)?.let { geometry[side] = it }
        }
    }

    /** Lays out the front, top, right and isometric views and picks a scale they fit at. */
    suspend fun layOut(title: String) {
        val sides = listOf(ViewSide.Front, ViewSide.Top, ViewSide.Right, ViewSide.Iso)
        project(sides)
        val sizes = sides.mapNotNull { s -> geometry[s]?.let { s to (it.width to it.height) } }.toMap()
        if (sizes[ViewSide.Front]?.let { it.first > 0 || it.second > 0 } != true) {
            message = "There's nothing solid to draw"
            return
        }
        editor.changeDrawing { d -> Sheet.arrange(d.copy(title = d.title.ifBlank { title }), sides, sizes) }
        picked = null
    }

    /** Adds a single view in the middle of the free space. */
    suspend fun addView(side: ViewSide) {
        project(listOf(side))
        val (l, b, r, t) = Sheet.area(drawing).toList()
        editor.changeDrawing { d -> d.copy(views = d.views + DrawingView(d.nextId(), side, (l + r) / 2, (b + Sheet.TITLE_H + t) / 2, hidden = side != ViewSide.Iso)) }
        picked = DrawingPick.View(drawing.views.last().id)
    }

    // Picking.

    /** The sheet box a view covers: left, bottom, right, top. */
    fun boxOf(v: DrawingView): DoubleArray? {
        val g = geometryOf(v) ?: return null
        val s = drawing.scaleOf(v)
        return doubleArrayOf(v.x - g.width * s / 2, v.y - g.height * s / 2, v.x + g.width * s / 2, v.y + g.height * s / 2)
    }

    fun viewAt(x: Double, y: Double, slack: Double = 3.0): DrawingView? = drawing.views.lastOrNull { v ->
        val b = boxOf(v) ?: return@lastOrNull false
        x >= b[0] - slack && x <= b[2] + slack && y >= b[1] - slack && y <= b[3] + slack
    }

    /** The dimension whose lines or number pass within [reach] mm of (x, y). */
    fun dimensionAt(x: Double, y: Double, reach: Double): DrawingDimension? {
        var best: DrawingDimension? = null
        var bestD = reach
        for (m in drawing.dimensions) {
            val v = drawing.views.firstOrNull { it.id == m.view } ?: continue
            val g = geometryOf(v) ?: continue
            val marks = mutableListOf<Mark>()
            Sheet.dimension(drawing, v, g, m, marks)
            for (k in marks) {
                val d = when (k) {
                    is Mark.Line -> segment(x, y, k.x1, k.y1, k.x2, k.y2)
                    is Mark.Text -> textDistance(k, x, y)
                    else -> Double.MAX_VALUE
                }
                if (d < bestD) { bestD = d; best = m }
            }
        }
        return best
    }

    fun noteAt(x: Double, y: Double, reach: Double): DrawingNote? = drawing.notes.lastOrNull { n ->
        val lines = n.text.split('\n')
        val w = lines.maxOf { DrawingExport.textWidth(it, n.height) }
        val top = n.y + n.height
        val bottom = n.y - (lines.size - 1) * n.height * 1.5 - n.height * 0.3
        x >= n.x - reach && x <= n.x + w + reach && y >= bottom - reach && y <= top + reach
    }

    /** What a tap at (x, y) with the Select tool picks: dimensions and notes before the views under them. */
    fun pickAt(x: Double, y: Double, reach: Double): DrawingPick? =
        dimensionAt(x, y, reach)?.let { DrawingPick.Dimension(it.id) }
            ?: noteAt(x, y, reach)?.let { DrawingPick.Note(it.id) }
            ?: viewAt(x, y)?.let { DrawingPick.View(it.id) }

    // Dimensions.

    /**
     * A tap with the Dimension tool: a corner, end or centre is a point to measure from or to; a circle
     * or arc's edge gives its diameter or radius. [reach] is how close counts, sheet mm.
     */
    fun dimensionTap(x: Double, y: Double, reach: Double) {
        val v = viewAt(x, y, reach) ?: run { message = "Tap a corner, an end or a circle in a view"; return }
        val g = geometryOf(v) ?: return
        val s = drawing.scaleOf(v)
        val (mx, my) = Sheet.unplace(drawing, v, g, x, y)
        val snap = g.snap(mx, my, reach / s)
        if (snap != null) {
            val first = firstPoint
            if (first == null || first.first != v.id) {
                firstPoint = Triple(v.id, snap.first, snap.second)
                message = null
                return
            }
            if (hypot(first.second - snap.first, first.third - snap.second) < 1e-6) return
            val ax = first.second; val ay = first.third; val bx = snap.first; val by = snap.second
            val kind = when {
                abs(ay - by) < 1e-6 -> DimensionKind.Horizontal
                abs(ax - bx) < 1e-6 -> DimensionKind.Vertical
                abs(bx - ax) >= abs(by - ay) -> DimensionKind.Horizontal
                else -> DimensionKind.Vertical
            }
            val offset = outward(g, kind, ax, ay, bx, by)
            val id = drawing.nextId()
            editor.changeDrawing { d -> d.copy(dimensions = d.dimensions + DrawingDimension(id, v.id, kind, ax, ay, bx, by, offset, box(g))) }
            firstPoint = null
            picked = DrawingPick.Dimension(id)
            return
        }
        // Near a circle's or arc's edge: its size.
        val round = g.rounds.minByOrNull { abs(hypot(mx - it.x1, my - it.y1) - it.r) }
            ?.takeIf { abs(hypot(mx - it.x1, my - it.y1) - it.r) <= reach / s && (it.kind == ProfileCurve.Kind.Circle || ViewGeometry.onArc(it.a0, it.a1, atan2(my - it.y1, mx - it.x1))) }
        if (round != null) {
            val a = atan2(my - round.y1, mx - round.x1)
            val kind = if (round.kind == ProfileCurve.Kind.Circle) DimensionKind.Diameter else DimensionKind.Radius
            val id = drawing.nextId()
            editor.changeDrawing { d ->
                d.copy(dimensions = d.dimensions + DrawingDimension(id, v.id, kind, round.x1, round.y1, round.x1 + round.r * cos(a), round.y1 + round.r * sin(a), 8.0, box(g)))
            }
            firstPoint = null
            picked = DrawingPick.Dimension(id)
            return
        }
        message = "Tap a corner, an end or a circle in a view"
    }

    private fun box(g: ViewGeometry) = listOf(g.minX, g.minY, g.maxX, g.maxY)

    /** An offset that puts the dimension outside the view, on the side its points are nearest. */
    private fun outward(g: ViewGeometry, kind: DimensionKind, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val mx = (ax + bx) / 2; val my = (ay + by) / 2
        return when (kind) {
            DimensionKind.Horizontal -> if (my >= g.cy) 10.0 else -10.0
            DimensionKind.Vertical -> if (mx <= g.cx) 10.0 else -10.0
            else -> {
                val dx = bx - ax; val dy = by - ay
                if ((-dy) * (mx - g.cx) + dx * (my - g.cy) >= 0) 10.0 else -10.0
            }
        }
    }

    fun setKind(id: Int, kind: DimensionKind) = editor.changeDrawing { d ->
        d.copy(dimensions = d.dimensions.map { if (it.id == id) it.copy(kind = kind) else it })
    }

    // Moving things.

    /** Drags what's picked by (dx, dy) sheet mm. The first step of a drag keeps an undo point. */
    fun drag(pick: DrawingPick, dx: Double, dy: Double, first: Boolean) {
        val d0 = drawing
        when (pick) {
            is DrawingPick.View -> {
                val v = d0.views.firstOrNull { it.id == pick.id } ?: return
                var nx = v.x + dx; var ny = v.y + dy
                // Lines up with another view when it's close, so projections stay in line.
                for (o in d0.views) if (o.id != v.id) {
                    if (abs(o.x - nx) < 2) nx = o.x
                    if (abs(o.y - ny) < 2) ny = o.y
                }
                editor.changeDrawing(first) { d -> d.copy(views = d.views.map { if (it.id == v.id) it.copy(x = nx, y = ny) else it }) }
            }
            is DrawingPick.Note -> editor.changeDrawing(first) { d ->
                d.copy(notes = d.notes.map { if (it.id == pick.id) it.copy(x = it.x + dx, y = it.y + dy) else it })
            }
            is DrawingPick.Dimension -> {
                val m = d0.dimensions.firstOrNull { it.id == pick.id } ?: return
                val v = d0.views.firstOrNull { it.id == m.view } ?: return
                val g = geometryOf(v) ?: return
                val change: DrawingDimension = when (m.kind) {
                    DimensionKind.Diameter, DimensionKind.Radius -> {
                        // The label follows the pointer round the circle.
                        val s = d0.scaleOf(v)
                        val (fx, fy) = Sheet.follow(m, g, m.ax, m.ay)
                        val (cx, cy) = Sheet.place(d0, v, g, fx, fy)
                        val r = hypot(m.bx - m.ax, m.by - m.ay)
                        val a0 = atan2(m.by - m.ay, m.bx - m.ax)
                        val lx = cx + cos(a0) * (r * s + m.offset) + dx
                        val ly = cy + sin(a0) * (r * s + m.offset) + dy
                        val a = atan2(ly - cy, lx - cx)
                        m.copy(bx = m.ax + r * cos(a), by = m.ay + r * sin(a), offset = max(2.0, hypot(lx - cx, ly - cy) - r * s))
                    }
                    else -> {
                        // Moves the dimension line square to what it measures.
                        val (ux, uy) = when (m.kind) {
                            DimensionKind.Horizontal -> 1.0 to 0.0
                            DimensionKind.Vertical -> 0.0 to 1.0
                            else -> {
                                val l = hypot(m.bx - m.ax, m.by - m.ay).takeIf { it > 1e-9 } ?: 1.0
                                (m.bx - m.ax) / l to (m.by - m.ay) / l
                            }
                        }
                        val along = -uy * dx + ux * dy
                        var off = m.offset + along
                        // Stays clear of the points: past them it flips to the other side.
                        if (m.offset >= 0 && off < 2) off = if (off < -2) off else 2.0
                        if (m.offset < 0 && off > -2) off = if (off > 2) off else -2.0
                        m.copy(offset = off)
                    }
                }
                editor.changeDrawing(first) { d -> d.copy(dimensions = d.dimensions.map { if (it.id == m.id) change else it }) }
            }
        }
    }

    fun deletePicked() {
        val p = picked ?: return
        editor.changeDrawing { d ->
            when (p) {
                // A view goes with its dimensions.
                is DrawingPick.View -> d.copy(views = d.views.filter { it.id != p.id }, dimensions = d.dimensions.filter { it.view != p.id })
                is DrawingPick.Dimension -> d.copy(dimensions = d.dimensions.filter { it.id != p.id })
                is DrawingPick.Note -> d.copy(notes = d.notes.filter { it.id != p.id })
            }
        }
        picked = null
    }

    fun saveNote(text: String) {
        val (x, y, old) = noteAt ?: return
        noteAt = null
        if (text.isBlank()) {
            if (old != null) editor.changeDrawing { d -> d.copy(notes = d.notes.filter { it.id != old.id }) }
            return
        }
        if (old != null) editor.changeDrawing { d -> d.copy(notes = d.notes.map { if (it.id == old.id) it.copy(text = text) else it }) }
        else {
            val id = drawing.nextId()
            editor.changeDrawing { d -> d.copy(notes = d.notes + DrawingNote(id, x, y, text)) }
            picked = DrawingPick.Note(id)
        }
    }

    companion object {
        fun segment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val dx = x2 - x1; val dy = y2 - y1
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((px - x1) * dx + (py - y1) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
        }

        /** How far (x, y) is from a text's box. */
        fun textDistance(t: Mark.Text, x: Double, y: Double): Double {
            val w = DrawingExport.textWidth(t.text, t.height)
            val a = t.angle * kotlin.math.PI / 180
            // Into the text's own frame: u along its baseline, v up.
            val rx = x - t.x; val ry = y - t.y
            val u = rx * cos(a) + ry * sin(a); val v = -rx * sin(a) + ry * cos(a)
            val left = when (t.anchor) { com.rm.parrotmetric.drawing.Anchor.Start -> 0.0; com.rm.parrotmetric.drawing.Anchor.Middle -> -w / 2; com.rm.parrotmetric.drawing.Anchor.End -> -w }
            val du = max(0.0, max(left - u, u - (left + w)))
            val dv = max(0.0, max(-0.3 * t.height - v, v - t.height))
            return hypot(du, dv)
        }

        /** Today as it's written in the title block: year, month, day. */
        fun today(): String {
            val days = kotlin.time.Clock.System.now().toEpochMilliseconds().floorDiv(86_400_000L)
            // Days since 1970 to a calendar date (Howard Hinnant's method).
            val z = days + 719468
            val era = z.floorDiv(146097)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        }
    }
}
