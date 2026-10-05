package com.rm.parrotmetric.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.rm.parrotmetric.app.AppController
import com.rm.parrotmetric.app.FileSink
import com.rm.parrotmetric.app.Http
import com.rm.parrotmetric.app.HttpReply
import com.rm.parrotmetric.app.PlatformFiles
import com.rm.parrotmetric.ui.LaunchSplash
import com.rm.parrotmetric.ui.ModelScreen
import com.rm.parrotmetric.ui.SelectionBox
import com.rm.parrotmetric.ui.ViewControls
import com.rm.parrotmetric.ui.viewGestures
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// The page's side: the canvas the core draws on, files and storage.

private fun glStart(): Boolean = js("globalThis.pmCore.ccall('pm_gl_start', 'number', ['string'], ['#pm-gl']) === 1")
private fun glResize(width: Int, height: Int): Unit = js("globalThis.pmCore.ccall('pm_gl_resize', null, ['string', 'number', 'number'], ['#pm-gl', width, height])")
private fun pixelRatio(): Double = js("window.devicePixelRatio || 1")

private fun storageGet(key: String): String? = js("(() => { try { return localStorage.getItem(key); } catch (e) { return null; } })()")
private fun storageSet(key: String, value: String): Unit = js("(() => { try { localStorage.setItem(key, value); } catch (e) {} })()")

/** Calls [then] when the tab is hidden or the page closes, while there's still time to save. */
private fun onPageHidden(then: () -> Unit): Unit = js("""{
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') then(); });
    window.addEventListener('pagehide', () => then());
}""")

/** Asks for a file; calls back with an object holding its name and bytes. */
private fun pickFile(then: (JsAny) -> Unit): Unit = js(
    """(() => {
        const input = document.createElement('input');
        input.type = 'file';
        input.onchange = () => {
            const f = input.files[0];
            if (f) f.arrayBuffer().then((b) => then({ name: f.name, data: new Uint8Array(b) }));
        };
        input.click();
    })()""",
)
private fun pickedName(f: JsAny): String = js("f.name")
private fun pickedSize(f: JsAny): Int = js("f.data.length")
private fun pickedByte(f: JsAny, i: Int): Int = js("f.data[i]")

private fun newBytes(n: Int): JsAny = js("new Uint8Array(n)")
private fun setByte(a: JsAny, i: Int, v: Int): Unit = js("a[i] = v")
/** Hands bytes to the browser to save as a download. */
private fun download(name: String, data: JsAny): Unit = js(
    """(() => {
        const url = URL.createObjectURL(new Blob([data]));
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
    })()""",
)

private fun fetchBytes(url: String, then: (JsAny?) -> Unit): Unit =
    js("fetch(url).then((r) => r.arrayBuffer()).then((b) => then({ data: new Uint8Array(b) })).catch(() => then(null))")

private fun newHeaders(): JsAny = js("({})")
private fun setHeader(h: JsAny, k: String, v: String): Unit = js("h[k] = v")
/** Sends a request; calls back with { code, data }, or null if it couldn't be sent. */
private fun fetchRequest(method: String, url: String, headers: JsAny, body: JsAny?, then: (JsAny?) -> Unit): Unit = js(
    """fetch(url, { method: method, headers: headers, body: body, cache: 'no-store' })
        .then((r) => r.arrayBuffer().then((b) => then({ code: r.status, data: new Uint8Array(b) })))
        .catch(() => then(null))""",
)
private fun replyCode(r: JsAny): Int = js("r.code")

/** HTTP with fetch; the server has to allow this page's origin (CORS). */
private object FetchHttp : Http {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply? {
        val h = newHeaders()
        headers.forEach { (k, v) -> setHeader(h, k, v) }
        val data = body?.let { b -> newBytes(b.size).also { a -> for (i in b.indices) setByte(a, i, b[i].toInt() and 255) } }
        return suspendCoroutine { done ->
            fetchRequest(method, url, h, data) { r ->
                done.resume(r?.let { HttpReply(replyCode(it), ByteArray(pickedSize(it)) { i -> pickedByte(it, i).toByte() }) })
            }
        }
    }
}

private class Download(override val name: String) : FileSink {
    override suspend fun write(bytes: ByteArray): Boolean {
        val a = newBytes(bytes.size)
        for (i in bytes.indices) setByte(a, i, bytes[i].toInt() and 255)
        download(name, a)
        return true
    }
}

/** Files in a browser: picked with the file chooser, saved as downloads; the autosave in local storage. */
private object WebFiles : PlatformFiles {
    override fun open(then: (String, ByteArray?) -> Unit) = pickFile { f ->
        then(pickedName(f), ByteArray(pickedSize(f)) { pickedByte(f, it).toByte() })
    }

    override fun create(suggested: String, then: (FileSink) -> Unit) = then(Download(suggested))
    override fun readAutosave() = storageGet("parrotmetric.autosave")
    override suspend fun writeAutosave(text: String) = storageSet("parrotmetric.autosave", text)
    override fun readSettings() = storageGet("parrotmetric.settings")
    override suspend fun writeSettings(text: String) = storageSet("parrotmetric.settings", text)
    override val http: Http = FetchHttp
    override val deviceName get() = "browser"
}

/**
 * The 3D view in a browser: the core draws with WebGL on the canvas under the
 * page, on the main thread, when something changes or while it's moving.
 */
private class WebView(private val app: () -> AppController) {
    var started = false
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastCamera = FloatArray(0)

    fun start() {
        if (started || problem != null) return
        if (glStart()) {
            WebCore.setDensity(pixelRatio().toFloat())
            WebCore.surfaceCreated()
            started = true
        } else {
            problem = "This browser has no WebGL 2"
        }
    }

    fun gl(work: () -> Unit) {
        if (started) work()
        wake.trySend(Unit)
    }

    fun resize(width: Int, height: Int) {
        if (!started) return
        glResize(width, height)
        WebCore.surfaceChanged(width, height)
        wake.trySend(Unit)
    }

    suspend fun drawLoop() {
        while (true) {
            wake.receive()
            do {
                withFrameNanos { }
                val moving = started && WebCore.drawFrame()
                if (started) {
                    val camera = WebCore.cameraState()
                    if (!camera.contentEquals(lastCamera)) {
                        lastCamera = camera
                        app().cameraChanged(camera)
                    }
                }
            } while (moving)
        }
    }
}

@Composable
private fun WebApp() {
    val scope = rememberCoroutineScope()
    lateinit var app: AppController
    val view = remember { WebView { app } }
    app = remember {
        WebCore.setScratchDirectory("/tmp")
        view.start()
        AppController(WebCore, WebFiles, scope, gl = { work -> view.gl(work) })
    }
    LaunchedEffect(view) { view.drawLoop() }
    LaunchedEffect(Unit) { onPageHidden { app.autosaveText()?.let { storageSet("parrotmetric.autosave", it) } } }
    var logo by remember { mutableStateOf<ImageBitmap?>(null) }
    var full by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        fun decode(f: JsAny) = org.jetbrains.skia.Image.makeFromEncoded(ByteArray(pickedSize(f)) { pickedByte(f, it).toByte() }).toComposeImageBitmap()
        fetchBytes("icons/small-128.png") { f -> if (f != null) logo = decode(f) }
        fetchBytes("icons/icon-512.png") { f -> if (f != null) full = decode(f) }
    }
    val controls = remember(view) {
        object : ViewControls {
            override fun orbit(dx: Float, dy: Float) = view.gl { WebCore.orbit(dx, dy) }
            override fun pan(dx: Float, dy: Float) = view.gl { WebCore.pan(dx, dy) }
            override fun zoom(factor: Float) = view.gl { WebCore.zoom(factor) }
            override fun tap(x: Float, y: Float, double: Boolean) = view.gl {
                app.selectionChanged(WebCore.tap(x, y))
                if (double) WebCore.fit()
            }
            override fun zoomAt(factor: Float, x: Float, y: Float) = view.gl { WebCore.zoomAt(factor, x, y) }
            override fun fit() = view.gl { WebCore.fit() }
            // While a tool is taking picks, a plain click adds to them.
            override fun click(x: Float, y: Float, add: Boolean) = view.gl { app.selectionChanged(WebCore.click(x, y, add || app.design.panel != null)) }
            override fun clickChain(x: Float, y: Float, add: Boolean) =
                view.gl { app.selectionChanged(WebCore.clickChain(x, y, add || app.design.panel != null)) }
            override fun box(rect: androidx.compose.ui.geometry.Rect, crossing: Boolean, add: Boolean) =
                view.gl { app.selectionChanged(WebCore.selectBox(rect.left, rect.top, rect.right, rect.bottom, crossing, add)) }
            override fun menu(x: Float, y: Float) = app.openMenu(x, y)
        }
    }
    var box by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.fillMaxSize()) {
    ModelScreen(
        viewport = {
            Box(Modifier.fillMaxSize().onSizeChanged { view.resize(it.width, it.height) }.viewGestures(controls) { box = it }) {
                // A hole through the page's canvas to the 3D canvas under it.
                Canvas(Modifier.fillMaxSize()) { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
                SelectionBox(box)
                view.problem?.let { Text("No 3D view: $it", Modifier.align(Alignment.Center).padding(24.dp), color = Color(0xFFE8DCC8)) }
            }
        },
        logo = { logo?.let { Image(it, contentDescription = null, modifier = Modifier.size(34.dp)) } },
        state = app.state,
        design = app.design,
        actions = app.actions,
        seeThrough = true,
        startIcon = full?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) },
    )
    LaunchSplash(full?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) })
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("root") { WebApp() }
}
