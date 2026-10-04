package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A kernel that makes no geometry: bodies are boxes along x, so overlap is
 * easy to reason about, and every call is counted.
 */
private class FakeKernel : Kernel {
    data class Box(val from: Double, val to: Double, val faces: List<String>)

    val bodies = mutableMapOf<Long, Box>()
    val counts = mutableMapOf<Long, Int>()
    val calls = mutableListOf<String>()
    /** What extrudes call their side, to take a name away. */
    var side = "s1"
    var lastForward = 0.0
    private var next = 1L

    private fun make(b: Box): Long {
        val h = next++
        bodies[h] = b
        counts[h] = 1
        return h
    }

    var lastBack = 0.0
    var lastThin = 0.0

    override fun extrude(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, forward: Double, back: Double, taper: Double, thin: Double): Long {
        calls += "extrude $id"
        lastForward = forward
        lastBack = back
        lastThin = thin
        val x = curves.minOf { minOf(it.x1, it.x2) }
        val w = curves.maxOf { maxOf(it.x1, it.x2) } - x
        return make(Box(x, x + w, listOf("F$id.$side", "F$id.end")))
    }

    /** A box's place along x, for faces and edges it has. */
    override fun signature(body: Long, name: String, edge: Boolean) = bodies.getValue(body).let { b ->
        if (name.split('|').all { it in b.faces }) doubleArrayOf(b.from, b.to, if (edge) 1.0 else 0.0) else null
    }

    override fun relocate(body: Long, signature: DoubleArray) = bodies.getValue(body).let { b ->
        if (b.from != signature[0] || b.to != signature[1]) null
        else if (signature[2] == 1.0) b.faces.take(2).sorted().joinToString("|") else b.faces.first()
    }

    override fun revolve(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double) =
        throw KernelException("No revolves here")

    override fun combine(id: Int, target: Long, tool: Long, how: Operation): Long {
        calls += "combine $id $how"
        val a = bodies.getValue(target); val b = bodies.getValue(tool)
        return when (how) {
            Operation.Cut -> make(Box(a.from, b.from, a.faces + b.faces))
            else -> make(Box(minOf(a.from, b.from), maxOf(a.to, b.to), a.faces + b.faces))
        }
    }

    override fun fillet(id: Int, body: Long, edges: List<String>, radius: Double): Long {
        calls += "fillet $id $edges"
        if (radius > 5) throw KernelException("The fillet doesn't fit")
        return make(bodies.getValue(body).let { it.copy(faces = it.faces + "F$id.r") })
    }

    override fun chamfer(id: Int, body: Long, edges: List<String>, distance: Double, kind: Int, second: Double, flip: Boolean) = fillet(id, body, edges, distance)

    override fun overlaps(a: Long, b: Long): Boolean {
        val x = bodies.getValue(a); val y = bodies.getValue(b)
        return x.from < y.to && y.from < x.to
    }

    override fun facePlane(body: Long, face: String) = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 0.0, 1.0)
    override fun faceNames(body: Long) = bodies.getValue(body).faces
    override fun import(id: Int, data: ByteArray, format: Int) = make(Box(0.0, 1.0, listOf("F$id.i0")))
    override fun shell(id: Int, body: Long, open: List<String>, thickness: Double) = make(bodies.getValue(body))
    override fun draft(id: Int, body: Long, faces: List<String>, neutral: String, angle: Double) = make(bodies.getValue(body))

    /** Moves along x by the matrix's x translation, or mirrors when the first entry is negative. */
    override fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long {
        calls += "transform $id $tag"
        val b = bodies.getValue(body)
        return if (m[0] < 0) make(Box(-b.to, -b.from, b.faces)) else make(Box(b.from + m[3], b.to + m[3], b.faces))
    }

    override fun split(id: Int, body: Long, origin: Vec3, normal: Vec3): List<Long> {
        val b = bodies.getValue(body)
        if (origin.x <= b.from || origin.x >= b.to) throw KernelException("The plane doesn't cut through the body")
        return listOf(make(Box(b.from, origin.x, b.faces)), make(Box(origin.x, b.to, b.faces)))
    }

    override fun convertToSolid(id: Int, body: Long) = make(bodies.getValue(body))
    override fun primitive(id: Int, plane: SketchPlane, kind: Int, u: Double, v: Double, a: Double, b: Double, c: Double): Long {
        calls += "primitive $id $kind"
        return make(Box(u - a / 2, u + a / 2, listOf("F$id.start", "F$id.end")))
    }
    /** Boxes are 10 deep and 10 high, on z from 0. */
    override fun bounds(body: Long) = bodies.getValue(body).let { doubleArrayOf(it.from, 0.0, 0.0, it.to, 10.0, 10.0) }
    override fun centre(body: Long) = bodies.getValue(body).let { Vec3((it.from + it.to) / 2, 0.0, 0.0) }
    override fun holeTool(id: Int, plane: SketchPlane, at: List<Pair<Double, Double>>, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double) =
        make(Box(at.minOf { it.first }, at.maxOf { it.first } + diameter, emptyList()))
    override fun retain(body: Long) { counts[body] = counts.getValue(body) + 1 }
    override fun release(body: Long) {
        val n = counts.getValue(body) - 1
        if (n == 0) { counts.remove(body); bodies.remove(body) } else counts[body] = n
    }
}

class RebuildTest {
    private fun sketchAt(design: Design, x: Double, w: Double): SketchFeature {
        val s = Sketch()
        val a = s.addPoint(x, 0.0); val b = s.addPoint(x + w, 0.0); val c = s.addPoint(x + w, 10.0); val d = s.addPoint(x, 10.0)
        s.addLine(a, b); s.addLine(b, c); s.addLine(c, d); s.addLine(d, a)
        val f = SketchFeature(design.newId(), "Sketch", PlaneRef.Fixed(SketchPlane.Top), s)
        design.add(f)
        return f
    }

    private fun extrude(design: Design, sketch: SketchFeature, op: Operation): ExtrudeFeature {
        val f = ExtrudeFeature(design.newId(), "Extrude", sketch.id, listOf(RegionRef(listOf(1, 2, 3, 4), 0.0, 0.0)), 10.0, 0.0, op)
        design.add(f)
        return f
    }

    @Test
    fun editingALateFeatureOnlyRebuildsFromThere() {
        val k = FakeKernel()
        val d = Design()
        val s1 = sketchAt(d, 0.0, 40.0)
        val e1 = extrude(d, s1, Operation.NewBody)
        val fillet = FilletFeature(d.newId(), "Fillet", listOf("F${e1.id}.end|F${e1.id}.s1"), 2.0)
        d.add(fillet)
        val r = Rebuilder(k)
        val built = r.rebuild(d.active)
        assertEquals(1, built.bodies.size)
        assertTrue(built.errors.isEmpty())

        k.calls.clear()
        d.replace(fillet.copy(radius = 3.0))
        r.rebuild(d.active)
        assertEquals(listOf("fillet ${fillet.id} [F${e1.id}.end|F${e1.id}.s1]"), k.calls)
    }

    @Test
    fun changingASketchRebuildsWhatComesAfterIt() {
        val k = FakeKernel()
        val d = Design()
        val s1 = sketchAt(d, 0.0, 40.0)
        extrude(d, s1, Operation.NewBody)
        val r = Rebuilder(k)
        r.rebuild(d.active)
        k.calls.clear()
        s1.sketch.move(s1.sketch.points.toList()[2], 60.0, 0.0)
        r.rebuild(d.active)
        assertEquals(listOf("extrude 2"), k.calls)
    }

    @Test
    fun cutsAndJoinsGoToTheBodiesTheyTouch() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 100.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 5.0, 20.0), Operation.Cut)
        val built = Rebuilder(k).rebuild(d.active)
        assertEquals(listOf("Body 1", "Body 2"), built.bodies.map { it.label })
        assertEquals(1, k.calls.count { it.startsWith("combine") })
        assertEquals(5.0, k.bodies.getValue(built.bodies[0].handle).to)

        // A join that touches nothing makes a body of its own.
        extrude(d, sketchAt(d, 200.0, 10.0), Operation.Join)
        val again = Rebuilder(k).rebuild(d.active)
        assertEquals(3, again.bodies.size)
    }

    @Test
    fun aFeatureThatFailsIsSkippedWithItsReason() {
        val k = FakeKernel()
        val d = Design()
        val e = extrude(d, sketchAt(d, 0.0, 40.0), Operation.NewBody)
        val bad = FilletFeature(d.newId(), "Fillet", listOf("F${e.id}.end|F${e.id}.s1"), 9.0)
        d.add(bad)
        val missing = FilletFeature(d.newId(), "Fillet", listOf("F99.end|F99.s1"), 1.0)
        d.add(missing)
        val built = Rebuilder(k).rebuild(d.active)
        assertEquals("The fillet doesn't fit", built.errors[bad.id])
        assertEquals("Its edges aren't there any more", built.errors[missing.id])
        assertEquals(1, built.bodies.size)
    }

    @Test
    fun aLostEdgeIsFoundAgainByItsShape() {
        val k = FakeKernel()
        val d = Design()
        val e = extrude(d, sketchAt(d, 0.0, 40.0), Operation.NewBody)
        val edge = "F${e.id}.end|F${e.id}.s1"
        val fillet = FilletFeature(d.newId(), "Fillet", listOf(edge), 2.0)
        d.add(fillet)
        val first = Rebuilder(k).rebuild(d.active)
        assertTrue(first.hints.containsKey("${fillet.id}:$edge"))
        assertTrue(first.warnings.isEmpty())

        // The extrude's side is named differently now, as if its sketch line had been redrawn.
        k.side = "s9"
        val again = Rebuilder(k).rebuild(d.active, first.hints)
        assertTrue(again.errors.isEmpty(), "${again.errors}")
        assertEquals(mapOf(edge to "F${e.id}.end|F${e.id}.s9"), again.found[fillet.id])
        assertTrue(again.warnings.containsKey(fillet.id))
        assertTrue(k.calls.last().endsWith("[F${e.id}.end|F${e.id}.s9]"))

        // Without knowing where it was, it fails as before.
        assertEquals("Its edges aren't there any more", Rebuilder(k).rebuild(d.active).errors[fillet.id])
    }

    @Test
    fun aStepTurnedOffIsLeftOut() {
        val k = FakeKernel()
        val d = Design()
        val e = extrude(d, sketchAt(d, 0.0, 40.0), Operation.NewBody)
        d.suppressed += e.id
        assertEquals(0, Rebuilder(k).rebuild(d.built).bodies.size)
        assertEquals(2, d.active.size)
        d.suppressed -= e.id
        assertEquals(1, Rebuilder(k).rebuild(d.built).bodies.size)
    }

    @Test
    fun anExtrudeGoesUpToAParallelPlane() {
        val k = FakeKernel()
        val d = Design()
        val s = sketchAt(d, 0.0, 40.0)
        val up = SketchPlane.Top.copy(origin = Vec3(0.0, 0.0, 25.0))
        d.add(ExtrudeFeature(d.newId(), "Extrude", s.id, listOf(RegionRef(listOf(1, 2, 3, 4), 0.0, 0.0)), 0.0, 0.0, Operation.NewBody, upTo = PlaneRef.Fixed(up)))
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), "${built.errors}")
        assertEquals(25.0, k.lastForward)

        // A plane through the sketch has nowhere to go.
        d.replace((d.features[1] as ExtrudeFeature).copy(upTo = PlaneRef.Fixed(SketchPlane.Top)))
        assertEquals("That face or plane goes through the sketch", Rebuilder(k).rebuild(d.active).errors[d.features[1].id])
    }

    @Test
    fun theMarkerLeavesLaterFeaturesOut() {
        val k = FakeKernel()
        val d = Design()
        val s = sketchAt(d, 0.0, 40.0)
        extrude(d, s, Operation.NewBody)
        d.moveMarker(1)
        assertEquals(0, Rebuilder(k).rebuild(d.active).bodies.size)
        // A feature added now goes in at the marker, before the extrude.
        val s2 = sketchAt(d, 100.0, 5.0)
        assertEquals(1, d.indexOf(s2.id))
        assertEquals(2, d.marker)
    }

    @Test
    fun everyBodyIsLetGoInTheEnd() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 5.0, 10.0), Operation.Join)
        val r = Rebuilder(k)
        r.rebuild(d.active)
        d.replace((d.features[3] as ExtrudeFeature).copy(forward = 20.0))
        r.rebuild(d.active)
        r.clear()
        assertTrue(k.bodies.isEmpty(), "still held: ${k.bodies.keys}")
    }

    @Test
    fun edgeNamesSplitOnlyBetweenFaces() {
        val r = Rebuilder(FakeKernel())
        assertEquals(listOf("F2.r(F1.end|F1.s1)", "F1.s2"), r.facesOf("F2.r(F1.end|F1.s1)|F1.s2"))
    }

    @Test
    fun mirrorsAndPatternsJoinOrAddBodies() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        d.add(MirrorFeature(d.newId(), "Mirror", emptyList(), PlaneRef.Fixed(SketchPlane.Right), join = false))
        val r = Rebuilder(k)
        assertEquals(2, r.rebuild(d.active).bodies.size)
        d.add(PatternFeature(d.newId(), "Pattern", listOf("Body 1"), false, Axis3.X, 3, 20.0, 0.0, null, 1, 0.0, join = false))
        val built = r.rebuild(d.active)
        assertEquals(listOf("Body 1", "Body 2", "Body 3", "Body 4"), built.bodies.map { it.label })
        assertEquals(40.0, k.bodies.getValue(built.bodies.last().handle).from)
        r.clear()
        assertTrue(k.bodies.isEmpty())
    }

    @Test
    fun combineSplitAndMove() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 50.0, 10.0), Operation.NewBody)
        d.add(CombineFeature(d.newId(), "Combine", "Body 1", listOf("Body 2"), Operation.Join, keepTools = false))
        d.add(SplitFeature(d.newId(), "Split", "Body 1", PlaneRef.Fixed(SketchPlane("x=30", Vec3(30.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0)))))
        d.add(MoveFeature(d.newId(), "Move", listOf("Body 3"), 5.0, 0.0, 0.0, Axis3.Z, 0.0, copy = true))
        val r = Rebuilder(k)
        val built = r.rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertEquals(listOf("Body 1", "Body 3", "Body 4"), built.bodies.map { it.label })
        assertEquals(35.0, k.bodies.getValue(built.bodies[2].handle).from)
        r.clear()
        assertTrue(k.bodies.isEmpty())
    }

    @Test
    fun constructionPlanesCanBeBuiltOn() {
        val d = Design()
        val offset = PlaneFeature(d.newId(), "Plane 1", PlaneFeature.Kind.Offset, PlaneRef.Fixed(SketchPlane.Top), 15.0, 0.0, false, null)
        d.add(offset)
        val tilted = PlaneFeature(d.newId(), "Plane 2", PlaneFeature.Kind.Angle, PlaneRef.Fixed(SketchPlane.Top), 0.0, kotlin.math.PI / 2, false, null)
        d.add(tilted)
        val mid = PlaneFeature(d.newId(), "Plane 3", PlaneFeature.Kind.Midway, PlaneRef.Fixed(SketchPlane.Top), 0.0, 0.0, false, PlaneRef.Construction(offset.id))
        d.add(mid)
        val s = SketchFeature(d.newId(), "Sketch", PlaneRef.Construction(offset.id), Sketch())
        d.add(s)
        val built = Rebuilder(FakeKernel()).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertEquals(15.0, built.sketchPlanes.getValue(s.id).origin.z)
        assertEquals(7.5, built.sketchPlanes.getValue(mid.id).origin.z)
        // The top plane turned a quarter round its x: its normal now lies along -y.
        val n = built.sketchPlanes.getValue(tilted.id).normal
        assertTrue(kotlin.math.abs(n.y + 1) < 1e-9 && kotlin.math.abs(n.z) < 1e-9, n.toString())
    }

    @Test
    fun aPlaneCutKeepsOneSide() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 40.0), Operation.NewBody)
        val plane = PlaneRef.Fixed(SketchPlane("x=30", Vec3(30.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0)))
        d.add(SplitFeature(d.newId(), "Cut", "Body 1", plane, keep = 1))
        val r = Rebuilder(k)
        val built = r.rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        // The plane's normal is y × z = +x, so the piece kept is past x = 30.
        assertEquals(1, built.bodies.size)
        assertEquals(30.0, k.bodies.getValue(built.bodies[0].handle).from)
        r.clear()
        assertTrue(k.bodies.isEmpty())
    }

    @Test
    fun throughAllGoesPastEveryBody() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        val s2 = sketchAt(d, 2.0, 4.0)
        val cut = ExtrudeFeature(d.newId(), "Cut", s2.id, listOf(RegionRef(listOf(1, 2, 3, 4), 0.0, 0.0)), 1.0, 0.0, Operation.Cut, throughAll = true, offset = 2.0, thin = 0.5)
        d.add(cut)
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        // Bodies reach z 10; from the plane moved up 2, that's 8 to go, and a little more.
        assertEquals(9.0, k.lastForward)
        assertEquals(0.0, k.lastBack)
        assertEquals(0.5, k.lastThin)
    }

    @Test
    fun aPrimitiveIsANewBodyOrCutsWhatItTouches() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        d.add(PrimitiveFeature(d.newId(), "Box", PrimitiveKind.Box, PlaneRef.Fixed(SketchPlane.Top), 30.0, 0.0, 4.0, 4.0, 4.0, Operation.NewBody))
        d.add(PrimitiveFeature(d.newId(), "Cylinder", PrimitiveKind.Cylinder, PlaneRef.Fixed(SketchPlane.Top), 5.0, 0.0, 2.0, 20.0, 0.0, Operation.Cut))
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertEquals(2, built.bodies.size)
        assertTrue("primitive 4 1" in k.calls)
    }
}
