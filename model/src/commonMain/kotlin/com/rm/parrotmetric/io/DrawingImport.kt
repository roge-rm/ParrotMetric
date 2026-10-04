package com.rm.parrotmetric.io

import com.rm.parrotmetric.sketch.Point
import com.rm.parrotmetric.sketch.Sketch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A curve read from a drawing, in mm with y up. */
sealed class Drawn {
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Drawn()
    /** Anticlockwise from angle [a0] to [a1], radians. */
    data class Arc(val cx: Double, val cy: Double, val r: Double, val a0: Double, val a1: Double) : Drawn()
    data class Circle(val cx: Double, val cy: Double, val r: Double) : Drawn()
    data class Bezier(val x1: Double, val y1: Double, val c1x: Double, val c1y: Double, val c2x: Double, val c2y: Double, val x2: Double, val y2: Double) : Drawn()
    /** A smooth curve through points. */
    data class Through(val points: List<Pair<Double, Double>>) : Drawn()
}

/**
 * Drawings into sketches: SVG and DXF read into [Drawn] curves, and those
 * added to a sketch with ends that meet joined.
 */
object DrawingImport {
    /** Reads a drawing by its file name's extension. Throws IllegalArgumentException if it isn't SVG or DXF. */
    fun read(name: String, text: String): List<Drawn> = when (name.substringAfterLast('.').lowercase()) {
        "svg" -> svg(text)
        "dxf" -> dxf(text)
        else -> throw IllegalArgumentException("Pick an SVG or DXF drawing")
    }

    /** Adds curves to a sketch, joining ends closer than [join] mm. Returns how many curves were added. */
    fun addTo(sketch: Sketch, curves: List<Drawn>, join: Double = 1e-4): Int {
        val points = mutableListOf<Point>()
        fun at(x: Double, y: Double): Point =
            points.firstOrNull { hypot(sketch.x(it) - x, sketch.y(it) - y) < join } ?: sketch.addPoint(x, y).also { points += it }
        var added = 0
        for (c in curves) {
            when (c) {
                is Drawn.Line -> if (hypot(c.x2 - c.x1, c.y2 - c.y1) > join) {
                    sketch.addLine(at(c.x1, c.y1), at(c.x2, c.y2)); added++
                }
                is Drawn.Circle -> if (c.r > join) { sketch.addCircle(sketch.addPoint(c.cx, c.cy), c.r); added++ }
                is Drawn.Arc -> if (c.r > join) {
                    val start = at(c.cx + c.r * cos(c.a0), c.cy + c.r * sin(c.a0))
                    val end = at(c.cx + c.r * cos(c.a1), c.cy + c.r * sin(c.a1))
                    if (start !== end) { sketch.addArc(sketch.addPoint(c.cx, c.cy), start, end); added++ }
                }
                is Drawn.Bezier -> {
                    // A spline through points along it.
                    val through = (0..4).map { i -> bezierAt(c, i / 4.0) }
                    if (hypot(c.x2 - c.x1, c.y2 - c.y1) > join || through.any { hypot(it.first - c.x1, it.second - c.y1) > join }) {
                        val pts = through.mapIndexed { i, (x, y) -> if (i == 0 || i == 4) at(x, y) else sketch.addPoint(x, y) }
                        sketch.addSpline(pts); added++
                    }
                }
                is Drawn.Through -> if (c.points.size >= 2) {
                    val pts = c.points.mapIndexed { i, (x, y) -> if (i == 0 || i == c.points.lastIndex) at(x, y) else sketch.addPoint(x, y) }
                    if (pts.first() !== pts.last() || pts.size > 3) { sketch.addSpline(pts); added++ }
                }
            }
        }
        return added
    }

    private fun bezierAt(b: Drawn.Bezier, t: Double): Pair<Double, Double> {
        val u = 1 - t
        val a = u * u * u; val p = 3 * u * u * t; val q = 3 * u * t * t; val d = t * t * t
        return (a * b.x1 + p * b.c1x + q * b.c2x + d * b.x2) to (a * b.y1 + p * b.c1y + q * b.c2y + d * b.y2)
    }

    // SVG.

    /** An affine map, x' = a x + c y + e, y' = b x + d y + f, as SVG writes matrix(a b c d e f). */
    private data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        fun apply(x: Double, y: Double) = (a * x + c * y + e) to (b * x + d * y + f)
        /** This, then [o]. */
        fun then(o: Affine) = Affine(
            o.a * a + o.c * b, o.b * a + o.d * b,
            o.a * c + o.c * d, o.b * c + o.d * d,
            o.a * e + o.c * f + o.e, o.b * e + o.d * f + o.f,
        )
        /** Its scale if it keeps circles round, else null. */
        fun roundScale(): Double? {
            val sx = hypot(a, b); val sy = hypot(c, d)
            return if (abs(sx - sy) < 1e-9 * maxOf(sx, 1.0) && abs(a * c + b * d) < 1e-9 * maxOf(sx * sy, 1.0)) sx else null
        }
        /** Whether it turns things over, which reverses which way arcs go. */
        val flips get() = a * d - b * c < 0

        companion object {
            val identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    private val tagPattern = Regex("""<(/?)([A-Za-z][\w:-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(/?)>""")
    private val attrPattern = Regex("""([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val numberPattern = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    private fun attrs(s: String) = attrPattern.findAll(s).associate { it.groupValues[1] to (it.groups[3]?.value ?: it.groups[4]?.value ?: "") }
    private fun numbers(s: String) = numberPattern.findAll(s).map { it.value.toDouble() }.toList()

    private fun transform(s: String?): Affine {
        if (s.isNullOrBlank()) return Affine.identity
        var out = Affine.identity
        for (m in Regex("""(\w+)\s*\(([^)]*)\)""").findAll(s)) {
            val n = numbers(m.groupValues[2])
            val t = when (m.groupValues[1]) {
                "matrix" -> if (n.size >= 6) Affine(n[0], n[1], n[2], n[3], n[4], n[5]) else Affine.identity
                "translate" -> Affine(1.0, 0.0, 0.0, 1.0, n.getOrElse(0) { 0.0 }, n.getOrElse(1) { 0.0 })
                "scale" -> Affine(n.getOrElse(0) { 1.0 }, 0.0, 0.0, n.getOrElse(1) { n.getOrElse(0) { 1.0 } }, 0.0, 0.0)
                "rotate" -> {
                    val r = n.getOrElse(0) { 0.0 } * PI / 180
                    val turn = Affine(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
                    if (n.size >= 3) Affine(1.0, 0.0, 0.0, 1.0, -n[1], -n[2]).then(turn).then(Affine(1.0, 0.0, 0.0, 1.0, n[1], n[2])) else turn
                }
                "skewX" -> Affine(1.0, 0.0, tan(n.getOrElse(0) { 0.0 } * PI / 180), 1.0, 0.0, 0.0)
                "skewY" -> Affine(1.0, tan(n.getOrElse(0) { 0.0 } * PI / 180), 0.0, 1.0, 0.0, 0.0)
                else -> Affine.identity
            }
            // Listed transforms apply right to left.
            out = t.then(out)
        }
        return out
    }

    /** A length with its unit, in mm; plain numbers and px at 96 to the inch. */
    private fun mm(s: String?): Double? {
        if (s == null) return null
        val v = numbers(s).firstOrNull() ?: return null
        return v * when {
            s.endsWith("mm") -> 1.0
            s.endsWith("cm") -> 10.0
            s.endsWith("in") -> 25.4
            s.endsWith("pt") -> 25.4 / 72
            s.endsWith("pc") -> 25.4 / 6
            else -> 25.4 / 96
        }
    }

    fun svg(text: String): List<Drawn> {
        val out = mutableListOf<Drawn>()
        val stack = ArrayDeque<Affine>()
        var current = Affine.identity
        for (m in tagPattern.findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].substringAfter(':')
            val selfClosing = m.groupValues[4] == "/"
            if (closing) {
                if (tag == "g" || tag == "svg" || tag == "a") current = stack.removeLastOrNull() ?: Affine.identity
                continue
            }
            val a = attrs(m.groupValues[3])
            val here = transform(a["transform"]).then(current)
            when (tag) {
                "svg" -> {
                    // User units to mm, from the size and viewBox; then y up.
                    val box = a["viewBox"]?.let { numbers(it) }?.takeIf { it.size == 4 }
                    val w = mm(a["width"]); val h = mm(a["height"])
                    val sx = if (box != null && w != null && box[2] > 0) w / box[2] else 25.4 / 96
                    val sy = if (box != null && h != null && box[3] > 0) h / box[3] else sx
                    val toMm = Affine(sx, 0.0, 0.0, -sy, -(box?.get(0) ?: 0.0) * sx, (box?.get(1) ?: 0.0) * sy)
                    stack.addLast(current)
                    current = toMm.then(current)
                    continue
                }
                "g", "a" -> if (!selfClosing) { stack.addLast(current); current = here; continue }
                "path" -> path(a["d"] ?: "", here, out)
                "line" -> {
                    val (x1, y1) = here.apply(a["x1"]?.toDoubleOrNull() ?: 0.0, a["y1"]?.toDoubleOrNull() ?: 0.0)
                    val (x2, y2) = here.apply(a["x2"]?.toDoubleOrNull() ?: 0.0, a["y2"]?.toDoubleOrNull() ?: 0.0)
                    out += Drawn.Line(x1, y1, x2, y2)
                }
                "rect" -> {
                    val x = a["x"]?.toDoubleOrNull() ?: 0.0; val y = a["y"]?.toDoubleOrNull() ?: 0.0
                    val w = a["width"]?.toDoubleOrNull() ?: 0.0; val h = a["height"]?.toDoubleOrNull() ?: 0.0
                    val rx = (a["rx"] ?: a["ry"])?.toDoubleOrNull()?.coerceAtMost(minOf(w, h) / 2) ?: 0.0
                    if (rx <= 0) polygon(listOf(x to y, x + w to y, x + w to y + h, x to y + h), true, here, out)
                    else path(
                        "M${x + rx},$y H${x + w - rx} A$rx,$rx 0 0 1 ${x + w},${y + rx} V${y + h - rx} A$rx,$rx 0 0 1 ${x + w - rx},${y + h} " +
                            "H${x + rx} A$rx,$rx 0 0 1 $x,${y + h - rx} V${y + rx} A$rx,$rx 0 0 1 ${x + rx},$y Z",
                        here, out,
                    )
                }
                "circle", "ellipse" -> {
                    val cx = a["cx"]?.toDoubleOrNull() ?: 0.0; val cy = a["cy"]?.toDoubleOrNull() ?: 0.0
                    val rx = (a["r"] ?: a["rx"])?.toDoubleOrNull() ?: 0.0
                    val ry = (a["r"] ?: a["ry"])?.toDoubleOrNull() ?: rx
                    val scale = here.roundScale()
                    if (abs(rx - ry) < 1e-12 && scale != null) {
                        val (x, y) = here.apply(cx, cy)
                        out += Drawn.Circle(x, y, rx * scale)
                    } else path("M${cx + rx},$cy A$rx,$ry 0 0 1 ${cx - rx},$cy A$rx,$ry 0 0 1 ${cx + rx},$cy Z", here, out)
                }
                "polyline", "polygon" -> {
                    val n = numbers(a["points"] ?: "")
                    polygon((0 until n.size / 2).map { n[2 * it] to n[2 * it + 1] }, tag == "polygon", here, out)
                }
            }
        }
        return out
    }

    private fun polygon(pts: List<Pair<Double, Double>>, closed: Boolean, t: Affine, out: MutableList<Drawn>) {
        val p = pts.map { t.apply(it.first, it.second) }
        for (i in 0 until p.size - 1) out += Drawn.Line(p[i].first, p[i].second, p[i + 1].first, p[i + 1].second)
        if (closed && p.size > 2) out += Drawn.Line(p.last().first, p.last().second, p[0].first, p[0].second)
    }

    /** An SVG path's d into curves, mapped by [t]. */
    private fun path(d: String, t: Affine, out: MutableList<Drawn>) {
        val tokens = Regex("""[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(d).map { it.value }.toList()
        var i = 0
        var x = 0.0; var y = 0.0
        var startX = 0.0; var startY = 0.0
        var lastCtrlX = 0.0; var lastCtrlY = 0.0
        var lastQuadX = 0.0; var lastQuadY = 0.0
        var command = 'M'
        var previous = ' '
        fun num(): Double = tokens[i++].toDouble()
        fun hasNumber() = i < tokens.size && tokens[i][0] !in "MmLlHhVvCcSsQqTtAaZz"
        fun line(x2: Double, y2: Double) {
            val (ax, ay) = t.apply(x, y); val (bx, by) = t.apply(x2, y2)
            out += Drawn.Line(ax, ay, bx, by)
        }
        fun cubic(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x2: Double, y2: Double) {
            val p0 = t.apply(x, y); val p1 = t.apply(c1x, c1y); val p2 = t.apply(c2x, c2y); val p3 = t.apply(x2, y2)
            out += Drawn.Bezier(p0.first, p0.second, p1.first, p1.second, p2.first, p2.second, p3.first, p3.second)
        }
        while (i < tokens.size) {
            if (!hasNumber()) { command = tokens[i++][0] } else if (command == 'M') command = 'L' else if (command == 'm') command = 'l'
            val rel = command.isLowerCase()
            val ox = if (rel) x else 0.0; val oy = if (rel) y else 0.0
            when (command.uppercaseChar()) {
                'M' -> { x = ox + num(); y = oy + num(); startX = x; startY = y }
                'L' -> { val nx = ox + num(); val ny = oy + num(); line(nx, ny); x = nx; y = ny }
                'H' -> { val nx = ox + num(); line(nx, y); x = nx }
                'V' -> { val ny = (if (rel) y else 0.0) + num(); line(x, ny); y = ny }
                'C' -> {
                    val c1x = ox + num(); val c1y = oy + num(); val c2x = ox + num(); val c2y = oy + num(); val nx = ox + num(); val ny = oy + num()
                    cubic(c1x, c1y, c2x, c2y, nx, ny); lastCtrlX = c2x; lastCtrlY = c2y; x = nx; y = ny
                }
                'S' -> {
                    val c1x = if (previous.uppercaseChar() in "CS") 2 * x - lastCtrlX else x
                    val c1y = if (previous.uppercaseChar() in "CS") 2 * y - lastCtrlY else y
                    val c2x = ox + num(); val c2y = oy + num(); val nx = ox + num(); val ny = oy + num()
                    cubic(c1x, c1y, c2x, c2y, nx, ny); lastCtrlX = c2x; lastCtrlY = c2y; x = nx; y = ny
                }
                'Q', 'T' -> {
                    val (qx, qy) = if (command.uppercaseChar() == 'Q') (ox + num()) to (oy + num())
                    else if (previous.uppercaseChar() in "QT") (2 * x - lastQuadX) to (2 * y - lastQuadY) else x to y
                    val nx = ox + num(); val ny = oy + num()
                    // A quadratic as a cubic.
                    cubic(x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), nx + 2.0 / 3 * (qx - nx), ny + 2.0 / 3 * (qy - ny), nx, ny)
                    lastQuadX = qx; lastQuadY = qy; x = nx; y = ny
                }
                'A' -> {
                    val rx = abs(num()); val ry = abs(num()); val rot = num(); val large = num() != 0.0; val sweep = num() != 0.0
                    val nx = ox + num(); val ny = oy + num()
                    arc(x, y, rx, ry, rot, large, sweep, nx, ny, t, out)
                    x = nx; y = ny
                }
                'Z' -> { if (hypot(x - startX, y - startY) > 1e-12) line(startX, startY); x = startX; y = startY }
            }
            previous = command
        }
    }

    /** An SVG arc, endpoint form, into a circular arc where it can be one, else Béziers. */
    private fun arc(x1: Double, y1: Double, rx0: Double, ry0: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double, t: Affine, out: MutableList<Drawn>) {
        if (rx0 < 1e-12 || ry0 < 1e-12) { val a = t.apply(x1, y1); val b = t.apply(x2, y2); out += Drawn.Line(a.first, a.second, b.first, b.second); return }
        // The centre form, as the SVG spec works it out.
        val phi = rotDeg * PI / 180
        val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2; val dy = (y1 - y2) / 2
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        var rx = rx0; var ry = ry0
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = sqrt(maxOf(0.0, num / den))
        if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry; val cyp = -co * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = acos(((ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val theta = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI else if (sweep && delta < 0) delta += 2 * PI
        val scale = t.roundScale()
        if (abs(rx - ry) < 1e-9 * rx && scale != null) {
            // A circular arc. SVG's y runs down, so its sweep direction is as mapped by t.
            val (ccx, ccy) = t.apply(cx, cy)
            val (sx, sy) = t.apply(x1, y1); val (ex, ey) = t.apply(x2, y2)
            val a0 = atan2(sy - ccy, sx - ccx); val a1 = atan2(ey - ccy, ex - ccx)
            // Which way it turns once mapped: delta's sign, flipped if t turns things over.
            val anticlockwise = (delta > 0) != t.flips
            out += if (anticlockwise) Drawn.Arc(ccx, ccy, rx * scale, a0, a1) else Drawn.Arc(ccx, ccy, rx * scale, a1, a0)
            return
        }
        // Elliptical: Béziers a quarter turn or less each.
        val pieces = maxOf(1, kotlin.math.ceil(abs(delta) / (PI / 2)).toInt())
        val step = delta / pieces
        val k = 4.0 / 3 * tan(step / 4)
        fun point(a: Double) = (cx + rx * cos(a) * cp - ry * sin(a) * sp) to (cy + rx * cos(a) * sp + ry * sin(a) * cp)
        fun deriv(a: Double) = (-rx * sin(a) * cp - ry * cos(a) * sp) to (-rx * sin(a) * sp + ry * cos(a) * cp)
        for (p in 0 until pieces) {
            val a0 = theta + p * step; val a1 = a0 + step
            val s = point(a0); val e = point(a1); val d0 = deriv(a0); val d1 = deriv(a1)
            val c1 = (s.first + k * d0.first) to (s.second + k * d0.second)
            val c2 = (e.first - k * d1.first) to (e.second - k * d1.second)
            val q0 = t.apply(s.first, s.second); val q1 = t.apply(c1.first, c1.second); val q2 = t.apply(c2.first, c2.second); val q3 = t.apply(e.first, e.second)
            out += Drawn.Bezier(q0.first, q0.second, q1.first, q1.second, q2.first, q2.second, q3.first, q3.second)
        }
    }

    // DXF.

    fun dxf(text: String): List<Drawn> {
        val lines = text.lines().map { it.trim() }
        val pairs = (0 until lines.size / 2).map { lines[2 * it].toIntOrNull() to lines[2 * it + 1] }
        // Drawing units to mm ($INSUNITS: 1 inch, 2 foot, 4 mm, 5 cm, 6 m; none is taken as mm).
        var unit = 1.0
        val units = pairs.indexOfFirst { it.first == 9 && it.second == "\$INSUNITS" }
        if (units >= 0) unit = when (pairs.getOrNull(units + 1)?.second?.trim()?.toIntOrNull()) {
            1 -> 25.4; 2 -> 304.8; 5 -> 10.0; 6 -> 1000.0; else -> 1.0
        }
        // Entities, each from its 0 code to the next.
        val start = pairs.indexOfFirst { it.first == 2 && it.second == "ENTITIES" }
        if (start < 0) return emptyList()
        val entities = mutableListOf<Pair<String, List<Pair<Int, String>>>>()
        var i = start + 1
        while (i < pairs.size) {
            val (code, value) = pairs[i]
            if (code == 0) {
                if (value == "ENDSEC") break
                val body = mutableListOf<Pair<Int, String>>()
                i++
                while (i < pairs.size && pairs[i].first != 0) { pairs[i].first?.let { c -> body += c to pairs[i].second }; i++ }
                entities += value to body
            } else i++
        }
        val out = mutableListOf<Drawn>()
        fun num(body: List<Pair<Int, String>>, code: Int, default: Double = 0.0) = body.firstOrNull { it.first == code }?.second?.toDoubleOrNull() ?: default
        fun all(body: List<Pair<Int, String>>, code: Int) = body.filter { it.first == code }.mapNotNull { it.second.toDoubleOrNull() }
        /** Straight or, with a bulge, an arc from one polyline point to the next. */
        fun segment(x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double) {
            if (abs(bulge) < 1e-12) { out += Drawn.Line(x1 * unit, y1 * unit, x2 * unit, y2 * unit); return }
            val sweep = 4 * atan(bulge)
            val chord = hypot(x2 - x1, y2 - y1)
            val r = chord / (2 * sin(abs(sweep) / 2))
            val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
            // The centre is off the chord's middle, to the left when the bulge is positive.
            val h = r * cos(abs(sweep) / 2) * (if ((bulge > 0) == (abs(sweep) < PI)) 1 else -1)
            val nx = -(y2 - y1) / chord; val ny = (x2 - x1) / chord
            val cx = mx + nx * h; val cy = my + ny * h
            val a1 = atan2(y1 - cy, x1 - cx); val a2 = atan2(y2 - cy, x2 - cx)
            out += if (bulge > 0) Drawn.Arc(cx * unit, cy * unit, r * unit, a1, a2) else Drawn.Arc(cx * unit, cy * unit, r * unit, a2, a1)
        }
        var polyline: MutableList<Triple<Double, Double, Double>>? = null
        var polylineClosed = false
        for ((type, b) in entities) {
            when (type) {
                "LINE" -> out += Drawn.Line(num(b, 10) * unit, num(b, 20) * unit, num(b, 11) * unit, num(b, 21) * unit)
                "CIRCLE" -> out += Drawn.Circle(num(b, 10) * unit, num(b, 20) * unit, num(b, 40) * unit)
                "ARC" -> out += Drawn.Arc(num(b, 10) * unit, num(b, 20) * unit, num(b, 40) * unit, num(b, 50) * PI / 180, num(b, 51) * PI / 180)
                "LWPOLYLINE" -> {
                    // Points in order, each with the bulge to the next.
                    val pts = mutableListOf<Triple<Double, Double, Double>>()
                    for ((c, v) in b) when (c) {
                        10 -> pts += Triple(v.toDouble(), 0.0, 0.0)
                        20 -> if (pts.isNotEmpty()) pts[pts.lastIndex] = pts.last().copy(second = v.toDouble())
                        42 -> if (pts.isNotEmpty()) pts[pts.lastIndex] = pts.last().copy(third = v.toDouble())
                    }
                    val closed = (num(b, 70).toInt() and 1) == 1
                    for (k in 0 until pts.size - 1) segment(pts[k].first, pts[k].second, pts[k + 1].first, pts[k + 1].second, pts[k].third)
                    if (closed && pts.size > 2) segment(pts.last().first, pts.last().second, pts[0].first, pts[0].second, pts.last().third)
                }
                "POLYLINE" -> { polyline = mutableListOf(); polylineClosed = (num(b, 70).toInt() and 1) == 1 }
                "VERTEX" -> polyline?.add(Triple(num(b, 10), num(b, 20), num(b, 42)))
                "SEQEND" -> polyline?.let { pts ->
                    for (k in 0 until pts.size - 1) segment(pts[k].first, pts[k].second, pts[k + 1].first, pts[k + 1].second, pts[k].third)
                    if (polylineClosed && pts.size > 2) segment(pts.last().first, pts.last().second, pts[0].first, pts[0].second, pts.last().third)
                    polyline = null
                }
                "SPLINE" -> {
                    // Through its fit points if it has them, else near its control points.
                    val fx = all(b, 11); val fy = all(b, 21)
                    val cx = all(b, 10); val cy = all(b, 20)
                    val (xs, ys) = if (fx.size >= 2 && fx.size == fy.size) fx to fy else cx to cy
                    if (xs.size >= 2 && xs.size == ys.size) out += Drawn.Through(xs.indices.map { xs[it] * unit to ys[it] * unit })
                }
                "ELLIPSE" -> {
                    val cx = num(b, 10); val cy = num(b, 20)
                    val mx = num(b, 11); val my = num(b, 21)
                    val ratio = num(b, 40, 1.0)
                    var t0 = num(b, 41); var t1 = num(b, 42, 2 * PI)
                    if (t1 <= t0) t1 += 2 * PI
                    val major = hypot(mx, my); val rot = atan2(my, mx)
                    val n = 24
                    out += Drawn.Through((0..n).map { k ->
                        val a = t0 + (t1 - t0) * k / n
                        val x = major * cos(a); val y = major * ratio * sin(a)
                        (cx + x * cos(rot) - y * sin(rot)) * unit to (cy + x * sin(rot) + y * cos(rot)) * unit
                    })
                }
            }
        }
        return out
    }
}
