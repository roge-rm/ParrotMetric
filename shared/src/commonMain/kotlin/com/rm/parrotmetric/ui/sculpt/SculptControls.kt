package com.rm.parrotmetric.ui.sculpt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.ui.Icons
import com.rm.parrotmetric.ui.ModelActions
import com.rm.parrotmetric.ui.Palette
import kotlin.math.pow

private val keepKeys = Modifier.focusProperties { canFocus = false }

private enum class Gesture { None, Stroke, Orbit, Pan, Fingers }

/**
 * Over the view while sculpting. On the mesh, a finger, pen or the left
 * button strokes (Shift smooths, Ctrl inverts); off it, it turns the view.
 * Two fingers move and pinch the view; the right button turns it and the
 * middle one moves it, as everywhere else, and the wheel zooms.
 */
@Composable
fun SculptOverlay(editor: SculptEditor, actions: ModelActions) {
    editor.density = LocalDensity.current.density
    Box(
        Modifier.fillMaxSize().pointerInput(editor) {
            awaitPointerEventScope {
                val down = mutableMapOf<PointerId, Offset>()
                var gesture = Gesture.None
                var last = Offset.Zero
                var lastSpread = 0f
                var strokeStart = 0L
                fun centre() = down.values.fold(Offset.Zero) { a, b -> a + b } / down.size.toFloat()
                fun spread(c: Offset) = if (down.size < 2) 0f else down.values.map { (it - c).getDistance() }.average().toFloat()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    val pen = change.type == PointerType.Stylus || change.type == PointerType.Eraser
                    val pressure = if (pen) change.pressure.coerceIn(0.05f, 1f) else 1f
                    when (event.type) {
                        PointerEventType.Press -> {
                            val first = down.isEmpty()
                            for (c in event.changes) if (c.pressed) down[c.id] = c.position
                            if (first) {
                                val mouse = change.type == PointerType.Mouse
                                val shift = event.keyboardModifiers.isShiftPressed
                                gesture = when {
                                    mouse && event.buttons.isSecondaryPressed -> if (shift) Gesture.Pan else Gesture.Orbit
                                    mouse && event.buttons.isTertiaryPressed -> if (shift) Gesture.Orbit else Gesture.Pan
                                    mouse && !event.buttons.isPrimaryPressed -> Gesture.None
                                    editor.begin(
                                        change.position.x, change.position.y, pressure, smooth = shift,
                                        // The pen's eraser end does the opposite, as Ctrl does.
                                        inverted = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed || change.type == PointerType.Eraser,
                                    ) -> Gesture.Stroke
                                    else -> Gesture.Orbit
                                }
                                if (gesture == Gesture.Stroke) {
                                    strokeStart = change.uptimeMillis
                                    editor.cursor = change.position
                                }
                            } else if (change.type != PointerType.Mouse) {
                                // A second finger: what was just begun was the start of a pinch.
                                if (gesture == Gesture.Stroke) {
                                    editor.end()
                                    if (change.uptimeMillis - strokeStart < 300) editor.undo()
                                    editor.cursor = null
                                }
                                gesture = Gesture.Fingers
                            }
                            last = centre()
                            lastSpread = spread(last)
                            change.consume()
                        }
                        PointerEventType.Move -> {
                            if (down.isEmpty()) {
                                // The mouse going over: the ring follows it.
                                if (change.type == PointerType.Mouse || pen) editor.cursor = change.position
                                continue
                            }
                            for (c in event.changes) if (c.id in down) down[c.id] = c.position
                            val c = centre()
                            val d = c - last
                            when (gesture) {
                                Gesture.Stroke -> {
                                    editor.move(change.position.x, change.position.y, pressure)
                                    editor.cursor = change.position
                                }
                                Gesture.Orbit -> actions.orbit(d.x, d.y)
                                Gesture.Pan -> actions.pan(d.x, d.y)
                                Gesture.Fingers -> {
                                    actions.pan(d.x, d.y)
                                    val s = spread(c)
                                    if (lastSpread > 0f && s > 0f) actions.zoom(s / lastSpread)
                                    lastSpread = s
                                }
                                Gesture.None -> {}
                            }
                            last = c
                            change.consume()
                        }
                        PointerEventType.Release -> {
                            for (c in event.changes) if (!c.pressed) down.remove(c.id)
                            if (down.isEmpty()) {
                                // Out to where it was let go, in case moves on the way were run together.
                                if (gesture == Gesture.Stroke) {
                                    editor.move(change.position.x, change.position.y, pressure)
                                    editor.end()
                                }
                                gesture = Gesture.None
                                if (change.type != PointerType.Mouse && !pen) editor.cursor = null
                            } else {
                                last = centre()
                                lastSpread = spread(last)
                            }
                        }
                        PointerEventType.Scroll -> {
                            val steps = change.scrollDelta.y
                            if (steps != 0f) actions.zoomAt(1.15f.pow(-steps.coerceIn(-3f, 3f)), change.position.x, change.position.y)
                        }
                        PointerEventType.Exit -> if (down.isEmpty()) editor.cursor = null
                    }
                }
            }
        },
    ) {
        val at = editor.brushAt ?: editor.cursor
        if (at != null) {
            val radius = editor.size * LocalDensity.current.density
            val colour = when {
                editor.brush == SculptBrush.Mask -> Palette.inspect
                editor.invert -> Palette.sketch
                else -> Palette.text
            }
            val pointer = editor.cursor
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(colour.copy(alpha = 0.85f), radius, at, style = Stroke(1.5f * density))
                drawCircle(colour.copy(alpha = 0.35f), radius * 0.08f + 1.5f * density, at)
                // The string to the pointer, while the brush trails it.
                if (pointer != null && pointer != at && editor.brushAt != null) drawLine(colour.copy(alpha = 0.4f), at, pointer, 1f * density)
            }
        }
    }
}

/** Above the view: what's being sculpted, undo and redo, and leaving with or without the changes. */
@Composable
fun SculptTopBar(editor: SculptEditor, onFit: () -> Unit, onFinish: (Boolean) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (editor.canUndo) asking = true else onFinish(false) }, keepKeys) {
            Icon(Icons.close, "Leave without keeping it", tint = Palette.muted)
        }
        Column(Modifier.weight(1f)) {
            Text("Sculpt", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1)
            Text(if (editor.working) "Working…" else "${editor.triangles} triangles", fontSize = 12.sp, color = Palette.muted, maxLines = 1)
        }
        IconButton(onClick = onFit, keepKeys) { Icon(Icons.fit, "Fit in view", tint = Palette.text) }
        IconButton(onClick = editor::undo, keepKeys, enabled = editor.canUndo) { Icon(Icons.undo, "Undo", tint = if (editor.canUndo) Palette.text else Palette.faint) }
        IconButton(onClick = editor::redo, keepKeys, enabled = editor.canRedo) { Icon(Icons.redo, "Redo", tint = if (editor.canRedo) Palette.text else Palette.faint) }
        Surface(onClick = { onFinish(true) }, shape = RoundedCornerShape(22.dp), color = Palette.mint, contentColor = Palette.ink, modifier = keepKeys.padding(start = 4.dp, end = 6.dp)) {
            Text("Done", Modifier.padding(horizontal = 18.dp, vertical = 11.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Leave without keeping it?") },
        text = { Text("What was sculpted this time goes.") },
        confirmButton = { TextButton(onClick = { asking = false; onFinish(false) }) { Text("Leave", color = Palette.orange) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep sculpting", color = Palette.mint) } },
        containerColor = Palette.raised,
    )
}

/** Below the view: the brush's size and strength, its options, and the brushes. */
@Composable
fun SculptBottom(editor: SculptEditor, wide: Boolean) {
    Surface(color = Palette.surface, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LabelledSlider("Size", "${editor.size.toInt()}", (editor.size / 300f).coerceIn(0f, 1f).let { kotlin.math.sqrt(it) }, Modifier.weight(1f)) {
                    // Squared, so small sizes get more of the slider.
                    editor.size = (it * it * 300f).coerceIn(4f, 300f)
                    editor.keep()
                }
                LabelledSlider("Strength", "${(editor.strength * 100).toInt()}", editor.strength, Modifier.weight(1f)) {
                    editor.strength = it
                    editor.keep()
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (editor.brush == SculptBrush.Mask) "Rub off" else "Invert", editor.invert) { editor.invert = !editor.invert }
                Text("Mirror", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(start = 6.dp))
                for ((label, bit) in listOf("X" to 1, "Y" to 2, "Z" to 4)) Chip(label, editor.mirror and bit != 0) { editor.toggleMirror(bit) }
                Spacer(Modifier.width(6.dp))
                Chip("Detail", editor.dynamic) { editor.dynamic = !editor.dynamic; editor.keep() }
                MoreOptions(editor)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (b in SculptBrush.entries) BrushButton(b, editor.brush == b, wide) {
                    editor.brush = b
                    editor.keep()
                }
            }
        }
    }
}

@Composable
private fun LabelledSlider(label: String, value: String, fraction: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(62.dp)) {
            Text(label, fontSize = 12.sp, color = Palette.muted)
            Text(value, fontSize = 14.sp, color = Palette.text, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = fraction, onValueChange = onChange, modifier = keepKeys.weight(1f).height(36.dp),
            colors = SliderDefaults.colors(thumbColor = Palette.mint, activeTrackColor = Palette.mint, inactiveTrackColor = Palette.line),
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = keepKeys, shape = RoundedCornerShape(12.dp), color = if (on) Palette.line else Palette.ground, contentColor = if (on) Palette.text else Palette.muted) {
        Text(label, Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun BrushButton(b: SculptBrush, on: Boolean, wide: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = keepKeys.widthIn(min = if (wide) 72.dp else 58.dp), shape = RoundedCornerShape(14.dp),
        color = if (on) Palette.raised else androidx.compose.ui.graphics.Color.Transparent, contentColor = if (on) Palette.mint else Palette.text,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(b.icon, b.label, Modifier.size(24.dp))
            Text(b.label, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** The settings used less: how fine the detail is, the pen, and the mask and triangles as a whole. */
@Composable
private fun MoreOptions(editor: SculptEditor) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("More", open) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Palette.raised) {
            Column(Modifier.width(280.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Detail", fontSize = 12.sp, color = Palette.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Coarse", fontSize = 12.sp, color = Palette.muted)
                    Slider(
                        value = editor.detail, onValueChange = { editor.detail = it; editor.keep() }, modifier = Modifier.weight(1f),
                        enabled = editor.dynamic,
                        colors = SliderDefaults.colors(thumbColor = Palette.mint, activeTrackColor = Palette.mint, inactiveTrackColor = Palette.line),
                    )
                    Text("Fine", fontSize = 12.sp, color = Palette.muted)
                }
                Text("Steady lines", fontSize = 12.sp, color = Palette.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Off", fontSize = 12.sp, color = Palette.muted)
                    Slider(
                        value = editor.steady, onValueChange = { editor.steady = it; editor.keep() }, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Palette.mint, activeTrackColor = Palette.mint, inactiveTrackColor = Palette.line),
                    )
                    Text("Most", fontSize = 12.sp, color = Palette.muted)
                }
                Toggle("Pen pressure changes strength", editor.pressureStrength) { editor.pressureStrength = it; editor.keep() }
                Toggle("Pen pressure changes size", editor.pressureSize) { editor.pressureSize = it; editor.keep() }
                Text("Mask", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Clear", false) { editor.clearMask(); open = false }
                    Chip("Invert", false) { editor.invertMask(); open = false }
                }
                Text("Triangles", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Each step about doubles or halves how many there are.
                    Chip("Finer all over", false) { editor.evenOut(0.7); open = false }
                    Chip("Even", false) { editor.evenOut(); open = false }
                    Chip("Coarser", false) { editor.evenOut(1.4); open = false }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = Palette.text)
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.mint, checkedThumbColor = Palette.ink))
    }
}
