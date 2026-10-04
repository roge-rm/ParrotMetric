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
    external fun drawFrame()
    external fun orbit(dx: Float, dy: Float)
    external fun zoom(factor: Float)
}
