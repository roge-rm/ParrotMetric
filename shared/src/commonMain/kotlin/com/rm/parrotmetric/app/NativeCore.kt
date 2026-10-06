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
    fun extrude(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, forward: Double, back: Double, taper: Double, thin: Double): Long
    fun revolve(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long
    /** how: 0 join, 1 cut, 2 intersect. */
    fun combine(id: Int, target: Long, tool: Long, how: Int): Long
    fun fillet(id: Int, body: Long, edges: Array<String>, radius: Double, kind: Int, second: Double): Long
    fun offsetFaces(id: Int, body: Long, faces: Array<String>, distance: Double): Long
    fun deleteFaces(id: Int, body: Long, faces: Array<String>): Long
    fun meshEdit(id: Int, body: Long, kind: Int, size: Double, steps: Int): Long
    fun patch(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray): Long
    fun patchEdges(id: Int, body: Long, edges: Array<String>): Long
    fun stitch(id: Int, bodies: LongArray): Long
    fun gather(bodies: LongArray): Long
    fun emboss(id: Int, body: Long, face: String, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, depth: Double, sink: Boolean): Long
    fun thicken(id: Int, body: Long, thickness: Double, both: Boolean): Long
    fun rib(id: Int, body: Long, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, thickness: Double, flip: Boolean, web: Boolean): Long
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
    /** Snap-fit clips, or with [catchPart] the recesses they rest in, at points on a plane, hooks pointing away from [middle]. */
    fun snapFitTool(id: Int, plane: DoubleArray, points: DoubleArray, middle: DoubleArray, length: Double, width: Double, thickness: Double, overhang: Double, catchHeight: Double, gap: Double, catchPart: Boolean, tag: String): Long
    /** What repairing a mesh file would change, as a line to show, or empty. */
    fun repairReport(data: ByteArray, format: Int): String
    fun convertToSolid(id: Int, body: Long): Long
    fun bodyCentre(body: Long): DoubleArray
    /** A sweep of sketch areas along a path: a sketch's curves when pathKinds has any, else a body's named edges. */
    fun sweep(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>): Long
    /** A round tube along a path, given as for sweep; hollow when inner is more than 0. */
    fun pipe(id: Int, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>, diameter: Double, inner: Double): Long
    fun pathPlaces(pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>, count: Int, spacing: Double, turn: Boolean, reverse: Boolean): DoubleArray?
    fun coil(id: Int, plane: DoubleArray, u: Double, v: Double, diameter: Double, pitch: Double, turns: Double, section: Double, square: Boolean): Long
    fun thread(id: Int, body: Long, face: String, pitch: Double, clearance: Double): Long
    /** Rings round the openings in a rim face, [inside] to [outside] mm out from their edges, [height] tall. */
    fun lipTool(id: Int, body: Long, face: String, inside: Double, outside: Double, height: Double, tag: String): Long
    /** Text as outline curves, nine numbers each: kind (0 line, 3 Bézier), start, end, then the two controls. */
    fun textOutline(text: String, height: Double, bold: Boolean, font: Int, data: ByteArray): DoubleArray
    /** Reads a picture (PNG or JPEG) and keeps it under key for canvases; its width and height, or null if it can't be read. */
    fun canvasImage(key: Int, bytes: ByteArray): IntArray?
    /** A loft through one area of each sketch: nine plane numbers and a curve count per sketch, then one pick per sketch. */
    /** With [hasGuide], the guide is the path given as for a sweep. */
    fun loft(id: Int, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ruled: Boolean, twist: Double, hasGuide: Boolean, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>): Long
    /** kind: 0 box, 1 cylinder, 2 sphere, 3 torus, 4 cone; sizes as Kernel.primitive. */
    fun primitive(id: Int, plane: DoubleArray, kind: Int, u: Double, v: Double, a: Double, b: Double, c: Double): Long
    /** The box round a body: x, y, z low, then high. */
    fun bounds(body: Long): DoubleArray
    /** A body cut by another: the pieces outside it, then inside. */
    fun splitBy(id: Int, body: Long, tool: Long): LongArray
    /** How much two bodies overlap, mm³. */
    fun overlapVolume(a: Long, b: Long): Double
    /** The names of the selected corners. */
    fun selectedCorners(): Array<String>
    /** Where a named corner of a solid is, or null. */
    fun corner(body: Long, name: String): DoubleArray?
    /** What shape a named edge or face is: kind, point, direction, size (see Kernel.shapeOf); null if none of those. */
    fun shapeOf(body: Long, name: String, edge: Boolean): DoubleArray?
    /** A point a fraction t along a named edge and the edge's direction there; null if it hasn't got it. */
    fun alongEdge(body: Long, name: String, t: Double): DoubleArray?
    /** A body's volume (mm³), surface area (mm²) and centre of mass x, y, z. */
    fun properties(body: Long): DoubleArray
    /** Where bodies cross a plane, as sketch curves; see jni.cpp. */
    fun section(bodies: LongArray, plane: DoubleArray): DoubleArray
    fun projectView(bodies: LongArray, view: DoubleArray, hidden: Boolean, fast: Double): DoubleArray
    /** Middle and normal of the selected flat part of a mesh, or null. */
    fun selectedMeshPlane(): DoubleArray?
    fun retain(body: Long)
    fun release(body: Long)
    fun isMesh(body: Long): Boolean
    /** format as [Format]'s ordinal, quality 0 fine to 2 coarse; null if none of them can go in that format. */
    fun exportBodies(bodies: LongArray, names: Array<String>, colours: IntArray, format: Int, quality: Int): ByteArray?

    // What's shown and selected.
    /** Bodies, then sketches (a plane each and their curves), then construction planes (nine numbers each), axes (six each) and points (three each). */
    fun show(bodies: LongArray, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, constructionPlanes: DoubleArray, axes: DoubleArray, points: DoubleArray, colours: IntArray, canvases: DoubleArray, refit: Boolean)
    fun selectedPlanes(): IntArray
    /** Lines describing what's selected: lengths, areas, gaps, angles, the body's volume and size. */
    fun measure(): Array<String>
    fun setSection(on: Boolean, ox: Double, oy: Double, oz: Double, nx: Double, ny: Double, nz: Double)
    fun setAnalysis(mode: Int, limit: Double)
    /** A face's edges as sketch curves on a plane; see jni.cpp. */
    fun faceOutline(body: Long, face: String, plane: DoubleArray): DoubleArray
    fun shownTriangles(): Int
    /** How finely solids are meshed for display, 0 low to 2 high; takes effect at the next show. */
    fun setDisplayDetail(level: Int)
    /** Times a short piece of fixed work, in ms, to judge the device. Off the main thread. */
    fun speedTest(): Double
    /** Selects or unselects what's under the point. Returns selected face, edge, sketch region and plane counts. GL thread. */
    fun tap(x: Float, y: Float): IntArray
    /** A click: selects what's under the point in place of the selection, or with add, adds or removes it. Counts as [tap]. GL thread. */
    fun click(x: Float, y: Float, add: Boolean): IntArray
    /** A double click: an edge and those running on smoothly from it. */
    fun clickChain(x: Float, y: Float, add: Boolean): IntArray
    /** Selects what's in a screen box: partly in it with crossing, else wholly inside. Counts as [tap]. GL thread. */
    fun selectBox(x0: Float, y0: Float, x1: Float, y1: Float, crossing: Boolean, add: Boolean): IntArray
    fun clearSelection()
    fun selectedEdges(): Array<String>
    /** "body number\tface name" each. */
    fun selectedFaces(): Array<String>
    /** Pairs of sketch number and region number. */
    fun selectedRegions(): IntArray
    fun select(edges: Array<String>, regions: IntArray, faces: Array<String>, corners: Array<String>)

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
    /** How much of each edge of the view panels cover, pixels: left, top, right, bottom. */
    fun setCovered(left: Float, top: Float, right: Float, bottom: Float)
    /** Turns to look from a direction: yaw round Z from +X and pitch up, in radians. */
    fun viewFrom(yaw: Float, pitch: Float)
    /** Yaw, pitch, viewport width and height, then the last frame's view-projection matrix. */
    fun cameraState(): FloatArray
    /** Sketch areas are picked over bodies in front of them. */
    fun setAreasFirst(on: Boolean)
    /** Starts sculpting a step's packed mesh, else a body, else a shape (0 sphere, 1 block) [size] mm across; see jni.cpp. */
    fun sculptStart(body: Long, packed: ByteArray, shape: Int, size: Double, maxTriangles: Int): Boolean
    fun sculptHit(x: Float, y: Float): Boolean
    fun sculptBegin(x: Float, y: Float, pressure: Float, brush: Int, radius: Float, strength: Float, invert: Boolean, mirror: Int, dynamic: Boolean, detail: Float, pressureSize: Boolean, pressureStrength: Boolean): Boolean
    fun sculptMove(x: Float, y: Float, pressure: Float)
    fun sculptEnd()
    fun sculptUndo(redo: Boolean): Boolean
    /** Can undo, can redo, triangles, average edge in mm. */
    fun sculptInfo(): DoubleArray
    /** 0 clears the mask, 1 inverts it, 2 evens out the triangles at [edge] mm. */
    fun sculptChange(what: Int, edge: Double)
    /** Stops sculpting, giving the packed mesh with [keep]. */
    fun sculptFinish(keep: Boolean): ByteArray
    /** A Sculpt step's body: as saved, or made again on [input], the body it was made from, if that changed. */
    fun sculptedBody(id: Int, packed: ByteArray, input: Long): Long
    /** The mesh being sculpted as it is now, packed, without stopping; empty if none. */
    fun sculptPack(): ByteArray
    /** 0 clay, 1 stone, 2 porcelain, 3 terracotta; [wire] draws its triangles. */
    fun sculptLook(look: Int, wire: Boolean)
    /** An involute gear standing on a plane; angles in radians, see pm::gear. */
    fun gear(id: Int, plane: DoubleArray, u: Double, v: Double, turn: Double, module: Double, teeth: Int, pressureAngle: Double, thickness: Double, helix: Double, herringbone: Boolean, bore: Double, clearance: Double): Long
    /** Threads drawn as a symbol from the next show(): a face of a body and its pitch each. */
    fun threadMarks(bodies: LongArray, faces: Array<String>, pitches: DoubleArray)
    /** Bodies drawn moved by these offsets, x, y and z each, from the next show(), for an exploded view. */
    fun bodyOffsets(bodies: LongArray, offsets: DoubleArray)
    /** The point on a shown mesh body under the last tap or click, and its place in the shown list; null if there's none. */
    fun tappedMeshPoint(): DoubleArray?
    /** A mesh body erased at spots (x, y, z and radius each) and filled. */
    fun meshErase(id: Int, body: Long, spots: DoubleArray): Long
    /** Each separate piece of a body, biggest first. */
    fun separate(id: Int, body: Long): LongArray
    /** A screw, nut or washer; see pm::fastener. */
    fun fastener(id: Int, seat: DoubleArray, kind: Int, d: Double, length: Double, head: Double, headHeight: Double, socket: Double, angle: Double): Long
    /** The camera to come back to: target x, y, z, yaw, pitch and distance in mm. */
    fun currentView(): FloatArray
    /** Moves the camera smoothly to a view as currentView gives it. */
    fun setView(view: FloatArray)
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
