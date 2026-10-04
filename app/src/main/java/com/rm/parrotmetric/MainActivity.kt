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
import com.rm.parrotmetric.ui.HistoryItem
import com.rm.parrotmetric.ui.ModelActions
import com.rm.parrotmetric.ui.ModelScreen
import com.rm.parrotmetric.ui.ModelState
import com.rm.parrotmetric.ui.ToolGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(ModelState())
    private var view: ModelView? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayName(uri)
            val format = Core.Format.forName(name)
            if (format == null) {
                state = state.copy(status = "Open an STL, STEP or IGES file")
                return@registerForActivityResult
            }
            inBackground(onDone = {
                state.copy(
                    title = name.substringBeforeLast('.'),
                    isMesh = format == Core.Format.Stl,
                    history = listOf(HistoryItem(name, ToolGroup.Create)),
                )
            }) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@inBackground "Couldn't read the file"
                Core.importFile(bytes, format)
            }
        }
    }

    private var exportFormat = Core.Format.Stl
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) inBackground(onDone = { state.copy(status = "Saved ${displayName(uri)}") }) {
            val bytes = Core.exportFile(exportFormat) ?: return@inBackground "A mesh can only be exported as STL"
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@inBackground "Couldn't write the file"
            null
        }
    }

    private val actions = object : ModelActions {
        override fun newBox() = inBackground(onDone = {
            state.copy(
                title = "Untitled",
                isMesh = false,
                history = listOf(HistoryItem("Box", ToolGroup.Create), HistoryItem("Fillet", ToolGroup.Modify)),
            )
        }) { Core.showFilletedBox(20.0, 2.0) }

        override fun openFile() = this@MainActivity.openFile.launch(arrayOf("*/*"))
        override fun exportStl() = export(Core.Format.Stl)
        override fun exportStep() = export(Core.Format.Step)

        override fun cutHole() = inBackground(onDone = {
            state.copy(isMesh = true, history = state.history + HistoryItem("Cut", ToolGroup.Modify))
        }) { Core.cutHole() }

        override fun clearSelection() {
            view?.gl { Core.clearSelection() }
            state = state.copy(selectedFaces = 0, selectedEdges = 0)
        }

        override fun fit() {
            view?.gl { Core.fit() }
        }

        override fun viewFrom(yaw: Float, pitch: Float) {
            view?.gl { Core.viewFrom(yaw, pitch) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Core.setScratchDirectory(cacheDir.absolutePath)
        setContent {
            ModelScreen(
                viewport = {
                    AndroidView(
                        factory = { context ->
                            ModelView(
                                context,
                                onCamera = { yaw, pitch -> state = state.copy(yaw = yaw, pitch = pitch) },
                                onSelection = { faces, edges -> state = state.copy(selectedFaces = faces, selectedEdges = edges) },
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
                actions = actions,
            )
        }
        actions.newBox()
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

    /**
     * Runs a core call off the main thread. On success the state becomes what
     * [onDone] makes of it; on failure the status shows the reason.
     */
    private fun inBackground(onDone: () -> ModelState = { state }, work: suspend () -> String?) {
        lifecycleScope.launch {
            state = state.copy(busy = true, status = "")
            val error = withContext(Dispatchers.Default) { work() }
            state = if (error == null) {
                onDone().copy(busy = false, triangles = Core.triangleCount(), selectedFaces = 0, selectedEdges = 0)
            } else {
                state.copy(busy = false, status = error)
            }
            view?.requestRender()
        }
    }
}
