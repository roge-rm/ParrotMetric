package com.rm.parrotmetric.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.rm.parrotmetric.Core
import com.rm.parrotmetric.app.AppController
import com.rm.parrotmetric.app.FileSink
import com.rm.parrotmetric.app.PlatformFiles
import com.rm.parrotmetric.ui.LaunchSplash
import com.rm.parrotmetric.ui.ModelScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Running on Windows, where the app's folders differ. */
private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** The app's own folder: %APPDATA%\ParrotMetric on Windows, else under XDG_DATA_HOME. */
private fun dataFolder(): File {
    val base = if (onWindows) {
        File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"), "ParrotMetric")
    } else {
        File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"), "parrotmetric")
    }
    return base.apply { mkdirs() }
}

/** Whether the screen is too small for the usual window. */
private fun smallScreen(): Boolean = runCatching {
    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    bounds.width < 1300 || bounds.height < 860
}.getOrDefault(false)

/** An image from the app's resources: parrotmetric-small.png for small uses, parrotmetric.png in full. */
private fun image(name: String): BitmapPainter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream(name)!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

private class LocalFile(private val file: File) : FileSink {
    override val name: String get() = file.name
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }.isSuccess
    }
}

/** Files through the system's file dialogs, and the autosave in the app's folder. */
private class DesktopFiles(private val data: File) : PlatformFiles {
    private var folder: String? = null

    private fun dialog(title: String, mode: Int, suggested: String? = null): File? {
        val d = FileDialog(null as Frame?, title, mode)
        folder?.let { d.directory = it }
        suggested?.let { d.file = it }
        d.isVisible = true
        val name = d.file ?: return null
        folder = d.directory
        return File(d.directory, name)
    }

    override fun open(then: (String, ByteArray?) -> Unit) {
        val file = dialog("Open", FileDialog.LOAD) ?: return
        then(file.name, runCatching { file.readBytes() }.getOrNull())
    }

    override fun create(suggested: String, then: (FileSink) -> Unit) {
        val file = dialog("Save", FileDialog.SAVE, suggested) ?: return
        then(LocalFile(file))
    }

    private val autosave get() = File(data, "autosave.pmet")
    override fun readAutosave() = autosave.takeIf { it.exists() }?.readText()
    override suspend fun writeAutosave(text: String) {
        LocalFile(autosave).write(text.encodeToByteArray())
    }
}

fun main(args: Array<String>) {
    val data = dataFolder()
    val scratch = File(System.getProperty("java.io.tmpdir"), "parrotmetric-" + System.getProperty("user.name")).apply { mkdirs() }
    Core.setScratchDirectory(scratch.path)
    val files = DesktopFiles(data)

    application {
        val window = rememberWindowState(
            size = DpSize(1280.dp, 860.dp),
            placement = if (smallScreen()) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        val painter = remember { image("parrotmetric-small.png") }
        val full = remember { image("parrotmetric.png") }
        Window(onCloseRequest = ::exitApplication, title = "ParrotMetric", icon = painter, state = window) {
            val scope = rememberCoroutineScope()
            lateinit var app: AppController
            val view = remember { DesktopView(onCamera = { app.cameraChanged(it) }, onSelection = { app.selectionChanged(it) }) }
            app = remember { AppController(Core, files, scope) { work -> view.gl(work) } }
            // A file named on the command line, as when opened from a file manager.
            LaunchedEffect(Unit) {
                args.firstOrNull()?.let { File(it) }?.takeIf { it.isFile }?.let { app.opened(it.name, runCatching { it.readBytes() }.getOrNull()) }
            }
            Box(Modifier.fillMaxSize()) {
                ModelScreen(
                    viewport = { DesktopViewport(view) },
                    logo = { painter?.let { Image(it, contentDescription = null, modifier = Modifier.size(34.dp)) } },
                    state = app.state,
                    design = app.design,
                    actions = app.actions,
                )
                LaunchSplash(full)
            }
        }
    }
}
