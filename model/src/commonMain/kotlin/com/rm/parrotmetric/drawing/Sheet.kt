package com.rm.parrotmetric.drawing

import com.rm.parrotmetric.sketch.ProfileCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/** How a mark is drawn: a part's seen edges thick, hidden ones dashed, everything else thin. */
enum class Pen { Visible, Hidden, Thin }

/** Where text sits on its point. */
enum class Anchor { Start, Middle, End }

/** Something drawn on the sheet, in sheet mm from its lower left, y up. */
sealed class Mark {
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val pen: Pen) : Mark()

    /** Joined lines through [points] (x, y pairs), drawn as one path so dashes run on round the bends. */
    class Poly(val points: DoubleArray, val pen: Pen) : Mark()

    /** Anticlockwise from [a0] to [a1], radians; a whole circle when they're 2π apart. */
    data class Arc(val cx: Double, val cy: Double, val r: Double, val a0: Double, val a1: Double, val pen: Pen) : Mark()

    /** [angle] in degrees, anticlockwise; the text's baseline runs through ([x], [y]). */
    data class Text(val x: Double, val y: Double, val text: String, val height: Double, val anchor: Anchor = Anchor.Middle, val angle: Double = 0.0) : Mark()

    /** A filled arrowhead with its tip at ([x], [y]), pointing along ([dx], [dy]). */
    data class Head(val x: Double, val y: Double, val dx: Double, val dy: Double) : Mark()
}

/**
 * A view worked out from the model: its seen and hidden curves in the view's own mm, and the middle of
 * what's seen. For a section, [cut] is the outline of the cut faces, to hatch.
 */
class ViewGeometry(val visible: List<ProfileCurve>, val hidden: List<ProfileCurve>, val cut: List<ProfileCurve> = emptyList()) {
    val minX: Double; val minY: Double; val maxX: Double; val maxY: Double
    init {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (c in visible) for ((x, y) in extremes(c)) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
        if (x0 > x1) { x0 = 0.0; y0 = 0.0; x1 = 0.0; y1 = 0.0 }
        minX = x0; minY = y0; maxX = x1; maxY = y1
    }
    val cx get() = (minX + maxX) / 2
    val cy get() = (minY + maxY) / 2
    val width get() = maxX - minX
    val height get() = maxY - minY

    /**
     * Corners, ends and centres, where dimensions catch on. Where a curve came back as short lines,
     * the joins along it where it runs smoothly on aren't corners and are left out.
     */
    val snaps: List<Pair<Double, Double>> by lazy {
        val out = mutableListOf<Pair<Double, Double>>()
        fun key(x: Double, y: Double) = kotlin.math.round(x * 1e4) to kotlin.math.round(y * 1e4)
        // The directions leaving each line end.
        val leaving = HashMap<Pair<Double, Double>, MutableList<Pair<Double, Double>>>()
        for (c in visible + hidden) if (c.kind == ProfileCurve.Kind.Line) {
            val l = hypot(c.x2 - c.x1, c.y2 - c.y1).takeIf { it > 1e-9 } ?: continue
            leaving.getOrPut(key(c.x1, c.y1)) { mutableListOf() } += ((c.x2 - c.x1) / l) to ((c.y2 - c.y1) / l)
            leaving.getOrPut(key(c.x2, c.y2)) { mutableListOf() } += ((c.x1 - c.x2) / l) to ((c.y1 - c.y2) / l)
        }
        fun smooth(x: Double, y: Double): Boolean {
            val d = leaving[key(x, y)] ?: return false
            // Two lines carrying on nearly straight through: within 25° of opposite.
            return d.size == 2 && d[0].first * d[1].first + d[0].second * d[1].second < -0.9
        }
        for (c in visible + hidden) when (c.kind) {
            ProfileCurve.Kind.Line -> {
                if (!smooth(c.x1, c.y1)) out += c.x1 to c.y1
                if (!smooth(c.x2, c.y2)) out += c.x2 to c.y2
            }
            ProfileCurve.Kind.Circle -> out += c.x1 to c.y1
            ProfileCurve.Kind.Arc -> {
                out += c.x1 to c.y1
                out += (c.x1 + c.r * cos(c.a0)) to (c.y1 + c.r * sin(c.a0))
                out += (c.x1 + c.r * cos(c.a1)) to (c.y1 + c.r * sin(c.a1))
            }
            ProfileCurve.Kind.Bezier -> { out += c.x1 to c.y1; out += c.x2 to c.y2 }
        }
        out
    }

    /** Circles and arcs, for diameters and radii. */
    val rounds: List<ProfileCurve> get() = (visible + hidden).filter { it.kind == ProfileCurve.Kind.Circle || it.kind == ProfileCurve.Kind.Arc }

    /** The snap point nearest (x, y) within [reach], or null. */
    fun snap(x: Double, y: Double, reach: Double): Pair<Double, Double>? =
        snaps.minByOrNull { hypot(it.first - x, it.second - y) }?.takeIf { hypot(it.first - x, it.second - y) <= reach }

    /** The circle or arc whose centre is nearest (x, y) within [reach]. */
    fun round(x: Double, y: Double, reach: Double): ProfileCurve? =
        rounds.minByOrNull { hypot(it.x1 - x, it.y1 - y) }?.takeIf { hypot(it.x1 - x, it.y1 - y) <= reach }

    companion object {
        /** Points that bound a curve: ends, and for circles and arcs their furthest reaches. */
        fun extremes(c: ProfileCurve): List<Pair<Double, Double>> = when (c.kind) {
            ProfileCurve.Kind.Line, ProfileCurve.Kind.Bezier -> listOf(c.x1 to c.y1, c.x2 to c.y2)
            ProfileCurve.Kind.Circle -> listOf((c.x1 - c.r) to (c.y1 - c.r), (c.x1 + c.r) to (c.y1 + c.r))
            ProfileCurve.Kind.Arc -> {
                val pts = mutableListOf((c.x1 + c.r * cos(c.a0)) to (c.y1 + c.r * sin(c.a0)), (c.x1 + c.r * cos(c.a1)) to (c.y1 + c.r * sin(c.a1)))
                for (k in 0..3) if (onArc(c.a0, c.a1, k * PI / 2)) pts += (c.x1 + c.r * cos(k * PI / 2)) to (c.y1 + c.r * sin(k * PI / 2))
                pts
            }
        }

        fun onArc(a0: Double, a1: Double, a: Double): Boolean {
            fun span(from: Double, to: Double): Double { var d = to - from; while (d < 0) d += 2 * PI; while (d >= 2 * PI) d -= 2 * PI; return d }
            return span(a0, a) <= span(a0, a1) + 1e-9
        }
    }
}

/** The sheet's layout: its frame, title block and room for views. */
object Sheet {
    const val MARGIN = 10.0
    const val TITLE_W = 120.0
    const val TITLE_H = 28.0

    /** The free area for views: left, bottom, right, top, sheet mm. */
    fun area(d: Drawing) = doubleArrayOf(MARGIN, MARGIN, d.width - MARGIN, d.height - MARGIN)

    /** Where a view's model point (x, y) lands on the sheet. */
    fun place(d: Drawing, v: DrawingView, g: ViewGeometry, x: Double, y: Double): Pair<Double, Double> {
        val s = d.scaleOf(v)
        return (v.x + (x - g.cx) * s) to (v.y + (y - g.cy) * s)
    }

    /** The other way: a sheet point to the view's model point. */
    fun unplace(d: Drawing, v: DrawingView, g: ViewGeometry, x: Double, y: Double): Pair<Double, Double> {
        val s = d.scaleOf(v)
        return (g.cx + (x - v.x) / s) to (g.cy + (y - v.y) / s)
    }

    /**
     * Everything on the sheet, given each view's worked-out geometry by view id. [date] goes in the
     * title block. [holes] is what each hole callout says, by dimension id, a line at a time.
     */
    fun marks(d: Drawing, geometry: Map<Int, ViewGeometry>, date: String, holes: Map<Int, List<String>> = emptyMap()): List<Mark> {
        val out = mutableListOf<Mark>()
        frame(d, date, out)
        for (v in d.views) {
            val g = geometry[v.id] ?: continue
            if (v.cut != null) hatch(d, v, g, out)
            curves(d, v, g, g.visible, Pen.Visible, out)
            if (v.hidden) curves(d, v, g, g.hidden, Pen.Hidden, out)
            // A section or a view at a scale of its own says so under it; the rest go by the title block's.
            val s = d.scaleOf(v)
            val name = mutableListOf<String>()
            if (v.label != null) name += "SECTION ${v.label}-${v.label}" else if (v.scale != null) name += v.side.label.uppercase()
            if (v.scale != null) name += Drawing.scaleLabel(s)
            if (name.isNotEmpty()) out += Mark.Text(v.x, v.y - g.height * s / 2 - 6, name.joinToString("  "), 3.0)
        }
        for (v in d.views) if (v.cut != null && v.label != null) for (w in d.views) {
            if (w.id == v.id) continue
            geometry[w.id]?.let { cutLine(d, w, it, v, out) }
        }
        for (m in d.dimensions) {
            val v = d.views.firstOrNull { it.id == m.view } ?: continue
            val g = geometry[v.id] ?: continue
            dimension(d, v, g, m, out, holes[m.id])
        }
        for (n in d.notes) for ((i, line) in n.text.split('\n').withIndex())
            out += Mark.Text(n.x, n.y - i * n.height * 1.5, line, n.height, Anchor.Start)
        return out
    }

    /** A view's curves: lines that run on from each other joined into paths, the rest one by one. */
    private fun curves(d: Drawing, v: DrawingView, g: ViewGeometry, list: List<ProfileCurve>, pen: Pen, out: MutableList<Mark>) {
        val lines = list.filter { it.kind == ProfileCurve.Kind.Line }
        for (c in list) if (c.kind != ProfileCurve.Kind.Line) curve(d, v, g, c, pen, out)
        fun key(x: Double, y: Double) = (kotlin.math.round(x * 1e4).toLong() shl 32) xor kotlin.math.round(y * 1e4).toLong()
        // Each line's ends, for finding the next one along.
        val at = HashMap<Long, MutableList<Int>>()
        for ((i, c) in lines.withIndex()) {
            at.getOrPut(key(c.x1, c.y1)) { mutableListOf() } += i
            at.getOrPut(key(c.x2, c.y2)) { mutableListOf() } += i
        }
        val used = BooleanArray(lines.size)
        fun nextFrom(x: Double, y: Double): Pair<Int, Boolean>? {
            for (j in at[key(x, y)].orEmpty()) if (!used[j]) {
                val c = lines[j]
                return j to (key(c.x1, c.y1) == key(x, y))
            }
            return null
        }
        for (i in lines.indices) {
            if (used[i]) continue
            used[i] = true
            val pts = ArrayDeque<Pair<Double, Double>>()
            pts.addLast(lines[i].x1 to lines[i].y1); pts.addLast(lines[i].x2 to lines[i].y2)
            // Grow both ways.
            while (true) {
                val (x, y) = pts.last()
                val (j, forward) = nextFrom(x, y) ?: break
                used[j] = true
                val c = lines[j]
                pts.addLast(if (forward) c.x2 to c.y2 else c.x1 to c.y1)
            }
            while (true) {
                val (x, y) = pts.first()
                val (j, forward) = nextFrom(x, y) ?: break
                used[j] = true
                val c = lines[j]
                pts.addFirst(if (forward) c.x2 to c.y2 else c.x1 to c.y1)
            }
            val placed = pts.map { (x, y) -> place(d, v, g, x, y) }
            if (placed.size == 2) out += Mark.Line(placed[0].first, placed[0].second, placed[1].first, placed[1].second, pen)
            else out += Mark.Poly(DoubleArray(placed.size * 2) { k -> if (k % 2 == 0) placed[k / 2].first else placed[k / 2].second }, pen)
        }
    }

    private fun curve(d: Drawing, v: DrawingView, g: ViewGeometry, c: ProfileCurve, pen: Pen, out: MutableList<Mark>) {
        val s = d.scaleOf(v)
        when (c.kind) {
            ProfileCurve.Kind.Line, ProfileCurve.Kind.Bezier -> {
                val (x1, y1) = place(d, v, g, c.x1, c.y1)
                val (x2, y2) = place(d, v, g, c.x2, c.y2)
                out += Mark.Line(x1, y1, x2, y2, pen)
            }
            ProfileCurve.Kind.Circle -> {
                val (x, y) = place(d, v, g, c.x1, c.y1)
                out += Mark.Arc(x, y, c.r * s, 0.0, 2 * PI, pen)
            }
            ProfileCurve.Kind.Arc -> {
                val (x, y) = place(d, v, g, c.x1, c.y1)
                out += Mark.Arc(x, y, c.r * s, c.a0, c.a1, pen)
            }
        }
    }

    /** 45° lines across a section's cut faces, 2 mm apart on the sheet: inside where an odd number of outlines are crossed. */
    private fun hatch(d: Drawing, v: DrawingView, g: ViewGeometry, out: MutableList<Mark>) {
        val segments = mutableListOf<DoubleArray>()
        for (c in g.cut) when (c.kind) {
            ProfileCurve.Kind.Line, ProfileCurve.Kind.Bezier -> segments += doubleArrayOf(c.x1, c.y1, c.x2, c.y2)
            ProfileCurve.Kind.Circle, ProfileCurve.Kind.Arc -> {
                var span = if (c.kind == ProfileCurve.Kind.Circle) 2 * PI else c.a1 - c.a0
                while (span <= 0) span += 2 * PI
                val a0 = if (c.kind == ProfileCurve.Kind.Circle) 0.0 else c.a0
                val n = max(8, (span / (PI / 24)).toInt())
                for (k in 0 until n) {
                    val p = a0 + span * k / n; val q = a0 + span * (k + 1) / n
                    segments += doubleArrayOf(c.x1 + c.r * cos(p), c.y1 + c.r * sin(p), c.x1 + c.r * cos(q), c.y1 + c.r * sin(q))
                }
            }
        }
        if (segments.isEmpty()) return
        val step = 2.0 / d.scaleOf(v)
        // Lines along (1, 1); n is square to them, so each line is where x·n is constant.
        val r2 = kotlin.math.sqrt(0.5)
        fun across(x: Double, y: Double) = (-x + y) * r2
        fun along(x: Double, y: Double) = (x + y) * r2
        val values = segments.flatMap { listOf(across(it[0], it[1]), across(it[2], it[3])) }
        var c = kotlin.math.floor(values.min() / step) * step + step / 2
        while (c < values.max()) {
            val hits = mutableListOf<Double>()
            for (sg in segments) {
                val c0 = across(sg[0], sg[1]); val c1 = across(sg[2], sg[3])
                if ((c0 <= c) == (c1 <= c)) continue
                val t = (c - c0) / (c1 - c0)
                hits += along(sg[0] + t * (sg[2] - sg[0]), sg[1] + t * (sg[3] - sg[1]))
            }
            hits.sort()
            for (k in 0 until hits.size - 1 step 2) {
                // Back from (along, across) to x, y.
                fun point(a: Double) = ((a - c) * r2) to ((a + c) * r2)
                val (x1, y1) = place(d, v, g, point(hits[k]).first, point(hits[k]).second)
                val (x2, y2) = place(d, v, g, point(hits[k + 1]).first, point(hits[k + 1]).second)
                out += Mark.Line(x1, y1, x2, y2, Pen.Thin)
            }
            c += step
        }
    }

    /**
     * Where section [cut]'s plane crosses view [w], if [w] sees it edge on: a thin chain line across the
     * view with thick ends, arrows looking the way the section does, and its letter.
     */
    private fun cutLine(d: Drawing, w: DrawingView, g: ViewGeometry, cut: DrawingView, out: MutableList<Mark>) {
        val t = cut.side.towards
        if (abs(w.side.towards.dot(t)) > 1e-6 || w.side == ViewSide.Iso) return
        val up = w.side.towards.cross(w.side.right)
        // In w's own x, y, the plane is a·x + b·y = depth.
        val a = w.side.right.dot(t); val b = up.dot(t)
        val depth = cut.cut ?: return
        val l = hypot(a, b).takeIf { it > 1e-9 } ?: return
        val nx = a / l; val ny = b / l
        // The line through the view's box and 6 mm past it on the sheet.
        val px = g.cx + nx * (depth / l - (nx * g.cx + ny * g.cy)); val py = g.cy + ny * (depth / l - (nx * g.cx + ny * g.cy))
        val half = max(g.width, g.height) / 2 + 6 / d.scaleOf(w)
        val (x1, y1) = place(d, w, g, px - ny * half, py + nx * half)
        val (x2, y2) = place(d, w, g, px + ny * half, py - nx * half)
        out += Mark.Line(x1, y1, x2, y2, Pen.Thin)
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx, dy).takeIf { it > 1e-9 } ?: return
        val ux = dx / len; val uy = dy / len
        for ((ex, ey, sign) in listOf(Triple(x1, y1, 1.0), Triple(x2, y2, -1.0))) {
            out += Mark.Line(ex, ey, ex + ux * sign * 5, ey + uy * sign * 5, Pen.Visible)
            // Looking along -t: the arrow points away from the side the viewer of the section stands.
            out += Mark.Line(ex, ey, ex - nx * 6, ey - ny * 6, Pen.Thin)
            out += Mark.Head(ex - nx * 6, ey - ny * 6, -nx, -ny)
            out += Mark.Text(ex - nx * 9, ey - ny * 9 - 1.5, cut.label ?: "", TEXT)
        }
    }

    /** The border, and the title block in the lower right. */
    private fun frame(d: Drawing, date: String, out: MutableList<Mark>) {
        val (l, b, r, t) = area(d).toList()
        fun box(x0: Double, y0: Double, x1: Double, y1: Double) {
            out += Mark.Line(x0, y0, x1, y0, Pen.Visible); out += Mark.Line(x1, y0, x1, y1, Pen.Visible)
            out += Mark.Line(x1, y1, x0, y1, Pen.Visible); out += Mark.Line(x0, y1, x0, y0, Pen.Visible)
        }
        box(l, b, r, t)
        val x0 = r - TITLE_W
        val y0 = b
        box(x0, y0, r, y0 + TITLE_H)
        // A wide row for the title, then two rows of small boxes.
        val row = TITLE_H / 3
        out += Mark.Line(x0, y0 + 2 * row, r, y0 + 2 * row, Pen.Thin)
        out += Mark.Line(x0, y0 + row, r, y0 + row, Pen.Thin)
        val col = TITLE_W / 3
        for (k in 1..2) out += Mark.Line(x0 + k * col, y0, x0 + k * col, y0 + 2 * row, Pen.Thin)
        out += Mark.Text(x0 + 3, y0 + 2 * row + 3, d.title.ifBlank { "Untitled" }, 5.0, Anchor.Start)
        fun cell(c: Int, rowFromTop: Int, label: String, value: String) {
            val cx = x0 + c * col + 2
            val cy = y0 + (2 - rowFromTop) * row
            out += Mark.Text(cx, cy + row - 3, label, 1.8, Anchor.Start)
            out += Mark.Text(cx, cy + 1.8, value, 3.0, Anchor.Start)
        }
        cell(0, 1, "SCALE", Drawing.scaleLabel(d.scale))
        cell(1, 1, "UNITS", "mm")
        cell(2, 1, "PROJECTION", if (d.firstAngle) "First angle" else "Third angle")
        cell(0, 2, "DRAWN BY", d.drawnBy)
        cell(1, 2, "DATE", date)
        cell(2, 2, "SHEET", d.paper.label + if (d.portrait) " portrait" else "")
    }

    /** Where a dimension's point is now: the same distance from the nearest side of the view's outline as when it was picked. */
    fun follow(m: DrawingDimension, g: ViewGeometry, x: Double, y: Double): Pair<Double, Double> {
        if (m.box.size != 4) return x to y
        val (l, b, r, t) = m.box
        val nx = if (x - l <= r - x) g.minX + (x - l) else g.maxX - (r - x)
        val ny = if (y - b <= t - y) g.minY + (y - b) else g.maxY - (t - y)
        return nx to ny
    }

    /** A dimension's lines, arrowheads and number. */
    fun dimension(d: Drawing, v: DrawingView, g: ViewGeometry, m: DrawingDimension, out: MutableList<Mark>, hole: List<String>? = null) {
        val s = d.scaleOf(v)
        when (m.kind) {
            DimensionKind.Diameter, DimensionKind.Radius, DimensionKind.Hole -> {
                // The circle as it is now, found again by its centre.
                val (fx, fy) = follow(m, g, m.ax, m.ay)
                val c = g.round(fx, fy, 1.0)
                val r = c?.r ?: hypot(m.bx - m.ax, m.by - m.ay)
                val (cx, cy) = place(d, v, g, c?.x1 ?: fx, c?.y1 ?: fy)
                val a = atan2(m.by - m.ay, m.bx - m.ax)
                val ux = cos(a); val uy = sin(a)
                val rs = r * s
                val label = if (m.kind == DimensionKind.Radius) "R" + number(r) else "Ø" + number(2 * r)
                val lines = if (m.kind == DimensionKind.Hole) hole ?: listOf(label) else listOf(label)
                // A leader from the far side (diameter) or the centre (radius), through the edge, out to the label.
                val ex = cx + ux * rs; val ey = cy + uy * rs
                val lx = cx + ux * (rs + m.offset); val ly = cy + uy * (rs + m.offset)
                // A hole's leader points in at its edge from outside.
                val hole = m.kind == DimensionKind.Hole
                val sx = if (m.kind == DimensionKind.Diameter) cx - ux * rs else if (hole) ex else cx
                val sy = if (m.kind == DimensionKind.Diameter) cy - uy * rs else if (hole) ey else cy
                out += Mark.Line(sx, sy, lx, ly, Pen.Thin)
                // Arrowheads from inside, pointing out to the circle; a hole's from outside, pointing in.
                if (hole) out += Mark.Head(ex, ey, -ux, -uy) else out += Mark.Head(ex, ey, ux, uy)
                if (m.kind == DimensionKind.Diameter) out += Mark.Head(sx, sy, -ux, -uy)
                val shelf = if (ux >= 0) 1.0 else -1.0
                out += Mark.Line(lx, ly, lx + shelf * 3, ly, Pen.Thin)
                for ((i, line) in lines.withIndex())
                    out += Mark.Text(lx + shelf * 4, ly - 1.2 - i * TEXT * 1.5, line, TEXT, if (shelf > 0) Anchor.Start else Anchor.End)
            }
            else -> {
                val (fax, fay) = follow(m, g, m.ax, m.ay)
                val (fbx, fby) = follow(m, g, m.bx, m.by)
                val (ax0, ay0) = g.snap(fax, fay, 0.5) ?: (fax to fay)
                val (bx0, by0) = g.snap(fbx, fby, 0.5) ?: (fbx to fby)
                val (ax, ay) = place(d, v, g, ax0, ay0)
                val (bx, by) = place(d, v, g, bx0, by0)
                // The direction measured along, and the value in model mm.
                val (dx, dy, value) = when (m.kind) {
                    DimensionKind.Horizontal -> Triple(1.0, 0.0, abs(bx0 - ax0))
                    DimensionKind.Vertical -> Triple(0.0, 1.0, abs(by0 - ay0))
                    else -> {
                        val l = hypot(bx - ax, by - ay).takeIf { it > 1e-9 } ?: 1.0
                        Triple((bx - ax) / l, (by - ay) / l, hypot(bx0 - ax0, by0 - ay0))
                    }
                }
                // Square to the measuring direction, the side the dimension line stands off to.
                val nx = -dy; val ny = dx
                // The line runs at [offset] from the first point; each point's extension line reaches it.
                val baseA = ax * nx + ay * ny
                val baseB = bx * nx + by * ny
                val at = (if (m.offset >= 0) max(baseA, baseB) else min(baseA, baseB)) + m.offset
                val pa = Pair(ax + nx * (at - baseA), ay + ny * (at - baseA))
                val pb = Pair(bx + nx * (at - baseB), by + ny * (at - baseB))
                val gap = 1.5
                fun ext(x: Double, y: Double, to: Pair<Double, Double>) {
                    val ex = to.first - x; val ey = to.second - y
                    val l = hypot(ex, ey)
                    if (l < gap + 0.1) return
                    out += Mark.Line(x + ex / l * gap, y + ey / l * gap, to.first + ex / l * 2, to.second + ey / l * 2, Pen.Thin)
                }
                ext(ax, ay, pa); ext(bx, by, pb)
                out += Mark.Line(pa.first, pa.second, pb.first, pb.second, Pen.Thin)
                val lx = pb.first - pa.first; val ly = pb.second - pa.second
                val l = hypot(lx, ly).takeIf { it > 1e-9 } ?: 1.0
                out += Mark.Head(pa.first, pa.second, -lx / l, -ly / l)
                out += Mark.Head(pb.first, pb.second, lx / l, ly / l)
                // The number over the middle of the line, reading upright.
                var angle = atan2(ly, lx) * 180 / PI
                if (angle > 90.0001) angle -= 180 else if (angle < -90.0001) angle += 180
                val mx = (pa.first + pb.first) / 2; val my = (pa.second + pb.second) / 2
                val ra = angle * PI / 180
                out += Mark.Text(mx - sin(ra) * 1.2, my + cos(ra) * 1.2, number(value), TEXT, Anchor.Middle, angle)
            }
        }
    }

    /** The height of dimension numbers, mm. */
    const val TEXT = 3.5

    /** A length as it's written on a drawing: up to two decimals, no trailing zeros. */
    fun number(v: Double): String {
        val r = round(v * 100) / 100
        return if (r == round(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    /**
     * Places standard views round the front one, third or first angle, and picks the largest
     * standard scale they fit at. [sizes] gives each side's width and height in model mm.
     */
    fun arrange(d: Drawing, sides: List<ViewSide>, sizes: Map<ViewSide, Pair<Double, Double>>): Drawing {
        val (l, b, r, t) = area(d).toList()
        val gap = 20.0
        val front = sizes[ViewSide.Front] ?: (0.0 to 0.0)
        val top = if (ViewSide.Top in sides) sizes[ViewSide.Top] ?: (0.0 to 0.0) else 0.0 to 0.0
        val right = if (ViewSide.Right in sides) sizes[ViewSide.Right] ?: (0.0 to 0.0) else 0.0 to 0.0
        val iso = if (ViewSide.Iso in sides) sizes[ViewSide.Iso] ?: (0.0 to 0.0) else 0.0 to 0.0
        // Free room, keeping clear of the title block along the bottom.
        val w = r - l - 2 * gap
        val h = t - b - TITLE_H - 2 * gap
        val needW = front.first + right.first + iso.first.takeIf { it > 0 && top.second == 0.0 }.let { it ?: 0.0 }
        val needH = front.second + top.second
        val count = 1 + (if (right.first > 0) 1 else 0)
        val rows = 1 + (if (top.second > 0) 1 else 0)
        val scale = Drawing.scales.firstOrNull { s ->
            needW * s + gap * (count - 1) <= w && needH * s + gap * (rows - 1) <= h && iso.first * s <= w / 2
        } ?: Drawing.scales.last()
        // The block of front, top and side views, centred in the free room above the title block.
        val blockW = (front.first + right.first) * scale + if (right.first > 0) gap else 0.0
        val blockH = (front.second + top.second) * scale + if (top.second > 0) gap else 0.0
        val left = l + gap + (w - blockW - iso.first * scale) / 2
        val bottom = b + TITLE_H + gap + (h - blockH) / 2
        val frontX: Double; val frontY: Double
        val rightX: Double
        val topY: Double
        if (!d.firstAngle) {
            frontX = left + front.first * scale / 2
            rightX = left + front.first * scale + gap + right.first * scale / 2
            frontY = bottom + front.second * scale / 2
            topY = bottom + front.second * scale + gap + top.second * scale / 2
        } else {
            // First angle: the right side's view on the left, the top view below.
            rightX = left + right.first * scale / 2
            frontX = left + (if (right.first > 0) right.first * scale + gap else 0.0) + front.first * scale / 2
            topY = bottom + top.second * scale / 2
            frontY = bottom + (if (top.second > 0) top.second * scale + gap else 0.0) + front.second * scale / 2
        }
        var next = d.nextId()
        val views = mutableListOf<DrawingView>()
        for (side in sides) {
            val (x, y) = when (side) {
                ViewSide.Front -> frontX to frontY
                ViewSide.Top -> frontX to topY
                ViewSide.Right -> rightX to frontY
                ViewSide.Iso -> (r - gap - iso.first * scale / 2) to (t - gap - iso.second * scale / 2)
                else -> continue
            }
            views += DrawingView(next++, side, x, y, hidden = side != ViewSide.Iso)
        }
        return d.copy(scale = scale, views = views, dimensions = emptyList())
    }
}
