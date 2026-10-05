package com.rm.parrotmetric.ui.sketch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.PlainTooltip
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.rm.parrotmetric.ui.sideways
import com.rm.parrotmetric.ui.Palette
import kotlinx.coroutines.delay
import kotlin.math.round

/** The top of the screen while sketching: its name and plane, fit, undo, redo and Finish. */

/** Buttons that leave the keys with the sketch, so a typed number or Enter still goes to it. */
private val keepKeys = Modifier.focusProperties { canFocus = false }
@Composable
fun SketchTopBar(editor: SketchEditor, onFit: () -> Unit, onFinish: () -> Unit) {
    editor.version
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.sketch, null, tint = Palette.sketch) }
        Column(Modifier.weight(1f)) {
            Text(editor.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1)
            Text(editor.plane.name, fontSize = 12.sp, color = Palette.muted, maxLines = 1)
        }
        // These leave the keys with the sketch, so its letter keys still work after a click.
        IconButton(onClick = onFit, keepKeys) { Icon(Icons.fit, "Fit the sketch in view", tint = Palette.text) }
        IconButton(onClick = editor::undo, keepKeys, enabled = editor.canUndo) { Icon(Icons.undo, "Undo", tint = if (editor.canUndo) Palette.text else Palette.faint) }
        IconButton(onClick = editor::redo, keepKeys, enabled = editor.canRedo) { Icon(Icons.redo, "Redo", tint = if (editor.canRedo) Palette.text else Palette.faint) }
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
        // An empty sketch has nothing to set yet.
        if (editor.sketch.curves.isNotEmpty() || editor.sketch.points.size > 1) Surface(color = Palette.surface.copy(alpha = 0.85f), contentColor = Palette.text, shape = RoundedCornerShape(15.dp)) {
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
                delay(4000)
                editor.message = null
            }
        }
    }
}

/**
 * The bottom of the screen while sketching: number entry, or what can be
 * done and the tools. [expanded] (large screens) shows every tool in one row.
 */
@Composable
fun SketchBottom(editor: SketchEditor, large: Boolean = false) {
    editor.version
    // A phone on its side has width to spare and little height: every tool in one row, as on a large screen.
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val expanded = large || window.width > window.height
    val editing = editor.editing
    Column(Modifier.imePadding().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            editing != null -> DimensionEntry(editor, editing)
            editor.transform != null -> TransformEntry(editor, editor.transform!!)
            editor.textEdit != null -> TextEntry(editor, editor.textEdit!!)
            else -> {
                val placed = editor.placed
                if (placed != null) PlacedSizes(editor, placed) else ActionBar(editor)
                ToolGrid(editor, expanded)
            }
        }
    }
}

/** An action in a group's menu; a null [run] shows it greyed out, as it needs something picked. */
private class SketchAction(val label: String, val icon: ImageVector, val run: (() -> Unit)?, val active: Boolean = false)

/**
 * What can be done to the sketch, as one row of icons: single actions, and
 * groups that open their related actions above them. Names show on hover or
 * a long press.
 */
@Composable
private fun ActionBar(editor: SketchEditor) {
    val hasSelection = editor.selection.isNotEmpty()
    val curves = editor.selectedCurves
    fun tool(label: String, icon: ImageVector, t: SketchTool) =
        SketchAction(label, icon, { editor.selectTool(if (editor.tool == t) SketchTool.Select else t) }, editor.tool == t)
    fun withCurves(label: String, icon: ImageVector, run: () -> Unit) = SketchAction(label, icon, if (curves.isNotEmpty()) run else null)
    val corners = editor.selectedCorner != null
    val change = listOfNotNull(
        tool("Trim", Icons.trim, SketchTool.Trim),
        tool("Extend", Icons.extend, SketchTool.Extend),
        tool("Break", Icons.breakTool, SketchTool.Break),
        withCurves("Offset", Icons.offset) { editor.startOffset() },
        SketchAction("Round corner", Icons.fillet, if (corners) ({ editor.startCornerFillet() }) else null),
        SketchAction("Cut corner", Icons.chamfer, if (corners) ({ editor.startCornerChamfer() }) else null),
        editor.selectedConic?.let { c -> SketchAction("Fullness ${kotlin.math.round(c.rho * 100) / 100}", Icons.conic, { editor.startConicFullness() }) },
        (editor.selection.singleOrNull() as? SketchItem.T)?.let { t -> SketchAction("Change text", Icons.text, { editor.editText(t.text) }) },
    )
    val mirror = curves.size >= 2 && curves.any { it is com.rm.parrotmetric.sketch.Line }
    val copy = listOf(
        withCurves("Move", Icons.move) { editor.startTransform(SketchTransform.Kind.Move) },
        withCurves("Scale", Icons.scale) { editor.startTransform(SketchTransform.Kind.Scale) },
        SketchAction("Mirror", Icons.mirror, if (mirror) ({ editor.mirrorSelection() }) else null),
        withCurves("Pattern", Icons.pattern) { editor.startTransform(SketchTransform.Kind.Row) },
        withCurves("Pattern round", Icons.patternRound) { editor.startTransform(SketchTransform.Kind.Round) },
    )
    val bring = buildList {
        if (editor.outline != null) add(SketchAction("Project", Icons.project, { editor.projectOutline() }))
        for ((label, name) in editor.bodies()) add(SketchAction("Outline of $name", Icons.project, { editor.projectBody(label) }))
        if (editor.canAddDrawing) add(SketchAction("Add drawing", Icons.open, { editor.addDrawing() }))
    }
    val constrain = editor.constraintChoices().map { c -> SketchAction(c.label, Icons.constrain, { editor.apply(c) }) }
    val dimension = tool("Dimension", Icons.dimension, SketchTool.Dimension)
    val construction = SketchAction("Construction", Icons.construction, { editor.toggleConstruction() }, editor.construction && !hasSelection)
    val delete = SketchAction("Delete", Icons.delete, if (hasSelection) ({ editor.deleteSelection() }) else null)
    Surface(color = Palette.surface, shape = RoundedCornerShape(22.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            // Spread out as far as the width allows: every action named, then every action as an
            // icon, then groups that open above. Constrain and Bring in stay groups, as their lists change.
            val flat = listOf(dimension) + change + copy + listOf(construction, delete)
            // Roughly how wide each layout is: a name about 7 dp a letter, an icon button 40, a group's 52.
            val named = flat.sumOf { 46 + it.label.length * 7 } + 2 * 110
            val icons = flat.size * 42 + 2 * 54
            val width = maxWidth.value
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                when {
                    width >= named -> {
                        BarButton(dimension, showName = true)
                        BarGroup("Constrain", Icons.constrain, constrain, showName = true)
                        for (a in change + copy) BarButton(a, showName = true)
                        BarGroup("Bring in", Icons.project, bring, showName = true)
                        BarButton(construction, showName = true)
                        BarButton(delete, showName = true, tint = Palette.orange)
                    }
                    width >= icons -> {
                        BarButton(dimension)
                        BarGroup("Constrain", Icons.constrain, constrain)
                        for (a in change + copy) BarButton(a)
                        BarGroup("Bring in", Icons.project, bring)
                        BarButton(construction)
                        BarButton(delete, tint = Palette.orange)
                    }
                    else -> {
                        BarButton(dimension)
                        BarGroup("Constrain", Icons.constrain, constrain)
                        BarGroup("Change", Icons.trim, change)
                        BarGroup("Copy and move", Icons.pattern, copy)
                        BarGroup("Bring in", Icons.project, bring)
                        BarButton(construction)
                        BarButton(delete, tint = Palette.orange)
                    }
                }
            }
        }
    }
}

/** An icon with its name in a tooltip on hover or a long press. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Named(label: String, content: @Composable () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) { content() }
}

/** An action as a button: its icon, and its name beside it when there's room. */
@Composable
private fun BarButton(a: SketchAction, showName: Boolean = false, tint: Color = Palette.yellow) {
    val enabled = a.run != null
    Named(a.label) {
        Surface(
            onClick = { a.run?.invoke() },
            enabled = enabled,
            modifier = keepKeys.height(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (a.active) Palette.yellow.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (enabled) Palette.text else Palette.faint,
        ) {
            Row(Modifier.padding(horizontal = if (showName) 10.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(a.icon, a.label, Modifier.size(22.dp), tint = if (enabled) tint else Palette.faint)
                if (showName) {
                    Spacer(Modifier.width(6.dp))
                    Text(a.label, fontSize = 13.sp, fontWeight = if (a.active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** A group: its icon, and name when there's room, opens its actions above it. Greyed out when none can be used now. */
@Composable
private fun BarGroup(label: String, icon: ImageVector, actions: List<SketchAction>, showName: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val enabled = actions.any { it.run != null }
    val active = actions.any { it.active }
    Box {
        Named(label) {
            Surface(
                onClick = { open = true },
                enabled = enabled,
                modifier = keepKeys.height(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (active || open) Palette.yellow.copy(alpha = 0.18f) else Color.Transparent,
                contentColor = if (enabled) Palette.text else Palette.faint,
            ) {
                Row(Modifier.padding(horizontal = if (showName) 10.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, label, Modifier.size(22.dp), tint = if (enabled) Palette.yellow else Palette.faint)
                    if (showName) {
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    // A group opens a menu: a small mark says so.
                    Icon(Icons.more, null, Modifier.padding(start = 2.dp).size(10.dp), tint = if (enabled) Palette.muted else Palette.faint)
                }
            }
        }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Palette.raised) {
            for (a in actions) {
                androidx.compose.material3.DropdownMenuItem(
                    { Text(a.label, fontWeight = if (a.active) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; a.run?.invoke() },
                    enabled = a.run != null,
                    leadingIcon = { Icon(a.icon, null, Modifier.size(18.dp), tint = if (a.run != null) Palette.yellow else Palette.faint) },
                )
            }
        }
    }
}

@Composable
private fun ToolGrid(editor: SketchEditor, expanded: Boolean) {
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
        Triple(SketchTool.Ellipse, "Ellipse", Icons.ellipse),
        Triple(SketchTool.Conic, "Conic", Icons.conic),
        Triple(SketchTool.Text, "Text", Icons.text),
    )
    var showMore by remember { mutableStateOf(editor.tool in more.map { it.first }) }
    if (expanded) showMore = false
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            if (showMore) {
                Row { ToolRow(editor, more) }
                if (editor.tool == SketchTool.Polygon) PolygonSides(editor)
            }
            StyleRow(editor)
            if (expanded && editor.tool == SketchTool.Polygon) PolygonSides(editor)
            if (expanded) Row { ToolRow(editor, main + more) } else Row {
                ToolRow(editor, main)
                Surface(
                    onClick = { showMore = !showMore },
                    modifier = keepKeys.weight(1f).height(64.dp),
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

/** How the tool in hand draws, as choices above the tools. Changing it drops a half-drawn shape. */
@Composable
private fun StyleRow(editor: SketchEditor) {
    when (editor.tool) {
        SketchTool.Rectangle -> ChoiceRow(RectangleStyle.entries.map { it.label }, editor.rectangleStyle.ordinal) {
            editor.endDrawing(); editor.rectangleStyle = RectangleStyle.entries[it]
        }
        SketchTool.Circle -> ChoiceRow(CircleStyle.entries.map { it.label }, editor.circleStyle.ordinal) {
            editor.endDrawing(); editor.circleStyle = CircleStyle.entries[it]
        }
        SketchTool.Arc -> ChoiceRow(ArcStyle.entries.map { it.label }, editor.arcStyle.ordinal) {
            editor.endDrawing(); editor.arcStyle = ArcStyle.entries[it]
        }
        SketchTool.Polygon -> ChoiceRow(PolygonStyle.entries.map { it.label }, editor.polygonStyle.ordinal) {
            editor.endDrawing(); editor.polygonStyle = PolygonStyle.entries[it]
        }
        SketchTool.Slot -> ChoiceRow(SlotStyle.entries.map { it.label }, editor.slotStyle.ordinal) {
            editor.endDrawing(); editor.slotStyle = SlotStyle.entries[it]
        }
        SketchTool.Spline -> ChoiceRow(SplineStyle.entries.map { it.label }, editor.splineStyle.ordinal) {
            editor.endDrawing(); editor.splineStyle = SplineStyle.entries[it]
        }
        else -> {}
    }
}

@Composable
private fun PolygonSides(editor: SketchEditor) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Sides", Modifier.weight(1f), fontSize = 13.sp, color = Palette.muted)
        for (n in listOf(3, 4, 5, 6, 8, 12)) {
            Surface(
                onClick = { editor.polygonSides = n },
                modifier = keepKeys,
                shape = RoundedCornerShape(12.dp),
                color = if (editor.polygonSides == n) Palette.line else Color.Transparent,
                contentColor = Palette.text,
            ) { Text("$n", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp) }
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
                modifier = keepKeys,
                shape = RoundedCornerShape(12.dp),
                color = if (i == chosen) Palette.line else Color.Transparent,
                contentColor = Palette.text,
            ) { Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp) }
        }
    }
}

/** The drawing tools' keys, as the sketch shortcuts have them. */
private val toolKeys = mapOf(
    SketchTool.Text to "Shift+T",
    SketchTool.Line to "L", SketchTool.Rectangle to "R", SketchTool.Circle to "C", SketchTool.Arc to "A",
    SketchTool.Point to "Shift+P", SketchTool.Spline to "Shift+S", SketchTool.Polygon to "G", SketchTool.Slot to "Shift+L",
    SketchTool.Ellipse to "Shift+C",
)

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolRow(editor: SketchEditor, tools: List<Triple<SketchTool, String, ImageVector>>) {
    run {
        run {
            for ((tool, label, icon) in tools) {
                val active = editor.tool == tool
                Surface(
                    // Tapping the tool in hand again ends what it's drawing.
                    onClick = { if (active && tool != SketchTool.Select) editor.endDrawing() else editor.selectTool(tool) },
                    modifier = keepKeys.weight(1f).height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) Palette.sketch.copy(alpha = 0.16f) else Color.Transparent,
                    contentColor = if (active) Palette.sketch else Palette.text,
                ) {
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(icon, null, Modifier.size(24.dp), tint = Palette.sketch)
                            Spacer(Modifier.height(4.dp))
                            Text(label, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                        }
                        // A narrow tool has no room for its key beside the icon.
                        if (com.rm.parrotmetric.ui.LocalKeyboard.current && maxWidth >= 72.dp) toolKeys[tool]?.let {
                            com.rm.parrotmetric.ui.KeyBadge(it, Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The sizes of the shape just placed, as drawn. Set makes them dimensions;
 * drawing on, or another tool, leaves the shape free. With a keyboard the
 * first size takes the typing straight away.
 */
@Composable
private fun PlacedSizes(editor: SketchEditor, sizes: List<PlacedSize>) {
    val values = remember(sizes) {
        sizes.map { size ->
            // A drawn size is rough, so one decimal is plenty and leaves room on a phone.
            val v = round(size.initial * 10) / 10
            val text = if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()
            mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
        }
    }
    val first = remember { FocusRequester() }
    val keyboard = com.rm.parrotmetric.ui.LocalKeyboard.current
    LaunchedEffect(sizes) { if (keyboard) first.requestFocus() }
    val set = { editor.setPlaced(values.map { it.value.text }) }
    // Typing replaces the drawn size: the number is selected just after a tap puts the cursor in it.
    var focused by remember(sizes) { mutableStateOf(-1) }
    LaunchedEffect(focused) {
        val i = focused
        if (i < 0) return@LaunchedEffect
        delay(60)
        values[i].value.let { values[i].value = it.copy(selection = TextRange(0, it.text.length)) }
    }
    Surface(color = Palette.surface, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            sizes.forEachIndexed { i, size ->
                Text(size.label, fontSize = 13.sp, color = Palette.muted)
                Spacer(Modifier.width(6.dp))
                Row(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.ground).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        values[i].value,
                        onValueChange = { values[i].value = it },
                        modifier = Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { f -> if (f.isFocused) focused = i else if (focused == i) focused = -1 },
                        textStyle = TextStyle(color = Palette.text, fontSize = 17.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Palette.mint),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false,
                            imeAction = if (i == sizes.lastIndex) ImeAction.Done else ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onDone = { set() }),
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Surface(onClick = { set() }, shape = RoundedCornerShape(16.dp), color = Palette.mint, contentColor = Palette.ink) {
                Text("Set", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = editor::dropPlaced) { Icon(Icons.close, "Leave it free", tint = Palette.muted) }
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

/** Typing text: what it says, how tall its capitals are, bold, and its angle. */
@Composable
private fun TextEntry(editor: SketchEditor, edit: TextEdit) {
    val old = edit.existing
    var text by remember(edit) { mutableStateOf(TextFieldValue(old?.text ?: "", TextRange(0, old?.text?.length ?: 0))) }
    var height by remember(edit) { mutableStateOf(old?.height ?: 10.0) }
    var bold by remember(edit) { mutableStateOf(old?.bold ?: false) }
    var degrees by remember(edit) { mutableStateOf((old?.angle ?: 0.0) * 180 / kotlin.math.PI) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(edit) { focus.requestFocus() }
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicTextField(
                text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.ground)
                    .padding(horizontal = 14.dp, vertical = 12.dp).focusRequester(focus),
                textStyle = TextStyle(color = Palette.text, fontSize = 20.sp),
                cursorBrush = SolidColor(Palette.mint),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editor.commitText(text.text, height, bold, degrees) }),
                decorationBox = { inner -> if (text.text.isEmpty()) Text("Text", color = Palette.faint, fontSize = 20.sp); inner() },
            )
            val set = { editor.commitText(text.text, height, bold, degrees) }
            com.rm.parrotmetric.ui.design.NumberRow("Height", height, "mm", allowNegative = false, onDone = { set() }) { height = it }
            com.rm.parrotmetric.ui.design.NumberRow("Angle", degrees, "°", allowNegative = true, onDone = { set() }) { degrees = it }
            com.rm.parrotmetric.ui.design.Toggle("Bold", bold) { bold = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = editor::cancelText, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(18.dp), color = Palette.raised, contentColor = Palette.text) {
                    Box(contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
                Surface(
                    onClick = { editor.commitText(text.text, height, bold, degrees) },
                    modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(18.dp), color = Palette.mint, contentColor = Palette.ink,
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("Set", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun TransformEntry(editor: SketchEditor, t: SketchTransform) {
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t.kind.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            when (t.kind) {
                SketchTransform.Kind.Move -> {
                    com.rm.parrotmetric.ui.design.NumberRow("X", t.dx, "mm", true) { t.dx = it }
                    com.rm.parrotmetric.ui.design.NumberRow("Y", t.dy, "mm", true) { t.dy = it }
                    com.rm.parrotmetric.ui.design.NumberRow("Turn", t.degrees, "°", true) { t.degrees = it }
                    com.rm.parrotmetric.ui.design.Toggle("Copy", t.copy) { t.copy = it }
                }
                SketchTransform.Kind.Scale -> {
                    com.rm.parrotmetric.ui.design.NumberRow("Scale", t.factor, "×", false) { t.factor = it }
                    com.rm.parrotmetric.ui.design.Toggle("Copy", t.copy) { t.copy = it }
                }
                SketchTransform.Kind.Row -> {
                    com.rm.parrotmetric.ui.design.NumberRow("Count", t.count, "", false) { t.count = it }
                    com.rm.parrotmetric.ui.design.NumberRow("X apart", t.dx, "mm", true) { t.dx = it }
                    com.rm.parrotmetric.ui.design.NumberRow("Y apart", t.dy, "mm", true) { t.dy = it }
                    com.rm.parrotmetric.ui.design.NumberRow("Rows", t.rows, "", false) { t.rows = it }
                    if (t.rows >= 1.5) com.rm.parrotmetric.ui.design.NumberRow("Rows apart", t.rowGap, "mm", true) { t.rowGap = it }
                }
                SketchTransform.Kind.Round -> {
                    com.rm.parrotmetric.ui.design.NumberRow("Count", t.count, "", false) { t.count = it }
                    com.rm.parrotmetric.ui.design.NumberRow("Angle", t.degrees, "°", true) { t.degrees = it }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = editor::cancelTransform, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(18.dp), color = Palette.raised, contentColor = Palette.text) {
                    Box(contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
                Surface(onClick = editor::commitTransform, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(18.dp), color = Palette.mint, contentColor = Palette.ink) {
                    Box(contentAlignment = Alignment.Center) { Text("Done", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

