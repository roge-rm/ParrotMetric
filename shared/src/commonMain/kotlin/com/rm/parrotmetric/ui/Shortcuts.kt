package com.rm.parrotmetric.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.ui.sketch.SketchEditor
import com.rm.parrotmetric.ui.sketch.SketchTool
import com.rm.parrotmetric.ui.sketch.SketchTransform
import com.rm.parrotmetric.ui.sketch.next
import kotlin.math.PI

private fun keys(vararg k: String) = k.toList()

/** The model screen's shortcuts: every tool, then file, edit and view. */
fun modelShortcuts(c: ToolContext, openFinder: () -> Unit, openKeys: () -> Unit, panelOpen: Boolean): List<Shortcut> {
    val design = c.design
    val actions = c.actions
    val view = { yaw: Double, pitch: Double -> actions.viewFrom(yaw.toFloat(), pitch.toFloat()) }
    return Tools.all.map { t ->
        Shortcut(t.label, listOfNotNull(t.key), "Tools (${t.group.label})", t.enabled(c)) { Tools.run(t, c) }
    } + listOf(
        Shortcut("New design", keys("Ctrl+N"), "File") { actions.newDesign() },
        Shortcut("Open", keys("Ctrl+O"), "File") { actions.openFile() },
        Shortcut("Save", keys("Ctrl+S"), "File") { actions.save() },
        Shortcut("Save as", keys("Ctrl+Shift+S"), "File") { actions.saveAs() },
        Shortcut("Export", keys("Ctrl+E"), "File") { c.openSheet("export") },
        Shortcut("Settings", keys(), "File") { actions.showScreen(AppScreen.Settings) },
        Shortcut("Help", keys("F1"), "File") { actions.showScreen(AppScreen.Help) },  // F1 itself is handled before shortcuts, on every screen
        Shortcut("Main menu", keys(), "File") { actions.showScreen(AppScreen.Start) },
        Shortcut("Undo", keys("Ctrl+Z"), "Edit", design.canUndo) { design.undo() },
        Shortcut("Redo", keys("Ctrl+Shift+Z", "Ctrl+Y"), "Edit", design.canRedo) { design.redo() },
        Shortcut("Fit the view", keys("V", "F6"), "View") { actions.fit() },
        // Digits go into the panel's fields while one is open.
        Shortcut("Front view", keys("1"), "View", !panelOpen) { view(-PI / 2, 0.0) },
        Shortcut("Back view", keys("2"), "View", !panelOpen) { view(PI / 2, 0.0) },
        Shortcut("Top view", keys("3"), "View", !panelOpen) { view(-PI / 2, 1.5607) },
        Shortcut("Bottom view", keys("4"), "View", !panelOpen) { view(-PI / 2, -1.5607) },
        Shortcut("Left view", keys("5"), "View", !panelOpen) { view(PI, 0.0) },
        Shortcut("Right view", keys("6"), "View", !panelOpen) { view(0.0, 0.0) },
        Shortcut("Home view", keys("0"), "View", !panelOpen) { view(-0.9, 0.45) },
        Shortcut("Find a tool", keys("S"), "Keys", run = openFinder),
        Shortcut("List the keys", keys("?"), "Keys", run = openKeys),
    )
}

/** The sketch screen's shortcuts: drawing tools, editing, the constraints that fit the selection, and finishing. */
fun sketchShortcuts(e: SketchEditor, actions: ModelActions, openFinder: () -> Unit, openKeys: () -> Unit): List<Shortcut> {
    // Pressing a tool's key again while it's in hand switches how it draws.
    fun tool(label: String, key: List<String>, t: SketchTool, again: (() -> Unit)? = null) = Shortcut(label, key, "Draw") {
        if (e.tool == t && again != null) {
            e.endDrawing()
            again()
        } else e.selectTool(t)
    }
    val choices = e.constraintChoices()
    val hasSelection = e.selection.isNotEmpty()
    return listOf(
        tool("Line", keys("L"), SketchTool.Line),
        tool("Rectangle", keys("R"), SketchTool.Rectangle) { e.rectangleStyle = e.rectangleStyle.next() },
        tool("Circle", keys("C"), SketchTool.Circle) { e.circleStyle = e.circleStyle.next() },
        tool("Arc", keys("A"), SketchTool.Arc) { e.arcStyle = e.arcStyle.next() },
        tool("Point", keys("Shift+P"), SketchTool.Point),
        tool("Spline", keys("Shift+S"), SketchTool.Spline) { e.splineStyle = e.splineStyle.next() },
        tool("Polygon", keys("G"), SketchTool.Polygon) { e.polygonStyle = e.polygonStyle.next() },
        tool("Slot", keys("Shift+L"), SketchTool.Slot) { e.slotStyle = e.slotStyle.next() },
        tool("Ellipse", keys("Shift+C"), SketchTool.Ellipse),
        tool("Conic", keys(), SketchTool.Conic),
        tool("Text", keys("Shift+T"), SketchTool.Text),
        tool("Select", keys(), SketchTool.Select),
        Shortcut("Dimension", keys("D"), "Edit") { e.selectTool(if (e.tool == SketchTool.Dimension) SketchTool.Select else SketchTool.Dimension) },
        Shortcut("Trim", keys("T"), "Edit") { e.selectTool(if (e.tool == SketchTool.Trim) SketchTool.Select else SketchTool.Trim) },
        Shortcut("Extend", keys("E"), "Edit") { e.selectTool(if (e.tool == SketchTool.Extend) SketchTool.Select else SketchTool.Extend) },
        Shortcut("Offset", keys("O"), "Edit", e.selectedCurves.isNotEmpty()) { e.startOffset() },
        Shortcut("Project", keys("P"), "Edit", e.outline != null) { e.projectOutline() },
        Shortcut("Round corner", keys("F"), "Edit", e.selectedCorner != null) { e.startCornerFillet() },
        Shortcut("Cut corner", keys("Shift+F"), "Edit", e.selectedCorner != null) { e.startCornerChamfer() },
        Shortcut("Break", keys("B"), "Edit") { e.selectTool(if (e.tool == SketchTool.Break) SketchTool.Select else SketchTool.Break) },
        Shortcut("Mirror", keys("Shift+M"), "Edit", e.selectedCurves.size >= 2) { e.mirrorSelection() },
        Shortcut("Move", keys("M"), "Edit", e.selectedCurves.isNotEmpty()) { e.startTransform(SketchTransform.Kind.Move) },
        Shortcut("Scale", keys(), "Edit", e.selectedCurves.isNotEmpty()) { e.startTransform(SketchTransform.Kind.Scale) },
        Shortcut("Pattern", keys(), "Edit", e.selectedCurves.isNotEmpty()) { e.startTransform(SketchTransform.Kind.Row) },
        Shortcut("Pattern round", keys(), "Edit", e.selectedCurves.isNotEmpty()) { e.startTransform(SketchTransform.Kind.Round) },
        Shortcut("Construction", keys("X"), "Edit") { e.toggleConstruction() },
        Shortcut("Add a drawing (SVG or DXF)", keys("Shift+I"), "Edit", e.canAddDrawing) { e.addDrawing() },
        Shortcut("Delete", keys("Delete"), "Edit", hasSelection) { e.deleteSelection() },
        Shortcut("Undo", keys("Ctrl+Z"), "Edit", e.canUndo) { e.undo() },
        Shortcut("Redo", keys("Ctrl+Shift+Z", "Ctrl+Y"), "Edit", e.canRedo) { e.redo() },
    ) + choices.map { ch ->
        val key = when (ch.label) {
            "Horizontal" -> keys("H")
            "Vertical" -> keys("V")
            else -> keys()
        }
        Shortcut(ch.label, key, "Constrain") { e.apply(ch) }
    } + listOf(
        Shortcut("Horizontal", keys("H"), "Constrain", false) {},
        Shortcut("Vertical", keys("V"), "Constrain", false) {},
        Shortcut("Fit the view", keys("F6"), "Sketch") { actions.fit() },
        Shortcut("Finish the sketch", keys("Ctrl+Enter"), "Sketch") { actions.finishSketch() },
        Shortcut("Find a tool", keys("S"), "Keys", run = openFinder),
        Shortcut("List the keys", keys("?"), "Keys", run = openKeys),
    )
}

/** Keys that aren't shortcuts of their own, for the key list. */
private val general = listOf(
    "Esc or Backspace" to "Cancel, close or go back",
    "Enter" to "Done; in a sketch, end the line, then finish",
    "Tab" to "Next field, or next size while drawing",
    "Digits" to "Type into the panel's first field, or a size while drawing",
    "Alt" to "Works as Ctrl",
    "A tool's key again" to "The next way of drawing it",
)

/** A key's name as a small label, beside a tool. Shift shows as an arrow, to keep it short. */
@Composable
fun KeyBadge(text: String, modifier: Modifier = Modifier) {
    val parts = text.split(" or ")
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(Palette.line).padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Text(" or ", fontSize = 10.sp, color = Palette.faint, maxLines = 1)
            val shifted = part.contains("Shift+")
            val ctrl = part.startsWith("Ctrl+")
            if (ctrl) Text("Ctrl ", fontSize = 10.sp, color = Palette.muted, fontFamily = FontFamily.Monospace, maxLines = 1)
            if (shifted) androidx.compose.material3.Icon(Icons.shiftKey, "Shift", Modifier.size(9.dp), tint = Palette.muted)
            Text(part.removePrefix("Ctrl+").removePrefix("Shift+"), fontSize = 10.sp, color = Palette.muted, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

/**
 * Tool search: type part of a name, arrows to choose, Enter to run it. Tools
 * that can't be used now are shown greyed.
 */
@Composable
fun ToolFinder(shortcuts: List<Shortcut>, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val matches = shortcuts.distinctBy { it.label }
        .filter { it.label.contains(query.trim(), ignoreCase = true) }
        .sortedWith(compareBy({ !it.enabled }, { !it.label.startsWith(query.trim(), ignoreCase = true) }))
        .take(8)
    fun run(s: Shortcut) {
        if (!s.enabled) return
        close()
        s.run()
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
        Surface(color = Palette.raised, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicTextField(
                    query,
                    onValueChange = { query = it; chosen = 0 },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.ground)
                        .padding(horizontal = 14.dp, vertical = 12.dp).focusRequester(focus)
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (it.key) {
                                Key.DirectionDown -> { chosen = (chosen + 1).coerceAtMost(matches.size - 1); true }
                                Key.DirectionUp -> { chosen = (chosen - 1).coerceAtLeast(0); true }
                                Key.Enter, Key.NumPadEnter -> { matches.getOrNull(chosen)?.let(::run); true }
                                else -> false
                            }
                        },
                    textStyle = TextStyle(color = Palette.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(Palette.mint),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { matches.getOrNull(chosen)?.let(::run) }),
                    decorationBox = { inner -> if (query.isEmpty()) Text("Find a tool", color = Palette.faint, fontSize = 17.sp); inner() },
                )
                matches.forEachIndexed { i, s ->
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (i == chosen) Palette.line else Color.Transparent)
                            .clickable { run(s) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.label, Modifier.weight(1f), fontSize = 15.sp, color = if (s.enabled) Palette.text else Palette.faint, maxLines = 1)
                        s.keys.firstOrNull()?.let { KeyBadge(it) }
                    }
                }
            }
        }
    }
}

/** Every key on this screen, by group. */
@Composable
fun KeyList(shortcuts: List<Shortcut>, close: () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).clickable(onClick = close), contentAlignment = Alignment.Center) {
        Surface(color = Palette.raised, shape = RoundedCornerShape(22.dp), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 640.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Keys", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                for ((group, items) in shortcuts.filter { it.keys.isNotEmpty() }.distinctBy { it.label to it.keys }.groupBy { it.group }) {
                    Text(group, Modifier.padding(top = 12.dp, bottom = 2.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.mint)
                    for (s in items) KeyRow(s.keys.joinToString(" or "), s.label)
                }
                Text("Anywhere", Modifier.padding(top = 12.dp, bottom = 2.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.mint)
                for ((k, what) in general) KeyRow(k, what)
            }
        }
    }
}

@Composable
private fun KeyRow(key: String, label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = Palette.text)
        KeyBadge(key)
    }
}
