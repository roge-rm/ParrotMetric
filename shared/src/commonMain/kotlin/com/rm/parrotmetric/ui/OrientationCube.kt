package com.rm.parrotmetric.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One face of the cube: its outward normal, its label and the view it turns to. */
private class CubeFace(val normal: Triple<Float, Float, Float>, val label: String, val yaw: Float?, val pitch: Float)

private val faces = listOf(
    CubeFace(Triple(0f, 0f, 1f), "TOP", null, 1.5607f),
    CubeFace(Triple(0f, 0f, -1f), "BOTTOM", null, -1.5607f),
    CubeFace(Triple(0f, -1f, 0f), "FRONT", (-PI / 2).toFloat(), 0f),
    CubeFace(Triple(0f, 1f, 0f), "BACK", (PI / 2).toFloat(), 0f),
    CubeFace(Triple(1f, 0f, 0f), "RIGHT", 0f, 0f),
    CubeFace(Triple(-1f, 0f, 0f), "LEFT", PI.toFloat(), 0f),
)

/**
 * A cube turned with the camera. Tapping a face turns the view to look at
 * that face; top and bottom square the view up to the nearest side.
 */
@Composable
fun OrientationCube(yaw: Float, pitch: Float, onViewFrom: (yaw: Float, pitch: Float) -> Unit, size: Dp = 60.dp, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
    // Camera axes, as in the renderer: right, up, and towards the eye.
    val right = Triple(-sy, cy, 0f)
    val eye = Triple(cp * cy, cp * sy, sp)
    val up = Triple(-sp * cy, -sp * sy, cp)
    fun dot(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>) = a.first * b.first + a.second * b.second + a.third * b.third

    // Each visible face's corners on screen, nearest last.
    val visible = faces.filter { dot(it.normal, eye) > 0.02f }.sortedBy { dot(it.normal, eye) }
    fun corners(f: CubeFace, half: Float, centre: Offset): List<Offset> {
        val n = f.normal
        // Two directions across the face.
        val a = if (n.third != 0f) Triple(1f, 0f, 0f) else Triple(0f, 0f, 1f)
        val b = Triple(n.second * a.third - n.third * a.second, n.third * a.first - n.first * a.third, n.first * a.second - n.second * a.first)
        return listOf(1f to 1f, -1f to 1f, -1f to -1f, 1f to -1f).map { (s, t) ->
            val p = Triple(n.first + s * a.first + t * b.first, n.second + s * a.second + t * b.second, n.third + s * a.third + t * b.third)
            Offset(centre.x + dot(p, right) * half, centre.y - dot(p, up) * half)
        }
    }
    fun inside(p: Offset, poly: List<Offset>): Boolean {
        var hit = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) hit = !hit
            j = i
        }
        return hit
    }

    Canvas(
        modifier.size(size).pointerInput(yaw, pitch) {
            detectTapGestures { tap ->
                val half = this.size.width * 0.29f
                val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                visible.lastOrNull { inside(tap, corners(it, half, centre)) }?.let {
                    val quarter = (PI / 2).toFloat()
                    onViewFrom(it.yaw ?: (kotlin.math.round(yaw / quarter) * quarter), it.pitch)
                }
            }
        },
    ) {
        val half = this.size.width * 0.29f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        for (f in visible) {
            val c = corners(f, half, centre)
            val path = Path().apply {
                moveTo(c[0].x, c[0].y)
                for (k in 1 until 4) lineTo(c[k].x, c[k].y)
                close()
            }
            val light = dot(f.normal, eye)
            drawPath(path, lerp(Palette.surface, Palette.teal, 0.35f + 0.5f * light))
            drawPath(path, Palette.ground, style = Stroke(width = 1.5f))
            if (light > 0.35f) {
                val text = measurer.measure(f.label, TextStyle(color = Palette.text, fontSize = 6.5.sp, fontWeight = FontWeight.Bold))
                val mid = Offset(c.sumOf { it.x.toDouble() }.toFloat() / 4f, c.sumOf { it.y.toDouble() }.toFloat() / 4f)
                drawText(text, topLeft = Offset(mid.x - text.size.width / 2f, mid.y - text.size.height / 2f), alpha = ((light - 0.35f) / 0.4f).coerceIn(0f, 1f))
            }
        }
    }
}
