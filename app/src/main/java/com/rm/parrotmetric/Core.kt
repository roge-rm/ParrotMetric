package com.rm.parrotmetric

/**
 * The C++ core (app/src/main/cpp/jni.cpp). Model calls can be slow and go on a
 * worker thread; the surface and draw calls belong to the GL thread. Calls that
 * can fail return the reason, or null when they worked.
 */
object Core {
    init {
        System.loadLibrary("parrotmetric")
    }

    external fun showFilletedBox(size: Double, radius: Double): String?
    /** File formats, by the numbers jni.cpp uses. */
    enum class Format(val extensions: List<String>, val mime: String) {
        Stl(listOf("stl"), "model/stl"),
        Step(listOf("step", "stp"), "model/step"),
        Iges(listOf("iges", "igs"), "model/iges");

        companion object {
            fun forName(name: String): Format? = entries.firstOrNull { name.substringAfterLast('.').lowercase() in it.extensions }
        }
    }

    fun importFile(data: ByteArray, format: Format): String? = importFile(data, format.ordinal)

    /** Null if what's shown can't be written as that format: a mesh can't become STEP or IGES. */
    fun exportFile(format: Format): ByteArray? = exportFile(format.ordinal)

    external fun setScratchDirectory(path: String)
    private external fun importFile(data: ByteArray, format: Int): String?
    private external fun exportFile(format: Int): ByteArray?
    external fun cutHole(): String?
    external fun triangleCount(): Int

    external fun surfaceCreated()
    external fun surfaceChanged(width: Int, height: Int)
    /** True while the view is moving and wants another frame. */
    external fun drawFrame(): Boolean
    external fun setDensity(density: Float)
    /** Selects or unselects what's under the point. Returns the selected face and edge counts. GL thread. */
    external fun tap(x: Float, y: Float): IntArray
    external fun clearSelection()
    external fun orbit(dx: Float, dy: Float)
    external fun pan(dx: Float, dy: Float)
    external fun zoom(factor: Float)
    external fun fit()
    /** Turns to look from a direction: yaw round Z from +X and pitch up, in radians. */
    external fun viewFrom(yaw: Float, pitch: Float)
    /** The camera's yaw and pitch in radians. */
    external fun cameraAngles(): FloatArray
    /** Yaw, pitch, viewport width and height, then the last frame's view-projection matrix. */
    external fun cameraState(): FloatArray
    /** Centre and outward normal of the one selected flat face, or null. */
    external fun selectedFacePlane(): DoubleArray?
    /** Finished sketches as light lines; see jni.cpp for the layout. */
    external fun setSketches(data: FloatArray)
    /** A sketch's closed regions; see jni.cpp for the layouts. */
    external fun findRegions(kinds: IntArray, ids: IntArray, numbers: DoubleArray): FloatArray
}
