package com.rm.parrotmetric.drawing

import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.io.DesignFile
import com.rm.parrotmetric.sketch.ProfileCurve
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SheetTest {
    private fun rect(w: Double, h: Double) = listOf(
        ProfileCurve(ProfileCurve.Kind.Line, 0, 0.0, 0.0, w, 0.0),
        ProfileCurve(ProfileCurve.Kind.Line, 0, w, 0.0, w, h),
        ProfileCurve(ProfileCurve.Kind.Line, 0, w, h, 0.0, h),
        ProfileCurve(ProfileCurve.Kind.Line, 0, 0.0, h, 0.0, 0.0),
    )

    @Test
    fun numbersAreWrittenShort() {
        assertEquals("40", Sheet.number(40.0))
        assertEquals("12.5", Sheet.number(12.5))
        assertEquals("0.33", Sheet.number(1.0 / 3))
        assertEquals("1:2", Drawing.scaleLabel(0.5))
        assertEquals("2:1", Drawing.scaleLabel(2.0))
    }

    @Test
    fun standardViewsAreLaidOutThirdAngleAndScaledToFit() {
        // A 400 x 200 x 100 part on A4: has to go down to 1:2 or smaller.
        val sizes = mapOf(ViewSide.Front to (400.0 to 100.0), ViewSide.Top to (400.0 to 200.0), ViewSide.Right to (200.0 to 100.0))
        val d = Sheet.arrange(Drawing(), listOf(ViewSide.Front, ViewSide.Top, ViewSide.Right), sizes)
        assertTrue(d.scale <= 0.5, "scale ${d.scale}")
        val front = d.views.first { it.side == ViewSide.Front }
        val top = d.views.first { it.side == ViewSide.Top }
        val right = d.views.first { it.side == ViewSide.Right }
        assertEquals(front.x, top.x)
        assertTrue(top.y > front.y)
        assertEquals(front.y, right.y)
        assertTrue(right.x > front.x)
        // First angle swaps them round.
        val first = Sheet.arrange(Drawing(firstAngle = true), listOf(ViewSide.Front, ViewSide.Top, ViewSide.Right), sizes)
        assertTrue(first.views.first { it.side == ViewSide.Top }.y < first.views.first { it.side == ViewSide.Front }.y)
        assertTrue(first.views.first { it.side == ViewSide.Right }.x < first.views.first { it.side == ViewSide.Front }.x)
    }

    @Test
    fun aDimensionMeasuresInModelMmWhateverTheScale() {
        val g = ViewGeometry(rect(40.0, 20.0), emptyList())
        val view = DrawingView(1, ViewSide.Front, 100.0, 100.0)
        val dim = DrawingDimension(2, 1, DimensionKind.Horizontal, 0.1, 0.2, 40.2, 19.9)
        val d = Drawing(scale = 0.5, views = listOf(view), dimensions = listOf(dim))
        val marks = Sheet.marks(d, mapOf(1 to g), "2026-10-05")
        // Snapped to the corners: 40, not 40.1.
        assertTrue(marks.any { it is Mark.Text && it.text == "40" }, marks.filterIsInstance<Mark.Text>().joinToString { it.text })
        // Drawn at half size, its four sides one path: the rectangle's sheet width is 20.
        val outline = marks.filterIsInstance<Mark.Poly>().single { it.pen == Pen.Visible }
        val xs = (0 until outline.points.size / 2).map { outline.points[2 * it] }
        assertEquals(20.0, xs.max() - xs.min(), 1e-9)
        assertEquals(5, outline.points.size / 2)
        // A diameter on a circle.
        val c = ViewGeometry(listOf(ProfileCurve(ProfileCurve.Kind.Circle, 0, 5.0, 5.0, r = 4.0)), emptyList())
        val dd = Drawing(views = listOf(view), dimensions = listOf(DrawingDimension(3, 1, DimensionKind.Diameter, 5.0, 5.0, 9.0, 5.0)))
        assertTrue(Sheet.marks(dd, mapOf(1 to c), "").any { it is Mark.Text && it.text == "Ø8" })
    }

    @Test
    fun aSectionIsHatchedAndItsPlaneShownOnTheTopView() {
        // A 40 x 20 cut face with a 10 x 10 hole in it: hatch lines inside the outline, none in the hole.
        val hole = rect(10.0, 10.0).map { it.copy(x1 = it.x1 + 15, x2 = it.x2 + 15, y1 = it.y1 + 5, y2 = it.y2 + 5) }
        val g = ViewGeometry(rect(40.0, 20.0), emptyList(), rect(40.0, 20.0) + hole)
        val section = DrawingView(1, ViewSide.Front, 100.0, 100.0, hidden = false, cut = -10.0, label = "A")
        val top = DrawingView(2, ViewSide.Top, 100.0, 160.0)
        val d = Drawing(views = listOf(section, top))
        val topG = ViewGeometry(rect(40.0, 30.0), emptyList())
        val marks = Sheet.marks(d, mapOf(1 to g, 2 to topG), "")
        assertTrue(marks.any { it is Mark.Text && it.text == "SECTION A-A" })
        val hatch = marks.filterIsInstance<Mark.Line>().filter { it.pen == Pen.Thin && it.y1 < 130 && kotlin.math.abs(kotlin.math.abs(it.x2 - it.x1) - kotlin.math.abs(it.y2 - it.y1)) < 1e-6 }
        assertTrue(hatch.size > 10)
        // No hatch crosses the hole's middle, which is at sheet (100, 100).
        assertTrue(hatch.none { DrawingState2.passesNear(it, 100.0, 100.0, 0.5) })
        // The front looks along -y, so seen from the top its plane is the line y = 10, lettered at both ends.
        assertEquals(2, marks.count { it is Mark.Text && it.text == "A" })
        assertTrue(marks.any { it is Mark.Line && it.pen == Pen.Thin && it.y1 == it.y2 && kotlin.math.abs(it.y1 - (160.0 + 10 - 15)) < 1e-6 })
    }

    @Test
    fun aHoleCalloutSaysWhatItsToldOnALineEach() {
        val view = DrawingView(1, ViewSide.Top, 100.0, 100.0)
        val c = ViewGeometry(listOf(ProfileCurve(ProfileCurve.Kind.Circle, 0, 5.0, 5.0, r = 2.0)), emptyList())
        val d = Drawing(views = listOf(view), dimensions = listOf(DrawingDimension(3, 1, DimensionKind.Hole, 5.0, 5.0, 7.0, 5.0)))
        val texts = Sheet.marks(d, mapOf(1 to c), "", mapOf(3 to listOf("4× Ø4 THRU", "CBORE Ø7 DEEP 3"))).filterIsInstance<Mark.Text>()
        val a = texts.first { it.text == "4× Ø4 THRU" }
        val b = texts.first { it.text == "CBORE Ø7 DEEP 3" }
        assertTrue(b.y < a.y)
        // Told nothing, it says its diameter.
        assertTrue(Sheet.marks(d, mapOf(1 to c), "").any { it is Mark.Text && it.text == "Ø4" })
    }

    @Test
    fun aDimensionAcrossThePartGrowsWithIt() {
        val view = DrawingView(1, ViewSide.Front, 100.0, 100.0)
        val dim = DrawingDimension(2, 1, DimensionKind.Horizontal, 0.0, 0.0, 40.0, 0.0, box = listOf(0.0, 0.0, 40.0, 20.0))
        val d = Drawing(views = listOf(view), dimensions = listOf(dim))
        // The part made 50 wide and moved: the dimension finds its new corners.
        val grown = ViewGeometry(rect(50.0, 20.0).map { it.copy(x1 = it.x1 - 5, x2 = it.x2 - 5) }, emptyList())
        assertTrue(Sheet.marks(d, mapOf(1 to grown), "").any { it is Mark.Text && it.text == "50" })
    }

    @Test
    fun aDrawingIsSavedWithTheDesign() {
        val design = Design()
        design.drawing = Drawing(
            paper = Paper.A3, portrait = true, scale = 0.5, firstAngle = true, title = "Bracket", drawnBy = "Dan",
            views = listOf(DrawingView(1, ViewSide.Front, 100.0, 80.0, hidden = false), DrawingView(2, ViewSide.Iso, 300.0, 200.0, scale = 0.25)),
            dimensions = listOf(DrawingDimension(3, 1, DimensionKind.Radius, 1.0, 2.0, 3.0, 4.0, -6.0, listOf(0.0, 1.0, 2.0, 3.0))),
            notes = listOf(DrawingNote(4, 20.0, 30.0, "Break sharp edges\nPLA", 5.0)),
        )
        val text = DesignFile.write(design, "t")
        val back = Design()
        DesignFile.read(text, back)
        assertEquals(design.drawing, back.drawing)
        // Undo brings back the drawing as it was.
        val snap = back.snapshot()
        back.drawing = null
        back.restore(snap)
        assertEquals(design.drawing, back.drawing)
    }

    @Test
    fun arcsBoundByTheirReach() {
        val half = ProfileCurve(ProfileCurve.Kind.Arc, 0, 0.0, 0.0, r = 10.0, a0 = 0.0, a1 = PI)
        val g = ViewGeometry(listOf(half), emptyList())
        assertEquals(-10.0, g.minX, 1e-9)
        assertEquals(10.0, g.maxY, 1e-9)
        assertEquals(0.0, g.minY, 1e-9)
    }
}

private object DrawingState2 {
    fun passesNear(l: Mark.Line, x: Double, y: Double, r: Double): Boolean {
        val dx = l.x2 - l.x1; val dy = l.y2 - l.y1
        val t = (((x - l.x1) * dx + (y - l.y1) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(l.x1 + t * dx - x, l.y1 + t * dy - y) < r
    }
}
