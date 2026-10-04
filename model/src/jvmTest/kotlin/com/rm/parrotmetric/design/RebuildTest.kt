package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
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
    private var next = 1L

    private fun make(b: Box): Long {
        val h = next++
        bodies[h] = b
        counts[h] = 1
        return h
    }

    override fun extrude(id: Int, plane: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>, forward: Double, back: Double): Long {
        calls += "extrude $id"
        val x = curves.minOf { minOf(it.x1, it.x2) }
        val w = curves.maxOf { maxOf(it.x1, it.x2) } - x
        return make(Box(x, x + w, listOf("F$id.s1", "F$id.end")))
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

    override fun chamfer(id: Int, body: Long, edges: List<String>, distance: Double) = fillet(id, body, edges, distance)

    override fun overlaps(a: Long, b: Long): Boolean {
        val x = bodies.getValue(a); val y = bodies.getValue(b)
        return x.from < y.to && y.from < x.to
    }

    override fun facePlane(body: Long, face: String) = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 0.0, 1.0)
    override fun faceNames(body: Long) = bodies.getValue(body).faces
    override fun import(id: Int, data: ByteArray, format: Int) = make(Box(0.0, 1.0, listOf("F$id.i0")))
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
}
