package com.rm.parrotmetric.design

/**
 * A plane to sketch on, mirror across or split along: [base] moved [offset]
 * mm along its normal, or turned [angle] radians round its own x or y axis,
 * or midway between [base] and [other].
 */
data class PlaneFeature(
    override val id: Int,
    override val name: String,
    val kind: Kind,
    val base: PlaneRef,
    val offset: Double,
    val angle: Double,
    val turnRoundY: Boolean,
    val other: PlaneRef?,
) : Feature() {
    enum class Kind { Offset, Angle, Midway }
    override fun key() = this
}

/** A straight axis through (x, y, z) along an origin axis, for patterns and turning. */
data class AxisFeature(
    override val id: Int,
    override val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val along: Axis3,
) : Feature() {
    override fun key() = this
}
