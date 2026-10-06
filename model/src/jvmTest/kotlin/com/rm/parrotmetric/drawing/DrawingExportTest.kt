package com.rm.parrotmetric.drawing

import com.rm.parrotmetric.io.DrawingImport
import com.rm.parrotmetric.io.Drawn
import com.rm.parrotmetric.sketch.ProfileCurve
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrawingExportTest {
    private val drawing = Drawing(title = "Plate (test)", drawnBy = "Dan", views = listOf(DrawingView(1, ViewSide.Top, 100.0, 120.0)),
        dimensions = listOf(DrawingDimension(2, 1, DimensionKind.Horizontal, 0.0, 0.0, 40.0, 0.0), DrawingDimension(3, 1, DimensionKind.Diameter, 20.0, 10.0, 24.0, 10.0)))
    private val geometry = ViewGeometry(
        listOf(
            ProfileCurve(ProfileCurve.Kind.Line, 0, 0.0, 0.0, 40.0, 0.0), ProfileCurve(ProfileCurve.Kind.Line, 0, 40.0, 0.0, 40.0, 20.0),
            ProfileCurve(ProfileCurve.Kind.Line, 0, 40.0, 20.0, 0.0, 20.0), ProfileCurve(ProfileCurve.Kind.Line, 0, 0.0, 20.0, 0.0, 0.0),
            ProfileCurve(ProfileCurve.Kind.Circle, 0, 20.0, 10.0, r = 4.0),
            ProfileCurve(ProfileCurve.Kind.Arc, 0, 5.0, 5.0, r = 2.0, a0 = 0.0, a1 = PI / 2),
        ),
        listOf(ProfileCurve(ProfileCurve.Kind.Line, 0, 10.0, 0.0, 10.0, 20.0)),
    )
    private val marks = Sheet.marks(drawing, mapOf(1 to geometry), "2026-10-05")

    @Test
    fun pdfHasItsObjectsWhereTheIndexSays() {
        val bytes = DrawingExport.pdf(drawing, marks)
        val text = bytes.decodeToString()
        assertTrue(text.startsWith("%PDF-1.4"))
        val start = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        val latin = String(bytes, Charsets.ISO_8859_1)
        assertTrue(latin.substring(start).startsWith("xref"))
        val offsets = latin.substring(start).lines().drop(3).takeWhile { it.endsWith("n ") }.map { it.substring(0, 10).toInt() }
        for ((i, o) in offsets.withIndex()) assertTrue(latin.substring(o).startsWith("${i + 1} 0 obj"), "object ${i + 1}")
        // The brackets in the title are escaped.
        assertTrue(text.contains("(Plate \\(test\\)) Tj"))
    }

    @Test
    fun dxfReadsBackAsTheSameLines() {
        val back = DrawingImport.dxf(DrawingExport.dxf(marks))
        // Single lines, and each piece of a joined path.
        val lines = marks.filterIsInstance<Mark.Line>().size + marks.filterIsInstance<Mark.Poly>().sumOf { it.points.size / 2 - 1 }
        assertEquals(lines, back.count { it is Drawn.Line })
        assertTrue(back.any { it is Drawn.Circle })
        val dxf = DrawingExport.dxf(marks)
        assertTrue(dxf.contains("HIDDEN") && dxf.contains("%%c8"))
    }

    @Test
    fun svgHasEveryMark() {
        val svg = DrawingExport.svg(drawing, marks)
        assertEquals(marks.count { it is Mark.Line }, Regex("<line ").findAll(svg).count())
        assertEquals(marks.count { it is Mark.Poly }, Regex("<polyline ").findAll(svg).count())
        assertTrue(svg.contains("stroke-dasharray"))
        assertTrue(svg.contains(">Ø8</text>"))
        assertTrue(svg.contains("Plate (test)"))
    }

    @Test
    fun numbersAreWrittenPlainly() {
        assertEquals("12.5", DrawingExport.fmt(12.5))
        assertEquals("-0.0001", DrawingExport.fmt(-0.0001))
        assertEquals("3", DrawingExport.fmt(2.99999))
        assertEquals("0", DrawingExport.fmt(1e-9))
    }
}
