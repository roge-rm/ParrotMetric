package com.rm.parrotmetric.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.pow

/** What the 3D view does with the pointer, in pixels. Called on the main thread. */
interface ViewControls {
    fun orbit(dx: Float, dy: Float)
    fun pan(dx: Float, dy: Float)
    /** Above 1 moves closer. */
    fun zoom(factor: Float)
    /** Zooms towards the point under (x, y), which stays put. */
    fun zoomAt(factor: Float, x: Float, y: Float)
    fun fit()
    /** A tap with a finger: selects or unselects what's under it; double is the second of a double tap, which also fits the view. */
    fun tap(x: Float, y: Float, double: Boolean)
    /** A mouse click: selects what's under it in place of the selection, or with add, adds or removes it. */
    fun click(x: Float, y: Float, add: Boolean)
    /** A double click: like a click, but an edge brings the edges running on smoothly from it. */
    fun clickChain(x: Float, y: Float, add: Boolean) = click(x, y, add)
    /** A box dragged with the mouse: from left to right takes what's wholly inside, from right to left anything it crosses. */
    fun box(rect: Rect, crossing: Boolean, add: Boolean)
    /** A right click: the menu for the selection, at (x, y). */
    fun menu(x: Float, y: Float)
}

/**
 * What the browser says about the scroll being handled, set just before Compose sees it: a touchpad's
 * two-finger scroll and a mouse wheel look alike to Compose. A pan in view pixels, or a pinch's zoom;
 * neither for a mouse wheel.
 */
object ScrollSource {
    var pan: Offset? = null
    var zoom: Float? = null
}

/**
 * The 3D view's gestures for the desktop and browser.
 *
 * With a finger: one finger orbits, two pan and pinch to zoom, a tap selects
 * and a double tap fits the view.
 *
 * With a mouse: the middle button pans, Shift and the middle button orbits,
 * as does the right button, with Shift panning (for touchpads), and the wheel
 * zooms towards the pointer; in a browser a touchpad's two-finger scroll pans
 * and a pinch zooms; a double middle click fits the view. A click selects, Shift or
 * Ctrl adding to the selection; a left drag selects with a box; a right
 * click opens the menu. [onBox] gets the box as it's dragged, then null.
 */
fun Modifier.viewGestures(controls: ViewControls, onBox: (Rect?) -> Unit = {}): Modifier = pointerInput(controls) {
    awaitPointerEventScope {
        val down = mutableMapOf<PointerId, Offset>()
        var start = Offset.Zero
        var lastCentre = Offset.Zero
        var lastSpread = 0f
        var dragging = false
        var lastTap = 0L
        var lastClick = 0L
        var downAt = 0L
        var lastClickAt = Offset.Zero
        var lastMiddle = 0L
        // The mouse button held (1 left, 2 right, 3 middle) and what its drag does.
        var mouse = false
        var button = 0
        var adding = false
        var orbiting = false
        fun centre() = down.values.fold(Offset.Zero) { a, b -> a + b } / down.size.toFloat()
        fun spread(c: Offset) = if (down.size < 2) 0f else down.values.map { (it - c).getDistance() }.average().toFloat()
        while (true) {
            val event = awaitPointerEvent()
            when (event.type) {
                PointerEventType.Press -> {
                    val first = down.isEmpty()
                    for (c in event.changes) if (c.pressed) down[c.id] = c.position
                    val change = event.changes.first()
                    if (first) {
                        start = change.position
                        downAt = change.uptimeMillis
                        dragging = false
                        mouse = change.type == PointerType.Mouse
                        button = when {
                            event.buttons.isTertiaryPressed -> 3
                            event.buttons.isSecondaryPressed -> 2
                            else -> 1
                        }
                        adding = event.keyboardModifiers.isShiftPressed || event.keyboardModifiers.isCtrlPressed
                        // Shift swaps the two: right drag turns and Shift-right pans, for touchpads; middle pans and Shift-middle turns.
                        orbiting = (button == 2) != event.keyboardModifiers.isShiftPressed
                    } else if (!mouse) {
                        // A second finger: it's a pan and pinch from here on.
                        dragging = true
                    }
                    lastCentre = centre()
                    lastSpread = spread(lastCentre)
                }
                PointerEventType.Move -> {
                    if (down.isEmpty()) continue
                    for (c in event.changes) if (c.id in down) down[c.id] = c.position
                    val c = centre()
                    if (!dragging && (c - start).getDistance() > viewConfiguration.touchSlop) dragging = true
                    if (dragging) {
                        val d = c - lastCentre
                        when {
                            mouse && button == 1 -> onBox(Rect(start, c))
                            mouse -> if (orbiting) controls.orbit(d.x, d.y) else controls.pan(d.x, d.y)
                            down.size >= 2 -> {
                                controls.pan(d.x, d.y)
                                val s = spread(c)
                                if (lastSpread > 0f && s > 0f) controls.zoom(s / lastSpread)
                                lastSpread = s
                            }
                            else -> controls.orbit(d.x, d.y)
                        }
                    }
                    lastCentre = c
                }
                PointerEventType.Release -> {
                    val change = event.changes.first()
                    for (c in event.changes) if (!c.pressed) down.remove(c.id)
                    if (down.isEmpty()) {
                        val at = change.position
                        val now = change.uptimeMillis
                        when {
                            !mouse -> if (!dragging) {
                                if (now - downAt >= 400) {
                                    // Held: an edge and those running on smoothly from it.
                                    controls.clickChain(at.x, at.y, true)
                                } else {
                                    val double = now - lastTap < 350
                                    lastTap = if (double) 0 else now
                                    controls.tap(at.x, at.y, double)
                                }
                            }
                            button == 1 -> if (dragging) {
                                onBox(null)
                                controls.box(Rect(start, at), crossing = at.x < start.x, add = adding)
                            } else if (now - lastClick < 350 && (at - lastClickAt).getDistance() < 6f) {
                                controls.clickChain(at.x, at.y, adding)
                                lastClick = 0
                            } else {
                                controls.click(at.x, at.y, adding)
                                lastClick = now
                                lastClickAt = at
                            }
                            button == 2 -> if (!dragging) controls.menu(at.x, at.y)
                            button == 3 -> if (!dragging) {
                                if (now - lastMiddle < 350) {
                                    controls.fit()
                                    lastMiddle = 0
                                } else {
                                    lastMiddle = now
                                }
                            }
                        }
                    } else {
                        // A finger lifted: carry on from where the rest are.
                        lastCentre = centre()
                        lastSpread = spread(lastCentre)
                    }
                }
                PointerEventType.Scroll -> {
                    val change = event.changes.first()
                    ScrollSource.zoom?.let { controls.zoomAt(it, change.position.x, change.position.y); ScrollSource.zoom = null; continue }
                    ScrollSource.pan?.let { controls.pan(it.x, it.y); ScrollSource.pan = null; continue }
                    val steps = change.scrollDelta.y
                    // Browsers give bigger steps than desktops; a notch is at most three.
                    if (steps != 0f) controls.zoomAt(1.15f.pow(-steps.coerceIn(-3f, 3f)), change.position.x, change.position.y)
                }
            }
        }
    }
}

/**
 * The box being dragged to select: a solid outline from left to right (what's
 * wholly inside), dashed from right to left (anything it crosses).
 */
@Composable
fun SelectionBox(rect: Rect?) {
    if (rect == null) return
    val dp = LocalDensity.current.density
    Canvas(Modifier.fillMaxSize()) {
        val crossing = rect.right < rect.left
        val r = Rect(minOf(rect.left, rect.right), minOf(rect.top, rect.bottom), maxOf(rect.left, rect.right), maxOf(rect.top, rect.bottom))
        val colour = if (crossing) Palette.mint else Palette.sketch
        drawRect(colour.copy(alpha = 0.12f), r.topLeft, r.size)
        drawRect(
            colour, r.topLeft, r.size,
            style = Stroke(1.5f * dp, pathEffect = if (crossing) PathEffect.dashPathEffect(floatArrayOf(6 * dp, 4 * dp)) else null),
        )
    }
}
