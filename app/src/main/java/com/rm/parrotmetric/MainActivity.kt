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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rm.parrotmetric.app.AppController
import com.rm.parrotmetric.app.FileSink
import com.rm.parrotmetric.app.PlatformFiles
import com.rm.parrotmetric.ui.ModelScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var view: ModelView? = null
    private lateinit var app: AppController

    private var onOpened: ((String, ByteArray?) -> Unit)? = null
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val then = onOpened ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
            }
            then(displayName(uri), bytes)
        }
    }

    private var onCreated: ((FileSink) -> Unit)? = null
    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) onCreated?.invoke(UriSink(uri))
    }

    private inner class UriSink(private val uri: Uri) : FileSink {
        override val name get() = displayName(uri)
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
            } catch (e: Exception) {
                false
            }
        }
    }

    private val files = object : PlatformFiles {
        override fun open(then: (String, ByteArray?) -> Unit) {
            onOpened = then
            openFile.launch(arrayOf("*/*"))
        }

        override fun create(suggested: String, then: (FileSink) -> Unit) {
            onCreated = then
            createFile.launch(suggested)
        }

        /** The design as it is, kept in the app's own files so it's there next time. */
        private val autosave get() = java.io.File(filesDir, "autosave.pmet")

        override fun readAutosave() = autosave.takeIf { it.exists() }?.readText()

        override suspend fun writeAutosave(text: String) = withContext(Dispatchers.IO) {
            val tmp = java.io.File(filesDir, "autosave.pmet.tmp")
            tmp.writeText(text)
            tmp.renameTo(autosave)
            Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Core.setScratchDirectory(cacheDir.absolutePath)
        app = AppController(Core, files, lifecycleScope) { work -> view?.gl(work) }
        setContent {
            ModelScreen(
                viewport = {
                    AndroidView(
                        factory = { context ->
                            ModelView(context, onCamera = app::cameraChanged, onSelection = app::selectionChanged).also { view = it }
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
                state = app.state,
                design = app.design,
                actions = app.actions,
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

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
}
