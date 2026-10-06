package com.rm.parrotmetric.drawing

import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.profileCurves

/** A sketch's shapes as a DXF, in the sketch's own mm: what's drawn and its text, not construction lines. */
object SketchDxf {
    fun write(sketch: Sketch): String = DrawingExport.dxf(marks(sketch.profileCurves()))

    /** Lines, circles and arcs as they are; each Bézier as a polyline close enough for a cutter, 24 lines a curve. */
    fun marks(curves: List<ProfileCurve>): List<Mark> = curves.map { c ->
        when (c.kind) {
            ProfileCurve.Kind.Line -> Mark.Line(c.x1, c.y1, c.x2, c.y2, Pen.Visible)
            ProfileCurve.Kind.Circle -> Mark.Arc(c.x1, c.y1, c.r, 0.0, 2 * kotlin.math.PI, Pen.Visible)
            ProfileCurve.Kind.Arc -> Mark.Arc(c.x1, c.y1, c.r, c.a0, c.a1, Pen.Visible)
            ProfileCurve.Kind.Bezier -> {
                val n = 24
                Mark.Poly(DoubleArray((n + 1) * 2) { k ->
                    val t = (k / 2).toDouble() / n
                    val u = 1 - t
                    val (a, b, cc, d) = if (k % 2 == 0) listOf(c.x1, c.cx1, c.cx2, c.x2) else listOf(c.y1, c.cy1, c.cy2, c.y2)
                    u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * cc + t * t * t * d
                }, Pen.Visible)
            }
        }
    }
}
