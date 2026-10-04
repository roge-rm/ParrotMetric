package com.rm.parrotmetric.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.sketch.Arc
import com.rm.parrotmetric.sketch.Circle
import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.sketch.Curve
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.Point
import com.rm.parrotmetric.sketch.Spline
import com.rm.parrotmetric.ui.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

private val free = Color(0xFF8CC8F0)
private val pinned = Color(0xFFF2E4CF)
private val chosen = Color(0xFFFF7A3D)
private val constructionColour = Color(0xFFD9B84A)

/** A dimension's pill or a constraint's glyph, placed on screen. */
private class Annotation(val item: SketchItem, val centre: Offset, val text: String?, val kind: Constraint)

/**
 * Draws the sketch over the 3D view, lined up with it through [projection],
 * and turns touches into sketch edits. Two fingers pan and zoom the view;
 * so does one finger on empty space when nothing's being drawn.
 */
@Composable
fun SketchOverlay(
    editor: SketchEditor,
    projection: PlaneProjection,
    onPan: (dx: Float, dy: Float) -> Unit,
    onZoom: (factor: Float) -> Unit,
    onZoomAt: (factor: Float, x: Float, y: Float) -> Unit = { f, _, _ -> onZoom(f) },
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val proj by rememberUpdatedState(projection)
    val slop = 8 * density
    // How near, on screen, a touch has to be to snap or hit.
    val reach = 16 * density
    // A finger is down, hiding what's under it, so the magnifier shows; not for a mouse.
    var touching by remember { mutableStateOf(false) }

    Canvas(
        Modifier.fillMaxSize().pointerInput(editor) {
            // With a mouse, the shape being drawn follows the pointer between clicks.
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: continue
                    // The wheel zooms towards the pointer; a notch is at most three steps, as browsers give big ones.
                    if (e.type == PointerEventType.Scroll && c.scrollDelta.y != 0f) {
                        onZoomAt(1.15f.pow(-c.scrollDelta.y.coerceIn(-3f, 3f)), c.position.x, c.position.y)
                        continue
                    }
                    if (c.type != PointerType.Mouse || c.pressed) continue
                    when (e.type) {
                        PointerEventType.Move -> proj.toPlane(c.position)?.let { editor.hover(it.first, it.second, reach * proj.mmPerPixel()) }
                        PointerEventType.Exit -> editor.hover(null, 0.0, 0.0)
                    }
                }
            }
        }.pointerInput(editor) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                touching = down.type != PointerType.Mouse
                fun plane(o: Offset) = proj.toPlane(o)
                fun tol() = reach * proj.mmPerPixel()
                var multi = false
                var moved = false
                var last = down.position
                var lastSpan = 0f
                val start = plane(down.position)
                // The middle (or right) mouse button pans; only the left draws and picks.
                val panButton = down.type == PointerType.Mouse && (currentEvent.buttons.isTertiaryPressed || currentEvent.buttons.isSecondaryPressed)
                val grabbed = !panButton && start != null && editor.press(start.first, start.second, tol())
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val c = Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat())
                        val span = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                        if (!multi) {
                            multi = true
                            editor.cancelPress()
                        } else {
                            onPan(c.x - last.x, c.y - last.y)
                            if (lastSpan > 0 && span > 0) onZoom(span / lastSpan)
                        }
                        last = c
                        lastSpan = span
                    } else if (pressed.size == 1 && !multi) {
                        val p = pressed[0].position
                        if (!moved && (p - down.position).getDistance() > slop) moved = true
                        if (grabbed) plane(p)?.let { editor.move(it.first, it.second, tol()) }
                        else if (moved) onPan(p.x - last.x, p.y - last.y)
                        last = p
                    }
                    event.changes.forEach { it.consume() }
                    if (pressed.isEmpty()) {
                        touching = false
                        if (!multi && !panButton) {
                            val up = event.changes.firstOrNull()?.position ?: last
                            val tappedNote = if (!moved && editor.tool in setOf(SketchTool.Select, SketchTool.Dimension)) {
                                annotations(editor, proj, density).minByOrNull { (it.centre - up).getDistance() }
                                    ?.takeIf { (it.centre - up).getDistance() < 18 * density }
                            } else null
                            if (tappedNote != null) {
                                editor.cancelPress()
                                editor.tap(tappedNote.item)
                            } else {
                                plane(up)?.let { editor.release(it.first, it.second, tol(), moved) }
                            }
                        }
                        break
                    }
                }
            }
        },
    ) {
        editor.version // Redraw on every change.
        drawSketch(editor, proj, density, measurer)
        if (touching) editor.preview?.let { snap ->
            val finger = proj.toScreen(snap.u, snap.v)
            val r = 52 * density
            val lens = Offset(finger.x, (finger.y - 120 * density).coerceAtLeast(r + 8 * density))
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(lens, r)) }
            drawCircle(Palette.ground, r, lens)
            clipPath(circle) {
                translate(lens.x - finger.x, lens.y - finger.y) {
                    scale(2.5f, pivot = finger) { drawSketch(editor, proj, density, measurer, magnified = true) }
                }
            }
            drawCircle(pinned, r, lens, style = Stroke(1.5f * density))
            val snapped = snap.point != null || snap.curve != null || snap.midpointOf != null || snap.axis != null || snap.alignedWith != null
            drawCircle(if (snapped) Palette.mint else free, 6 * density, lens, style = Stroke(2 * density))
        }
    }
}

private fun DrawScope.drawSketch(editor: SketchEditor, proj: PlaneProjection, density: Float, measurer: TextMeasurer, magnified: Boolean = false) {
    val sketch = editor.sketch
    val dp = density
    val line = (if (magnified) 0.8f else 2f) * dp

    drawGrid(proj, dp)

    // Axes through the origin.
    val o = proj.toScreen(0.0, 0.0)
    val far = 1e5
    drawLine(Color(0xFF8A4A30).copy(alpha = 0.8f), proj.toScreen(-far, 0.0), proj.toScreen(far, 0.0), 1.2f * dp)
    drawLine(Color(0xFF3E8A62).copy(alpha = 0.8f), proj.toScreen(0.0, -far), proj.toScreen(0.0, far), 1.2f * dp)

    // Closed regions, faintly filled.
    for (r in editor.regions) {
        val path = Path()
        for (loop in r.loops) {
            for (i in 0 until loop.size / 2) {
                val s = proj.toScreen(loop[2 * i].toDouble(), loop[2 * i + 1].toDouble())
                if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
            }
            path.close()
        }
        path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        drawPath(path, Palette.mint.copy(alpha = 0.09f))
    }

    val freedom = editor.freedom
    val selected = editor.selection.toSet()
    for (c in sketch.curves) {
        val colour = when {
            SketchItem.C(c) in selected -> chosen
            c.construction -> constructionColour
            c in freedom.freeCurves -> free
            else -> pinned
        }
        val effect = if (c.construction) PathEffect.dashPathEffect(floatArrayOf(6 * dp, 5 * dp)) else null
        drawCurve(c, editor, proj, colour, line * (if (SketchItem.C(c) in selected) 1.4f else 1f), effect)
    }

    // A spline being drawn, through the points placed so far.
    if (editor.tool == SketchTool.Spline && editor.pending.size >= 2 && editor.preview == null) {
        drawPolyline(editor.pending.map { screen(it, editor, proj) }, free.copy(alpha = 0.9f), line, PathEffect.dashPathEffect(floatArrayOf(7 * dp, 6 * dp)))
    }

    // Drawing in progress.
    editor.preview?.let { snap ->
        val end = proj.toScreen(snap.u, snap.v)
        val rubber = free.copy(alpha = 0.9f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(7 * dp, 6 * dp))
        val pending = editor.pending
        when (editor.tool) {
            SketchTool.Line -> pending.lastOrNull()?.let { drawLine(rubber, screen(it, editor, proj), end, line, pathEffect = dash) }
            SketchTool.Rectangle -> if (editor.rectangleFromCentre) pending.firstOrNull()?.let {
                // Mirrored through the centre.
                val cx = sketch.x(it); val cy = sketch.y(it)
                val ou = 2 * cx - snap.u; val ov = 2 * cy - snap.v
                val corners = listOf(snap.u to snap.v, ou to snap.v, ou to ov, snap.u to ov).map { (u, v) -> proj.toScreen(u, v) }
                val path = Path().apply { moveTo(corners[0].x, corners[0].y); corners.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, rubber, style = Stroke(line, pathEffect = dash))
            } else pending.firstOrNull()?.let {
                val a = screen(it, editor, proj)
                val b = proj.toScreen(snap.u, sketch.y(it))
                val d = proj.toScreen(sketch.x(it), snap.v)
                val path = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(end.x, end.y); lineTo(d.x, d.y); close() }
                drawPath(path, rubber, style = Stroke(line, pathEffect = dash))
            }
            SketchTool.Circle -> pending.firstOrNull()?.let {
                val c = screen(it, editor, proj)
                drawCircle(rubber, (end - c).getDistance(), c, style = Stroke(line, pathEffect = dash))
            }
            SketchTool.Spline -> if (pending.isNotEmpty()) {
                val pts = pending.map { screen(it, editor, proj) } + end
                drawPolyline(pts, rubber, line, dash)
            }
            SketchTool.Polygon -> pending.firstOrNull()?.let {
                val cx = sketch.x(it); val cy = sketch.y(it)
                val r = hypot(snap.u - cx, snap.v - cy)
                val a0 = atan2(snap.v - cy, snap.u - cx)
                val n = editor.polygonSides
                drawPolyline((0..n).map { i -> val a = a0 + 2 * PI * i / n; proj.toScreen(cx + r * cos(a), cy + r * sin(a)) }, rubber, line, dash)
            }
            SketchTool.Slot -> if (pending.size >= 1) {
                drawLine(rubber, screen(pending[0], editor, proj), if (pending.size == 2) screen(pending[1], editor, proj) else end, line, pathEffect = dash)
                if (pending.size == 2) drawLine(rubber, screen(pending[1], editor, proj), end, line, pathEffect = dash)
            }
            SketchTool.Arc -> if (editor.arcThroughPoints) {
                if (pending.size == 1) drawLine(rubber, screen(pending[0], editor, proj), end, line, pathEffect = dash)
                else if (pending.size == 2) {
                    val ax = sketch.x(pending[0]); val ay = sketch.y(pending[0])
                    val bx = sketch.x(pending[1]); val by = sketch.y(pending[1])
                    circleThrough(ax, ay, bx, by, snap.u, snap.v)?.let { c ->
                        val r = hypot(ax - c.first, ay - c.second)
                        val (fromX, fromY, toX, toY) = if (anticlockwiseBetween(c, ax, ay, snap.u, snap.v, bx, by)) listOf(ax, ay, bx, by) else listOf(bx, by, ax, ay)
                        val a0 = atan2(fromY - c.second, fromX - c.first)
                        var a1 = atan2(toY - c.second, toX - c.first)
                        while (a1 <= a0) a1 += 2 * PI
                        drawPolyline(arcPoints(c.first, c.second, r, a0, a1).map { proj.toScreen(it.first, it.second) }, rubber, line, dash)
                    } ?: drawLine(rubber, screen(pending[0], editor, proj), screen(pending[1], editor, proj), line, pathEffect = dash)
                }
            } else if (pending.size == 1) {
                val c = screen(pending[0], editor, proj)
                drawCircle(rubber.copy(alpha = 0.4f), (end - c).getDistance(), c, style = Stroke(line, pathEffect = dash))
            } else if (pending.size == 2) {
                val cx = sketch.x(pending[0]); val cy = sketch.y(pending[0])
                val r = sketch.distance(pending[0], pending[1])
                val a0 = atan2(sketch.y(pending[1]) - cy, sketch.x(pending[1]) - cx)
                var a1 = atan2(snap.v - cy, snap.u - cx)
                while (a1 <= a0) a1 += 2 * PI
                drawPolyline(arcPoints(cx, cy, r, a0, a1).map { proj.toScreen(it.first, it.second) }, rubber, line, dash)
            }
            else -> {}
        }
        drawCircle(free, 4.5f * dp * (if (magnified) 0.4f else 1f), end)
        // Its size as it's drawn, beside the pointer.
        if (!magnified) liveSize(editor, snap.u, snap.v)?.let { label ->
            val text = measurer.measure(label, TextStyle(color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace))
            val w = text.size.width + 16 * dp
            val h = 24 * dp
            val at = end + Offset(22 * dp, -30 * dp)
            drawRoundRect(Color(0xFF2C3433), Offset(at.x, at.y - h / 2), Size(w, h), CornerRadius(h / 2))
            drawText(text, topLeft = Offset(at.x + 8 * dp, at.y - text.size.height / 2))
        }
    }

    // Points: the origin, line ends and centres.
    for (p in sketch.points) {
        val s = screen(p, editor, proj)
        val colour = when {
            SketchItem.P(p) in selected -> chosen
            p === sketch.origin -> Palette.mint
            p in freedom.freePoints -> free
            else -> pinned
        }
        drawCircle(colour, (if (p === sketch.origin) 4f else 3.2f) * dp * (if (magnified) 0.5f else 1f), s)
    }
    if (o.x.isFinite()) drawCircle(Palette.ground, 1.6f * dp, o)

    if (magnified) return
    for (a in annotations(editor, proj, dp)) {
        val isChosen = a.item in selected
        if (a.text != null) {
            val text = measurer.measure(a.text, TextStyle(color = if (isChosen) chosen else pinned, fontSize = 13.sp, fontFamily = FontFamily.Monospace))
            val w = text.size.width + 16 * dp
            val h = 24 * dp
            drawRoundRect(Color(0xFF2C3433), Offset(a.centre.x - w / 2, a.centre.y - h / 2), Size(w, h), CornerRadius(h / 2))
            drawText(text, topLeft = Offset(a.centre.x - text.size.width / 2, a.centre.y - text.size.height / 2))
        } else {
            drawGlyph(a.kind, a.centre, dp, if (isChosen) chosen else Palette.yellow)
        }
    }
}

/** Sizes in mm as labels show them: up to two decimals, no trailing zeros. */
private fun mm(v: Double): String {
    val r = kotlin.math.round(v * 100) / 100
    return if (r == floor(r)) r.toLong().toString() else r.toString()
}

private fun degrees(a: Double) = mm(a * 180 / PI) + "°"

/** What the shape being drawn measures with its next point at (u, v), or null before it has a first point. */
private fun liveSize(editor: SketchEditor, u: Double, v: Double): String? {
    val s = editor.sketch
    val p = editor.pending
    if (p.isEmpty()) return null
    // Sizes being typed: each with what's typed so far, the one taking keys marked.
    editor.typed?.let { t ->
        return t.labels.indices.joinToString("   ") { i ->
            t.labels[i] + " " + t.texts[i].ifEmpty { if (i == t.active) "" else "–" } + (if (i == t.active) "▏" else "") +
                (if (t.angle[i] && t.texts[i].isNotEmpty()) "°" else "")
        }
    }
    val x0 = s.x(p[0]); val y0 = s.y(p[0])
    return when (editor.tool) {
        SketchTool.Line -> {
            val a = p.last()
            val dx = u - s.x(a); val dy = v - s.y(a)
            mm(hypot(dx, dy)) + "  " + degrees(atan2(dy, dx))
        }
        SketchTool.Rectangle -> {
            val k = if (editor.rectangleFromCentre) 2 else 1
            mm(kotlin.math.abs(u - x0) * k) + " × " + mm(kotlin.math.abs(v - y0) * k)
        }
        SketchTool.Circle -> "⌀ " + mm(2 * hypot(u - x0, v - y0))
        SketchTool.Polygon -> "R " + mm(hypot(u - x0, v - y0))
        SketchTool.Arc -> when {
            editor.arcThroughPoints && p.size == 1 -> mm(hypot(u - x0, v - y0))
            editor.arcThroughPoints -> circleThrough(x0, y0, s.x(p[1]), s.y(p[1]), u, v)?.let { c -> "R " + mm(hypot(x0 - c.first, y0 - c.second)) }
            p.size == 1 -> "R " + mm(hypot(u - x0, v - y0))
            else -> {
                var sweep = atan2(v - y0, u - x0) - atan2(s.y(p[1]) - y0, s.x(p[1]) - x0)
                while (sweep <= 0) sweep += 2 * PI
                degrees(sweep)
            }
        }
        SketchTool.Slot -> if (p.size == 1) mm(hypot(u - x0, v - y0)) else {
            val bx = s.x(p[1]); val by = s.y(p[1])
            val len = hypot(bx - x0, by - y0).coerceAtLeast(1e-9)
            "↕ " + mm(2 * kotlin.math.abs((bx - x0) * (v - y0) - (by - y0) * (u - x0)) / len)
        }
        else -> null
    }
}

/** A light grid on the plane, its spacing stepped 1-2-5 so lines stay a comfortable distance apart on screen. */
private fun DrawScope.drawGrid(proj: PlaneProjection, dp: Float) {
    val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).mapNotNull { proj.toPlane(it) }
    if (corners.size < 4) return
    val minU = corners.minOf { it.first }; val maxU = corners.maxOf { it.first }
    val minV = corners.minOf { it.second }; val maxV = corners.maxOf { it.second }
    val want = 14 * dp * proj.mmPerPixel()
    val decade = 10.0.pow(floor(ln(want) / ln(10.0)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * decade }.first { it >= want }
    val majorEvery = if (step / decade == 2.0) 5 else if (step / decade == 5.0) 2 else 10
    if ((maxU - minU) / step > 400 || (maxV - minV) / step > 400) return
    var i = ceil(minU / step).toLong()
    while (i * step <= maxU) {
        val major = i % majorEvery == 0L
        drawLine(Color.White.copy(alpha = if (major) 0.10f else 0.045f), proj.toScreen(i * step, minV), proj.toScreen(i * step, maxV), 1f)
        i++
    }
    i = ceil(minV / step).toLong()
    while (i * step <= maxV) {
        val major = i % majorEvery == 0L
        drawLine(Color.White.copy(alpha = if (major) 0.10f else 0.045f), proj.toScreen(minU, i * step), proj.toScreen(maxU, i * step), 1f)
        i++
    }
}

private fun centreOf(c: Curve): Point = when (c) {
    is Circle -> c.centre
    is Arc -> c.centre
    is Line -> c.a
    is Spline -> c.through.first()
}

private fun screen(p: Point, editor: SketchEditor, proj: PlaneProjection) = proj.toScreen(editor.sketch.x(p), editor.sketch.y(p))

private fun arcPoints(cx: Double, cy: Double, r: Double, a0: Double, a1: Double): List<Pair<Double, Double>> {
    val n = maxOf(8, ((a1 - a0) / (PI / 48)).toInt())
    return (0..n).map { i -> val a = a0 + (a1 - a0) * i / n; cx + r * cos(a) to cy + r * sin(a) }
}

private fun DrawScope.drawPolyline(points: List<Offset>, colour: Color, width: Float, effect: PathEffect? = null) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
}

private fun DrawScope.drawCurve(c: Curve, editor: SketchEditor, proj: PlaneProjection, colour: Color, width: Float, effect: PathEffect?) {
    val s = editor.sketch
    when (c) {
        is Line -> drawLine(colour, screen(c.a, editor, proj), screen(c.b, editor, proj), width, StrokeCap.Round, effect)
        is Circle -> drawPolyline(arcPoints(s.x(c.centre), s.y(c.centre), s.radius(c), 0.0, 2 * PI).map { proj.toScreen(it.first, it.second) }, colour, width, effect)
        is Arc -> {
            val cx = s.x(c.centre); val cy = s.y(c.centre)
            val a0 = atan2(s.y(c.start) - cy, s.x(c.start) - cx)
            var a1 = atan2(s.y(c.end) - cy, s.x(c.end) - cx)
            while (a1 <= a0) a1 += 2 * PI
            drawPolyline(arcPoints(cx, cy, s.radius(c), a0, a1).map { proj.toScreen(it.first, it.second) }, colour, width, effect)
        }
        is Spline -> drawPolyline(s.sampleSpline(c).map { proj.toScreen(it.first, it.second) }, colour, width, effect)
    }
}

/** Where each dimension's pill and each constraint's glyph goes on screen. */
private fun annotations(editor: SketchEditor, proj: PlaneProjection, dp: Float): List<Annotation> {
    val s = editor.sketch
    val out = mutableListOf<Annotation>()
    val stacked = mutableMapOf<Curve, Int>()
    fun mid(l: Line) = (screen(l.a, editor, proj) + screen(l.b, editor, proj)) / 2f
    // Dimensions go on the side of a line away from the middle of the sketch.
    val middle = s.points.filter { it !== s.origin }.map { screen(it, editor, proj) }
        .let { pts -> if (pts.isEmpty()) Offset.Zero else Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat()) }
    fun outward(a: Offset, b: Offset): Offset {
        val d = b - a
        val len = d.getDistance().coerceAtLeast(1e-3f)
        val n = Offset(-d.y / len, d.x / len)
        val m = (a + b) / 2f - middle
        return if (n.x * m.x + n.y * m.y < 0) -n else n
    }
    fun normal(a: Offset, b: Offset): Offset {
        val d = b - a
        val len = d.getDistance().coerceAtLeast(1e-3f)
        return Offset(-d.y / len, d.x / len)
    }
    fun along(a: Offset, b: Offset): Offset {
        val d = b - a
        val len = d.getDistance().coerceAtLeast(1e-3f)
        return Offset(d.x / len, d.y / len)
    }
    fun format(v: Double): String {
        val r = kotlin.math.round(v * 100) / 100
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }
    for (c in s.constraints) {
        val item = SketchItem.K(c)
        when (c) {
            is Constraint.ArcRadius -> {}
            is Constraint.Length -> {
                val a = screen(c.line.a, editor, proj); val b = screen(c.line.b, editor, proj)
                out += Annotation(item, (a + b) / 2f + outward(a, b) * (22 * dp), c.expression ?: format(c.value), c)
            }
            is Constraint.Distance -> {
                val a = screen(c.p, editor, proj); val b = screen(c.q, editor, proj)
                out += Annotation(item, (a + b) / 2f + normal(a, b) * (22 * dp), format(c.value), c)
            }
            is Constraint.AxisDistance -> {
                val a = screen(c.p, editor, proj); val b = screen(c.q, editor, proj)
                out += Annotation(item, (a + b) / 2f + Offset(0f, -18 * dp), format(c.value), c)
            }
            is Constraint.PointLineDistance -> {
                val p = screen(c.p, editor, proj)
                val a = screen(c.line.a, editor, proj); val b = screen(c.line.b, editor, proj)
                val d = along(a, b)
                val foot = a + d * ((p - a).x * d.x + (p - a).y * d.y)
                out += Annotation(item, (p + foot) / 2f + d * (16 * dp), format(c.value), c)
            }
            is Constraint.Radius -> {
                val curve = c.curve
                val cx = s.x(centreOf(curve)); val cy = s.y(centreOf(curve))
                val angle = if (curve is Arc) {
                    val a0 = atan2(s.y(curve.start) - cy, s.x(curve.start) - cx)
                    var a1 = atan2(s.y(curve.end) - cy, s.x(curve.end) - cx)
                    while (a1 <= a0) a1 += 2 * PI
                    (a0 + a1) / 2
                } else PI / 4
                val r = when (curve) {
                    is Circle -> s.radius(curve)
                    is Arc -> s.radius(curve)
                    is Line -> s.length(curve)
                    is Spline -> 0.0
                }
                val centre = proj.toScreen(cx, cy)
                val rim = proj.toScreen(cx + r * cos(angle), cy + r * sin(angle))
                out += Annotation(item, rim + along(centre, rim) * (20 * dp), (if (c.diameter) "Ø" else "R") + format(c.value), c)
            }
            is Constraint.Angle -> {
                val a = mid(c.l1); val b = mid(c.l2)
                out += Annotation(item, (a + b) / 2f, format(abs(c.value) * 180 / PI) + "°", c)
            }
            else -> {
                // Glyphs sit beside the first line they're about, or beside the point.
                // Equal goes on the second, so a polygon's marks spread one to a side.
                val l = (if (c is Constraint.Equal) c.curves().lastOrNull { it is Line } else c.curves().firstOrNull { it is Line }) as Line?
                if (l != null) {
                    val n = stacked.getOrElse(l) { 0 }
                    stacked[l] = n + 1
                    val a = screen(l.a, editor, proj); val b = screen(l.b, editor, proj)
                    out += Annotation(item, (a + b) / 2f - outward(a, b) * (16 * dp) + along(a, b) * (n * 24 * dp), null, c)
                } else {
                    val p = c.points().firstOrNull { it !== s.origin } ?: continue
                    out += Annotation(item, screen(p, editor, proj) + Offset(15 * dp, -15 * dp), null, c)
                }
            }
        }
    }
    return out
}

/** A small tile with a drawing of the constraint. */
private fun DrawScope.drawGlyph(c: Constraint, at: Offset, dp: Float, colour: Color) {
    val h = 10 * dp
    drawRoundRect(Color(0xFF2C3433), Offset(at.x - h, at.y - h), Size(2 * h, 2 * h), CornerRadius(6 * dp))
    val w = 1.8f * dp
    fun l(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(colour, at + Offset(x1 * dp, y1 * dp), at + Offset(x2 * dp, y2 * dp), w, StrokeCap.Round)
    fun dot(x: Float, y: Float) = drawCircle(colour, 2 * dp, at + Offset(x * dp, y * dp))
    when (c) {
        is Constraint.Horizontal -> l(-5f, 0f, 5f, 0f)
        is Constraint.Vertical -> l(0f, -5f, 0f, 5f)
        is Constraint.HorizontalPoints -> { dot(-5f, 0f); dot(5f, 0f); l(-2f, 0f, 2f, 0f) }
        is Constraint.VerticalPoints -> { dot(0f, -5f); dot(0f, 5f); l(0f, -2f, 0f, 2f) }
        is Constraint.Parallel -> { l(-5f, 4f, -1f, -4f); l(1f, 4f, 5f, -4f) }
        is Constraint.Perpendicular -> { l(-5f, 5f, 5f, 5f); l(0f, 5f, 0f, -5f) }
        is Constraint.Equal -> { l(-5f, -2.5f, 5f, -2.5f); l(-5f, 2.5f, 5f, 2.5f) }
        is Constraint.TangentLine, is Constraint.TangentCircles -> {
            drawArc(colour, 180f, 180f, false, at + Offset(-5 * dp, -2 * dp), Size(10 * dp, 10 * dp), style = Stroke(w))
            l(-6f, -2f, 6f, -2f)
        }
        is Constraint.Coincident -> dot(0f, 0f)
        is Constraint.Fixed -> {
            drawArc(colour, 180f, 180f, false, at + Offset(-3.5f * dp, -6 * dp), Size(7 * dp, 7 * dp), style = Stroke(w))
            drawRoundRect(colour, at + Offset(-5 * dp, -1 * dp), Size(10 * dp, 7 * dp), CornerRadius(1.5f * dp))
        }
        is Constraint.Midpoint -> { l(-6f, 0f, 6f, 0f); dot(0f, 0f) }
        is Constraint.OnLine -> { l(-6f, 3f, 6f, -3f); dot(0f, 0f) }
        is Constraint.OnCircle -> {
            drawCircle(colour, 5 * dp, at, style = Stroke(w))
            dot(3.5f, -3.5f)
        }
        is Constraint.Symmetric -> { l(0f, -6f, 0f, 6f); dot(-5f, 0f); dot(5f, 0f) }
        else -> dot(0f, 0f)
    }
}
