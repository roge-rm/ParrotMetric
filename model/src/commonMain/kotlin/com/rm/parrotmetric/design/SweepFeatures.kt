package com.rm.parrotmetric.design

/** What a sweep or pipe follows: a sketch's curves, or edges of a body by name. */
sealed class PathRef {
    data class Sketch(val sketchId: Int) : PathRef()
    data class Edges(val names: List<String>) : PathRef()
}

/** Sketch areas swept along a path. */
data class SweepFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int,
    val regions: List<RegionRef>,
    val path: PathRef,
    val operation: Operation,
) : Feature() {
    override fun key() = this
}

/** A round tube along a path, [diameter] across, hollow to [inner] when that's more than 0 (mm). */
data class PipeFeature(
    override val id: Int,
    override val name: String,
    val path: PathRef,
    val diameter: Double,
    val inner: Double,
    val operation: Operation,
) : Feature() {
    override fun key() = this
}

/**
 * A coil standing on a plane at ([u], [v]): [diameter] across the middle of
 * its wire, rising [pitch] a turn for [turns] turns, its wire [section] across,
 * round or [square] (mm).
 */
data class CoilFeature(
    override val id: Int,
    override val name: String,
    val plane: PlaneRef,
    val u: Double,
    val v: Double,
    val diameter: Double,
    val pitch: Double,
    val turns: Double,
    val section: Double,
    val square: Boolean,
    val operation: Operation,
) : Feature() {
    override fun key() = this
}

/**
 * An involute gear standing on a plane at ([u], [v]): [module] mm of pitch
 * diameter a tooth, [teeth] of them, [thickness] mm high. [pressureAngle] and
 * [helix] are radians, the helix 0 for straight teeth; [herringbone] turns
 * them back halfway up. [bore] is a hole's diameter, 0 for none, and
 * [clearance] mm comes off each tooth at the pitch circle. A tooth points
 * [turn] radians round from the plane's x.
 *
 * With [meshWith], another gear step's id, it goes beside that one instead,
 * [around] radians round from its x, turned to mesh, on its plane and with
 * its module, pressure angle and helix (the other hand); [plane], [u], [v],
 * [turn] and those are then unused.
 */
data class GearFeature(
    override val id: Int,
    override val name: String,
    val plane: PlaneRef,
    val u: Double,
    val v: Double,
    val module: Double,
    val teeth: Int,
    val thickness: Double,
    val pressureAngle: Double = 20 * kotlin.math.PI / 180,
    val helix: Double = 0.0,
    val herringbone: Boolean = false,
    val bore: Double = 0.0,
    val clearance: Double = 0.1,
    val operation: Operation = Operation.NewBody,
    val turn: Double = 0.0,
    val meshWith: Int? = null,
    val around: Double = 0.0,
) : Feature() {
    override fun key() = this
    /** Across the pitch circle, where it meets another gear: two gears' centres are half their two pitch diameters apart. */
    val pitchDiameter get() = module * teeth
}

/** An ISO metric thread [pitch] mm a turn, cut into a round [face]: outside a shaft or inside a hole. */
/** [clearance] (mm) moves the face away from the mating part first: a shaft gets smaller, a hole bigger, so printed threads fit. */
data class ThreadFeature(override val id: Int, override val name: String, val face: String, val pitch: Double, val clearance: Double = 0.0) : Feature() {
    override fun key() = this
}

/** One area of one sketch, for a loft. */
data class LoftSection(val sketchId: Int, val region: RegionRef)

/**
 * A solid through areas of two or more sketches, in order: smooth, or [ruled]
 * straight from one to the next. [twist] radians turns the areas about their
 * middles, none on the first and all of it on the last. With a [guide], the
 * areas grow and shrink to touch it all the way.
 */
data class LoftFeature(
    override val id: Int,
    override val name: String,
    val sections: List<LoftSection>,
    val ruled: Boolean,
    val operation: Operation,
    val twist: Double = 0.0,
    val guide: PathRef? = null,
) : Feature() {
    override fun key() = this
}
