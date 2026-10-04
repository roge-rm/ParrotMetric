package com.rm.parrotmetric.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rm.parrotmetric.Core
import com.rm.parrotmetric.DesktopGl
import com.rm.parrotmetric.ui.ViewControls
import com.rm.parrotmetric.ui.viewGestures
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * The 3D view on desktop. The core draws on its own GL thread into an
 * offscreen framebuffer, and each frame is read back and shown as an image.
 * It draws only when something changes or the view is moving.
 *
 * [onCamera] and [onSelection] are called on the main thread, like on Android.
 */
class DesktopView(
    private val onCamera: (FloatArray) -> Unit,
    private val onSelection: (IntArray) -> Unit,
) {
    private val thread = Executors.newSingleThreadScheduledExecutor { Thread(it, "ParrotMetric GL").apply { isDaemon = true } }

    /** The last frame drawn. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Why there's no 3D view, if GL wouldn't start. */
    var problem by mutableStateOf<String?>(null)
        private set

    // GL thread only.
    private var started = false
    private var failed = false
    private var size = IntSize.Zero
    private var pixels = ByteArray(0)
    private var lastCamera = FloatArray(0)

    @Volatile private var wanted = IntSize.Zero
    @Volatile var density = 1f
    private val drawQueued = AtomicBoolean(false)

    /** Runs on the GL thread, then draws. Dropped if GL didn't start. */
    fun gl(work: () -> Unit) {
        thread.execute { if (ready()) work() }
        requestDraw()
    }

    fun resize(to: IntSize) {
        wanted = to
        requestDraw()
    }

    fun requestDraw() {
        if (drawQueued.compareAndSet(false, true)) thread.execute(::drawNow)
    }

    /** A click: selects what's under it; a double click also fits the view. */
    fun tap(x: Float, y: Float, double: Boolean) = gl {
        val counts = Core.tap(x, y)
        SwingUtilities.invokeLater { onSelection(counts) }
        if (double) Core.fit()
    }

    private fun ready(): Boolean {
        if (!started && !failed) {
            val why = DesktopGl.start()
            if (why != null) {
                failed = true
                SwingUtilities.invokeLater { problem = why }
            } else {
                started = true
                Core.setDensity(density)
                Core.surfaceCreated()
            }
        }
        return started
    }

    private fun drawNow() {
        drawQueued.set(false)
        if (!ready()) return
        val s = wanted
        if (s.width <= 0 || s.height <= 0) return
        if (s != size) {
            size = s
            DesktopGl.resize(s.width, s.height)
            Core.surfaceChanged(s.width, s.height)
            pixels = ByteArray(s.width * s.height * 4)
        }
        val moving = Core.drawFrame()
        DesktopGl.read(pixels)
        val image = Image.makeRaster(ImageInfo(s.width, s.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), pixels, s.width * 4)
            .toComposeImageBitmap()
        val camera = Core.cameraState()
        val changed = !camera.contentEquals(lastCamera)
        lastCamera = camera
        SwingUtilities.invokeLater {
            frame = image
            if (changed) onCamera(camera)
        }
        if (moving) thread.schedule({ requestDraw() }, 15, TimeUnit.MILLISECONDS)
    }
}

/** Shows [view], with the shared gestures (see viewGestures). */
@Composable
fun DesktopViewport(view: DesktopView) {
    val density = LocalDensity.current.density
    SideEffect { view.density = density }
    val controls = remember(view) {
        object : ViewControls {
            override fun orbit(dx: Float, dy: Float) = view.gl { Core.orbit(dx, dy) }
            override fun pan(dx: Float, dy: Float) = view.gl { Core.pan(dx, dy) }
            override fun zoom(factor: Float) = view.gl { Core.zoom(factor) }
            override fun tap(x: Float, y: Float, double: Boolean) = view.tap(x, y, double)
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { view.resize(it) }.viewGestures(controls)) {
        view.frame?.let { image -> Canvas(Modifier.fillMaxSize()) { drawImage(image) } }
        view.problem?.let {
            Text("No 3D view: $it", Modifier.align(Alignment.Center).padding(24.dp), color = Color(0xFFE8DCC8))
        }
    }
}
