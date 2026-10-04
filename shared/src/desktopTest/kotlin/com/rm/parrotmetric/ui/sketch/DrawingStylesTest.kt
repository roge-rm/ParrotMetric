package com.rm.parrotmetric.ui.sketch

import com.rm.parrotmetric.sketch.Arc
import com.rm.parrotmetric.sketch.Circle
import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.RegionFinder
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Each way of drawing, by taps as a finger would make them. */
class DrawingStylesTest {
    private val tol = 0.5

    private fun editor() = SketchEditor(SketchPlane.Top, "Sketch", Sketch(), RegionFinder { emptyList() })

    private fun SketchEditor.tap(u: Double, v: Double) {
        press(u, v, tol)
        release(u, v, tol, moved = false)
    }

    private fun near(expected: Double, actual: Double, what: String) = assertEquals(expected, actual, 1e-6, what)

    @Test
    fun circleThroughThreePoints() {
        val e = editor()
        e.selectTool(SketchTool.Circle)
        e.circleStyle = CircleStyle.ThreePoints
        e.tap(0.0, 0.0); e.tap(10.0, 0.0); e.tap(0.0, 10.0)
        val c = e.sketch.curves.filterIsInstance<Circle>().single()
        near(50.0, e.sketch.radius(c) * e.sketch.radius(c), "radius squared")
        near(5.0, e.sketch.x(c.centre), "centre x")
    }

    @Test
    fun circleAcrossTwoPoints() {
        val e = editor()
        e.selectTool(SketchTool.Circle)
        e.circleStyle = CircleStyle.TwoPoints
        e.tap(0.0, 0.0); e.tap(10.0, 0.0)
        near(5.0, e.sketch.radius(e.sketch.curves.filterIsInstance<Circle>().single()), "radius")
    }

    @Test
    fun rectangleFromThreePointsKeepsItsSidesSquare() {
        val e = editor()
        e.selectTool(SketchTool.Rectangle)
        e.rectangleStyle = RectangleStyle.ThreePoints
        e.tap(0.0, 0.0); e.tap(8.0, 6.0); e.tap(2.0, 9.0)
        val sides = e.sketch.curves.filterIsInstance<Line>().filter { !it.construction }
        assertEquals(4, sides.size)
        near(10.0, e.sketch.length(sides[0]), "first side")
        near(e.sketch.length(sides[1]), e.sketch.length(sides[3]), "opposite sides")
        assertTrue(e.sketch.constraints.any { it is Constraint.Perpendicular })
    }

    @Test
    fun tangentArcCarriesOnFromALine() {
        val e = editor()
        e.selectTool(SketchTool.Line)
        e.tap(0.0, 0.0); e.tap(10.0, 0.0)
        e.endDrawing()
        e.selectTool(SketchTool.Arc)
        e.arcStyle = ArcStyle.Tangent
        e.tap(10.0, 0.0); e.tap(10.0, 10.0)
        val arc = assertNotNull(e.sketch.curves.filterIsInstance<Arc>().singleOrNull())
        near(5.0, e.sketch.radius(arc), "radius")
        near(10.0, e.sketch.x(arc.centre), "centre x")
        near(5.0, e.sketch.y(arc.centre), "centre y")
        assertTrue(e.sketch.constraints.any { it is Constraint.TangentJoin })
    }

    @Test
    fun tangentArcHasToStartAtAnEnd() {
        val e = editor()
        e.selectTool(SketchTool.Arc)
        e.arcStyle = ArcStyle.Tangent
        e.tap(3.0, 3.0)
        assertTrue(e.pending.isEmpty())
        assertNotNull(e.message)
    }

    @Test
    fun polygonWithItsSidesOnTheCircle() {
        val e = editor()
        e.selectTool(SketchTool.Polygon)
        e.polygonStyle = PolygonStyle.Outside
        e.polygonSides = 6
        e.tap(0.0, 0.0); e.tap(10.0, 0.0)
        val sides = e.sketch.curves.filterIsInstance<Line>().filter { !it.construction }
        assertEquals(6, sides.size)
        near(20 * tan(PI / 6), e.sketch.length(sides[0]), "side")
    }

    @Test
    fun slotMeasuredEndToEnd() {
        val e = editor()
        e.selectTool(SketchTool.Slot)
        e.slotStyle = SlotStyle.Overall
        e.tap(0.0, 0.0); e.tap(40.0, 0.0); e.tap(20.0, 5.0)
        val ends = e.sketch.curves.filterIsInstance<Arc>()
        assertEquals(2, ends.size)
        near(5.0, e.sketch.radius(ends[0]), "end radius")
        near(5.0, ends.minOf { e.sketch.x(it.centre) }, "first centre")
        near(35.0, ends.maxOf { e.sketch.x(it.centre) }, "second centre")
        assertEquals(4, e.sketch.constraints.count { it is Constraint.TangentJoin })
    }

    @Test
    fun slotFromItsMiddle() {
        val e = editor()
        e.selectTool(SketchTool.Slot)
        e.slotStyle = SlotStyle.Middle
        e.tap(0.0, 0.0); e.tap(15.0, 0.0); e.tap(0.0, 4.0)
        val ends = e.sketch.curves.filterIsInstance<Arc>()
        near(-15.0, ends.minOf { e.sketch.x(it.centre) }, "mirrored centre")
        near(4.0, e.sketch.radius(ends[0]), "end radius")
    }

    @Test
    fun collinearLinesLineUp() {
        val e = editor()
        e.selectTool(SketchTool.Line)
        e.tap(0.0, 0.0); e.tap(10.0, 0.0)
        e.endDrawing()
        e.tap(20.0, 3.0); e.tap(30.0, 5.0)
        e.endDrawing()
        val (a, b) = e.sketch.curves.filterIsInstance<Line>()
        e.sketch.add(Constraint.Collinear(a, b))
        near(0.0, e.sketch.y(b.a), "start on the line")
        near(0.0, e.sketch.y(b.b), "end on the line")
    }

    @Test
    fun ellipseFromCentreAxisAndWidth() {
        val e = editor()
        e.selectTool(SketchTool.Ellipse)
        e.tap(0.0, 0.0); e.tap(10.0, 0.0); e.tap(3.0, 4.0)
        val sp = e.sketch.curves.filterIsInstance<com.rm.parrotmetric.sketch.Spline>().single()
        assertEquals(com.rm.parrotmetric.sketch.Spline.Shape.Ellipse, sp.shape)
        near(4.0, e.sketch.y(sp.through[2]), "second axis")
        near(0.0, e.sketch.x(sp.through[2]), "square to the first")
    }

    @Test
    fun conicAndPatternRound() {
        val e = editor()
        e.selectTool(SketchTool.Conic)
        e.tap(0.0, 0.0); e.tap(10.0, 10.0); e.tap(10.0, 0.0)
        val sp = e.sketch.curves.filterIsInstance<com.rm.parrotmetric.sketch.Spline>().single()
        assertEquals(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0), sp.through.map { e.sketch.x(it) to e.sketch.y(it) })
        e.selectTool(SketchTool.Select)
        e.selection += SketchItem.C(sp)
        e.startTransform(SketchTransform.Kind.Round)
        e.transform!!.count = 4.0
        e.commitTransform()
        assertEquals(4, e.sketch.curves.size)
        // A quarter turn round the origin takes the end (10, 10) to (-10, 10).
        assertTrue(e.sketch.points.any { kotlin.math.abs(e.sketch.x(it) + 10) < 1e-9 && kotlin.math.abs(e.sketch.y(it) - 10) < 1e-9 })
    }
}
