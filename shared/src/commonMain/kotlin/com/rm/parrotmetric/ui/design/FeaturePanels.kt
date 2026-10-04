package com.rm.parrotmetric.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.rm.parrotmetric.design.AxisRef
import com.rm.parrotmetric.design.Operation
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.Expression
import com.rm.parrotmetric.ui.Icons
import com.rm.parrotmetric.ui.Palette
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.round

/** The open feature's panel: its settings, and Cancel and Done. */
@Composable
fun FeaturePanel(editor: DesignEditor) {
    val d = editor.panel ?: return
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Long panels scroll, so the model stays in view above them.
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            when (d) {
                is DesignEditor.ExtrudeDraft -> ExtrudeSettings(editor, d)
                is DesignEditor.RevolveDraft -> RevolveSettings(editor, d)
                is DesignEditor.EdgeDraft -> EdgeSettings(editor, d)
                is DesignEditor.FaceDraft -> FaceSettings(editor, d)
                is DesignEditor.HoleDraft -> HoleSettings(editor, d)
                is DesignEditor.MirrorDraft -> MirrorSettings(editor, d)
                is DesignEditor.PatternDraft -> PatternSettings(editor, d)
                is DesignEditor.CombineDraft -> CombineSettings(editor, d)
                is DesignEditor.SplitDraft -> SplitSettings(editor, d)
                is DesignEditor.MoveDraft -> MoveSettings(editor, d)
                is DesignEditor.PlaneDraft -> PlaneSettings(editor, d)
                is DesignEditor.AxisDraft -> AxisSettings(editor, d)
                else -> {}
            }
            }
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
    Header("Extrude", Icons.extrude, Palette.create, if (d.regions.isEmpty()) null else count(d.regions.size, "area", "areas"))
    Segmented(listOf("One side", "Both ways", "Two sides"), d.direction.ordinal) {
        d.direction = DesignEditor.Direction.entries[it]
        editor.draftChanged()
    }
    NumberRow(if (d.direction == DesignEditor.Direction.TwoSides) "Front" else "Distance", d.distance, "mm", allowNegative = true) {
        d.distance = it
        editor.draftChanged()
    }
    if (d.direction == DesignEditor.Direction.TwoSides) NumberRow("Back", d.other, "mm", allowNegative = true) {
        d.other = it
        editor.draftChanged()
    }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
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
    NumberRow("Angle", d.degrees, "°", allowNegative = true) {
        d.degrees = it
        editor.draftChanged()
    }
    OperationRow(d.operation) {
        d.operation = it
        editor.draftChanged()
    }
}

@Composable
private fun EdgeSettings(editor: DesignEditor, d: DesignEditor.EdgeDraft) {
    Header(
        if (d.chamfer) "Chamfer" else "Fillet",
        if (d.chamfer) Icons.chamfer else Icons.fillet,
        Palette.modify,
        if (d.edges.isEmpty()) null else count(d.edges.size, "edge", "edges"),
    )
    NumberRow(if (d.chamfer) "Distance" else "Radius", d.size, "mm", allowNegative = false) {
        d.size = it
        editor.draftChanged()
    }
}

@Composable
private fun OperationRow(op: Operation, onPick: (Operation) -> Unit) {
    Segmented(listOf("New body", "Join", "Cut", "Intersect"), op.ordinal) { onPick(Operation.entries[it]) }
}

/** A labelled number field that takes sums and units, applied on Done from the keyboard or when it loses focus. */
@Composable
fun NumberRow(label: String, value: Double, unit: String, allowNegative: Boolean, onChange: (Double) -> Unit) {
    fun text(v: Double): String {
        val r = round(v * 1000) / 1000
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }
    var field by remember(value) { mutableStateOf(TextFieldValue(text(value))) }
    var bad by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Select it all once focused, after the tap has placed the cursor, so typing replaces it.
    LaunchedEffect(focused) {
        if (focused) {
            delay(30)
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    fun apply() {
        val v = Expression.evaluate(field.text)
        bad = v == null || (!allowNegative && v <= 0)
        if (!bad && v != value) onChange(v!!)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), fontSize = 14.sp, color = Palette.muted)
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                .background(if (bad) Color(0xFF3A2C24) else Palette.ground).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                field,
                onValueChange = { field = it },
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (focused && !it.isFocused) apply()
                    focused = it.isFocused
                },
                textStyle = TextStyle(color = Palette.text, fontSize = 19.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Palette.mint),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { apply() }),
            )
            Text(unit, fontSize = 14.sp, color = Palette.muted)
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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
    Header(if (d.tilt) "Draft" else "Shell", if (d.tilt) Icons.draft else Icons.shell, Palette.modify, if (d.faces.isEmpty()) null else count(d.faces.size, "face", "faces"))
    if (d.tilt) {
        Segmented(listOf("Faces to tilt", "Pivot face"), if (d.pickingPivot) 1 else 0) { d.pickingPivot = it == 1 }
        NumberRow("Angle", d.size, "°", allowNegative = true) { d.size = it; editor.draftChanged() }
    } else {
        NumberRow("Walls", d.size, "mm", allowNegative = false) { d.size = it; editor.draftChanged() }
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
    Segmented(listOf("Simple", "Counterbore", "Countersink"), d.kind.ordinal) { d.kind = com.rm.parrotmetric.design.HoleKind.entries[it]; editor.draftChanged() }
    NumberRow("Diameter", d.diameter, "mm", allowNegative = false) { d.diameter = it; editor.draftChanged() }
    Toggle("All the way through", d.through) { d.through = it; editor.draftChanged() }
    if (!d.through) NumberRow("Depth", d.depth, "mm", allowNegative = false) { d.depth = it; editor.draftChanged() }
    if (d.kind != com.rm.parrotmetric.design.HoleKind.Simple) NumberRow("Top", d.topDiameter, "mm", allowNegative = false) { d.topDiameter = it; editor.draftChanged() }
    if (d.kind == com.rm.parrotmetric.design.HoleKind.Counterbore) NumberRow("Top depth", d.topDepth, "mm", allowNegative = false) { d.topDepth = it; editor.draftChanged() }
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
    Header("Mirror", Icons.mirror, Palette.modify, bodiesLabel(d.bodies, "All bodies"))
    PlaneRow(d, d.plane) { d.plane = it; editor.draftChanged() }
    Toggle("Join to the original", d.join) { d.join = it; editor.draftChanged() }
}

@Composable
internal fun PatternSettings(editor: DesignEditor, d: DesignEditor.PatternDraft) {
    Header("Pattern", Icons.pattern, Palette.modify, bodiesLabel(d.bodies, "All bodies"))
    Segmented(listOf("In a row", "Round an axis"), if (d.circular) 1 else 0) { d.circular = it == 1; editor.draftChanged() }
    val axes = editor.axisFeatures()
    if (d.circular && axes.isNotEmpty()) {
        val options = listOf<Int?>(null) + axes.map { it.id }
        Segmented(listOf("Origin axis") + axes.map { it.name }, options.indexOf(d.axisFeature).coerceAtLeast(0)) {
            d.axisFeature = options[it]
            editor.draftChanged()
        }
    }
    if (!d.circular || d.axisFeature == null) AxisRow(if (d.circular) "Round" else "Along", d.axis, false) { d.axis = it!!; editor.draftChanged() }
    NumberRow("Count", d.count, "", allowNegative = false) { d.count = it; editor.draftChanged() }
    if (d.circular) {
        NumberRow("Angle", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
    } else {
        NumberRow("Spacing", d.spacing, "mm", allowNegative = true) { d.spacing = it; editor.draftChanged() }
        AxisRow("And along", d.axis2, true) { d.axis2 = it; editor.draftChanged() }
        if (d.axis2 != null) {
            NumberRow("Count", d.count2, "", allowNegative = false) { d.count2 = it; editor.draftChanged() }
            NumberRow("Spacing", d.spacing2, "mm", allowNegative = true) { d.spacing2 = it; editor.draftChanged() }
        }
    }
    Toggle("Join to the original", d.join) { d.join = it; editor.draftChanged() }
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
    Header("Split", Icons.cut, Palette.modify, d.bodies.firstOrNull())
    PlaneRow(d, d.plane) { d.plane = it; editor.draftChanged() }
}

@Composable
internal fun MoveSettings(editor: DesignEditor, d: DesignEditor.MoveDraft) {
    Header("Move", Icons.move, Palette.modify, bodiesLabel(d.bodies, "All bodies"))
    NumberRow("X", d.dx, "mm", allowNegative = true) { d.dx = it; editor.draftChanged() }
    NumberRow("Y", d.dy, "mm", allowNegative = true) { d.dy = it; editor.draftChanged() }
    NumberRow("Z", d.dz, "mm", allowNegative = true) { d.dz = it; editor.draftChanged() }
    AxisRow("Turn round", d.axis, false) { d.axis = it!!; editor.draftChanged() }
    NumberRow("By", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
    Toggle("Move a copy", d.copy) { d.copy = it; editor.draftChanged() }
}

@Composable
internal fun PlaneSettings(editor: DesignEditor, d: DesignEditor.PlaneDraft) {
    val title = when (d.kind) {
        com.rm.parrotmetric.design.PlaneFeature.Kind.Offset -> "Offset plane"
        com.rm.parrotmetric.design.PlaneFeature.Kind.Angle -> "Angled plane"
        com.rm.parrotmetric.design.PlaneFeature.Kind.Midway -> "Midplane"
    }
    Header(title, Icons.plane, Palette.construct, null)
    Text("From", fontSize = 13.sp, color = Palette.muted)
    Segmented(d.planes.map { it.first }, d.planes.indexOfFirst { it.second == d.base }.coerceAtLeast(0)) { d.base = d.planes[it].second; editor.draftChanged() }
    when (d.kind) {
        com.rm.parrotmetric.design.PlaneFeature.Kind.Offset ->
            NumberRow("Distance", d.offset, "mm", allowNegative = true) { d.offset = it; editor.draftChanged() }
        com.rm.parrotmetric.design.PlaneFeature.Kind.Angle -> {
            Segmented(listOf("Round its x", "Round its y"), if (d.turnRoundY) 1 else 0) { d.turnRoundY = it == 1; editor.draftChanged() }
            NumberRow("Angle", d.degrees, "°", allowNegative = true) { d.degrees = it; editor.draftChanged() }
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
    Header("Axis", Icons.axis, Palette.construct, null)
    AxisRow("Along", d.along, false) { d.along = it!!; editor.draftChanged() }
    NumberRow("Through X", d.x, "mm", allowNegative = true) { d.x = it; editor.draftChanged() }
    NumberRow("Y", d.y, "mm", allowNegative = true) { d.y = it; editor.draftChanged() }
    NumberRow("Z", d.z, "mm", allowNegative = true) { d.z = it; editor.draftChanged() }
}
