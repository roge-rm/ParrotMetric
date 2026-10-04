package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import com.rm.parrotmetric.sketch.profileCurves

/**
 * A step in the design's history. Features hold only their inputs; the
 * geometry is rebuilt from them. [key] changes whenever anything that
 * affects the result changes, which is how a rebuild knows where to start.
 */
sealed class Feature {
    abstract val id: Int
    abstract val name: String
    abstract fun key(): Any
}

/** Where a sketch lies: a fixed plane, or a flat face found by name when the history is rebuilt. */
sealed class PlaneRef {
    data class Fixed(val plane: SketchPlane) : PlaneRef()

    /** On a face, with its x along [x] as far as the face allows. */
    data class OnFace(val face: String, val x: Vec3) : PlaneRef()
}

class SketchFeature(override val id: Int, override val name: String, val plane: PlaneRef, val sketch: Sketch) : Feature() {
    override fun key(): Any = listOf(id, plane, sketch.profileCurves())
}

/** Which closed area of a sketch: the curves round it, and a point inside it when it was picked. */
data class RegionRef(val curveIds: List<Int>, val u: Double, val v: Double)

enum class Operation { NewBody, Join, Cut, Intersect }

/** Sweeps sketch areas straight out of the sketch plane, [forward] in front and [back] behind (mm). */
data class ExtrudeFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int,
    val regions: List<RegionRef>,
    val forward: Double,
    val back: Double,
    val operation: Operation,
) : Feature() {
    override fun key() = this
}

/** What a revolve turns round: a line in its sketch, or the sketch's own x or y axis. */
sealed class AxisRef {
    data class SketchLine(val curveId: Int) : AxisRef()
    data object SketchX : AxisRef()
    data object SketchY : AxisRef()
}

/** Turns sketch areas round an axis by [angle] radians. */
data class RevolveFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int,
    val regions: List<RegionRef>,
    val axis: AxisRef,
    val angle: Double,
    val operation: Operation,
) : Feature() {
    override fun key() = this
}

/** Rounds edges, found by name. */
data class FilletFeature(override val id: Int, override val name: String, val edges: List<String>, val radius: Double) : Feature() {
    override fun key() = this
}

/** Bevels edges, found by name, by [distance] back along each face. */
data class ChamferFeature(override val id: Int, override val name: String, val edges: List<String>, val distance: Double) : Feature() {
    override fun key() = this
}

/** A body read from a file. format: 0 STL (a mesh), 1 STEP, 2 IGES. */
class ImportFeature(override val id: Int, override val name: String, val data: ByteArray, val format: Int) : Feature() {
    override fun key(): Any = listOf(id, name, data, format)
}

/** A sketch's curves as the kernel takes them, for a feature that uses it. */
internal fun SketchFeature.curves(): List<ProfileCurve> = sketch.profileCurves()
