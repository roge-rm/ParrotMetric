package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Vec3

/**
 * A picture laid on a plane to trace over: [width] mm across, its height
 * from its shape ([aspect], height over width), centred at ([u], [v]) on the
 * plane, turned [angle] radians, [opacity] from 0 to 1. Shown only; it makes
 * no geometry.
 */
class CanvasFeature(
    override val id: Int,
    override val name: String,
    val plane: PlaneRef,
    val image: ByteArray,
    val aspect: Double,
    val width: Double,
    val u: Double,
    val v: Double,
    val angle: Double,
    val opacity: Double,
) : Feature() {
    override fun key(): Any = listOf(id, name, plane, image.contentHashCode(), aspect, width, u, v, angle, opacity)

    fun copy(width: Double = this.width, u: Double = this.u, v: Double = this.v, angle: Double = this.angle, opacity: Double = this.opacity) =
        CanvasFeature(id, name, plane, image, aspect, width, u, v, angle, opacity)

    override fun equals(other: Any?) = other is CanvasFeature && key() == other.key()
    override fun hashCode() = key().hashCode()
}

/** A canvas where it's shown: its corners round from the bottom left, and how see-through. */
class PlacedCanvas(val featureId: Int, val image: ByteArray, val corners: List<Vec3>, val opacity: Double)
