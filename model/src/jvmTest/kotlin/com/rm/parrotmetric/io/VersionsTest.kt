package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.drawing.SketchDxf
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Spline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionsTest {
    private fun sketched(d: Design, name: String) = d.add(SketchFeature(d.newId(), name, PlaneRef.Fixed(SketchPlane.Top), Sketch()))

    @Test
    fun versionsGoWithTheFileAndBringTheDesignBack() {
        val d = Design()
        sketched(d, "First")
        d.versions += Design.Version("One step", "2026-10-06", DesignFile.write(d, "Box", withVersions = false))
        sketched(d, "Second")
        val back = Design()
        assertEquals("Box", DesignFile.read(DesignFile.write(d, "Box"), back))
        assertEquals(2, back.features.size)
        val v = back.versions.single()
        assertEquals("One step", v.name)
        // A version's own copy has no versions in it.
        assertTrue("\"versions\":[" !in v.text.replace(" ", ""))
        // Going back to it keeps the versions.
        DesignFile.read(v.text, back, keepVersions = true)
        assertEquals(1, back.features.size)
        assertEquals(1, back.versions.size)
    }

    @Test
    fun aSketchSavesAsDxfWithItsCurvesNotItsConstruction() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 0.0); val c = s.addPoint(10.0, 5.0)
        s.addLine(a, b); s.addLine(b, c)
        s.addLine(a, c, construction = true)
        s.addCircle(s.addPoint(20.0, 0.0), 3.0)
        s.addSpline(listOf(a, s.addPoint(0.0, 5.0), s.addPoint(5.0, 9.0), c), shape = Spline.Shape.Bezier)
        val dxf = SketchDxf.write(s)
        assertEquals(2, Regex("\nLINE\n").findAll(dxf).count())
        assertEquals(1, Regex("\nCIRCLE\n").findAll(dxf).count())
        assertEquals(1, Regex("\nPOLYLINE\n").findAll(dxf).count())
        assertTrue(dxf.trimEnd().endsWith("EOF"))
    }

    @Test
    fun namedViewsGoWithTheFile() {
        val d = Design()
        d.views += Design.NamedView("Front close", listOf(1.0, 2.0, 3.0, 0.5, 0.25, 120.0))
        val back = Design()
        DesignFile.read(DesignFile.write(d, "x"), back)
        assertEquals(d.views, back.views)
    }

    @Test
    fun aSeeThroughBodyStaysSo() {
        val d = Design()
        d.bodies["Body 1"] = Design.BodyInfo(colour = 0x336699, seeThrough = true)
        val back = Design()
        DesignFile.read(DesignFile.write(d, "x"), back)
        assertEquals(Design.BodyInfo(colour = 0x336699, seeThrough = true), back.info("Body 1"))
    }
}
