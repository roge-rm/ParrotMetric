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

/** Moves faces along their normals by [distance] mm, out when more than 0, the body following. */
data class OffsetFaceFeature(override val id: Int, override val name: String, val faces: List<String>, val distance: Double) : Feature() {
    override fun key() = this
}

/** Takes faces away and closes the gap, as removing a fillet, hole or boss. */
data class DeleteFaceFeature(override val id: Int, override val name: String, val faces: List<String>) : Feature() {
    override fun key() = this
}

/**
 * A rib from a sketch's open curves, [thickness] mm, grown until it meets a
 * body: across the sketch plane, or with [web] out of it along its normal.
 * [flip] grows it the other way.
 */
data class RibFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int,
    val thickness: Double,
    val flip: Boolean,
    val web: Boolean,
) : Feature() {
    override fun key() = this
}

/**
 * Sketch areas projected along the sketch's normal onto [face], flat or
 * curved, and raised out of it [depth] mm, or sunk into it with [sink].
 */
data class EmbossFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int,
    val regions: List<RegionRef>,
    val face: String,
    val depth: Double,
    val sink: Boolean,
) : Feature() {
    override fun key() = this
}

/** A surface: sketch areas laid flat ([sketchId] and [regions]), or filling a loop of body [edges]. */
data class PatchFeature(
    override val id: Int,
    override val name: String,
    val sketchId: Int?,
    val regions: List<RegionRef>,
    val edges: List<String>,
) : Feature() {
    override fun key() = this
}

/** Surfaces sewn into the first of [bodies]; a solid if they close round. */
data class StitchFeature(override val id: Int, override val name: String, val bodies: BodyPick) : Feature() {
    override fun key() = this
}

/** A surface made [thickness] mm thick, to one side or [both]. */
data class ThickenFeature(override val id: Int, override val name: String, val body: String, val thickness: Double, val both: Boolean) : Feature() {
    override fun key() = this
}

enum class JointKind { Rigid, Turn, Slide, TurnSlide }

/**
 * Joins component [moving] to [fixed] (or to the origin when null) where
 * they are, and moves it as the joint allows: turned [value] radians round
 * the axis, slid [value] mm along it, or turned [value] and slid [value2].
 * Rigid moves nothing, but from then on the two move together.
 *
 * The axis is a straight or round edge or a cylinder's face ([edge] or
 * [face]), a construction axis ([axisFeature]), or else [axis] through the
 * origin.
 */
data class JointFeature(
    override val id: Int,
    override val name: String,
    val kind: JointKind,
    val moving: String,
    val fixed: String?,
    val edge: String? = null,
    val face: String? = null,
    val axisFeature: Int? = null,
    val axis: Axis3 = Axis3.Z,
    val value: Double = 0.0,
    val value2: Double = 0.0,
) : Feature() {
    override fun key() = this
}

enum class MeshEdit { Reduce, Remesh, Smooth }

/**
 * Changes a body's triangles, making it a mesh body if it's a solid:
 * Reduce to fewer, the surface moving no more than [size] mm; Remesh to
 * smaller ones, no edge longer than [size] mm; Smooth, each triangle split
 * [steps] x [steps] and rounded off, keeping edges sharper than [size]
 * degrees.
 */
data class MeshEditFeature(
    override val id: Int,
    override val name: String,
    val body: String,
    val kind: MeshEdit,
    val size: Double,
    val steps: Int = 2,
) : Feature() {
    override fun key() = this
}

enum class HoleKind { Simple, Counterbore, Countersink }

/** What a hole is for, when it's for a metric screw in a printed part. */
enum class HoleFit { Insert, SelfTap, Clearance }

/**
 * Hole sizes for metric screws in printed parts: a pocket for a standard
 * heat-set insert, or a pilot hole the screw cuts its own thread in.
 */
object HolePresets {
    val sizes = listOf("M2", "M2.5", "M3", "M4", "M5")

    // Pocket diameter, and depth: a short insert's length and 2 mm more for the plastic it pushes down.
    // M3 is a proven print, 4 mm wide and 6 deep for a 4 mm insert; the rest go by the inserts' sizes.
    private val insert = mapOf("M2" to (3.2 to 5.0), "M2.5" to (3.6 to 6.0), "M3" to (4.0 to 6.0), "M4" to (5.6 to 7.5), "M5" to (6.4 to 8.5))

    // Two thirds of the screw's size, tight enough that it forces its own thread: 2 mm for M3 holds.
    private val selfTap = mapOf("M2" to 1.3, "M2.5" to 1.7, "M3" to 2.0, "M4" to 2.7, "M5" to 3.3)

    // The screw passes through freely: 3.5 for M3, as printed holes come out a little small.
    private val clearance = mapOf("M2" to 2.5, "M2.5" to 3.0, "M3" to 3.5, "M4" to 4.5, "M5" to 5.5)

    // Room for the head: a countersunk screw's head across (ISO 10642), and a socket cap's across and how deep, each a little over.
    private val countersink = mapOf("M2" to 4.4, "M2.5" to 5.5, "M3" to 6.7, "M4" to 9.0, "M5" to 11.2)
    private val counterbore = mapOf("M2" to (4.2 to 2.3), "M2.5" to (5.0 to 2.8), "M3" to (6.0 to 3.3), "M4" to (7.5 to 4.3), "M5" to (9.0 to 5.3))

    fun diameter(fit: HoleFit, size: String): Double = when (fit) {
        HoleFit.Insert -> insert.getValue(size).first
        HoleFit.SelfTap -> selfTap.getValue(size)
        HoleFit.Clearance -> clearance.getValue(size)
    }

    /** The top's size across and depth for a screw head, for a clearance hole that's countersunk or counterbored. */
    fun top(kind: HoleKind, size: String): Pair<Double, Double>? = when (kind) {
        HoleKind.Countersink -> countersink.getValue(size) to 0.0
        HoleKind.Counterbore -> counterbore.getValue(size)
        HoleKind.Simple -> null
    }

    /** How deep the hole has to be, or null when it's up to the screw's length. */
    fun depth(fit: HoleFit, size: String): Double? = if (fit == HoleFit.Insert) insert.getValue(size).second else null

    /** The preset a hole of this diameter matches, if any. */
    fun match(diameter: Double): Pair<HoleFit, String>? {
        insert.entries.firstOrNull { kotlin.math.abs(it.value.first - diameter) < 1e-6 }?.let { return HoleFit.Insert to it.key }
        selfTap.entries.firstOrNull { kotlin.math.abs(it.value - diameter) < 1e-6 }?.let { return HoleFit.SelfTap to it.key }
        clearance.entries.firstOrNull { kotlin.math.abs(it.value - diameter) < 1e-6 }?.let { return HoleFit.Clearance to it.key }
        return null
    }
}

/**
 * Holes at a sketch's hole points (see Sketch.holePoints), going in against the sketch's normal.
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
    /** The bodies it may change, by label, or empty for any it reaches. */
    override val only: List<String> = emptyList(),
) : Feature() {
    override fun key() = this
}

/** Which bodies a feature works on: by label, or every body when empty. */
typealias BodyPick = List<String>

/**
 * A mirror image of bodies across a plane, joined to them or as new bodies.
 * With [features], those features are done again mirrored instead, each
 * joining, cutting or adding as it did.
 */
data class MirrorFeature(
    override val id: Int,
    override val name: String,
    val bodies: BodyPick,
    val plane: PlaneRef,
    val join: Boolean,
    val features: List<Int> = emptyList(),
) : Feature() {
    override fun key() = this
}

/**
 * Copies of bodies in a row (along [axis], [spacing] mm apart, and
 * optionally along [axis2] too) or round [axis] ([angle] radians shared
 * between them; a full turn spaces them evenly). With [path], they go along
 * it instead: [count] spread over its length, or [spacing] apart when that's
 * more than 0, turned to follow it with [turn], from its far end with
 * [reverse]. With [features], those
 * features are done again at each place instead of copying bodies.
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
    val path: PathRef? = null,
    val turn: Boolean = false,
    val features: List<Int> = emptyList(),
    val reverse: Boolean = false,
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
data class SplitFeature(
    override val id: Int,
    override val name: String,
    val body: String,
    val plane: PlaneRef,
    val keep: Int = 0,
    /**
     * Another body to split it by in place of the plane; then [keep] is 0
     * for both, 1 for the part outside it and 2 for the part inside.
     */
    val tool: String? = null,
) : Feature() {
    override fun key() = this
}

/**
 * Turns and moves bodies so a flat [face] of one of them lies on [target]:
 * facing it, or the same way if [sameWay], [gap] (mm) away from it, and with
 * the face's middle over the target's origin if [centred].
 */
data class AlignFeature(
    override val id: Int,
    override val name: String,
    /** The bodies to move; none means the body with the face. */
    val bodies: BodyPick,
    val face: String,
    val target: PlaneRef,
    val sameWay: Boolean = false,
    val centred: Boolean = false,
    val gap: Double = 0.0,
) : Feature() {
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

/**
 * A lip standing [height] mm out of a flat rim face, [width] mm wide round the
 * inside of each opening in it, and when [lid] names a body, a groove in that
 * body the lip fits into with [gap] mm to spare all round.
 */
data class LipFeature(
    override val id: Int,
    override val name: String,
    val face: String,
    val width: Double,
    val height: Double,
    val gap: Double,
    val lid: String?,
) : Feature() {
    override fun key() = this
}
