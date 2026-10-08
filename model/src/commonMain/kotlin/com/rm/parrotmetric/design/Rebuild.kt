package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** A body after some feature: its label and the kernel's handle. */
data class BodyState(val label: String, val handle: Long)

/** What the history builds into. */
class Built(
    val bodies: List<BodyState>,
    /** Construction axes by feature id: a point on each and its direction. */
    val axes: Map<Int, Pair<Vec3, Vec3>> = emptyMap(),
    /** Construction points by feature id. */
    val points: Map<Int, Vec3> = emptyMap(),
    /** Canvases where they're shown, by feature id. */
    val canvases: Map<Int, PlacedCanvas> = emptyMap(),
    /** Where each sketch ended up, by feature id. */
    val sketchPlanes: Map<Int, SketchPlane>,
    /** Why a feature couldn't be built, by feature id. */
    val errors: Map<Int, String>,
    /** Features that built, but only after finding faces or edges again by shape: by id, what to check. */
    val warnings: Map<Int, String> = emptyMap(),
    /** For those, each lost face or edge name and the one found in its place. */
    val found: Map<Int, Map<String, String>> = emptyMap(),
    /** Where each face or edge a feature uses was when last found by name, by "id:name" (see Kernel.signature). */
    val hints: Map<String, DoubleArray> = emptyMap(),
    /** Threads drawn as a symbol rather than cut. */
    val threads: List<ThreadMark> = emptyList(),
    /** Bodies from inserted designs, by label, and the component each goes in. */
    val linked: Map<String, String> = emptyMap(),
    /** Screws, nuts and washers made as bodies, by label, and what each is, as "Socket cap M3 × 10". */
    val hardware: Map<String, String> = emptyMap(),
)

/**
 * Builds the design's active features with the kernel, keeping the bodies
 * after each one. A rebuild starts from the first feature whose inputs
 * changed, so editing a late feature doesn't redo the early ones.
 *
 * A feature that fails is skipped: the bodies carry on as they were before
 * it, and its reason is in [Built.errors].
 *
 * Faces and edges are referred to by name. If an earlier change takes a name
 * away, the one in the same place and of the same size is used instead, if
 * there is one, and the feature gets a warning (see Kernel.relocate).
 */
class Rebuilder(private val kernel: Kernel) {
    private class Step(
        val key: Any,
        val bodies: List<BodyState>,
        val planes: Map<Int, SketchPlane>,
        val error: String?,
        val bodyCount: Int,
        val axes: Map<Int, Pair<Vec3, Vec3>> = emptyMap(),
        val points: Map<Int, Vec3> = emptyMap(),
        val canvases: Map<Int, PlacedCanvas> = emptyMap(),
    ) {
        /** Names found again by shape while building it, old to new. */
        var found: Map<String, String> = emptyMap()
        /** The shape it added or took away, held for patterns and mirrors of it, and how it was used. */
        var tool = 0L
        var toolOp = Operation.NewBody
        /** For a mirror or pattern of features: each feature whose shape it placed, and where. */
        var repeats: List<Pair<Int, DoubleArray>> = emptyList()
        /** Threads it drew as a symbol. */
        var threads: List<ThreadMark> = emptyList()
        /** Bodies it brought in from another design, by label, and their component. */
        var linked: Map<String, String> = emptyMap()
        /** Fasteners it made as bodies, by label, and what each is. */
        var hardware: Map<String, String> = emptyMap()
    }

    private val steps = mutableListOf<Step>()
    /** Each body's component, by label, for joints. */
    private var components: Map<String, String> = emptyMap()
    private var hints = mutableMapOf<String, DoubleArray>()
    private val found = mutableMapOf<String, String>()

    /** Builds the features. [hints] are those from the last build (Built.hints), to find lost faces and edges again. */
    fun rebuild(features: List<Feature>, hints: Map<String, DoubleArray> = emptyMap(), components: Map<String, String> = emptyMap()): Built {
        this.hints = hints.toMutableMap()
        var from = 0
        while (from < steps.size && from < features.size && steps[from].key == features[from].key()) from++
        // Joints move whole components: what's in them changing redoes them.
        if (components != this.components) {
            val firstJoint = features.indexOfFirst { it is JointFeature }
            if (firstJoint >= 0) from = minOf(from, firstJoint)
            this.components = components
        }
        discardFrom(from)

        for (i in from until features.size) {
            val f = features[i]
            val before = steps.lastOrNull()
            val bodies = before?.bodies ?: emptyList()
            val planes = before?.planes ?: emptyMap()
            val made = before?.bodyCount ?: 0
            found.clear()
            val step = try {
                build(f, bodies, planes, made, features)
            } catch (e: KernelException) {
                bodies.forEach { kernel.retain(it.handle) }
                Step(f.key(), bodies, planes, e.message ?: "That couldn't be built", made)
            }
            step.found = found.toMap()
            steps += step
        }
        val last = steps.lastOrNull()
        return Built(
            last?.bodies ?: emptyList(),
            steps.fold(emptyMap()) { m, s -> m + s.axes },
            steps.fold(emptyMap()) { m, s -> m + s.points },
            steps.fold(emptyMap()) { m, s -> m + s.canvases },
            last?.planes ?: emptyMap(),
            steps.withIndex().mapNotNull { (i, s) -> s.error?.let { features[i].id to it } }.toMap(),
            steps.withIndex().mapNotNull { (i, s) ->
                if (s.error == null && s.found.isNotEmpty()) features[i].id to "Something it uses changed and was found again by its shape. Check it's still the right one." else null
            }.toMap(),
            steps.withIndex().mapNotNull { (i, s) -> if (s.found.isNotEmpty()) features[i].id to s.found else null }.toMap(),
            this.hints.toMap(),
            steps.filter { it.error == null }.flatMap { it.threads },
            steps.fold(emptyMap()) { m, s -> m + s.linked },
            steps.filter { it.error == null }.fold(emptyMap()) { m, s -> m + s.hardware },
        )
    }

    /**
     * A face or edge a feature uses, by name. Its signature is kept while the
     * name is there; once it's gone, the one most like that signature is used.
     */
    private fun ref(f: Feature, name: String, edge: Boolean, bodies: List<BodyState>): String {
        val key = "${f.id}:$name"
        for (b in bodies) {
            val sig = kernel.signature(b.handle, name, edge) ?: continue
            hints[key] = sig
            return name
        }
        val sig = hints[key] ?: return name
        for (b in bodies) {
            val again = kernel.relocate(b.handle, sig) ?: continue
            found[name] = again
            return again
        }
        return name
    }

    /** Lets go of every body held. */
    fun clear() = discardFrom(0)

    private fun discardFrom(i: Int) {
        while (steps.size > i) {
            val s = steps.removeAt(steps.size - 1)
            s.bodies.forEach { kernel.release(it.handle) }
            if (s.tool != 0L) kernel.release(s.tool)
        }
    }

    private fun build(f: Feature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, all: List<Feature>): Step = when (f) {
        is SketchFeature -> {
            val plane = resolvePlane(f.plane, bodies, f, planes)
            val followed = follow(f, plane, bodies)
            keep(bodies)
            Step(f.key(), bodies, planes + (f.id to plane), if (followed) null else "What it projected changed shape. Open it and project again.", made)
        }
        is PlaneFeature -> {
            val base = resolvePlane(f.base, bodies, f, planes)
            val plane = when (f.kind) {
                PlaneFeature.Kind.Offset -> base.copy(name = f.name, origin = base.origin + base.normal * f.offset)
                PlaneFeature.Kind.Angle -> {
                    // Turning round the base's own x (or y) through its origin.
                    val axis = if (f.turnRoundY) base.y else base.x
                    val r = Transforms.rotate(axis, f.angle)
                    fun turn(v: Vec3) = Vec3(r[0] * v.x + r[1] * v.y + r[2] * v.z, r[4] * v.x + r[5] * v.y + r[6] * v.z, r[8] * v.x + r[9] * v.y + r[10] * v.z)
                    SketchPlane(f.name, base.origin, turn(base.x), turn(base.y))
                }
                PlaneFeature.Kind.Midway -> {
                    val other = resolvePlane(f.other ?: throw KernelException("Pick the second plane or face"), bodies, f, planes)
                    if (kotlin.math.abs(kotlin.math.abs(base.normal.dot(other.normal)) - 1) > 1e-6) throw KernelException("The two aren't parallel")
                    // Halfway along the base's normal to the other plane.
                    val gap = (other.origin - base.origin).dot(base.normal)
                    base.copy(name = f.name, origin = base.origin + base.normal * (gap / 2))
                }
                PlaneFeature.Kind.ThreePoints -> {
                    if (f.points.size < 3) throw KernelException("Pick three points")
                    val (a, b, c) = f.points.take(3).map { pointOf(it, bodies, f) }
                    planeFrom(f.name, a, b - a, (b - a).cross(c - a)) ?: throw KernelException("The points are in a line")
                }
                PlaneFeature.Kind.TwoEdges -> {
                    if (f.edges.size < 2) throw KernelException("Pick two straight edges")
                    val (p1, d1) = straightEdge(f.edges[0], bodies, f)
                    val (p2, d2) = straightEdge(f.edges[1], bodies, f)
                    var n = d1.cross(d2)
                    if (n.dot(n) < 1e-12) n = d1.cross(p2 - p1)
                    val plane = planeFrom(f.name, p1, d1, n) ?: throw KernelException("The edges are in a line")
                    if (kotlin.math.abs((p2 - p1).dot(plane.normal)) > 1e-6) throw KernelException("The edges aren't in one plane")
                    plane
                }
                PlaneFeature.Kind.Tangent -> {
                    val faceName = ref(f, f.face ?: throw KernelException("Pick a round face"), false, bodies)
                    val s = bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, faceName, false) }?.takeIf { it[0] == 2.0 }
                        ?: throw KernelException("Pick a cylinder's face")
                    val p = Vec3(s[1], s[2], s[3])
                    val d = Vec3(s[4], s[5], s[6])
                    // Out from the axis towards where the base plane faces, then turned round the axis.
                    var w = base.normal - d * base.normal.dot(d)
                    if (w.dot(w) < 1e-12) w = squareTo(d)
                    w = turn(w * (1 / sqrt(w.dot(w))), d, f.angle)
                    planeFrom(f.name, p + w * s[7], d, w)!!
                }
                PlaneFeature.Kind.AlongEdge -> {
                    val edge = ref(f, f.edges.firstOrNull() ?: throw KernelException("Pick an edge"), true, bodies)
                    val a = bodies.firstNotNullOfOrNull { kernel.alongEdge(it.handle, edge, f.along) } ?: throw KernelException("Its edge isn't there any more")
                    val t = Vec3(a[3], a[4], a[5])
                    planeFrom(f.name, Vec3(a[0], a[1], a[2]), squareTo(t), t)!!
                }
            }
            keep(bodies)
            Step(f.key(), bodies, planes + (f.id to plane), null, made)
        }
        is PointFeature -> {
            val at = when (f.kind) {
                PointFeature.Kind.Fixed -> Vec3(f.x, f.y, f.z)
                PointFeature.Kind.At -> pointOf(f.ref ?: throw KernelException("Pick a corner or round edge"), bodies, f)
                PointFeature.Kind.ThreePlanes -> {
                    if (f.planes.size < 3) throw KernelException("Pick three planes or flat faces")
                    meet(f.planes.take(3).map { resolvePlane(it, bodies, f, planes) }) ?: throw KernelException("The planes don't meet at one point")
                }
            }
            keep(bodies)
            Step(f.key(), bodies, planes, null, made, points = mapOf(f.id to at))
        }
        is AxisFeature -> {
            val axis = when (f.kind) {
                AxisFeature.Kind.Fixed -> Vec3(f.x, f.y, f.z) to Transforms.unit(f.along)
                AxisFeature.Kind.Edge -> straightEdge(f.edge ?: throw KernelException("Pick a straight edge"), bodies, f)
                AxisFeature.Kind.Round -> {
                    val s = f.face?.let { n -> ref(f, n, false, bodies).let { r -> bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, r, false) } } }
                        ?: f.edge?.let { n -> ref(f, n, true, bodies).let { r -> bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, r, true) } } }
                    if (s == null || (s[0] != 1.0 && s[0] != 2.0)) throw KernelException("Pick a cylinder's face or a round edge")
                    Vec3(s[1], s[2], s[3]) to Vec3(s[4], s[5], s[6])
                }
                AxisFeature.Kind.TwoPoints -> {
                    if (f.points.size < 2) throw KernelException("Pick two points")
                    val a = pointOf(f.points[0], bodies, f)
                    val d = pointOf(f.points[1], bodies, f) - a
                    if (d.dot(d) < 1e-12) throw KernelException("The points are in the same place")
                    a to d * (1 / sqrt(d.dot(d)))
                }
            }
            keep(bodies)
            Step(f.key(), bodies, planes, null, made, mapOf(f.id to axis))
        }
        is ExtrudeFeature -> {
            val profile = f.face?.let { faceProfile(f, it, bodies, planes) } ?: run {
                val sketch = sketchOf(f.sketchId, all)
                val onSketch = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
                Profile(onSketch, sketch.curves(), f.regions, (sketch.plane as? PlaneRef.OnFace)?.face)
            }
            val (onSketch, curves, regions) = profile
            val plane = if (f.offset == 0.0) onSketch else onSketch.copy(origin = onSketch.origin + onSketch.normal * f.offset)
            if (f.surface && f.face == null) {
                val sheet = kernel.surfaceFromLines(f.id, plane, curves, false, f.forward, f.back, listOf(0.0, 0.0, 0.0, 0.0), 0.0)
                return applyTool(f, sheet, Operation.NewBody, bodies, planes, made)
            }
            val target = if (f.throughAll) null else f.upTo?.let { resolvePlane(it, bodies, f, planes) }
            val tool = when {
                target != null -> extrudeUpTo(f, plane, target, curves, regions)
                f.throughAll -> {
                    val (forward, back) = throughAll(f, plane, bodies)
                    kernel.extrude(f.id, plane, curves, regions, forward, back, f.taper, f.thin)
                }
                else -> {
                    val out = kernel.extrude(f.id, plane, curves, regions, f.forward, f.back, f.taper, f.thin)
                    // A one-sided cut from a sketch on a face goes into the body, whichever way the face looks;
                    // only the bodies it may change count.
                    val oneSide = f.back == 0.0 && f.forward > 0
                    val mayChange = if (f.only.isEmpty()) bodies else bodies.filter { it.label in f.only }
                    if (oneSide && f.operation != Operation.NewBody && f.operation != Operation.Join && mayChange.none { shares(it.handle, out) }) {
                        kernel.release(out)
                        kernel.extrude(f.id, plane, curves, regions, 0.0, f.forward, f.taper, f.thin)
                    } else out
                }
            }
            applyTool(f, tool, f.operation, bodies, planes, made, profile.home)
        }
        is SweepFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            applyTool(f, kernel.sweep(f.id, plane, sketch.curves(), f.regions, pathOf(f.path, f, bodies, planes, all)), f.operation, bodies, planes, made)
        }
        is PipeFeature -> applyTool(f, kernel.pipe(f.id, pathOf(f.path, f, bodies, planes, all), f.diameter, f.inner), f.operation, bodies, planes, made)
        is GearFeature -> {
            val g = meshed(f, all)
            val plane = resolvePlane(g.plane, bodies, f, planes)
            val made1 = kernel.gear(f.id, plane, g.u, g.v, g.turn, g.module, g.teeth, g.pressureAngle, g.thickness, g.helix, g.herringbone, g.bore, g.clearance)
            applyTool(f, made1, f.operation, bodies, planes, made)
        }
        is CoilFeature -> {
            val plane = resolvePlane(f.plane, bodies, f, planes)
            applyTool(f, kernel.coil(f.id, plane, f.u, f.v, f.diameter, f.pitch, f.turns, f.section, f.square), f.operation, bodies, planes, made)
        }
        is SnapFitFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val s = sketch.sketch
            val at = s.holePoints().map { s.x(it) to s.y(it) }
            if (at.isEmpty()) throw KernelException("Put points in its sketch where the clips go")
            val face = (sketch.plane as? PlaneRef.OnFace)?.face ?: throw KernelException("Sketch the clips on the face they stand on")
            val base = bodyWithFace(face, bodies) ?: throw KernelException("The face the clips stand on isn't there any more")
            val catchBody = f.catchIn?.let { l -> bodies.firstOrNull { it.label == l } ?: throw KernelException("The body for the catches isn't there any more") }
            if (catchBody == base) throw KernelException("The catches go in another body")
            val middle = kernel.centre(base.handle)
            val clips = kernel.snapFitTool(f.id, plane, at, middle, f.sizes, false, "c")
            val catches = try {
                catchBody?.let { kernel.snapFitTool(f.id, plane, at, middle, f.sizes, true, "k") }
            } catch (e: KernelException) {
                kernel.release(clips)
                throw e
            }
            val out = mutableListOf<BodyState>()
            try {
                for (b in bodies) out += when {
                    b == base -> BodyState(b.label, kernel.combine(f.id, b.handle, clips, Operation.Join))
                    b == catchBody && catches != null -> BodyState(b.label, kernel.combine(f.id, b.handle, catches, Operation.Cut))
                    else -> b.also { kernel.retain(it.handle) }
                }
            } catch (e: KernelException) {
                out.forEach { kernel.release(it.handle) }
                throw e
            } finally {
                kernel.release(clips)
                catches?.let { kernel.release(it) }
            }
            Step(f.key(), out, planes, null, made)
        }
        is LipFeature -> {
            val rim = ref(f, f.face, false, bodies)
            val base = bodyWithFace(rim, bodies) ?: throw KernelException("The rim isn't there any more")
            val lid = f.lid?.let { l -> bodies.firstOrNull { it.label == l } ?: throw KernelException("The lid isn't there any more") }
            if (lid == base) throw KernelException("The groove goes in another body")
            // A groove as deep as the lid is thick would cut it in two.
            if (lid != null) kernel.facePlane(base.handle, rim)?.let { p ->
                val b = kernel.bounds(lid.handle)
                val n = Vec3(p[3], p[4], p[5])
                var far = Double.NEGATIVE_INFINITY
                for (i in 0..7) far = maxOf(far, Vec3(b[if (i and 1 == 0) 0 else 3], b[if (i and 2 == 0) 1 else 4], b[if (i and 4 == 0) 2 else 5]).dot(n))
                if (far - Vec3(p[0], p[1], p[2]).dot(n) <= f.height + f.gap + 1e-6)
                    throw KernelException("The groove would go right through the lid. Make the lip lower or the lid thicker")
            }
            // Both from the rim as it is before the lip goes on.
            val lip = kernel.lipTool(f.id, base.handle, rim, 0.0, f.width, f.height, "l")
            val groove = try {
                lid?.let { kernel.lipTool(f.id, base.handle, rim, -f.gap, f.width + f.gap, f.height + f.gap, "g") }
            } catch (e: KernelException) {
                kernel.release(lip)
                throw e
            }
            val out = mutableListOf<BodyState>()
            try {
                for (b in bodies) out += when {
                    b == base -> BodyState(b.label, kernel.combine(f.id, b.handle, lip, Operation.Join))
                    b == lid && groove != null -> BodyState(b.label, kernel.combine(f.id, b.handle, groove, Operation.Cut))
                    else -> b.also { kernel.retain(it.handle) }
                }
            } catch (e: KernelException) {
                out.forEach { kernel.release(it.handle) }
                throw e
            } finally {
                kernel.release(lip)
                groove?.let { kernel.release(it) }
            }
            Step(f.key(), out, planes, null, made)
        }
        is ThreadFeature -> {
            val face = ref(f, f.face, false, bodies)
            val body = bodyWithFace(face, bodies) ?: throw KernelException("The face it's on isn't there any more")
            if (f.symbol) {
                keep(bodies)
                Step(f.key(), bodies, planes, null, made).also { it.threads = listOf(ThreadMark(face, f.pitch)) }
            } else {
                replace(f, bodies, planes, made, body) { kernel.thread(f.id, body.handle, face, f.pitch, f.clearance) }
            }
        }
        is FastenerFeature -> fastener(f, bodies, planes, made)
        is LinkFeature -> link(f, bodies, planes, made)
        is LoftFeature -> {
            if (f.sections.size < 2) throw KernelException("Pick areas in at least two sketches")
            val sections = f.sections.map { s ->
                val sketch = sketchOf(s.sketchId, all)
                Triple(planes[s.sketchId] ?: throw KernelException("A sketch of it couldn't be built"), sketch.curves(), s.region)
            }
            val guide = f.guide?.let { pathOf(it, f, bodies, planes, all) }
            applyTool(f, kernel.loft(f.id, sections, f.ruled, f.twist, guide), f.operation, bodies, planes, made)
        }
        is CanvasFeature -> {
            val plane = resolvePlane(f.plane, bodies, f, planes)
            val c = kotlin.math.cos(f.angle); val s = kotlin.math.sin(f.angle)
            val ex = plane.x * c + plane.y * s
            val ey = plane.y * c - plane.x * s
            val middle = plane.origin + plane.x * f.u + plane.y * f.v
            val hw = f.width / 2; val hh = f.width * f.aspect / 2
            val corners = listOf(
                middle - ex * hw - ey * hh, middle + ex * hw - ey * hh, middle + ex * hw + ey * hh, middle - ex * hw + ey * hh,
            )
            keep(bodies)
            Step(f.key(), bodies, planes, null, made, canvases = mapOf(f.id to PlacedCanvas(f.id, f.image, corners, f.opacity)))
        }
        is PrimitiveFeature -> {
            val plane = resolvePlane(f.plane, bodies, f, planes)
            var tool = kernel.primitive(f.id, plane, f.kind.ordinal, f.u, f.v, f.a, f.b, f.c)
            if (f.flip) {
                // Mirrored through its plane, so it grows the other way.
                val flipped = kernel.transform(f.id, tool, Transforms.mirror(plane.origin, plane.normal), "f")
                kernel.release(tool)
                tool = flipped
            }
            applyTool(f, tool, f.operation, bodies, planes, made)
        }
        is RevolveFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val (ax, ay, dx, dy) = axisOf(f.axis, sketch)
            if (f.surface) applyTool(f, kernel.surfaceFromLines(f.id, plane, sketch.curves().filter { c -> (f.axis as? AxisRef.SketchLine)?.curveId != c.id }, true, 0.0, 0.0, listOf(ax, ay, dx, dy), f.angle), Operation.NewBody, bodies, planes, made)
            else applyTool(f, kernel.revolve(f.id, plane, sketch.curves(), f.regions, ax, ay, dx, dy, f.angle), f.operation, bodies, planes, made)
        }
        is FilletFeature -> edgeFeature(f, f.edges, bodies, planes, made) { body, edges -> kernel.fillet(f.id, body, edges, f.radius, f.kind.ordinal, f.second) }
        is OffsetFaceFeature -> faceFeature(f, f.faces, bodies, planes, made) { body, faces -> kernel.offsetFaces(f.id, body, faces, f.distance) }
        is DeleteFaceFeature -> faceFeature(f, f.faces, bodies, planes, made) { body, faces -> kernel.deleteFaces(f.id, body, faces) }
        is ChamferFeature -> edgeFeature(f, f.edges, bodies, planes, made) { body, edges ->
            kernel.chamfer(f.id, body, edges, f.distance, f.kind.ordinal, f.second, f.flip)
        }
        is ImportFeature -> {
            val h = kernel.import(f.id, f.data, f.format)
            keep(bodies)
            Step(f.key(), bodies + BodyState("Body ${made + 1}", h), planes, null, made + 1)
        }
        is ShellFeature -> {
            val faces = f.faces.map { ref(f, it, false, bodies) }
            val body = bodyWithFace(faces.firstOrNull(), bodies) ?: throw KernelException("Pick the faces to leave open")
            replace(f, bodies, planes, made, body) { kernel.shell(f.id, body.handle, faces, f.thickness) }
        }
        is DraftFeature -> {
            val neutral = ref(f, f.neutral, false, bodies)
            val faces = f.faces.map { ref(f, it, false, bodies) }
            val body = bodyWithFace(neutral, bodies) ?: throw KernelException("The face it pivots on isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.draft(f.id, body.handle, faces, neutral, f.angle) }
        }
        is HoleFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val s = sketch.sketch
            // Lone points and the corners and centres of construction curves, not the ends of drawn ones.
            val at = s.holePoints().map { s.x(it) to s.y(it) }
            if (at.isEmpty()) throw KernelException("Its sketch has no points for holes")
            val tool = kernel.holeTool(f.id, plane, at, f.diameter, f.depth, f.kind.ordinal, f.topDiameter, f.topDepth)
            applyTool(f, tool, Operation.Cut, bodies, planes, made)
        }
        is MirrorFeature -> {
            val plane = resolvePlane(f.plane, bodies, f, planes)
            val m = Transforms.mirror(plane.origin, plane.normal)
            if (f.features.isNotEmpty()) repeatFeatures(f, f.features, listOf(m), bodies, planes, made, all)
            else copies(f, bodies, planes, made, picked(f.bodies, bodies), f.join) { b -> listOf(kernel.transform(f.id, b.handle, m, "m")) }
        }
        is PatternFeature -> {
            val axis = f.axisFeature?.let { id ->
                steps.firstNotNullOfOrNull { it.axes[id] } ?: throw KernelException("Its axis has been deleted")
            }
            val mats = f.path?.let { path ->
                kernel.pathPlaces(pathOf(path, f, bodies, planes, all), f.count, f.spacing, f.turn, f.reverse).drop(1)
            } ?: patternMatrices(f, axis)
            if (f.features.isNotEmpty()) repeatFeatures(f, f.features, mats, bodies, planes, made, all)
            else copies(f, bodies, planes, made, picked(f.bodies, bodies), f.join) { b ->
                mats.mapIndexed { i, m -> kernel.transform(f.id, b.handle, m, "p$i") }
            }
        }
        is CombineFeature -> combineBodies(f, bodies, planes, made)
        is SplitFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            val tool = f.tool?.let { t -> bodies.firstOrNull { it.label == t } ?: throw KernelException("$t isn't there any more") }
            if (tool == body) throw KernelException("Pick a different body to split it by")
            val plane = if (tool == null) resolvePlane(f.plane, bodies, f, planes) else null
            var pieces = when {
                tool == null -> kernel.split(f.id, body.handle, plane!!.origin, plane.normal)
                f.keep == 0 -> kernel.splitBy(f.id, body.handle, tool.handle)
                else -> listOf(kernel.combine(f.id, body.handle, tool.handle, if (f.keep == 1) Operation.Cut else Operation.Intersect))
            }
            if (plane != null && f.keep != 0) {
                // Only the pieces on the side asked for.
                val wanted = pieces.filter { h -> (kernel.centre(h) - plane.origin).dot(plane.normal) > 0 == (f.keep == 1) }
                pieces.filter { it !in wanted }.forEach { kernel.release(it) }
                if (wanted.isEmpty()) throw KernelException("Nothing is left on that side")
                pieces = wanted
            }
            val out = mutableListOf<BodyState>()
            var count = made
            for (b in bodies) {
                if (b != body) { kernel.retain(b.handle); out += b; continue }
                pieces.forEachIndexed { i, h -> out += if (i == 0) BodyState(b.label, h) else BodyState("Body ${++count}", h) }
            }
            Step(f.key(), out, planes, null, count)
        }
        is AlignFeature -> {
            val face = ref(f, f.face, false, bodies)
            val owner = bodyWithFace(face, bodies) ?: throw KernelException("The face to line up isn't there any more")
            val d = kernel.facePlane(owner.handle, face) ?: throw KernelException("Line up by a flat face")
            val c = Vec3(d[0], d[1], d[2])
            val n = Vec3(d[3], d[4], d[5])
            val target = resolvePlane(f.target, bodies, f, planes)
            val want = if (f.sameWay) target.normal else target.normal * -1.0
            // Turned about the face's middle, then moved onto the target, the gap away from it.
            val turn = Transforms.then(Transforms.then(Transforms.translate(c * -1.0), Transforms.turnOnto(n, want)), Transforms.translate(c))
            val m = target.normal
            val away = want * -f.gap
            val shift = if (f.centred) target.origin - c + away else m * (target.origin - c).dot(m) + away
            val matrix = Transforms.then(turn, Transforms.translate(shift))
            val chosen = if (f.bodies.isEmpty()) listOf(owner) else picked(f.bodies, bodies)
            val out = mutableListOf<BodyState>()
            try {
                for (b in bodies) {
                    if (b in chosen) out += BodyState(b.label, kernel.transform(f.id, b.handle, matrix, "a"))
                    else { kernel.retain(b.handle); out += b }
                }
            } catch (e: KernelException) {
                out.forEach { kernel.release(it.handle) }
                throw e
            }
            Step(f.key(), out, planes, null, made)
        }
        is RibFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val curves = sketch.curves()
            if (curves.isEmpty()) throw KernelException("Its sketch has no lines")
            // The first body it meets.
            var made2: Pair<BodyState, Long>? = null
            var why: String? = null
            for (b in bodies) {
                try {
                    made2 = b to kernel.rib(f.id, b.handle, plane, curves, f.thickness, f.flip, f.web)
                    break
                } catch (e: KernelException) {
                    if (why == null) why = e.message
                }
            }
            val (body, h) = made2 ?: throw KernelException(why ?: "There's no body for it to meet")
            replace(f, bodies, planes, made, body) { h }
        }
        is EmbossFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            if (f.regions.isEmpty()) throw KernelException("Pick an area of the sketch")
            val face = ref(f, f.face, false, bodies)
            val body = bodyWithFace(face, bodies) ?: throw KernelException("The face it's on isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.emboss(f.id, body.handle, face, plane, sketch.curves(), f.regions, f.depth, f.sink) }
        }
        is PatchFeature -> {
            if (f.sketchId != null) {
                val sketch = sketchOf(f.sketchId, all)
                val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
                if (f.regions.isEmpty()) throw KernelException("Pick an area of the sketch")
                applyTool(f, kernel.patch(f.id, plane, sketch.curves(), f.regions), Operation.NewBody, bodies, planes, made)
            } else {
                val edges = f.edges.map { ref(f, it, true, bodies) }
                val body = bodies.firstOrNull { b -> val faces = kernel.faceNames(b.handle).toSet(); edges.all { e -> facesOf(e).all { it in faces } } }
                    ?: throw KernelException("Its edges aren't all on one body any more")
                applyTool(f, kernel.patchEdges(f.id, body.handle, edges), Operation.NewBody, bodies, planes, made)
            }
        }
        is StitchFeature -> {
            val chosen = picked(f.bodies, bodies)
            if (chosen.size < 2) throw KernelException("Pick at least two surfaces")
            val h = kernel.stitch(f.id, chosen.map { it.handle })
            val out = bodies.mapNotNull { b ->
                when {
                    b == chosen[0] -> BodyState(b.label, h)
                    b in chosen -> null
                    else -> b.also { kernel.retain(it.handle) }
                }
            }
            Step(f.key(), out, planes, null, made)
        }
        is ThickenFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.thicken(f.id, body.handle, f.thickness, f.both) }
        }
        is JointFeature -> {
            if (f.moving == f.fixed) throw KernelException("Join two different components")
            if (bodies.none { components[it.label] == f.moving }) throw KernelException("${f.moving} has no bodies")
            if (f.kind == JointKind.Rigid) {
                keep(bodies)
                Step(f.key(), bodies, planes, null, made)
            } else {
                val (p, d) = jointAxis(f, bodies)
                val m = jointMatrix(f, p, d, jointTurn(f, bodies, all))
                val group = rigidWith(f.moving, f.fixed, all.subList(0, all.indexOf(f).coerceAtLeast(0)))
                val out = mutableListOf<BodyState>()
                try {
                    for (b in bodies) {
                        if (components[b.label] in group) out += BodyState(b.label, kernel.transform(f.id, b.handle, m, "j"))
                        else { kernel.retain(b.handle); out += b }
                    }
                } catch (e: KernelException) {
                    out.forEach { kernel.release(it.handle) }
                    throw e
                }
                Step(f.key(), out, planes, null, made)
            }
        }
        is MeshEditFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.meshEdit(f.id, body.handle, f.kind.ordinal, f.size, f.steps) }
        }
        is OffsetSurfaceFeature -> {
            val faces = f.faces.map { ref(f, it, false, bodies) }
            val body = bodyWithFace(faces.firstOrNull(), bodies) ?: throw KernelException("Its faces aren't there any more")
            applyTool(f, kernel.offsetSurface(f.id, body.handle, faces, f.distance), Operation.NewBody, bodies, planes, made)
        }
        is MeshEraseFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            if (f.spots.size < 4) throw KernelException("Tap where to erase")
            replace(f, bodies, planes, made, body) { kernel.meshErase(f.id, body.handle, f.spots) }
        }
        is SeparateFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            val pieces = kernel.separate(f.id, body.handle)
            var count = made
            val out = bodies.flatMap { b ->
                if (b != body) listOf(b.also { kernel.retain(it.handle) })
                else pieces.mapIndexed { i, h -> BodyState(if (i == 0) b.label else "Body ${++count}", h) }
            }
            Step(f.key(), out, planes, null, count)
        }
        is SculptFeature -> {
            val old = f.body?.let { l -> bodies.firstOrNull { it.label == l } }
            // If the body it was sculpted from has changed, its strokes are made again on it.
            val h = kernel.sculptedBody(f.id, f.mesh, old?.handle ?: 0L)
            if (old != null) replace(f, bodies, planes, made, old) { h }
            else {
                keep(bodies)
                Step(f.key(), bodies + BodyState(f.body ?: "Body ${made + 1}", h), planes, null, if (f.body == null) made + 1 else made)
            }
        }
        is ConvertFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.convertToSolid(f.id, body.handle) }
        }
        is MoveFeature -> {
            if (f.sx == 0.0 || f.sy == 0.0 || f.sz == 0.0) throw KernelException("Scale by something other than 0")
            val turn = Transforms.then(Transforms.rotate(Transforms.unit(f.axis), f.angle), Transforms.translate(Vec3(f.dx, f.dy, f.dz)))
            // Scaling is about each body's own middle.
            fun matrix(b: BodyState) = if (!f.scaled) turn else Transforms.then(Transforms.scale(f.sx, f.sy, f.sz, kernel.centre(b.handle)), turn)
            val chosen = picked(f.bodies, bodies)
            if (f.copy) copies(f, bodies, planes, made, chosen, false) { b -> listOf(kernel.transform(f.id, b.handle, matrix(b), "c")) }
            else {
                val out = mutableListOf<BodyState>()
                try {
                    for (b in bodies) {
                        if (b in chosen) out += BodyState(b.label, kernel.transform(f.id, b.handle, matrix(b), "t"))
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

    /**
     * A joint's axis: a point on it and its unit direction. For Planar, a
     * point on the face and its normal; for Ball, its centre.
     */
    private fun jointAxis(f: JointFeature, bodies: List<BodyState>): Pair<Vec3, Vec3> {
        f.axisFeature?.let { id -> return steps.firstNotNullOfOrNull { it.axes[id] } ?: throw KernelException("Its axis has been deleted") }
        // While the joint's panel is open its own result is shown, so a pick there is named
        // after it: back to the name it had before the joint moved it.
        val edge = f.edge?.let { unwrap(it, "F${f.id}.j(") }
        val face = f.face?.let { unwrap(it, "F${f.id}.j(") }
        val picked = edge?.let { n -> ref(f, n, true, bodies).let { r -> bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, r, true) } } }
            ?: face?.let { n -> ref(f, n, false, bodies).let { r -> bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, r, false) } } }
        if (f.edge != null || f.face != null) {
            val shape = picked?.get(0)?.toInt()
            when (f.kind) {
                JointKind.Planar -> if (shape != 4) throw KernelException("Pick a flat face for it to slide on")
                JointKind.Ball -> if (shape != 1 && shape != 3 && shape != 4) throw KernelException("Pick a ball, a round edge or a flat face for its centre")
                else -> if (shape == null || shape > 2) throw KernelException("Its axis edge or face isn't there any more")
            }
            val point = Vec3(picked!![1], picked[2], picked[3])
            if (shape == 3) return point to Vec3(0.0, 0.0, 1.0)
            val d = Vec3(picked[4], picked[5], picked[6])
            return point to d * (1 / sqrt(d.dot(d)))
        }
        return Vec3(0.0, 0.0, 0.0) to Transforms.unit(f.axis)
    }

    /** A joint's turn in radians: its own, plus that of the joint it's geared to times the ratio, kept within its limits. */
    private fun jointTurn(f: JointFeature, bodies: List<BodyState>, all: List<Feature>): Double {
        val leaderId = f.linkedTo ?: return f.turnWithin(f.value)
        if (f.kind != JointKind.Turn) throw KernelException("Only a Turn joint can be geared to another")
        val before = all.subList(0, all.indexOf(f).coerceAtLeast(0))
        val leader = before.firstOrNull { it.id == leaderId } as? JointFeature ?: throw KernelException("The joint it's geared to is gone")
        if (leader.kind != JointKind.Turn) throw KernelException("It can only be geared to a Turn joint")
        val ratio = f.ratio ?: run {
            val mine = gearIn(f.moving, bodies, before)
            val theirs = gearIn(leader.moving, bodies, before)
            if (mine == null || theirs == null) throw KernelException("Give the ratio, as these aren't both gears")
            -theirs.teeth.toDouble() / mine.teeth
        }
        return f.turnWithin(f.value + ratio * jointTurn(leader, bodies, before))
    }

    /** The gear made by the Gear tool whose body is in [component], if any. */
    private fun gearIn(component: String, bodies: List<BodyState>, before: List<Feature>): GearFeature? {
        val faces = bodies.filter { components[it.label] == component }.flatMap { kernel.faceNames(it.handle) }
        return before.filterIsInstance<GearFeature>().firstOrNull { g -> faces.any { it.startsWith("F${g.id}.") } }
    }

    /** [name] with each "[open]...)" around a part of it taken off, keeping what was inside. */
    private fun unwrap(name: String, open: String): String {
        var out = name
        while (true) {
            val at = out.indexOf(open)
            if (at < 0) return out
            var depth = 1
            var i = at + open.length
            while (i < out.length && depth > 0) {
                if (out[i] == '(') depth++ else if (out[i] == ')') depth--
                i++
            }
            if (depth != 0) return out
            out = out.substring(0, at) + out.substring(at + open.length, i - 1) + out.substring(i)
        }
    }

    /** A component and those joined rigidly to it by earlier joints, other than [fixed]. */
    private fun rigidWith(moving: String, fixed: String?, before: List<Feature>): Set<String> {
        val pairs = before.filterIsInstance<JointFeature>().filter { it.kind == JointKind.Rigid }.mapNotNull { j -> j.fixed?.let { j.moving to it } }
        val group = mutableSetOf(moving)
        var grew = true
        while (grew) {
            grew = false
            for ((a, b) in pairs) {
                if (a in group && b !in group && b != fixed) { group += b; grew = true }
                if (b in group && a !in group && a != fixed) { group += a; grew = true }
            }
        }
        return group
    }

    /** A path as the kernel takes it. */
    private fun pathOf(p: PathRef, owner: Feature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, all: List<Feature>): KernelPath = when (p) {
        is PathRef.Sketch -> {
            val sketch = sketchOf(p.sketchId, all)
            KernelPath(planes[p.sketchId] ?: throw KernelException("Its path's sketch couldn't be built"), sketch.curves(), 0, emptyList())
        }
        is PathRef.Edges -> {
            if (p.names.isEmpty()) throw KernelException("Pick the edges to follow")
            val names = p.names.map { ref(owner, it, true, bodies) }
            val body = bodies.firstOrNull { b -> val faces = kernel.faceNames(b.handle).toSet(); names.all { e -> facesOf(e).all { it in faces } } }
                ?: throw KernelException("Its path's edges aren't all on one body any more")
            KernelPath(null, emptyList(), body.handle, names)
        }
    }

    /** Where a point construction geometry goes through is, now. */
    private fun pointOf(r: PointRef, bodies: List<BodyState>, owner: Feature): Vec3 = when (r) {
        is PointRef.Corner -> bodies.firstNotNullOfOrNull { kernel.corner(it.handle, r.name) } ?: throw KernelException("Its corner isn't there any more")
        is PointRef.CentreOf -> {
            val edge = ref(owner, r.edge, true, bodies)
            bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, edge, true) }?.takeIf { it[0] == 1.0 }?.let { Vec3(it[1], it[2], it[3]) }
                ?: throw KernelException("Its round edge isn't there any more")
        }
        is PointRef.Construction -> steps.firstNotNullOfOrNull { it.points[r.featureId] } ?: throw KernelException("Its point has been deleted")
    }

    /** A straight edge's start and unit direction. */
    private fun straightEdge(name: String, bodies: List<BodyState>, owner: Feature): Pair<Vec3, Vec3> {
        val edge = ref(owner, name, true, bodies)
        val s = bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, edge, true) }?.takeIf { it[0] == 0.0 }
            ?: throw KernelException("Pick straight edges")
        return Vec3(s[1], s[2], s[3]) to Vec3(s[4], s[5], s[6])
    }

    /** A plane at origin with normal n and its x as near x as it can be; null if n or x is nothing or they're parallel. */
    private fun planeFrom(name: String, origin: Vec3, x: Vec3, n: Vec3): SketchPlane? {
        val nl = sqrt(n.dot(n))
        if (nl < 1e-9) return null
        val normal = n * (1 / nl)
        val flat = x - normal * x.dot(normal)
        val xl = sqrt(flat.dot(flat))
        if (xl < 1e-9) return null
        val ux = flat * (1 / xl)
        return SketchPlane(name, origin, ux, normal.cross(ux))
    }

    /** Some unit vector square to d. */
    private fun squareTo(d: Vec3): Vec3 {
        val other = if (abs(d.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val s = d.cross(other)
        return s * (1 / sqrt(s.dot(s)))
    }

    /** v turned round the unit axis d by angle radians. */
    private fun turn(v: Vec3, d: Vec3, angle: Double): Vec3 {
        val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
        return v * c + d.cross(v) * s + d * (d.dot(v) * (1 - c))
    }

    /** Where three planes meet, or null if they don't at one point. */
    private fun meet(p: List<SketchPlane>): Vec3? {
        val (a, b, c) = p.map { it.normal }
        val det = a.dot(b.cross(c))
        if (abs(det) < 1e-9) return null
        val (da, db, dc) = p.map { it.normal.dot(it.origin) }
        return (b.cross(c) * da + c.cross(a) * db + a.cross(b) * dc) * (1 / det)
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

    private fun patternMatrices(f: PatternFeature, axis: Pair<Vec3, Vec3>?): List<DoubleArray> {
        if (f.count < 1 || f.count > 500 || f.count2 < 1 || f.count2 > 500) throw KernelException("Use between 1 and 500 copies")
        val out = mutableListOf<DoubleArray>()
        if (f.circular) {
            val full = kotlin.math.abs(kotlin.math.abs(f.angle) - 2 * kotlin.math.PI) < 1e-9
            val step = if (full) f.angle / f.count else f.angle / (f.count - 1).coerceAtLeast(1)
            for (i in 1 until f.count) {
                if (axis == null) out += Transforms.rotate(Transforms.unit(f.axis), step * i)
                else {
                    // Round an axis through a point: move the point to the origin, turn, and move back.
                    val (p, d) = axis
                    out += Transforms.then(Transforms.then(Transforms.translate(-p), Transforms.rotate(d, step * i)), Transforms.translate(p))
                }
            }
        } else {
            val d1 = Transforms.unit(f.axis)
            val d2 = f.axis2?.let { Transforms.unit(it) }
            for (i in 0 until f.count) for (j in 0 until (if (d2 != null) f.count2 else 1)) {
                if (i == 0 && j == 0) continue
                var v = d1 * (f.spacing * (i + if (f.stagger && j % 2 == 1) 0.5 else 0.0))
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

    /** Another design's shown bodies, built from its copy, moved into place as new bodies. */
    private fun link(f: LinkFeature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int): Step {
        val child = Design()
        try {
            com.rm.parrotmetric.io.DesignFile.read(f.text, child)
        } catch (e: IllegalArgumentException) {
            throw KernelException("${f.file} can't be read")
        }
        val inner = Rebuilder(kernel)
        try {
            val built = inner.rebuild(Parametrics.apply(child, child.built), components = child.bodies.mapNotNull { (l, i) -> i.component?.let { l to it } }.toMap())
            val shown = built.bodies.filter { !child.info(it.label).hidden }
            if (shown.isEmpty()) throw KernelException("${f.file} has no bodies to show")
            val m = Transforms.translate(Vec3(f.dx, f.dy, f.dz))
            val added = shown.mapIndexed { i, b -> BodyState("Body ${made + i + 1}", kernel.transform(f.id, b.handle, m, "l$i.")) }
            keep(bodies)
            return Step(f.key(), bodies + added, planes, null, made + added.size).also { s -> s.linked = added.associate { it.label to f.component } }
        } finally {
            inner.clear()
        }
    }

    /** A screw, nut or washer, seated in its hole or standing on its plane. */
    private fun fastener(f: FastenerFeature, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int): Step {
        val size = Fasteners.size(f.size) ?: throw KernelException("There's no ${f.size}")
        val seat = if (f.hole != null) {
            val face = ref(f, f.hole, false, bodies)
            val s = bodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, face, false) }?.takeIf { it[0] == 2.0 && it.size > 10 }
                ?: throw KernelException("The hole it goes in isn't there any more")
            val at = Vec3(s[1], s[2], s[3])
            val axis = Vec3(s[4], s[5], s[6])
            // The higher end, or the lower one; the seat faces out of the hole.
            val ends = listOf(at + axis * s[9] to axis * -1.0, at + axis * s[10] to axis)
            val up = Vec3(0.0, 0.0, 1.0)
            val higher = if (abs(ends[1].first.dot(up) - ends[0].first.dot(up)) < 1e-6) ends[1] else ends.maxBy { it.first.dot(up) }
            val (origin, out) = if (f.otherEnd) ends.first { it !== higher } else higher
            val x = squareTo(out)
            SketchPlane(f.name, origin, x, out.cross(x))
        } else {
            val p = resolvePlane(f.plane, bodies, f, planes)
            p.copy(origin = p.toWorld(f.u, f.v))
        }
        val c = f.clearance
        val threaded = f.modelled && f.kind != FastenerKind.Washer
        val (head, height, key, angle) = when (f.kind) {
            FastenerKind.SocketCap -> listOf(size.capHead, size.capHeight, size.capKey, 0.0)
            FastenerKind.HexBolt -> listOf(size.hex, size.hexHeight, 0.0, 0.0)
            // A countersunk head's height follows from its angle; the 1 is unused.
            FastenerKind.Countersunk -> listOf(size.sunkHead, 1.0, size.sunkKey, size.sunkAngle)
            FastenerKind.Nut -> listOf(size.hex, size.nutHeight, 0.0, 0.0)
            FastenerKind.Washer -> listOf(size.washerOutside, size.washerThickness, 0.0, 0.0)
        }
        // A modelled nut's hole is the thread's smaller size, which the thread is cut out from.
        val d = when (f.kind) {
            FastenerKind.Washer -> size.washerHole
            FastenerKind.Nut -> if (threaded) size.d - 1.0825 * size.pitch else size.d
            else -> size.d
        }
        var tool = kernel.fastener(
            f.id, seat, f.kind.ordinal, if (f.kind == FastenerKind.Washer || f.kind == FastenerKind.Nut) d - 2 * c else d + 2 * c,
            if (f.kind.screw) f.length + c else 0.0, head + 2 * c, height + c, key, angle,
        )
        if (threaded) {
            val plain = tool
            try {
                tool = kernel.thread(f.id, plain, "F${f.id}.thread", size.pitch)
            } finally {
                kernel.release(plain)
            }
        }
        val step = applyTool(f, tool, f.operation, bodies, planes, made)
        if (!threaded && f.kind != FastenerKind.Washer && f.operation != Operation.Cut) step.threads = listOf(ThreadMark("F${f.id}.thread", size.pitch))
        if (f.operation == Operation.NewBody) {
            val what = "${f.kind.label} ${size.name}" + if (f.kind.screw) " × ${f.length.toString().removeSuffix(".0")}" else ""
            step.hardware = (step.bodies.map { it.label } - bodies.map { it.label }.toSet()).associateWith { what }
        }
        return step
    }

    private fun keep(bodies: List<BodyState>) = bodies.forEach { kernel.retain(it.handle) }

    private fun sketchOf(id: Int, all: List<Feature>): SketchFeature =
        all.firstOrNull { it.id == id } as? SketchFeature ?: throw KernelException("Its sketch has been deleted or turned off")

    private fun resolvePlane(ref: PlaneRef, bodies: List<BodyState>, owner: Feature, planes: Map<Int, SketchPlane>): SketchPlane = when (val p = ref) {
        is PlaneRef.Fixed -> p.plane
        is PlaneRef.Construction -> planes[p.featureId] ?: throw KernelException("Its plane has been deleted")
        is PlaneRef.OnFace -> {
            val face = ref(owner, p.face, false, bodies)
            val body = bodies.firstOrNull { face in kernel.faceNames(it.handle) }
                ?: throw KernelException("The face it's on isn't there any more")
            val d = kernel.facePlane(body.handle, face) ?: throw KernelException("The face it's on isn't there any more")
            val n = Vec3(d[3], d[4], d[5])
            val origin = p.origin(Vec3(d[0], d[1], d[2]), n)
            // x as it was, flattened onto the face; any direction across the face if that's gone.
            var x = p.x - n * p.x.dot(n)
            if (x.dot(x) < 1e-12) x = if (abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0).cross(n) else Vec3(1.0, 0.0, 0.0).cross(n)
            x *= 1 / sqrt(x.dot(x))
            SketchPlane("On a face", origin, x, n.cross(x))
        }
    }

    /**
     * An extrude as far as a plane or flat face. Parallel to the sketch, that's
     * a distance. Slanted, it goes well past and is cut off where it crosses,
     * keeping the part on the sketch's side.
     */
    private fun extrudeUpTo(f: ExtrudeFeature, plane: SketchPlane, target: SketchPlane, curves: List<ProfileCurve>, regions: List<RegionRef>): Long {
        val n = plane.normal
        val m = target.normal
        val facing = n.dot(m)
        if (abs(facing) < 1e-6) throw KernelException("That face or plane runs alongside the sketch")
        // How far along the sketch's normal its origin is from the target.
        val reach = (target.origin - plane.origin).dot(m) / facing
        if (abs(reach) < 1e-6) throw KernelException("That face or plane goes through the sketch")
        fun extrude(length: Double) =
            if (length > 0) kernel.extrude(f.id, plane, curves, regions, length, 0.0, f.taper, f.thin)
            else kernel.extrude(f.id, plane, curves, regions, 0.0, -length, f.taper, f.thin)
        if (abs(facing) > 1 - 1e-9) return extrude(reach)
        // Far enough for any part of the sketch to reach the slanted target.
        val spread = curves.maxOfOrNull { c -> maxOf(hypot(c.x1, c.y1), hypot(c.x2, c.y2), hypot(c.cx1, c.cy1), hypot(c.cx2, c.cy2)) + c.r } ?: 0.0
        val far = abs(reach) + spread * sqrt(1 - facing * facing) / abs(facing) + 10
        val tool = extrude(if (reach > 0) far else -far)
        val pieces = try {
            kernel.split(f.id, tool, target.origin, m)
        } finally {
            kernel.release(tool)
        }
        // The sketch is on the side the extrude starts from.
        val side = -reach * facing
        val kept = pieces.filter { (kernel.centre(it) - target.origin).dot(m) * side > 0 }
        pieces.filter { it !in kept }.forEach { kernel.release(it) }
        if (kept.isEmpty()) throw KernelException("It doesn't reach that face or plane")
        var joined = kept[0]
        for (h in kept.drop(1)) {
            val next = try { kernel.combine(f.id, joined, h, Operation.Join) } finally { kernel.release(h) }
            kernel.release(joined)
            joined = next
        }
        return joined
    }

    /** What an extrude pushes out: the plane, its curves and areas, and the face the plane is on if any. */
    private data class Profile(val plane: SketchPlane, val curves: List<ProfileCurve>, val regions: List<RegionRef>, val home: String?)

    /** A flat face's outline as curves on a plane on the face, numbered from 1, with the face as the area. */
    private fun faceProfile(f: ExtrudeFeature, face: String, bodies: List<BodyState>, planes: Map<Int, SketchPlane>): Profile {
        val name = ref(f, face, false, bodies)
        val body = bodyWithFace(name, bodies) ?: throw KernelException("The face it's on isn't there any more")
        if (kernel.facePlane(body.handle, name) == null) throw KernelException("Only a flat face can be extruded")
        val plane = resolvePlane(PlaneRef.OnFace(name, Vec3(1.0, 0.0, 0.0)), bodies, f, planes)
        val curves = kernel.faceOutline(body.handle, name, plane)?.mapIndexed { i, c -> c.copy(id = i + 1) }
            ?: throw KernelException("The face couldn't be extruded")
        // The face is the one area bounded by all its edges, holes and all; inside a hole is an area of its own.
        return Profile(plane, curves, listOf(RegionRef(curves.map { it.id }, 0.0, 0.0)), name)
    }

    /**
     * How far an extrude goes to pass right through every body: forward
     * unless [ExtrudeFeature.forward] is backwards, and back as well if it
     * goes both ways.
     */
    private fun throughAll(f: ExtrudeFeature, plane: SketchPlane, bodies: List<BodyState>): Pair<Double, Double> {
        if (bodies.isEmpty()) throw KernelException("There's nothing for it to go through")
        var ahead = 0.0
        var behind = 0.0
        for (b in bodies) {
            val box = kernel.bounds(b.handle)
            for (i in 0 until 8) {
                val corner = Vec3(box[if (i and 1 == 0) 0 else 3], box[if (i and 2 == 0) 1 else 4], box[if (i and 4 == 0) 2 else 5])
                val along = (corner - plane.origin).dot(plane.normal)
                ahead = maxOf(ahead, along)
                behind = maxOf(behind, -along)
            }
        }
        // A little past the last body, so the far face is clean.
        val bothWays = f.back > 0 && f.forward > 0
        return when {
            bothWays -> (ahead + 1) to (behind + 1)
            f.forward < 0 || (f.forward == 0.0 && f.back > 0) -> 0.0 to (behind + 1)
            else -> (ahead + 1) to 0.0
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
    private fun applyTool(f: Feature, tool: Long, op: Operation, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, home: String? = null): Step {
        kernel.retain(tool)
        val step = try {
            useTool(f, tool, op, bodies, planes, made, home)
        } catch (e: KernelException) {
            kernel.release(tool)
            throw e
        }
        step.tool = tool
        step.toolOp = op
        return step
    }

    /**
     * Moves what the sketch projected to where its edges are now: its face's
     * outline, or where the bodies it went through cross its plane. False if
     * they've changed shape, when it's left as it was.
     */
    private fun follow(f: SketchFeature, plane: SketchPlane, bodies: List<BodyState>): Boolean {
        var ok = true
        for (link in f.sketch.links) {
            val curves = if (link.section) {
                val through = bodies.filter { it.label in link.bodies }
                if (through.isEmpty()) continue
                // Each body a hair behind the plane, so a sketch on a body's flat top still finds its
                // outline; else a hair in front, for a body standing on the plane.
                fun at(b: BodyState, offset: Double) = kernel.section(listOf(b.handle), plane.copy(origin = plane.origin + plane.normal * offset))
                val found = through.map { b ->
                    if (link.middle) at(b, middleOf(b, plane)) ?: return true
                    else at(b, -0.01)?.ifEmpty { at(b, 0.01) } ?: return true
                }
                found.flatten()
            } else {
                val face = (f.plane as? PlaneRef.OnFace)?.face ?: continue
                val body = bodies.firstOrNull { face in kernel.faceNames(it.handle) } ?: continue
                kernel.faceOutline(body.handle, face, plane) ?: return true
            }
            if (!com.rm.parrotmetric.sketch.SketchOps.reproject(f.sketch, link, curves)) ok = false
        }
        return ok
    }

    /** How far along a plane's normal the middle of a body is, from the plane. */
    private fun middleOf(b: BodyState, plane: SketchPlane): Double {
        val box = kernel.bounds(b.handle)
        val centre = Vec3((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2)
        return (centre - plane.origin).dot(plane.normal)
    }

    /**
     * A cut tried on each body its box meets, kept where it took something away; [useTool] for a cut,
     * without working out each overlap first (a second boolean as slow as the cut). Null where the
     * kernel can't measure volumes. A cut into a body it overlaps that leaves it as it was has failed.
     */
    private fun cutInto(
        f: Feature, tool: Long, bodies: List<BodyState>, pool: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, only: List<String>,
    ): Step? {
        if (pool.isEmpty() || kernel.properties(pool[0].handle) == null) return null
        val reach = kernel.bounds(tool)
        val out = mutableListOf<BodyState>()
        var changed = 0
        try {
            for (b in bodies) {
                val box = kernel.bounds(b.handle)
                val near = b in pool && (0 until 3).all { box[it] <= reach[it + 3] && reach[it] <= box[it + 3] }
                if (near) {
                    val was = kernel.properties(b.handle)?.get(0) ?: 0.0
                    val cut = kernel.combine(f.id, b.handle, tool, Operation.Cut)
                    val now = kernel.properties(cut)?.get(0) ?: was
                    if (was - now > was * 1e-9) {
                        out += BodyState(b.label, cut)
                        changed++
                        continue
                    }
                    kernel.release(cut)
                    if (kernel.overlapVolume(b.handle, tool) > 1e-9) throw KernelException("The cut couldn't be worked out; try a slightly different size or place")
                }
                kernel.retain(b.handle)
                out += b
            }
        } catch (e: KernelException) {
            out.forEach { kernel.release(it.handle) }
            throw e
        }
        if (changed == 0) {
            out.forEach { kernel.release(it.handle) }
            throw KernelException(if (only.isNotEmpty()) "It doesn't reach the bodies it's set to change" else "It doesn't reach any body to cut")
        }
        kernel.release(tool)
        return Step(f.key(), out, planes, null, made)
    }

    /** Whether two solids share some volume, not just a face. */
    private fun shares(a: Long, b: Long) = kernel.overlapVolume(a, b) > 1e-9

    /**
     * [applyTool] without keeping the tool; takes over the tool's handle.
     * [home] is the face its sketch is on, if it's on one.
     */
    private fun useTool(
        f: Feature, tool: Long, op: Operation, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, home: String? = null,
        only: List<String> = f.only,
    ): Step {
        try {
            if (op == Operation.NewBody) {
                keep(bodies)
                return Step(f.key(), bodies + BodyState("Body ${made + 1}", tool), planes, null, made + 1)
            }
            // A cut or intersect goes into the bodies it reaches into. A join takes those too; when it only
            // touches, as a post on a floor does, the body its sketch is on, or else all it touches.
            // When it's set to change only some bodies, the others are left as they are.
            val pool = if (only.isEmpty()) bodies else bodies.filter { it.label in only }
            if (op == Operation.Cut) cutInto(f, tool, bodies, pool, planes, made, only)?.let { return it }
            val overlap = pool.associateWith { kernel.overlapVolume(it.handle, tool) }
            val touched = pool.filter { overlap.getValue(it) > 1e-9 }.ifEmpty {
                if (op != Operation.Join) return@ifEmpty emptyList()
                val near = pool.filter { kernel.overlaps(it.handle, tool) }
                near.filter { home != null && home in kernel.faceNames(it.handle) }.ifEmpty { near }
            }
            if (touched.isEmpty()) {
                if (op == Operation.Join) {
                    keep(bodies)
                    return Step(f.key(), bodies + BodyState("Body ${made + 1}", tool), planes, null, made + 1)
                }
                throw KernelException(when {
                    only.isNotEmpty() -> "It doesn't reach the bodies it's set to change"
                    op == Operation.Cut -> "It doesn't reach any body to cut"
                    else -> "It doesn't overlap any body"
                })
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

    /**
     * Earlier features' shapes moved by each of [mats] and used again as each
     * feature used its own. A cut that misses every body is left out.
     */
    private fun repeatFeatures(
        f: Feature, ids: List<Int>, mats: List<DoubleArray>, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, all: List<Feature>,
    ): Step {
        val sources = ids.flatMap { placedBy(it, all) }
        keep(bodies)
        var now = bodies
        var count = made
        var used = 0
        val numbers = mutableMapOf<Int, Int>()
        val placed = mutableListOf<Pair<Int, DoubleArray>>()
        try {
            for ((id, src, inner) in sources) {
                val only = all.firstOrNull { it.id == id }?.only.orEmpty()
                val copies = mutableListOf<Long>()
                for (m in mats) {
                    val at = Transforms.then(inner, m)
                    val n = numbers.getOrElse(id) { 0 }.also { numbers[id] = it + 1 }
                    copies += kernel.transform(f.id, src.tool, at, "f$id.$n")
                    placed += id to at
                }
                // A feature's cuts go in together: much quicker than one by one, and steadier where they cross others.
                // Copies that miss do nothing in a cut made together, so they aren't checked one by one.
                val together = if (src.toolOp == Operation.Cut && copies.size > 1) kernel.gather(copies) else null
                val tools = if (together != null) {
                    copies.forEach { kernel.release(it) }
                    listOf(together)
                } else copies.filter { copy ->
                    val reaches = (src.toolOp != Operation.Cut && src.toolOp != Operation.Intersect) ||
                        now.any { (only.isEmpty() || it.label in only) && shares(it.handle, copy) }
                    if (!reaches) kernel.release(copy)
                    reaches
                }
                for (tool in tools) {
                    used++
                    val s = useTool(f, tool, src.toolOp, now, planes, count, only = only)
                    now.forEach { kernel.release(it.handle) }
                    now = s.bodies
                    count = s.bodyCount
                }
            }
            if (used == 0 && mats.isNotEmpty()) throw KernelException("None of the copies reach a body")
        } catch (e: KernelException) {
            now.forEach { kernel.release(it.handle) }
            throw e
        }
        return Step(f.key(), now, planes, null, count).also { it.repeats = placed }
    }

    /**
     * The shapes a feature added or took away, each with where it went: its own tool where it is, or
     * for a mirror or pattern of features, what that placed.
     */
    private fun placedBy(id: Int, all: List<Feature>): List<Triple<Int, Step, DoubleArray>> {
        val i = all.indexOfFirst { it.id == id }
        val step = steps.getOrNull(i)?.takeIf { i < steps.size && it.error == null }
        val out = when {
            step == null -> emptyList()
            step.repeats.isNotEmpty() -> step.repeats.flatMap { (sid, m) -> placedBy(sid, all).map { (id2, s, inner) -> Triple(id2, s, Transforms.then(inner, m)) } }
            step.tool != 0L -> listOf(Triple(id, step, Transforms.translate(Vec3(0.0, 0.0, 0.0))))
            else -> emptyList()
        }
        return out.ifEmpty { throw KernelException("Pick features that add or take away a shape, from before this one") }
    }

    /** Press pull or delete face: each body with some of the faces gets the operation on its own faces. */
    private fun faceFeature(
        f: Feature, faces: List<String>, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int,
        op: (Long, List<String>) -> Long,
    ): Step {
        if (faces.isEmpty()) throw KernelException("Pick the faces")
        @Suppress("NAME_SHADOWING")
        val faces = faces.map { ref(f, it, false, bodies) }
        val out = mutableListOf<BodyState>()
        var found = 0
        try {
            for (b in bodies) {
                val names = kernel.faceNames(b.handle).toSet()
                val mine = faces.filter { it in names }
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
            throw KernelException("Its faces aren't there any more")
        }
        return Step(f.key(), out, planes, null, made)
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
        @Suppress("NAME_SHADOWING")
        val edges = edges.map { ref(f, it, true, bodies) }
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

/**
 * Where a joint puts its moving component, about the point [p] and unit
 * direction [d] (see Rebuilder.jointAxis), with [turn] its turn in radians
 * after gearing and limits.
 */
internal fun jointMatrix(f: JointFeature, p: Vec3, d: Vec3, turn: Double): DoubleArray {
    fun about(axis: Vec3, angle: Double) = Transforms.then(Transforms.then(Transforms.translate(-p), Transforms.rotate(axis, angle)), Transforms.translate(p))
    return when (f.kind) {
        JointKind.Rigid -> Transforms.translate(Vec3(0.0, 0.0, 0.0))
        JointKind.Turn -> about(d, turn)
        JointKind.Slide -> Transforms.translate(d * f.slideWithin(f.value))
        JointKind.TurnSlide -> Transforms.then(about(d, turn), Transforms.translate(d * f.slideWithin(f.value2)))
        JointKind.Planar -> {
            val (u, v) = across(d)
            Transforms.then(about(d, turn), Transforms.translate(u * f.slideWithin(f.value2) + v * f.slideWithin(f.value3)))
        }
        JointKind.Ball -> Transforms.then(
            Transforms.then(about(Transforms.unit(Axis3.X), f.turnWithin(f.value)), about(Transforms.unit(Axis3.Y), f.turnWithin(f.value2))),
            about(Transforms.unit(Axis3.Z), f.turnWithin(f.value3)),
        )
    }
}

/** Two unit directions square to unit [n] and each other, the first along x where it can be, as a Planar joint slides. */
internal fun across(n: Vec3): Pair<Vec3, Vec3> {
    val a = if (abs(n.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
    val u = (a - n * a.dot(n)).let { it * (1 / sqrt(it.dot(it))) }
    return u to n.cross(u)
}

/**
 * A gear as it's made: placed and turned to mesh with the gear it goes beside,
 * if any, following that one's own placing.
 */
fun meshed(f: GearFeature, all: List<Feature>, seen: Int = 0): GearFeature {
    val with = f.meshWith ?: return f
    if (seen > 50) throw KernelException("The gears mesh with each other in a ring")
    val other = all.firstOrNull { it.id == with } as? GearFeature ?: throw KernelException("The gear it meshes with is gone")
    val o = meshed(other, all, seen + 1)
    val distance = o.module * (o.teeth + f.teeth) / 2
    val stepO = 2 * kotlin.math.PI / o.teeth
    val stepF = 2 * kotlin.math.PI / f.teeth
    // How far through a tooth the other gear is where they meet; this one's the other way round, half a tooth on.
    val phase = (f.around - o.turn) / stepO
    val mine = 0.5 - (phase - kotlin.math.floor(phase))
    return f.copy(
        plane = o.plane, u = o.u + distance * kotlin.math.cos(f.around), v = o.v + distance * kotlin.math.sin(f.around),
        turn = f.around + kotlin.math.PI - mine * stepF, module = o.module, pressureAngle = o.pressureAngle, helix = -o.helix,
        herringbone = o.herringbone, meshWith = null,
    )
}
