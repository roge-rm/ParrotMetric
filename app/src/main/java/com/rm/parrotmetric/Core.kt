package com.rm.parrotmetric

/**
 * The C++ core (app/src/main/cpp/jni.cpp). Kernel calls can be slow and go on
 * a worker thread; they throw RuntimeException with a reason fit to show.
 * The surface, draw and tap calls belong to the GL thread.
 */
object Core {
    init {
        System.loadLibrary("parrotmetric")
    }

    /** File formats, by the numbers jni.cpp uses. */
    enum class Format(val extensions: List<String>) {
        Stl(listOf("stl")),
        Step(listOf("step", "stp")),
        Iges(listOf("iges", "igs")),
        Obj(listOf("obj")),
        ThreeMf(listOf("3mf"));

        companion object {
            fun forName(name: String): Format? = entries.firstOrNull { name.substringAfterLast('.').lowercase() in it.extensions }
        }
    }

    external fun setScratchDirectory(path: String)

    // Kernel. Bodies are handles; each made comes retained once.
    external fun extrude(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, forward: Double, back: Double): Long
    external fun revolve(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long
    /** how: 0 join, 1 cut, 2 intersect. */
    external fun combine(id: Int, target: Long, tool: Long, how: Int): Long
    external fun fillet(id: Int, body: Long, edges: Array<String>, radius: Double): Long
    external fun chamfer(id: Int, body: Long, edges: Array<String>, distance: Double): Long
    external fun overlaps(a: Long, b: Long): Boolean
    external fun facePlane(body: Long, name: String): DoubleArray
    external fun faceNames(body: Long): Array<String>
    external fun importBody(id: Int, data: ByteArray, format: Int): Long
    external fun shell(id: Int, body: Long, open: Array<String>, thickness: Double): Long
    external fun draft(id: Int, body: Long, faces: Array<String>, neutral: String, angle: Double): Long
    external fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long
    /** plane: origin then normal. */
    external fun split(id: Int, body: Long, plane: DoubleArray): LongArray
    external fun holeTool(id: Int, plane: DoubleArray, points: DoubleArray, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double): Long
    external fun retain(body: Long)
    external fun release(body: Long)
    external fun isMesh(body: Long): Boolean
    /** format as [Format]'s ordinal, quality 0 fine to 2 coarse; null if none of them can go in that format. */
    external fun exportBodies(bodies: LongArray, names: Array<String>, format: Int, quality: Int): ByteArray?

    // What's shown and selected.
    /** Bodies, then sketches (a plane each and their curves), then construction planes (nine numbers each) and axes (six each). */
    external fun show(bodies: LongArray, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, constructionPlanes: DoubleArray, axes: DoubleArray, refit: Boolean)
    external fun selectedPlanes(): IntArray
    /** Lines describing what's selected: lengths, areas, gaps, angles, the body's volume and size. */
    external fun measure(): Array<String>
    external fun setSection(on: Boolean, ox: Double, oy: Double, oz: Double, nx: Double, ny: Double, nz: Double)
    /** A face's edges as sketch curves on a plane; see jni.cpp. */
    external fun faceOutline(body: Long, face: String, plane: DoubleArray): DoubleArray
    external fun shownTriangles(): Int
    /** Selects or unselects what's under the point. Returns selected face, edge, sketch region and plane counts. GL thread. */
    external fun tap(x: Float, y: Float): IntArray
    external fun clearSelection()
    external fun selectedEdges(): Array<String>
    /** "body number\tface name" each. */
    external fun selectedFaces(): Array<String>
    /** Pairs of sketch number and region number. */
    external fun selectedRegions(): IntArray
    external fun select(edges: Array<String>, regions: IntArray, faces: Array<String>)

    /** A sketch's closed regions; see jni.cpp for the layouts. */
    external fun findRegions(kinds: IntArray, ids: IntArray, numbers: DoubleArray): FloatArray

    // The view, on the GL thread.
    external fun surfaceCreated()
    external fun surfaceChanged(width: Int, height: Int)
    /** True while the view is moving and wants another frame. */
    external fun drawFrame(): Boolean
    external fun setDensity(density: Float)
    external fun orbit(dx: Float, dy: Float)
    external fun pan(dx: Float, dy: Float)
    external fun zoom(factor: Float)
    external fun fit()
    /** Turns to look from a direction: yaw round Z from +X and pitch up, in radians. */
    external fun viewFrom(yaw: Float, pitch: Float)
    /** Yaw, pitch, viewport width and height, then the last frame's view-projection matrix. */
    external fun cameraState(): FloatArray
}
