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

/** An ISO metric thread [pitch] mm a turn, cut into a round [face]: outside a shaft or inside a hole. */
data class ThreadFeature(override val id: Int, override val name: String, val face: String, val pitch: Double) : Feature() {
    override fun key() = this
}

/** One area of one sketch, for a loft. */
data class LoftSection(val sketchId: Int, val region: RegionRef)

/** A solid through areas of two or more sketches, in order: smooth, or [ruled] straight from one to the next. */
data class LoftFeature(
    override val id: Int,
    override val name: String,
    val sections: List<LoftSection>,
    val ruled: Boolean,
    val operation: Operation,
) : Feature() {
    override fun key() = this
}
