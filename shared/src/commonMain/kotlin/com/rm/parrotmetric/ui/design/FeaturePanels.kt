package com.rm.parrotmetric.ui.design

import com.rm.parrotmetric.ui.Typing.typing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import com.rm.parrotmetric.design.AxisRef
import com.rm.parrotmetric.design.Operation
import com.rm.parrotmetric.design.AxisFeature
import com.rm.parrotmetric.design.PointFeature
import com.rm.parrotmetric.design.PrimitiveKind
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.Expression
import com.rm.parrotmetric.ui.Icons
import com.rm.parrotmetric.ui.Palette
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.round

/** The open feature's panel: its settings, and Cancel and Done. Settings past [maxHeight] scroll. */
@Composable
fun FeaturePanel(editor: DesignEditor, maxHeight: androidx.compose.ui.unit.Dp = 400.dp) {
    val d = editor.panel ?: return
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Long panels scroll, so the model stays in view above them.
            Column(
                Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            when (d) {
                is DesignEditor.ExtrudeDraft -> ExtrudeSettings(editor, d)
                is DesignEditor.RevolveDraft -> RevolveSettings(editor, d)
                is DesignEditor.EdgeDraft -> EdgeSettings(editor, d)
                is DesignEditor.FaceDraft -> FaceSettings(editor, d)
                is DesignEditor.HoleDraft -> HoleSettings(editor, d)
                is DesignEditor.SnapFitDraft -> SnapFitSettings(editor, d)
                is DesignEditor.MirrorDraft -> MirrorSettings(editor, d)
                is DesignEditor.PatternDraft -> PatternSettings(editor, d)
                is DesignEditor.CombineDraft -> CombineSettings(editor, d)
                is DesignEditor.SplitDraft -> SplitSettings(editor, d)
                is DesignEditor.MoveDraft -> MoveSettings(editor, d)
                is DesignEditor.ConvertDraft -> Header("To solid", Icons.convert, Palette.modify, d.bodies.firstOrNull())
                is DesignEditor.MeshEditDraft -> MeshEditSettings(editor, d)
                is DesignEditor.EraseDraft -> EraseSettings(editor, d)
                is DesignEditor.SeparateDraft -> Header("Separate", Icons.separate, Palette.modify, d.bodies.firstOrNull()?.let { editor.design.nameOf(it) })
                is DesignEditor.RibDraft -> RibSettings(editor, d)
                is DesignEditor.JointDraft -> JointSettings(editor, d)
                is DesignEditor.EmbossDraft -> {
                    Header("Emboss", Icons.emboss, Palette.modify, if (d.regions.isEmpty()) null else count(d.regions.size, "area", "areas"))
                    Segmented(listOf("Raised", "Sunk"), if (d.sink) 1 else 0) { d.sink = it == 1; editor.draftChanged() }
                    Field(editor, d, "size", "Depth", d.depth, "mm", allowNegative = false) { d.depth = it; editor.draftChanged() }
                    if (d.face == null) Text("Tap the face to put it on", fontSize = 14.sp, color = Palette.muted)
                }
                is DesignEditor.PatchDraft -> {
                    Header("Patch", Icons.patch, Palette.create, if (d.byEdges) count(d.edges.size, "edge", "edges").takeIf { d.edges.isNotEmpty() } else null)
                    Segmented(listOf("Sketch areas", "Fill edges"), if (d.byEdges) 1 else 0) { d.byEdges = it == 1; editor.draftChanged() }
                }
                is DesignEditor.StitchDraft -> Header("Stitch", Icons.patch, Palette.modify, bodiesLabel(d.bodies, "Tap the surfaces"))
                is DesignEditor.ThickenDraft -> {
                    Header("Thicken", Icons.patch, Palette.modify, d.bodies.firstOrNull()?.let { editor.design.nameOf(it) })
                    Field(editor, d, "size", "Thickness", d.thickness, "mm", allowNegative = false) { d.thickness = it; editor.draftChanged() }
                    Toggle("Both sides", d.both) { d.both = it; editor.draftChanged() }
                }
                is DesignEditor.PlaneDraft -> PlaneSettings(editor, d)
                is DesignEditor.AxisDraft -> AxisSettings(editor, d)
                is DesignEditor.PointDraft -> PointSettings(editor, d)
                is DesignEditor.PrimitiveDraft -> PrimitiveSettings(editor, d)
                is DesignEditor.AlignDraft -> AlignSettings(editor, d)
                is DesignEditor.SweepDraft -> SweepSettings(editor, d)
                is DesignEditor.PipeDraft -> PipeSettings(editor, d)
                is DesignEditor.CoilDraft -> CoilSettings(editor, d)
                is DesignEditor.GearDraft -> GearSettings(editor, d)
                is DesignEditor.FastenerDraft -> FastenerSettings(editor, d)
                is DesignEditor.LinkDraft -> LinkSettings(editor, d)
                is DesignEditor.ThreadDraft -> ThreadSettings(editor, d)
                is DesignEditor.LipDraft -> LipSettings(editor, d)
                is DesignEditor.LoftDraft -> LoftSettings(editor, d)
                is DesignEditor.CanvasDraft -> CanvasSettings(editor, d)
                else -> {}
            }
            if (d is DesignEditor.BodyDraft && editor.picksBodies() === d) BodyPicks(editor, d)
            }
            editor.panelProblem()?.let { Text(it, fontSize = 14.sp, color = Palette.orange) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Cancel", Palette.raised, Palette.text, Modifier.weight(1f)) { editor.cancelPanel() }
                Button("Done", Palette.mint, Palette.ink, Modifier.weight(1f)) { editor.confirmPanel() }
            }
        }
    }
}

@Composable
internal fun Header(title: String, icon: ImageVector, tint: Color, picked: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        if (picked != null) {
            Surface(color = Color(0xFF3A2C24), contentColor = Color(0xFFFFB48C), shape = RoundedCornerShape(17.dp)) {
                Text(picked, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

internal fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

@Composable
private fun ExtrudeSettings(editor: DesignEditor, d: DesignEditor.ExtrudeDraft) {
    Header("Extrude", Icons.extrude, Palette.create, when { d.face != null -> "A face"; d.regions.isNotEmpty() -> count(d.regions.size, "area", "areas"); d.sketchId != null && d.thinOn -> "Open line"; else -> null })
    // A sketch of many areas: one tap takes them all.
    val areas = editor.areaCount(d)
    if (areas > 1 && d.regions.size < areas) androidx.compose.material3.TextButton(onClick = { editor.pickAllAreas(d) }) {
        Text("Take all $areas areas", color = Palette.mint, fontSize = 14.sp)
    }
    if (d.sketchId != null) Toggle("As a surface", d.surfaceOn) { d.surfaceOn = it; editor.draftChanged() }
    if (!d.surfaceOn) Segmented(listOf("Distance", "Through all", "Up to"), if (d.throughAll) 1 else if (d.upToOn) 2 else 0) {
        d.throughAll = it == 1
        d.upToOn = it == 2
        // Through all goes through bodies, so it cuts unless told otherwise.
        if (it == 1 && d.operation != Operation.Intersect && editor.allBodies().isNotEmpty()) d.operation = Operation.Cut
        editor.draftChanged()
    }
    if (d.throughAll && !d.surfaceOn) {
        val choice = if (d.direction != DesignEditor.Direction.OneSide) 2 else if (d.backwards) 1 else 0
        Segmented(listOf("Forward", "Back", "Both ways"), choice) {
            d.direction = if (it == 2) DesignEditor.Direction.Symmetric else DesignEditor.Direction.OneSide
            d.backwards = it == 1
            editor.draftChanged()
        }
    } else if (d.upToOn && !d.surfaceOn) {
        // A face tapped in the view is added here as "The face".
        Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.upTo }) {
            d.upTo = d.planes[it].second
            editor.draftChanged()
        }
    } else {
        Segmented(listOf("One side", "Both ways", "Two sides"), d.direction.ordinal) {
            d.direction = DesignEditor.Direction.entries[it]
            editor.draftChanged()
        }
        Field(editor, d, if (d.direction == DesignEditor.Direction.Symmetric) "both" else "forward", if (d.direction == DesignEditor.Direction.TwoSides) "Front" else "Distance", d.distance, "mm", allowNegative = true) {
            d.distance = it
            editor.draftChanged()
        }
        if (d.direction == DesignEditor.Direction.TwoSides) Field(editor, d, "back", "Back", d.other, "mm", allowNegative = true) {
            d.other = it
            editor.draftChanged()
        }
        // The other way is a distance below 0.
        if (d.direction == DesignEditor.Direction.OneSide) Toggle("The other way", d.distance < 0) {
            d.distance = -d.distance
            d.exprs["forward"]?.let { e -> d.exprs["forward"] = if (e.startsWith("-(") && e.endsWith(")")) e.substring(2, e.length - 1) else "-($e)" }
            editor.draftChanged()
        }
    }
    if (!d.surfaceOn) Field(editor, d, "taper", "Taper", d.taperDegrees, "°", allowNegative = true) {
        d.taperDegrees = it
        editor.draftChanged()
    }
    Field(editor, d, "offset", "Start at", d.offset, "mm", allowNegative = true) {
        d.offset = it
        editor.draftChanged()
    }
    // A surface is a new body with no thickness.
    if (d.surfaceOn) return
    Toggle("Thin wall", d.thinOn) { d.thinOn = it; editor.draftChanged() }
    if (d.thinOn) Field(editor, d, "thin", "Wall", d.thin, "mm", allowNegative = false) {
        d.thin = it
        editor.draftChanged()
    }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
    if (d.operation != Operation.NewBody) OnlyRow(editor, d)
}

@Composable
private fun PrimitiveSettings(editor: DesignEditor, d: DesignEditor.PrimitiveDraft) {
    Header(d.kind.name, primitiveIcon(d.kind), Palette.create, null)
    Text("On", fontSize = 13.sp, color = Palette.muted)
    Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.plane }.coerceAtLeast(0)) { d.plane = d.planes[it].second; editor.draftChanged() }
    // Which sizes each shape has, and what they're called.
    val labels = when (d.kind) {
        PrimitiveKind.Box -> listOf("Width", "Depth", "Height")
        PrimitiveKind.Cylinder -> listOf("Diameter", "Height")
        PrimitiveKind.Sphere -> listOf("Diameter")
        PrimitiveKind.Torus -> listOf("Ring", "Tube")
        PrimitiveKind.Cone -> listOf("Base", "Top", "Height")
    }
    labels.forEachIndexed { i, label ->
        val field = listOf("a", "b", "c")[i]
        val value = listOf(d.a, d.b, d.c)[i]
        // A cone's top can be 0, a point.
        Field(editor, d, field, label, value, "mm", allowNegative = d.kind == PrimitiveKind.Cone && i == 1) {
            when (i) {
                0 -> d.a = it
                1 -> d.b = it
                else -> d.c = it
            }
            editor.draftChanged()
        }
    }
    Field(editor, d, "u", "Centre x", d.u, "mm", allowNegative = true) { d.u = it; editor.draftChanged() }
    Field(editor, d, "v", "Centre y", d.v, "mm", allowNegative = true) { d.v = it; editor.draftChanged() }
    // Each shape sits on its plane, on the side it faces.
    Toggle("Grow the other way", d.flip) { d.flip = it; editor.draftChanged() }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
    if (d.operation != Operation.NewBody) OnlyRow(editor, d)
}

/** What a sweep or pipe follows: one of the other sketches, or edges picked in the view. */
@Composable
private fun PathRow(editor: DesignEditor, except: Int?, byEdges: Boolean, sketch: Int?, edges: Int, onPick: (byEdges: Boolean, sketch: Int?) -> Unit) {
    val choices = editor.sketchChoices(except)
    Text("Along", fontSize = 13.sp, color = Palette.muted)
    val labels = choices.map { it.first } + (if (edges > 0) count(edges, "edge", "edges") else "Picked edges")
    val chosen = if (byEdges) choices.size else choices.indexOfFirst { it.second == sketch }
    Segmented(labels, chosen) { i -> if (i == choices.size) onPick(true, sketch) else onPick(false, choices[i].second) }
}

@Composable
private fun SweepSettings(editor: DesignEditor, d: DesignEditor.SweepDraft) {
    Header("Sweep", Icons.sweep, Palette.create, if (d.regions.isEmpty()) null else count(d.regions.size, "area", "areas"))
    PathRow(editor, d.sketchId, d.pathByEdges, d.pathSketch, d.pathEdges.size) { byEdges, sketch ->
        d.pathByEdges = byEdges
        d.pathSketch = sketch
        editor.draftChanged()
    }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
}

@Composable
private fun PipeSettings(editor: DesignEditor, d: DesignEditor.PipeDraft) {
    Header("Pipe", Icons.pipe, Palette.create, null)
    PathRow(editor, null, d.pathByEdges, d.pathSketch, d.pathEdges.size) { byEdges, sketch ->
        d.pathByEdges = byEdges
        d.pathSketch = sketch
        editor.draftChanged()
    }
    Field(editor, d, "diameter", "Diameter", d.diameter, "mm", allowNegative = false) { d.diameter = it; editor.draftChanged() }
    Toggle("Hollow", d.hollow) { d.hollow = it; editor.draftChanged() }
    if (d.hollow) Field(editor, d, "inner", "Inside", d.inner, "mm", allowNegative = false) { d.inner = it; editor.draftChanged() }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
}

@Composable
private fun CoilSettings(editor: DesignEditor, d: DesignEditor.CoilDraft) {
    Header("Coil", Icons.coil, Palette.create, null)
    Text("On", fontSize = 13.sp, color = Palette.muted)
    Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.plane }.coerceAtLeast(0)) { d.plane = d.planes[it].second; editor.draftChanged() }
    Field(editor, d, "diameter", "Diameter", d.diameter, "mm", allowNegative = false) { d.diameter = it; editor.draftChanged() }
    Field(editor, d, "pitch", "Pitch", d.pitch, "mm", allowNegative = false) { d.pitch = it; editor.draftChanged() }
    Field(editor, d, "turns", "Turns", d.turns, "", allowNegative = false) { d.turns = it; editor.draftChanged() }
    Field(editor, d, "section", "Wire", d.section, "mm", allowNegative = false) { d.section = it; editor.draftChanged() }
    Segmented(listOf("Round wire", "Square wire"), if (d.square) 1 else 0) { d.square = it == 1; editor.draftChanged() }
    Field(editor, d, "u", "Centre x", d.u, "mm", allowNegative = true) { d.u = it; editor.draftChanged() }
    Field(editor, d, "v", "Centre y", d.v, "mm", allowNegative = true) { d.v = it; editor.draftChanged() }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
}

@Composable
private fun GearSettings(editor: DesignEditor, d: DesignEditor.GearDraft) {
    Header("Gear", Icons.gear, Palette.create, null)
    val others = d.others
    if (others.isNotEmpty()) {
        Text("Beside", fontSize = 13.sp, color = Palette.muted)
        Segmented(listOf("Nothing") + others.map { it.name }, others.indexOfFirst { it.id == d.meshWith } + 1) {
            d.meshWith = if (it == 0) null else others[it - 1].id
            editor.draftChanged()
        }
    }
    val beside = d.meshWith != null
    if (!beside) {
        Text("On", fontSize = 13.sp, color = Palette.muted)
        Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.plane }.coerceAtLeast(0)) { d.plane = d.planes[it].second; editor.draftChanged() }
        Field(editor, d, "module", "Module", d.module, "mm", allowNegative = false) { d.module = it; editor.draftChanged() }
    }
    Field(editor, d, "teeth", "Teeth", d.teeth, "", allowNegative = false) { d.teeth = kotlin.math.round(it); editor.draftChanged() }
    Field(editor, d, "thickness", "Thickness", d.thickness, "mm", allowNegative = false) { d.thickness = it; editor.draftChanged() }
    if (!beside) {
        Segmented(listOf("Straight", "Helical", "Herringbone"), d.kind) { d.kind = it; editor.draftChanged() }
        if (d.kind != 0) Field(editor, d, "helix", "Helix angle", d.helixDegrees, "°", allowNegative = true) { d.helixDegrees = it; editor.draftChanged() }
        Field(editor, d, "pressure", "Pressure angle", d.pressureDegrees, "°", allowNegative = false) { d.pressureDegrees = it; editor.draftChanged() }
    }
    Field(editor, d, "bore", "Bore", d.bore, "mm", allowNegative = false) { d.bore = it; editor.draftChanged() }
    Field(editor, d, "clearance", "Clearance", d.clearance, "mm", allowNegative = false) { d.clearance = it; editor.draftChanged() }
    if (beside) {
        Field(editor, d, "around", "Round from it", d.aroundDegrees, "°", allowNegative = true) { d.aroundDegrees = it; editor.draftChanged() }
    } else {
        Field(editor, d, "u", "Centre x", d.u, "mm", allowNegative = true) { d.u = it; editor.draftChanged() }
        Field(editor, d, "v", "Centre y", d.v, "mm", allowNegative = true) { d.v = it; editor.draftChanged() }
        Field(editor, d, "turn", "Turn", d.turnDegrees, "°", allowNegative = true) { d.turnDegrees = it; editor.draftChanged() }
    }
    d.placed()?.let { g ->
        val pitch = g.module * g.teeth
        var text = "Pitch circle ${short(pitch)} mm, ${short(pitch + 2 * g.module)} mm across the tips"
        others.firstOrNull { it.id == d.meshWith }?.let { o -> text += ", centres ${short((pitch + g.module * o.teeth) / 2)} mm apart" }
        Text(text, fontSize = 13.sp, color = Palette.muted)
    }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
}

private fun short(v: Double): String = tenths(v).removeSuffix(".0")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SnapFitSettings(editor: DesignEditor, d: DesignEditor.SnapFitDraft) {
    Header("Snap fit", Icons.snapFit, Palette.modify, null)
    val sketches = editor.snapSketches()
    if (sketches.size > 1) Segmented(sketches.map { it.name }, sketches.indexOfFirst { it.id == d.sketchId }.coerceAtLeast(0)) {
        editor.pickSnapSketch(d, sketches[it].id)
    }
    Field(editor, d, "length", "Length", d.length, "mm", allowNegative = false) { d.length = it; editor.draftChanged() }
    Field(editor, d, "width", "Width", d.width, "mm", allowNegative = false) { d.width = it; editor.draftChanged() }
    Field(editor, d, "thickness", "Thickness", d.thickness, "mm", allowNegative = false) { d.thickness = it; editor.draftChanged() }
    Field(editor, d, "overhang", "Overhang", d.overhang, "mm", allowNegative = false) { d.overhang = it; editor.draftChanged() }
    Field(editor, d, "catchHeight", "Hook", d.catchHeight, "mm", allowNegative = false) { d.catchHeight = it; editor.draftChanged() }
    val others = editor.allBodies().map { it.label }.filter { it != d.base }
    if (others.isEmpty()) return
    Text("Catch in", fontSize = 13.sp, color = Palette.muted)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in listOf<String?>(null) + others) Surface(
            onClick = { d.catchIn = l; editor.draftChanged() },
            shape = RoundedCornerShape(12.dp),
            color = if (d.catchIn == l) Palette.line else Palette.ground,
            contentColor = Palette.text,
        ) { Text(l?.let { editor.bodyTitle(it) } ?: "Nothing", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 13.sp) }
    }
    if (d.catchIn != null) Field(editor, d, "gap", "Gap", d.gap, "mm", allowNegative = false) { d.gap = it; editor.draftChanged() }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LipSettings(editor: DesignEditor, d: DesignEditor.LipDraft) {
    Header("Lip", Icons.lip, Palette.modify, if (d.face != null) "1 face" else null)
    Field(editor, d, "width", "Width", d.width, "mm", allowNegative = false) { d.width = it; editor.draftChanged() }
    Field(editor, d, "height", "Height", d.height, "mm", allowNegative = false) { d.height = it; editor.draftChanged() }
    // The body the groove goes in, usually the lid sitting on the rim.
    val others = editor.allBodies().map { it.label }.filter { it != d.base }
    if (others.isEmpty()) return
    Text("Groove in", fontSize = 13.sp, color = Palette.muted)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in listOf<String?>(null) + others) Surface(
            onClick = { d.lid = l; editor.draftChanged() },
            shape = RoundedCornerShape(12.dp),
            color = if (d.lid == l) Palette.line else Palette.ground,
            contentColor = Palette.text,
        ) { Text(l?.let { editor.bodyTitle(it) } ?: "Nothing", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 13.sp) }
    }
    if (d.lid != null) Field(editor, d, "gap", "Gap", d.gap, "mm", allowNegative = false) { d.gap = it; editor.draftChanged() }
}

/** A size to a tenth of a mm, as it's worth printing. */
private fun tenths(v: Double): String = (kotlin.math.round(v * 10) / 10).toString()

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThreadSettings(editor: DesignEditor, d: DesignEditor.ThreadDraft) {
    Header("Thread", Icons.thread, Palette.modify, if (d.face != null) "1 face" else null)
    Segmented(listOf("Metric", "Inch"), if (d.inches) 1 else 0) {
        d.inches = it == 1
        if (d.across > 0) { d.pitch = ThreadSizes.fitting(d.across, d.inches).pitch; d.exprs.remove("pitch") }
        editor.draftChanged()
    }
    // Picking a size sets its pitch.
    SizeChips((if (d.inches) ThreadSizes.inch else ThreadSizes.metric).map { it.name }, (if (d.inches) ThreadSizes.inch else ThreadSizes.metric).indexOfFirst { it.pitch == d.pitch }) { i ->
        d.pitch = (if (d.inches) ThreadSizes.inch else ThreadSizes.metric)[i].pitch
        d.exprs.remove("pitch")
        editor.draftChanged()
    }
    Field(editor, d, "pitch", "Pitch", d.pitch, "mm", allowNegative = false) { d.pitch = it; editor.draftChanged() }
    Toggle("Only draw it", d.symbol) { d.symbol = it; editor.draftChanged() }
    if (!d.symbol) Field(editor, d, "clearance", "Clearance", d.clearance, "mm", allowNegative = false) { d.clearance = it; editor.draftChanged() }
    // ISO: a nut's hole is made 1.0825 pitches smaller than its bolt.
    if (d.across > 0) Text(
        if (d.hole) "Takes a bolt ${tenths(d.across + 1.0825 * d.pitch)} across" else "Make its nut's hole ${tenths(d.across - 1.0825 * d.pitch)} across",
        fontSize = 12.sp, color = Palette.muted,
    )
}

@Composable
private fun LinkSettings(editor: DesignEditor, d: DesignEditor.LinkDraft) {
    Header(d.editing.component, Icons.insertDesign, Palette.create, null)
    Text("From ${d.editing.file}", fontSize = 13.sp, color = Palette.muted)
    Field(editor, d, "dx", "Move x", d.dx, "mm", allowNegative = true) { d.dx = it; editor.draftChanged() }
    Field(editor, d, "dy", "Move y", d.dy, "mm", allowNegative = true) { d.dy = it; editor.draftChanged() }
    Field(editor, d, "dz", "Move z", d.dz, "mm", allowNegative = true) { d.dz = it; editor.draftChanged() }
}

@Composable
private fun FastenerSettings(editor: DesignEditor, d: DesignEditor.FastenerDraft) {
    Header("Fastener", Icons.fastener, Palette.modify, if (d.hole != null) "In a hole" else null)
    val kinds = com.rm.parrotmetric.design.FastenerKind.entries
    SizeChips(kinds.map { it.label }, d.kind.ordinal) { d.kind = kinds[it]; editor.draftChanged() }
    Segmented(listOf("Metric", "Inch"), if (d.inches) 1 else 0) {
        d.inches = it == 1
        val sizes = if (d.inches) com.rm.parrotmetric.design.Fasteners.inch else com.rm.parrotmetric.design.Fasteners.metric
        val now = com.rm.parrotmetric.design.Fasteners.size(d.size)?.d ?: 3.0
        d.size = sizes.minBy { s -> kotlin.math.abs(s.d - now) }.name
        editor.draftChanged()
    }
    val sizes = if (d.inches) com.rm.parrotmetric.design.Fasteners.inch else com.rm.parrotmetric.design.Fasteners.metric
    SizeChips(sizes.map { it.name }, sizes.indexOfFirst { it.name == d.size }) { d.size = sizes[it].name; editor.draftChanged() }
    if (d.kind.screw) Field(editor, d, "length", "Length", d.length, "mm", allowNegative = false) { d.length = it; editor.draftChanged() }
    if (d.hole != null) {
        Toggle("From the other end", d.otherEnd) { d.otherEnd = it; editor.draftChanged() }
    } else {
        Text("On", fontSize = 13.sp, color = Palette.muted)
        Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.plane }.coerceAtLeast(0)) { d.plane = d.planes[it].second; editor.draftChanged() }
        Field(editor, d, "u", "Centre x", d.u, "mm", allowNegative = true) { d.u = it; editor.draftChanged() }
        Field(editor, d, "v", "Centre y", d.v, "mm", allowNegative = true) { d.v = it; editor.draftChanged() }
    }
    if (d.kind != com.rm.parrotmetric.design.FastenerKind.Washer) Toggle("Model the thread", d.modelled) { d.modelled = it; editor.draftChanged() }
    Field(editor, d, "clearance", "Clearance", d.clearance, "mm", allowNegative = false) { d.clearance = it; editor.draftChanged() }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
}

/** A row of sizes to pick from, [picked] highlighted. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SizeChips(names: List<String>, picked: Int, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, name ->
            Surface(
                onClick = { onPick(i) },
                shape = RoundedCornerShape(12.dp),
                color = if (i == picked) Palette.line else Palette.ground,
                contentColor = Palette.text,
            ) { Text(name, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun LoftSettings(editor: DesignEditor, d: DesignEditor.LoftDraft) {
    Header("Loft", Icons.loft, Palette.create, if (d.sections.isEmpty()) null else count(d.sections.size, "area", "areas"))
    Toggle("Straight between them", d.ruled) { d.ruled = it; editor.draftChanged() }
    Field(editor, d, "twist", "Twist", d.twistDegrees, "°", allowNegative = true) { d.twistDegrees = it; editor.draftChanged() }
    Toggle("Follow a guide", d.guideOn) { d.guideOn = it; editor.draftChanged() }
    if (d.guideOn) PathRow(editor, null, d.guideByEdges, d.guideSketch, d.guideEdges.size) { byEdges, sketch ->
        d.guideByEdges = byEdges
        d.guideSketch = sketch
        editor.draftChanged()
    }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
}

@Composable
private fun CanvasSettings(editor: DesignEditor, d: DesignEditor.CanvasDraft) {
    Header("Canvas", Icons.canvas, Palette.construct, null)
    Text("On", fontSize = 13.sp, color = Palette.muted)
    Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.plane }.coerceAtLeast(0)) { d.plane = d.planes[it].second; editor.draftChanged() }
    Field(editor, d, "width", "Width", d.width, "mm", allowNegative = false) { d.width = it; editor.draftChanged() }
    Field(editor, d, "u", "Centre x", d.u, "mm", allowNegative = true) { d.u = it; editor.draftChanged() }
    Field(editor, d, "v", "Centre y", d.v, "mm", allowNegative = true) { d.v = it; editor.draftChanged() }
    Field(editor, d, "angle", "Turned", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
    NumberRow("Shown", d.opacityPercent, "%", allowNegative = false) { d.opacityPercent = it.coerceIn(5.0, 100.0); editor.draftChanged() }
}

internal fun primitiveIcon(kind: PrimitiveKind) = when (kind) {
    PrimitiveKind.Box -> Icons.box
    PrimitiveKind.Cylinder -> Icons.cylinder
    PrimitiveKind.Sphere -> Icons.sphere
    PrimitiveKind.Torus -> Icons.torus
    PrimitiveKind.Cone -> Icons.cone
}

@Composable
private fun RevolveSettings(editor: DesignEditor, d: DesignEditor.RevolveDraft) {
    Header("Revolve", Icons.revolve, Palette.create, if (d.regions.isEmpty()) null else count(d.regions.size, "area", "areas"))
    // The sketch's own axes, and any construction lines drawn in it.
    val sketch = d.sketchId?.let { editor.design.feature(it) } as? SketchFeature
    val lines = sketch?.sketch?.curves?.filter { it.construction && it is Line }.orEmpty()
    val axes = listOf<AxisRef>(AxisRef.SketchY, AxisRef.SketchX) + lines.map { AxisRef.SketchLine(it.id) }
    val labels = listOf("Y axis", "X axis") + lines.indices.map { if (lines.size == 1) "Its line" else "Line ${it + 1}" }
    Segmented(labels, axes.indexOf(d.axis).coerceAtLeast(0)) {
        d.axis = axes[it]
        editor.draftChanged()
    }
    Field(editor, d, "angle", "Angle", d.degrees, "°", allowNegative = true) {
        d.degrees = it
        editor.draftChanged()
    }
    if (d.sketchId != null) Toggle("As a surface", d.surfaceOn) { d.surfaceOn = it; editor.draftChanged() }
    if (d.surfaceOn) return
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
    if (d.operation != Operation.NewBody) OnlyRow(editor, d)
}

@Composable
private fun EdgeSettings(editor: DesignEditor, d: DesignEditor.EdgeDraft) {
    Header(
        if (d.chamfer) "Chamfer" else "Fillet",
        if (d.chamfer) Icons.chamfer else Icons.fillet,
        Palette.modify,
        if (d.edges.isEmpty()) null else count(d.edges.size, "edge", "edges"),
    )
    if (d.chamfer) Segmented(listOf("Equal", "Two distances", "With angle"), d.kind.ordinal) {
        d.kind = com.rm.parrotmetric.design.ChamferKind.entries[it]
        d.second = if (d.kind == com.rm.parrotmetric.design.ChamferKind.DistanceAngle) 45.0 else d.size
        d.exprs.remove("second")
        editor.draftChanged()
    }
    if (!d.chamfer) Segmented(listOf("Radius", "Start to end", "Across"), d.filletKind.ordinal) {
        d.filletKind = com.rm.parrotmetric.design.FilletKind.entries[it]
        if (d.filletKind == com.rm.parrotmetric.design.FilletKind.Variable) { d.second = d.size * 2; d.exprs.remove("second") }
        editor.draftChanged()
    }
    val sizeLabel = when {
        d.chamfer -> "Distance"
        d.filletKind == com.rm.parrotmetric.design.FilletKind.Variable -> "Start"
        d.filletKind == com.rm.parrotmetric.design.FilletKind.Chord -> "Across"
        else -> "Radius"
    }
    Field(editor, d, "size", sizeLabel, d.size, "mm", allowNegative = false) {
        d.size = it
        editor.draftChanged()
    }
    if (!d.chamfer && d.filletKind == com.rm.parrotmetric.design.FilletKind.Variable) {
        Field(editor, d, "second", "End", d.second, "mm", allowNegative = false) { d.second = it; editor.draftChanged() }
    }
    if (d.chamfer && d.kind != com.rm.parrotmetric.design.ChamferKind.Equal) {
        val angle = d.kind == com.rm.parrotmetric.design.ChamferKind.DistanceAngle
        Field(editor, d, "second", if (angle) "Angle" else "Other side", d.second, if (angle) "°" else "mm", allowNegative = false) {
            d.second = it
            editor.draftChanged()
        }
        Toggle("Swap sides", d.flip) { d.flip = it; editor.draftChanged() }
    }
}

@Composable
private fun OperationRow(op: Operation, onPick: (Operation) -> Unit) {
    Segmented(listOf("New body", "Join", "Cut", "Intersect"), op.ordinal) { onPick(Operation.entries[it]) }
}

/** The bodies by name, to pick ones that are hidden or hard to tap. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BodyPicks(editor: DesignEditor, d: DesignEditor.BodyDraft) {
    val labels = editor.allBodies().map { it.label }
    if (labels.size < 2) return
    Text("Bodies", fontSize = 13.sp, color = Palette.muted)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in labels) Surface(
            onClick = { editor.toggleBody(l) },
            shape = RoundedCornerShape(12.dp),
            color = if (l in d.bodies) Palette.line else Palette.ground,
            contentColor = Palette.text,
        ) { Text(editor.bodyTitle(l), Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 13.sp) }
    }
}

/** Which bodies a join, cut or intersect changes: any it reaches, or the ones picked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun OnlyRow(editor: DesignEditor, d: DesignEditor.FeatureDraft) {
    val labels = (editor.allBodies().map { it.label } + d.only).distinct()
    if (labels.isEmpty()) return
    Text("Changes", fontSize = 13.sp, color = Palette.muted)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable
        fun pill(text: String, on: Boolean, onClick: () -> Unit) = Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = if (on) Palette.line else Palette.ground,
            contentColor = Palette.text,
        ) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 13.sp) }
        pill("Any it reaches", d.only.isEmpty()) { d.only = emptyList(); editor.draftChanged() }
        for (l in labels) pill(editor.bodyTitle(l), l in d.only) {
            d.only = if (l in d.only) d.only - l else d.only + l
            editor.draftChanged()
        }
    }
}

/** A number field tied to a draft's field: what's typed with parameter names in it is kept as an expression. */
@Composable
internal fun Field(
    editor: DesignEditor, d: DesignEditor.FeatureDraft, key: String, label: String, value: Double, unit: String,
    allowNegative: Boolean, onChange: (Double) -> Unit,
) {
    NumberRow(label, value, unit, allowNegative, d.exprs[key], editor.names()) { v, text ->
        if (text != null && com.rm.parrotmetric.sketch.Expression.usesNames(text)) d.exprs[key] = text else d.exprs.remove(key)
        onChange(v)
    }
}

/** A labelled number field that takes sums and units, applied on Done from the keyboard or when it loses focus. */
@Composable
fun NumberRow(label: String, value: Double, unit: String, allowNegative: Boolean, onDone: (() -> Unit)? = null, onChange: (Double) -> Unit) =
    NumberRow(label, value, unit, allowNegative, null, emptyMap(), onDone) { v, _ -> onChange(v) }

/** As above, showing [expression] when there is one and reading parameter [names]. */
@Composable
fun NumberRow(
    label: String, value: Double, unit: String, allowNegative: Boolean, expression: String?, names: Map<String, Double>,
    /** What Enter does once the number is in, where the field isn't in a panel. */
    onDone: (() -> Unit)? = null,
    onChange: (Double, String?) -> Unit,
) {
    fun text(v: Double): String {
        val r = round(v * 1000) / 1000
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }
    var field by remember(value, expression) { mutableStateOf(TextFieldValue(expression ?: text(value))) }
    var bad by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Started by a typed digit: keep what was typed in place of selecting it all.
    var started by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val chain = com.rm.parrotmetric.ui.LocalFieldChain.current
    // Focus is taken just after the key that started the field is done with.
    var starts by remember { mutableStateOf(0) }
    LaunchedEffect(starts) { if (starts > 0) focus.requestFocus() }
    // The field's text is remembered afresh when its value changes, so the chain's start sets whichever is current.
    val setField = androidx.compose.runtime.rememberUpdatedState<(TextFieldValue) -> Unit> { field = it }
    val link = remember {
        com.rm.parrotmetric.ui.FieldChain.Field(focus) { c ->
            started = true
            setField.value(TextFieldValue(c.toString(), TextRange(1)))
            starts++
        }
    }
    DisposableEffect(chain) {
        chain?.add(link)
        onDispose { chain?.remove(link) }
    }
    // Select it all once focused, after the tap has placed the cursor, so typing replaces it.
    LaunchedEffect(focused) {
        if (focused && !started) {
            delay(30)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
        if (!focused) started = false
    }
    fun apply() {
        val v = Expression.evaluate(field.text, names)
        bad = v == null || (!allowNegative && v <= 0)
        if (!bad && (v != value || field.text != (expression ?: text(value)))) onChange(v!!, field.text)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), fontSize = 14.sp, color = Palette.muted)
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                .background(if (bad) Color(0xFF3A2C24) else Palette.ground).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.rm.parrotmetric.ui.NumberField(
                field,
                onValueChange = { field = it },
                modifier = Modifier.weight(1f),
                fieldModifier = Modifier.typing().focusRequester(focus)
                    .onGloballyPositioned { val at = it.positionInRoot(); link.x = at.x; link.y = at.y }
                    .onFocusChanged {
                        if (focused && !it.isFocused) apply()
                        focused = it.isFocused
                        link.focused = it.isFocused
                    },
                textStyle = TextStyle(color = Palette.text, fontSize = 19.sp, fontFamily = FontFamily.Monospace),
                onDone = { apply(); if (!bad) onDone?.invoke() },
            )
            Text(unit, fontSize = 14.sp, color = Palette.muted)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    // More than fit across: they wrap onto more lines.
    if (options.size > 4) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, label ->
                Surface(
                    onClick = { onSelect(i) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (i == selected) Palette.line else Palette.ground,
                    contentColor = if (i == selected) Palette.text else Palette.muted,
                ) { Text(label, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 13.sp, maxLines = 1) }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.ground).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(11.dp),
                color = if (i == selected) Palette.line else Color.Transparent,
                contentColor = if (i == selected) Palette.text else Palette.muted,
            ) {
                Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Button(label: String, colour: Color, text: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(50.dp), shape = RoundedCornerShape(18.dp), color = colour, contentColor = text) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = Palette.text)
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.mint, checkedThumbColor = Palette.ink),
        )
    }
}

private fun bodiesLabel(bodies: List<String>, emptyMeans: String) = when (bodies.size) {
    0 -> emptyMeans
    1 -> bodies[0]
    else -> "${bodies.size} bodies"
}

@Composable
internal fun FaceSettings(editor: DesignEditor, d: DesignEditor.FaceDraft) {
    val icon = when (d.tool) {
        DesignEditor.FaceTool.Shell -> Icons.shell
        DesignEditor.FaceTool.Draft -> Icons.draft
        DesignEditor.FaceTool.PressPull -> Icons.pressPull
        DesignEditor.FaceTool.Delete -> Icons.deleteFace
        DesignEditor.FaceTool.OffsetSurface -> Icons.patch
    }
    Header(d.tool.title, icon, Palette.modify, if (d.faces.isEmpty()) null else count(d.faces.size, "face", "faces"))
    when (d.tool) {
        DesignEditor.FaceTool.Draft -> {
            Segmented(listOf("Faces to tilt", "Pivot face"), if (d.pickingPivot) 1 else 0) { d.pickingPivot = it == 1 }
            Field(editor, d, "size", "Angle", d.size, "°", allowNegative = true) { d.size = it; editor.draftChanged() }
        }
        DesignEditor.FaceTool.Shell -> Field(editor, d, "size", "Walls", d.size, "mm", allowNegative = false) { d.size = it; editor.draftChanged() }
        DesignEditor.FaceTool.PressPull -> {
            Field(editor, d, "size", "Distance", d.size, "mm", allowNegative = true) { d.size = it; editor.draftChanged() }
            editor.pressPullNote(d)?.let { Text(it, fontSize = 12.sp, color = Palette.muted) }
        }
        DesignEditor.FaceTool.Delete -> {}
        DesignEditor.FaceTool.OffsetSurface -> Field(editor, d, "distance", "Distance", d.size, "mm", allowNegative = true) { d.size = it; editor.draftChanged() }
    }
}

@Composable
private fun JointSettings(editor: DesignEditor, d: DesignEditor.JointDraft) {
    Header("Joint", Icons.joint, Palette.modify, d.moving)
    Segmented(listOf("Rigid", "Turn", "Slide", "Both", "Planar", "Ball"), d.kind.ordinal) {
        d.kind = com.rm.parrotmetric.design.JointKind.entries[it]
        d.value = 0.0
        d.value2 = 0.0
        d.value3 = 0.0
        d.exprs.remove("value")
        d.exprs.remove("value2")
        d.exprs.remove("value3")
        editor.draftChanged()
    }
    val ball = d.kind == com.rm.parrotmetric.design.JointKind.Ball
    // How far it's moved first, as that's what's changed most.
    if (d.kind != com.rm.parrotmetric.design.JointKind.Rigid) {
        val turnLabel = if (ball) "Turned round x" else if (d.turns) "Turned" else "Slid"
        // As far as its limits let it go, typed or dragged.
        val limits = when {
            d.turns && d.turnLimits -> minOf(d.turnMin, d.turnMax)..maxOf(d.turnMin, d.turnMax)
            !d.turns && d.slideLimits -> minOf(d.slideMin, d.slideMax)..maxOf(d.slideMin, d.slideMax)
            else -> null
        }
        Field(editor, d, "value", turnLabel, d.value, if (d.turns) "°" else "mm", allowNegative = true) {
            d.value = limits?.let { l -> it.coerceIn(l) } ?: it
            editor.draftChanged()
        }
        val range = limits?.let { it.start.toFloat()..it.endInclusive.toFloat() } ?: if (d.turns) -180f..180f else -100f..100f
        androidx.compose.material3.Slider(
            value = d.value.toFloat().coerceIn(range),
            onValueChange = { d.value = kotlin.math.round(it).toDouble(); d.exprs.remove("value"); editor.draftChanged() },
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Palette.mint, activeTrackColor = Palette.mint),
        )
        when (d.kind) {
            com.rm.parrotmetric.design.JointKind.TurnSlide ->
                Field(editor, d, "value2", "Slid", d.value2, "mm", allowNegative = true) { d.value2 = it; editor.draftChanged() }
            com.rm.parrotmetric.design.JointKind.Planar -> {
                Field(editor, d, "value2", "Slid one way", d.value2, "mm", allowNegative = true) { d.value2 = it; editor.draftChanged() }
                Field(editor, d, "value3", "Slid the other way", d.value3, "mm", allowNegative = true) { d.value3 = it; editor.draftChanged() }
            }
            com.rm.parrotmetric.design.JointKind.Ball -> {
                Field(editor, d, "value2", "Turned round y", d.value2, "°", allowNegative = true) { d.value2 = it; editor.draftChanged() }
                Field(editor, d, "value3", "Turned round z", d.value3, "°", allowNegative = true) { d.value3 = it; editor.draftChanged() }
            }
            else -> {}
        }
        if (d.turns) {
            Toggle("Turn limits", d.turnLimits) { d.turnLimits = it; editor.draftChanged() }
            if (d.turnLimits) {
                NumberRow("Lowest", d.turnMin, "°", allowNegative = true) { d.turnMin = it; editor.draftChanged() }
                NumberRow("Highest", d.turnMax, "°", allowNegative = true) { d.turnMax = it; editor.draftChanged() }
            }
        }
        if (d.slides) {
            Toggle("Slide limits", d.slideLimits) { d.slideLimits = it; editor.draftChanged() }
            if (d.slideLimits) {
                NumberRow("Lowest", d.slideMin, "mm", allowNegative = true) { d.slideMin = it; editor.draftChanged() }
                NumberRow("Highest", d.slideMax, "mm", allowNegative = true) { d.slideMax = it; editor.draftChanged() }
            }
        }
        val leaders = d.leaders()
        if (d.kind == com.rm.parrotmetric.design.JointKind.Turn && leaders.isNotEmpty()) {
            Text("Geared to", fontSize = 13.sp, color = Palette.muted)
            val ids = listOf<Int?>(null) + leaders.map { it.second }
            Segmented(listOf("None") + leaders.map { it.first }, ids.indexOf(d.linkedTo).coerceAtLeast(0)) { d.linkedTo = ids[it]; editor.draftChanged() }
            if (d.linkedTo != null) {
                Toggle("Ratio from the teeth", d.ratio == null) { d.ratio = if (it) null else -1.0; editor.draftChanged() }
                d.ratio?.let { r -> NumberRow("Ratio", r, "", allowNegative = true) { d.ratio = it; editor.draftChanged() } }
            }
        }
    }
    val comps = editor.components()
    Text("Moving", fontSize = 13.sp, color = Palette.muted)
    Segmented(comps, comps.indexOf(d.moving).coerceAtLeast(0)) { d.moving = comps[it]; editor.draftChanged() }
    val others = listOf<String?>(null) + comps.filter { it != d.moving }
    Text("Joined to", fontSize = 13.sp, color = Palette.muted)
    Segmented(others.map { it ?: "In place" }, others.indexOf(d.fixed).coerceAtLeast(0)) { d.fixed = others[it]; editor.draftChanged() }
    if (d.kind == com.rm.parrotmetric.design.JointKind.Rigid) return
    if (d.edge != null || d.face != null) {
        val where = when (d.kind) {
            com.rm.parrotmetric.design.JointKind.Planar -> "On the face picked"
            com.rm.parrotmetric.design.JointKind.Ball -> if (d.edge != null) "About the edge picked" else "About the face picked"
            else -> if (d.edge != null) "Round the edge picked" else "Round the face picked"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(where, Modifier.weight(1f), fontSize = 14.sp, color = Palette.text)
            androidx.compose.material3.TextButton(onClick = { d.edge = null; d.face = null; editor.draftChanged() }) {
                Text(if (ball) "Use the origin" else "Use an axis", color = Palette.mint)
            }
        }
    } else if (!ball) AxisRow(if (d.kind == com.rm.parrotmetric.design.JointKind.Planar) "Square to" else "Round", d.axis, false) { d.axis = it!!; editor.draftChanged() }
}

@Composable
private fun RibSettings(editor: DesignEditor, d: DesignEditor.RibDraft) {
    Header(if (d.web) "Web" else "Rib", Icons.rib, Palette.modify, null)
    Segmented(listOf("Rib", "Web"), if (d.web) 1 else 0) { d.web = it == 1; editor.draftChanged() }
    val sketches = editor.lineSketches()
    if (sketches.size > 1) Segmented(sketches.takeLast(4).map { it.first }, sketches.takeLast(4).indexOfFirst { it.second == d.sketchId }.coerceAtLeast(0)) {
        d.sketchId = sketches.takeLast(4)[it].second
        editor.draftChanged()
    }
    Field(editor, d, "size", "Thickness", d.thickness, "mm", allowNegative = false) { d.thickness = it; editor.draftChanged() }
    Toggle("Grow the other way", d.flip) { d.flip = it; editor.draftChanged() }
}

@Composable
private fun MeshEditSettings(editor: DesignEditor, d: DesignEditor.MeshEditDraft) {
    Header(d.kind.name, Icons.meshEdit, Palette.modify, d.bodies.firstOrNull()?.let { editor.design.nameOf(it) })
    Segmented(listOf("Reduce", "Remesh", "Smooth", "Hollow"), d.kind.ordinal) {
        d.kind = com.rm.parrotmetric.design.MeshEdit.entries[it]
        d.size = d.defaultSize(d.kind)
        d.exprs.remove("size")
        editor.draftChanged()
    }
    when (d.kind) {
        com.rm.parrotmetric.design.MeshEdit.Reduce -> Field(editor, d, "size", "Allowed error", d.size, "mm", allowNegative = false) { d.size = it; editor.draftChanged() }
        com.rm.parrotmetric.design.MeshEdit.Remesh -> Field(editor, d, "size", "Longest edge", d.size, "mm", allowNegative = false) { d.size = it; editor.draftChanged() }
        com.rm.parrotmetric.design.MeshEdit.Smooth -> {
            Field(editor, d, "steps", "Steps", d.steps, "", allowNegative = false) { d.steps = it; editor.draftChanged() }
            Field(editor, d, "size", "Sharp over", d.size, "°", allowNegative = false) { d.size = it; editor.draftChanged() }
        }
        com.rm.parrotmetric.design.MeshEdit.Hollow -> Field(editor, d, "size", "Wall", d.size, "mm", allowNegative = false) { d.size = it; editor.draftChanged() }
    }
    Text("${editor.triangles} triangles shown", fontSize = 13.sp, color = Palette.muted)
}

@Composable
private fun EraseSettings(editor: DesignEditor, d: DesignEditor.EraseDraft) {
    val n = d.spots.size / 4
    Header("Erase and fill", Icons.erase, Palette.modify, if (n == 0) null else count(n, "spot", "spots"))
    NumberRow("Size", d.radius * 2, "mm", false) { d.radius = it / 2 }
    if (n > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { d.spots = d.spots.dropLast(4); editor.draftChanged() }) { Text("Take back the last", color = Palette.mint) }
    }
}

@Composable
internal fun HoleSettings(editor: DesignEditor, d: DesignEditor.HoleDraft) {
    Header("Hole", Icons.hole, Palette.modify, null)
    val sketches = editor.holeSketches()
    if (sketches.size > 1) Segmented(sketches.take(4).map { it.name }, sketches.indexOfFirst { it.id == d.sketchId }.coerceAtLeast(0)) {
        d.sketchId = sketches[it].id
        editor.draftChanged()
    }
    Segmented(listOf("Simple", "Counterbore", "Countersink"), d.kind.ordinal) {
        d.kind = com.rm.parrotmetric.design.HoleKind.entries[it]
        // A screw head's room follows the size picked.
        d.usePreset(d.fit, d.size)
        editor.draftChanged()
    }
    val fits = listOf(null, com.rm.parrotmetric.design.HoleFit.Insert, com.rm.parrotmetric.design.HoleFit.SelfTap, com.rm.parrotmetric.design.HoleFit.Clearance)
    Segmented(listOf("Any size", "Insert", "Self-tap", "Clearance"), fits.indexOf(d.fit)) { d.usePreset(fits[it], d.size); editor.draftChanged() }
    if (d.fit != null) {
        val sizes = com.rm.parrotmetric.design.HolePresets.sizes
        Segmented(sizes, sizes.indexOf(d.size).coerceAtLeast(0)) { d.usePreset(d.fit, sizes[it]); editor.draftChanged() }
    }
    Field(editor, d, "diameter", "Diameter", d.diameter, "mm", allowNegative = false) { d.diameter = it; d.fit = null; editor.draftChanged() }
    Toggle("All the way through", d.through) { d.through = it; editor.draftChanged() }
    if (!d.through) Field(editor, d, "depth", "Depth", d.depth, "mm", allowNegative = false) { d.depth = it; editor.draftChanged() }
    if (d.kind != com.rm.parrotmetric.design.HoleKind.Simple) Field(editor, d, "topDiameter", "Top", d.topDiameter, "mm", allowNegative = false) { d.topDiameter = it; editor.draftChanged() }
    if (d.kind == com.rm.parrotmetric.design.HoleKind.Counterbore) Field(editor, d, "topDepth", "Top depth", d.topDepth, "mm", allowNegative = false) { d.topDepth = it; editor.draftChanged() }
    OnlyRow(editor, d)
}

@Composable
private fun PlaneRow(d: DesignEditor.BodyDraft, current: com.rm.parrotmetric.design.PlaneRef, set: (com.rm.parrotmetric.design.PlaneRef) -> Unit) {
    Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == current }.coerceAtLeast(0)) { set(d.planes[it].second) }
}

@Composable
private fun AxisRow(label: String, axis: com.rm.parrotmetric.design.Axis3?, allowNone: Boolean, set: (com.rm.parrotmetric.design.Axis3?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), fontSize = 14.sp, color = Palette.muted)
        val options = (if (allowNone) listOf<com.rm.parrotmetric.design.Axis3?>(null) else emptyList()) + com.rm.parrotmetric.design.Axis3.entries
        Box(Modifier.weight(1f)) {
            Segmented(options.map { it?.name ?: "None" }, options.indexOf(axis).coerceAtLeast(0)) { set(options[it]) }
        }
    }
}

@Composable
internal fun MirrorSettings(editor: DesignEditor, d: DesignEditor.MirrorDraft) {
    Header("Mirror", Icons.mirror, Palette.modify, if (d.byFeatures) featuresLabel(d.features) else bodiesLabel(d.bodies, "All bodies"))
    FeatureChoice(editor, d.id, d.byFeatures, d.features) { by, features -> d.byFeatures = by; d.features = features; editor.draftChanged() }
    PlaneRow(d, d.plane) { d.plane = it; editor.draftChanged() }
    if (!d.byFeatures) Toggle("Join to the original", d.join) { d.join = it; editor.draftChanged() }
}

private fun featuresLabel(features: List<Int>) = if (features.isEmpty()) null else count(features.size, "feature", "features")

/** Bodies or features to copy, and which features when it's those. */
@Composable
private fun FeatureChoice(editor: DesignEditor, id: Int, byFeatures: Boolean, features: List<Int>, set: (Boolean, List<Int>) -> Unit) {
    Segmented(listOf("Bodies", "Features"), if (byFeatures) 1 else 0) { set(it == 1, features) }
    if (byFeatures) {
        val choices = editor.toolFeatures(id)
        if (choices.isEmpty()) Text("Nothing before this adds or takes away a shape", fontSize = 14.sp, color = Palette.muted)
        for (f in choices) Toggle(f.name, f.id in features) { on -> set(true, if (on) features + f.id else features - f.id) }
    }
}

@Composable
internal fun PatternSettings(editor: DesignEditor, d: DesignEditor.PatternDraft) {
    Header("Pattern", Icons.pattern, Palette.modify, if (d.byFeatures) featuresLabel(d.features) else bodiesLabel(d.bodies, "All bodies"))
    FeatureChoice(editor, d.id, d.byFeatures, d.features) { by, features -> d.byFeatures = by; d.features = features; editor.draftChanged() }
    Segmented(listOf("In a row", "Round an axis", "Along a path"), if (d.alongPath) 2 else if (d.circular) 1 else 0) {
        d.alongPath = it == 2
        d.circular = it == 1
        if (d.alongPath && d.pathSketch == null && !d.pathByEdges) d.pathSketch = editor.sketchChoices(null).lastOrNull()?.second
        editor.draftChanged()
    }
    if (d.alongPath) {
        PathRow(editor, null, d.pathByEdges, d.pathSketch, d.pathEdges.size) { byEdges, sketch ->
            d.pathByEdges = byEdges
            d.pathSketch = sketch
            editor.draftChanged()
        }
        Field(editor, d, "count", "Count", d.count, "", allowNegative = false) { d.count = it; editor.draftChanged() }
        Segmented(listOf("Spread along it", "Spaced"), if (d.spread) 0 else 1) { d.spread = it == 0; editor.draftChanged() }
        if (!d.spread) Field(editor, d, "spacing", "Spacing", d.spacing, "mm", allowNegative = false) { d.spacing = it; editor.draftChanged() }
        Toggle("Turn with the path", d.turn) { d.turn = it; editor.draftChanged() }
        Toggle("Start from the other end", d.reverse) { d.reverse = it; editor.draftChanged() }
        if (!d.byFeatures) Toggle("Join to the original", d.join) { d.join = it; editor.draftChanged() }
        return
    }
    val axes = editor.axisFeatures()
    if (d.circular && axes.isNotEmpty()) {
        val options = listOf<Int?>(null) + axes.map { it.id }
        Segmented(listOf("Origin axis") + axes.map { it.name }, options.indexOf(d.axisFeature).coerceAtLeast(0)) {
            d.axisFeature = options[it]
            editor.draftChanged()
        }
    }
    if (!d.circular || d.axisFeature == null) AxisRow(if (d.circular) "Round" else "Along", d.axis, false) { d.axis = it!!; editor.draftChanged() }
    Field(editor, d, "count", "Count", d.count, "", allowNegative = false) { d.count = it; editor.draftChanged() }
    if (d.circular) {
        Field(editor, d, "angle", "Angle", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
    } else {
        Field(editor, d, "spacing", "Spacing", d.spacing, "mm", allowNegative = true) { d.spacing = it; editor.draftChanged() }
        AxisRow("And along", d.axis2, true) { d.axis2 = it; editor.draftChanged() }
        if (d.axis2 != null) {
            Field(editor, d, "count2", "Count", d.count2, "", allowNegative = false) { d.count2 = it; editor.draftChanged() }
            Field(editor, d, "spacing2", "Spacing", d.spacing2, "mm", allowNegative = true) { d.spacing2 = it; editor.draftChanged() }
            Toggle("Stagger the rows", d.stagger) { d.stagger = it; editor.draftChanged() }
        }
    }
    if (!d.byFeatures) Toggle("Join to the original", d.join) { d.join = it; editor.draftChanged() }
}

@Composable
internal fun CombineSettings(editor: DesignEditor, d: DesignEditor.CombineDraft) {
    Header("Combine", Icons.combine, Palette.modify, if (d.bodies.isEmpty()) null else d.bodies.joinToString(" + "))
    Segmented(listOf("Join", "Cut", "Intersect"), d.operation.ordinal - 1) {
        d.operation = com.rm.parrotmetric.design.Operation.entries[it + 1]
        editor.draftChanged()
    }
    Toggle("Keep the others", d.keepTools) { d.keepTools = it; editor.draftChanged() }
}

@Composable
internal fun SplitSettings(editor: DesignEditor, d: DesignEditor.SplitDraft) {
    Header(if (d.keep == 0 || d.byBody) "Split" else "Plane cut", Icons.cut, Palette.modify, d.bodies.firstOrNull())
    Segmented(listOf("By a plane", "By a body"), if (d.byBody) 1 else 0) { d.byBody = it == 1; editor.draftChanged() }
    if (d.byBody) {
        Text(d.bodies.getOrNull(1)?.let { "By ${editor.design.nameOf(it)}" } ?: "Tap the body to split it by", fontSize = 14.sp, color = Palette.muted)
        Segmented(listOf("Keep both", "Keep outside", "Keep inside"), d.keep) { d.keep = it; editor.draftChanged() }
    } else {
        PlaneRow(d, d.plane) { d.plane = it; editor.draftChanged() }
        Segmented(listOf("Keep both", "Keep in front", "Keep behind"), d.keep) { d.keep = it; editor.draftChanged() }
    }
}

@Composable
internal fun AlignSettings(editor: DesignEditor, d: DesignEditor.AlignDraft) {
    Header("Align", Icons.align, Palette.modify, if (d.face == null) null else "1 face")
    Text("Onto", fontSize = 13.sp, color = Palette.muted)
    Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.target }) { d.target = d.planes[it].second; editor.draftChanged() }
    Field(editor, d, "gap", "Gap", d.gap, "mm", allowNegative = true) { d.gap = it; editor.draftChanged() }
    Toggle("Line up the middles", d.centred) { d.centred = it; editor.draftChanged() }
    Toggle("Face the same way", d.sameWay) { d.sameWay = it; editor.draftChanged() }
}

@Composable
internal fun MoveSettings(editor: DesignEditor, d: DesignEditor.MoveDraft) {
    Header(if (d.scaling) "Scale" else "Move", if (d.scaling) Icons.scale else Icons.move, Palette.modify, bodiesLabel(d.bodies, "All bodies"))
    if (!d.scaling) {
        Field(editor, d, "dx", "X", d.dx, "mm", allowNegative = true) { d.dx = it; editor.draftChanged() }
        Field(editor, d, "dy", "Y", d.dy, "mm", allowNegative = true) { d.dy = it; editor.draftChanged() }
        Field(editor, d, "dz", "Z", d.dz, "mm", allowNegative = true) { d.dz = it; editor.draftChanged() }
        AxisRow("Turn round", d.axis, false) { d.axis = it!!; editor.draftChanged() }
        Field(editor, d, "angle", "By", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
    }
    // Scale has its own tool; a move that also scales, from an older design, shows both.
    if (d.scaling || d.sx != 1.0 || d.sy != 1.0 || d.sz != 1.0) {
        Toggle("Same scale every way", d.evenly) {
            d.evenly = it
            if (!it) { d.sy = d.sx; d.sz = d.sx }
            d.exprs.remove("scale"); d.exprs.remove("sx")
            editor.draftChanged()
        }
        if (d.evenly) {
            Field(editor, d, "scale", "Scale", d.sx, "×", allowNegative = false) { d.sx = it; editor.draftChanged() }
        } else {
            Field(editor, d, "sx", "Scale X", d.sx, "×", allowNegative = false) { d.sx = it; editor.draftChanged() }
            Field(editor, d, "sy", "Y", d.sy, "×", allowNegative = false) { d.sy = it; editor.draftChanged() }
            Field(editor, d, "sz", "Z", d.sz, "×", allowNegative = false) { d.sz = it; editor.draftChanged() }
        }
    }
    Toggle("Move a copy", d.copy) { d.copy = it; editor.draftChanged() }
}

@Composable
internal fun PointSettings(editor: DesignEditor, d: DesignEditor.PointDraft) {
    val picked = when (d.kind) {
        PointFeature.Kind.At -> if (d.ref != null) "Picked" else null
        PointFeature.Kind.ThreePlanes -> "${d.planes.size.coerceAtMost(3)} of 3"
        PointFeature.Kind.Fixed -> null
    }
    Header("Point", Icons.pointTool, Palette.construct, picked)
    Segmented(listOf("At", "Corner or centre", "Three planes"), d.kind.ordinal) { editor.setConstructionKind(d, PointFeature.Kind.entries[it]) }
    if (d.kind == PointFeature.Kind.Fixed) {
        Field(editor, d, "x", "X", d.x, "mm", allowNegative = true) { d.x = it; editor.draftChanged() }
        Field(editor, d, "y", "Y", d.y, "mm", allowNegative = true) { d.y = it; editor.draftChanged() }
        Field(editor, d, "z", "Z", d.z, "mm", allowNegative = true) { d.z = it; editor.draftChanged() }
    }
}

@Composable
internal fun PlaneSettings(editor: DesignEditor, d: DesignEditor.PlaneDraft) {
    val title = when (d.kind) {
        com.rm.parrotmetric.design.PlaneFeature.Kind.Offset -> "Offset plane"
        com.rm.parrotmetric.design.PlaneFeature.Kind.Angle -> "Angled plane"
        com.rm.parrotmetric.design.PlaneFeature.Kind.Midway -> "Midplane"
        com.rm.parrotmetric.design.PlaneFeature.Kind.ThreePoints -> "Plane through three points"
        com.rm.parrotmetric.design.PlaneFeature.Kind.TwoEdges -> "Plane through two edges"
        com.rm.parrotmetric.design.PlaneFeature.Kind.Tangent -> "Tangent plane"
        com.rm.parrotmetric.design.PlaneFeature.Kind.AlongEdge -> "Plane along an edge"
    }
    val picked = when (d.kind) {
        com.rm.parrotmetric.design.PlaneFeature.Kind.ThreePoints -> "${d.points.size.coerceAtMost(3)} of 3"
        com.rm.parrotmetric.design.PlaneFeature.Kind.TwoEdges -> "${d.edges.size.coerceAtMost(2)} of 2"
        com.rm.parrotmetric.design.PlaneFeature.Kind.Tangent -> if (d.face != null) "1 face" else null
        com.rm.parrotmetric.design.PlaneFeature.Kind.AlongEdge -> if (d.edges.isNotEmpty()) "1 edge" else null
        else -> null
    }
    Header(title, Icons.plane, Palette.construct, picked)
    val fromAPlane = d.kind in setOf(
        com.rm.parrotmetric.design.PlaneFeature.Kind.Offset, com.rm.parrotmetric.design.PlaneFeature.Kind.Angle,
        com.rm.parrotmetric.design.PlaneFeature.Kind.Midway, com.rm.parrotmetric.design.PlaneFeature.Kind.Tangent,
    )
    if (fromAPlane) {
        Text(if (d.kind == com.rm.parrotmetric.design.PlaneFeature.Kind.Tangent) "Facing" else "From", fontSize = 13.sp, color = Palette.muted)
        Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.base }.coerceAtLeast(0)) { d.base = d.planes[it].second; editor.draftChanged() }
    }
    when (d.kind) {
        com.rm.parrotmetric.design.PlaneFeature.Kind.Tangent ->
            Field(editor, d, "angle", "Turned", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
        com.rm.parrotmetric.design.PlaneFeature.Kind.AlongEdge ->
            Field(editor, d, "along", "Along", d.alongPercent, "%", allowNegative = true) { d.alongPercent = it.coerceIn(0.0, 100.0); editor.draftChanged() }
        com.rm.parrotmetric.design.PlaneFeature.Kind.ThreePoints, com.rm.parrotmetric.design.PlaneFeature.Kind.TwoEdges -> {}
        com.rm.parrotmetric.design.PlaneFeature.Kind.Offset ->
            Field(editor, d, "offset", "Distance", d.offset, "mm", allowNegative = true) { d.offset = it; editor.draftChanged() }
        com.rm.parrotmetric.design.PlaneFeature.Kind.Angle -> {
            Segmented(listOf("Round its x", "Round its y"), if (d.turnRoundY) 1 else 0) { d.turnRoundY = it == 1; editor.draftChanged() }
            Field(editor, d, "angle", "Angle", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
        }
        com.rm.parrotmetric.design.PlaneFeature.Kind.Midway -> {
            Text("And", fontSize = 13.sp, color = Palette.muted)
            val others = d.planes.filter { it.second != d.base }
            Segmented(others.map { it.first }, others.indexOfFirst { it.second == d.other }) { d.other = others[it].second; editor.draftChanged() }
        }
    }
}

@Composable
internal fun AxisSettings(editor: DesignEditor, d: DesignEditor.AxisDraft) {
    val picked = when (d.kind) {
        AxisFeature.Kind.Edge -> if (d.edge != null) "1 edge" else null
        AxisFeature.Kind.Round -> if (d.face != null || d.edge != null) "Picked" else null
        AxisFeature.Kind.TwoPoints -> "${d.points.size.coerceAtMost(2)} of 2"
        AxisFeature.Kind.Fixed -> null
    }
    Header("Axis", Icons.axis, Palette.construct, picked)
    Segmented(listOf("Along", "Edge", "Round", "Two points"), d.kind.ordinal) { editor.setConstructionKind(d, AxisFeature.Kind.entries[it]) }
    if (d.kind == AxisFeature.Kind.Fixed) {
        AxisRow("Along", d.along, false) { d.along = it!!; editor.draftChanged() }
        Field(editor, d, "x", "Through X", d.x, "mm", allowNegative = true) { d.x = it; editor.draftChanged() }
        Field(editor, d, "y", "Y", d.y, "mm", allowNegative = true) { d.y = it; editor.draftChanged() }
        Field(editor, d, "z", "Z", d.z, "mm", allowNegative = true) { d.z = it; editor.draftChanged() }
    }
}
