package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.AxisRef
import com.rm.parrotmetric.design.ChamferFeature
import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.design.ExtrudeFeature
import com.rm.parrotmetric.design.Feature
import com.rm.parrotmetric.design.FilletFeature
import com.rm.parrotmetric.design.ImportFeature
import com.rm.parrotmetric.design.Operation
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
    ).toString()

    /** Reads a file into the design. Returns its title. Throws IllegalArgumentException if it can't. */
    fun read(text: String, into: Design): String {
        val root = Json.parse(text) as? Json.Obj ?: throw IllegalArgumentException("Not a design file")
        if (root["format"] != Json.Str("parrotmetric")) throw IllegalArgumentException("Not a design file")
        if (root.int("version") > VERSION) throw IllegalArgumentException("This file was saved by a newer ParrotMetric")
        val features = root.arr("features").map { feature(it as Json.Obj) }
        into.load(features, root.int("marker"))
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
            is SketchFeature -> mapOf(
                "type" to "sketch",
                "plane" to when (val p = f.plane) {
                    is PlaneRef.Fixed -> mapOf("name" to p.plane.name, "origin" to vec(p.plane.origin), "x" to vec(p.plane.x), "y" to vec(p.plane.y))
                    is PlaneRef.OnFace -> mapOf("face" to p.face, "x" to vec(p.x))
                },
                "sketch" to sketch(f.sketch),
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
            "sketch" -> {
                val p = o.obj("plane")
                val plane = if (p["face"] != null) PlaneRef.OnFace(p.str("face"), vec(p["x"]))
                else PlaneRef.Fixed(SketchPlane(p.str("name"), vec(p["origin"]), vec(p["x"]), vec(p["y"])))
                SketchFeature(id, name, plane, sketch(o.obj("sketch")))
            }
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

    // Sketches: points, curves and constraints, each referring to the others by id.

    private fun sketch(s: Sketch): Map<String, Any?> = mapOf(
        "points" to s.points.filter { it !== s.origin }.map { listOf(it.id, s.x(it), s.y(it)) },
        "curves" to s.curves.map { c ->
            when (c) {
                is Line -> mapOf("type" to "line", "id" to c.id, "a" to c.a.id, "b" to c.b.id, "construction" to c.construction)
                is Circle -> mapOf("type" to "circle", "id" to c.id, "centre" to c.centre.id, "r" to s.radius(c), "construction" to c.construction)
                is Arc -> mapOf("type" to "arc", "id" to c.id, "centre" to c.centre.id, "start" to c.start.id, "end" to c.end.id, "construction" to c.construction)
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
        is Constraint.Distance -> mapOf("type" to "distance", "p" to c.p.id, "q" to c.q.id, "value" to c.value)
        is Constraint.Length -> mapOf("type" to "length", "line" to c.line.id, "value" to c.value)
        is Constraint.AxisDistance -> mapOf("type" to "axisDistance", "p" to c.p.id, "q" to c.q.id, "vertical" to c.vertical, "value" to c.value)
        is Constraint.PointLineDistance -> mapOf("type" to "pointLineDistance", "p" to c.p.id, "line" to c.line.id, "value" to c.value)
        is Constraint.Radius -> mapOf("type" to "radius", "curve" to c.curve.id, "diameter" to c.diameter, "value" to c.value)
        is Constraint.Angle -> mapOf("type" to "angle", "l1" to c.l1.id, "l2" to c.l2.id, "value" to c.value)
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
            s.loadConstraint(k)
        }
        return s
    }
}
