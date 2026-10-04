package com.rm.parrotmetric.app

/**
 * The C++ core: jni.cpp on Android and desktop, the WebAssembly build in the
 * browser. Kernel calls can be slow and go on a worker thread where there is
 * one; they throw RuntimeException with a reason fit to show. The surface,
 * draw and tap calls belong to the GL thread.
 */
interface NativeCore {
    fun setScratchDirectory(path: String)

    // Kernel. Bodies are handles; each made comes retained once.
    fun extrude(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, forward: Double, back: Double, taper: Double): Long
    fun revolve(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long
    /** how: 0 join, 1 cut, 2 intersect. */
    fun combine(id: Int, target: Long, tool: Long, how: Int): Long
    fun fillet(id: Int, body: Long, edges: Array<String>, radius: Double): Long
    fun chamfer(id: Int, body: Long, edges: Array<String>, distance: Double, kind: Int, second: Double, flip: Boolean): Long
    fun overlaps(a: Long, b: Long): Boolean
    fun facePlane(body: Long, name: String): DoubleArray
    fun faceNames(body: Long): Array<String>
    /** Where a named face (or edge) is and how big, to find it again by shape; null if the body hasn't got it. */
    fun signature(body: Long, name: String, edge: Boolean): DoubleArray?
    /** The face or edge most like a signature, or null if none is close enough. */
    fun relocate(body: Long, signature: DoubleArray): String?
    fun importBody(id: Int, data: ByteArray, format: Int): Long
    fun shell(id: Int, body: Long, open: Array<String>, thickness: Double): Long
    fun draft(id: Int, body: Long, faces: Array<String>, neutral: String, angle: Double): Long
    fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long
    /** plane: origin then normal. */
    fun split(id: Int, body: Long, plane: DoubleArray): LongArray
    fun holeTool(id: Int, plane: DoubleArray, points: DoubleArray, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double): Long
    /** What repairing a mesh file would change, as a line to show, or empty. */
    fun repairReport(data: ByteArray, format: Int): String
    fun convertToSolid(id: Int, body: Long): Long
    fun bodyCentre(body: Long): DoubleArray
    /** Where bodies cross a plane, as sketch curves; see jni.cpp. */
    fun section(bodies: LongArray, plane: DoubleArray): DoubleArray
    /** Middle and normal of the selected flat part of a mesh, or null. */
    fun selectedMeshPlane(): DoubleArray?
    fun retain(body: Long)
    fun release(body: Long)
    fun isMesh(body: Long): Boolean
    /** format as [Format]'s ordinal, quality 0 fine to 2 coarse; null if none of them can go in that format. */
    fun exportBodies(bodies: LongArray, names: Array<String>, format: Int, quality: Int): ByteArray?

    // What's shown and selected.
    /** Bodies, then sketches (a plane each and their curves), then construction planes (nine numbers each), axes (six each) and points (three each). */
    fun show(bodies: LongArray, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, constructionPlanes: DoubleArray, axes: DoubleArray, points: DoubleArray, refit: Boolean)
    fun selectedPlanes(): IntArray
    /** Lines describing what's selected: lengths, areas, gaps, angles, the body's volume and size. */
    fun measure(): Array<String>
    fun setSection(on: Boolean, ox: Double, oy: Double, oz: Double, nx: Double, ny: Double, nz: Double)
    /** A face's edges as sketch curves on a plane; see jni.cpp. */
    fun faceOutline(body: Long, face: String, plane: DoubleArray): DoubleArray
    fun shownTriangles(): Int
    /** Selects or unselects what's under the point. Returns selected face, edge, sketch region and plane counts. GL thread. */
    fun tap(x: Float, y: Float): IntArray
    /** A click: selects what's under the point in place of the selection, or with add, adds or removes it. Counts as [tap]. GL thread. */
    fun click(x: Float, y: Float, add: Boolean): IntArray
    /** Selects what's in a screen box: partly in it with crossing, else wholly inside. Counts as [tap]. GL thread. */
    fun selectBox(x0: Float, y0: Float, x1: Float, y1: Float, crossing: Boolean, add: Boolean): IntArray
    fun clearSelection()
    fun selectedEdges(): Array<String>
    /** "body number\tface name" each. */
    fun selectedFaces(): Array<String>
    /** Pairs of sketch number and region number. */
    fun selectedRegions(): IntArray
    fun select(edges: Array<String>, regions: IntArray, faces: Array<String>)

    /** A sketch's closed regions; see jni.cpp for the layouts. */
    fun findRegions(kinds: IntArray, ids: IntArray, numbers: DoubleArray): FloatArray

    // The view, on the GL thread.
    fun surfaceCreated()
    fun surfaceChanged(width: Int, height: Int)
    /** True while the view is moving and wants another frame. */
    fun drawFrame(): Boolean
    fun setDensity(density: Float)
    fun orbit(dx: Float, dy: Float)
    fun pan(dx: Float, dy: Float)
    fun zoom(factor: Float)
    /** Zooms towards the point under (x, y), which stays put. */
    fun zoomAt(factor: Float, x: Float, y: Float)
    fun fit()
    /** Turns to look from a direction: yaw round Z from +X and pitch up, in radians. */
    fun viewFrom(yaw: Float, pitch: Float)
    /** Yaw, pitch, viewport width and height, then the last frame's view-projection matrix. */
    fun cameraState(): FloatArray
}

/** File formats, by the numbers the core uses. */
enum class FileFormat(val extensions: List<String>) {
    Stl(listOf("stl")),
    Step(listOf("step", "stp")),
    Iges(listOf("iges", "igs")),
    Obj(listOf("obj")),
    ThreeMf(listOf("3mf"));

    val isMesh get() = this == Stl || this == Obj || this == ThreeMf

    companion object {
        fun forName(name: String): FileFormat? = entries.firstOrNull { name.substringAfterLast('.').lowercase() in it.extensions }

        /** The format for a name shown in the export sheet. */
        fun forLabel(label: String) = when (label) {
            "3MF" -> ThreeMf
            "OBJ" -> Obj
            "STEP" -> Step
            "IGES" -> Iges
            else -> Stl
        }
    }
}
