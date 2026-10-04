package com.rm.parrotmetric

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rm.parrotmetric.ui.ModelActions
import com.rm.parrotmetric.ui.ModelScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("")
    private var view: ModelView? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inBackground {
            val format = Core.Format.forName(displayName(uri)) ?: return@inBackground "Open an STL, STEP or IGES file"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@inBackground "Couldn't read the file"
            Core.importFile(bytes, format)
        }
    }

    private var exportFormat = Core.Format.Stl
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) inBackground {
            val bytes = Core.exportFile(exportFormat) ?: return@inBackground "A mesh can only be exported as STL"
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@inBackground "Couldn't write the file"
            null
        }
    }

    private val actions = object : ModelActions {
        override fun newBox() = inBackground { Core.showFilletedBox(20.0, 2.0) }
        override fun importFile() = openFile.launch(arrayOf("*/*"))
        override fun cutHole() = inBackground { Core.cutHole() }
        override fun exportStl() = export(Core.Format.Stl)
        override fun exportStep() = export(Core.Format.Step)
    }

    private fun export(format: Core.Format) {
        exportFormat = format
        saveFile.launch("model." + format.extensions.first())
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Core.setScratchDirectory(cacheDir.absolutePath)
        setContent {
            ModelScreen(
                viewport = { AndroidView(factory = { ModelView(it).also { v -> view = v } }) },
                actions = actions,
                status = status,
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

    /** Runs a core call off the main thread, then shows its error or the new triangle count. */
    private fun inBackground(work: suspend () -> String?) {
        lifecycleScope.launch {
            status = "Working…"
            val error = withContext(Dispatchers.Default) { work() }
            status = error ?: "${Core.triangleCount()} triangles"
            view?.requestRender()
        }
    }
}
