package com.rm.parrotmetric

import com.rm.parrotmetric.sketch.Arc
import com.rm.parrotmetric.sketch.Circle
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.RegionFinder
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.SketchRegion
import com.rm.parrotmetric.sketch.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** A finished sketch and where it lies. */
class SketchRecord(val name: String, val plane: SketchPlane, val sketch: Sketch)

/** Finds regions with the C++ core. */
val coreRegionFinder = RegionFinder { curves ->
    val kinds = IntArray(curves.size) { curves[it].kind.ordinal }
    val ids = IntArray(curves.size) { curves[it].id }
    val numbers = DoubleArray(curves.size * 7)
    curves.forEachIndexed { i, c ->
        doubleArrayOf(c.x1, c.y1, c.x2, c.y2, c.r, c.a0, c.a1).copyInto(numbers, i * 7)
    }
    parseRegions(Core.findRegions(kinds, ids, numbers))
}

private fun parseRegions(d: FloatArray): List<SketchRegion> {
    var i = 0
    fun next() = d[i++]
    val out = mutableListOf<SketchRegion>()
    repeat(next().toInt()) {
        val area = next().toDouble()
        val ids = List(next().toInt()) { next().toInt() }
        val loops = List(next().toInt()) {
            val n = next().toInt()
            FloatArray(n * 2) { next() }
        }
        out += SketchRegion(loops, ids, area)
    }
    return out
}

/** The finished sketches as polylines in 3D, flattened the way Core.setSketches takes them. */
fun sketchLines(records: List<SketchRecord>): FloatArray {
    val out = mutableListOf<Float>()
    out += records.size.toFloat()
    for (r in records) {
        val s = r.sketch
        val lines = s.curves.filter { !it.construction }.map { c ->
            when (c) {
                is Line -> listOf(s.x(c.a) to s.y(c.a), s.x(c.b) to s.y(c.b))
                is Circle -> arc(s.x(c.centre), s.y(c.centre), s.radius(c), 0.0, 2 * PI)
                is Arc -> {
                    val cx = s.x(c.centre); val cy = s.y(c.centre)
                    val a0 = atan2(s.y(c.start) - cy, s.x(c.start) - cx)
                    var a1 = atan2(s.y(c.end) - cy, s.x(c.end) - cx)
                    while (a1 <= a0) a1 += 2 * PI
                    arc(cx, cy, s.radius(c), a0, a1)
                }
            }
        }
        out += lines.size.toFloat()
        for (pts in lines) {
            out += pts.size.toFloat()
            for ((u, v) in pts) {
                val w = r.plane.toWorld(u, v)
                out += w.x.toFloat(); out += w.y.toFloat(); out += w.z.toFloat()
            }
        }
    }
    return out.toFloatArray()
}

private fun arc(cx: Double, cy: Double, r: Double, a0: Double, a1: Double): List<Pair<Double, Double>> {
    val n = maxOf(8, ((a1 - a0) / (PI / 32)).toInt())
    return (0..n).map { i -> val a = a0 + (a1 - a0) * i / n; cx + r * cos(a) to cy + r * sin(a) }
}

/** The camera's yaw and pitch for looking straight at a plane, with its x to the right. */
fun viewOf(plane: SketchPlane): Pair<Float, Float> {
    val n = plane.normal
    val pitch = asin(n.z.coerceIn(-1.0, 1.0))
    val yaw = atan2(-plane.x.x, plane.x.y)
    return yaw.toFloat() to pitch.toFloat()
}

/** A plane on a flat face, from its centre and outward normal, turned to match the current view. */
fun facePlane(face: DoubleArray, currentYaw: Float, name: String): SketchPlane {
    val n = Vec3(face[3], face[4], face[5])
    val yaw = if (abs(n.z) > 0.999) {
        val quarter = PI / 2
        round(currentYaw / quarter) * quarter
    } else atan2(n.y, n.x)
    val x = Vec3(-sin(yaw), cos(yaw), 0.0)
    return SketchPlane(name, Vec3(face[0], face[1], face[2]), x, n.cross(x))
}
