package com.rm.parrotmetric.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.ui.design.DesignEditor
import com.rm.parrotmetric.ui.design.FeaturePanel
import com.rm.parrotmetric.ui.design.HistoryEntry
import com.rm.parrotmetric.ui.design.Segmented
import com.rm.parrotmetric.ui.sketch.CameraState
import com.rm.parrotmetric.ui.sketch.PlaneProjection
import com.rm.parrotmetric.ui.sketch.SketchBottom
import com.rm.parrotmetric.ui.sketch.SketchEditor
import com.rm.parrotmetric.ui.sketch.SketchOverlay
import com.rm.parrotmetric.ui.sketch.SketchStatus
import com.rm.parrotmetric.ui.sketch.SketchTopBar
import kotlinx.coroutines.delay

/** What the model screen shows besides the design itself. */
data class ModelState(
    val title: String = "Untitled",
    val selectedFaces: Int = 0,
    val selectedEdges: Int = 0,
    val selectedAreas: Int = 0,
    val selectedPlanes: Int = 0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    /** The camera as last drawn, for lining the sketch overlay up with the view. */
    val camera: CameraState? = null,
    /** The sketch being edited, if any. */
    val sketch: SketchEditor? = null,
)

/** What the model screen asks the platform to do. */
interface ModelActions {
    fun newDesign()
    fun save()
    fun saveAs()
    fun openFile()
    fun export(request: com.rm.parrotmetric.ui.design.ExportRequest)
    fun clearSelection()
    fun fit()
    fun viewFrom(yaw: Float, pitch: Float)
    fun pan(dx: Float, dy: Float)
    fun zoom(factor: Float)
    /** Starts a sketch on a plane, or on the selected flat face when plane is null. */
    fun startSketch(plane: SketchPlane?)
    fun finishSketch()
    /** Opens a step of the history to change it. */
    fun openHistory(id: Int)
}

/**
 * The model fills the screen. Along the top: the file menu, the name, undo
 * and redo; the orientation cube below on the right. Along the bottom: the
 * history bar and the tool groups, whose tools open in a sheet above them,
 * or the open feature's panel.
 */
@Composable
fun ModelScreen(
    viewport: @Composable () -> Unit,
    logo: @Composable () -> Unit,
    state: ModelState,
    design: DesignEditor,
    actions: ModelActions,
) {
    var openGroup by remember { mutableStateOf<ToolGroup?>(null) }
    // A sheet over the bottom: the parts list or export.
    var sheet by remember { mutableStateOf<String?>(null) }
    MaterialTheme(colorScheme = Palette.scheme) {
        val sketch = state.sketch
        // The view stays put while the controls over it change, so it keeps its GL context.
        Box(Modifier.fillMaxSize().background(Palette.ground)) {
            viewport()
            if (sketch != null) {
                state.camera?.let { SketchOverlay(sketch, PlaneProjection(it, sketch.plane), actions::pan, actions::zoom) }
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SketchTopBar(sketch, actions::finishSketch)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SketchStatus(sketch, Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
                    }
                    SketchBottom(sketch)
                }
            } else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(logo, state, design, actions, onParts = { sheet = if (sheet == "parts") null else "parts" }, onExport = { sheet = "export" })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(color = Palette.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(18.dp)) {
                            OrientationCube(state.yaw, state.pitch, actions::viewFrom, size = 72.dp)
                        }
                        Surface(color = Palette.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(14.dp)) {
                            IconButton(onClick = actions::fit) { Icon(Icons.fit, "Fit the model in view", tint = Palette.text) }
                        }
                    }
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectionChip(state, actions)
                        Message(design)
                    }
                }
                Column(Modifier.imePadding().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (design.panel != null) {
                        FeaturePanel(design)
                    } else if (sheet == "parts") {
                        com.rm.parrotmetric.ui.design.PartsSheet(design) { sheet = null }
                    } else if (sheet == "measure") {
                        com.rm.parrotmetric.ui.design.MeasureSheet(design) { design.stopMeasuring(); sheet = null }
                    } else if (sheet == "section") {
                        com.rm.parrotmetric.ui.design.SectionSheet(design) { design.stopSection(); sheet = null }
                    } else if (sheet == "parameters") {
                        com.rm.parrotmetric.ui.design.ParametersSheet(design) { sheet = null }
                    } else if (sheet == "export") {
                        com.rm.parrotmetric.ui.design.ExportSheet(design, { sheet = null }) { actions.export(it); sheet = null }
                    } else {
                        AnimatedVisibility(
                            visible = openGroup != null,
                            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
                        ) {
                            openGroup?.let { ToolSheet(it, state, design, actions, onSheet = { name -> sheet = name }) { openGroup = null } }
                        }
                        if (openGroup == null) HistoryBar(design, actions)
                        GroupBar(openGroup) { openGroup = if (openGroup == it) null else it }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(logo: @Composable () -> Unit, state: ModelState, design: DesignEditor, actions: ModelActions, onParts: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    design.version
    val bodies = design.built?.bodies?.size ?: 0
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { menu = true }) { logo() }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Palette.raised) {
                DropdownMenuItem({ Text("New") }, onClick = { menu = false; actions.newDesign() }, leadingIcon = { Icon(Icons.newFile, null, tint = Palette.mint) })
                DropdownMenuItem({ Text("Open…") }, onClick = { menu = false; actions.openFile() }, leadingIcon = { Icon(Icons.open, null, tint = Palette.mint) })
                DropdownMenuItem({ Text("Save") }, onClick = { menu = false; actions.save() }, leadingIcon = { Icon(Icons.save, null, tint = Palette.mint) })
                DropdownMenuItem({ Text("Save as…") }, onClick = { menu = false; actions.saveAs() }, leadingIcon = { Icon(Icons.save, null, tint = Palette.mint) })
                DropdownMenuItem({ Text("Export…") }, onClick = { menu = false; onExport() }, leadingIcon = { Icon(Icons.export, null, tint = Palette.mint) })
            }
        }
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(state.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1)
            val triangles = design.triangles
            Text(
                when {
                    design.busy -> "Working…"
                    bodies == 0 -> "Nothing yet"
                    else -> (if (bodies == 1) "1 body" else "$bodies bodies") + if (triangles > 0) ", $triangles triangles" else ""
                },
                fontSize = 12.sp,
                color = Palette.muted,
                maxLines = 1,
            )
        }
        IconButton(onClick = onParts) { Icon(Icons.parts, "Parts list", tint = Palette.text) }
        IconButton(onClick = design::undo, enabled = design.canUndo) { Icon(Icons.undo, "Undo", tint = if (design.canUndo) Palette.text else Palette.faint) }
        IconButton(onClick = design::redo, enabled = design.canRedo) { Icon(Icons.redo, "Redo", tint = if (design.canRedo) Palette.text else Palette.faint) }
    }
}

@Composable
private fun Message(design: DesignEditor) {
    val message = design.message
    AnimatedVisibility(message != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(300))) {
        Surface(color = Color(0xFF3A2C24), contentColor = Color(0xFFFFB48C), shape = RoundedCornerShape(15.dp)) {
            Text(message.orEmpty(), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp)
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(3000)
            design.message = null
        }
    }
}

@Composable
private fun SelectionChip(state: ModelState, actions: ModelActions) {
    val parts = buildList {
        if (state.selectedFaces > 0) add(if (state.selectedFaces == 1) "1 face" else "${state.selectedFaces} faces")
        if (state.selectedEdges > 0) add(if (state.selectedEdges == 1) "1 edge" else "${state.selectedEdges} edges")
        if (state.selectedAreas > 0) add(if (state.selectedAreas == 1) "1 area" else "${state.selectedAreas} areas")
        if (state.selectedPlanes > 0) add(if (state.selectedPlanes == 1) "1 plane" else "${state.selectedPlanes} planes")
    }
    AnimatedVisibility(parts.isNotEmpty(), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        Surface(color = Color(0xFF3A2C24), contentColor = Color(0xFFFFB48C), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.height(36.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(parts.joinToString(", "), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = actions::clearSelection, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.close, "Clear the selection", Modifier.size(14.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryBar(design: DesignEditor, actions: ModelActions) {
    design.version
    design.built
    val history = design.history()
    val marker = design.design.marker
    val scroll = rememberScrollState()
    // New steps come in at the end, so keep the end in view as the history grows.
    LaunchedEffect(history.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        history.forEachIndexed { index, entry ->
            if (index == marker) Marker(design, history.size)
            var menu by remember(entry.id) { mutableStateOf(false) }
            val group = when (entry.kind) {
                HistoryEntry.Kind.Sketch -> ToolGroup.Sketch
                HistoryEntry.Kind.Modify -> ToolGroup.Modify
                HistoryEntry.Kind.Construct -> ToolGroup.Construct
                else -> ToolGroup.Create
            }
            Box {
                Row(
                    Modifier.alpha(if (entry.active) 1f else 0.4f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.surface)
                        .then(if (entry.error != null) Modifier.border(1.5.dp, Palette.orange, RoundedCornerShape(18.dp)) else Modifier)
                        .combinedClickable(
                            onClick = { if (entry.error != null) design.message = entry.error; actions.openHistory(entry.id) },
                            onLongClick = { menu = true },
                        )
                        .height(36.dp).padding(start = 9.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(group.icon, null, Modifier.size(15.dp), tint = if (entry.error != null) Palette.orange else group.colour)
                    Spacer(Modifier.width(6.dp))
                    Text(entry.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.text, maxLines = 1)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Palette.raised) {
                    DropdownMenuItem({ Text("Edit") }, onClick = { menu = false; actions.openHistory(entry.id) })
                    DropdownMenuItem({ Text(if (entry.active) "Roll back to here" else "Roll forward to here") }, onClick = { menu = false; design.rollTo(index) })
                    DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; design.delete(entry.id) })
                }
            }
        }
        if (marker >= history.size && history.isNotEmpty()) Marker(design, history.size)
    }
}

/** The rollback marker: after the last feature built. Tapping it when it's rolled back rolls it forward to the end. */
@Composable
private fun Marker(design: DesignEditor, total: Int) {
    Box(
        Modifier.width(18.dp).height(40.dp).clip(RoundedCornerShape(6.dp))
            .combinedClickableCompat { if (design.design.marker < total) design.rollTo(total - 1) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(6.dp).height(38.dp).clip(RoundedCornerShape(3.dp)).background(Palette.orange))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
private fun GroupBar(open: ToolGroup?, onGroup: (ToolGroup) -> Unit) {
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            for (g in ToolGroup.entries) {
                val active = g == open
                Surface(
                    onClick = { onGroup(g) },
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) g.colour.copy(alpha = 0.16f) else Color.Transparent,
                    contentColor = if (active) g.colour else Palette.text,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(g.icon, null, Modifier.size(24.dp), tint = g.colour)
                        Spacer(Modifier.height(4.dp))
                        Text(g.label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A tool in a group's sheet. A null action means it isn't built yet and shows greyed out. */
private class Tool(val label: String, val icon: ImageVector, val action: (() -> Unit)?)

@Composable
private fun ToolSheet(group: ToolGroup, state: ModelState, design: DesignEditor, actions: ModelActions, onSheet: (String) -> Unit, close: () -> Unit) {
    var meshTools by remember(group) { mutableStateOf(false) }
    val oneFace = (state.selectedFaces == 1 && state.selectedPlanes == 0 || state.selectedPlanes == 1 && state.selectedFaces == 0) && state.selectedEdges == 0
    val tools = when (group) {
        ToolGroup.Sketch -> listOf(
            Tool("Top", Icons.plane) { actions.startSketch(SketchPlane.Top) },
            Tool("Front", Icons.plane) { actions.startSketch(SketchPlane.Front) },
            Tool("Right", Icons.plane) { actions.startSketch(SketchPlane.Right) },
            Tool("On selected", Icons.sketch, if (oneFace) ({ actions.startSketch(null) }) else null),
        )
        ToolGroup.Create -> listOf(
            Tool("Extrude", Icons.extrude) { design.startExtrude() },
            Tool("Revolve", Icons.revolve) { design.startRevolve() },
            Tool("Open", Icons.open) { actions.openFile() },
        )
        ToolGroup.Modify -> if (meshTools) listOf(
            Tool("Plane cut", Icons.cut) { design.startPlaneCut() },
            Tool("To solid", Icons.convert) { design.startConvert() },
            Tool("Split", Icons.cut) { design.startSplit() },
            Tool("Combine", Icons.combine) { design.startCombine() },
            Tool("Mirror", Icons.mirror) { design.startMirror() },
            Tool("Move", Icons.move) { design.startMove() },
            Tool("Pattern", Icons.pattern) { design.startPattern() },
            Tool("Hole", Icons.hole) { design.startHole() },
        ) else listOf(
            Tool("Fillet", Icons.fillet) { design.startFillet() },
            Tool("Chamfer", Icons.chamfer) { design.startChamfer() },
            Tool("Shell", Icons.shell) { design.startShell() },
            Tool("Hole", Icons.hole) { design.startHole() },
            Tool("Draft", Icons.draft) { design.startDraft() },
            Tool("Mirror", Icons.mirror) { design.startMirror() },
            Tool("Pattern", Icons.pattern) { design.startPattern() },
            Tool("Combine", Icons.combine) { design.startCombine() },
            Tool("Split", Icons.cut) { design.startSplit() },
            Tool("Move", Icons.move) { design.startMove() },
        )
        ToolGroup.Construct -> listOf(
            Tool("Offset plane", Icons.plane) { design.startPlane(com.rm.parrotmetric.design.PlaneFeature.Kind.Offset) },
            Tool("Angled plane", Icons.plane) { design.startPlane(com.rm.parrotmetric.design.PlaneFeature.Kind.Angle) },
            Tool("Midplane", Icons.plane) { design.startPlane(com.rm.parrotmetric.design.PlaneFeature.Kind.Midway) },
            Tool("Axis", Icons.axis) { design.startAxis() },
        )
        ToolGroup.Inspect -> listOf(
            Tool("Measure", Icons.measure) { design.startMeasuring(); onSheet("measure") },
            Tool("Section", Icons.section) { design.startSection(); onSheet("section") },
            Tool("Parameters", Icons.parameters) { onSheet("parameters") },
        )
    }
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                if (group == ToolGroup.Modify) Box(Modifier.width(170.dp)) { Segmented(listOf("Solid", "Mesh"), if (meshTools) 1 else 0) { meshTools = it == 1 } }
            }
            for (row in tools.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in row) {
                        val enabled = t.action != null
                        Surface(
                            onClick = { t.action?.invoke(); close() },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).height(72.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = Palette.raised,
                            contentColor = if (enabled) Palette.text else Palette.faint,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(t.icon, null, Modifier.size(24.dp), tint = if (enabled) group.colour else Palette.faint)
                                Spacer(Modifier.height(6.dp))
                                Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

enum class ToolGroup(val label: String, val icon: ImageVector, val colour: Color) {
    Sketch("Sketch", Icons.sketch, Palette.sketch),
    Create("Create", Icons.create, Palette.create),
    Modify("Modify", Icons.modify, Palette.modify),
    Construct("Construct", Icons.construct, Palette.construct),
    Inspect("Inspect", Icons.inspect, Palette.inspect),
}
