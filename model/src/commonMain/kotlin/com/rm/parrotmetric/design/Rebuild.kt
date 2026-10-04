package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** A body after some feature: its label and the kernel's handle. */
data class BodyState(val label: String, val handle: Long)

/** What the history builds into. */
class Built(
    val bodies: List<BodyState>,
    /** Where each sketch ended up, by feature id. */
    val sketchPlanes: Map<Int, SketchPlane>,
    /** Why a feature couldn't be built, by feature id. */
    val errors: Map<Int, String>,
)

/**
 * Builds the design's active features with the kernel, keeping the bodies
 * after each one. A rebuild starts from the first feature whose inputs
 * changed, so editing a late feature doesn't redo the early ones.
 *
 * A feature that fails is skipped: the bodies carry on as they were before
 * it, and its reason is in [Built.errors].
 */
class Rebuilder(private val kernel: Kernel) {
    private class Step(
        val key: Any,
        val bodies: List<BodyState>,
        val planes: Map<Int, SketchPlane>,
        val error: String?,
        val bodyCount: Int,
    )

    private val steps = mutableListOf<Step>()

    fun rebuild(features: List<Feature>): Built {
        var from = 0
        while (from < steps.size && from < features.size && steps[from].key == features[from].key()) from++
        discardFrom(from)

        for (i in from until features.size) {
            val f = features[i]
            val before = steps.lastOrNull()
            val bodies = before?.bodies ?: emptyList()
            val planes = before?.planes ?: emptyMap()
            val made = before?.bodyCount ?: 0
            val step = try {
                build(f, bodies, planes, made, features)
            } catch (e: KernelException) {
                bodies.forEach { kernel.retain(it.handle) }
                Step(f.key(), bodies, planes, e.message ?: "That couldn't be built", made)
            }
            steps += step
        }
        val last = steps.lastOrNull()
        return Built(
            last?.bodies ?: emptyList(),
            last?.planes ?: emptyMap(),
            steps.withIndex().mapNotNull { (i, s) -> s.error?.let { features[i].id to it } }.toMap(),
        )
    }

    /** Lets go of every body held. */
    fun clear() = discardFrom(0)

    private fun discardFrom(i: Int) {
        while (steps.size > i) {
            val s = steps.removeAt(steps.size - 1)
            s.bodies.forEach { kernel.release(it.handle) }
        }
    }

    private fun build(f: Feature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, all: List<Feature>): Step = when (f) {
        is SketchFeature -> {
            val plane = resolvePlane(f, bodies)
            keep(bodies)
            Step(f.key(), bodies, planes + (f.id to plane), null, made)
        }
        is ExtrudeFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val tool = kernel.extrude(f.id, plane, sketch.curves(), f.regions, f.forward, f.back)
            applyTool(f, tool, f.operation, bodies, planes, made)
        }
        is RevolveFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val (ax, ay, dx, dy) = axisOf(f.axis, sketch)
            val tool = kernel.revolve(f.id, plane, sketch.curves(), f.regions, ax, ay, dx, dy, f.angle)
            applyTool(f, tool, f.operation, bodies, planes, made)
        }
        is FilletFeature -> edgeFeature(f, f.edges, bodies, planes, made) { body, edges -> kernel.fillet(f.id, body, edges, f.radius) }
        is ChamferFeature -> edgeFeature(f, f.edges, bodies, planes, made) { body, edges -> kernel.chamfer(f.id, body, edges, f.distance) }
        is ImportFeature -> {
            val h = kernel.import(f.id, f.data, f.format)
            keep(bodies)
            Step(f.key(), bodies + BodyState("Body ${made + 1}", h), planes, null, made + 1)
        }
    }

    private fun keep(bodies: List<BodyState>) = bodies.forEach { kernel.retain(it.handle) }

    private fun sketchOf(id: Int, all: List<Feature>): SketchFeature =
        all.firstOrNull { it.id == id } as? SketchFeature ?: throw KernelException("Its sketch has been deleted")

    private fun resolvePlane(f: SketchFeature, bodies: List<BodyState>): SketchPlane = when (val p = f.plane) {
        is PlaneRef.Fixed -> p.plane
        is PlaneRef.OnFace -> {
            val body = bodies.firstOrNull { p.face in kernel.faceNames(it.handle) }
                ?: throw KernelException("The face it's on isn't there any more")
            val d = kernel.facePlane(body.handle, p.face) ?: throw KernelException("The face it's on isn't there any more")
            val origin = Vec3(d[0], d[1], d[2])
            val n = Vec3(d[3], d[4], d[5])
            // x as it was, flattened onto the face; any direction across the face if that's gone.
            var x = p.x - n * p.x.dot(n)
            if (x.dot(x) < 1e-12) x = if (abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0).cross(n) else Vec3(1.0, 0.0, 0.0).cross(n)
            x *= 1 / sqrt(x.dot(x))
            SketchPlane(f.name, origin, x, n.cross(x))
        }
    }

    private fun axisOf(a: AxisRef, s: SketchFeature): List<Double> = when (a) {
        AxisRef.SketchX -> listOf(0.0, 0.0, 1.0, 0.0)
        AxisRef.SketchY -> listOf(0.0, 0.0, 0.0, 1.0)
        is AxisRef.SketchLine -> {
            val l = s.sketch.curve(a.curveId) as? Line ?: throw KernelException("The line it turns round has been deleted")
            val sk = s.sketch
            listOf(sk.x(l.a), sk.y(l.a), sk.x(l.b) - sk.x(l.a), sk.y(l.b) - sk.y(l.a))
        }
    }

    /**
     * A new solid from an extrude or revolve, used as asked: a new body, or
     * joined to, cut from or intersected with the bodies it overlaps.
     */
    private fun applyTool(f: Feature, tool: Long, op: Operation, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int): Step {
        try {
            if (op == Operation.NewBody) {
                keep(bodies)
                return Step(f.key(), bodies + BodyState("Body ${made + 1}", tool), planes, null, made + 1)
            }
            val touched = bodies.filter { kernel.overlaps(it.handle, tool) }
            if (touched.isEmpty()) {
                if (op == Operation.Join) {
                    keep(bodies)
                    return Step(f.key(), bodies + BodyState("Body ${made + 1}", tool), planes, null, made + 1)
                }
                kernel.release(tool)
                throw KernelException(if (op == Operation.Cut) "It doesn't reach any body to cut" else "It doesn't overlap any body")
            }
            val out = mutableListOf<BodyState>()
            try {
            if (op == Operation.Join) {
                // Everything it touches becomes one body, in the place of the first.
                var joined = kernel.combine(f.id, touched[0].handle, tool, Operation.Join)
                for (b in touched.drop(1)) {
                    val next = kernel.combine(f.id, joined, b.handle, Operation.Join)
                    kernel.release(joined)
                    joined = next
                }
                for (b in bodies) when {
                    b == touched[0] -> out += BodyState(b.label, joined)
                    b in touched -> {}
                    else -> { kernel.retain(b.handle); out += b }
                }
            } else {
                for (b in bodies) {
                    if (b in touched) out += BodyState(b.label, kernel.combine(f.id, b.handle, tool, op))
                    else { kernel.retain(b.handle); out += b }
                }
            }
            } catch (e: KernelException) {
                out.forEach { kernel.release(it.handle) }
                throw e
            }
            kernel.release(tool)
            return Step(f.key(), out, planes, null, made)
        } catch (e: KernelException) {
            kernel.release(tool)
            throw e
        }
    }

    /** The two face names in an edge name. Face names can hold "|" in brackets, as a fillet's does. */
    internal fun facesOf(edge: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((i, c) in edge.withIndex()) when (c) {
            '(' -> depth++
            ')' -> depth--
            '|' -> if (depth == 0) { out += edge.substring(start, i); start = i + 1 }
        }
        out += edge.substring(start)
        return out
    }

    /** Fillet or chamfer: each body with some of the edges gets the operation on its own edges. */
    private fun edgeFeature(
        f: Feature, edges: List<String>, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int,
        op: (Long, List<String>) -> Long,
    ): Step {
        val out = mutableListOf<BodyState>()
        var found = 0
        try {
            for (b in bodies) {
                val faces = kernel.faceNames(b.handle).toSet()
                val mine = edges.filter { e -> facesOf(e).all { it in faces } }
                if (mine.isEmpty()) {
                    kernel.retain(b.handle)
                    out += b
                } else {
                    found += mine.size
                    out += BodyState(b.label, op(b.handle, mine))
                }
            }
        } catch (e: KernelException) {
            out.forEach { kernel.release(it.handle) }
            throw e
        }
        if (found == 0) {
            out.forEach { kernel.release(it.handle) }
            throw KernelException("Its edges aren't there any more")
        }
        return Step(f.key(), out, planes, null, made)
    }
}
