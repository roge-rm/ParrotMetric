package com.rm.parrotmetric

import com.rm.parrotmetric.app.NativeCore

/** The C++ core through JNI (app/src/main/cpp/jni.cpp), on Android and desktop. */
object Core : NativeCore {
    init {
        System.loadLibrary("parrotmetric")
    }

    override external fun setScratchDirectory(path: String)

    // Kernel. Bodies are handles; each made comes retained once.
    override external fun extrude(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, forward: Double, back: Double, taper: Double, thin: Double): Long
    override external fun revolve(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long
    /** how: 0 join, 1 cut, 2 intersect. */
    override external fun combine(id: Int, target: Long, tool: Long, how: Int): Long
    override external fun fillet(id: Int, body: Long, edges: Array<String>, radius: Double): Long
    override external fun chamfer(id: Int, body: Long, edges: Array<String>, distance: Double, kind: Int, second: Double, flip: Boolean): Long
    override external fun overlaps(a: Long, b: Long): Boolean
    override external fun facePlane(body: Long, name: String): DoubleArray
    override external fun faceNames(body: Long): Array<String>
    /** Where a named face (or edge) is and how big, to find it again by shape; null if the body hasn't got it. */
    override external fun signature(body: Long, name: String, edge: Boolean): DoubleArray?
    /** The face or edge most like a signature, or null if none is close enough. */
    override external fun relocate(body: Long, signature: DoubleArray): String?
    override external fun importBody(id: Int, data: ByteArray, format: Int): Long
    override external fun shell(id: Int, body: Long, open: Array<String>, thickness: Double): Long
    override external fun draft(id: Int, body: Long, faces: Array<String>, neutral: String, angle: Double): Long
    override external fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long
    /** plane: origin then normal. */
    override external fun split(id: Int, body: Long, plane: DoubleArray): LongArray
    override external fun holeTool(id: Int, plane: DoubleArray, points: DoubleArray, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double): Long
    /** What repairing a mesh file would change, as a line to show, or empty. */
    override external fun repairReport(data: ByteArray, format: Int): String
    override external fun convertToSolid(id: Int, body: Long): Long
    override external fun bodyCentre(body: Long): DoubleArray
    override external fun sweep(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>): Long
    override external fun pipe(id: Int, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>, diameter: Double, inner: Double): Long
    override external fun coil(id: Int, plane: DoubleArray, u: Double, v: Double, diameter: Double, pitch: Double, turns: Double, section: Double, square: Boolean): Long
    override external fun thread(id: Int, body: Long, face: String, pitch: Double): Long
    override external fun textOutline(text: String, height: Double, bold: Boolean): DoubleArray
    override external fun canvasImage(key: Int, bytes: ByteArray): IntArray?
    override external fun loft(id: Int, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ruled: Boolean): Long
    override external fun primitive(id: Int, plane: DoubleArray, kind: Int, u: Double, v: Double, a: Double, b: Double, c: Double): Long
    override external fun bounds(body: Long): DoubleArray
    override external fun splitBy(id: Int, body: Long, tool: Long): LongArray
    override external fun overlapVolume(a: Long, b: Long): Double
    override external fun selectedCorners(): Array<String>
    override external fun corner(body: Long, name: String): DoubleArray?
    override external fun shapeOf(body: Long, name: String, edge: Boolean): DoubleArray?
    override external fun alongEdge(body: Long, name: String, t: Double): DoubleArray?
    override external fun properties(body: Long): DoubleArray
    /** Where bodies cross a plane, as sketch curves; see jni.cpp. */
    override external fun section(bodies: LongArray, plane: DoubleArray): DoubleArray
    /** Middle and normal of the selected flat part of a mesh, or null. */
    override external fun selectedMeshPlane(): DoubleArray?
    override external fun retain(body: Long)
    override external fun release(body: Long)
    override external fun isMesh(body: Long): Boolean
    /** format as [Format]'s ordinal, quality 0 fine to 2 coarse; null if none of them can go in that format. */
    override external fun exportBodies(bodies: LongArray, names: Array<String>, colours: IntArray, format: Int, quality: Int): ByteArray?

    // What's shown and selected.
    /** Bodies, then sketches (a plane each and their curves), then construction planes (nine numbers each), axes (six each) and points (three each). */
    override external fun show(bodies: LongArray, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, constructionPlanes: DoubleArray, axes: DoubleArray, points: DoubleArray, colours: IntArray, canvases: DoubleArray, refit: Boolean)
    override external fun selectedPlanes(): IntArray
    /** Lines describing what's selected: lengths, areas, gaps, angles, the body's volume and size. */
    override external fun measure(): Array<String>
    override external fun setSection(on: Boolean, ox: Double, oy: Double, oz: Double, nx: Double, ny: Double, nz: Double)
    /** A face's edges as sketch curves on a plane; see jni.cpp. */
    override external fun faceOutline(body: Long, face: String, plane: DoubleArray): DoubleArray
    override external fun shownTriangles(): Int
    override external fun setDisplayDetail(level: Int)
    override external fun speedTest(): Double
    /** Selects or unselects what's under the point. Returns selected face, edge, sketch region and plane counts. GL thread. */
    override external fun tap(x: Float, y: Float): IntArray
    /** A click: selects what's under the point in place of the selection, or with add, adds or removes it. Counts as [tap]. GL thread. */
    override external fun click(x: Float, y: Float, add: Boolean): IntArray
    /** Selects what's in a screen box: partly in it with crossing, else wholly inside. Counts as [tap]. GL thread. */
    override external fun selectBox(x0: Float, y0: Float, x1: Float, y1: Float, crossing: Boolean, add: Boolean): IntArray
    override external fun clearSelection()
    override external fun selectedEdges(): Array<String>
    /** "body number\tface name" each. */
    override external fun selectedFaces(): Array<String>
    /** Pairs of sketch number and region number. */
    override external fun selectedRegions(): IntArray
    override external fun select(edges: Array<String>, regions: IntArray, faces: Array<String>, corners: Array<String>)

    /** A sketch's closed regions; see jni.cpp for the layouts. */
    override external fun findRegions(kinds: IntArray, ids: IntArray, numbers: DoubleArray): FloatArray

    // The view, on the GL thread.
    override external fun surfaceCreated()
    override external fun surfaceChanged(width: Int, height: Int)
    /** True while the view is moving and wants another frame. */
    override external fun drawFrame(): Boolean
    override external fun setDensity(density: Float)
    override external fun orbit(dx: Float, dy: Float)
    override external fun pan(dx: Float, dy: Float)
    override external fun zoom(factor: Float)
    /** Zooms towards the point under (x, y), which stays put. */
    override external fun zoomAt(factor: Float, x: Float, y: Float)
    override external fun fit()
    /** Turns to look from a direction: yaw round Z from +X and pitch up, in radians. */
    override external fun viewFrom(yaw: Float, pitch: Float)
    /** Yaw, pitch, viewport width and height, then the last frame's view-projection matrix. */
    override external fun cameraState(): FloatArray
}
