package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Line
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
    }

    private val steps = mutableListOf<Step>()
    private var hints = mutableMapOf<String, DoubleArray>()
    private val found = mutableMapOf<String, String>()

    /** Builds the features. [hints] are those from the last build (Built.hints), to find lost faces and edges again. */
    fun rebuild(features: List<Feature>, hints: Map<String, DoubleArray> = emptyMap()): Built {
        this.hints = hints.toMutableMap()
        var from = 0
        while (from < steps.size && from < features.size && steps[from].key == features[from].key()) from++
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
            keep(bodies)
            Step(f.key(), bodies, planes + (f.id to plane), null, made)
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
            val sketch = sketchOf(f.sketchId, all)
            val onSketch = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val plane = if (f.offset == 0.0) onSketch else onSketch.copy(origin = onSketch.origin + onSketch.normal * f.offset)
            val target = if (f.throughAll) null else f.upTo?.let { resolvePlane(it, bodies, f, planes) }
            val tool = when {
                target != null -> extrudeUpTo(f, plane, target, sketch)
                f.throughAll -> {
                    val (forward, back) = throughAll(f, plane, bodies)
                    kernel.extrude(f.id, plane, sketch.curves(), f.regions, forward, back, f.taper, f.thin)
                }
                else -> kernel.extrude(f.id, plane, sketch.curves(), f.regions, f.forward, f.back, f.taper, f.thin)
            }
            applyTool(f, tool, f.operation, bodies, planes, made)
        }
        is SweepFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            applyTool(f, kernel.sweep(f.id, plane, sketch.curves(), f.regions, pathOf(f.path, f, bodies, planes, all)), f.operation, bodies, planes, made)
        }
        is PipeFeature -> applyTool(f, kernel.pipe(f.id, pathOf(f.path, f, bodies, planes, all), f.diameter, f.inner), f.operation, bodies, planes, made)
        is CoilFeature -> {
            val plane = resolvePlane(f.plane, bodies, f, planes)
            applyTool(f, kernel.coil(f.id, plane, f.u, f.v, f.diameter, f.pitch, f.turns, f.section, f.square), f.operation, bodies, planes, made)
        }
        is ThreadFeature -> {
            val face = ref(f, f.face, false, bodies)
            val body = bodyWithFace(face, bodies) ?: throw KernelException("The face it's on isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.thread(f.id, body.handle, face, f.pitch) }
        }
        is LoftFeature -> {
            if (f.sections.size < 2) throw KernelException("Pick areas in at least two sketches")
            val sections = f.sections.map { s ->
                val sketch = sketchOf(s.sketchId, all)
                Triple(planes[s.sketchId] ?: throw KernelException("A sketch of it couldn't be built"), sketch.curves(), s.region)
            }
            applyTool(f, kernel.loft(f.id, sections, f.ruled), f.operation, bodies, planes, made)
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
            applyTool(f, kernel.primitive(f.id, plane, f.kind.ordinal, f.u, f.v, f.a, f.b, f.c), f.operation, bodies, planes, made)
        }
        is RevolveFeature -> {
            val sketch = sketchOf(f.sketchId, all)
            val plane = planes[f.sketchId] ?: throw KernelException("Its sketch couldn't be built")
            val (ax, ay, dx, dy) = axisOf(f.axis, sketch)
            val tool = kernel.revolve(f.id, plane, sketch.curves(), f.regions, ax, ay, dx, dy, f.angle)
            applyTool(f, tool, f.operation, bodies, planes, made)
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
            // The sketch's lone points, not the ends of its curves.
            val at = s.points.filter { p -> p !== s.origin && s.curves.none { p in it.points() } }.map { s.x(it) to s.y(it) }
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
        is MeshEditFeature -> {
            val body = bodies.firstOrNull { it.label == f.body } ?: throw KernelException("${f.body} isn't there any more")
            replace(f, bodies, planes, made, body) { kernel.meshEdit(f.id, body.handle, f.kind.ordinal, f.size, f.steps) }
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
        all.firstOrNull { it.id == id } as? SketchFeature ?: throw KernelException("Its sketch has been deleted or turned off")

    private fun resolvePlane(ref: PlaneRef, bodies: List<BodyState>, owner: Feature, planes: Map<Int, SketchPlane>): SketchPlane = when (val p = ref) {
        is PlaneRef.Fixed -> p.plane
        is PlaneRef.Construction -> planes[p.featureId] ?: throw KernelException("Its plane has been deleted")
        is PlaneRef.OnFace -> {
            val face = ref(owner, p.face, false, bodies)
            val body = bodies.firstOrNull { face in kernel.faceNames(it.handle) }
                ?: throw KernelException("The face it's on isn't there any more")
            val d = kernel.facePlane(body.handle, face) ?: throw KernelException("The face it's on isn't there any more")
            val origin = Vec3(d[0], d[1], d[2])
            val n = Vec3(d[3], d[4], d[5])
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
    private fun extrudeUpTo(f: ExtrudeFeature, plane: SketchPlane, target: SketchPlane, sketch: SketchFeature): Long {
        val n = plane.normal
        val m = target.normal
        val facing = n.dot(m)
        if (abs(facing) < 1e-6) throw KernelException("That face or plane runs alongside the sketch")
        // How far along the sketch's normal its origin is from the target.
        val reach = (target.origin - plane.origin).dot(m) / facing
        if (abs(reach) < 1e-6) throw KernelException("That face or plane goes through the sketch")
        val curves = sketch.curves()
        fun extrude(length: Double) =
            if (length > 0) kernel.extrude(f.id, plane, curves, f.regions, length, 0.0, f.taper, f.thin)
            else kernel.extrude(f.id, plane, curves, f.regions, 0.0, -length, f.taper, f.thin)
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
    private fun applyTool(f: Feature, tool: Long, op: Operation, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int): Step {
        kernel.retain(tool)
        val step = try {
            useTool(f, tool, op, bodies, planes, made)
        } catch (e: KernelException) {
            kernel.release(tool)
            throw e
        }
        step.tool = tool
        step.toolOp = op
        return step
    }

    /** [applyTool] without keeping the tool; takes over the tool's handle. */
    private fun useTool(f: Feature, tool: Long, op: Operation, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int): Step {
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

    /**
     * Earlier features' shapes moved by each of [mats] and used again as each
     * feature used its own. A cut that misses every body is left out.
     */
    private fun repeatFeatures(
        f: Feature, ids: List<Int>, mats: List<DoubleArray>, bodies: List<BodyState>, planes: Map<Int, SketchPlane>, made: Int, all: List<Feature>,
    ): Step {
        val sources = ids.map { id ->
            val i = all.indexOfFirst { it.id == id }
            steps.getOrNull(i)?.takeIf { i < steps.size && it.tool != 0L && it.error == null }
                ?: throw KernelException("Pick features that add or take away a shape, from before this one")
        }
        keep(bodies)
        var now = bodies
        var count = made
        var used = 0
        try {
            for ((k, src) in sources.withIndex()) for ((j, m) in mats.withIndex()) {
                val copy = kernel.transform(f.id, src.tool, m, "f${ids[k]}.$j")
                if (src.toolOp == Operation.Cut || src.toolOp == Operation.Intersect) {
                    if (now.none { kernel.overlaps(it.handle, copy) }) { kernel.release(copy); continue }
                }
                used++
                val s = useTool(f, copy, src.toolOp, now, planes, count)
                now.forEach { kernel.release(it.handle) }
                now = s.bodies
                count = s.bodyCount
            }
            if (used == 0 && mats.isNotEmpty()) throw KernelException("None of the copies reach a body")
        } catch (e: KernelException) {
            now.forEach { kernel.release(it.handle) }
            throw e
        }
        return Step(f.key(), now, planes, null, count)
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
