package com.rm.parrotmetric.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.ui.Icons
import com.rm.parrotmetric.ui.Palette

/** What to export: a format by name, how fine, and which bodies by label. */
data class ExportRequest(val format: String, val quality: Int, val labels: List<String>)

private val formats = listOf("STL", "3MF", "OBJ", "STEP", "IGES")

@Composable
private fun SheetFrame(title: String, close: () -> Unit, content: @Composable () -> Unit) {
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                IconButton(onClick = close) { Icon(Icons.close, "Close", Modifier.size(16.dp), tint = Palette.muted) }
            }
            content()
        }
    }
}

/** Export: format, quality for mesh formats, and which bodies. */
@Composable
fun ExportSheet(editor: DesignEditor, close: () -> Unit, export: (ExportRequest) -> Unit) {
    editor.version
    val bodies = editor.allBodies().filter { !editor.design.info(it.label).hidden }
    var format by remember { mutableStateOf(0) }
    var quality by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    SheetFrame("Export", close) {
        Segmented(formats, format) { format = it }
        if (format < 3) Segmented(listOf("Fine", "Medium", "Coarse"), quality) { quality = it }
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            BodyRow("All shown bodies", picked.isEmpty()) { picked = emptySet() }
            val byComponent = bodies.groupBy { editor.design.info(it.label).component }
            for ((component, list) in byComponent) {
                if (component != null) BodyRow(component, list.all { it.label in picked }, indent = false) {
                    picked = if (list.all { it.label in picked }) picked - list.map { it.label }.toSet() else picked + list.map { it.label }
                }
                for (b in list) BodyRow(editor.design.nameOf(b.label), b.label in picked, indent = component != null) {
                    picked = if (b.label in picked) picked - b.label else picked + b.label
                }
            }
        }
        Surface(
            onClick = { export(ExportRequest(formats[format], quality, picked.toList())) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(18.dp),
            color = Palette.mint,
            contentColor = Palette.ink,
        ) {
            Box(contentAlignment = Alignment.Center) { Text("Export ${formats[format]}…", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun BodyRow(label: String, on: Boolean, indent: Boolean = false, toggle: () -> Unit) {
    Surface(onClick = toggle, color = Color.Transparent, contentColor = Palette.text, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(start = if (indent) 26.dp else 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Palette.mint else Palette.ground),
                contentAlignment = Alignment.Center,
            ) { if (on) Icon(Icons.check, null, Modifier.size(13.dp), tint = Palette.ink) }
            Text(label, Modifier.padding(start = 12.dp), fontSize = 14.sp)
        }
    }
}

/** The parts list: bodies grouped by component, to show or hide, rename and group. */
@Composable
fun PartsSheet(editor: DesignEditor, close: () -> Unit) {
    editor.version
    val bodies = editor.allBodies()
    var renaming by remember { mutableStateOf<String?>(null) }
    var newComponentFor by remember { mutableStateOf<String?>(null) }
    SheetFrame("Parts", close) {
        if (bodies.isEmpty()) Text("No bodies yet", fontSize = 14.sp, color = Palette.muted)
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            for ((component, list) in bodies.groupBy { editor.design.info(it.label).component }) {
                if (component != null) Text(component, Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp), fontSize = 13.sp, color = Palette.muted, fontWeight = FontWeight.SemiBold)
                for (b in list) {
                    val info = editor.design.info(b.label)
                    var menu by remember(b.label) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().height(46.dp).padding(start = if (component != null) 14.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { editor.setHidden(b.label, !info.hidden) }) {
                            Icon(if (info.hidden) Icons.hidden else Icons.shown, if (info.hidden) "Show" else "Hide", tint = if (info.hidden) Palette.faint else Palette.mint)
                        }
                        Text(
                            editor.design.nameOf(b.label),
                            Modifier.weight(1f).alpha(if (info.hidden) 0.5f else 1f),
                            fontSize = 15.sp,
                            color = Palette.text,
                        )
                        ColourPick(info.colour) { editor.setColour(b.label, it) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.more, "Options", tint = Palette.muted) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Palette.raised) {
                                DropdownMenuItem({ Text("Rename…") }, onClick = { menu = false; renaming = b.label })
                                for (c in editor.components().filter { it != info.component }) {
                                    DropdownMenuItem({ Text("Into $c") }, onClick = { menu = false; editor.setComponent(b.label, c) })
                                }
                                DropdownMenuItem({ Text("Into a new component…") }, onClick = { menu = false; newComponentFor = b.label })
                                if (info.component != null) DropdownMenuItem({ Text("Out of ${info.component}") }, onClick = { menu = false; editor.setComponent(b.label, null) })
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { label ->
        NameDialog("Rename", editor.design.nameOf(label), { renaming = null }) { editor.rename(label, it); renaming = null }
    }
    newComponentFor?.let { label ->
        NameDialog("New component", "Component ${editor.components().size + 1}", { newComponentFor = null }) {
            if (it.isNotBlank()) editor.setComponent(label, it.trim())
            newComponentFor = null
        }
    }
}

@Composable
private fun NameDialog(title: String, start: String, dismiss: () -> Unit, done: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Palette.surface,
        title = { Text(title, color = Palette.text) },
        text = {
            BasicTextField(
                text, { text = it },
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.ground).padding(12.dp),
                textStyle = TextStyle(color = Palette.text, fontSize = 17.sp),
                cursorBrush = SolidColor(Palette.mint),
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { done(text) }) { Text("Done", color = Palette.mint) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel", color = Palette.muted) } },
    )
}

/** The parameters table: named values any number field can use. */
@Composable
fun ParametersSheet(editor: DesignEditor, close: () -> Unit) {
    editor.version
    val list = editor.design.parameters.toList()
    val values = editor.names()
    var adding by remember { mutableStateOf(false) }
    SheetFrame("Parameters", close) {
        if (list.isEmpty()) Text("No parameters yet", fontSize = 14.sp, color = Palette.muted)
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        NumberRow(p.name, values[p.name] ?: 0.0, "", true, p.expression, values) { _, text ->
                            if (text != null) editor.setParameters(list.toMutableList().also { it[i] = p.copy(expression = text) })
                        }
                    }
                    IconButton(onClick = { editor.setParameters(list.filterIndexed { k, _ -> k != i }) }) {
                        Icon(Icons.delete, "Remove ${p.name}", Modifier.size(18.dp), tint = Palette.muted)
                    }
                }
            }
        }
        Surface(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(16.dp), color = Palette.raised, contentColor = Palette.text) {
            Box(contentAlignment = Alignment.Center) { Text("Add a parameter", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
    if (adding) NameDialog("New parameter", "width", { adding = false }) { name ->
        val n = name.trim()
        when {
            !com.rm.parrotmetric.sketch.Expression.isName(n) -> editor.message = "A name is letters, digits and _, starting with a letter"
            list.any { it.name == n } -> editor.message = "There's already a $n"
            else -> editor.setParameters(list + com.rm.parrotmetric.design.Parameter(n, "10"))
        }
        adding = false
    }
}

/** Measure: what's selected, measured, as it changes. */
@Composable
fun MeasureSheet(editor: DesignEditor, close: () -> Unit) {
    SheetFrame("Measure", close) {
        val lines = editor.measureLines
        if (lines.isEmpty()) Text("Tap edges or faces", fontSize = 14.sp, color = Palette.muted)
        for (l in lines) Text(l, fontSize = 15.sp, color = Palette.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        editor.measuredBody()?.let { (label, p) ->
            val (material, density) = Materials[editor.material]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(editor.design.nameOf(label), Modifier.weight(1f), fontSize = 14.sp, color = Palette.muted)
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }) { Text(material, color = Palette.mint) }
                    DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Palette.raised) {
                        Materials.forEachIndexed { i, (name, d) ->
                            DropdownMenuItem({ Text("$name   ${d} g/cm³") }, onClick = { open = false; editor.material = i })
                        }
                    }
                }
            }
            // Solid all through; a print with less infill weighs less.
            val grams = p[0] / 1000 * density
            Text("Mass ${round1(grams)} g solid", fontSize = 15.sp, color = Palette.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text("Area ${round1(p[1] / 100)} cm²", fontSize = 15.sp, color = Palette.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(
                "Centre of mass ${round1(p[2])}, ${round1(p[3])}, ${round1(p[4])}",
                fontSize = 15.sp, color = Palette.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

private fun round1(v: Double): String {
    val r = kotlin.math.round(v * 10) / 10
    return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
}

/** Bodies that overlap, worked out when the sheet opens. */
@Composable
fun InterferenceSheet(editor: DesignEditor, close: () -> Unit) {
    var found by remember { mutableStateOf<List<Triple<String, String, Double>>?>(null) }
    LaunchedEffect(editor.version, editor.built) { found = editor.interference() }
    SheetFrame("Interference", close) {
        val list = found
        when {
            list == null -> Text("Checking…", fontSize = 14.sp, color = Palette.muted)
            list.isEmpty() -> Text("No bodies overlap", fontSize = 14.sp, color = Palette.muted)
            else -> for ((a, b, v) in list) {
                Text(
                    "${editor.design.nameOf(a)} and ${editor.design.nameOf(b)}: ${round1(v / 1000)} cm³",
                    fontSize = 15.sp, color = Palette.text,
                )
            }
        }
    }
}

/** Materials for Measure's mass: name and density in g/cm³. */
val Materials = listOf(
    "PLA" to 1.24, "PETG" to 1.27, "ABS" to 1.04, "ASA" to 1.07, "TPU" to 1.21, "Nylon" to 1.14,
    "Resin" to 1.18, "Aluminium" to 2.70, "Steel" to 7.85, "Brass" to 8.50,
)

/** Colours a body can have, as 0xRRGGBB; null is the usual grey. */
private val BodyColours = listOf(
    null, 0xF2F0EB, 0x2B2B2B, 0xD64541, 0xF28C38, 0xF2C53D, 0x5DAA68, 0x3BB3A6, 0x4A7FD6, 0x8E6BD6, 0xE57FB0, 0x8B5E3C,
)

private fun swatch(c: Int?) = if (c == null) androidx.compose.ui.graphics.Color(0xFFCCD1CC) else androidx.compose.ui.graphics.Color(0xFF000000 or c.toLong())

/** A body's colour as a dot; tapping it offers the others. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColourPick(colour: Int?, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(swatch(colour)))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Palette.raised) {
            androidx.compose.foundation.layout.FlowRow(Modifier.width(200.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in BodyColours) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(19.dp))
                            .background(if (c == colour) Palette.mint else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(3.dp).clip(RoundedCornerShape(16.dp)).background(swatch(c))
                            .clickable { open = false; onPick(c) },
                    )
                }
            }
        }
    }
}

/** Section: a plane to cut the view along, how far along, and which side to keep. */
@Composable
fun PrintCheckSheet(editor: DesignEditor, close: () -> Unit) {
    SheetFrame("Print check", close) {
        Segmented(listOf("Overhangs", "Thin walls"), (editor.printCheck - 1).coerceAtLeast(0)) {
            editor.printCheck = it + 1
            editor.updatePrintCheck()
        }
        if (editor.printCheck == 2) NumberRow("Thinnest", editor.thinWall, "mm", allowNegative = false) {
            editor.thinWall = it
            editor.updatePrintCheck()
        } else NumberRow("Steepest", editor.overhangAngle, "°", allowNegative = false) {
            editor.overhangAngle = it.coerceIn(0.0, 89.0)
            editor.updatePrintCheck()
        }
    }
}

@Composable
fun SectionSheet(editor: DesignEditor, close: () -> Unit) {
    SheetFrame("Section", close) {
        val planes = editor.sectionPlanes
        Segmented(planes.map { it.first }, planes.indexOfFirst { it.second == editor.sectionPlane }.coerceAtLeast(0)) {
            editor.sectionPlane = planes[it].second
            editor.updateSection()
        }
        NumberRow("Along", editor.sectionOffset, "mm", allowNegative = true) {
            editor.sectionOffset = it
            editor.updateSection()
        }
        androidx.compose.material3.Slider(
            value = editor.sectionOffset.toFloat().coerceIn(-100f, 100f),
            onValueChange = { editor.sectionOffset = (kotlin.math.round(it * 2) / 2).toDouble(); editor.updateSection() },
            valueRange = -100f..100f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Palette.mint, activeTrackColor = Palette.mint),
        )
        Toggle("Keep the other side", editor.sectionFlip) { editor.sectionFlip = it; editor.updateSection() }
    }
}
