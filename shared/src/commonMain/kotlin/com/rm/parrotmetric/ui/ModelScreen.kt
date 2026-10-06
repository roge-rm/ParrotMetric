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
import androidx.compose.material3.PlainTooltip
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
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.ui.sketch.SketchTool
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
    val selectedCorners: Int = 0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    /** The camera as last drawn, for lining the sketch overlay up with the view. */
    val camera: CameraState? = null,
    /** The sketch being edited, if any. */
    val sketch: SketchEditor? = null,
    /** Where the right-click menu is open, in pixels on the view, or null. */
    val menu: Offset? = null,
    val layout: LayoutMode = LayoutMode.Automatic,
    val screen: AppScreen = AppScreen.Start,
    /** The autosaved design, for Continue: its title and number of steps. */
    val lastDesign: Pair<String, Int>? = null,
    val detail: DisplayDetail = DisplayDetail.Automatic,
    /** What Automatic picks on this device, once it's been measured. */
    val autoDetail: DisplayDetail? = null,
    val canQuit: Boolean = false,
    /** The projects folder's name, if one is chosen, and the designs in it. */
    val folderName: String? = null,
    val projects: List<com.rm.parrotmetric.app.ProjectFile> = emptyList(),
    /** Whether this platform can have a projects folder. */
    val canChooseFolder: Boolean = false,
    /** Whether this platform can use a WebDAV server as the projects folder, and the last one used. */
    val canUseServer: Boolean = false,
    val server: com.rm.parrotmetric.app.DavLogin? = null,
    /** While a server is being tried, and why it couldn't be used. */
    val connecting: Boolean = false,
    val serverProblem: String? = null,
    /** Apps an export can be handed to, such as slicers. */
    val handOffs: List<String> = emptyList(),
)

/** Which screen layout: by the window's width, or always the phone one or the large-screen one. */
enum class LayoutMode(val label: String) {
    Automatic("Automatic layout"),
    Phone("Phone layout"),
    Large("Large screen layout"),
}

/** What the model screen asks the platform to do. */
interface ModelActions {
    fun newDesign()
    fun save()
    fun saveAs()
    fun openFile()
    fun export(request: com.rm.parrotmetric.ui.design.ExportRequest)
    /** Asks where to save a file named [name], then writes what [bytes] makes, off the main thread. */
    fun saveFile(name: String, bytes: () -> ByteArray) {}
    /** Today, year-month-day, for a drawing's title block. */
    fun today(): String = com.rm.parrotmetric.ui.drawing.DrawingState.today()
    /** Hands the bodies to an app such as a slicer, one of [ModelState.handOffs]. */
    fun handOff(to: String, request: com.rm.parrotmetric.ui.design.ExportRequest) {}
    fun clearSelection()
    fun fit()
    /** Fits an open sketch in view if any of it has gone past the edges. */
    fun keepSketchInView() {}
    /** The part of the view not under panels, so the model is centred and fitted there: pixels covered at each edge. */
    fun setCovered(left: Float, top: Float, right: Float, bottom: Float) {}
    fun viewFrom(yaw: Float, pitch: Float)
    fun pan(dx: Float, dy: Float)
    fun zoom(factor: Float)
    /** Zooms towards the point under (x, y), pixels on the view, which stays put. */
    fun zoomAt(factor: Float, x: Float, y: Float)
    /** Starts a sketch on a plane, or on the selected flat face when plane is null. */
    fun startSketch(plane: SketchPlane?)
    /** Starts a sketch on a construction plane, by its feature's id. */
    fun startSketchOnPlane(id: Int) {}
    fun finishSketch()
    /** Opens a step of the history to change it. */
    fun openHistory(id: Int)
    fun closeMenu()
    fun setLayout(mode: LayoutMode)
    /** Opens the autosaved design. */
    fun continueLast()
    fun showScreen(screen: AppScreen)
    /** Back from settings to the screen before. */
    fun closeSettings()
    fun setDetail(detail: DisplayDetail)
    /** Asks for a picture to lay on a plane. */
    fun insertCanvas()
    fun quit()
    /** Opens a design from the projects folder. */
    fun openProject(name: String) {}
    fun chooseFolder() {}
    fun forgetFolder() {}
    /** Uses a folder on a WebDAV server as the projects folder, if it can be reached. */
    fun useServer(url: String, user: String, password: String) {}
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
    /** True where the 3D view is drawn behind the screen (the browser), so the screen leaves it showing. */
    seeThrough: Boolean = false,
    /** The app's icon for the start screen. */
    startIcon: androidx.compose.ui.graphics.painter.Painter? = null,
    /** A keyboard is attached (always so on desktop and in the browser): tools show their keys. */
    hasKeyboard: Boolean = true,
    /** The platform's back gesture, where there is one: enabled, then what it does. */
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    var openGroup by remember { mutableStateOf<ToolGroup?>(null) }
    // A sheet over the bottom: the parts list or export.
    var sheet by remember { mutableStateOf<String?>(null) }
    var finder by remember { mutableStateOf(false) }
    var keyList by remember { mutableStateOf(false) }
    // Any key pressed shows the keys, as on a phone with a keyboard plugged in later.
    var keyboard by remember { mutableStateOf(hasKeyboard) }
    val focus = remember { FocusRequester() }
    val chain = remember { FieldChain() }
    // A digit that started a field: the desktop sends it again as typed text, which the field mustn't get twice.
    var started by remember { mutableStateOf<Char?>(null) }
    // A number typed in a sketch when nothing takes one: the Enter after it isn't meant to finish the sketch.
    var strayNumber by remember { mutableStateOf(false) }
    // The screen itself has the keys, not a field in it.
    var screenFocused by remember { mutableStateOf(false) }
    val sketch = state.sketch
    val context = ToolContext(state, design, actions) { sheet = it }
    val shortcuts = when {
        state.screen != AppScreen.Model -> emptyList()
        sketch != null -> sketchShortcuts(sketch, actions, { finder = true }, { keyList = true })
        else -> modelShortcuts(context, { finder = true }, { keyList = true }, design.panel != null)
    }
    fun closeSheet() {
        when (sheet) {
            "measure" -> design.stopMeasuring()
            "section" -> design.stopSection()
            "printcheck", "surfacecheck" -> design.stopPrintCheck()
        }
        sheet = null
    }
    // Esc, Backspace or Back: closes or cancels the nearest thing. False if there was nothing.
    fun escape(): Boolean {
        when {
            finder -> finder = false
            keyList -> keyList = false
            state.screen == AppScreen.Help -> if (!Help.back()) actions.closeSettings()
            state.screen == AppScreen.Settings -> actions.closeSettings()
            state.screen != AppScreen.Model -> return false
            sketch != null -> when {
                sketch.editing != null -> sketch.cancelDimension()
                // The size offered for what was just drawn goes, and so does the line being drawn.
                sketch.placed != null -> { sketch.dropPlaced(); if (sketch.pending.isNotEmpty()) sketch.endDrawing() }
                sketch.textEdit != null -> sketch.cancelText()
                sketch.dropTyped() -> {}
                sketch.pending.isNotEmpty() -> sketch.endDrawing()
                sketch.selection.isNotEmpty() -> sketch.clearSelection()
                sketch.tool != SketchTool.Select -> sketch.selectTool(SketchTool.Select)
                else -> return false
            }
            state.menu != null -> actions.closeMenu()
            design.panel != null -> { focus.requestFocus(); design.cancelPanel() }
            sheet != null -> closeSheet()
            openGroup != null -> openGroup = null
            state.selectedFaces + state.selectedEdges + state.selectedAreas + state.selectedPlanes + state.selectedCorners > 0 -> actions.clearSelection()
            else -> return false
        }
        return true
    }
    // Back, past what Esc closes: out of the drawing or the sketch, then to the start screen.
    backHandler(state.screen != AppScreen.Start) {
        if (state.screen == AppScreen.Drawing) actions.showScreen(AppScreen.Model)
        else if (!escape()) {
            if (sketch != null) actions.finishSketch() else actions.showScreen(AppScreen.Start)
        }
    }
    // Keys come here when no field is focused, so the screen's own focus has to come back after fields and dialogs close.
    LaunchedEffect(state.screen, sketch, design.panel, sketch?.editing, sketch?.textEdit, finder, keyList, sheet) {
        if (!finder && sketch?.editing == null && sketch?.textEdit == null && !chain.focused) focus.requestFocus()
    }
    val keys = Modifier
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp) {
                val again = started != null && typedChar(e) == started
                started = null
                return@onPreviewKeyEvent again
            }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            started = null
            keyboard = true
            val name = keyName(e) ?: return@onPreviewKeyEvent false
            if (name == "Esc") return@onPreviewKeyEvent escape()
            if (name == "F1" && !finder && !keyList) {
                actions.showScreen(AppScreen.Help)
                return@onPreviewKeyEvent true
            }
            if (finder || keyList || state.screen != AppScreen.Model) return@onPreviewKeyEvent false
            when {
                name == "Ctrl+Enter" && sketch != null -> { actions.finishSketch(); true }
                name == "Enter" && sketch != null -> when {
                    sketch.editing != null || sketch.textEdit != null -> false
                    // In a size field, Enter sets the sizes.
                    sketch.placed != null && !screenFocused -> false
                    sketch.applyTyped() -> true
                    sketch.pending.isNotEmpty() -> { sketch.endDrawing(); true }
                    strayNumber -> { strayNumber = false; true }
                    // Something else has the keys, such as a menu.
                    !screenFocused -> false
                    else -> { actions.finishSketch(); true }
                }
                name == "Enter" && design.panel != null -> {
                    // Focus leaving a field applies what was typed in it.
                    focus.requestFocus()
                    design.confirmPanel()
                    true
                }
                name == "Tab" || name == "Shift+Tab" -> {
                    val back = name.startsWith("Shift")
                    sketch?.typedNext(back) == true || (chain.any && chain.next(back))
                }
                // A field keeps its own undo, copy and paste.
                name in fieldKeys && !screenFocused -> false
                name.startsWith("Ctrl+") -> shortcuts.press(name)
                else -> false
            }
        }
        .onKeyEvent { e ->
            // Only keys no field took; a field on the desktop lets key presses through, keeping the typed text.
            if (e.type != KeyEventType.KeyDown || !screenFocused || finder || keyList || state.screen != AppScreen.Model) return@onKeyEvent false
            val name = keyName(e)
            val c = typedChar(e)
            if (sketch != null && sketch.editing == null && sketch.textEdit == null) {
                if (c != null && sketch.typeKey(c)) return@onKeyEvent true
                if (c != null && startsNumber(c)) {
                    strayNumber = true
                    return@onKeyEvent true
                }
                if (name == "Backspace") return@onKeyEvent when {
                    sketch.typedBackspace() -> true
                    sketch.selection.isNotEmpty() && sketch.tool == SketchTool.Select -> { sketch.deleteSelection(); true }
                    else -> escape()
                }
            }
            // A digit, or the first letter of a parameter's name, starts the panel's first size.
            val startsSize = c != null && (startsNumber(c) || design.design.parameters.any { it.name.startsWith(c) })
            if (sketch == null && design.panel != null && startsSize && chain.startFirst(c!!)) {
                started = c
                return@onKeyEvent true
            }
            if (name == "Backspace") return@onKeyEvent escape()
            name != null && shortcuts.press(name)
        }
        .onFocusChanged { screenFocused = it.isFocused }
        .focusRequester(focus)
        .focusable()
    // Moving between pages drops the button that had focus, so keys come back here.
    LaunchedEffect(state.screen, Help.reading) { if (state.screen != AppScreen.Model) focus.requestFocus() }
    // When a sketch's size or dimension fields close, the keys go back to the sketch.
    val sketchFields = sketch != null && (sketch.editing != null || sketch.placed != null || sketch.textEdit != null)
    LaunchedEffect(sketchFields) { if (sketch != null && !sketchFields) focus.requestFocus() }
    LaunchedEffect(sketch?.resized) { if ((sketch?.resized ?: 0) > 0) actions.keepSketchInView() }
    // A press on the model takes keys back from any field.
    val refocus = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press) {
                    focus.requestFocus()
                    strayNumber = false
                }
            }
        }
    }
    MaterialTheme(colorScheme = Palette.scheme) {
        // The view stays put while the controls over it change, so it keeps its GL context.
        BoxWithConstraints((if (seeThrough) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(Palette.ground)).then(keys)) {
            val screenHeight = maxHeight
            val expanded = when (state.layout) {
                // Wide but short, like a small tablet on its side, gets the phone layout: the toolbar would take half the height.
                LayoutMode.Automatic -> maxWidth >= 840.dp && maxHeight >= 640.dp
                LayoutMode.Phone -> false
                LayoutMode.Large -> true
            }
            Box(Modifier.fillMaxSize().then(refocus)) { viewport() }
            if (state.screen == AppScreen.Start) {
                StartScreen(startIcon, state, actions)
                return@BoxWithConstraints
            }
            // A press anywhere on these takes keys back, so Esc still works after a button goes away.
            if (state.screen == AppScreen.Settings) {
                Box(Modifier.fillMaxSize().then(refocus)) { SettingsScreen(state, actions, actions::closeSettings) }
                return@BoxWithConstraints
            }
            if (state.screen == AppScreen.Help) {
                Box(Modifier.fillMaxSize().then(refocus)) { HelpScreen(actions::closeSettings) }
                return@BoxWithConstraints
            }
            if (state.screen == AppScreen.Drawing) {
                com.rm.parrotmetric.ui.drawing.DrawingScreen(design, state.title, actions)
                return@BoxWithConstraints
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalFieldChain provides chain, LocalKeyboard provides keyboard) {
            state.menu?.let { at -> SelectionMenu(at, context, actions::closeMenu) }
            if (sketch != null) {
                state.camera?.let {
                    Box(Modifier.fillMaxSize().then(refocus)) {
                        SketchOverlay(sketch, PlaneProjection(it, sketch.plane), actions::pan, actions::zoom, actions::zoomAt)
                    }
                }
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SketchTopBar(sketch, actions::fit, actions::finishSketch)
                    Box(Modifier.weight(1f).fillMaxWidth().steadyOpenArea(actions, sketch)) {
                        SketchStatus(sketch, Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
                    }
                    SketchBottom(sketch, expanded)
                }
            } else if (expanded) {
                ExpandedModel(logo, state, design, actions, sheet) { sheet = it }
            } else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(logo, state, design, actions, onParts = { sheet = if (sheet == "parts") null else "parts" }, onExport = { sheet = "export" })
                Box(Modifier.weight(1f).fillMaxWidth().openArea(actions)) {
                    Column(
                        Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(color = Palette.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(18.dp)) {
                            OrientationCube(state.yaw, state.pitch, actions::viewFrom, size = 72.dp)
                        }
                        Surface(color = Palette.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(14.dp)) {
                            IconButton(onClick = actions::fit, Modifier.focusProperties { canFocus = false }) { Icon(Icons.fit, "Fit the model in view", tint = Palette.text) }
                        }
                    }
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectionChip(state, actions)
                        Message(design)
                    }
                }
                Column(Modifier.imePadding().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (design.panel != null) {
                        // Kept to under a third of the screen, so the model stays in view above it.
                        FeaturePanel(design, maxHeight = (screenHeight * 0.3f).coerceIn(160.dp, 400.dp))
                    } else if (sheet == "parts") {
                        com.rm.parrotmetric.ui.design.PartsSheet(design) { sheet = null }
                    } else if (sheet == "measure") {
                        com.rm.parrotmetric.ui.design.MeasureSheet(design) { design.stopMeasuring(); sheet = null }
                    } else if (sheet == "section") {
                        com.rm.parrotmetric.ui.design.SectionSheet(design) { design.stopSection(); sheet = null }
                    } else if (sheet == "surfacecheck") {
                        com.rm.parrotmetric.ui.design.SurfaceCheckSheet(design) { design.stopPrintCheck(); sheet = null }
                    } else if (sheet == "printcheck") {
                        com.rm.parrotmetric.ui.design.PrintCheckSheet(design) { design.stopPrintCheck(); sheet = null }
                    } else if (sheet == "interference") {
                        com.rm.parrotmetric.ui.design.InterferenceSheet(design) { sheet = null }
                    } else if (sheet == "parameters") {
                        com.rm.parrotmetric.ui.design.ParametersSheet(design) { sheet = null }
                    } else if (sheet == "export") {
                        com.rm.parrotmetric.ui.design.ExportSheet(design, { sheet = null }, state.handOffs, { to, r -> actions.handOff(to, r); sheet = null }) { actions.export(it); sheet = null }
                    } else {
                        AnimatedVisibility(
                            visible = openGroup != null,
                            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
                        ) {
                            openGroup?.let { ToolSheet(it, state, design, actions, onSheet = { name -> sheet = name }) { openGroup = null } }
                        }
                        if (openGroup == null) HistoryBar(design, actions, rows = false)
                        GroupBar(openGroup) { openGroup = if (openGroup == it) null else it }
                    }
                }
            }
            if (finder) ToolFinder(shortcuts) { finder = false }
            if (keyList) KeyList(shortcuts) { keyList = false }
            }
        }
    }
}

/** Tells the view which part of it isn't under panels, so the model is centred and fitted there. */
private fun Modifier.openArea(actions: ModelActions) = onGloballyPositioned { c ->
    val root = c.findRootCoordinates().size
    val b = c.boundsInRoot()
    actions.setCovered(b.left, b.top, root.width - b.right, root.height - b.bottom)
}

/**
 * As [openArea], but taken once for each [key] and again only when the window changes size,
 * so rows coming and going under a sketch don't slide the drawing about.
 */
@Composable
private fun Modifier.steadyOpenArea(actions: ModelActions, key: Any): Modifier {
    var at by remember(key) { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    return onGloballyPositioned { c ->
        val root = c.findRootCoordinates().size
        if (root == at) return@onGloballyPositioned
        at = root
        val b = c.boundsInRoot()
        actions.setCovered(b.left, b.top, root.width - b.right, root.height - b.bottom)
    }
}

/** Keys a text field handles itself when it has focus. */
private val fieldKeys = setOf("Ctrl+Z", "Ctrl+Shift+Z", "Ctrl+Y", "Ctrl+A", "Ctrl+C", "Ctrl+V", "Ctrl+X")

/** True when tools should show their keys. */
val LocalKeyboard = androidx.compose.runtime.compositionLocalOf { false }

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
                androidx.compose.material3.HorizontalDivider(color = Palette.line)
                DropdownMenuItem({ Text("Settings…") }, onClick = { menu = false; actions.showScreen(AppScreen.Settings) }, leadingIcon = { Icon(Icons.parameters, null, tint = Palette.mint) })
                DropdownMenuItem({ Text("Help") }, onClick = { menu = false; actions.showScreen(AppScreen.Help) }, leadingIcon = { Icon(Icons.help, null, tint = Palette.mint) })
                DropdownMenuItem({ Text("Main menu") }, onClick = { menu = false; actions.showScreen(AppScreen.Start) }, leadingIcon = { Icon(Icons.close, null, tint = Palette.mint) })
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
        val keepKeys = Modifier.focusProperties { canFocus = false }
        IconButton(onClick = onParts, keepKeys) { Icon(Icons.parts, "Parts list", tint = Palette.text) }
        IconButton(onClick = design::undo, keepKeys, enabled = design.canUndo) { Icon(Icons.undo, "Undo", tint = if (design.canUndo) Palette.text else Palette.faint) }
        IconButton(onClick = design::redo, keepKeys, enabled = design.canRedo) { Icon(Icons.redo, "Redo", tint = if (design.canRedo) Palette.text else Palette.faint) }
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
        if (state.selectedCorners > 0) add(if (state.selectedCorners == 1) "1 corner" else "${state.selectedCorners} corners")
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

/**
 * The history: each step as a chip with its name when they all fit across;
 * when they don't, just their icons, the name in a tooltip and at the top of
 * the menu. With [rows] they wrap onto up to three rows before the bar
 * scrolls; on a phone they stay in one row that scrolls.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryBar(design: DesignEditor, actions: ModelActions, rows: Boolean) {
    design.version
    design.built
    val history = design.history()
    val marker = design.design.marker
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth.value - 8
        val named = history.sumOf { 48.0 + it.name.length * 7.5 } + 24
        val icon = 42.0
        val perRow = ((room - 24) / icon).toInt().coerceAtLeast(1)
        val showNames = named <= room
        val wrap = rows && !showNames && history.size <= perRow * 3
        // New steps come in at the end, so keep the end in view as the history grows.
        LaunchedEffect(history.size, wrap) { if (!wrap) scroll.animateScrollTo(scroll.maxValue) }
        val chips: @Composable () -> Unit = {
            history.forEachIndexed { index, entry ->
                if (index == marker) Marker(design, history.size)
                HistoryChip(design, actions, entry, index, showNames)
            }
            if (marker >= history.size && history.isNotEmpty()) Marker(design, history.size)
        }
        if (wrap) androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { chips() }
        else Row(
            Modifier.fillMaxWidth().sideways(scroll).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { chips() }
    }
}

/** One step: its icon, and its name when [showName]; a tap opens it, a long press or right-click its menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryChip(design: DesignEditor, actions: ModelActions, entry: HistoryEntry, index: Int, showName: Boolean) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val group = when (entry.kind) {
        HistoryEntry.Kind.Sketch -> ToolGroup.Sketch
        HistoryEntry.Kind.Modify -> ToolGroup.Modify
        HistoryEntry.Kind.Construct -> ToolGroup.Construct
        else -> ToolGroup.Create
    }
    val chip: @Composable () -> Unit = {
        Row(
            Modifier.alpha(if (entry.active && !entry.off) 1f else 0.4f)
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.surface)
                .then(
                    when {
                        entry.error != null -> Modifier.border(1.5.dp, Palette.orange, RoundedCornerShape(18.dp))
                        entry.warning != null -> Modifier.border(1.5.dp, Palette.construct, RoundedCornerShape(18.dp))
                        else -> Modifier
                    },
                )
                // A right-click opens the menu too, as a long press does.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                e.changes.forEach { it.consume() }
                                menu = true
                            }
                        }
                    }
                }
                .combinedClickable(
                    onClick = { (entry.error ?: entry.warning)?.let { design.message = it }; actions.openHistory(entry.id) },
                    onLongClick = { menu = true },
                )
                .height(36.dp)
                .then(if (showName) Modifier.padding(start = 9.dp, end = 12.dp) else Modifier.width(36.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (showName) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(entry.tool?.let { Tools.byId(it) }?.icon ?: group.icon, entry.name, Modifier.size(if (showName) 15.dp else 17.dp), tint = if (entry.error != null) Palette.orange else group.colour)
            if (showName) {
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.text, maxLines = 1,
                    textDecoration = if (entry.off) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
        }
    }
    Box {
        if (showName) chip() else com.rm.parrotmetric.ui.sketch.Named(entry.name) { chip() }
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Palette.raised) {
            if (!showName) Text(entry.name, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, color = Palette.muted, fontWeight = FontWeight.SemiBold)
            DropdownMenuItem({ Text("Edit") }, onClick = { menu = false; actions.openHistory(entry.id) })
            if (entry.kind == HistoryEntry.Kind.Sketch) DropdownMenuItem(
                { Text("Move to picked face") }, onClick = { menu = false; design.moveSketch(entry.id) }, enabled = design.hasPickedPlane(),
            )
            DropdownMenuItem({ Text(if (entry.active) "Roll back to here" else "Roll forward to here") }, onClick = { menu = false; design.rollTo(index) })
            DropdownMenuItem({ Text(if (entry.off) "Turn on" else "Turn off") }, onClick = { menu = false; design.setOff(entry.id, !entry.off) })
            DropdownMenuItem({ Text("Delete") }, onClick = { menu = false; design.delete(entry.id) })
        }
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
@Composable
private fun ToolSheet(group: ToolGroup, state: ModelState, design: DesignEditor, actions: ModelActions, onSheet: (String) -> Unit, close: () -> Unit) {
    var meshTools by remember(group) { mutableStateOf(false) }
    val context = ToolContext(state, design, actions, onSheet)
    val tools = Tools.inGroup(group, meshTools) + if (group == ToolGroup.Sketch) Tools.planes(design) else emptyList()
    Surface(color = Palette.surface, shape = RoundedCornerShape(26.dp)) {
        // Four across on a phone; more on a wide screen, so a tablet on its side keeps the model in view.
        BoxWithConstraints {
        val across = (maxWidth / 128.dp).toInt().coerceAtLeast(4)
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
                if (group == ToolGroup.Modify) Box(Modifier.width(170.dp)) { Segmented(listOf("Solid", "Mesh"), if (meshTools) 1 else 0) { meshTools = it == 1 } }
            }
            for (row in tools.chunked(across)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in row) {
                        val enabled = t.enabled(context)
                        Surface(
                            onClick = { Tools.run(t, context); close() },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).height(72.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = Palette.raised,
                            contentColor = if (enabled) Palette.text else Palette.faint,
                        ) {
                            Box {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(t.icon, null, Modifier.size(24.dp), tint = if (enabled) group.colour else Palette.faint)
                                    Spacer(Modifier.height(6.dp))
                                    Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                }
                                if (LocalKeyboard.current) t.key?.let { KeyBadge(it, Modifier.align(Alignment.TopEnd).padding(5.dp)) }
                            }
                        }
                    }
                    repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                }
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

/**
 * The right-click menu: the last tool again, the tools that suit what's
 * selected, and a few things to do with the selection.
 */
@Composable
private fun SelectionMenu(at: Offset, context: ToolContext, close: () -> Unit) {
    val design = context.design
    val state = context.state
    val repeat = design.lastTool?.let { Tools.byId(it) }?.takeIf { it.enabled(context) }
    val suggested = Tools.all.filter { it.suggest(state) && it.enabled(context) && it != repeat }
    val px = with(LocalDensity.current) { DpOffset(at.x.toDp(), at.y.toDp()) }
    Box(Modifier.offset(px.x, px.y)) {
        val items = buildList<Pair<String, () -> Unit>> {
            repeat?.let { add("Repeat ${it.label}" to { Tools.run(it, context) }) }
            for (t in suggested) add(t.label to { Tools.run(t, context) })
            if (state.selectedFaces > 0) add("Hide" to { design.hideSelectedBodies() })
            add("Fit the view" to { context.actions.fit() })
            if (state.selectedFaces + state.selectedEdges + state.selectedAreas + state.selectedPlanes + state.selectedCorners > 0) add("Clear selection" to { context.actions.clearSelection() })
        }
        DropdownMenu(true, onDismissRequest = close, containerColor = Palette.raised) {
            for ((label, action) in items) DropdownMenuItem({ Text(label) }, onClick = { close(); action() })
        }
    }
}

/**
 * The large-screen layout: every tool in a toolbar along the top, the parts
 * list docked on the left, feature panels and sheets docked on the right,
 * and the history along the bottom, all over the model.
 */
@Composable
private fun ExpandedModel(
    logo: @Composable () -> Unit, state: ModelState, design: DesignEditor, actions: ModelActions,
    sheet: String?, setSheet: (String?) -> Unit,
) {
    var partsOpen by remember { mutableStateOf(true) }
    val context = ToolContext(state, design, actions) { setSheet(it) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(logo, state, design, actions, onParts = { partsOpen = !partsOpen }, onExport = { setSheet("export") })
        Toolbar(context)
        Row(Modifier.weight(1f).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (partsOpen) Box(Modifier.width(300.dp)) { com.rm.parrotmetric.ui.design.PartsSheet(design) { partsOpen = false } }
            Box(Modifier.weight(1f).fillMaxHeight().openArea(actions)) {
                Column(
                    Modifier.align(Alignment.TopEnd),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(color = Palette.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(18.dp)) {
                        OrientationCube(state.yaw, state.pitch, actions::viewFrom, size = 72.dp)
                    }
                    Surface(color = Palette.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(14.dp)) {
                        IconButton(onClick = actions::fit, Modifier.focusProperties { canFocus = false }) { Icon(Icons.fit, "Fit the model in view", tint = Palette.text) }
                    }
                }
                Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionChip(state, actions)
                    Message(design)
                }
            }
            val docked = design.panel != null || sheet in setOf("measure", "section", "printcheck", "surfacecheck", "interference", "parameters", "export")
            if (docked) Box(Modifier.width(380.dp)) {
                when {
                    design.panel != null -> FeaturePanel(design)
                    sheet == "measure" -> com.rm.parrotmetric.ui.design.MeasureSheet(design) { design.stopMeasuring(); setSheet(null) }
                    sheet == "section" -> com.rm.parrotmetric.ui.design.SectionSheet(design) { design.stopSection(); setSheet(null) }
                    sheet == "printcheck" -> com.rm.parrotmetric.ui.design.PrintCheckSheet(design) { design.stopPrintCheck(); setSheet(null) }
                    sheet == "surfacecheck" -> com.rm.parrotmetric.ui.design.SurfaceCheckSheet(design) { design.stopPrintCheck(); setSheet(null) }
                    sheet == "interference" -> com.rm.parrotmetric.ui.design.InterferenceSheet(design) { setSheet(null) }
                    sheet == "parameters" -> com.rm.parrotmetric.ui.design.ParametersSheet(design) { setSheet(null) }
                    sheet == "export" -> com.rm.parrotmetric.ui.design.ExportSheet(design, { setSheet(null) }, state.handOffs, { to, r -> actions.handOff(to, r); setSheet(null) }) { actions.export(it); setSheet(null) }
                }
            }
        }
        Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { HistoryBar(design, actions, rows = true) }
    }
}

/** Every tool at once, in sections by group that wrap onto more rows when the window is narrow, for the large-screen layout. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(context: ToolContext) {
    Surface(color = Palette.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(horizontal = 10.dp)) {
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolGroup.entries.forEachIndexed { i, group ->
                Column(Modifier.padding(end = 10.dp)) {
                    Text(group.label, Modifier.padding(start = 8.dp, bottom = 2.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = group.colour)
                    Row {
                        // Clustered tools share a button where the first of them is.
                        val tools = Tools.all.filter { it.group == group } + if (group == ToolGroup.Sketch) Tools.planes(context.design) else emptyList()
                        for ((i, t) in tools.withIndex()) {
                            val cluster = t.cluster
                            if (cluster == null) ToolbarButton(t, context)
                            else if (tools.indexOfFirst { it.cluster == cluster } == i) ToolbarMenu(cluster, tools.filter { it.cluster == cluster }, context)
                        }
                    }
                }
            }
        }
    }
}

/** Several tools on one button: a menu of them, by name. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarMenu(label: String, tools: List<ToolDef>, context: ToolContext) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.TooltipBox(
            positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(
                androidx.compose.material3.TooltipAnchorPosition.Below,
            ),
            tooltip = { PlainTooltip { Text(label) } },
            state = androidx.compose.material3.rememberTooltipState(),
        ) {
            Surface(onClick = { open = true }, modifier = Modifier.size(48.dp, 40.dp), shape = RoundedCornerShape(12.dp), color = Color.Transparent) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(tools[0].icon, label, Modifier.size(22.dp), tint = tools[0].group.colour)
                    Icon(Icons.more, null, Modifier.size(12.dp), tint = Palette.muted)
                }
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Palette.raised) {
            for (t in tools) {
                val enabled = t.enabled(context)
                DropdownMenuItem(
                    { Text(t.label) },
                    onClick = { open = false; Tools.run(t, context) },
                    enabled = enabled,
                    leadingIcon = { Icon(t.icon, null, tint = if (enabled) t.group.colour else Palette.faint) },
                    trailingIcon = t.key?.let { k -> { KeyBadge(k) } },
                )
            }
        }
    }
}

/** A tool as an icon, its name in a tooltip on hover or a long press. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarButton(t: ToolDef, context: ToolContext) {
    val enabled = t.enabled(context)
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(
            androidx.compose.material3.TooltipAnchorPosition.Below,
        ),
        tooltip = { PlainTooltip { Text(t.label + (t.key?.let { "   $it" } ?: "")) } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) {
        Surface(
            onClick = { Tools.run(t, context) },
            enabled = enabled,
            modifier = Modifier.size(40.dp, 40.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(t.icon, t.label, Modifier.size(22.dp), tint = if (enabled) t.group.colour else Palette.faint)
            }
        }
    }
}
