package com.rm.parrotmetric.ui.sculpt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import com.rm.parrotmetric.app.NativeCore
import com.rm.parrotmetric.ui.Icons
import kotlinx.coroutines.launch

/** The brushes, in the core's order (pm::Brush). [key] is the shortcut. */
enum class SculptBrush(val label: String, val icon: ImageVector, val key: String, val strength: Float) {
    Draw("Draw", Icons.brushDraw, "D", 0.5f),
    Clay("Clay", Icons.brushClay, "C", 0.5f),
    Crease("Crease", Icons.brushCrease, "Shift+C", 0.5f),
    Smooth("Smooth", Icons.brushSmooth, "S", 0.6f),
    Flatten("Flatten", Icons.brushFlatten, "F", 0.5f),
    Inflate("Inflate", Icons.brushInflate, "I", 0.4f),
    Pinch("Pinch", Icons.brushPinch, "P", 0.5f),
    Grab("Grab", Icons.brushGrab, "G", 1f),
    Pull("Pull", Icons.brushPull, "Shift+G", 1f),
    Layer("Layer", Icons.brushLayer, "L", 0.5f),
    Mask("Mask", Icons.brushMask, "M", 0.7f),
}

/**
 * A sculpting session: the brush and its settings, and strokes passed to the
 * core, which holds the mesh and draws it. Strokes are in view pixels.
 * [editing] is the Sculpt step being changed, if it's one already made, and
 * [body] the body it takes the place of, if any.
 */
class SculptEditor(
    private val core: NativeCore,
    /** Asks for a new frame, with any GL work. */
    private val redraw: (() -> Unit) -> Unit,
    /** For changes to the whole mesh, which take a while on a big one. */
    private val scope: kotlinx.coroutines.CoroutineScope,
    val editing: Int?,
    val body: String?,
) {
    var brush by mutableStateOf(remembered.brush)
    /** The brush's size across the screen in dp, the same for every brush. */
    var size by mutableFloatStateOf(remembered.size)
    private val strengths = mutableStateMapOf<SculptBrush, Float>().apply { putAll(remembered.strengths) }
    var strength: Float
        get() = strengths[brush] ?: brush.strength
        set(v) { strengths[brush] = v.coerceIn(0.02f, 1f) }
    /** Push in instead of out, rub the mask off, and so on. */
    var invert by mutableStateOf(false)
    /** Bits: 1 across x, 2 across y, 4 across z. */
    var mirror by mutableIntStateOf(remembered.mirror)
    /** Triangles split and joined under the brush to keep the detail even. */
    var dynamic by mutableStateOf(remembered.dynamic)
    /** 0 coarse to 1 fine. */
    var detail by mutableFloatStateOf(remembered.detail)
    var pressureSize by mutableStateOf(remembered.pressureSize)
    var pressureStrength by mutableStateOf(remembered.pressureStrength)

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var triangles by mutableIntStateOf(0)
        private set
    /** How long the triangles' edges are on average, mm. */
    private var averageEdge = 1.0
    /** Where the brush is, to draw its ring; null when the pointer is away. */
    var cursor by mutableStateOf<Offset?>(null)
    var stroking by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    /** View pixels per dp, which the brush's size is in. */
    var density = 1f

    /** Whether (x, y) is over the mesh, so a press there strokes rather than turning the view. */
    fun over(x: Float, y: Float): Boolean = core.sculptHit(x, y)

    /** Starts a stroke; [smooth] and [inverted] are held keys. False if it's off the mesh. */
    fun begin(x: Float, y: Float, pressure: Float, smooth: Boolean = false, inverted: Boolean = false): Boolean {
        if (working) return false
        val b = if (smooth) SculptBrush.Smooth else brush
        val s = strengths[b] ?: b.strength
        val ok = core.sculptBegin(
            x, y, pressure.coerceIn(0.05f, 1f), b.ordinal, size * density, s, invert != inverted, mirror, dynamic, detail, pressureSize, pressureStrength,
        )
        stroking = ok
        if (ok) redraw {}
        return ok
    }

    fun move(x: Float, y: Float, pressure: Float) {
        if (!stroking) return
        core.sculptMove(x, y, pressure.coerceIn(0.05f, 1f))
        redraw {}
    }

    fun end() {
        if (!stroking) return
        stroking = false
        core.sculptEnd()
        refresh()
        redraw {}
    }

    fun undo() { if (core.sculptUndo(false)) { refresh(); redraw {} } }
    fun redo() { if (core.sculptUndo(true)) { refresh(); redraw {} } }

    fun clearMask() = whole(0, 0.0)
    fun invertMask() = whole(1, 0.0)
    /** Even triangles all over: at the size they are on average, or [scale] times it (below 1 finer). */
    fun evenOut(scale: Double = 1.0) = whole(2, averageEdge * scale)

    /** True while a change to the whole mesh is being made. */
    var working by mutableStateOf(false)
        private set

    private fun whole(what: Int, edge: Double) {
        if (working || stroking) return
        working = true
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { core.sculptChange(what, edge) }
            working = false
            refresh()
            redraw {}
        }
    }

    fun refresh() {
        val info = core.sculptInfo()
        canUndo = info[0] > 0
        canRedo = info[1] > 0
        triangles = info[2].toInt()
        averageEdge = info[3]
        keep()
    }

    /** Bigger or smaller by a step, as [ and ] do. */
    fun resize(bigger: Boolean) {
        size = (if (bigger) size * 1.2f else size / 1.2f).coerceIn(4f, 400f)
        keep()
    }

    fun toggleMirror(bit: Int) {
        mirror = mirror xor bit
        keep()
    }

    /** The settings carry on to the next session. */
    fun keep() {
        remembered = Settings(brush, size, strengths.toMap(), mirror, dynamic, detail, pressureSize, pressureStrength)
    }

    private class Settings(
        val brush: SculptBrush = SculptBrush.Draw,
        val size: Float = 50f,
        val strengths: Map<SculptBrush, Float> = emptyMap(),
        val mirror: Int = 1,
        val dynamic: Boolean = true,
        val detail: Float = 0.5f,
        val pressureSize: Boolean = false,
        val pressureStrength: Boolean = true,
    )

    companion object {
        private var remembered = Settings()
    }
}
