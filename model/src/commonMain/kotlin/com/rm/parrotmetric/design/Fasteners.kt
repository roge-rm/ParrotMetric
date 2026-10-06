package com.rm.parrotmetric.design

import kotlin.math.PI

/** The kinds of fastener, in the core's order (pm::Fastener). */
enum class FastenerKind(val label: String, val screw: Boolean) {
    SocketCap("Socket cap", true),
    HexBolt("Hex bolt", true),
    Countersunk("Countersunk", true),
    Nut("Nut", false),
    Washer("Washer", false),
}

/**
 * A standard size of screw, nut and washer, mm: the thread's [d] and [pitch];
 * a socket cap screw's head across and high and its hex key; a hex head's and
 * nut's size across the flats, the head's and nut's height; a countersunk
 * head's size across, its hex key and angle; a washer's hole, outside and
 * thickness.
 */
class FastenerSize(
    val name: String,
    val d: Double,
    val pitch: Double,
    val capHead: Double,
    val capHeight: Double,
    val capKey: Double,
    val hex: Double,
    val hexHeight: Double,
    val nutHeight: Double,
    val sunkHead: Double,
    val sunkKey: Double,
    val sunkAngle: Double,
    val washerHole: Double,
    val washerOutside: Double,
    val washerThickness: Double,
) {
    val inch get() = !name.startsWith("M")
}

object Fasteners {
    /** ISO 4762 socket cap, 4017 hex bolt, 10642 countersunk, 4032 nut and 7089 washer. */
    val metric = listOf(
        m("M2", 2.0, 0.4, 3.8, 1.5, 4.0, 1.4, 1.6, 4.0, 1.3, 2.2, 5.0, 0.3),
        m("M2.5", 2.5, 0.45, 4.5, 2.0, 5.0, 1.7, 2.0, 5.0, 1.5, 2.7, 6.0, 0.5),
        m("M3", 3.0, 0.5, 5.5, 2.5, 5.5, 2.0, 2.4, 6.72, 2.0, 3.2, 7.0, 0.5),
        m("M4", 4.0, 0.7, 7.0, 3.0, 7.0, 2.8, 3.2, 8.96, 2.5, 4.3, 9.0, 0.8),
        m("M5", 5.0, 0.8, 8.5, 4.0, 8.0, 3.5, 4.7, 11.2, 3.0, 5.3, 10.0, 1.0),
        m("M6", 6.0, 1.0, 10.0, 5.0, 10.0, 4.0, 5.2, 13.44, 4.0, 6.4, 12.0, 1.6),
        m("M8", 8.0, 1.25, 13.0, 6.0, 13.0, 5.3, 6.8, 17.92, 5.0, 8.4, 16.0, 1.6),
        m("M10", 10.0, 1.5, 16.0, 8.0, 16.0, 6.4, 8.4, 22.4, 6.0, 10.5, 20.0, 2.0),
        m("M12", 12.0, 1.75, 18.0, 10.0, 18.0, 7.5, 10.8, 26.88, 8.0, 13.0, 24.0, 2.5),
    )

    /** UNC, in inches: socket cap and flat head screws (82°), hex heads, hex nuts and SAE washers. */
    val inch = listOf(
        i("#4-40", 0.112, 40, 0.183, 0.112, 3.0 / 32, 0.25, 0.08, 3.0 / 32, 0.225, 1.0 / 16, 0.125, 0.312, 0.032),
        i("#6-32", 0.138, 32, 0.226, 0.138, 7.0 / 64, 0.3125, 0.1, 7.0 / 64, 0.279, 5.0 / 64, 0.156, 0.375, 0.049),
        i("#8-32", 0.164, 32, 0.27, 0.164, 9.0 / 64, 0.34375, 0.11, 0.125, 0.332, 3.0 / 32, 0.188, 0.438, 0.049),
        i("#10-24", 0.19, 24, 0.312, 0.19, 5.0 / 32, 0.375, 0.12, 0.125, 0.385, 1.0 / 8, 0.219, 0.5, 0.049),
        i("1/4-20", 0.25, 20, 0.375, 0.25, 3.0 / 16, 0.4375, 5.0 / 32, 7.0 / 32, 0.507, 5.0 / 32, 0.281, 0.625, 0.065),
        i("5/16-18", 0.3125, 18, 0.469, 0.3125, 0.25, 0.5, 13.0 / 64, 17.0 / 64, 0.635, 3.0 / 16, 0.344, 0.688, 0.065),
        i("3/8-16", 0.375, 16, 0.5625, 0.375, 5.0 / 16, 0.5625, 15.0 / 64, 21.0 / 64, 0.762, 7.0 / 32, 0.406, 0.812, 0.065),
        i("1/2-13", 0.5, 13, 0.75, 0.5, 3.0 / 8, 0.75, 11.0 / 32, 7.0 / 16, 1.016, 5.0 / 16, 0.531, 1.062, 0.095),
    )

    val all get() = metric + inch

    fun size(name: String) = all.firstOrNull { it.name == name }

    private fun m(
        name: String, d: Double, pitch: Double, capHead: Double, capKey: Double, hex: Double, hexHeight: Double, nutHeight: Double,
        sunkHead: Double, sunkKey: Double, washerHole: Double, washerOutside: Double, washerThickness: Double,
    ) = FastenerSize(name, d, pitch, capHead, d, capKey, hex, hexHeight, nutHeight, sunkHead, sunkKey, PI / 2, washerHole, washerOutside, washerThickness)

    private fun i(
        name: String, d: Double, tpi: Int, capHead: Double, capHeight: Double, capKey: Double, hex: Double, hexHeight: Double, nutHeight: Double,
        sunkHead: Double, sunkKey: Double, washerHole: Double, washerOutside: Double, washerThickness: Double,
    ) = FastenerSize(
        name, d * IN, IN / tpi, capHead * IN, capHeight * IN, capKey * IN, hex * IN, hexHeight * IN, nutHeight * IN,
        sunkHead * IN, sunkKey * IN, 82 * PI / 180, washerHole * IN, washerOutside * IN, washerThickness * IN,
    )

    private const val IN = 25.4
}

/**
 * A standard screw, nut or washer of [size] (Fasteners.size), [length] mm long
 * under the head for screws (all of it for countersunk ones). It goes in the
 * round face [hole], at its higher end or with [otherEnd] its lower one, or
 * else stands on [plane] at ([u], [v]). Its thread is drawn as a symbol unless
 * [modelled]. [clearance] mm is added all round, for cutting a pocket it fits.
 */
data class FastenerFeature(
    override val id: Int,
    override val name: String,
    val kind: FastenerKind,
    val size: String,
    val length: Double,
    val hole: String?,
    val otherEnd: Boolean = false,
    val plane: PlaneRef = PlaneRef.Fixed(com.rm.parrotmetric.sketch.SketchPlane.Top),
    val u: Double = 0.0,
    val v: Double = 0.0,
    val modelled: Boolean = false,
    val clearance: Double = 0.0,
    val operation: Operation = Operation.NewBody,
) : Feature() {
    override fun key() = this
}

/** A thread drawn as a symbol: a fine helix on a round [face], a turn every [pitch] mm. */
data class ThreadMark(val face: String, val pitch: Double)
