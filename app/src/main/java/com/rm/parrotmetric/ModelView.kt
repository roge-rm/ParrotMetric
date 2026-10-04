package com.rm.parrotmetric

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The 3D view, drawn by the core on OpenGL ES 3. One finger orbits and a pinch
 * zooms. It draws only when something changes.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ModelView(context: Context) : GLSurfaceView(context) {
    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1

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
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = Core.surfaceCreated()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = Core.surfaceChanged(width, height)
            override fun onDrawFrame(gl: GL10?) = Core.drawFrame()
        })
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
            }
            // A second finger starts a pinch; orbiting stops until all fingers lift.
            MotionEvent.ACTION_POINTER_DOWN -> pointer = -1
            MotionEvent.ACTION_MOVE -> if (pointer >= 0 && event.pointerCount == 1) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                queueEvent { Core.orbit(dx, dy) }
                requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer = -1
        }
        return true
    }
}
