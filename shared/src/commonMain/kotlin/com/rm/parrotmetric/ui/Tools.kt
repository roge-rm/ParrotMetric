package com.rm.parrotmetric.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.rm.parrotmetric.design.PlaneFeature
import com.rm.parrotmetric.design.PrimitiveKind
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.ui.design.DesignEditor

/** What a tool needs to run: the screen's state, the design, the platform's actions and a way to open a sheet. */
class ToolContext(val state: ModelState, val design: DesignEditor, val actions: ModelActions, val openSheet: (String) -> Unit) {
    /** One flat face or one plane picked, and nothing else: something to sketch on. */
    val oneFace get() = (state.selectedFaces == 1 && state.selectedPlanes == 0 || state.selectedPlanes == 1 && state.selectedFaces == 0) &&
        state.selectedEdges == 0
}

/**
 * A tool on the model screen, for the tool sheets and bars, the right-click
 * menu, keyboard shortcuts and tool search. [solid] and [mesh] say which
 * Modify list it's in; [key] is its keyboard shortcut; [suggest] is when the
 * right-click menu offers it for what's selected.
 */
class ToolDef(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val group: ToolGroup,
    val key: String? = null,
    val solid: Boolean = true,
    val mesh: Boolean = false,
    val enabled: (ToolContext) -> Boolean = { true },
    val suggest: (ModelState) -> Boolean = { false },
    /** Tools with the same cluster share one toolbar button, with a menu. */
    val cluster: String? = null,
    val run: (ToolContext) -> Unit,
)

object Tools {
    private fun edges(s: ModelState) = s.selectedEdges > 0
    private fun faces(s: ModelState) = s.selectedFaces > 0
    private fun areas(s: ModelState) = s.selectedAreas > 0

    val all: List<ToolDef> = listOf(
        ToolDef("sketch.top", "Top", Icons.plane, ToolGroup.Sketch, cluster = "Sketch on a plane") { it.actions.startSketch(SketchPlane.Top) },
        ToolDef("sketch.front", "Front", Icons.plane, ToolGroup.Sketch, cluster = "Sketch on a plane") { it.actions.startSketch(SketchPlane.Front) },
        ToolDef("sketch.right", "Right", Icons.plane, ToolGroup.Sketch, cluster = "Sketch on a plane") { it.actions.startSketch(SketchPlane.Right) },
        ToolDef(
            "sketch.selected", "On selected", Icons.sketch, ToolGroup.Sketch, key = "N",
            enabled = { it.oneFace }, suggest = { s -> s.selectedFaces == 1 && s.selectedEdges == 0 || s.selectedPlanes == 1 },
        ) { it.actions.startSketch(null) },

        ToolDef("extrude", "Extrude", Icons.extrude, ToolGroup.Create, key = "E", suggest = ::areas) { it.design.startExtrude() },
        ToolDef("revolve", "Revolve", Icons.revolve, ToolGroup.Create, key = "Shift+E", suggest = ::areas) { it.design.startRevolve() },
        ToolDef("open", "Open", Icons.open, ToolGroup.Create) { it.actions.openFile() },
        ToolDef("sweep", "Sweep", Icons.sweep, ToolGroup.Create, cluster = "Sweep and loft", suggest = { it.selectedAreas > 0 }) { it.design.startSweep() },
        ToolDef("loft", "Loft", Icons.loft, ToolGroup.Create, cluster = "Sweep and loft") { it.design.startLoft() },
        ToolDef("pipe", "Pipe", Icons.pipe, ToolGroup.Create, cluster = "Sweep and loft") { it.design.startPipe() },
        ToolDef("coil", "Coil", Icons.coil, ToolGroup.Create, cluster = "Sweep and loft") { it.design.startCoil() },
        ToolDef("box", "Box", Icons.box, ToolGroup.Create, cluster = "Shapes") { it.design.startPrimitive(PrimitiveKind.Box) },
        ToolDef("cylinder", "Cylinder", Icons.cylinder, ToolGroup.Create, cluster = "Shapes") { it.design.startPrimitive(PrimitiveKind.Cylinder) },
        ToolDef("sphere", "Sphere", Icons.sphere, ToolGroup.Create, cluster = "Shapes") { it.design.startPrimitive(PrimitiveKind.Sphere) },
        ToolDef("torus", "Torus", Icons.torus, ToolGroup.Create, cluster = "Shapes") { it.design.startPrimitive(PrimitiveKind.Torus) },
        ToolDef("cone", "Cone", Icons.cone, ToolGroup.Create, cluster = "Shapes") { it.design.startPrimitive(PrimitiveKind.Cone) },

        ToolDef("fillet", "Fillet", Icons.fillet, ToolGroup.Modify, key = "F", suggest = ::edges) { it.design.startFillet() },
        ToolDef("chamfer", "Chamfer", Icons.chamfer, ToolGroup.Modify, key = "Shift+F", suggest = ::edges) { it.design.startChamfer() },
        ToolDef("shell", "Shell", Icons.shell, ToolGroup.Modify, key = "W", suggest = ::faces, cluster = "Shell and draft") { it.design.startShell() },
        ToolDef("hole", "Hole", Icons.hole, ToolGroup.Modify, key = "H", mesh = true, cluster = "Holes and threads") { it.design.startHole() },
        ToolDef("thread", "Thread", Icons.thread, ToolGroup.Modify, cluster = "Holes and threads") { it.design.startThread() },
        ToolDef("draft", "Draft", Icons.draft, ToolGroup.Modify, key = "Shift+D", suggest = ::faces, cluster = "Shell and draft") { it.design.startDraft() },
        ToolDef("planecut", "Plane cut", Icons.cut, ToolGroup.Modify, solid = false, mesh = true, cluster = "Split and cut") { it.design.startPlaneCut() },
        ToolDef("tosolid", "To solid", Icons.convert, ToolGroup.Modify, solid = false, mesh = true) { it.design.startConvert() },
        ToolDef("mirror", "Mirror", Icons.mirror, ToolGroup.Modify, key = "Shift+M", mesh = true, cluster = "Mirror and pattern") { it.design.startMirror() },
        ToolDef("pattern", "Pattern", Icons.pattern, ToolGroup.Modify, key = "P", mesh = true, cluster = "Mirror and pattern") { it.design.startPattern() },
        ToolDef("combine", "Combine", Icons.combine, ToolGroup.Modify, key = "J", mesh = true) { it.design.startCombine() },
        ToolDef("split", "Split", Icons.cut, ToolGroup.Modify, key = "X", mesh = true, cluster = "Split and cut") { it.design.startSplit() },
        ToolDef("move", "Move", Icons.move, ToolGroup.Modify, key = "M", mesh = true, suggest = ::faces) { it.design.startMove() },
        ToolDef("scale", "Scale", Icons.scale, ToolGroup.Modify, mesh = true, cluster = "Scale and align") { it.design.startScale() },
        ToolDef("align", "Align", Icons.align, ToolGroup.Modify, mesh = true, cluster = "Scale and align", suggest = { it.selectedFaces == 2 }) { it.design.startAlign() },

        ToolDef("plane.offset", "Offset plane", Icons.plane, ToolGroup.Construct, cluster = "Planes") { it.design.startPlane(PlaneFeature.Kind.Offset) },
        ToolDef("plane.angle", "Angled plane", Icons.plane, ToolGroup.Construct, cluster = "Planes") { it.design.startPlane(PlaneFeature.Kind.Angle) },
        ToolDef("plane.mid", "Midplane", Icons.plane, ToolGroup.Construct, cluster = "Planes") { it.design.startPlane(PlaneFeature.Kind.Midway) },
        ToolDef("plane.points", "Plane through three points", Icons.plane, ToolGroup.Construct, cluster = "Planes", suggest = { it.selectedCorners >= 3 }) {
            it.design.startPlane(PlaneFeature.Kind.ThreePoints)
        },
        ToolDef("plane.edges", "Plane through two edges", Icons.plane, ToolGroup.Construct, cluster = "Planes", suggest = { it.selectedEdges == 2 }) {
            it.design.startPlane(PlaneFeature.Kind.TwoEdges)
        },
        ToolDef("plane.tangent", "Tangent plane", Icons.plane, ToolGroup.Construct, cluster = "Planes") { it.design.startPlane(PlaneFeature.Kind.Tangent) },
        ToolDef("plane.along", "Plane along an edge", Icons.plane, ToolGroup.Construct, cluster = "Planes") { it.design.startPlane(PlaneFeature.Kind.AlongEdge) },
        ToolDef("axis", "Axis", Icons.axis, ToolGroup.Construct) { it.design.startAxis() },
        ToolDef("point", "Point", Icons.pointTool, ToolGroup.Construct) { it.design.startPoint() },
        ToolDef("canvas", "Canvas", Icons.canvas, ToolGroup.Construct) { it.actions.insertCanvas() },

        ToolDef("measure", "Measure", Icons.measure, ToolGroup.Inspect, key = "I", suggest = { faces(it) || edges(it) }) {
            it.design.startMeasuring(); it.openSheet("measure")
        },
        ToolDef("section", "Section", Icons.section, ToolGroup.Inspect, key = "Shift+I") { it.design.startSection(); it.openSheet("section") },
        ToolDef("interference", "Interference", Icons.interference, ToolGroup.Inspect) { it.openSheet("interference") },
        ToolDef("parameters", "Parameters", Icons.parameters, ToolGroup.Inspect) { it.openSheet("parameters") },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** A group's tools; for Modify, those for solids or for meshes. */
    fun inGroup(group: ToolGroup, mesh: Boolean = false) =
        all.filter { it.group == group && (group != ToolGroup.Modify || (if (mesh) it.mesh else it.solid)) }

    /** Runs a tool, remembering it for Repeat. */
    fun run(tool: ToolDef, context: ToolContext) {
        if (!tool.enabled(context)) return
        context.design.lastTool = tool.id
        tool.run(context)
    }
}
