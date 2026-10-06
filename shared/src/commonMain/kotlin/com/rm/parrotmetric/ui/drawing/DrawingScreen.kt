package com.rm.parrotmetric.ui.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.parrotmetric.drawing.Anchor
import com.rm.parrotmetric.drawing.DimensionKind
import com.rm.parrotmetric.drawing.Drawing
import com.rm.parrotmetric.drawing.DrawingExport
import com.rm.parrotmetric.drawing.Mark
import com.rm.parrotmetric.drawing.Paper
import com.rm.parrotmetric.drawing.Pen
import com.rm.parrotmetric.drawing.Sheet
import com.rm.parrotmetric.drawing.ViewSide
import com.rm.parrotmetric.ui.AppScreen
import com.rm.parrotmetric.ui.Icons
import com.rm.parrotmetric.ui.ModelActions
import com.rm.parrotmetric.ui.Palette
import com.rm.parrotmetric.ui.ScrollSource
import com.rm.parrotmetric.ui.design.DesignEditor
import com.rm.parrotmetric.ui.design.Segmented
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private val ink = Color(0xFF1B1B1B)
private val paper = Color(0xFFFBFAF7)

/** The drawing: one sheet of views of the design, with dimensions and notes, to save as PDF, DXF or SVG. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawingScreen(editor: DesignEditor, title: String, actions: ModelActions) {
    val state = remember(editor) { DrawingState(editor) }
    val scope = rememberCoroutineScope()
    editor.drawingVersion
    val drawing = state.drawing
    val today = remember { actions.today() }
    // The views follow the model: worked out again after each rebuild.
    LaunchedEffect(editor.built, drawing.views.map { it.side }) {
        if (editor.design.drawing == null) state.layOut(title) else state.project()
    }
    val marks = remember(editor.drawingVersion, state.geometry.toMap()) { state.marks(today) }
    var sheet by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding().focusRequester(focus).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.Escape -> {
                    when {
                        state.firstPoint != null -> state.firstPoint = null
                        state.picked != null -> state.picked = null
                        sheet -> sheet = false
                        state.tool != DrawingTool.Select -> state.tool = DrawingTool.Select
                        else -> actions.showScreen(AppScreen.Model)
                    }
                    true
                }
                e.key == Key.Delete || e.key == Key.Backspace -> { state.deletePicked(); true }
                e.isCtrlPressed && e.key == Key.Z -> { if (e.isShiftPressed) editor.redo() else editor.undo(); true }
                else -> false
            }
        },
    ) {
        TopBar(editor, state, drawing, title, actions, marks)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SheetView(state, drawing, marks)
            Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val hint = state.message ?: when {
                    state.working -> "Working out the views…"
                    state.tool == DrawingTool.Dimension && state.firstPoint != null -> "Tap the second point"
                    state.tool == DrawingTool.Dimension -> "Tap two corners or ends, or a circle"
                    state.tool == DrawingTool.Note -> "Tap where the note goes"
                    else -> null
                }
                hint?.let {
                    Surface(color = Palette.surface.copy(alpha = 0.92f), shape = RoundedCornerShape(16.dp)) {
                        Text(it, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, color = Palette.text)
                    }
                }
            }
        }
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sheet) SheetSettings(state, drawing, title, close = { sheet = false })
            else PickedBar(state, drawing)
            ToolBar(state, drawing, onSheet = { sheet = !sheet })
        }
    }
    state.noteAt?.let { (_, _, old) -> NoteDialog(old?.text ?: "", { state.noteAt = null }) { state.saveNote(it) } }
}

@Composable
private fun TopBar(editor: DesignEditor, state: DrawingState, drawing: Drawing, title: String, actions: ModelActions, marks: List<Mark>) {
    var exporting by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.showScreen(AppScreen.Model) }) { Icon(Icons.back, "Back to the model", tint = Palette.text) }
        Column(Modifier.weight(1f)) {
            Text("Drawing", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, maxLines = 1)
            Text("${drawing.paper.label} · ${Drawing.scaleLabel(drawing.scale)}", fontSize = 12.sp, color = Palette.muted, maxLines = 1)
        }
        IconButton(onClick = editor::undo, enabled = editor.canUndo) { Icon(Icons.undo, "Undo", tint = if (editor.canUndo) Palette.text else Palette.faint) }
        IconButton(onClick = editor::redo, enabled = editor.canRedo) { Icon(Icons.redo, "Redo", tint = if (editor.canRedo) Palette.text else Palette.faint) }
        Box {
            IconButton(onClick = { exporting = true }) { Icon(Icons.export, "Save as PDF, DXF or SVG", tint = Palette.text) }
            DropdownMenu(exporting, { exporting = false }, containerColor = Palette.raised) {
                val name = drawing.title.ifBlank { title }
                for ((label, ext) in listOf("PDF" to "pdf", "DXF" to "dxf", "SVG" to "svg")) {
                    DropdownMenuItem({ Text(label, color = Palette.text) }, onClick = {
                        exporting = false
                        val d = drawing
                        val m = marks
                        actions.saveFile("$name.$ext") {
                            when (ext) {
                                "pdf" -> DrawingExport.pdf(d, m)
                                "dxf" -> DrawingExport.dxf(m).encodeToByteArray()
                                else -> DrawingExport.svg(d, m).encodeToByteArray()
                            }
                        }
                    })
                }
            }
        }
    }
}

/** The sheet, panned and zoomed with fingers, the mouse or a touchpad. */
@Composable
private fun SheetView(state: DrawingState, drawing: Drawing, marks: List<Mark>) {
    var zoom by remember { mutableFloatStateOf(0f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(Size.Zero) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    fun fit() {
        if (size.width <= 0f) return
        val pad = 16f * density.density
        zoom = min((size.width - 2 * pad) / drawing.width.toFloat(), (size.height - 2 * pad) / drawing.height.toFloat())
        ox = (size.width - drawing.width.toFloat() * zoom) / 2
        oy = (size.height + drawing.height.toFloat() * zoom) / 2
    }
    // Fits when it first has a size and when the paper changes; bars coming and going below leave it be.
    val sized = size.width > 0f
    LaunchedEffect(sized, drawing.paper, drawing.portrait) { fit() }
    fun sx(x: Double) = ox + x.toFloat() * zoom
    fun sy(y: Double) = oy - y.toFloat() * zoom
    fun toSheet(p: Offset) = ((p.x - ox) / zoom).toDouble() to ((oy - p.y) / zoom).toDouble()
    Canvas(
        Modifier.fillMaxSize().pointerInput(state) {
            awaitPointerEventScope {
                val down = mutableMapOf<PointerId, Offset>()
                var start = Offset.Zero
                var last = Offset.Zero
                var lastSpread = 0f
                var dragging = false
                var target: DrawingPick? = null
                var firstStep = true
                var panOnly = false
                var lastTap = 0L
                fun centre() = down.values.fold(Offset.Zero) { a, b -> a + b } / down.size.toFloat()
                fun spread(c: Offset) = if (down.size < 2) 0f else down.values.map { (it - c).getDistance() }.average().toFloat()
                while (true) {
                    val e = awaitPointerEvent()
                    when (e.type) {
                        PointerEventType.Press -> {
                            val first = down.isEmpty()
                            for (c in e.changes) if (c.pressed) down[c.id] = c.position
                            if (first) {
                                start = e.changes.first().position
                                dragging = false
                                firstStep = true
                                val mouse = e.changes.first().type == PointerType.Mouse
                                // Middle or right drag pans; so does a drag that starts on nothing.
                                panOnly = mouse && !e.buttons.isPrimaryPressed
                                val (x, y) = toSheet(start)
                                target = if (!panOnly && state.tool == DrawingTool.Select) state.pickAt(x, y, 10.0 / zoom) else null
                            } else dragging = true
                            last = centre()
                            lastSpread = spread(last)
                        }
                        PointerEventType.Move -> {
                            if (down.isEmpty()) continue
                            for (c in e.changes) if (c.id in down) down[c.id] = c.position
                            val c = centre()
                            if (!dragging && (c - start).getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                target?.let { state.picked = it }
                            }
                            if (dragging) {
                                val d = c - last
                                val t = target
                                if (down.size >= 2) {
                                    ox += d.x; oy += d.y
                                    val s = spread(c)
                                    if (lastSpread > 0f && s > 0f) {
                                        val f = s / lastSpread
                                        ox = c.x - (c.x - ox) * f; oy = c.y - (c.y - oy) * f; zoom *= f
                                    }
                                    lastSpread = s
                                } else if (t != null) {
                                    state.drag(t, (d.x / zoom).toDouble(), (-d.y / zoom).toDouble(), firstStep)
                                    firstStep = false
                                } else {
                                    ox += d.x; oy += d.y
                                }
                            }
                            last = c
                        }
                        PointerEventType.Release -> {
                            for (c in e.changes) if (!c.pressed) down.remove(c.id)
                            if (down.isEmpty()) {
                                val at = e.changes.first().position
                                val now = e.changes.first().uptimeMillis
                                if (!dragging && !panOnly) {
                                    val (x, y) = toSheet(at)
                                    val double = now - lastTap < 350
                                    lastTap = now
                                    state.message = null
                                    when (state.tool) {
                                        DrawingTool.Select -> {
                                            val p = state.pickAt(x, y, 10.0 / zoom)
                                            // A second tap on a note opens it to change.
                                            if (double && p is DrawingPick.Note && p == state.picked) {
                                                state.drawing.notes.firstOrNull { it.id == p.id }?.let { n -> state.noteAt = Triple(n.x, n.y, n) }
                                            }
                                            state.picked = p
                                        }
                                        DrawingTool.Dimension -> state.dimensionTap(x, y, 12.0 / zoom)
                                        DrawingTool.Note -> state.noteAt = Triple(x, y, null)
                                    }
                                }
                                target = null
                            } else {
                                last = centre()
                                lastSpread = spread(last)
                            }
                        }
                        PointerEventType.Scroll -> {
                            val c = e.changes.first()
                            val zoomBy = ScrollSource.zoom?.also { ScrollSource.zoom = null }
                            val pan = ScrollSource.pan?.also { ScrollSource.pan = null }
                            when {
                                pan != null -> { ox += pan.x; oy += pan.y }
                                else -> {
                                    val f = zoomBy ?: 1.15f.pow(-c.scrollDelta.y.coerceIn(-3f, 3f))
                                    ox = c.position.x - (c.position.x - ox) * f
                                    oy = c.position.y - (c.position.y - oy) * f
                                    zoom *= f
                                }
                            }
                        }
                    }
                }
            }
        },
    ) {
        size = this.size
        if (zoom <= 0f) return@Canvas
        // The paper, with a soft edge.
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(sx(0.0) + 4, sy(drawing.height) + 4), Size(drawing.width.toFloat() * zoom, drawing.height.toFloat() * zoom))
        drawRect(paper, Offset(sx(0.0), sy(drawing.height)), Size(drawing.width.toFloat() * zoom, drawing.height.toFloat() * zoom))
        val picked = state.picked
        val pickedMarks: List<Mark> = when (picked) {
            is DrawingPick.Dimension -> {
                val m = drawing.dimensions.firstOrNull { it.id == picked.id }
                val v = m?.let { d -> drawing.views.firstOrNull { it.id == d.view } }
                val g = v?.let { state.geometryOf(it) }
                if (m != null && v != null && g != null) mutableListOf<Mark>().also { Sheet.dimension(drawing, v, g, m, it) } else emptyList()
            }
            else -> emptyList()
        }
        drawMarks(marks, ink, zoom, ::sx, ::sy, measurer, density.density)
        if (pickedMarks.isNotEmpty()) drawMarks(pickedMarks, Palette.orange, zoom, ::sx, ::sy, measurer, density.density)
        // A picked view or note: a box round it.
        val box: DoubleArray? = when (picked) {
            is DrawingPick.View -> drawing.views.firstOrNull { it.id == picked.id }?.let { state.boxOf(it) }
            is DrawingPick.Note -> drawing.notes.firstOrNull { it.id == picked.id }?.let { n ->
                val lines = n.text.split('\n')
                val w = lines.maxOf { DrawingExport.textWidth(it, n.height) }
                doubleArrayOf(n.x, n.y - (lines.size - 1) * n.height * 1.5 - n.height * 0.3, n.x + w, n.y + n.height)
            }
            else -> null
        }
        box?.let { b ->
            val pad = 2.0
            drawRect(
                Palette.orange, Offset(sx(b[0] - pad), sy(b[3] + pad)), Size(((b[2] - b[0] + 2 * pad) * zoom).toFloat(), ((b[3] - b[1] + 2 * pad) * zoom).toFloat()),
                style = Stroke(1.5f * density.density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }
        // Where dimensions can catch, while measuring; the first point picked bigger.
        if (state.tool == DrawingTool.Dimension) {
            for (v in drawing.views) {
                val g = state.geometryOf(v) ?: continue
                for ((x, y) in g.snaps.distinct()) {
                    val (px, py) = Sheet.place(drawing, v, g, x, y)
                    drawCircle(Palette.inspect.copy(alpha = 0.6f), 2.2f * density.density, Offset(sx(px), sy(py)))
                }
            }
            state.firstPoint?.let { (id, x, y) ->
                val v = drawing.views.firstOrNull { it.id == id } ?: return@let
                val g = state.geometryOf(v) ?: return@let
                val (px, py) = Sheet.place(drawing, v, g, x, y)
                drawCircle(Palette.orange, 5f * density.density, Offset(sx(px), sy(py)))
            }
        }
    }
}

/** Draws marks in [colour] at [zoom] pixels a mm. */
private fun DrawScope.drawMarks(
    marks: List<Mark>, colour: Color, zoom: Float, sx: (Double) -> Float, sy: (Double) -> Float,
    measurer: androidx.compose.ui.text.TextMeasurer, density: Float,
) {
    // Lines at least a pixel wide, so a whole sheet in view still shows them.
    fun width(p: Pen) = max(DrawingExport.width(p).toFloat() * zoom, if (p == Pen.Visible) 1.4f else 1f)
    val dash = PathEffect.dashPathEffect(floatArrayOf((DrawingExport.DASH[0] * zoom).toFloat(), (DrawingExport.DASH[1] * zoom).toFloat()))
    for (m in marks) when (m) {
        is Mark.Line -> drawLine(colour, Offset(sx(m.x1), sy(m.y1)), Offset(sx(m.x2), sy(m.y2)), width(m.pen), StrokeCap.Round, if (m.pen == Pen.Hidden) dash else null)
        is Mark.Poly -> {
            val path = Path().apply {
                moveTo(sx(m.points[0]), sy(m.points[1]))
                for (k in 1 until m.points.size / 2) lineTo(sx(m.points[2 * k]), sy(m.points[2 * k + 1]))
            }
            drawPath(path, colour, style = Stroke(width(m.pen), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round, pathEffect = if (m.pen == Pen.Hidden) dash else null))
        }
        is Mark.Arc -> {
            var sweep = m.a1 - m.a0
            while (sweep <= 0) sweep += 2 * PI
            if (sweep > 2 * PI) sweep = 2 * PI
            val r = (m.r * zoom).toFloat()
            // Screen y runs down, so angles turn the other way.
            drawArc(
                colour, (-m.a0 * 180 / PI).toFloat(), (-sweep * 180 / PI).toFloat(), false,
                Offset(sx(m.cx) - r, sy(m.cy) - r), Size(2 * r, 2 * r),
                style = Stroke(width(m.pen), cap = StrokeCap.Round, pathEffect = if (m.pen == Pen.Hidden) dash else null),
            )
        }
        is Mark.Head -> {
            val k = DrawingExport.headCorners(m)
            val path = Path().apply {
                moveTo(sx(k[0].first), sy(k[0].second)); lineTo(sx(k[1].first), sy(k[1].second)); lineTo(sx(k[2].first), sy(k[2].second)); close()
            }
            drawPath(path, colour)
        }
        is Mark.Text -> {
            if (m.text.isEmpty()) continue
            val px = (m.height / DrawingExport.CAP * zoom).toFloat()
            if (px < 3f) continue
            val layout = measurer.measure(m.text, TextStyle(color = colour, fontSize = (px / density).sp, fontFamily = FontFamily.SansSerif))
            val w = layout.size.width.toFloat()
            val shift = when (m.anchor) { Anchor.Start -> 0f; Anchor.Middle -> w / 2; Anchor.End -> w }
            val at = Offset(sx(m.x), sy(m.y))
            rotate((-m.angle).toFloat(), at) {
                drawText(layout, topLeft = Offset(at.x - shift, at.y - layout.firstBaseline))
            }
        }
    }
}

/** What can be done with what's picked. */
@Composable
private fun PickedBar(state: DrawingState, drawing: Drawing) {
    val p = state.picked ?: return
    Surface(color = Palette.surface, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (p) {
                is DrawingPick.View -> {
                    val v = drawing.views.firstOrNull { it.id == p.id } ?: return@Row
                    Text(v.side.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                    Chip(if (v.hidden) "Hidden lines shown" else "Hidden lines off", v.hidden) {
                        state.editor.changeDrawing { d -> d.copy(views = d.views.map { if (it.id == v.id) it.copy(hidden = !it.hidden) else it }) }
                    }
                }
                is DrawingPick.Dimension -> {
                    val m = drawing.dimensions.firstOrNull { it.id == p.id } ?: return@Row
                    if (m.kind == DimensionKind.Diameter || m.kind == DimensionKind.Radius) {
                        Box(Modifier.weight(1f)) {
                            Segmented(listOf("Diameter", "Radius"), if (m.kind == DimensionKind.Diameter) 0 else 1) {
                                state.setKind(m.id, if (it == 0) DimensionKind.Diameter else DimensionKind.Radius)
                            }
                        }
                    } else {
                        val kinds = listOf(DimensionKind.Horizontal, DimensionKind.Vertical, DimensionKind.Aligned)
                        Box(Modifier.weight(1f)) {
                            Segmented(listOf("Across", "Up", "Along"), kinds.indexOf(m.kind)) { state.setKind(m.id, kinds[it]) }
                        }
                    }
                }
                is DrawingPick.Note -> {
                    val n = drawing.notes.firstOrNull { it.id == p.id } ?: return@Row
                    Text(n.text.lineSequence().first(), Modifier.weight(1f), fontSize = 14.sp, color = Palette.text, maxLines = 1)
                    Chip("Change", false) { state.noteAt = Triple(n.x, n.y, n) }
                }
            }
            IconButton(onClick = state::deletePicked) { Icon(Icons.delete, "Delete", tint = Palette.orange) }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = if (on) Palette.teal else Palette.raised, contentColor = Palette.text) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, maxLines = 1)
    }
}

/** The tools along the bottom. */
@Composable
private fun ToolBar(state: DrawingState, drawing: Drawing, onSheet: () -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    Surface(color = Palette.surface, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolButton("Select", Icons.select, state.tool == DrawingTool.Select) { state.tool = DrawingTool.Select; state.firstPoint = null }
            ToolButton("Dimension", Icons.dimension, state.tool == DrawingTool.Dimension) { state.tool = DrawingTool.Dimension; state.picked = null }
            ToolButton("Note", Icons.text, state.tool == DrawingTool.Note) { state.tool = DrawingTool.Note; state.firstPoint = null }
            Box {
                ToolButton("Views", Icons.drawing, false) { adding = true }
                DropdownMenu(adding, { adding = false }, containerColor = Palette.raised) {
                    for (side in ViewSide.entries) if (drawing.views.none { it.side == side }) {
                        DropdownMenuItem({ Text("Add ${side.label.lowercase()}", color = Palette.text) }, onClick = {
                            adding = false
                            scope.launch { state.addView(side) }
                        })
                    }
                }
            }
            ToolButton("Sheet", Icons.parameters, false, onSheet)
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = if (on) Palette.raised else Color.Transparent, contentColor = Palette.text) {
        Column(Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (on) Palette.mint else Palette.inspect)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** The sheet's size, scale, projection and title block. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetSettings(state: DrawingState, drawing: Drawing, title: String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val editor = state.editor
    Surface(color = Palette.surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sheet", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                IconButton(onClick = close) { Icon(Icons.close, "Close", Modifier.size(16.dp), tint = Palette.muted) }
            }
            Segmented(Paper.entries.map { it.label }, drawing.paper.ordinal) { i -> editor.changeDrawing { it.copy(paper = Paper.entries[i]) } }
            Segmented(listOf("Landscape", "Portrait"), if (drawing.portrait) 1 else 0) { i -> editor.changeDrawing { it.copy(portrait = i == 1) } }
            Text("Scale", fontSize = 13.sp, color = Palette.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in Drawing.scales) Chip(Drawing.scaleLabel(s), s == drawing.scale) {
                    // Views keep their centres; the drawing grows or shrinks round them.
                    editor.changeDrawing { it.copy(scale = s) }
                }
            }
            Segmented(listOf("Third angle", "First angle"), if (drawing.firstAngle) 1 else 0) { i -> editor.changeDrawing { it.copy(firstAngle = i == 1) } }
            // One undo step for each field typed in, not each letter.
            var typing by remember { mutableStateOf("") }
            TextRow("Title", drawing.title.ifBlank { title }) { t -> editor.changeDrawing(typing != "title") { it.copy(title = t) }; typing = "title" }
            TextRow("Drawn by", drawing.drawnBy) { t -> editor.changeDrawing(typing != "by") { it.copy(drawnBy = t) }; typing = "by" }
            Surface(onClick = { scope.launch { state.layOut(title) } }, shape = RoundedCornerShape(16.dp), color = Palette.raised, contentColor = Palette.text, modifier = Modifier.fillMaxWidth()) {
                Text("Lay out the views again", Modifier.padding(12.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A labelled line of text that's saved as it's typed, one undo step per field. */
@Composable
private fun TextRow(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(label) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), fontSize = 14.sp, color = Palette.text)
        BasicTextField(
            text, { text = it; onChange(it) },
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.ground).padding(10.dp),
            textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.mint),
            singleLine = true,
        )
    }
}

/** Writing or changing a note; more than one line is fine. */
@Composable
private fun NoteDialog(start: String, dismiss: () -> Unit, done: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Palette.surface,
        title = { Text("Note", color = Palette.text) },
        text = {
            BasicTextField(
                text, { text = it },
                Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(12.dp)).background(Palette.ground).padding(12.dp).focusRequester(focus),
                textStyle = TextStyle(color = Palette.text, fontSize = 16.sp),
                cursorBrush = SolidColor(Palette.mint),
            )
        },
        confirmButton = { TextButton(onClick = { done(text) }) { Text("Done", color = Palette.mint) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel", color = Palette.muted) } },
    )
}
