package com.rm.parrotmetric.ui.design

/** Standard thread sizes: name, size across in mm and pitch in mm. ISO metric coarse and UNC. */
object ThreadSizes {
    class Size(val name: String, val across: Double, val pitch: Double)

    val metric = listOf(
        "M2" to 0.4, "M2.5" to 0.45, "M3" to 0.5, "M4" to 0.7, "M5" to 0.8, "M6" to 1.0,
        "M8" to 1.25, "M10" to 1.5, "M12" to 1.75, "M16" to 2.0, "M20" to 2.5, "M24" to 3.0,
        "M30" to 3.5, "M36" to 4.0, "M42" to 4.5, "M48" to 5.0, "M56" to 5.5, "M64" to 6.0,
    ).map { (name, pitch) -> Size(name, name.removePrefix("M").toDouble(), pitch) }

    /** Sizes in inches and threads per inch. */
    val inch = listOf(
        Triple("#2-56", 0.086, 56), Triple("#4-40", 0.112, 40), Triple("#6-32", 0.138, 32), Triple("#8-32", 0.164, 32),
        Triple("#10-24", 0.19, 24), Triple("1/4-20", 0.25, 20), Triple("5/16-18", 0.3125, 18), Triple("3/8-16", 0.375, 16),
        Triple("7/16-14", 0.4375, 14), Triple("1/2-13", 0.5, 13), Triple("5/8-11", 0.625, 11), Triple("3/4-10", 0.75, 10),
        Triple("7/8-9", 0.875, 9), Triple("1-8", 1.0, 8),
    ).map { (name, across, tpi) -> Size(name, across * 25.4, 25.4 / tpi) }

    /**
     * The size whose shaft or tapping hole is nearest [diameter]: a shaft is
     * the size across, a hole a little under it (the size less 1.0825
     * times the pitch).
     */
    fun fitting(diameter: Double, inches: Boolean = false): Size = (if (inches) inch else metric).minBy { s ->
        minOf(kotlin.math.abs(s.across - diameter), kotlin.math.abs(s.across - 1.0825 * s.pitch - diameter))
    }

    /** Whether [pitch] is an inch size's: a whole number of threads per inch. */
    fun isInch(pitch: Double) = inch.any { kotlin.math.abs(it.pitch - pitch) < 1e-9 } && metric.none { kotlin.math.abs(it.pitch - pitch) < 1e-9 }
}
