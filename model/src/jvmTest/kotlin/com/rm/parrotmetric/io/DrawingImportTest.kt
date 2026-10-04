package com.rm.parrotmetric.io

import com.rm.parrotmetric.sketch.Arc
import com.rm.parrotmetric.sketch.Circle
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.Spline
import com.rm.parrotmetric.sketch.profileCurves
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DrawingImportTest {
    private fun near(expected: Double, actual: Double, what: String = "") = assertEquals(expected, actual, 1e-6, what)

    @Test
    fun svgInMillimetresWithYUp() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100mm" height="50mm" viewBox="0 0 200 100">
            <rect x="20" y="10" width="40" height="20"/>
            <circle cx="100" cy="50" r="10"/>
        </svg>"""
        val drawn = DrawingImport.svg(svg)
        val lines = drawn.filterIsInstance<Drawn.Line>()
        assertEquals(4, lines.size)
        // 200 user units across 100 mm: half a mm each; y measured up from the bottom of the page.
        near(10.0, lines.minOf { minOf(it.x1, it.x2) }, "left")
        near(30.0, lines.maxOf { maxOf(it.x1, it.x2) }, "right")
        near(-5.0, lines.maxOf { maxOf(it.y1, it.y2) }, "top")
        val c = drawn.filterIsInstance<Drawn.Circle>().single()
        near(5.0, c.r, "radius")
        near(50.0, c.cx)
        near(-25.0, c.cy)
    }

    @Test
    fun svgPathsWithCurvesAndArcs() {
        val svg = """<svg width="10mm" height="10mm" viewBox="0 0 10 10">
            <g transform="translate(1,0)"><path d="M0 0 L4 0 A2 2 0 0 1 4 4 C3 5 1 5 0 4 z"/></g>
        </svg>"""
        val drawn = DrawingImport.svg(svg)
        assertEquals(1, drawn.filterIsInstance<Drawn.Arc>().size)
        assertEquals(1, drawn.filterIsInstance<Drawn.Bezier>().size)
        assertEquals(2, drawn.filterIsInstance<Drawn.Line>().size)
        val arc = drawn.filterIsInstance<Drawn.Arc>().single()
        near(2.0, arc.r)
        near(5.0, arc.cx, "moved by the group")
        // Clockwise on the page, y down: anticlockwise once y is up, from the end to the start... either way a half turn.
        var sweep = arc.a1 - arc.a0
        while (sweep <= 0) sweep += 2 * PI
        near(PI, sweep, "half a turn")
    }

    @Test
    fun dxfLinesArcsAndBulges() {
        val dxf = listOf(
            "0", "SECTION", "2", "HEADER", "9", "\$INSUNITS", "70", "4", "0", "ENDSEC",
            "0", "SECTION", "2", "ENTITIES",
            "0", "LINE", "8", "0", "10", "0", "20", "0", "11", "10", "21", "0",
            "0", "CIRCLE", "8", "0", "10", "5", "20", "5", "40", "2",
            "0", "ARC", "8", "0", "10", "0", "20", "0", "40", "3", "50", "0", "51", "90",
            "0", "LWPOLYLINE", "8", "0", "90", "2", "70", "0", "10", "0", "20", "0", "42", "1", "10", "10", "20", "0",
            "0", "ENDSEC", "0", "EOF",
        ).joinToString("\n")
        val drawn = DrawingImport.dxf(dxf)
        assertEquals(1, drawn.filterIsInstance<Drawn.Line>().size)
        assertEquals(1, drawn.filterIsInstance<Drawn.Circle>().size)
        val arcs = drawn.filterIsInstance<Drawn.Arc>()
        assertEquals(2, arcs.size)
        // A bulge of 1 is a half circle on the chord: radius 5 round its middle.
        val half = arcs.first { it.r > 4 }
        near(5.0, half.r)
        near(5.0, half.cx)
        near(0.0, half.cy)
    }

    @Test
    fun addedToASketchWithEndsJoined() {
        val s = Sketch()
        val n = DrawingImport.addTo(s, listOf(
            Drawn.Line(0.0, 0.0, 10.0, 0.0), Drawn.Line(10.0, 0.0, 10.0, 10.0), Drawn.Line(10.0, 10.0, 0.0, 0.0),
            Drawn.Arc(20.0, 0.0, 5.0, 0.0, PI), Drawn.Circle(40.0, 0.0, 2.0),
            Drawn.Bezier(0.0, 20.0, 3.0, 25.0, 7.0, 25.0, 10.0, 20.0),
        ))
        assertEquals(6, n)
        assertEquals(3, s.curves.filterIsInstance<Line>().size)
        assertEquals(1, s.curves.filterIsInstance<Arc>().size)
        assertEquals(1, s.curves.filterIsInstance<Circle>().size)
        assertEquals(1, s.curves.filterIsInstance<Spline>().size)
        // The triangle's three lines share three corners.
        val corners = s.curves.filterIsInstance<Line>().flatMap { listOf(it.a, it.b) }.toSet()
        assertEquals(3, corners.size)
        assertTrue(s.profileCurves().isNotEmpty())
    }

    @Test
    fun otherFilesAreTurnedAway() {
        assertFailsWith<IllegalArgumentException> { DrawingImport.read("model.stl", "") }
    }
}
