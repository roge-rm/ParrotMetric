package com.rm.parrotmetric.design

/** One of the origin axes, for patterns and turning. */
enum class Axis3 { X, Y, Z }

/** Hollows a body to walls [thickness] thick, leaving [faces] open. */
data class ShellFeature(override val id: Int, override val name: String, val faces: List<String>, val thickness: Double) : Feature() {
    override fun key() = this
}

/** Tilts [faces] by [angle] radians, pivoting on the flat [neutral] face. */
data class DraftFeature(override val id: Int, override val name: String, val faces: List<String>, val neutral: String, val angle: Double) : Feature() {
    override fun key() = this
}

enum class HoleKind { Simple, Counterbore, Countersink }

/**
 * Holes at a sketch's lone points, going in against the sketch's normal.
 * [depth] 0 goes right through. The top's size is for counterbores and
 * countersinks; [topDepth] only for counterbores.
 */
data class HoleFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int,
    val diameter: Double,
    val depth: Double,
    val kind: HoleKind,
    val topDiameter: Double,
    val topDepth: Double,
) : Feature() {
    override fun key() = this
}

/** Which bodies a feature works on: by label, or every body when empty. */
typealias BodyPick = List<String>

/** A mirror image of bodies across a plane, joined to them or as new bodies. */
data class MirrorFeature(override val id: Int, override val name: String, val bodies: BodyPick, val plane: PlaneRef, val join: Boolean) : Feature() {
    override fun key() = this
}

/**
 * Copies of bodies in a row (along [axis], [spacing] mm apart, and
 * optionally along [axis2] too) or round [axis] ([angle] radians shared
 * between them; a full turn spaces them evenly).
 */
data class PatternFeature(
    override val id: Int,
    override val name: String,
    val bodies: BodyPick,
    val circular: Boolean,
    val axis: Axis3,
    val count: Int,
    val spacing: Double,
    val angle: Double,
    val axis2: Axis3?,
    val count2: Int,
    val spacing2: Double,
    val join: Boolean,
    /** For a pattern round an axis: a construction axis to use instead of the origin's. */
    val axisFeature: Int? = null,
) : Feature() {
    override fun key() = this
}

/** Joins, cuts or intersects [target] with [tools], keeping the tools if asked. */
data class CombineFeature(
    override val id: Int,
    override val name: String,
    val target: String,
    val tools: List<String>,
    val operation: Operation,
    val keepTools: Boolean,
) : Feature() {
    override fun key() = this
}

/**
 * Cuts a body along a plane. [keep] 0 keeps both pieces as bodies; 1 keeps
 * the side the plane faces, 2 the side behind it.
 */
data class SplitFeature(override val id: Int, override val name: String, val body: String, val plane: PlaneRef, val keep: Int = 0) : Feature() {
    override fun key() = this
}

/** Makes a mesh body a solid, so it can be filleted, shelled and so on. */
data class ConvertFeature(override val id: Int, override val name: String, val body: String) : Feature() {
    override fun key() = this
}

/**
 * Moves bodies by (dx, dy, dz) mm, after turning them [angle] radians round an
 * origin axis. First each is scaled about its own middle by (sx, sy, sz).
 */
data class MoveFeature(
    override val id: Int,
    override val name: String,
    val bodies: BodyPick,
    val dx: Double,
    val dy: Double,
    val dz: Double,
    val axis: Axis3,
    val angle: Double,
    val copy: Boolean,
    val sx: Double = 1.0,
    val sy: Double = 1.0,
    val sz: Double = 1.0,
) : Feature() {
    val scaled get() = sx != 1.0 || sy != 1.0 || sz != 1.0
    override fun key() = this
}
