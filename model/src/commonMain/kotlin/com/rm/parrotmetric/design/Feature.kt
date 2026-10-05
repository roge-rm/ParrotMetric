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

    /**
     * On a face, with its x along [x] as far as the face allows. Its origin is
     * where the design's origin falls on the face's plane, so it stays put as
     * the face grows; with [fromOrigin] false, as in files from before, the
     * middle of the face.
     */
    data class OnFace(val face: String, val x: Vec3, val fromOrigin: Boolean = true) : PlaneRef() {
        /** The origin, given the face's [middle] and its unit [normal]. */
        fun origin(middle: Vec3, normal: Vec3): Vec3 = if (fromOrigin) normal * middle.dot(normal) else middle
    }

    /** A construction plane made earlier in the history. */
    data class Construction(val featureId: Int) : PlaneRef()
}

class SketchFeature(override val id: Int, override val name: String, val plane: PlaneRef, val sketch: Sketch) : Feature() {
    override fun key(): Any = listOf(id, plane, sketch.profileCurves())
}

/** Which closed area of a sketch: the curves round it, and a point inside it when it was picked. */
data class RegionRef(val curveIds: List<Int>, val u: Double, val v: Double)

enum class Operation { NewBody, Join, Cut, Intersect }

/**
 * Sweeps sketch areas straight out of the sketch plane, [forward] in front and
 * [back] behind (mm), or in front as far as [upTo] when that's set. A [taper]
 * (radians) leans the sides in going forward, pivoting at the sketch plane.
 */
data class ExtrudeFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int,
    val regions: List<RegionRef>,
    val forward: Double,
    val back: Double,
    val operation: Operation,
    val taper: Double = 0.0,
    val upTo: PlaneRef? = null,
    /** Right through every body, in the direction(s) [forward] and [back] go. */
    val throughAll: Boolean = false,
    /** Starts this far (mm) in front of the sketch plane. */
    val offset: Double = 0.0,
    /** A wall this thick (mm) inside the areas' edges, or 0 for solid. */
    val thin: Double = 0.0,
) : Feature() {
    override fun key() = this
}

enum class PrimitiveKind { Box, Cylinder, Sphere, Torus, Cone }

/**
 * A simple solid standing on a plane, centred at ([u], [v]) on it. Sizes
 * (mm): box width [a], depth [b], height [c]; cylinder diameter [a], height
 * [b]; sphere diameter [a]; torus ring diameter [a], tube diameter [b]; cone
 * base diameter [a], top diameter [b], height [c].
 */
data class PrimitiveFeature(
    override val id: Int,
    override val name: String,
    val kind: PrimitiveKind,
    val plane: PlaneRef,
    val u: Double,
    val v: Double,
    val a: Double,
    val b: Double,
    val c: Double,
    val operation: Operation,
    /** Grown behind its plane instead of in front. */
    val flip: Boolean = false,
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

/** How a fillet is measured. */
enum class FilletKind { Constant, Variable, Chord }

/**
 * Rounds edges, found by name: [radius] all along, or for [FilletKind.Variable]
 * from [radius] at each edge's start to [second] at its end, or for
 * [FilletKind.Chord] [radius] straight across from face to face.
 */
data class FilletFeature(
    override val id: Int,
    override val name: String,
    val edges: List<String>,
    val radius: Double,
    val kind: FilletKind = FilletKind.Constant,
    val second: Double = 0.0,
) : Feature() {
    override fun key() = this
}

/** How a chamfer is measured. */
enum class ChamferKind { Equal, TwoDistances, DistanceAngle }

/**
 * Bevels edges, found by name: [distance] back along each face, or along one
 * face with [second] along the other (mm) or at [second] radians to it. [flip]
 * swaps which face gets [distance].
 */
data class ChamferFeature(
    override val id: Int,
    override val name: String,
    val edges: List<String>,
    val distance: Double,
    val kind: ChamferKind = ChamferKind.Equal,
    val second: Double = 0.0,
    val flip: Boolean = false,
) : Feature() {
    override fun key() = this
}

/** A body read from a file. format: 0 STL (a mesh), 1 STEP, 2 IGES. */
class ImportFeature(override val id: Int, override val name: String, val data: ByteArray, val format: Int) : Feature() {
    override fun key(): Any = listOf(id, name, data, format)
}

/** A sketch's curves as the kernel takes them, for a feature that uses it. */
internal fun SketchFeature.curves(): List<ProfileCurve> = sketch.profileCurves()
