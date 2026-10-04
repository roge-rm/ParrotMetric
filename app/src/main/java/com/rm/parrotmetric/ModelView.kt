package com.rm.parrotmetric

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

/**
 * The 3D view, drawn by the core on OpenGL ES 3. One finger orbits, two
 * fingers pan and pinch to zoom, a tap selects and a double tap fits the view.
 * It draws only when something changes or the view is moving.
 *
 * [onCamera] gets the core's camera state (see Core.cameraState) when it
 * changes. It and [onSelection] are called on the main thread.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ModelView(
    context: Context,
    private val onCamera: (state: FloatArray) -> Unit,
    private val onSelection: (counts: IntArray) -> Unit,
) : GLSurfaceView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var multi = false
    private var lastTapTime = 0L
    private var lastCamera = FloatArray(0)

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val f = detector.scaleFactor
            queueEvent { Core.zoom(f) }
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        val density = resources.displayMetrics.density
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                Core.setDensity(density)
                Core.surfaceCreated()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = Core.surfaceChanged(width, height)

            override fun onDrawFrame(gl: GL10?) {
                if (Core.drawFrame()) requestRender()
                val camera = Core.cameraState()
                if (!camera.contentEquals(lastCamera)) {
                    lastCamera = camera
                    post { onCamera(camera) }
                }
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    /** Runs on the GL thread, then draws. */
    fun gl(work: () -> Unit) {
        queueEvent(work)
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.eventTime
                lastX = event.x; lastY = event.y
                dragging = false
                multi = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // The centroid jumps when a finger comes or goes; start again from where it is now.
                multi = true
                dragging = true
                centroid(event, skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1).let { (x, y) ->
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val (x, y) = centroid(event)
                if (!dragging && hypot(x - downX, y - downY) > slop) dragging = true
                if (dragging) {
                    val dx = x - lastX
                    val dy = y - lastY
                    if (event.pointerCount >= 2) gl { Core.pan(dx, dy) }
                    else if (!multi) gl { Core.orbit(dx, dy) }
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && event.eventTime - downTime < 400) tapped(event.x, event.y, event.eventTime)
            }
        }
        return true
    }

    private fun tapped(x: Float, y: Float, time: Long) {
        // A double tap fits the view. Its two taps select and unselect, so the selection stays as it was.
        val double = time - lastTapTime < 300
        lastTapTime = if (double) 0 else time
        gl {
            val counts = Core.tap(x, y)
            post { onSelection(counts) }
            if (double) Core.fit()
        }
    }

    private fun centroid(event: MotionEvent, skip: Int = -1): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            x += event.getX(i); y += event.getY(i); n++
        }
        return if (n == 0) event.x to event.y else x / n to y / n
    }
}
