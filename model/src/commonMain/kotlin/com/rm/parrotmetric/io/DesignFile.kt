package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.Axis3
import com.rm.parrotmetric.design.AxisFeature
import com.rm.parrotmetric.design.AxisRef
import com.rm.parrotmetric.design.PlaneFeature
import com.rm.parrotmetric.design.CombineFeature
import com.rm.parrotmetric.design.DraftFeature
import com.rm.parrotmetric.design.HoleFeature
import com.rm.parrotmetric.design.HoleKind
import com.rm.parrotmetric.design.MirrorFeature
import com.rm.parrotmetric.design.MoveFeature
import com.rm.parrotmetric.design.PatternFeature
import com.rm.parrotmetric.design.ShellFeature
import com.rm.parrotmetric.design.SplitFeature
import com.rm.parrotmetric.design.ChamferFeature
import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.design.ExtrudeFeature
import com.rm.parrotmetric.design.Feature
import com.rm.parrotmetric.design.FilletFeature
import com.rm.parrotmetric.design.ImportFeature
import com.rm.parrotmetric.design.Operation
import com.rm.parrotmetric.design.Parameter
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.RegionRef
import com.rm.parrotmetric.design.RevolveFeature
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.Arc
import com.rm.parrotmetric.sketch.Circle
import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Spline
import com.rm.parrotmetric.sketch.Vec3
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The .pmet file: the design's history as JSON, everything in mm and
 * radians. Geometry isn't saved; it's rebuilt when the file is opened.
 * Newer kinds of feature are added as the app grows; [VERSION] goes up when
 * an old file would need changing to be read.
 */
@OptIn(ExperimentalEncodingApi::class)
object DesignFile {
    const val VERSION = 1

    /** Extra things a feature kind can add to the file, so later milestones don't have to edit this one. */
    interface Codec {
        val type: String
        fun write(f: Feature): Map<String, Any?>?
        fun read(o: Json.Obj): Feature?
    }

    val extraCodecs = mutableListOf<Codec>()

    fun write(design: Design, title: String): String = Json.obj(
        "format" to "parrotmetric",
        "version" to VERSION,
        "title" to title,
        "marker" to design.marker,
        "features" to design.features.map { feature(it) },
        "bodies" to design.bodies.mapValues { (_, b) -> mapOf("name" to b.name, "component" to b.component, "hidden" to b.hidden) },
        "parameters" to design.parameters.map { mapOf("name" to it.name, "expression" to it.expression) },
        "expressions" to design.expressions.mapKeys { it.key.toString() },
    ).toString()

    /** Reads a file into the design. Returns its title. Throws IllegalArgumentException if it can't. */
    fun read(text: String, into: Design): String {
        val root = Json.parse(text) as? Json.Obj ?: throw IllegalArgumentException("Not a design file")
        if (root["format"] != Json.Str("parrotmetric")) throw IllegalArgumentException("Not a design file")
        if (root.int("version") > VERSION) throw IllegalArgumentException("This file was saved by a newer ParrotMetric")
        val features = root.arr("features").map { feature(it as Json.Obj) }
        val bodies = (root["bodies"] as? Json.Obj)?.fields?.mapValues { (_, v) ->
            v as Json.Obj
            Design.BodyInfo((v["name"] as? Json.Str)?.value, (v["component"] as? Json.Str)?.value, v.bool("hidden"))
        } ?: emptyMap()
        val parameters = root.arr("parameters").map { p -> p as Json.Obj; Parameter(p.str("name"), p.str("expression")) }
        val expressions = (root["expressions"] as? Json.Obj)?.fields?.map { (k, v) ->
            k.toInt() to (v as Json.Obj).fields.mapValues { (it.value as Json.Str).value }
        }?.toMap() ?: emptyMap()
        into.load(features, root.int("marker"), bodies, parameters, expressions)
        return (root["title"] as? Json.Str)?.value ?: "Untitled"
    }

    private fun vec(v: Vec3) = listOf(v.x, v.y, v.z)
    private fun vec(j: Json?): Vec3 {
        val a = (j as Json.Arr).items.map { (it as Json.Num).value }
        return Vec3(a[0], a[1], a[2])
    }

    private fun writeRegions(r: List<RegionRef>) = r.map { mapOf("curves" to it.curveIds, "u" to it.u, "v" to it.v) }
    private fun readRegions(j: List<Json>) = j.map { o ->
        o as Json.Obj
        RegionRef(o.arr("curves").map { (it as Json.Num).value.toInt() }, o.num("u"), o.num("v"))
    }

    private fun feature(f: Feature): Json {
        extraCodecs.firstNotNullOfOrNull { c -> c.write(f)?.let { c.type to it } }?.let { (type, fields) ->
            return Json.of(mapOf("type" to type, "id" to f.id, "name" to f.name) + fields)
        }
        val base = mapOf("id" to f.id, "name" to f.name)
        val extra: Map<String, Any?> = when (f) {
            is SketchFeature -> mapOf("type" to "sketch", "plane" to plane(f.plane), "sketch" to sketch(f.sketch))
            is ShellFeature -> mapOf("type" to "shell", "faces" to f.faces, "thickness" to f.thickness)
            is DraftFeature -> mapOf("type" to "draft", "faces" to f.faces, "neutral" to f.neutral, "angle" to f.angle)
            is HoleFeature -> mapOf(
                "type" to "hole", "sketch" to f.sketchId, "diameter" to f.diameter, "depth" to f.depth, "kind" to f.kind.name,
                "topDiameter" to f.topDiameter, "topDepth" to f.topDepth,
            )
            is MirrorFeature -> mapOf("type" to "mirror", "bodies" to f.bodies, "plane" to plane(f.plane), "join" to f.join)
            is PatternFeature -> mapOf(
                "type" to "pattern", "bodies" to f.bodies, "circular" to f.circular, "axis" to f.axis.name, "count" to f.count,
                "spacing" to f.spacing, "angle" to f.angle, "axis2" to f.axis2?.name, "count2" to f.count2, "spacing2" to f.spacing2, "join" to f.join,
                "axisFeature" to f.axisFeature,
            )
            is CombineFeature -> mapOf(
                "type" to "combine", "target" to f.target, "tools" to f.tools, "operation" to f.operation.name, "keepTools" to f.keepTools,
            )
            is SplitFeature -> mapOf("type" to "split", "body" to f.body, "plane" to plane(f.plane))
            is PlaneFeature -> mapOf(
                "type" to "plane", "kind" to f.kind.name, "base" to plane(f.base), "offset" to f.offset, "angle" to f.angle,
                "turnRoundY" to f.turnRoundY, "other" to f.other?.let { plane(it) },
            )
            is AxisFeature -> mapOf("type" to "axis", "x" to f.x, "y" to f.y, "z" to f.z, "along" to f.along.name)
            is MoveFeature -> mapOf(
                "type" to "move", "bodies" to f.bodies, "dx" to f.dx, "dy" to f.dy, "dz" to f.dz, "axis" to f.axis.name, "angle" to f.angle, "copy" to f.copy,
            )
            is ExtrudeFeature -> mapOf(
                "type" to "extrude", "sketch" to f.sketchId, "regions" to writeRegions(f.regions),
                "forward" to f.forward, "back" to f.back, "operation" to f.operation.name,
            )
            is RevolveFeature -> mapOf(
                "type" to "revolve", "sketch" to f.sketchId, "regions" to writeRegions(f.regions),
                "axis" to when (val a = f.axis) {
                    AxisRef.SketchX -> "x"
                    AxisRef.SketchY -> "y"
                    is AxisRef.SketchLine -> "line ${a.curveId}"
                },
                "angle" to f.angle, "operation" to f.operation.name,
            )
            is FilletFeature -> mapOf("type" to "fillet", "edges" to f.edges, "radius" to f.radius)
            is ChamferFeature -> mapOf("type" to "chamfer", "edges" to f.edges, "distance" to f.distance)
            is ImportFeature -> mapOf("type" to "import", "format" to f.format, "data" to Base64.encode(f.data))
            else -> throw IllegalArgumentException("Can't save ${f.name}")
        }
        return Json.of(base + extra)
    }

    private fun feature(o: Json.Obj): Feature {
        val id = o.int("id")
        val name = o.str("name")
        val type = o.str("type")
        extraCodecs.firstOrNull { it.type == type }?.let { return it.read(o) ?: throw IllegalArgumentException("Can't read $name") }
        return when (type) {
            "sketch" -> SketchFeature(id, name, plane(o.obj("plane")), sketch(o.obj("sketch")))
            "shell" -> ShellFeature(id, name, strings(o.arr("faces")), o.num("thickness"))
            "draft" -> DraftFeature(id, name, strings(o.arr("faces")), o.str("neutral"), o.num("angle"))
            "hole" -> HoleFeature(
                id, name, o.int("sketch"), o.num("diameter"), o.num("depth"), HoleKind.valueOf(o.str("kind")), o.num("topDiameter"), o.num("topDepth"),
            )
            "mirror" -> MirrorFeature(id, name, strings(o.arr("bodies")), plane(o.obj("plane")), o.bool("join"))
            "pattern" -> PatternFeature(
                id, name, strings(o.arr("bodies")), o.bool("circular"), Axis3.valueOf(o.str("axis")), o.int("count"), o.num("spacing"), o.num("angle"),
                (o["axis2"] as? Json.Str)?.let { Axis3.valueOf(it.value) }, o.int("count2"), o.num("spacing2"), o.bool("join"),
                (o["axisFeature"] as? Json.Num)?.value?.toInt(),
            )
            "combine" -> CombineFeature(id, name, o.str("target"), strings(o.arr("tools")), Operation.valueOf(o.str("operation")), o.bool("keepTools"))
            "split" -> SplitFeature(id, name, o.str("body"), plane(o.obj("plane")))
            "plane" -> PlaneFeature(
                id, name, PlaneFeature.Kind.valueOf(o.str("kind")), plane(o.obj("base")), o.num("offset"), o.num("angle"),
                o.bool("turnRoundY"), (o["other"] as? Json.Obj)?.let { plane(it) },
            )
            "axis" -> AxisFeature(id, name, o.num("x"), o.num("y"), o.num("z"), Axis3.valueOf(o.str("along")))
            "move" -> MoveFeature(
                id, name, strings(o.arr("bodies")), o.num("dx"), o.num("dy"), o.num("dz"), Axis3.valueOf(o.str("axis")), o.num("angle"), o.bool("copy"),
            )
            "extrude" -> ExtrudeFeature(
                id, name, o.int("sketch"), readRegions(o.arr("regions")), o.num("forward"), o.num("back"), Operation.valueOf(o.str("operation")),
            )
            "revolve" -> {
                val axis = when (val a = o.str("axis")) {
                    "x" -> AxisRef.SketchX
                    "y" -> AxisRef.SketchY
                    else -> AxisRef.SketchLine(a.removePrefix("line ").toInt())
                }
                RevolveFeature(id, name, o.int("sketch"), readRegions(o.arr("regions")), axis, o.num("angle"), Operation.valueOf(o.str("operation")))
            }
            "fillet" -> FilletFeature(id, name, o.arr("edges").map { (it as Json.Str).value }, o.num("radius"))
            "chamfer" -> ChamferFeature(id, name, o.arr("edges").map { (it as Json.Str).value }, o.num("distance"))
            "import" -> ImportFeature(id, name, Base64.decode(o.str("data")), o.int("format"))
            else -> throw IllegalArgumentException("This file has a step this version can't read: $type")
        }
    }

    private fun strings(j: List<Json>) = j.map { (it as Json.Str).value }

    private fun plane(p: PlaneRef): Map<String, Any?> = when (p) {
        is PlaneRef.Fixed -> mapOf("name" to p.plane.name, "origin" to vec(p.plane.origin), "x" to vec(p.plane.x), "y" to vec(p.plane.y))
        is PlaneRef.OnFace -> mapOf("face" to p.face, "x" to vec(p.x))
        is PlaneRef.Construction -> mapOf("construction" to p.featureId)
    }

    private fun plane(p: Json.Obj): PlaneRef = when {
        p["construction"] != null -> PlaneRef.Construction(p.int("construction"))
        p["face"] != null -> PlaneRef.OnFace(p.str("face"), vec(p["x"]))
        else -> PlaneRef.Fixed(SketchPlane(p.str("name"), vec(p["origin"]), vec(p["x"]), vec(p["y"])))
    }

    // Sketches: points, curves and constraints, each referring to the others by id.

    private fun sketch(s: Sketch): Map<String, Any?> = mapOf(
        "points" to s.points.filter { it !== s.origin }.map { listOf(it.id, s.x(it), s.y(it)) },
        "curves" to s.curves.map { c ->
            when (c) {
                is Line -> mapOf("type" to "line", "id" to c.id, "a" to c.a.id, "b" to c.b.id, "construction" to c.construction)
                is Circle -> mapOf("type" to "circle", "id" to c.id, "centre" to c.centre.id, "r" to s.radius(c), "construction" to c.construction)
                is Arc -> mapOf("type" to "arc", "id" to c.id, "centre" to c.centre.id, "start" to c.start.id, "end" to c.end.id, "construction" to c.construction)
                is Spline -> mapOf("type" to "spline", "id" to c.id, "through" to c.through.map { it.id }, "construction" to c.construction)
            }
        },
        "constraints" to s.constraints.mapNotNull { constraint(it) },
    )

    private fun constraint(c: Constraint): Map<String, Any?>? = when (c) {
        is Constraint.ArcRadius -> null
        is Constraint.Coincident -> mapOf("type" to "coincident", "p" to c.p.id, "q" to c.q.id)
        is Constraint.Horizontal -> mapOf("type" to "horizontal", "line" to c.line.id)
        is Constraint.Vertical -> mapOf("type" to "vertical", "line" to c.line.id)
        is Constraint.HorizontalPoints -> mapOf("type" to "horizontalPoints", "p" to c.p.id, "q" to c.q.id)
        is Constraint.VerticalPoints -> mapOf("type" to "verticalPoints", "p" to c.p.id, "q" to c.q.id)
        is Constraint.Parallel -> mapOf("type" to "parallel", "l1" to c.l1.id, "l2" to c.l2.id)
        is Constraint.Perpendicular -> mapOf("type" to "perpendicular", "l1" to c.l1.id, "l2" to c.l2.id)
        is Constraint.Equal -> mapOf("type" to "equal", "c1" to c.c1.id, "c2" to c.c2.id)
        is Constraint.Fixed -> mapOf("type" to "fixed", "p" to c.p.id, "x" to c.atX, "y" to c.atY)
        is Constraint.Midpoint -> mapOf("type" to "midpoint", "p" to c.p.id, "line" to c.line.id)
        is Constraint.OnLine -> mapOf("type" to "onLine", "p" to c.p.id, "line" to c.line.id)
        is Constraint.OnCircle -> mapOf("type" to "onCircle", "p" to c.p.id, "curve" to c.curve.id)
        is Constraint.TangentLine -> mapOf("type" to "tangentLine", "line" to c.line.id, "curve" to c.curve.id)
        is Constraint.TangentCircles -> mapOf("type" to "tangentCircles", "c1" to c.c1.id, "c2" to c.c2.id, "inside" to c.inside)
        is Constraint.Symmetric -> mapOf("type" to "symmetric", "p" to c.p.id, "q" to c.q.id, "line" to c.line.id)
        is Constraint.Distance -> mapOf("type" to "distance", "p" to c.p.id, "q" to c.q.id, "value" to c.value, "expression" to c.expression)
        is Constraint.Length -> mapOf("type" to "length", "line" to c.line.id, "value" to c.value, "expression" to c.expression)
        is Constraint.AxisDistance -> mapOf("type" to "axisDistance", "p" to c.p.id, "q" to c.q.id, "vertical" to c.vertical, "value" to c.value, "expression" to c.expression)
        is Constraint.PointLineDistance -> mapOf("type" to "pointLineDistance", "p" to c.p.id, "line" to c.line.id, "value" to c.value, "expression" to c.expression)
        is Constraint.Radius -> mapOf("type" to "radius", "curve" to c.curve.id, "diameter" to c.diameter, "value" to c.value, "expression" to c.expression)
        is Constraint.Angle -> mapOf("type" to "angle", "l1" to c.l1.id, "l2" to c.l2.id, "value" to c.value, "expression" to c.expression)
    }

    private fun sketch(o: Json.Obj): Sketch {
        val s = Sketch()
        for (p in o.arr("points")) {
            val a = (p as Json.Arr).items.map { (it as Json.Num).value }
            s.loadPoint(a[0].toInt(), a[1], a[2])
        }
        fun pt(c: Json.Obj, k: String) = s.point(c.int(k)) ?: throw IllegalArgumentException("A sketch refers to a missing point")
        for (j in o.arr("curves")) {
            val c = j as Json.Obj
            val id = c.int("id")
            val cons = c.bool("construction")
            when (c.str("type")) {
                "line" -> s.loadLine(id, pt(c, "a"), pt(c, "b"), cons)
                "circle" -> s.loadCircle(id, pt(c, "centre"), c.num("r"), cons)
                "arc" -> s.loadArc(id, pt(c, "centre"), pt(c, "start"), pt(c, "end"), cons)
                "spline" -> s.loadSpline(id, c.arr("through").map { s.point((it as Json.Num).value.toInt()) ?: throw IllegalArgumentException("A sketch refers to a missing point") }, cons)
            }
        }
        fun line(c: Json.Obj, k: String) = s.curve(c.int(k)) as? Line ?: throw IllegalArgumentException("A sketch refers to a missing line")
        fun curve(c: Json.Obj, k: String) = s.curve(c.int(k)) ?: throw IllegalArgumentException("A sketch refers to a missing curve")
        for (j in o.arr("constraints")) {
            val c = j as Json.Obj
            val k: Constraint = when (c.str("type")) {
                "coincident" -> Constraint.Coincident(pt(c, "p"), pt(c, "q"))
                "horizontal" -> Constraint.Horizontal(line(c, "line"))
                "vertical" -> Constraint.Vertical(line(c, "line"))
                "horizontalPoints" -> Constraint.HorizontalPoints(pt(c, "p"), pt(c, "q"))
                "verticalPoints" -> Constraint.VerticalPoints(pt(c, "p"), pt(c, "q"))
                "parallel" -> Constraint.Parallel(line(c, "l1"), line(c, "l2"))
                "perpendicular" -> Constraint.Perpendicular(line(c, "l1"), line(c, "l2"))
                "equal" -> Constraint.Equal(curve(c, "c1"), curve(c, "c2"))
                "fixed" -> Constraint.Fixed(pt(c, "p"), c.num("x"), c.num("y"))
                "midpoint" -> Constraint.Midpoint(pt(c, "p"), line(c, "line"))
                "onLine" -> Constraint.OnLine(pt(c, "p"), line(c, "line"))
                "onCircle" -> Constraint.OnCircle(pt(c, "p"), curve(c, "curve"))
                "tangentLine" -> Constraint.TangentLine(line(c, "line"), curve(c, "curve"))
                "tangentCircles" -> Constraint.TangentCircles(curve(c, "c1"), curve(c, "c2"), c.bool("inside"))
                "symmetric" -> Constraint.Symmetric(pt(c, "p"), pt(c, "q"), line(c, "line"))
                "distance" -> Constraint.Distance(pt(c, "p"), pt(c, "q"), c.num("value"))
                "length" -> Constraint.Length(line(c, "line"), c.num("value"))
                "axisDistance" -> Constraint.AxisDistance(pt(c, "p"), pt(c, "q"), c.bool("vertical"), c.num("value"))
                "pointLineDistance" -> Constraint.PointLineDistance(pt(c, "p"), line(c, "line"), c.num("value"))
                "radius" -> Constraint.Radius(curve(c, "curve"), c.bool("diameter"), c.num("value"))
                "angle" -> Constraint.Angle(line(c, "l1"), line(c, "l2"), c.num("value"))
                else -> continue
            }
            if (k is Constraint.Dimension) k.expression = (c["expression"] as? Json.Str)?.value
            s.loadConstraint(k)
        }
        return s
    }
}
