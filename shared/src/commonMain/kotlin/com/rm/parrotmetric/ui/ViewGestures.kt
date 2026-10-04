package com.rm.parrotmetric.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.pow

/** What the 3D view does with the pointer, in pixels. Called on the main thread. */
interface ViewControls {
    fun orbit(dx: Float, dy: Float)
    fun pan(dx: Float, dy: Float)
    /** Above 1 moves closer. */
    fun zoom(factor: Float)
    /** A tap or click; double is the second of a double tap, which also fits the view. */
    fun tap(x: Float, y: Float, double: Boolean)
}

/**
 * The 3D view's gestures for the desktop and browser, with a mouse or a touch
 * screen. One finger or the left button orbits; two fingers, the right or
 * middle button, or Shift with the left pans; pinching or the wheel zooms. A
 * tap or click selects, and a double tap or click fits the view.
 */
fun Modifier.viewGestures(controls: ViewControls): Modifier = pointerInput(controls) {
    awaitPointerEventScope {
        val down = mutableMapOf<PointerId, Offset>()
        var start = Offset.Zero
        var lastCentre = Offset.Zero
        var lastSpread = 0f
        var dragging = false
        var panning = false
        var clicked = false
        var lastTap = 0L
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
                        dragging = false
                        val mouse = change.type == PointerType.Mouse
                        clicked = !mouse || event.buttons.isPrimaryPressed
                        panning = mouse && (!event.buttons.isPrimaryPressed || event.keyboardModifiers.isShiftPressed)
                    } else {
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
                        if (down.size >= 2) {
                            controls.pan(d.x, d.y)
                            val s = spread(c)
                            if (lastSpread > 0f && s > 0f) controls.zoom(s / lastSpread)
                            lastSpread = s
                        } else if (panning) {
                            controls.pan(d.x, d.y)
                        } else {
                            controls.orbit(d.x, d.y)
                        }
                    }
                    lastCentre = c
                }
                PointerEventType.Release -> {
                    val change = event.changes.first()
                    for (c in event.changes) if (!c.pressed) down.remove(c.id)
                    if (down.isEmpty()) {
                        if (!dragging && clicked) {
                            val now = change.uptimeMillis
                            val double = now - lastTap < 350
                            lastTap = if (double) 0 else now
                            controls.tap(change.position.x, change.position.y, double)
                        }
                    } else {
                        // A finger lifted: carry on from where the rest are.
                        lastCentre = centre()
                        lastSpread = spread(lastCentre)
                    }
                }
                PointerEventType.Scroll -> {
                    val steps = event.changes.first().scrollDelta.y
                    // Browsers give bigger steps than desktops; a notch is at most three.
                    if (steps != 0f) controls.zoom(1.15f.pow(-steps.coerceIn(-3f, 3f)))
                }
            }
        }
    }
}
