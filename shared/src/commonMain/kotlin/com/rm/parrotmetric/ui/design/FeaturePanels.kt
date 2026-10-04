package com.rm.parrotmetric.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            when (d) {
                is DesignEditor.ExtrudeDraft -> ExtrudeSettings(editor, d)
                is DesignEditor.RevolveDraft -> RevolveSettings(editor, d)
                is DesignEditor.EdgeDraft -> EdgeSettings(editor, d)
                else -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Cancel", Palette.raised, Palette.text, Modifier.weight(1f)) { editor.cancelPanel() }
                Button("Done", Palette.mint, Palette.ink, Modifier.weight(1f)) { editor.confirmPanel() }
            }
        }
    }
}

@Composable
private fun Header(title: String, icon: ImageVector, tint: Color, picked: String?) {
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

private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

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
        bad = v == null || (!allowNegative && v <= 0) || v == 0.0
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
