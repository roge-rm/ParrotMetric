package com.rm.parrotmetric.sketch

import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.io.DesignFile
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SketchTextTest {
    private val square = listOf(
        ProfileCurve(ProfileCurve.Kind.Line, 0, 0.0, 0.0, 1.0, 0.0),
        ProfileCurve(ProfileCurve.Kind.Line, 1, 1.0, 0.0, 1.0, 1.0),
        ProfileCurve(ProfileCurve.Kind.Line, 2, 1.0, 1.0, 0.0, 1.0),
        ProfileCurve(ProfileCurve.Kind.Line, 3, 0.0, 1.0, 0.0, 0.0),
    )

    @Test
    fun textIsPlacedAtItsAnchorAndTurned() {
        val s = Sketch()
        val anchor = s.addPoint(10.0, 5.0)
        val t = s.addText(anchor, "A", 1.0, false, PI / 2, square)
        val placed = s.profileCurves()
        assertEquals(4, placed.size)
        // (1, 0) turned a quarter turn is (0, 1), then moved to the anchor.
        assertEquals(10.0, placed[0].x2, 1e-9)
        assertEquals(6.0, placed[0].y2, 1e-9)
        assertTrue(placed.all { it.id >= textCurveBase })
        // Moving the anchor moves the text.
        s.drag(anchor, 20.0, 5.0)
        assertEquals(20.0, s.profileCurves()[0].x1, 1e-6)
        // Undo brings it back after it's taken away.
        val before = s.snapshot()
        s.removeText(t)
        assertTrue(s.profileCurves().isEmpty())
        s.restore(before)
        assertEquals(4, s.profileCurves().size)
    }

    @Test
    fun textComesBackFromAFile() {
        val d = Design()
        val s = Sketch()
        s.addText(s.addPoint(3.0, 4.0), "Hi", 8.0, true, 0.25, square, TextAlign.Centre)
        d.add(SketchFeature(d.newId(), "Sketch", PlaneRef.Fixed(SketchPlane.Top), s))
        val back = Design()
        DesignFile.read(DesignFile.write(d, "x"), back)
        val t = (back.features[0] as SketchFeature).sketch.texts.single()
        assertEquals("Hi", t.text)
        assertEquals(8.0, t.height)
        assertTrue(t.bold)
        assertEquals(0.25, t.angle)
        assertEquals(TextAlign.Centre, t.align)
        assertEquals(square.map { it.copy(id = 0) }, t.outline)
        assertEquals(3.0, (back.features[0] as SketchFeature).sketch.x(t.anchor), 1e-9)
    }

    @Test
    fun textLinesUpOnItsAnchor() {
        val centred = alignOutline(square, TextAlign.Centre)
        assertEquals(-0.5, centred.minOf { minOf(it.x1, it.x2) }, 1e-9)
        assertEquals(0.5, centred.maxOf { maxOf(it.y1, it.y2) }, 1e-9)
        val right = alignOutline(square, TextAlign.Right)
        assertEquals(0.0, right.maxOf { maxOf(it.x1, it.x2) }, 1e-9)
        assertEquals(0.0, right.minOf { minOf(it.y1, it.y2) }, 1e-9)
        assertEquals(square, alignOutline(square, TextAlign.Left))
    }

    @Test
    fun aFontFileGoesWithTheDesignWhileTextUsesIt() {
        val d = Design()
        val s = Sketch()
        s.addText(s.addPoint(0.0, 0.0), "Hi", 8.0, false, 0.0, square, font = "Stencil")
        d.add(SketchFeature(d.newId(), "Sketch", PlaneRef.Fixed(SketchPlane.Top), s))
        d.fonts["Stencil"] = byteArrayOf(1, 2, 3)
        d.fonts["Unused"] = byteArrayOf(4)
        val back = Design()
        DesignFile.read(DesignFile.write(d, "x"), back)
        assertEquals("Stencil", (back.features[0] as SketchFeature).sketch.texts.single().font)
        assertEquals(listOf<Byte>(1, 2, 3), back.fonts["Stencil"]?.toList())
        assertTrue("Unused" !in back.fonts)
    }

    @Test
    fun aBezierSplineIsItsCurvesExactly() {
        val s = Sketch()
        val p = listOf(0.0 to 0.0, 1.0 to 2.0, 3.0 to 2.0, 4.0 to 0.0, 5.0 to -2.0, 7.0 to -2.0, 8.0 to 0.0).map { s.addPoint(it.first, it.second) }
        s.addSpline(p, shape = Spline.Shape.Bezier)
        val pieces = s.profileCurves()
        assertEquals(2, pieces.size)
        assertEquals(ProfileCurve.Kind.Bezier, pieces[0].kind)
        assertEquals(1.0, pieces[0].cx1, 1e-9)
        assertEquals(3.0, pieces[0].cx2, 1e-9)
        assertEquals(4.0, pieces[0].x2, 1e-9)
        assertEquals(8.0, pieces[1].x2, 1e-9)
    }
}
