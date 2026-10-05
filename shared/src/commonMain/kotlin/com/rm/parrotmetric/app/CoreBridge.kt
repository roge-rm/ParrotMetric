package com.rm.parrotmetric.app

import com.rm.parrotmetric.design.Kernel
import com.rm.parrotmetric.design.KernelPath
import com.rm.parrotmetric.design.KernelException
import com.rm.parrotmetric.design.Operation
import com.rm.parrotmetric.design.RegionRef
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.RegionFinder
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.SketchRegion
import com.rm.parrotmetric.sketch.Vec3
import com.rm.parrotmetric.ui.design.Viewport

/** Curves as the core takes them: kinds, ids, and eleven numbers each (see curvesOf in jni.cpp). */
private class Curves(curves: List<ProfileCurve>) {
    val kinds = IntArray(curves.size) { curves[it].kind.ordinal }
    val ids = IntArray(curves.size) { curves[it].id }
    val nums = DoubleArray(curves.size * 11).also { n ->
        curves.forEachIndexed { i, c -> doubleArrayOf(c.x1, c.y1, c.x2, c.y2, c.r, c.a0, c.a1, c.cx1, c.cy1, c.cx2, c.cy2).copyInto(n, i * 11) }
    }
}

private fun SketchPlane.numbers() = doubleArrayOf(origin.x, origin.y, origin.z, x.x, x.y, x.z, y.x, y.y, y.z)

/** Curves from the core, 12 numbers each after a count: kind, then the ends, radius and angles. */
private fun unpackCurves(d: DoubleArray): List<ProfileCurve> = List(d[0].toInt()) { i ->
    val o = 1 + i * 12
    ProfileCurve(ProfileCurve.Kind.entries[d[o].toInt()], 0, d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5], d[o + 6], d[o + 7])
}

/** Finds regions with the C++ core. */
fun coreRegionFinder(core: NativeCore) = RegionFinder { curves ->
    val c = Curves(curves)
    val d = core.findRegions(c.kinds, c.ids, c.nums)
    var i = 0
    fun next() = d[i++]
    List(next().toInt()) {
        val area = next().toDouble()
        val u = next().toDouble()
        val v = next().toDouble()
        val ids = List(next().toInt()) { next().toInt() }
        val loops = List(next().toInt()) {
            val n = next().toInt()
            FloatArray(n * 2) { next() }
        }
        SketchRegion(loops, ids, area, u, v)
    }
}

/** The design's geometry, done by the C++ core. */
class CoreKernel(private val core: NativeCore) : Kernel {
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        throw KernelException(e.message ?: "That couldn't be done")
    }

    override fun section(bodies: List<Long>, plane: SketchPlane): List<ProfileCurve>? =
        try { unpackCurves(core.section(bodies.toLongArray(), plane.numbers())) } catch (e: RuntimeException) { null }

    override fun faceOutline(body: Long, face: String, plane: SketchPlane): List<ProfileCurve>? =
        try { unpackCurves(core.faceOutline(body, face, plane.numbers())) } catch (e: RuntimeException) { null }

    private class Picks(regions: List<RegionRef>) {
        val counts = IntArray(regions.size) { regions[it].curveIds.size }
        val ids = regions.flatMap { it.curveIds }.toIntArray()
        val points = regions.flatMap { listOf(it.u, it.v) }.toDoubleArray()
    }

    override fun extrude(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, forward: Double, back: Double, taper: Double, thin: Double): Long {
        val c = Curves(curves)
        val p = Picks(regions)
        return call { core.extrude(id, plane.numbers(), c.kinds, c.ids, c.nums, p.counts, p.ids, p.points, forward, back, taper, thin) }
    }

    override fun revolve(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long {
        val c = Curves(curves)
        val p = Picks(regions)
        return call { core.revolve(id, plane.numbers(), c.kinds, c.ids, c.nums, p.counts, p.ids, p.points, ax, ay, dx, dy, angle) }
    }

    override fun combine(id: Int, target: Long, tool: Long, how: Operation): Long =
        call { core.combine(id, target, tool, when (how) { Operation.Cut -> 1; Operation.Intersect -> 2; else -> 0 }) }

    override fun fillet(id: Int, body: Long, edges: List<String>, radius: Double, kind: Int, second: Double) =
        call { core.fillet(id, body, edges.toTypedArray(), radius, kind, second) }
    override fun offsetFaces(id: Int, body: Long, faces: List<String>, distance: Double) = call { core.offsetFaces(id, body, faces.toTypedArray(), distance) }
    override fun deleteFaces(id: Int, body: Long, faces: List<String>) = call { core.deleteFaces(id, body, faces.toTypedArray()) }
    override fun meshEdit(id: Int, body: Long, kind: Int, size: Double, steps: Int) = call { core.meshEdit(id, body, kind, size, steps) }
    override fun patch(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>): Long {
        val c = Curves(curves)
        val p = Picks(regions)
        return call { core.patch(id, plane.numbers(), c.kinds, c.ids, c.nums, p.counts, p.ids, p.points) }
    }
    override fun patchEdges(id: Int, body: Long, edges: List<String>) = call { core.patchEdges(id, body, edges.toTypedArray()) }
    override fun stitch(id: Int, bodies: List<Long>) = call { core.stitch(id, bodies.toLongArray()) }
    override fun emboss(id: Int, body: Long, face: String, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, depth: Double, sink: Boolean): Long {
        val c = Curves(curves)
        val p = Picks(regions)
        return call { core.emboss(id, body, face, plane.numbers(), c.kinds, c.ids, c.nums, p.counts, p.ids, p.points, depth, sink) }
    }
    override fun thicken(id: Int, body: Long, thickness: Double, both: Boolean) = call { core.thicken(id, body, thickness, both) }
    override fun rib(id: Int, body: Long, plane: SketchPlane, curves: List<ProfileCurve>, thickness: Double, flip: Boolean, web: Boolean): Long {
        val c = Curves(curves)
        return call { core.rib(id, body, plane.numbers(), c.kinds, c.ids, c.nums, thickness, flip, web) }
    }
    override fun chamfer(id: Int, body: Long, edges: List<String>, distance: Double, kind: Int, second: Double, flip: Boolean) =
        call { core.chamfer(id, body, edges.toTypedArray(), distance, kind, second, flip) }
    override fun overlaps(a: Long, b: Long) = call { core.overlaps(a, b) }
    override fun facePlane(body: Long, face: String): DoubleArray? = call { core.facePlane(body, face) }
    override fun faceNames(body: Long) = call { core.faceNames(body).toList() }
    override fun import(id: Int, data: ByteArray, format: Int) = call { core.importBody(id, data, format) }
    override fun shell(id: Int, body: Long, open: List<String>, thickness: Double) = call { core.shell(id, body, open.toTypedArray(), thickness) }
    override fun draft(id: Int, body: Long, faces: List<String>, neutral: String, angle: Double) = call { core.draft(id, body, faces.toTypedArray(), neutral, angle) }
    override fun transform(id: Int, body: Long, m: DoubleArray, tag: String) = call { core.transform(id, body, m, tag) }
    override fun split(id: Int, body: Long, origin: com.rm.parrotmetric.sketch.Vec3, normal: com.rm.parrotmetric.sketch.Vec3) =
        call { core.split(id, body, doubleArrayOf(origin.x, origin.y, origin.z, normal.x, normal.y, normal.z)).toList() }
    override fun holeTool(id: Int, plane: SketchPlane, at: List<Pair<Double, Double>>, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double) =
        call { core.holeTool(id, plane.numbers(), at.flatMap { listOf(it.first, it.second) }.toDoubleArray(), diameter, depth, kind, topDiameter, topDepth) }
    override fun convertToSolid(id: Int, body: Long) = call { core.convertToSolid(id, body) }
    override fun centre(body: Long) = call { core.bodyCentre(body).let { Vec3(it[0], it[1], it[2]) } }
    override fun primitive(id: Int, plane: SketchPlane, kind: Int, u: Double, v: Double, a: Double, b: Double, c: Double) =
        call { core.primitive(id, plane.numbers(), kind, u, v, a, b, c) }
    override fun bounds(body: Long) = call { core.bounds(body) }
    override fun properties(body: Long) = call { core.properties(body) }
    override fun splitBy(id: Int, body: Long, tool: Long) = call { core.splitBy(id, body, tool).toList() }
    override fun overlapVolume(a: Long, b: Long) = call { core.overlapVolume(a, b) }
    /** A path as the core takes it: curves on a plane, or a body's edges. */
    private fun withPath(path: KernelPath, call: (DoubleArray, Curves, Long, Array<String>) -> Long): Long {
        val c = Curves(path.curves)
        return call(path.plane?.numbers() ?: DoubleArray(9), c, path.body, path.edges.toTypedArray())
    }

    override fun sweep(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, path: KernelPath): Long {
        val c = Curves(curves)
        val p = Picks(regions)
        return call {
            withPath(path) { pp, pc, body, edges ->
                core.sweep(id, plane.numbers(), c.kinds, c.ids, c.nums, p.counts, p.ids, p.points, pp, pc.kinds, pc.ids, pc.nums, body, edges)
            }
        }
    }

    override fun pipe(id: Int, path: KernelPath, diameter: Double, inner: Double) =
        call { withPath(path) { pp, pc, body, edges -> core.pipe(id, pp, pc.kinds, pc.ids, pc.nums, body, edges, diameter, inner) } }

    override fun pathPlaces(path: KernelPath, count: Int, spacing: Double, turn: Boolean, reverse: Boolean): List<DoubleArray> = call {
        val c = Curves(path.curves)
        val flat = core.pathPlaces(path.plane?.numbers() ?: DoubleArray(9), c.kinds, c.ids, c.nums, path.body, path.edges.toTypedArray(), count, spacing, turn, reverse)
            ?: DoubleArray(0)
        (0 until flat.size / 12).map { flat.copyOfRange(it * 12, it * 12 + 12) }
    }

    override fun coil(id: Int, plane: SketchPlane, u: Double, v: Double, diameter: Double, pitch: Double, turns: Double, section: Double, square: Boolean) =
        call { core.coil(id, plane.numbers(), u, v, diameter, pitch, turns, section, square) }

    override fun thread(id: Int, body: Long, face: String, pitch: Double) = call { core.thread(id, body, face, pitch) }

    override fun loft(id: Int, sections: List<Triple<SketchPlane, List<ProfileCurve>, RegionRef>>, ruled: Boolean): Long {
        val c = Curves(sections.flatMap { it.second })
        val p = Picks(sections.map { it.third })
        val planes = sections.flatMap { it.first.numbers().asList() }.toDoubleArray()
        val counts = IntArray(sections.size) { sections[it].second.size }
        return call { core.loft(id, planes, counts, c.kinds, c.ids, c.nums, p.counts, p.ids, p.points, ruled) }
    }

    override fun corner(body: Long, name: String) = core.corner(body, name)?.let { Vec3(it[0], it[1], it[2]) }
    override fun shapeOf(body: Long, name: String, edge: Boolean) = core.shapeOf(body, name, edge)
    override fun alongEdge(body: Long, name: String, t: Double) = core.alongEdge(body, name, t)
    override fun signature(body: Long, name: String, edge: Boolean) = core.signature(body, name, edge)
    override fun relocate(body: Long, signature: DoubleArray) = core.relocate(body, signature)
    override fun retain(body: Long) = core.retain(body)
    override fun release(body: Long) = core.release(body)
}

/** The 3D view's side of the design editor. [gl] runs a call on the GL thread. */
class CoreViewport(private val core: NativeCore, private val gl: (() -> Unit) -> Unit) : Viewport {
    override fun show(
        bodies: List<Long>, sketches: List<Pair<SketchPlane, List<ProfileCurve>>>,
        planes: List<SketchPlane>, axes: List<Pair<Vec3, Vec3>>, points: List<Vec3>, colours: List<Int>,
        canvases: List<com.rm.parrotmetric.design.PlacedCanvas>, refit: Boolean,
    ) {
        val canvasNumbers = canvases.flatMap { c -> listOf(c.featureId.toDouble()) + c.corners.flatMap { listOf(it.x, it.y, it.z) } + c.opacity }.toDoubleArray()
        val all = sketches.flatMap { it.second }
        val c = Curves(all)
        val sketchPlanes = sketches.flatMap { it.first.numbers().asList() }.toDoubleArray()
        val construction = planes.flatMap { it.numbers().asList() }.toDoubleArray()
        val axisNumbers = axes.flatMap { (p, d) -> listOf(p.x, p.y, p.z, d.x, d.y, d.z) }.toDoubleArray()
        core.show(bodies.toLongArray(), sketchPlanes, IntArray(sketches.size) { sketches[it].second.size }, c.kinds, c.ids, c.nums, construction, axisNumbers,
            points.flatMap { listOf(it.x, it.y, it.z) }.toDoubleArray(), colours.toIntArray(), canvasNumbers, refit)
        gl {}
    }

    override fun canvasImage(key: Int, bytes: ByteArray) = core.canvasImage(key, bytes)
    override fun selectedPlanes() = core.selectedPlanes().toList()
    override fun measure() = core.measure().toList()
    override fun setAnalysis(mode: Int, limit: Double) = core.setAnalysis(mode, limit)

    override fun setSection(on: Boolean, origin: Vec3, normal: Vec3) {
        core.setSection(on, origin.x, origin.y, origin.z, normal.x, normal.y, normal.z)
        gl {}
    }

    override fun selectedMeshPlane() = core.selectedMeshPlane()?.let { Vec3(it[0], it[1], it[2]) to Vec3(it[3], it[4], it[5]) }

    override fun section(bodies: List<Long>, plane: SketchPlane): List<ProfileCurve> = unpackCurves(core.section(bodies.toLongArray(), plane.numbers()))

    override fun faceOutline(body: Long, face: String, plane: SketchPlane): List<ProfileCurve> = unpackCurves(core.faceOutline(body, face, plane.numbers()))

    override fun selectedEdges() = core.selectedEdges().toList()
    override fun selectedCorners() = core.selectedCorners().toList()
    override fun selectedFaces() = core.selectedFaces().map { s -> s.substringBefore('\t').toInt() to s.substringAfter('\t') }
    override fun selectedRegions() = core.selectedRegions().let { r -> List(r.size / 2) { r[2 * it] to r[2 * it + 1] } }

    override fun select(edges: List<String>, regions: List<Pair<Int, Int>>, faces: List<String>, corners: List<String>) {
        core.select(edges.toTypedArray(), regions.flatMap { listOf(it.first, it.second) }.toIntArray(), faces.toTypedArray(), corners.toTypedArray())
        gl {}
    }

    override fun clearSelection() {
        core.clearSelection()
        gl {}
    }

    override fun viewFrom(yaw: Float, pitch: Float) = gl { core.viewFrom(yaw, pitch) }
    override fun fit() = gl { core.fit() }
    override fun isMesh(body: Long) = core.isMesh(body)
    override fun triangles() = core.shownTriangles()
}

/** The camera's yaw and pitch for looking straight at a plane, with its x to the right where the plane allows. */
fun viewOf(plane: SketchPlane): Pair<Float, Float> {
    val n = plane.normal
    val pitch = kotlin.math.asin(n.z.coerceIn(-1.0, 1.0))
    val yaw = if (kotlin.math.abs(n.z) > 0.999) kotlin.math.atan2(-plane.x.x, plane.x.y) else kotlin.math.atan2(n.y, n.x)
    return yaw.toFloat() to pitch.toFloat()
}
