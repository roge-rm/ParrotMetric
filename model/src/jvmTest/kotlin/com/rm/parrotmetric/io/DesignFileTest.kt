package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.ChamferFeature
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
        val el = s.addSpline(listOf(s.addPoint(30.0, 30.0), s.addPoint(40.0, 30.0), s.addPoint(30.0, 35.0)), shape = com.rm.parrotmetric.sketch.Spline.Shape.Ellipse)
        val co = s.addSpline(listOf(a, s.addPoint(5.0, 5.0), b), shape = com.rm.parrotmetric.sketch.Spline.Shape.Conic, rho = 0.7)
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
        d.isolated += setOf("Body 2", "Body 3")

        val text = DesignFile.write(d, "Bracket")
        val back = Design()
        assertEquals("Bracket", DesignFile.read(text, back))
        assertEquals(3, back.marker)
        assertEquals(Design.BodyInfo("Lid", "Case", hidden = true), back.bodies["Body 1"])
        assertEquals(setOf("Body 2", "Body 3"), back.isolated)
        assertEquals(d.features.size, back.features.size)
        for (i in 0 until 4) assertEquals(d.features[i].key(), back.features[i].key(), "feature $i")
        assertContentEquals(byteArrayOf(1, 2, 3, -1), (back.features[4] as ImportFeature).data)
        val s2 = (back.features[0] as SketchFeature).sketch
        // Two saved, and the arc's and ellipse's own.
        assertEquals(4, s2.constraints.size)
        assertEquals(com.rm.parrotmetric.sketch.Spline.Shape.Ellipse, (s2.curve(el.id) as com.rm.parrotmetric.sketch.Spline).shape)
        assertEquals(0.7, (s2.curve(co.id) as com.rm.parrotmetric.sketch.Spline).rho)
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
            com.rm.parrotmetric.design.HoleFeature(d.newId(), "Hole", 1, 4.0, 0.0, com.rm.parrotmetric.design.HoleKind.Countersink, 8.0, 0.0, listOf("Body 2")),
            com.rm.parrotmetric.design.LipFeature(d.newId(), "Lip", "F2.end", 1.0, 2.0, 0.2, "Body 2"),
            com.rm.parrotmetric.design.LipFeature(d.newId(), "Lip 2", "F2.end", 1.0, 2.0, 0.2, null),
            com.rm.parrotmetric.design.SnapFitFeature(d.newId(), "Snap fit", 1, com.rm.parrotmetric.design.SnapFitSizes(9.0, 5.0, 1.2, 0.8, 1.4, 0.3), "Body 2"),
            com.rm.parrotmetric.design.MirrorFeature(d.newId(), "Mirror", listOf("Body 1"), top, true),
            com.rm.parrotmetric.design.PatternFeature(d.newId(), "Pattern", emptyList(), false, com.rm.parrotmetric.design.Axis3.X, 3, 10.0, 0.0, com.rm.parrotmetric.design.Axis3.Y, 2, 5.0, false),
            com.rm.parrotmetric.design.CombineFeature(d.newId(), "Combine", "Body 1", listOf("Body 2"), Operation.Cut, true),
            com.rm.parrotmetric.design.SplitFeature(d.newId(), "Split", "Body 1", PlaneRef.OnFace("F1.s2", Vec3(0.0, 1.0, 0.0))),
            com.rm.parrotmetric.design.MoveFeature(d.newId(), "Move", listOf("Body 3"), 1.0, 2.0, 3.0, com.rm.parrotmetric.design.Axis3.Z, 0.5, true),
            com.rm.parrotmetric.design.MoveFeature(d.newId(), "Scale", listOf("Body 3"), 0.0, 0.0, 0.0, com.rm.parrotmetric.design.Axis3.Z, 0.0, false, 2.0, 1.0, 0.5),
            ChamferFeature(d.newId(), "Chamfer", listOf("F1.s1|F1.end"), 1.0, com.rm.parrotmetric.design.ChamferKind.DistanceAngle, 0.5, true),
            ExtrudeFeature(d.newId(), "Up to", 1, emptyList(), 0.0, 0.0, Operation.Join, 0.1, PlaneRef.OnFace("F1.end", Vec3(1.0, 0.0, 0.0))),
            com.rm.parrotmetric.design.PointFeature(d.newId(), "Point", 1.0, 2.0, 3.0),
            ExtrudeFeature(d.newId(), "Through", 1, emptyList(), 5.0, 0.0, Operation.Cut, throughAll = true, offset = 2.0, thin = 1.5),
            com.rm.parrotmetric.design.SplitFeature(d.newId(), "Split by", "Body 1", top, 2, "Body 2"),
            com.rm.parrotmetric.design.SweepFeature(d.newId(), "Sweep", 1, listOf(com.rm.parrotmetric.design.RegionRef(listOf(1, 2), 0.5, 0.5)), com.rm.parrotmetric.design.PathRef.Sketch(3), Operation.Join),
            com.rm.parrotmetric.design.PipeFeature(d.newId(), "Pipe", com.rm.parrotmetric.design.PathRef.Edges(listOf("F1.s1|F1.end")), 4.0, 2.0, Operation.NewBody),
            com.rm.parrotmetric.design.CoilFeature(d.newId(), "Coil", top, 1.0, 2.0, 20.0, 5.0, 3.5, 2.0, true, Operation.Cut),
            com.rm.parrotmetric.design.ThreadFeature(d.newId(), "Thread", "F2.side", 1.25),
            com.rm.parrotmetric.design.ThreadFeature(d.newId(), "Drawn", "F2.side", 1.25, 0.1, symbol = true),
            com.rm.parrotmetric.design.LinkFeature(d.newId(), "Lid", "Lid.pmet", "{\"format\":\"parrotmetric\"}", "Lid", 1.0, 2.0, 3.0),
            com.rm.parrotmetric.design.FastenerFeature(d.newId(), "Bolt", com.rm.parrotmetric.design.FastenerKind.Countersunk, "#8-32", 12.0, "F2.side", true, top, 1.0, 2.0, true, 0.2, Operation.Cut),
            com.rm.parrotmetric.design.GearFeature(d.newId(), "Gear", top, 1.0, 2.0, 1.5, 24, 6.0, 0.35, 0.3, true, 4.0, 0.15, Operation.NewBody, 0.1),
            com.rm.parrotmetric.design.GearFeature(d.newId(), "Gear 2", top, 0.0, 0.0, 1.5, 12, 6.0, meshWith = 1, around = 0.5),
            com.rm.parrotmetric.design.LoftFeature(d.newId(), "Loft", listOf(com.rm.parrotmetric.design.LoftSection(1, com.rm.parrotmetric.design.RegionRef(listOf(1), 0.0, 0.0)), com.rm.parrotmetric.design.LoftSection(3, com.rm.parrotmetric.design.RegionRef(listOf(2), 1.0, 1.0))), true, Operation.NewBody),
            com.rm.parrotmetric.design.PlaneFeature(
                d.newId(), "Three", com.rm.parrotmetric.design.PlaneFeature.Kind.ThreePoints, top, 0.0, 0.0, false, null,
                listOf(com.rm.parrotmetric.design.PointRef.Corner("a & b"), com.rm.parrotmetric.design.PointRef.CentreOf("F1.s5|F1.end"), com.rm.parrotmetric.design.PointRef.Construction(4)),
                listOf("F1.s1|F1.end"), "F2.side", 0.25,
            ),
            com.rm.parrotmetric.design.PointFeature(d.newId(), "Meet", 0.0, 0.0, 0.0, com.rm.parrotmetric.design.PointFeature.Kind.ThreePlanes, null, listOf(top, top, top)),
            com.rm.parrotmetric.design.AxisFeature(d.newId(), "Round", 0.0, 0.0, 0.0, com.rm.parrotmetric.design.Axis3.Z, com.rm.parrotmetric.design.AxisFeature.Kind.Round, "F1.s5|F1.end", "F2.side", listOf(com.rm.parrotmetric.design.PointRef.Construction(4))),
            com.rm.parrotmetric.design.AlignFeature(d.newId(), "Align", listOf("Body 2"), "F1.end", PlaneRef.OnFace("F3.s1", Vec3(1.0, 0.0, 0.0)), true, true, 1.5),
            com.rm.parrotmetric.design.PrimitiveFeature(d.newId(), "Torus", com.rm.parrotmetric.design.PrimitiveKind.Torus, top, 1.0, 2.0, 30.0, 6.0, 0.0, Operation.Join),
            com.rm.parrotmetric.design.OffsetFaceFeature(d.newId(), "Press pull", listOf("F1.end"), -2.5),
            com.rm.parrotmetric.design.DeleteFaceFeature(d.newId(), "Delete face", listOf("F4.r(F1.s1|F1.end)")),
            com.rm.parrotmetric.design.RibFeature(d.newId(), "Web", 1, 2.5, true, true),
            com.rm.parrotmetric.design.EmbossFeature(d.newId(), "Emboss", 1, listOf(com.rm.parrotmetric.design.RegionRef(listOf(1, 2), 0.5, 0.5)), "F2.side", 0.8, true),
            com.rm.parrotmetric.design.PatchFeature(d.newId(), "Patch", 1, listOf(com.rm.parrotmetric.design.RegionRef(listOf(1, 2), 0.5, 0.5)), emptyList()),
            com.rm.parrotmetric.design.PatchFeature(d.newId(), "Fill", null, emptyList(), listOf("F1.s1|F1.end")),
            com.rm.parrotmetric.design.StitchFeature(d.newId(), "Stitch", listOf("Body 1", "Body 2")),
            com.rm.parrotmetric.design.JointFeature(d.newId(), "Hinge", com.rm.parrotmetric.design.JointKind.Turn, "Lid", "Case", edge = "F1.s1|F1.end", value = 0.5),
            com.rm.parrotmetric.design.JointFeature(d.newId(), "Held", com.rm.parrotmetric.design.JointKind.Rigid, "Knob", null, axis = com.rm.parrotmetric.design.Axis3.X),
            com.rm.parrotmetric.design.JointFeature(
                d.newId(), "Slider", com.rm.parrotmetric.design.JointKind.Planar, "Lid", "Case", face = "F1.end", value = 0.2, value2 = 3.0, value3 = -4.0,
                turnMin = -1.0, turnMax = 1.5, slideMin = -10.0, slideMax = 12.0,
            ),
            com.rm.parrotmetric.design.JointFeature(d.newId(), "Socket", com.rm.parrotmetric.design.JointKind.Ball, "Knob", "Case", value = 0.1, value2 = 0.2, value3 = 0.3),
            com.rm.parrotmetric.design.JointFeature(d.newId(), "Driven", com.rm.parrotmetric.design.JointKind.Turn, "Knob", null, linkedTo = 3, ratio = -1.5),
            com.rm.parrotmetric.design.JointFeature(d.newId(), "By teeth", com.rm.parrotmetric.design.JointKind.Turn, "Knob", null, linkedTo = 3),
            com.rm.parrotmetric.design.ThickenFeature(d.newId(), "Thicken", "Body 1", 1.5, true),
            com.rm.parrotmetric.design.MeshEditFeature(d.newId(), "Smooth", "Body 2", com.rm.parrotmetric.design.MeshEdit.Smooth, 40.0, 3),
            com.rm.parrotmetric.design.MeshEditFeature(d.newId(), "Hollow", "Body 2", com.rm.parrotmetric.design.MeshEdit.Hollow, 2.0),
            com.rm.parrotmetric.design.MeshEraseFeature(d.newId(), "Erase", "Body 2", listOf(1.0, 2.0, 3.0, 4.5)),
            com.rm.parrotmetric.design.SeparateFeature(d.newId(), "Separate", "Body 2"),
            com.rm.parrotmetric.design.OffsetSurfaceFeature(d.newId(), "Skin", listOf("F2.side"), -1.5),
            ExtrudeFeature(d.newId(), "Sheet", 1, emptyList(), 5.0, 0.0, Operation.NewBody, surface = true),
            ExtrudeFeature(d.newId(), "From a face", -1, emptyList(), 4.0, 0.0, Operation.Join, face = "F1.end"),
            com.rm.parrotmetric.design.FilletFeature(d.newId(), "Variable", listOf("F1.s1|F1.end"), 1.0, com.rm.parrotmetric.design.FilletKind.Variable, 3.0),
            com.rm.parrotmetric.design.MirrorFeature(d.newId(), "Mirror features", emptyList(), top, false, listOf(2, 5)),
            com.rm.parrotmetric.design.PatternFeature(
                d.newId(), "Along", emptyList(), false, com.rm.parrotmetric.design.Axis3.X, 4, 12.0, 0.0, null, 1, 0.0, true,
                path = com.rm.parrotmetric.design.PathRef.Edges(listOf("F1.s1|F1.end")), turn = true, features = listOf(2), reverse = true,
            ),
        )
        features.forEach { d.add(it) }
        d.suppressed += features[1].id
        d.hints["${features[0].id}:F1.end"] = doubleArrayOf(0.0, 1.0, 2.5)
        val back = Design()
        DesignFile.read(DesignFile.write(d, "x"), back)
        assertEquals(features, back.features)
        assertEquals(setOf(features[1].id), back.suppressed)
        assertContentEquals(doubleArrayOf(0.0, 1.0, 2.5), back.hints["${features[0].id}:F1.end"])
    }

    @Test
    fun aSketchOnAFaceFromAnOlderFileKeepsTheMiddleOfTheFace() {
        val d = Design()
        d.add(SketchFeature(d.newId(), "Sketch 1", PlaneRef.OnFace("F2.end", Vec3(1.0, 0.0, 0.0)), Sketch()))
        val text = DesignFile.write(d, "x")
        val older = Regex(",\\s*\"fromOrigin\"\\s*:\\s*true").replace(text, "")
        assertTrue(older != text)
        val back = Design()
        DesignFile.read(older, back)
        assertEquals(false, ((back.features[0] as SketchFeature).plane as PlaneRef.OnFace).fromOrigin)
        val again = Design()
        DesignFile.read(text, again)
        assertEquals(true, ((again.features[0] as SketchFeature).plane as PlaneRef.OnFace).fromOrigin)
    }

    @Test
    fun whatASketchProjectedStaysTiedToItsSource() {
        val d = Design()
        val sk = Sketch()
        val curves = listOf(
            com.rm.parrotmetric.sketch.ProfileCurve(com.rm.parrotmetric.sketch.ProfileCurve.Kind.Line, 0, 0.0, 0.0, 10.0, 0.0),
            com.rm.parrotmetric.sketch.ProfileCurve(com.rm.parrotmetric.sketch.ProfileCurve.Kind.Circle, 0, 5.0, 5.0, r = 1.0),
        )
        sk.links += com.rm.parrotmetric.sketch.SketchOps.project(sk, curves, true, listOf("Body 1", "Body 2"))
        d.add(SketchFeature(d.newId(), "Sketch 1", PlaneRef.Fixed(SketchPlane.Top), sk))
        val back = Design()
        DesignFile.read(DesignFile.write(d, "x"), back)
        val link = (back.features[0] as SketchFeature).sketch.links.single()
        assertEquals(true, link.section)
        assertEquals(listOf("Body 1", "Body 2"), link.bodies)
        assertEquals(sk.links[0].points.map { it.id }, link.points.map { it.id })
        assertEquals(1, link.circles.size)
    }

    @Test
    fun otherFilesAreTurnedAway() {
        assertFailsWith<IllegalArgumentException> { DesignFile.read("{\"format\":\"something\"}", Design()) }
        assertFailsWith<IllegalArgumentException> { DesignFile.read("not json", Design()) }
        assertFailsWith<IllegalArgumentException> {
            DesignFile.read("{\"format\":\"parrotmetric\",\"version\":99,\"marker\":0,\"features\":[]}", Design())
        }
    }

    @Test
    fun configurationsComeBackAndSwitch() {
        val d = Design()
        d.parameters += com.rm.parrotmetric.design.Parameter("width", "20")
        d.parameters += com.rm.parrotmetric.design.Parameter("height", "5")
        val f = ExtrudeFeature(d.newId(), "Extrude", 1, emptyList(), 5.0, 0.0, Operation.NewBody)
        d.add(f)
        d.saveConfiguration("Small")
        d.parameters[0] = d.parameters[0].copy(expression = "40")
        d.suppressed += f.id
        d.saveConfiguration("Big")
        val back = Design()
        DesignFile.read(DesignFile.write(back.let { d }, "x"), back)
        assertEquals(listOf("Small", "Big"), back.configurations.map { it.name })
        assertEquals("Big", back.configuration)
        back.useConfiguration("Small")
        assertEquals("20", back.parameters[0].expression)
        assertTrue(back.suppressed.isEmpty())
        back.useConfiguration("Big")
        assertEquals("40", back.parameters[0].expression)
        assertEquals(setOf(f.id), back.suppressed)
    }
}
