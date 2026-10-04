package com.rm.parrotmetric

import com.rm.parrotmetric.design.Kernel
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

/** Finds regions with the C++ core. */
val coreRegionFinder = RegionFinder { curves ->
    val c = Curves(curves)
    val d = Core.findRegions(c.kinds, c.ids, c.nums)
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
object CoreKernel : Kernel {
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        throw KernelException(e.message ?: "That couldn't be done")
    }

    private class Picks(regions: List<RegionRef>) {
        val counts = IntArray(regions.size) { regions[it].curveIds.size }
        val ids = regions.flatMap { it.curveIds }.toIntArray()
        val points = regions.flatMap { listOf(it.u, it.v) }.toDoubleArray()
    }

    override fun extrude(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, forward: Double, back: Double): Long {
        val c = Curves(curves)
        val p = Picks(regions)
        return call { Core.extrude(id, plane.numbers(), c.kinds, c.ids, c.nums, p.counts, p.ids, p.points, forward, back) }
    }

    override fun revolve(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long {
        val c = Curves(curves)
        val p = Picks(regions)
        return call { Core.revolve(id, plane.numbers(), c.kinds, c.ids, c.nums, p.counts, p.ids, p.points, ax, ay, dx, dy, angle) }
    }

    override fun combine(id: Int, target: Long, tool: Long, how: Operation): Long =
        call { Core.combine(id, target, tool, when (how) { Operation.Cut -> 1; Operation.Intersect -> 2; else -> 0 }) }

    override fun fillet(id: Int, body: Long, edges: List<String>, radius: Double) = call { Core.fillet(id, body, edges.toTypedArray(), radius) }
    override fun chamfer(id: Int, body: Long, edges: List<String>, distance: Double) = call { Core.chamfer(id, body, edges.toTypedArray(), distance) }
    override fun overlaps(a: Long, b: Long) = call { Core.overlaps(a, b) }
    override fun facePlane(body: Long, face: String): DoubleArray? = call { Core.facePlane(body, face) }
    override fun faceNames(body: Long) = call { Core.faceNames(body).toList() }
    override fun import(id: Int, data: ByteArray, format: Int) = call { Core.importBody(id, data, format) }
    override fun shell(id: Int, body: Long, open: List<String>, thickness: Double) = call { Core.shell(id, body, open.toTypedArray(), thickness) }
    override fun draft(id: Int, body: Long, faces: List<String>, neutral: String, angle: Double) = call { Core.draft(id, body, faces.toTypedArray(), neutral, angle) }
    override fun transform(id: Int, body: Long, m: DoubleArray, tag: String) = call { Core.transform(id, body, m, tag) }
    override fun split(id: Int, body: Long, origin: com.rm.parrotmetric.sketch.Vec3, normal: com.rm.parrotmetric.sketch.Vec3) =
        call { Core.split(id, body, doubleArrayOf(origin.x, origin.y, origin.z, normal.x, normal.y, normal.z)).toList() }
    override fun holeTool(id: Int, plane: SketchPlane, at: List<Pair<Double, Double>>, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double) =
        call { Core.holeTool(id, plane.numbers(), at.flatMap { listOf(it.first, it.second) }.toDoubleArray(), diameter, depth, kind, topDiameter, topDepth) }
    override fun retain(body: Long) = Core.retain(body)
    override fun release(body: Long) = Core.release(body)
}

/** The 3D view's side of the design editor. [gl] runs a call on the GL thread. */
class CoreViewport(private val gl: (() -> Unit) -> Unit) : Viewport {
    override fun show(
        bodies: List<Long>, sketches: List<Pair<SketchPlane, List<ProfileCurve>>>,
        planes: List<SketchPlane>, axes: List<Pair<Vec3, Vec3>>, refit: Boolean,
    ) {
        val all = sketches.flatMap { it.second }
        val c = Curves(all)
        val sketchPlanes = sketches.flatMap { it.first.numbers().asList() }.toDoubleArray()
        val construction = planes.flatMap { it.numbers().asList() }.toDoubleArray()
        val axisNumbers = axes.flatMap { (p, d) -> listOf(p.x, p.y, p.z, d.x, d.y, d.z) }.toDoubleArray()
        Core.show(bodies.toLongArray(), sketchPlanes, IntArray(sketches.size) { sketches[it].second.size }, c.kinds, c.ids, c.nums, construction, axisNumbers, refit)
        gl {}
    }

    override fun selectedPlanes() = Core.selectedPlanes().toList()

    override fun faceOutline(body: Long, face: String, plane: SketchPlane): List<ProfileCurve> {
        val d = Core.faceOutline(body, face, plane.numbers())
        return List(d[0].toInt()) { i ->
            val o = 1 + i * 12
            ProfileCurve(
                ProfileCurve.Kind.entries[d[o].toInt()], 0, d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5], d[o + 6], d[o + 7],
            )
        }
    }

    override fun selectedEdges() = Core.selectedEdges().toList()
    override fun selectedFaces() = Core.selectedFaces().map { s -> s.substringBefore('\t').toInt() to s.substringAfter('\t') }
    override fun selectedRegions() = Core.selectedRegions().let { r -> List(r.size / 2) { r[2 * it] to r[2 * it + 1] } }

    override fun select(edges: List<String>, regions: List<Pair<Int, Int>>, faces: List<String>) {
        Core.select(edges.toTypedArray(), regions.flatMap { listOf(it.first, it.second) }.toIntArray(), faces.toTypedArray())
        gl {}
    }

    override fun clearSelection() {
        Core.clearSelection()
        gl {}
    }

    override fun viewFrom(yaw: Float, pitch: Float) = gl { Core.viewFrom(yaw, pitch) }
    override fun fit() = gl { Core.fit() }
    override fun isMesh(body: Long) = Core.isMesh(body)
    override fun triangles() = Core.shownTriangles()
}

/** The camera's yaw and pitch for looking straight at a plane, with its x to the right where the plane allows. */
fun viewOf(plane: SketchPlane): Pair<Float, Float> {
    val n = plane.normal
    val pitch = kotlin.math.asin(n.z.coerceIn(-1.0, 1.0))
    val yaw = if (kotlin.math.abs(n.z) > 0.999) kotlin.math.atan2(-plane.x.x, plane.x.y) else kotlin.math.atan2(n.y, n.x)
    return yaw.toFloat() to pitch.toFloat()
}
