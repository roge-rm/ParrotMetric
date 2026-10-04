package com.rm.parrotmetric.ui.design

/** ISO metric coarse threads: size and pitch, mm. */
object ThreadSizes {
    val all = listOf(
        "M2" to 0.4, "M2.5" to 0.45, "M3" to 0.5, "M4" to 0.7, "M5" to 0.8, "M6" to 1.0,
        "M8" to 1.25, "M10" to 1.5, "M12" to 1.75, "M16" to 2.0, "M20" to 2.5, "M24" to 3.0,
    )

    private fun size(name: String) = name.removePrefix("M").toDouble()

    /**
     * The size whose shaft or tapping hole is nearest [diameter]: a shaft is
     * the size across, a hole a little under it (the size less 1.0825
     * times the pitch).
     */
    fun fitting(diameter: Double): Pair<String, Double> = all.minBy { (name, pitch) ->
        val d = size(name)
        minOf(kotlin.math.abs(d - diameter), kotlin.math.abs(d - 1.0825 * pitch - diameter))
    }
}
