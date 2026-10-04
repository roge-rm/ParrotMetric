package com.rm.parrotmetric.ui.sketch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.ui.Icons
import com.rm.parrotmetric.ui.Palette
import kotlinx.coroutines.delay
import kotlin.math.round

/** The top of the screen while sketching: its name and plane, undo, redo and Finish. */
@Composable
fun SketchTopBar(editor: SketchEditor, onFinish: () -> Unit) {
    editor.version
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.sketch, null, tint = Palette.sketch) }
        Column(Modifier.weight(1f)) {
            Text(editor.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1)
            Text(editor.plane.name, fontSize = 12.sp, color = Palette.muted, maxLines = 1)
        }
        IconButton(onClick = editor::undo, enabled = editor.canUndo) { Icon(Icons.undo, "Undo", tint = if (editor.canUndo) Palette.text else Palette.faint) }
        IconButton(onClick = editor::redo, enabled = editor.canRedo) { Icon(Icons.redo, "Redo", tint = if (editor.canRedo) Palette.text else Palette.faint) }
        Surface(onClick = onFinish, shape = RoundedCornerShape(22.dp), color = Palette.mint, contentColor = Palette.ink, modifier = Modifier.padding(start = 4.dp, end = 6.dp)) {
            Text("Finish", Modifier.padding(horizontal = 18.dp, vertical = 11.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** How many ways the sketch can still move, and any message, under the top bar. */
@Composable
fun SketchStatus(editor: SketchEditor, modifier: Modifier = Modifier) {
    editor.version
    val free = editor.freedom.count
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = Palette.surface.copy(alpha = 0.85f), contentColor = Palette.text, shape = RoundedCornerShape(15.dp)) {
            Row(Modifier.height(30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(if (free == 0) Palette.mint else Palette.sketch))
                Spacer(Modifier.width(7.dp))
                Text(if (free == 0) "Fully set" else "$free free", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        val message = editor.message
        AnimatedVisibility(message != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(300))) {
            Surface(color = Color(0xFF3A2C24), contentColor = Color(0xFFFFB48C), shape = RoundedCornerShape(15.dp)) {
                Text(message.orEmpty(), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp)
            }
        }
        LaunchedEffect(message) {
            if (message != null) {
                delay(2500)
                editor.message = null
            }
        }
    }
}

/** The bottom of the screen while sketching: number entry, the Constrain sheet, or the tools. */
@Composable
fun SketchBottom(editor: SketchEditor) {
    editor.version
    var constraining by remember { mutableStateOf(false) }
    val editing = editor.editing
    Column(Modifier.imePadding().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            editing != null -> DimensionEntry(editor, editing)
            constraining -> ConstrainSheet(editor) { constraining = false }
            else -> {
                ChipRow(editor) { constraining = true }
                ToolGrid(editor)
            }
        }
    }
}

@Composable
private fun ChipRow(editor: SketchEditor, onConstrain: () -> Unit) {
    val hasSelection = editor.selection.isNotEmpty()
    val choices = editor.constraintChoices()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Dimension", Icons.dimension, active = editor.tool == SketchTool.Dimension) {
            editor.selectTool(if (editor.tool == SketchTool.Dimension) SketchTool.Select else SketchTool.Dimension)
        }
        Chip("Constrain", Icons.constrain, enabled = choices.isNotEmpty(), onClick = onConstrain)
        Chip("Trim", Icons.trim, active = editor.tool == SketchTool.Trim) {
            editor.selectTool(if (editor.tool == SketchTool.Trim) SketchTool.Select else SketchTool.Trim)
        }
        Chip("Extend", Icons.extend, active = editor.tool == SketchTool.Extend) {
            editor.selectTool(if (editor.tool == SketchTool.Extend) SketchTool.Select else SketchTool.Extend)
        }
        if (editor.selectedCurves.isNotEmpty()) Chip("Offset", Icons.offset) { editor.startOffset() }
        if (editor.outline != null) Chip("Project", Icons.project) { editor.projectOutline() }
        if (editor.selectedCorner != null) Chip("Round corner", Icons.fillet) { editor.startCornerFillet() }
        Chip("Construction", Icons.construction, active = editor.construction && !hasSelection) { editor.toggleConstruction() }
        if (hasSelection) Chip("Delete", Icons.delete, tint = Palette.orange) { editor.deleteSelection() }
    }
}

@Composable
private fun Chip(label: String, icon: ImageVector, active: Boolean = false, enabled: Boolean = true, tint: Color = Palette.yellow, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(19.dp),
        color = if (active) Palette.yellow.copy(alpha = 0.18f) else Palette.surface,
        contentColor = if (enabled) Palette.text else Palette.faint,
    ) {
        Row(Modifier.height(38.dp).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (enabled) tint else Palette.faint)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
private fun ToolGrid(editor: SketchEditor) {
    val main = listOf(
        Triple(SketchTool.Select, "Select", Icons.select),
        Triple(SketchTool.Line, "Line", Icons.line),
        Triple(SketchTool.Rectangle, "Rectangle", Icons.rectangle),
        Triple(SketchTool.Circle, "Circle", Icons.circleTool),
        Triple(SketchTool.Arc, "Arc", Icons.arc),
    )
    val more = listOf(
        Triple(SketchTool.Point, "Point", Icons.pointTool),
        Triple(SketchTool.Spline, "Spline", Icons.spline),
        Triple(SketchTool.Polygon, "Polygon", Icons.polygon),
        Triple(SketchTool.Slot, "Slot", Icons.slot),
    )
    var showMore by remember { mutableStateOf(editor.tool in more.map { it.first }) }
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            if (showMore) {
                Row { ToolRow(editor, more) }
                if (editor.tool == SketchTool.Polygon) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sides", Modifier.weight(1f), fontSize = 13.sp, color = Palette.muted)
                    for (n in listOf(3, 4, 5, 6, 8, 12)) {
                        Surface(
                            onClick = { editor.polygonSides = n },
                            shape = RoundedCornerShape(12.dp),
                            color = if (editor.polygonSides == n) Palette.line else Color.Transparent,
                            contentColor = Palette.text,
                        ) { Text("$n", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp) }
                    }
                }
            }
            if (editor.tool == SketchTool.Rectangle) ChoiceRow(listOf("Corner to corner", "From the centre"), if (editor.rectangleFromCentre) 1 else 0) {
                editor.endDrawing()
                editor.rectangleFromCentre = it == 1
            }
            if (editor.tool == SketchTool.Arc) ChoiceRow(listOf("Centre, then ends", "Through three points"), if (editor.arcThroughPoints) 1 else 0) {
                editor.endDrawing()
                editor.arcThroughPoints = it == 1
            }
            Row {
                ToolRow(editor, main)
                Surface(
                    onClick = { showMore = !showMore },
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (showMore) Palette.sketch.copy(alpha = 0.16f) else Color.Transparent,
                    contentColor = Palette.text,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.more, null, Modifier.size(24.dp), tint = Palette.sketch)
                        Spacer(Modifier.height(4.dp))
                        Text("More", fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A tool's way of drawing, as small choices above the tools. */
@Composable
private fun ChoiceRow(options: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            Surface(
                onClick = { onPick(i) },
                shape = RoundedCornerShape(12.dp),
                color = if (i == chosen) Palette.line else Color.Transparent,
                contentColor = Palette.text,
            ) { Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolRow(editor: SketchEditor, tools: List<Triple<SketchTool, String, ImageVector>>) {
    run {
        run {
            for ((tool, label, icon) in tools) {
                val active = editor.tool == tool
                Surface(
                    // Tapping the tool in hand again ends what it's drawing.
                    onClick = { if (active && tool != SketchTool.Select) editor.endDrawing() else editor.selectTool(tool) },
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) Palette.sketch.copy(alpha = 0.16f) else Color.Transparent,
                    contentColor = if (active) Palette.sketch else Palette.text,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, null, Modifier.size(24.dp), tint = Palette.sketch)
                        Spacer(Modifier.height(4.dp))
                        Text(label, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DimensionEntry(editor: SketchEditor, edit: DimensionEdit) {
    val start = remember(edit) {
        val v = round(edit.initial * 100) / 100
        val text = edit.existing?.expression ?: if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()
        TextFieldValue(text, TextRange(0, text.length))
    }
    var value by remember(edit) { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(edit) { focus.requestFocus() }
    val label = edit.label
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(80.dp), fontSize = 14.sp, color = Palette.muted)
                Row(
                    Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp)).background(Palette.ground).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value,
                        onValueChange = { value = it },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        textStyle = TextStyle(color = Palette.text, fontSize = 20.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Palette.mint),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { editor.commitDimension(value.text) }),
                    )
                    Text(if (edit.isAngle) "°" else "mm", fontSize = 14.sp, color = Palette.muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = editor::cancelDimension, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(18.dp), color = Palette.raised, contentColor = Palette.text) {
                    Box(contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
                Surface(
                    onClick = { editor.commitDimension(value.text) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Palette.mint,
                    contentColor = Palette.ink,
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("Set", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun ConstrainSheet(editor: SketchEditor, close: () -> Unit) {
    val choices = editor.constraintChoices()
    if (choices.isEmpty()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Constrain", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                IconButton(onClick = close) { Icon(Icons.close, "Close", Modifier.size(16.dp), tint = Palette.muted) }
            }
            for (row in choices.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in row) {
                        Surface(
                            onClick = { editor.apply(c); close() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Palette.raised,
                            contentColor = Palette.text,
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(c.label, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
