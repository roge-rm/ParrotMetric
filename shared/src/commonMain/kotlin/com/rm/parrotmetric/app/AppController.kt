package com.rm.parrotmetric.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.io.DesignFile
import com.rm.parrotmetric.ui.AppScreen
import com.rm.parrotmetric.ui.DisplayDetail
import com.rm.parrotmetric.ui.LayoutMode
import com.rm.parrotmetric.ui.ModelActions
import com.rm.parrotmetric.ui.ModelState
import com.rm.parrotmetric.ui.design.DesignEditor
import com.rm.parrotmetric.ui.design.ExportRequest
import com.rm.parrotmetric.ui.sketch.CameraState
import com.rm.parrotmetric.ui.sketch.SketchEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file the user picked to write to. */
interface FileSink {
    val name: String
    /** Writes the whole file, replacing what was there. False if it couldn't. */
    suspend fun write(bytes: ByteArray): Boolean
}

/** What each platform does with files: its pickers, and where the design is kept between sessions. */
interface PlatformFiles {
    /** Asks for a file to open and calls back with its name and contents (null if unreadable), on the main thread. */
    fun open(then: (name: String, bytes: ByteArray?) -> Unit)
    /** Asks where to save, suggesting a name, and calls back on the main thread if one was picked. */
    fun create(suggested: String, then: (FileSink) -> Unit)
    fun readAutosave(): String?
    suspend fun writeAutosave(text: String)
    /** The app's settings as "key=value" lines, or null if none are saved yet. */
    fun readSettings(): String?
    suspend fun writeSettings(text: String)
}

/**
 * The app on every platform: the design, the screen's state and what its
 * buttons do. The platform gives it the core, its files, a scope on the main
 * thread and [gl], which runs a call on the GL thread and redraws.
 */
class AppController(
    private val core: NativeCore,
    private val files: PlatformFiles,
    private val scope: CoroutineScope,
    private val gl: (() -> Unit) -> Unit,
    /** Closes the app, where the platform can; null hides Quit. */
    private val quit: (() -> Unit)? = null,
) {
    var state by mutableStateOf(ModelState())
        private set
    private val regionFinder = coreRegionFinder(core)
    val design = DesignEditor(CoreKernel(core), regionFinder, CoreViewport(core, gl), scope)

    /** A new sketch waiting for Finish to go into the history. */
    private var newSketch: Pair<PlaneRef, String>? = null
    /** Where Save writes: the design file last opened or saved, if any. */
    private var document: FileSink? = null
    private var autosaveJob: Job? = null
    /** A design was started or opened this session, so autosave may write over the last one. */
    private var designOpen = false
    /** Changes not yet autosaved. */
    private var unsaved = false
    /** Where Settings goes back to. */
    private var beforeSettings = AppScreen.Start

    /** The saved settings, as "key=value" lines in the platform's settings file. */
    private val settings = mutableMapOf<String, String>()

    init {
        files.readSettings()?.lines()?.forEach { line ->
            val at = line.indexOf('=')
            if (at > 0) settings[line.substring(0, at)] = line.substring(at + 1)
        }
        state = state.copy(
            layout = LayoutMode.entries.firstOrNull { it.name == settings["layout"] } ?: LayoutMode.Automatic,
            detail = DisplayDetail.entries.firstOrNull { it.name == settings["detail"] } ?: DisplayDetail.Automatic,
            autoDetail = settings["speed"]?.toDoubleOrNull()?.let(::detailFor),
            canQuit = quit != null,
            lastDesign = files.readAutosave()?.let { DesignFile.summary(it) },
        )
        applyDetail()
        if (state.autoDetail == null) measureSpeed()
        design.onShown = { state = state.copy(selectedFaces = 0, selectedEdges = 0, selectedAreas = 0, selectedPlanes = 0, selectedCorners = 0) }
        design.onHistoryChanged = ::scheduleAutosave
        // Saves now and then while there are changes, besides shortly after each.
        scope.launch {
            while (true) {
                delay(30_000)
                // An open sketch changes without telling the history, so it's saved each time.
                if (unsaved || state.sketch != null) write()
            }
        }
    }

    private fun saveSettings() {
        val text = settings.entries.joinToString("") { "${it.key}=${it.value}\n" }
        scope.launch { files.writeSettings(text) }
    }

    /** Automatic display detail for a speed test's time in ms: a desktop takes about 60, a slow tablet 250. */
    private fun detailFor(ms: Double) = when {
        ms < 100 -> DisplayDetail.High
        ms < 200 -> DisplayDetail.Medium
        else -> DisplayDetail.Low
    }

    private fun measureSpeed() {
        scope.launch {
            // Once, the first time the app runs; the result is kept with the settings.
            val ms = withContext(Dispatchers.Default) { core.speedTest() }
            settings["speed"] = ms.toInt().toString()
            saveSettings()
            state = state.copy(autoDetail = detailFor(ms))
            applyDetail()
        }
    }

    private fun applyDetail() {
        val chosen = if (state.detail == DisplayDetail.Automatic) state.autoDetail ?: DisplayDetail.Medium else state.detail
        core.setDisplayDetail(chosen.ordinal - 1)
        if (design.design.features.isNotEmpty()) design.rebuild()
    }

    /** Writes the design to autosave now, if one is open: when the app goes to the background or closes. */
    suspend fun saveNow() {
        autosaveJob?.cancel()
        write()
    }

    private suspend fun write() {
        if (!designOpen) return
        unsaved = false
        files.writeAutosave(autosaveContent())
    }

    /**
     * The design's autosave text, if one is open, for a platform that must
     * write it at once, such as a browser tab closing; it counts as saved.
     */
    fun autosaveText(): String? {
        if (!designOpen) return null
        autosaveJob?.cancel()
        unsaved = false
        return autosaveContent()
    }

    /** The design, with a new sketch that's still open. */
    private fun autosaveContent(): String {
        val drawing = newSketch?.let { (ref, name) -> state.sketch?.let { Triple(name, ref, it.sketch) } }
        return design.fileText(state.title, drawing)
    }

    private fun opening() {
        designOpen = true
        state = state.copy(screen = AppScreen.Model)
    }

    /** The core's camera state changed (see NativeCore.cameraState). Main thread. */
    fun cameraChanged(c: FloatArray) {
        val camera = CameraState.from(c)
        state = state.copy(yaw = camera.yaw, pitch = camera.pitch, camera = camera)
    }

    /** A tap changed the selection; counts as NativeCore.tap gives them. Main thread. */
    fun selectionChanged(counts: IntArray) {
        state = state.copy(
            selectedFaces = counts[0], selectedEdges = counts[1], selectedAreas = counts[2], selectedPlanes = counts[3],
            selectedCorners = counts.getOrElse(4) { 0 },
        )
        design.selectionChanged()
    }

    /** A right click on the view at (x, y), pixels: opens the menu for the selection. Main thread. */
    fun openMenu(x: Float, y: Float) {
        state = state.copy(menu = androidx.compose.ui.geometry.Offset(x, y))
    }

    private fun scheduleAutosave() {
        if (!designOpen) return
        unsaved = true
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(800)
            write()
        }
    }

    /** Opens a design or imports a file, given its name and contents (null if it couldn't be read). */
    fun opened(name: String, bytes: ByteArray?) {
        if (bytes == null) {
            design.message = "Couldn't read the file"
            return
        }
        if (name.endsWith(".pmet", ignoreCase = true)) {
            try {
                state = state.copy(title = design.openFile(bytes.decodeToString()))
                document = null
                opening()
            } catch (e: IllegalArgumentException) {
                design.message = e.message
            }
            return
        }
        // A picture goes on a plane as a canvas to trace over.
        if (name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg")) {
            if (state.screen != AppScreen.Model) {
                design.newDesign()
                document = null
                state = state.copy(title = "Untitled")
            }
            opening()
            design.startCanvas(name, bytes)
            return
        }
        val format = FileFormat.forName(name)
        if (format == null) {
            design.message = "Open a design, a picture, or an STL, 3MF, OBJ, STEP or IGES file"
            return
        }
        // From the start screen, a mesh or solid file starts a new design.
        if (state.screen != AppScreen.Model) {
            design.newDesign()
            document = null
            state = state.copy(title = "Untitled")
        }
        opening()
        scope.launch {
            if (design.design.features.isEmpty()) state = state.copy(title = name.substringBeforeLast('.'))
            // Meshes are repaired as they come in; say what was done.
            val report = if (format.isMesh) withContext(Dispatchers.Default) {
                try { core.repairReport(bytes, format.ordinal) } catch (e: RuntimeException) { e.message ?: "" }
            } else ""
            design.importFile(name, bytes, format.ordinal)
            if (report.isNotEmpty()) design.message = report
        }
    }

    private fun writeDesign(sink: FileSink) {
        val text = design.fileText(state.title)
        scope.launch {
            if (sink.write(text.encodeToByteArray())) {
                document = sink
                state = state.copy(title = sink.name.substringBeforeLast('.'))
                design.message = "Saved ${sink.name}"
            } else {
                design.message = "Couldn't save the file"
            }
        }
    }

    private fun export(request: ExportRequest, sink: FileSink) {
        val format = FileFormat.forLabel(request.format)
        val chosen = design.allBodies().filter { b ->
            if (request.labels.isEmpty()) !design.design.info(b.label).hidden else b.label in request.labels
        }
        val bodies = chosen.map { it.handle }.toLongArray()
        val names = chosen.map { design.design.nameOf(it.label) }.toTypedArray()
        val colours = chosen.map { design.design.info(it.label).colour ?: -1 }.toIntArray()
        scope.launch {
            val error = try {
                val bytes = withContext(Dispatchers.Default) { core.exportBodies(bodies, names, colours, format.ordinal, request.quality) }
                when {
                    bytes == null -> if (format == FileFormat.Step || format == FileFormat.Iges) "Meshes can't be saved as ${request.format}" else "There's nothing to export"
                    !sink.write(bytes) -> "Couldn't write the file"
                    else -> null
                }
            } catch (e: RuntimeException) {
                e.message ?: "Couldn't export"
            }
            design.message = error ?: "Saved ${sink.name}"
        }
    }

    /** The view's covered edges as last sent, so layout passes that don't change them send nothing. */
    private var covered = listOf(0f, 0f, 0f, 0f)

    val actions = object : ModelActions {
        override fun newDesign() {
            design.newDesign()
            document = null
            state = state.copy(title = "Untitled")
            opening()
        }

        override fun continueLast() {
            val text = files.readAutosave() ?: return
            try {
                state = state.copy(title = design.openFile(text))
                document = null
                opening()
            } catch (e: IllegalArgumentException) {
                design.message = "The last design couldn't be read back"
            }
        }

        override fun showScreen(screen: AppScreen) {
            if (screen == AppScreen.Settings) beforeSettings = state.screen
            if (screen == AppScreen.Start) {
                // Leaving the design: keep it for Continue.
                scope.launch { saveNow() }
                state = state.copy(lastDesign = if (designOpen) state.title to design.design.features.size else state.lastDesign)
            }
            state = state.copy(screen = if (screen == AppScreen.Model && !designOpen) AppScreen.Start else screen)
        }

        override fun closeSettings() = showScreen(beforeSettings)

        override fun insertCanvas() = files.open { name, bytes ->
            if (bytes == null) design.message = "Couldn't read the file" else design.startCanvas(name, bytes)
        }

        override fun setDetail(detail: DisplayDetail) {
            state = state.copy(detail = detail)
            settings["detail"] = detail.name
            saveSettings()
            applyDetail()
        }

        override fun quit() {
            scope.launch {
                saveNow()
                quit?.invoke()
            }
        }

        override fun save() {
            document?.let { writeDesign(it) } ?: saveAs()
        }

        override fun saveAs() = files.create(state.title + ".pmet", ::writeDesign)
        override fun openFile() = files.open(::opened)
        override fun export(request: ExportRequest) =
            files.create(state.title + "." + FileFormat.forLabel(request.format).extensions.first()) { export(request, it) }

        override fun clearSelection() {
            core.clearSelection()
            gl {}
            state = state.copy(selectedFaces = 0, selectedEdges = 0, selectedAreas = 0, selectedPlanes = 0, selectedCorners = 0)
            design.selectionChanged()
        }

        override fun fit() = gl { core.fit() }
        override fun setCovered(left: Float, top: Float, right: Float, bottom: Float) {
            val now = listOf(left, top, right, bottom)
            if (now == covered) return
            covered = now
            gl { core.setCovered(left, top, right, bottom) }
        }
        override fun viewFrom(yaw: Float, pitch: Float) = gl { core.viewFrom(yaw, pitch) }
        override fun pan(dx: Float, dy: Float) = gl { core.pan(dx, dy) }
        override fun zoom(factor: Float) = gl { core.zoom(factor) }
        override fun zoomAt(factor: Float, x: Float, y: Float) = gl { core.zoomAt(factor, x, y) }

        override fun startSketch(plane: SketchPlane?) {
            val name = design.nextSketchName()
            val (ref, p) = if (plane != null) PlaneRef.Fixed(plane) to plane else design.sketchPlaneUnderSelection(state.yaw, name) ?: return
            newSketch = ref to name
            openSketch(SketchEditor(p, name, Sketch(), regionFinder, outlineFor(ref, p), design::names, design::constructionPoints, files::open, ::textOutline))
        }

        override fun finishSketch() {
            val editor = state.sketch ?: return
            editor.endDrawing()
            val pending = newSketch
            if (pending != null) {
                if (editor.sketch.curves.isNotEmpty()) {
                    design.checkpoint()
                    design.addSketch(pending.second, pending.first, editor.sketch)
                }
            } else {
                design.sketchChanged()
            }
            newSketch = null
            state = state.copy(sketch = null)
        }

        override fun setLayout(mode: LayoutMode) {
            state = state.copy(layout = mode)
            settings["layout"] = mode.name
            saveSettings()
        }

        override fun closeMenu() {
            state = state.copy(menu = null)
        }

        override fun openHistory(id: Int) {
            val f = design.design.feature(id)
            if (f is SketchFeature) {
                val plane = design.planeOf(f) ?: return
                design.checkpoint()
                newSketch = null
                openSketch(SketchEditor(plane, f.name, f.sketch, regionFinder, outlineFor(f.plane, plane), design::names, design::constructionPoints, files::open, ::textOutline))
            } else {
                design.edit(id)
            }
        }
    }

    /** Text as outline curves at (0, 0), from the core's fonts. */
    private fun textOutline(text: String, height: Double, bold: Boolean): List<ProfileCurve> {
        val n = core.textOutline(text, height, bold)
        return (0 until n.size / 9).map { i ->
            val o = i * 9
            ProfileCurve(
                if (n[o].toInt() == 3) ProfileCurve.Kind.Bezier else ProfileCurve.Kind.Line, 0,
                n[o + 1], n[o + 2], n[o + 3], n[o + 4], cx1 = n[o + 5], cy1 = n[o + 6], cx2 = n[o + 7], cy2 = n[o + 8],
            )
        }
    }

    /** For Project: a face's edges for a sketch on a face, else where the bodies cross the sketch's plane. */
    private fun outlineFor(ref: PlaneRef, plane: SketchPlane): (() -> List<ProfileCurve>?) =
        if (ref is PlaneRef.OnFace) ({ design.outlineOf(ref, plane) }) else ({ design.sectionThrough(plane) })

    private fun openSketch(editor: SketchEditor) {
        core.clearSelection()
        val (yaw, pitch) = viewOf(editor.plane)
        gl { core.viewFrom(yaw, pitch) }
        state = state.copy(sketch = editor, selectedFaces = 0, selectedEdges = 0, selectedAreas = 0)
    }
}
