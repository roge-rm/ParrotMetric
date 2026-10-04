package com.rm.parrotmetric.design

/** A point construction geometry goes through, found again when the history is rebuilt. */
sealed class PointRef {
    /** A corner of a solid, named by two edges that meet there. */
    data class Corner(val name: String) : PointRef()

    /** The centre of a round edge. */
    data class CentreOf(val edge: String) : PointRef()

    /** A construction point made earlier in the history. */
    data class Construction(val featureId: Int) : PointRef()
}

/**
 * A plane to sketch on, mirror across or split along:
 * - Offset: [base] moved [offset] mm along its normal.
 * - Angle: [base] turned [angle] radians round its own x, or y if [turnRoundY].
 * - Midway: halfway between [base] and [other].
 * - ThreePoints: through the first three [points].
 * - TwoEdges: through two straight [edges] that lie in one plane.
 * - Tangent: touching the round [face], on the side [base]'s normal points to, turned [angle] radians round its axis.
 * - AlongEdge: square to the first of [edges], a fraction [along] of the way along it.
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
    val points: List<PointRef> = emptyList(),
    val edges: List<String> = emptyList(),
    val face: String? = null,
    val along: Double = 0.0,
) : Feature() {
    enum class Kind { Offset, Angle, Midway, ThreePoints, TwoEdges, Tangent, AlongEdge }
    override fun key() = this
}

/**
 * A point in space, shown in the view and taken into sketches by Project: at
 * (x, y, z), at [ref] (a corner or the centre of a round edge), or where
 * three [planes] meet.
 */
data class PointFeature(
    override val id: Int,
    override val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val kind: Kind = Kind.Fixed,
    val ref: PointRef? = null,
    val planes: List<PlaneRef> = emptyList(),
) : Feature() {
    enum class Kind { Fixed, At, ThreePlanes }
    override fun key() = this
}

/**
 * A straight axis, for patterns and turning: through (x, y, z) along an
 * origin axis, along a straight [edge], through the middle of a round [face]
 * or edge, or through two [points].
 */
data class AxisFeature(
    override val id: Int,
    override val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val along: Axis3,
    val kind: Kind = Kind.Fixed,
    val edge: String? = null,
    val face: String? = null,
    val points: List<PointRef> = emptyList(),
) : Feature() {
    enum class Kind { Fixed, Edge, Round, TwoPoints }
    override fun key() = this
}
