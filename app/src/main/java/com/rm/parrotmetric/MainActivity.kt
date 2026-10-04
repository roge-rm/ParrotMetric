package com.rm.parrotmetric

import android.content.Intent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.rm.parrotmetric.app.AppController
import com.rm.parrotmetric.app.FileSink
import com.rm.parrotmetric.app.PlatformFiles
import com.rm.parrotmetric.ui.LaunchSplash
import com.rm.parrotmetric.ui.ModelScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the app while the activity is made again, as when a setting such as
 * the font size changes, so the design and screen carry on.
 */
class AppHolder : ViewModel() {
    var activity: MainActivity? = null

    /** The files of whichever activity is current. */
    private val forward = object : PlatformFiles {
        private val current get() = activity!!.files
        override fun open(then: (String, ByteArray?) -> Unit) = current.open(then)
        override fun create(suggested: String, then: (FileSink) -> Unit) = current.create(suggested, then)
        override fun readAutosave() = current.readAutosave()
        override suspend fun writeAutosave(text: String) = current.writeAutosave(text)
        override fun readSettings() = current.readSettings()
        override suspend fun writeSettings(text: String) = current.writeSettings(text)
    }

    /** Made on first use, once [activity] is set, since it reads the settings through it. */
    val app by lazy {
        AppController(Core, forward, viewModelScope, { work -> activity?.view?.gl(work) }) { activity?.finishAndRemoveTask() }
    }
}

class MainActivity : ComponentActivity() {
    internal var view: ModelView? = null
    private lateinit var holder: AppHolder
    private val app get() = holder.app

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

    internal val files = object : PlatformFiles {
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

        private val settings get() = java.io.File(filesDir, "settings.txt")
        override fun readSettings() = settings.takeIf { it.exists() }?.readText()
        override suspend fun writeSettings(text: String) = withContext(Dispatchers.IO) { settings.writeText(text) }

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
        holder = ViewModelProvider(this)[AppHolder::class.java]
        holder.activity = this
        // Only when the app starts, not when the activity is made again on turning the phone.
        val starting = savedInstanceState == null
        if (starting) openFrom(intent)
        setContent {
            Box(Modifier.fillMaxSize()) {
            ModelScreen(
                viewport = {
                    AndroidView(
                        factory = { context ->
                            ModelView(context, onCamera = app::cameraChanged, onSelection = app::selectionChanged).also { view = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                logo = { Image(painterResource(R.drawable.logo_small), contentDescription = null, modifier = Modifier.size(34.dp)) },
                state = app.state,
                design = app.design,
                actions = app.actions,
                startIcon = painterResource(R.drawable.logo_full),
            )
            LaunchSplash(painterResource(R.drawable.logo_full), starting)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A file opened or shared from another app. */
    private fun openFrom(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            @Suppress("DEPRECATION")
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        } ?: return
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
            }
            app.opened(displayName(uri), bytes)
        }
    }

    override fun onStop() {
        super.onStop()
        // Going to the background, where Android may close the app without warning.
        holder.viewModelScope.launch { app.saveNow() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (holder.activity === this) holder.activity = null
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
        if (uri.scheme == "file") uri.lastPathSegment.orEmpty() else contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
}
