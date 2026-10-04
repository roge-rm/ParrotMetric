package com.rm.parrotmetric.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
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

    init {
        design.onShown = { state = state.copy(selectedFaces = 0, selectedEdges = 0, selectedAreas = 0, selectedPlanes = 0) }
        design.onHistoryChanged = ::scheduleAutosave
        // Carry on from where the last session left off.
        files.readAutosave()?.let { text ->
            try {
                state = state.copy(title = design.openFile(text))
            } catch (e: IllegalArgumentException) {
                design.message = "The last design couldn't be read back"
            }
        }
    }

    /** The core's camera state changed (see NativeCore.cameraState). Main thread. */
    fun cameraChanged(c: FloatArray) {
        val camera = CameraState.from(c)
        state = state.copy(yaw = camera.yaw, pitch = camera.pitch, camera = camera)
    }

    /** A tap changed the selection; counts as NativeCore.tap gives them. Main thread. */
    fun selectionChanged(counts: IntArray) {
        state = state.copy(selectedFaces = counts[0], selectedEdges = counts[1], selectedAreas = counts[2], selectedPlanes = counts[3])
        design.selectionChanged()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(800)
            files.writeAutosave(design.fileText(state.title))
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
            } catch (e: IllegalArgumentException) {
                design.message = e.message
            }
            return
        }
        val format = FileFormat.forName(name)
        if (format == null) {
            design.message = "Open a design, or an STL, 3MF, OBJ, STEP or IGES file"
            return
        }
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
        scope.launch {
            val error = try {
                val bytes = withContext(Dispatchers.Default) { core.exportBodies(bodies, names, format.ordinal, request.quality) }
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

    val actions = object : ModelActions {
        override fun newDesign() {
            design.newDesign()
            document = null
            state = state.copy(title = "Untitled")
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
            state = state.copy(selectedFaces = 0, selectedEdges = 0, selectedAreas = 0, selectedPlanes = 0)
            design.selectionChanged()
        }

        override fun fit() = gl { core.fit() }
        override fun viewFrom(yaw: Float, pitch: Float) = gl { core.viewFrom(yaw, pitch) }
        override fun pan(dx: Float, dy: Float) = gl { core.pan(dx, dy) }
        override fun zoom(factor: Float) = gl { core.zoom(factor) }

        override fun startSketch(plane: SketchPlane?) {
            val name = design.nextSketchName()
            val (ref, p) = if (plane != null) PlaneRef.Fixed(plane) to plane else design.sketchPlaneUnderSelection(state.yaw, name) ?: return
            newSketch = ref to name
            openSketch(SketchEditor(p, name, Sketch(), regionFinder, outlineFor(ref, p), design::names, design::constructionPoints))
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

        override fun openHistory(id: Int) {
            val f = design.design.feature(id)
            if (f is SketchFeature) {
                val plane = design.planeOf(f) ?: return
                design.checkpoint()
                newSketch = null
                openSketch(SketchEditor(plane, f.name, f.sketch, regionFinder, outlineFor(f.plane, plane), design::names, design::constructionPoints))
            } else {
                design.edit(id)
            }
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
