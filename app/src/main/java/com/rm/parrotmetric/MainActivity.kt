package com.rm.parrotmetric

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import com.rm.parrotmetric.ui.ModelActions
import com.rm.parrotmetric.ui.ModelScreen
import com.rm.parrotmetric.ui.ModelState
import com.rm.parrotmetric.ui.design.DesignEditor
import com.rm.parrotmetric.ui.sketch.CameraState
import com.rm.parrotmetric.ui.sketch.SketchEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(ModelState())
    private var view: ModelView? = null
    private lateinit var design: DesignEditor

    /** A new sketch waiting for Finish to go into the history. */
    private var newSketch: Pair<PlaneRef, String>? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = displayName(uri)
        if (name.endsWith(".pmet", ignoreCase = true)) {
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                try {
                    state = state.copy(title = design.openFile(text ?: throw IllegalArgumentException("Couldn't read the file")))
                    documentUri = uri
                } catch (e: IllegalArgumentException) {
                    design.message = e.message
                }
            }
            return@registerForActivityResult
        }
        val format = Core.Format.forName(name)
        if (format == null) {
            design.message = "Open a design, or an STL, STEP or IGES file"
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes == null) {
                design.message = "Couldn't read the file"
                return@launch
            }
            if (design.design.features.isEmpty()) state = state.copy(title = name.substringBeforeLast('.'))
            design.importFile(name, bytes, format.ordinal)
        }
    }

    /** Where Save writes: the design file last opened or saved, if any. */
    private var documentUri: Uri? = null

    private val saveDesign = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) writeDesign(uri)
    }

    private fun writeDesign(uri: Uri) {
        val text = design.fileText(state.title)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                documentUri = uri
                val name = displayName(uri)
                state = state.copy(title = name.substringBeforeLast('.'))
                design.message = "Saved $name"
            } else {
                design.message = "Couldn't save the file"
            }
        }
    }

    /** The design as it is, kept in the app's own files so it's there next time. */
    private val autosave by lazy { java.io.File(filesDir, "autosave.pmet") }
    private var autosaveJob: kotlinx.coroutines.Job? = null

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(800)
            val text = design.fileText(state.title)
            withContext(Dispatchers.IO) {
                val tmp = java.io.File(filesDir, "autosave.pmet.tmp")
                tmp.writeText(text)
                tmp.renameTo(autosave)
            }
        }
    }

    private var exportFormat = Core.Format.Stl
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@registerForActivityResult
        val bodies = design.bodies().toLongArray()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.Default) {
                try {
                    val bytes = Core.exportBodies(bodies, exportFormat.ordinal)
                        ?: return@withContext if (exportFormat == Core.Format.Stl) "There's nothing to export" else "Meshes can only be exported as STL"
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext "Couldn't write the file"
                    null
                } catch (e: RuntimeException) {
                    e.message ?: "Couldn't export"
                }
            }
            design.message = error ?: "Saved ${displayName(uri)}"
        }
    }

    private val actions = object : ModelActions {
        override fun newDesign() {
            design.newDesign()
            documentUri = null
            state = state.copy(title = "Untitled")
        }

        override fun save() {
            documentUri?.let { writeDesign(it) } ?: saveAs()
        }

        override fun saveAs() = saveDesign.launch(state.title + ".pmet")

        override fun openFile() = this@MainActivity.openFile.launch(arrayOf("*/*"))
        override fun exportStl() = export(Core.Format.Stl)
        override fun exportStep() = export(Core.Format.Step)

        override fun clearSelection() {
            Core.clearSelection()
            view?.requestRender()
            state = state.copy(selectedFaces = 0, selectedEdges = 0, selectedAreas = 0)
            design.selectionChanged()
        }

        override fun fit() {
            view?.gl { Core.fit() }
        }

        override fun viewFrom(yaw: Float, pitch: Float) {
            view?.gl { Core.viewFrom(yaw, pitch) }
        }

        override fun pan(dx: Float, dy: Float) {
            view?.gl { Core.pan(dx, dy) }
        }

        override fun zoom(factor: Float) {
            view?.gl { Core.zoom(factor) }
        }

        override fun startSketch(plane: SketchPlane?) {
            val name = design.nextSketchName()
            val ref: PlaneRef
            val p: SketchPlane
            if (plane != null) {
                ref = PlaneRef.Fixed(plane)
                p = plane
            } else {
                ref = design.faceUnderSelection(state.yaw) ?: return
                p = planeOnSelectedFace(ref as PlaneRef.OnFace, name) ?: return
            }
            newSketch = ref to name
            openSketch(SketchEditor(p, name, Sketch(), coreRegionFinder))
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
                openSketch(SketchEditor(plane, f.name, f.sketch, coreRegionFinder))
            } else {
                design.edit(id)
            }
        }
    }

    /** The plane a new sketch on the selected face gets: as the rebuild will place it. */
    private fun planeOnSelectedFace(ref: PlaneRef.OnFace, name: String): SketchPlane? {
        val (bodyIndex, face) = Core.selectedFaces().firstOrNull()?.let { it.substringBefore('\t').toInt() to it.substringAfter('\t') } ?: return null
        val body = design.bodies().getOrNull(bodyIndex) ?: return null
        val d = try {
            Core.facePlane(body, face)
        } catch (e: RuntimeException) {
            design.message = e.message
            return null
        }
        val n = Vec3(d[3], d[4], d[5])
        var x = ref.x - n * ref.x.dot(n)
        if (x.dot(x) < 1e-12) x = if (abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0).cross(n) else Vec3(1.0, 0.0, 0.0).cross(n)
        x = x * (1 / sqrt(x.dot(x)))
        return SketchPlane("On a face", Vec3(d[0], d[1], d[2]), x, n.cross(x))
    }

    private fun openSketch(editor: SketchEditor) {
        Core.clearSelection()
        val (yaw, pitch) = viewOf(editor.plane)
        view?.gl { Core.viewFrom(yaw, pitch) }
        state = state.copy(sketch = editor, selectedFaces = 0, selectedEdges = 0, selectedAreas = 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Core.setScratchDirectory(cacheDir.absolutePath)
        design = DesignEditor(CoreKernel, coreRegionFinder, CoreViewport { work -> view?.gl(work) }, lifecycleScope)
        design.onShown = { state = state.copy(selectedFaces = 0, selectedEdges = 0, selectedAreas = 0) }
        design.onHistoryChanged = ::scheduleAutosave
        // Carry on from where the last session left off.
        if (autosave.exists()) {
            try {
                state = state.copy(title = design.openFile(autosave.readText()))
            } catch (e: IllegalArgumentException) {
                design.message = "The last design couldn't be read back"
            }
        }
        setContent {
            ModelScreen(
                viewport = {
                    AndroidView(
                        factory = { context ->
                            ModelView(
                                context,
                                onCamera = { c ->
                                    val camera = CameraState.from(c)
                                    state = state.copy(yaw = camera.yaw, pitch = camera.pitch, camera = camera)
                                },
                                onSelection = { counts ->
                                    state = state.copy(selectedFaces = counts[0], selectedEdges = counts[1], selectedAreas = counts[2])
                                    design.selectionChanged()
                                },
                            ).also { view = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                logo = {
                    // The icon's two layers, cropped to its visible middle.
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))) {
                        for (layer in listOf(R.mipmap.ic_launcher_background, R.mipmap.ic_launcher_foreground)) {
                            Image(
                                painterResource(layer),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f),
                            )
                        }
                    }
                },
                state = state,
                design = design,
                actions = actions,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        view?.onResume()
    }

    override fun onPause() {
        super.onPause()
        view?.onPause()
    }

    private fun export(format: Core.Format) {
        exportFormat = format
        saveFile.launch(state.title + "." + format.extensions.first())
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
}
