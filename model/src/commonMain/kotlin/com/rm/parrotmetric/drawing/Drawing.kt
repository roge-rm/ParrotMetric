package com.rm.parrotmetric.drawing

import com.rm.parrotmetric.sketch.Vec3
import kotlin.math.sqrt

/** Paper sizes, long side first, mm. */
enum class Paper(val label: String, val long: Double, val short: Double) {
    A4("A4", 297.0, 210.0),
    A3("A3", 420.0, 297.0),
    A2("A2", 594.0, 420.0),
    Letter("Letter", 279.4, 215.9),
    Tabloid("Tabloid", 431.8, 279.4),
}

/** Which way a view looks at the model: [towards] points at the viewer, [right] runs across the view. Z is up. */
enum class ViewSide(val label: String, val towards: Vec3, val right: Vec3) {
    Front("Front", Vec3(0.0, -1.0, 0.0), Vec3(1.0, 0.0, 0.0)),
    Top("Top", Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0)),
    Right("Right", Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0)),
    Back("Back", Vec3(0.0, 1.0, 0.0), Vec3(-1.0, 0.0, 0.0)),
    Left("Left", Vec3(-1.0, 0.0, 0.0), Vec3(0.0, -1.0, 0.0)),
    Bottom("Bottom", Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0)),
    Iso("Isometric", unit(1.0, -1.0, 1.0), unit(1.0, 1.0, 0.0)),
}

private fun unit(x: Double, y: Double, z: Double): Vec3 {
    val l = sqrt(x * x + y * y + z * z)
    return Vec3(x / l, y / l, z / l)
}

/**
 * A view placed on the sheet: its centre at ([x], [y]) mm from the sheet's lower left. [hidden] draws
 * hidden lines dashed. [scale] overrides the drawing's for this view only.
 *
 * A section view has a [cut]: the model is cut by the plane square to its side's direction, that far
 * along it from the origin (mm), and only what's behind the plane is drawn, the cut faces hatched.
 * [label] is its letter, shown under it and where the plane crosses the other views.
 */
data class DrawingView(
    val id: Int,
    val side: ViewSide,
    val x: Double,
    val y: Double,
    val hidden: Boolean = true,
    val scale: Double? = null,
    val cut: Double? = null,
    val label: String? = null,
) {
    /** What its geometry is worked out from. */
    val key get() = ViewKey(side, cut)
}

/** A view's side and where it's cut, if it's a section: views with the same key look the same. */
data class ViewKey(val side: ViewSide, val cut: Double? = null)

/** What a dimension measures. */
enum class DimensionKind { Aligned, Horizontal, Vertical, Diameter, Radius, Hole }

/**
 * A dimension in a view. Its points are in the view's model coordinates (mm, before scaling), found
 * again on the nearest corner, end or centre each time the view is worked out, so it follows the model.
 * Diameter, radius and hole use [a] as the circle's centre and [b] as a point on it; a hole's label
 * says what the hole is, as Sheet.marks is told. [offset] is how far the
 * dimension line stands off the points, in sheet mm; for a diameter or radius it's where the label sits
 * along the leader. [box] is the view's outline when the points were picked (left, bottom, right, top):
 * each point keeps its distance from the nearest side of it, so a dimension across the part grows with it.
 */
data class DrawingDimension(
    val id: Int,
    val view: Int,
    val kind: DimensionKind,
    val ax: Double, val ay: Double,
    val bx: Double, val by: Double,
    val offset: Double = 10.0,
    val box: List<Double> = emptyList(),
)

/** Text on the sheet at ([x], [y]) mm, [height] mm tall. */
data class DrawingNote(val id: Int, val x: Double, val y: Double, val text: String, val height: Double = 3.5)

/**
 * A design's drawing: one sheet of views of its bodies with dimensions and notes. [scale] is model mm
 * per sheet mm turned around: 0.5 draws at half size (1:2). [firstAngle] lays the side views out the
 * European way (top view below the front) instead of the third-angle way used in North America.
 */
data class Drawing(
    val paper: Paper = Paper.A4,
    val portrait: Boolean = false,
    val scale: Double = 1.0,
    val firstAngle: Boolean = false,
    val title: String = "",
    val drawnBy: String = "",
    val views: List<DrawingView> = emptyList(),
    val dimensions: List<DrawingDimension> = emptyList(),
    val notes: List<DrawingNote> = emptyList(),
) {
    val width get() = if (portrait) paper.short else paper.long
    val height get() = if (portrait) paper.long else paper.short

    fun nextId() = (views.map { it.id } + dimensions.map { it.id } + notes.map { it.id }).maxOrNull()?.plus(1) ?: 1

    fun scaleOf(v: DrawingView) = v.scale ?: scale

    companion object {
        /** Standard scales, largest first: model mm drawn per sheet mm. */
        val scales = listOf(10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1, 0.05, 0.02, 0.01)

        fun scaleLabel(s: Double) = when {
            s >= 1.0 -> "${fmt(s)}:1"
            else -> "1:${fmt(1 / s)}"
        }

        private fun fmt(v: Double) = if (v == kotlin.math.round(v)) v.toLong().toString() else v.toString()
    }
}
