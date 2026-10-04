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
            val plane = resolvePlane(f.plane, bodies, f.name)
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
        is ShellFeature -> {
            val body = bodyWithFace(f.faces.firstOrNull(), bodies) ?: throw KernelException("Pick the faces to leave open")
            replace(f, bodies, planes, made, body) { kernel.shell(f.id, body.handle, f.faces, f.thickness) }
        }
        is DraftFeature -> {
            val body = bodyWithFace(f.neutral, bodies) ?: throw KernelException("The face it pivots on isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.draft(f.id, body.handle, f.faces, f.neutral, f.angle) }
        }
        is HoleFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val s = sketch.sketch
            // The sketch's lone points, not the ends of its curves.
            val at = s.points.filter { p -> p !== s.origin && s.curves.none { p in it.points() } }.map { s.x(it) to s.y(it) }
            if (at.isEmpty()) throw KernelException("Its sketch has no points for holes")
            val tool = kernel.holeTool(f.id, plane, at, f.diameter, f.depth, f.kind.ordinal, f.topDiameter, f.topDepth)
            applyTool(f, tool, Operation.Cut, bodies, planes, made)
        }
        is MirrorFeature -> {
            val plane = resolvePlane(f.plane, bodies, f.name)
            val m = Transforms.mirror(plane.origin, plane.normal)
            copies(f, bodies, planes, made, picked(f.bodies, bodies), f.join) { b -> listOf(kernel.transform(f.id, b.handle, m, "m")) }
        }
        is PatternFeature -> {
            val mats = patternMatrices(f)
            copies(f, bodies, planes, made, picked(f.bodies, bodies), f.join) { b ->
                mats.mapIndexed { i, m -> kernel.transform(f.id, b.handle, m, "p$i") }
            }
        }
        is CombineFeature -> combineBodies(f, bodies, planes, made)
        is SplitFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            val plane = resolvePlane(f.plane, bodies, f.name)
            val pieces = kernel.split(f.id, body.handle, plane.origin, plane.normal)
            val out = mutableListOf<BodyState>()
            var count = made
            for (b in bodies) {
                if (b != body) { kernel.retain(b.handle); out += b; continue }
                pieces.forEachIndexed { i, h -> out += if (i == 0) BodyState(b.label, h) else BodyState("Body ${++count}", h) }
            }
            Step(f.key(), out, planes, null, count)
        }
        is MoveFeature -> {
            val m = Transforms.then(Transforms.rotate(Transforms.unit(f.axis), f.angle), Transforms.translate(com.rm.parrotmetric.sketch.Vec3(f.dx, f.dy, f.dz)))
            val chosen = picked(f.bodies, bodies)
            if (f.copy) copies(f, bodies, planes, made, chosen, false) { b -> listOf(kernel.transform(f.id, b.handle, m, "c")) }
            else {
                val out = mutableListOf<BodyState>()
                try {
                    for (b in bodies) {
                        if (b in chosen) out += BodyState(b.label, kernel.transform(f.id, b.handle, m, "t"))
                        else { kernel.retain(b.handle); out += b }
                    }
                } catch (e: KernelException) {
                    out.forEach { kernel.release(it.handle) }
                    throw e
                }
                Step(f.key(), out, planes, null, made)
            }
        }
        else -> throw KernelException("This version can't build ${f.name}")
    }

    private fun bodyWithFace(face: String?, bodies: List<BodyState>): BodyState? =
        face?.let { n -> bodies.firstOrNull { n in kernel.faceNames(it.handle) } }

    /** The bodies named, or all of them when none are. */
    private fun picked(labels: BodyPick, bodies: List<BodyState>): List<BodyState> {
        if (labels.isEmpty()) return bodies
        val out = bodies.filter { it.label in labels }
        if (out.isEmpty()) throw KernelException("Its bodies aren't there any more")
        return out
    }

    /** One body changed by an operation, the rest kept. */
    private fun replace(f: Feature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, body: BodyState, op: () -> Long): Step {
        val h = op()
        val out = bodies.map { b -> if (b == body) BodyState(b.label, h) else b.also { kernel.retain(it.handle) } }
        return Step(f.key(), out, planes, null, made)
    }

    /** Copies of chosen bodies, joined to their body or added as new bodies. */
    private fun copies(
        f: Feature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, chosen: List<BodyState>, join: Boolean,
        make: (BodyState) -> List<Long>,
    ): Step {
        val out = mutableListOf<BodyState>()
        val added = mutableListOf<BodyState>()
        var count = made
        try {
            for (b in bodies) {
                if (b !in chosen) { kernel.retain(b.handle); out += b; continue }
                val made2 = make(b)
                if (join) {
                    var joined = b.handle
                    kernel.retain(joined)
                    for (h in made2) {
                        val next = try { kernel.combine(f.id, joined, h, Operation.Join) } finally { kernel.release(h) }
                        kernel.release(joined)
                        joined = next
                    }
                    out += BodyState(b.label, joined)
                } else {
                    kernel.retain(b.handle)
                    out += b
                    for (h in made2) added += BodyState("Body ${++count}", h)
                }
            }
        } catch (e: KernelException) {
            (out + added).forEach { kernel.release(it.handle) }
            throw e
        }
        return Step(f.key(), out + added, planes, null, count)
    }

    private fun patternMatrices(f: PatternFeature): List<DoubleArray> {
        if (f.count < 1 || f.count > 500 || f.count2 < 1 || f.count2 > 500) throw KernelException("Use between 1 and 500 copies")
        val out = mutableListOf<DoubleArray>()
        if (f.circular) {
            val full = kotlin.math.abs(kotlin.math.abs(f.angle) - 2 * kotlin.math.PI) < 1e-9
            val step = if (full) f.angle / f.count else f.angle / (f.count - 1).coerceAtLeast(1)
            for (i in 1 until f.count) out += Transforms.rotate(Transforms.unit(f.axis), step * i)
        } else {
            val d1 = Transforms.unit(f.axis)
            val d2 = f.axis2?.let { Transforms.unit(it) }
            for (i in 0 until f.count) for (j in 0 until (if (d2 != null) f.count2 else 1)) {
                if (i == 0 && j == 0) continue
                var v = d1 * (f.spacing * i)
                if (d2 != null) v += d2 * (f.spacing2 * j)
                out += Transforms.translate(v)
            }
        }
        return out
    }

    private fun combineBodies(f: CombineFeature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int): Step {
        val target = bodies.firstOrNull { it.label == f.target } ?: throw KernelException("${f.target} isn't there any more")
        val tools = bodies.filter { it.label in f.tools && it != target }
        if (tools.isEmpty()) throw KernelException("Pick the bodies to combine with")
        var result = target.handle
        kernel.retain(result)
        try {
            for (t in tools) {
                val next = kernel.combine(f.id, result, t.handle, f.operation)
                kernel.release(result)
                result = next
            }
        } catch (e: KernelException) {
            kernel.release(result)
            throw e
        }
        val out = bodies.mapNotNull { b ->
            when {
                b == target -> BodyState(b.label, result)
                b in tools && !f.keepTools -> null
                else -> b.also { kernel.retain(it.handle) }
            }
        }
        return Step(f.key(), out, planes, null, made)
    }

    private fun keep(bodies: List<BodyState>) = bodies.forEach { kernel.retain(it.handle) }

    private fun sketchOf(id: Int, all: List<Feature>): SketchFeature =
        all.firstOrNull { it.id == id } as? SketchFeature ?: throw KernelException("Its sketch has been deleted")

    private fun resolvePlane(ref: PlaneRef, bodies: List<BodyState>, name: String): SketchPlane = when (val p = ref) {
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
            SketchPlane("On a face", origin, x, n.cross(x))
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
