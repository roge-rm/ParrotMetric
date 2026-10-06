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
        // With [facingBack] set, a sketch plane there whose front looks towards -x.
        facingBack?.let { at -> return make(Box(at - forward, at + back, listOf("F$id.$side", "F$id.end"))) }
        val x = curves.minOf { minOf(it.x1, it.x2) }
        val w = curves.maxOf { maxOf(it.x1, it.x2) } - x
        return make(Box(x, x + w, listOf("F$id.$side", "F$id.end")))
    }
    var facingBack: Double? = null

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

    override fun offsetFaces(id: Int, body: Long, faces: List<String>, distance: Double): Long {
        calls += "offsetFaces $id $faces $distance"
        return make(bodies.getValue(body).let { it.copy(to = it.to + distance) })
    }

    override fun meshEdit(id: Int, body: Long, kind: Int, size: Double, steps: Int): Long {
        calls += "meshEdit $id $kind $size $steps"
        return make(bodies.getValue(body))
    }

    override fun deleteFaces(id: Int, body: Long, faces: List<String>): Long {
        calls += "deleteFaces $id $faces"
        return make(bodies.getValue(body).let { b -> b.copy(faces = b.faces - faces.toSet()) })
    }

    /** Places 10 apart along x. */
    override fun pathPlaces(path: KernelPath, count: Int, spacing: Double, turn: Boolean, reverse: Boolean) =
        (0 until count).map { i -> doubleArrayOf(1.0, 0.0, 0.0, 10.0 * i, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0) }

    override fun fillet(id: Int, body: Long, edges: List<String>, radius: Double, kind: Int, second: Double): Long {
        calls += "fillet $id $edges"
        if (radius > 5) throw KernelException("The fillet doesn't fit")
        return make(bodies.getValue(body).let { it.copy(faces = it.faces + "F$id.r") })
    }

    override fun chamfer(id: Int, body: Long, edges: List<String>, distance: Double, kind: Int, second: Double, flip: Boolean) = fillet(id, body, edges, distance, 0, 0.0)

    override fun overlaps(a: Long, b: Long): Boolean {
        val x = bodies.getValue(a); val y = bodies.getValue(b)
        return x.from <= y.to && y.from <= x.to
    }

    override fun overlapVolume(a: Long, b: Long): Double {
        val x = bodies.getValue(a); val y = bodies.getValue(b)
        return maxOf(0.0, minOf(x.to, y.to) - maxOf(x.from, y.from))
    }

    var plane = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 0.0, 1.0)
    override fun facePlane(body: Long, face: String) = plane
    override fun faceNames(body: Long) = bodies.getValue(body).faces
    override fun import(id: Int, data: ByteArray, format: Int) = make(Box(0.0, 1.0, listOf("F$id.i0")))
    override fun shell(id: Int, body: Long, open: List<String>, thickness: Double) = make(bodies.getValue(body))
    override fun draft(id: Int, body: Long, faces: List<String>, neutral: String, angle: Double) = make(bodies.getValue(body))

    /** Moves along x by the matrix's x translation, or mirrors when the first entry is negative. */
    override fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long {
        calls += "transform $id $tag"
        val b = bodies.getValue(body)
        return if (m[0] < 0) make(Box(m[3] - b.to, m[3] - b.from, b.faces)) else make(Box(b.from + m[3], b.to + m[3], b.faces))
    }

    override fun split(id: Int, body: Long, origin: Vec3, normal: Vec3): List<Long> {
        val b = bodies.getValue(body)
        if (origin.x <= b.from || origin.x >= b.to) throw KernelException("The plane doesn't cut through the body")
        return listOf(make(Box(b.from, origin.x, b.faces)), make(Box(origin.x, b.to, b.faces)))
    }

    override fun convertToSolid(id: Int, body: Long) = make(bodies.getValue(body))
    override fun sweep(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, path: KernelPath): Long {
        calls += "sweep $id ${if (path.plane != null) "sketch" else "edges ${path.edges}"}"
        return make(Box(0.0, 5.0, listOf("F$id.s1", "F$id.end")))
    }
    override fun pipe(id: Int, path: KernelPath, diameter: Double, inner: Double): Long {
        calls += "pipe $id"
        return make(Box(100.0, 101.0, listOf("F$id.p0")))
    }
    override fun coil(id: Int, plane: SketchPlane, u: Double, v: Double, diameter: Double, pitch: Double, turns: Double, section: Double, square: Boolean): Long {
        calls += "coil $id"
        return make(Box(u - diameter / 2, u + diameter / 2, listOf("F$id.c0")))
    }
    override fun snapFitTool(id: Int, plane: SketchPlane, at: List<Pair<Double, Double>>, middle: Vec3, sizes: SnapFitSizes, catchPart: Boolean, tag: String): Long {
        calls += "snapFitTool $id ${at.size} $catchPart $tag"
        return make(Box(10.0, 10.0 + sizes.length, listOf("F$id.${tag}0")))
    }

    override fun lipTool(id: Int, body: Long, face: String, inside: Double, outside: Double, height: Double, tag: String): Long {
        calls += "lipTool $id $face $inside $outside $height $tag"
        val b = bodies.getValue(body)
        return make(Box(b.to, b.to + height, listOf("F$id.${tag}0")))
    }

    override fun thread(id: Int, body: Long, face: String, pitch: Double, clearance: Double): Long {
        calls += "thread $id $face $pitch" + (if (clearance > 0) " $clearance" else "")
        return make(bodies.getValue(body).let { it.copy(faces = it.faces + "F$id.t0") })
    }
    override fun loft(id: Int, sections: List<Triple<SketchPlane, List<ProfileCurve>, RegionRef>>, ruled: Boolean): Long {
        calls += "loft $id ${sections.size}"
        return make(Box(200.0, 210.0, listOf("F$id.start", "F$id.end")))
    }
    override fun splitBy(id: Int, body: Long, tool: Long): List<Long> {
        calls += "splitBy $id"
        val b = bodies.getValue(body); val t = bodies.getValue(tool)
        if (t.to <= b.from || t.from >= b.to) throw KernelException("The bodies don't cross")
        return listOf(make(Box(b.from, t.from, b.faces)), make(Box(t.from, minOf(b.to, t.to), b.faces)))
    }
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
    fun aCutSetToChangeOneBodyLeavesTheOthers() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 10.0, 10.0), Operation.NewBody)
        // Reaches into both, but only the second is to change.
        val s = sketchAt(d, 5.0, 10.0)
        d.add(ExtrudeFeature(d.newId(), "Extrude", s.id, listOf(RegionRef(listOf(1, 2, 3, 4), 0.0, 0.0)), 10.0, 0.0, Operation.Cut, only = listOf("Body 2")))
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty())
        assertEquals(1, k.calls.count { it.startsWith("combine") })
        assertEquals(10.0, k.bodies.getValue(built.bodies[0].handle).to)

        // Set to change a body it doesn't reach, it says so.
        val t = sketchAt(d, 50.0, 10.0)
        d.add(ExtrudeFeature(d.newId(), "Extrude", t.id, listOf(RegionRef(listOf(1, 2, 3, 4), 0.0, 0.0)), 10.0, 0.0, Operation.Cut, only = listOf("Body 1")))
        assertEquals("It doesn't reach the bodies it's set to change", Rebuilder(k).rebuild(d.active).errors.values.single())
    }

    @Test
    fun aLipGoesOnTheRimAndItsGrooveInTheLid() {
        val k = FakeKernel()
        // The rim 7 below the lid's top: room for a 2.2 deep groove.
        k.plane = doubleArrayOf(0.0, 0.0, 7.0, 0.0, 0.0, 1.0)
        val d = Design()
        val base = extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 10.0, 2.0), Operation.NewBody)
        d.add(LipFeature(d.newId(), "Lip", "F${base.id}.end", 1.0, 2.0, 0.2, "Body 2"))
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertTrue("lipTool 5 F2.end 0.0 1.0 2.0 l" in k.calls, k.calls.toString())
        assertTrue(k.calls.any { it.startsWith("lipTool 5 F2.end -0.2 1.2") && it.endsWith(" g") }, k.calls.toString())
        assertEquals(12.0, k.bodies.getValue(built.bodies[0].handle).to)
        assertEquals(10.0, k.bodies.getValue(built.bodies[1].handle).to)

        // The groove can't go in the body the lip is on.
        d.replace(LipFeature(5, "Lip", "F${base.id}.end", 1.0, 2.0, 0.2, "Body 1"))
        assertEquals("The groove goes in another body", Rebuilder(k).rebuild(d.active).errors.values.single())
        // Too deep for the lid.
        d.replace(LipFeature(5, "Lip", "F${base.id}.end", 1.0, 7.0, 0.2, "Body 2"))
        assertTrue(Rebuilder(k).rebuild(d.active).errors.values.single().startsWith("The groove would go right through"))
    }

    @Test
    fun snapFitClipsJoinTheirFaceAndCatchesCutTheOtherBody() {
        val k = FakeKernel()
        val d = Design()
        val base = extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 10.0, 10.0), Operation.NewBody)
        val pts = Sketch().also { it.addPoint(1.0, 1.0); it.addPoint(2.0, 1.0) }
        val sk = SketchFeature(d.newId(), "Sketch", PlaneRef.OnFace("F${base.id}.end", Vec3(1.0, 0.0, 0.0)), pts).also { d.add(it) }
        d.add(SnapFitFeature(d.newId(), "Snap fit", sk.id, SnapFitSizes(), "Body 2"))
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertTrue(k.calls.any { it.startsWith("snapFitTool") && it.endsWith(" 2 false c") }, k.calls.toString())
        assertTrue(k.calls.any { it.startsWith("snapFitTool") && it.endsWith(" 2 true k") }, k.calls.toString())
        assertEquals(2, k.calls.count { it.startsWith("combine") })
    }

    @Test
    fun aJoinThatOnlyTouchesAFaceStillJoins() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        // Starts where the first ends, like a post on a floor.
        extrude(d, sketchAt(d, 10.0, 10.0), Operation.Join)
        assertEquals(1, Rebuilder(k).rebuild(d.active).bodies.size)
        // A cut that only touches takes nothing away, and doesn't touch the body.
        extrude(d, sketchAt(d, 20.0, 5.0), Operation.Cut)
        assertEquals(1, k.calls.count { it.startsWith("combine") })
    }

    @Test
    fun aJoinThatTouchesTwoBodiesTakesTheOneItsSketchIsOn() {
        val k = FakeKernel()
        val d = Design()
        val floor = extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 20.0, 10.0), Operation.NewBody)
        // Between the two, touching both, sketched on the first one's end.
        val onFloor = SketchFeature(d.newId(), "Sketch", PlaneRef.OnFace("F${floor.id}.end", Vec3(1.0, 0.0, 0.0)), sketchAt(Design(), 10.0, 10.0).sketch)
        d.add(onFloor)
        d.add(ExtrudeFeature(d.newId(), "Extrude", onFloor.id, listOf(RegionRef(onFloor.sketch.curves.map { it.id }, 15.0, 1.0)), 10.0, 0.0, Operation.Join))
        val built = Rebuilder(k).rebuild(d.active)
        assertEquals(2, built.bodies.size)
        assertEquals(20.0, k.bodies.getValue(built.bodies[0].handle).to)
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
    fun aStaggeredGridShiftsEveryOtherRow() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        d.add(PatternFeature(d.newId(), "Pattern", listOf("Body 1"), false, Axis3.X, 2, 20.0, 0.0, Axis3.Y, 3, 15.0, join = false, stagger = true))
        val built = Rebuilder(k).rebuild(d.active)
        // Rows of two: along x at 0 and 20, the second row half a step on, the third back in line.
        val starts = built.bodies.map { k.bodies.getValue(it.handle).from }
        assertEquals(listOf(0.0, 0.0, 10.0, 20.0, 20.0, 30.0), starts.sorted())
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

    @Test
    fun splittingByABodyKeepsTheToolAndAddsAPiece() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 5.0, 10.0), Operation.NewBody)
        d.add(SplitFeature(d.newId(), "Split", "Body 1", PlaneRef.Fixed(SketchPlane.Right), 0, tool = "Body 2"))
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertEquals(listOf("Body 1", "Body 3", "Body 2"), built.bodies.map { it.label })
    }

    @Test
    fun aligningMovesTheBodyWithTheFace() {
        val k = FakeKernel()
        val d = Design()
        val e = extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        val align = AlignFeature(d.newId(), "Align", emptyList(), "F${e.id}.end", PlaneRef.Fixed(SketchPlane.Top), gap = 2.0)
        d.add(align)
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertTrue("transform ${align.id} a" in k.calls)
    }

    @Test
    fun constructionThroughPointsAndPlanes() {
        val k = FakeKernel()
        val d = Design()
        val a = PointFeature(d.newId(), "A", 0.0, 0.0, 5.0).also { d.add(it) }
        val b = PointFeature(d.newId(), "B", 10.0, 0.0, 5.0).also { d.add(it) }
        val c = PointFeature(d.newId(), "C", 0.0, 10.0, 5.0).also { d.add(it) }
        val refs = listOf(a, b, c).map { PointRef.Construction(it.id) }
        val flat = PlaneFeature(d.newId(), "Flat", PlaneFeature.Kind.ThreePoints, PlaneRef.Fixed(SketchPlane.Top), 0.0, 0.0, false, null, refs).also { d.add(it) }
        val axis = AxisFeature(d.newId(), "AB", 0.0, 0.0, 0.0, Axis3.Z, AxisFeature.Kind.TwoPoints, points = refs.take(2)).also { d.add(it) }
        val up = PlaneFeature(d.newId(), "Up", PlaneFeature.Kind.Offset, PlaneRef.Fixed(SketchPlane.Front), 3.0, 0.0, false, null).also { d.add(it) }
        val across = PlaneFeature(d.newId(), "Across", PlaneFeature.Kind.Offset, PlaneRef.Fixed(SketchPlane.Right), 2.0, 0.0, false, null).also { d.add(it) }
        val planes = listOf(PlaneRef.Construction(flat.id), PlaneRef.Construction(up.id), PlaneRef.Construction(across.id))
        val corner = PointFeature(d.newId(), "Meet", 0.0, 0.0, 0.0, PointFeature.Kind.ThreePlanes, planes = planes).also { d.add(it) }
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        val plane = built.sketchPlanes.getValue(flat.id)
        assertEquals(1.0, kotlin.math.abs(plane.normal.z), 1e-9)
        assertEquals(5.0, plane.origin.z, 1e-9)
        val (from, dir) = built.axes.getValue(axis.id)
        assertEquals(0.0, from.x, 1e-9)
        assertEquals(1.0, dir.x, 1e-9)
        // Where the three meet is on each of them.
        val at = built.points.getValue(corner.id)
        for (id in listOf(flat.id, up.id, across.id)) {
            val p = built.sketchPlanes.getValue(id)
            assertEquals(0.0, (at - p.origin).dot(p.normal), 1e-9)
        }
    }

    @Test
    fun threePointsInALineMakeNoPlane() {
        val k = FakeKernel()
        val d = Design()
        val refs = listOf(0.0, 1.0, 2.0).map { x -> PointRef.Construction(PointFeature(d.newId(), "P", x, 0.0, 0.0).also { d.add(it) }.id) }
        val flat = PlaneFeature(d.newId(), "Flat", PlaneFeature.Kind.ThreePoints, PlaneRef.Fixed(SketchPlane.Top), 0.0, 0.0, false, null, refs).also { d.add(it) }
        val built = Rebuilder(k).rebuild(d.active)
        assertEquals("The points are in a line", built.errors[flat.id])
    }

    @Test
    fun sweepsPipesCoilsLoftsAndThreadsBuild() {
        val k = FakeKernel()
        val d = Design()
        val profile = sketchAt(d, 0.0, 10.0)
        val path = sketchAt(d, 0.0, 30.0)
        val box = extrude(d, profile, Operation.NewBody)
        val region = RegionRef(listOf(1, 2, 3, 4), 0.0, 0.0)
        d.add(SweepFeature(d.newId(), "Sweep", profile.id, listOf(region), PathRef.Sketch(path.id), Operation.NewBody))
        d.add(PipeFeature(d.newId(), "Pipe", PathRef.Edges(listOf("F${box.id}.s1|F${box.id}.end")), 4.0, 2.0, Operation.NewBody))
        d.add(CoilFeature(d.newId(), "Coil", PlaneRef.Fixed(SketchPlane.Top), 50.0, 0.0, 10.0, 3.0, 5.0, 1.0, false, Operation.NewBody))
        d.add(LoftFeature(d.newId(), "Loft", listOf(LoftSection(profile.id, region), LoftSection(path.id, region)), false, Operation.NewBody))
        val thread = ThreadFeature(d.newId(), "Thread", "F${box.id}.end", 1.5).also { d.add(it) }
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertEquals(5, built.bodies.size)
        assertTrue(k.calls.any { it.startsWith("sweep") && it.endsWith("sketch") })
        assertTrue("pipe ${d.features[4].id}" in k.calls)
        assertTrue("thread ${thread.id} F${box.id}.end 1.5" in k.calls)
        assertTrue(k.calls.any { it.startsWith("loft") && it.endsWith(" 2") })
    }

    @Test
    fun aCutGoesIntoTheBodyItIsSetToChangeEvenWhenAnotherIsOnTheOtherSide() {
        val k = FakeKernel()
        val d = Design()
        // Body 1 from 0 to 10, Body 2 from 10 to 20; a cut sketched at 10 is set to change Body 2.
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 10.0, 10.0), Operation.NewBody)
        val s = sketchAt(d, 10.0, 0.5)
        val r = Rebuilder(k)
        r.rebuild(d.active)
        // Only the cut is built from here on, its sketch facing Body 1.
        k.facingBack = 10.0
        d.add(ExtrudeFeature(d.newId(), "Extrude", s.id, listOf(RegionRef(listOf(1, 2, 3, 4), 0.0, 0.0)), 3.0, 0.0, Operation.Cut, only = listOf("Body 2")))
        val built = r.rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        // Cut from Body 2 (the fake cut keeps what lies before the tool).
        assertEquals(10.0, k.bodies.getValue(built.bodies[1].handle).to)
    }

    @Test
    fun aThreadWithClearanceEasesItsFaceFirst() {
        val k = FakeKernel()
        val d = Design()
        val box = extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        val thread = ThreadFeature(d.newId(), "Thread", "F${box.id}.end", 1.5, 0.3).also { d.add(it) }
        assertTrue(Rebuilder(k).rebuild(d.active).errors.isEmpty())
        assertTrue("thread ${thread.id} F${box.id}.end 1.5 0.3" in k.calls, k.calls.toString())
    }

    @Test
    fun pressPullDeleteFaceAndFeaturePatterns() {
        val k = FakeKernel()
        val d = Design()
        val base = extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        val boss = extrude(d, sketchAt(d, 5.0, 3.0), Operation.Join)
        val pattern = PatternFeature(d.newId(), "Pattern", emptyList(), false, Axis3.X, 3, 0.0, 0.0, null, 1, 0.0, join = false,
            path = PathRef.Sketch(boss.sketchId), features = listOf(boss.id))
        d.add(pattern)
        val pull = OffsetFaceFeature(d.newId(), "Press pull", listOf("F${base.id}.end"), 2.0)
        d.add(pull)
        d.add(DeleteFaceFeature(d.newId(), "Delete face", listOf("F${boss.id}.s1")))
        val smooth = MeshEditFeature(d.newId(), "Smooth", "Body 2", MeshEdit.Smooth, 30.0, 3)
        d.add(smooth)
        d.add(MirrorFeature(d.newId(), "Mirror", emptyList(), PlaneRef.Fixed(SketchPlane.Right), false, features = listOf(boss.id)))
        val r = Rebuilder(k)
        val built = r.rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        // The boss again at 15 and 25 along the path, apart from the body so new bodies, then mirrored to -8.
        assertEquals(listOf("Body 1", "Body 2", "Body 3", "Body 4"), built.bodies.map { it.label })
        assertEquals(listOf(15.0, 25.0, -8.0), built.bodies.drop(1).map { k.bodies.getValue(it.handle).from })
        assertTrue("offsetFaces ${pull.id} [F${base.id}.end] 2.0" in k.calls)
        assertTrue(k.calls.any { it.startsWith("deleteFaces") })
        assertTrue("meshEdit ${smooth.id} 2 30.0 3" in k.calls)
        assertTrue("transform ${pattern.id} f${boss.id}.0" in k.calls)
        r.clear()
        assertTrue(k.bodies.isEmpty())
    }

    @Test
    fun aPatternRepeatsWhatAMirrorOfFeaturesPlaced() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        val boss = extrude(d, sketchAt(d, 5.0, 3.0), Operation.Join)
        val mirror = MirrorFeature(d.newId(), "Mirror", emptyList(), PlaneRef.Fixed(SketchPlane.Right), false, features = listOf(boss.id)).also { d.add(it) }
        val pattern = PatternFeature(d.newId(), "Pattern", emptyList(), false, Axis3.X, 2, 30.0, 0.0, null, 1, 0.0, join = false, features = listOf(mirror.id))
        d.add(pattern)
        val built = Rebuilder(k).rebuild(d.active)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        // The mirrored boss at -8, and again 30 along.
        assertEquals(listOf(-8.0, 22.0), built.bodies.drop(1).map { k.bodies.getValue(it.handle).from })
        assertTrue("transform ${pattern.id} f${boss.id}.0" in k.calls, k.calls.toString())
    }

    @Test
    fun jointsMoveComponentsAndRigidOnesGoTogether() {
        val k = FakeKernel()
        val d = Design()
        extrude(d, sketchAt(d, 0.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 20.0, 10.0), Operation.NewBody)
        extrude(d, sketchAt(d, 40.0, 10.0), Operation.NewBody)
        d.add(JointFeature(d.newId(), "Held", JointKind.Rigid, "Knob", "Lid"))
        val slide = JointFeature(d.newId(), "Slide", JointKind.Slide, "Lid", "Base", axis = Axis3.X, value = 5.0)
        d.add(slide)
        val r = Rebuilder(k)
        val comps = mapOf("Body 1" to "Base", "Body 2" to "Lid", "Body 3" to "Knob")
        val built = r.rebuild(d.active, components = comps)
        assertTrue(built.errors.isEmpty(), built.errors.toString())
        assertEquals(listOf(0.0, 25.0, 45.0), built.bodies.map { k.bodies.getValue(it.handle).from })
        // Taking the knob out of its component leaves it behind.
        val again = r.rebuild(d.active, components = comps - "Body 3")
        assertEquals(40.0, k.bodies.getValue(again.bodies[2].handle).from)
        r.clear()
        assertTrue(k.bodies.isEmpty())
    }
}
