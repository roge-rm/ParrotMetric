package com.rm.parrotmetric.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.ui.sketch.CameraState
import com.rm.parrotmetric.ui.sketch.PlaneProjection
import com.rm.parrotmetric.ui.sketch.SketchBottom
import com.rm.parrotmetric.ui.sketch.SketchEditor
import com.rm.parrotmetric.ui.sketch.SketchOverlay
import com.rm.parrotmetric.ui.sketch.SketchStatus
import com.rm.parrotmetric.ui.sketch.SketchTopBar

/** What the model screen shows. */
data class ModelState(
    val title: String = "Untitled",
    val status: String = "",
    val busy: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val selectedFaces: Int = 0,
    val selectedEdges: Int = 0,
    val isMesh: Boolean = false,
    val triangles: Int = 0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    /** The camera as last drawn, for lining the sketch overlay up with the view. */
    val camera: CameraState? = null,
    /** The sketch being edited, if any. */
    val sketch: SketchEditor? = null,
)

/** A step in the history bar. Tapping it calls [ModelActions.openHistory] with its place in the list. */
data class HistoryItem(val name: String, val kind: ToolGroup)

/** What the model screen can ask the platform to do. */
interface ModelActions {
    fun newBox()
    fun openFile()
    fun exportStl()
    fun exportStep()
    fun cutHole()
    fun clearSelection()
    fun fit()
    fun viewFrom(yaw: Float, pitch: Float)
    fun pan(dx: Float, dy: Float)
    fun zoom(factor: Float)
    /** Starts a sketch on a plane, or on the selected flat face when plane is null. */
    fun startSketch(plane: SketchPlane?)
    fun finishSketch()
    fun openHistory(index: Int)
}

/**
 * The model fills the screen. Along the top: the file menu, the name and
 * undo; the orientation cube below on the right. Along the bottom: the
 * history bar and the tool groups, whose tools open in a sheet above them.
 */
@Composable
fun ModelScreen(viewport: @Composable () -> Unit, logo: @Composable () -> Unit, state: ModelState, actions: ModelActions) {
    var openGroup by remember { mutableStateOf<ToolGroup?>(null) }
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
                TopBar(logo, state, actions)
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
                    SelectionChip(state, actions, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                    if (state.status.isNotEmpty()) {
                        Surface(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                            color = Palette.surface.copy(alpha = 0.85f),
                            contentColor = Palette.text,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(state.status, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp)
                        }
                    }
                }
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedVisibility(
                        visible = openGroup != null,
                        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
                    ) {
                        openGroup?.let { ToolSheet(it, state, actions) { openGroup = null } }
                    }
                    if (openGroup == null) HistoryBar(state.history, actions::openHistory)
                    GroupBar(openGroup) { openGroup = if (openGroup == it) null else it }
                }
            }
        }
    }
}

@Composable
private fun TopBar(logo: @Composable () -> Unit, state: ModelState, actions: ModelActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { menu = true }) { logo() }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Palette.raised) {
                DropdownMenuItem({ Text("Open…") }, onClick = { menu = false; actions.openFile() }, leadingIcon = { Icon(Icons.open, null, tint = Palette.mint) })
                DropdownMenuItem({ Text("Export STL…") }, onClick = { menu = false; actions.exportStl() }, leadingIcon = { Icon(Icons.export, null, tint = Palette.mint) })
                DropdownMenuItem(
                    { Text("Export STEP…") },
                    onClick = { menu = false; actions.exportStep() },
                    enabled = !state.isMesh,
                    leadingIcon = { Icon(Icons.export, null, tint = Palette.mint) },
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(state.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1)
            Text(
                if (state.busy) "Working…" else if (state.isMesh) "Mesh, ${state.triangles} triangles" else "Solid",
                fontSize = 12.sp,
                color = Palette.muted,
                maxLines = 1,
            )
        }
        IconButton(onClick = {}, enabled = false) { Icon(Icons.parts, "Parts list", tint = Palette.faint) }
        IconButton(onClick = {}, enabled = false) { Icon(Icons.undo, "Undo", tint = Palette.faint) }
        IconButton(onClick = {}, enabled = false) { Icon(Icons.redo, "Redo", tint = Palette.faint) }
    }
}

@Composable
private fun SelectionChip(state: ModelState, actions: ModelActions, modifier: Modifier) {
    val parts = buildList {
        if (state.selectedFaces > 0) add(if (state.selectedFaces == 1) "1 face" else "${state.selectedFaces} faces")
        if (state.selectedEdges > 0) add(if (state.selectedEdges == 1) "1 edge" else "${state.selectedEdges} edges")
    }
    AnimatedVisibility(parts.isNotEmpty(), modifier, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
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

@Composable
private fun HistoryBar(history: List<HistoryItem>, onOpen: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((index, item) in history.withIndex()) {
            Surface(onClick = { onOpen(index) }, color = Palette.surface, contentColor = Palette.text, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.height(36.dp).padding(start = 9.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.kind.icon, null, Modifier.size(15.dp), tint = item.kind.colour)
                    Spacer(Modifier.width(6.dp))
                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        if (history.isNotEmpty()) Box(Modifier.width(6.dp).height(38.dp).clip(RoundedCornerShape(3.dp)).background(Palette.orange))
    }
}

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
private fun ToolSheet(group: ToolGroup, state: ModelState, actions: ModelActions, close: () -> Unit) {
    var meshTools by remember(group) { mutableStateOf(state.isMesh) }
    val tools = when (group) {
        ToolGroup.Sketch -> listOf(
            Tool("Top", Icons.plane) { actions.startSketch(SketchPlane.Top) },
            Tool("Front", Icons.plane) { actions.startSketch(SketchPlane.Front) },
            Tool("Right", Icons.plane) { actions.startSketch(SketchPlane.Right) },
            Tool("On face", Icons.sketch, if (state.selectedFaces == 1 && state.selectedEdges == 0 && !state.isMesh) ({ actions.startSketch(null) }) else null),
        )
        ToolGroup.Create -> listOf(
            Tool("Box", Icons.box) { actions.newBox() },
            Tool("Extrude", Icons.extrude, null),
            Tool("Revolve", Icons.revolve, null),
            Tool("Open", Icons.open) { actions.openFile() },
        )
        ToolGroup.Modify -> if (meshTools) listOf(
            Tool("Cut", Icons.cut) { actions.cutHole() },
            Tool("Combine", Icons.combine, null),
            Tool("Mirror", Icons.mirror, null),
            Tool("Move", Icons.move, null),
        ) else listOf(
            Tool("Fillet", Icons.fillet, null),
            Tool("Chamfer", Icons.chamfer, null),
            Tool("Shell", Icons.shell, null),
            Tool("Hole", Icons.hole, null),
            Tool("Mirror", Icons.mirror, null),
            Tool("Pattern", Icons.pattern, null),
            Tool("Combine", Icons.combine, null),
            Tool("Move", Icons.move, null),
        )
        ToolGroup.Construct -> listOf(Tool("Plane", Icons.plane, null), Tool("Axis", Icons.axis, null), Tool("Point", Icons.point, null))
        ToolGroup.Inspect -> listOf(Tool("Measure", Icons.measure, null), Tool("Section", Icons.section, null))
    }
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                if (group == ToolGroup.Modify) Segmented(listOf("Solid", "Mesh"), if (meshTools) 1 else 0) { meshTools = it == 1 }
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

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.ground).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(11.dp),
                color = if (i == selected) Palette.line else Color.Transparent,
                contentColor = if (i == selected) Palette.text else Palette.muted,
            ) {
                Text(label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium)
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
