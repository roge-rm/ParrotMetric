package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.design.ExtrudeFeature
import com.rm.parrotmetric.design.FilletFeature
import com.rm.parrotmetric.design.ImportFeature
import com.rm.parrotmetric.design.Operation
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.RegionRef
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesignFileTest {
    @Test
    fun aDesignComesBackTheSame() {
        val d = Design()
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(40.0, 0.0); val c = s.addPoint(40.0, 20.0)
        val l1 = s.addLine(a, b); val l2 = s.addLine(b, c); val l3 = s.addLine(c, a, construction = true)
        s.addArc(s.addPoint(10.0, 10.0), s.addPoint(15.0, 10.0), s.addPoint(10.0, 15.0))
        s.add(Constraint.Horizontal(l1))
        s.add(Constraint.Length(l2, 20.0))
        val sketch = SketchFeature(d.newId(), "Sketch \"1\"", PlaneRef.Fixed(SketchPlane.Front), s)
        d.add(sketch)
        d.add(ExtrudeFeature(d.newId(), "Extrude 1", sketch.id, listOf(RegionRef(listOf(1, 2, 3), 1.5, 2.5)), 10.0, 2.0, Operation.Cut))
        d.add(FilletFeature(d.newId(), "Fillet 1", listOf("F2.r(F1.end|F1.s1)|F1.s2"), 2.5))
        d.add(SketchFeature(d.newId(), "Sketch 2", PlaneRef.OnFace("F2.end", Vec3(1.0, 0.0, 0.0)), Sketch()))
        d.add(ImportFeature(d.newId(), "part.stl", byteArrayOf(1, 2, 3, -1), 0))
        d.moveMarker(3)
        d.bodies["Body 1"] = Design.BodyInfo("Lid", "Case", hidden = true)

        val text = DesignFile.write(d, "Bracket")
        val back = Design()
        assertEquals("Bracket", DesignFile.read(text, back))
        assertEquals(3, back.marker)
        assertEquals(Design.BodyInfo("Lid", "Case", hidden = true), back.bodies["Body 1"])
        assertEquals(d.features.size, back.features.size)
        for (i in 0 until 4) assertEquals(d.features[i].key(), back.features[i].key(), "feature $i")
        assertContentEquals(byteArrayOf(1, 2, 3, -1), (back.features[4] as ImportFeature).data)
        val s2 = (back.features[0] as SketchFeature).sketch
        // Two saved, and the arc's own.
        assertEquals(3, s2.constraints.size)
        assertTrue(s2.curve(l3.id)!!.construction)
        // New things in a loaded design get fresh ids.
        assertEquals(6, back.newId())
    }

    @Test
    fun modifyStepsComeBackTheSame() {
        val d = Design()
        val top = PlaneRef.Fixed(SketchPlane.Top)
        val features = listOf(
            com.rm.parrotmetric.design.ShellFeature(d.newId(), "Shell", listOf("F1.end"), 2.0),
            com.rm.parrotmetric.design.DraftFeature(d.newId(), "Draft", listOf("F1.s1"), "F1.start", 0.1),
            com.rm.parrotmetric.design.HoleFeature(d.newId(), "Hole", 1, 4.0, 0.0, com.rm.parrotmetric.design.HoleKind.Countersink, 8.0, 0.0),
            com.rm.parrotmetric.design.MirrorFeature(d.newId(), "Mirror", listOf("Body 1"), top, true),
            com.rm.parrotmetric.design.PatternFeature(d.newId(), "Pattern", emptyList(), false, com.rm.parrotmetric.design.Axis3.X, 3, 10.0, 0.0, com.rm.parrotmetric.design.Axis3.Y, 2, 5.0, false),
            com.rm.parrotmetric.design.CombineFeature(d.newId(), "Combine", "Body 1", listOf("Body 2"), Operation.Cut, true),
            com.rm.parrotmetric.design.SplitFeature(d.newId(), "Split", "Body 1", PlaneRef.OnFace("F1.s2", Vec3(0.0, 1.0, 0.0))),
            com.rm.parrotmetric.design.MoveFeature(d.newId(), "Move", listOf("Body 3"), 1.0, 2.0, 3.0, com.rm.parrotmetric.design.Axis3.Z, 0.5, true),
        )
        features.forEach { d.add(it) }
        val back = Design()
        DesignFile.read(DesignFile.write(d, "x"), back)
        assertEquals(features, back.features)
    }

    @Test
    fun otherFilesAreTurnedAway() {
        assertFailsWith<IllegalArgumentException> { DesignFile.read("{\"format\":\"something\"}", Design()) }
        assertFailsWith<IllegalArgumentException> { DesignFile.read("not json", Design()) }
        assertFailsWith<IllegalArgumentException> {
            DesignFile.read("{\"format\":\"parrotmetric\",\"version\":99,\"marker\":0,\"features\":[]}", Design())
        }
    }
}
