package com.rm.parrotmetric.io

import com.rm.parrotmetric.design.Axis3
import com.rm.parrotmetric.design.AxisFeature
import com.rm.parrotmetric.design.AxisRef
import com.rm.parrotmetric.design.PlaneFeature
import com.rm.parrotmetric.design.CombineFeature
import com.rm.parrotmetric.design.ConvertFeature
import com.rm.parrotmetric.design.DraftFeature
import com.rm.parrotmetric.design.HoleFeature
import com.rm.parrotmetric.design.HoleKind
import com.rm.parrotmetric.design.MirrorFeature
import com.rm.parrotmetric.design.MoveFeature
import com.rm.parrotmetric.design.PatternFeature
import com.rm.parrotmetric.design.PrimitiveFeature
import com.rm.parrotmetric.design.PrimitiveKind
import com.rm.parrotmetric.design.ShellFeature
import com.rm.parrotmetric.design.AlignFeature
import com.rm.parrotmetric.design.CanvasFeature
import com.rm.parrotmetric.design.CoilFeature
import com.rm.parrotmetric.design.LoftFeature
import com.rm.parrotmetric.design.LoftSection
import com.rm.parrotmetric.design.PathRef
import com.rm.parrotmetric.design.PipeFeature
import com.rm.parrotmetric.design.SweepFeature
import com.rm.parrotmetric.design.ThreadFeature
import com.rm.parrotmetric.design.SplitFeature
import com.rm.parrotmetric.design.ChamferFeature
import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.design.ExtrudeFeature
import com.rm.parrotmetric.design.Feature
import com.rm.parrotmetric.design.FilletFeature
import com.rm.parrotmetric.design.FilletKind
import com.rm.parrotmetric.design.OffsetFaceFeature
import com.rm.parrotmetric.design.DeleteFaceFeature
import com.rm.parrotmetric.design.MeshEdit
import com.rm.parrotmetric.design.RibFeature
import com.rm.parrotmetric.design.PatchFeature
import com.rm.parrotmetric.design.EmbossFeature
import com.rm.parrotmetric.design.JointFeature
import com.rm.parrotmetric.design.JointKind
import com.rm.parrotmetric.design.StitchFeature
import com.rm.parrotmetric.design.ThickenFeature
import com.rm.parrotmetric.design.MeshEditFeature
import com.rm.parrotmetric.design.ImportFeature
import com.rm.parrotmetric.design.SculptFeature
import com.rm.parrotmetric.design.Operation
import com.rm.parrotmetric.design.Parameter
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.RegionRef
import com.rm.parrotmetric.design.RevolveFeature
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.sketch.TextAlign
import com.rm.parrotmetric.sketch.Arc
import com.rm.parrotmetric.sketch.Circle
import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.sketch.Line
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchText
import com.rm.parrotmetric.sketch.ProfileCurve
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

    /** The design as a file; [withVersions] puts its named versions in too, which a version's own copy hasn't. */
    fun write(design: Design, title: String, withVersions: Boolean = true): String = Json.obj(
        "format" to "parrotmetric",
        "version" to VERSION,
        "title" to title,
        "marker" to design.marker,
        "features" to design.features.map { feature(it) },
        "bodies" to design.bodies.mapValues { (_, b) -> mapOf("name" to b.name, "component" to b.component, "hidden" to b.hidden, "colour" to b.colour) },
        "parameters" to design.parameters.map { mapOf("name" to it.name, "expression" to it.expression) },
        "expressions" to design.expressions.mapKeys { it.key.toString() },
        "suppressed" to design.suppressed.sorted(),
        "hiddenPlanes" to design.hiddenPlanes.sorted(),
        "configurations" to design.configurations.map { c ->
            mapOf("name" to c.name, "parameters" to c.parameters, "suppressed" to c.suppressed.sorted())
        },
        "configuration" to design.configuration,
        "drawing" to design.drawing?.let { DrawingFile.write(it) },
        // Only the font files some text uses.
        "fonts" to design.fonts.filterKeys { name ->
            design.features.any { f -> f is SketchFeature && f.sketch.texts.any { it.font == name } }
        }.mapValues { Base64.encode(it.value) },
        "versions" to if (withVersions) design.versions.map { mapOf("name" to it.name, "date" to it.date, "text" to it.text) } else null,
        // Only those of features still there.
        "hints" to design.hints.filterKeys { k -> design.feature(k.substringBefore(':').toIntOrNull() ?: -1) != null }
            .mapValues { it.value.toList() },
    ).toString()

    /** A file's title and how many steps it has, without reading the steps; null if it isn't a design file. */
    fun summary(text: String): Pair<String, Int>? {
        val root = try { Json.parse(text) as? Json.Obj } catch (e: Exception) { null } ?: return null
        if (root["format"] != Json.Str("parrotmetric")) return null
        return ((root["title"] as? Json.Str)?.value ?: "Untitled") to root.arr("features").size
    }

    /** Reads a file into the design. Returns its title. Throws IllegalArgumentException if it can't. */
    /** Reads a design file into [into], its title back. With [keepVersions] the named versions [into] has stay as they are. */
    fun read(text: String, into: Design, keepVersions: Boolean = false): String {
        val root = Json.parse(text) as? Json.Obj ?: throw IllegalArgumentException("Not a design file")
        if (root["format"] != Json.Str("parrotmetric")) throw IllegalArgumentException("Not a design file")
        if (root.int("version") > VERSION) throw IllegalArgumentException("This file was saved by a newer ParrotMetric")
        val features = root.arr("features").map { feature(it as Json.Obj) }
        val bodies = (root["bodies"] as? Json.Obj)?.fields?.mapValues { (_, v) ->
            v as Json.Obj
            Design.BodyInfo((v["name"] as? Json.Str)?.value, (v["component"] as? Json.Str)?.value, v.bool("hidden"), (v["colour"] as? Json.Num)?.value?.toInt())
        } ?: emptyMap()
        val parameters = root.arr("parameters").map { p -> p as Json.Obj; Parameter(p.str("name"), p.str("expression")) }
        val expressions = (root["expressions"] as? Json.Obj)?.fields?.map { (k, v) ->
            k.toInt() to (v as Json.Obj).fields.mapValues { (it.value as Json.Str).value }
        }?.toMap() ?: emptyMap()
        val suppressed = (root["suppressed"] as? Json.Arr)?.items?.map { (it as Json.Num).value.toInt() }?.toSet() ?: emptySet()
        val hints = (root["hints"] as? Json.Obj)?.fields?.mapValues { (_, v) -> (v as Json.Arr).items.map { (it as Json.Num).value }.toDoubleArray() }
            ?: emptyMap()
        val configurations = (root["configurations"] as? Json.Arr)?.items?.map { c ->
            c as Json.Obj
            Design.Configuration(
                c.str("name"),
                (c["parameters"] as? Json.Obj)?.fields?.mapValues { (it.value as Json.Str).value } ?: emptyMap(),
                (c["suppressed"] as? Json.Arr)?.items?.map { (it as Json.Num).value.toInt() }?.toSet() ?: emptySet(),
            )
        } ?: emptyList()
        into.load(
            features, root.int("marker"), bodies, parameters, expressions, suppressed, hints,
            configurations, (root["configuration"] as? Json.Str)?.value,
            (root["hiddenPlanes"] as? Json.Arr)?.items?.map { (it as Json.Num).value.toInt() }?.toSet() ?: emptySet(),
        )
        into.drawing = (root["drawing"] as? Json.Obj)?.let { DrawingFile.read(it) }
        if (!keepVersions) {
            into.versions.clear()
            for (v in root.arr("versions")) {
                v as Json.Obj
                into.versions += Design.Version(v.str("name"), v.str("date"), v.str("text"))
            }
        }
        into.fonts.clear()
        (root["fonts"] as? Json.Obj)?.fields?.forEach { (name, v) -> (v as? Json.Str)?.let { into.fonts[name] = Base64.decode(it.value) } }
        return (root["title"] as? Json.Str)?.value ?: "Untitled"
    }

    private fun vec(v: Vec3) = listOf(v.x, v.y, v.z)

    private fun path(p: PathRef): Map<String, Any?> = when (p) {
        is PathRef.Sketch -> mapOf("sketch" to p.sketchId)
        is PathRef.Edges -> mapOf("edges" to p.names)
    }

    private fun path(o: Json.Obj): PathRef = if (o["sketch"] is Json.Num) PathRef.Sketch(o.int("sketch")) else PathRef.Edges(strings(o.arr("edges")))

    private fun pointRef(r: com.rm.parrotmetric.design.PointRef): Map<String, Any?> = when (r) {
        is com.rm.parrotmetric.design.PointRef.Corner -> mapOf("corner" to r.name)
        is com.rm.parrotmetric.design.PointRef.CentreOf -> mapOf("centreOf" to r.edge)
        is com.rm.parrotmetric.design.PointRef.Construction -> mapOf("point" to r.featureId)
    }

    private fun pointRef(o: Json.Obj): com.rm.parrotmetric.design.PointRef = when {
        o["corner"] is Json.Str -> com.rm.parrotmetric.design.PointRef.Corner(o.str("corner"))
        o["centreOf"] is Json.Str -> com.rm.parrotmetric.design.PointRef.CentreOf(o.str("centreOf"))
        else -> com.rm.parrotmetric.design.PointRef.Construction(o.int("point"))
    }
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
            is MirrorFeature -> mapOf("type" to "mirror", "bodies" to f.bodies, "plane" to plane(f.plane), "join" to f.join, "features" to f.features)
            is OffsetFaceFeature -> mapOf("type" to "offsetFace", "faces" to f.faces, "distance" to f.distance)
            is DeleteFaceFeature -> mapOf("type" to "deleteFace", "faces" to f.faces)
            is JointFeature -> mapOf(
                "type" to "joint", "kind" to f.kind.name, "moving" to f.moving, "fixed" to f.fixed, "edge" to f.edge, "face" to f.face,
                "axisFeature" to f.axisFeature, "axis" to f.axis.name, "value" to f.value, "value2" to f.value2, "value3" to f.value3,
                "turnMin" to f.turnMin, "turnMax" to f.turnMax, "slideMin" to f.slideMin, "slideMax" to f.slideMax,
                "linkedTo" to f.linkedTo, "ratio" to f.ratio,
            )
            is EmbossFeature -> mapOf(
                "type" to "emboss", "sketch" to f.sketchId, "regions" to writeRegions(f.regions), "face" to f.face, "depth" to f.depth, "sink" to f.sink,
            )
            is PatchFeature -> mapOf("type" to "patch", "sketch" to f.sketchId, "regions" to writeRegions(f.regions), "edges" to f.edges)
            is StitchFeature -> mapOf("type" to "stitch", "bodies" to f.bodies)
            is ThickenFeature -> mapOf("type" to "thicken", "body" to f.body, "thickness" to f.thickness, "both" to f.both)
            is RibFeature -> mapOf("type" to "rib", "sketch" to f.sketchId, "thickness" to f.thickness, "flip" to f.flip, "web" to f.web)
            is MeshEditFeature -> mapOf("type" to "meshEdit", "body" to f.body, "kind" to f.kind.name, "size" to f.size, "steps" to f.steps)
            is PatternFeature -> mapOf(
                "type" to "pattern", "bodies" to f.bodies, "circular" to f.circular, "axis" to f.axis.name, "count" to f.count,
                "spacing" to f.spacing, "angle" to f.angle, "axis2" to f.axis2?.name, "count2" to f.count2, "spacing2" to f.spacing2, "join" to f.join,
                "axisFeature" to f.axisFeature, "path" to f.path?.let { path(it) }, "turn" to f.turn, "features" to f.features, "reverse" to f.reverse, "stagger" to f.stagger,
            )
            is CombineFeature -> mapOf(
                "type" to "combine", "target" to f.target, "tools" to f.tools, "operation" to f.operation.name, "keepTools" to f.keepTools,
            )
            is SplitFeature -> mapOf("type" to "split", "body" to f.body, "plane" to plane(f.plane), "keep" to f.keep, "tool" to f.tool)
            is SweepFeature -> mapOf(
                "type" to "sweep", "sketch" to f.sketchId, "regions" to writeRegions(f.regions), "path" to path(f.path), "operation" to f.operation.name,
            )
            is PipeFeature -> mapOf("type" to "pipe", "path" to path(f.path), "diameter" to f.diameter, "inner" to f.inner, "operation" to f.operation.name)
            is com.rm.parrotmetric.design.GearFeature -> mapOf(
                "type" to "gear", "plane" to plane(f.plane), "u" to f.u, "v" to f.v, "module" to f.module, "teeth" to f.teeth,
                "thickness" to f.thickness, "pressureAngle" to f.pressureAngle, "helix" to f.helix, "herringbone" to f.herringbone,
                "bore" to f.bore, "clearance" to f.clearance, "operation" to f.operation.name, "turn" to f.turn,
                "meshWith" to f.meshWith, "around" to f.around,
            )
            is CoilFeature -> mapOf(
                "type" to "coil", "plane" to plane(f.plane), "u" to f.u, "v" to f.v, "diameter" to f.diameter, "pitch" to f.pitch,
                "turns" to f.turns, "section" to f.section, "square" to f.square, "operation" to f.operation.name,
            )
            is ThreadFeature -> mapOf("type" to "thread", "face" to f.face, "pitch" to f.pitch, "clearance" to f.clearance, "symbol" to f.symbol)
            is com.rm.parrotmetric.design.MeshEraseFeature -> mapOf("type" to "meshErase", "body" to f.body, "spots" to f.spots)
            is com.rm.parrotmetric.design.SeparateFeature -> mapOf("type" to "separate", "body" to f.body)
            is com.rm.parrotmetric.design.LinkFeature -> mapOf(
                "type" to "link", "file" to f.file, "text" to f.text, "component" to f.component, "dx" to f.dx, "dy" to f.dy, "dz" to f.dz,
            )
            is com.rm.parrotmetric.design.FastenerFeature -> mapOf(
                "type" to "fastener", "kind" to f.kind.name, "size" to f.size, "length" to f.length, "hole" to f.hole, "otherEnd" to f.otherEnd,
                "plane" to plane(f.plane), "u" to f.u, "v" to f.v, "modelled" to f.modelled, "clearance" to f.clearance, "operation" to f.operation.name,
            )
            is com.rm.parrotmetric.design.SnapFitFeature -> f.sizes.let { z ->
                mapOf(
                    "type" to "snapFit", "sketch" to f.sketchId, "length" to z.length, "width" to z.width, "thickness" to z.thickness,
                    "overhang" to z.overhang, "catchHeight" to z.catchHeight, "gap" to z.gap, "catchIn" to f.catchIn,
                )
            }
            is com.rm.parrotmetric.design.LipFeature -> mapOf(
                "type" to "lip", "face" to f.face, "width" to f.width, "height" to f.height, "gap" to f.gap, "lid" to f.lid,
            )
            is CanvasFeature -> mapOf(
                "type" to "canvas", "plane" to plane(f.plane), "image" to Base64.encode(f.image), "aspect" to f.aspect, "width" to f.width,
                "u" to f.u, "v" to f.v, "angle" to f.angle, "opacity" to f.opacity,
            )
            is LoftFeature -> mapOf(
                "type" to "loft", "sections" to f.sections.map { mapOf("sketch" to it.sketchId, "region" to writeRegions(listOf(it.region)).first()) },
                "ruled" to f.ruled, "operation" to f.operation.name, "twist" to f.twist, "guide" to f.guide?.let { path(it) },
            )
            is AlignFeature -> mapOf(
                "type" to "align", "bodies" to f.bodies, "face" to f.face, "target" to plane(f.target),
                "sameWay" to f.sameWay, "centred" to f.centred, "gap" to f.gap,
            )
            is ConvertFeature -> mapOf("type" to "convert", "body" to f.body)
            is PlaneFeature -> mapOf(
                "type" to "plane", "kind" to f.kind.name, "base" to plane(f.base), "offset" to f.offset, "angle" to f.angle,
                "turnRoundY" to f.turnRoundY, "other" to f.other?.let { plane(it) },
                "points" to f.points.map { pointRef(it) }, "edges" to f.edges, "face" to f.face, "along" to f.along,
            )
            is com.rm.parrotmetric.design.PointFeature -> mapOf(
                "type" to "point", "x" to f.x, "y" to f.y, "z" to f.z, "kind" to f.kind.name,
                "ref" to f.ref?.let { pointRef(it) }, "planes" to f.planes.map { plane(it) },
            )
            is AxisFeature -> mapOf(
                "type" to "axis", "x" to f.x, "y" to f.y, "z" to f.z, "along" to f.along.name, "kind" to f.kind.name,
                "edge" to f.edge, "face" to f.face, "points" to f.points.map { pointRef(it) },
            )
            is MoveFeature -> mapOf(
                "type" to "move", "bodies" to f.bodies, "dx" to f.dx, "dy" to f.dy, "dz" to f.dz, "axis" to f.axis.name, "angle" to f.angle, "copy" to f.copy,
                "sx" to f.sx, "sy" to f.sy, "sz" to f.sz,
            )
            is ExtrudeFeature -> mapOf(
                "type" to "extrude", "sketch" to f.sketchId, "regions" to writeRegions(f.regions),
                "forward" to f.forward, "back" to f.back, "operation" to f.operation.name,
                "taper" to f.taper, "upTo" to f.upTo?.let { plane(it) },
                "throughAll" to f.throughAll, "offset" to f.offset, "thin" to f.thin,
            )
            is PrimitiveFeature -> mapOf(
                "type" to "primitive", "kind" to f.kind.name, "plane" to plane(f.plane), "u" to f.u, "v" to f.v,
                "a" to f.a, "b" to f.b, "c" to f.c, "operation" to f.operation.name, "flip" to f.flip,
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
            is FilletFeature -> mapOf("type" to "fillet", "edges" to f.edges, "radius" to f.radius, "kind" to f.kind.name, "second" to f.second)
            is ChamferFeature -> mapOf(
                "type" to "chamfer", "edges" to f.edges, "distance" to f.distance, "kind" to f.kind.name, "second" to f.second, "flip" to f.flip,
            )
            is ImportFeature -> mapOf("type" to "import", "format" to f.format, "data" to Base64.encode(f.data))
            is SculptFeature -> mapOf("type" to "sculpt", "body" to f.body, "mesh" to Base64.encode(f.mesh))
            else -> throw IllegalArgumentException("Can't save ${f.name}")
        }
        return Json.of(base + extra + (if (f.only.isEmpty()) emptyMap() else mapOf("only" to f.only)))
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
                strings(o.arr("only")),
            )
            "mirror" -> MirrorFeature(id, name, strings(o.arr("bodies")), plane(o.obj("plane")), o.bool("join"), ints(o["features"]))
            "offsetFace" -> OffsetFaceFeature(id, name, strings(o.arr("faces")), o.num("distance"))
            "deleteFace" -> DeleteFaceFeature(id, name, strings(o.arr("faces")))
            "joint" -> JointFeature(
                id, name, JointKind.valueOf(o.str("kind")), o.str("moving"), (o["fixed"] as? Json.Str)?.value, (o["edge"] as? Json.Str)?.value,
                (o["face"] as? Json.Str)?.value, (o["axisFeature"] as? Json.Num)?.value?.toInt(), Axis3.valueOf(o.str("axis")), o.num("value"), o.num("value2"),
                o.numOr("value3", 0.0), (o["turnMin"] as? Json.Num)?.value, (o["turnMax"] as? Json.Num)?.value,
                (o["slideMin"] as? Json.Num)?.value, (o["slideMax"] as? Json.Num)?.value,
                (o["linkedTo"] as? Json.Num)?.value?.toInt(), (o["ratio"] as? Json.Num)?.value,
            )
            "emboss" -> EmbossFeature(id, name, o.int("sketch"), readRegions(o.arr("regions")), o.str("face"), o.num("depth"), o.bool("sink"))
            "patch" -> PatchFeature(id, name, (o["sketch"] as? Json.Num)?.value?.toInt(), readRegions(o.arr("regions")), strings(o.arr("edges")))
            "stitch" -> StitchFeature(id, name, strings(o.arr("bodies")))
            "thicken" -> ThickenFeature(id, name, o.str("body"), o.num("thickness"), o.bool("both"))
            "rib" -> RibFeature(id, name, o.int("sketch"), o.num("thickness"), o.bool("flip"), o.bool("web"))
            "meshEdit" -> MeshEditFeature(id, name, o.str("body"), MeshEdit.valueOf(o.str("kind")), o.num("size"), o.int("steps"))
            "pattern" -> PatternFeature(
                id, name, strings(o.arr("bodies")), o.bool("circular"), Axis3.valueOf(o.str("axis")), o.int("count"), o.num("spacing"), o.num("angle"),
                (o["axis2"] as? Json.Str)?.let { Axis3.valueOf(it.value) }, o.int("count2"), o.num("spacing2"), o.bool("join"),
                (o["axisFeature"] as? Json.Num)?.value?.toInt(), (o["path"] as? Json.Obj)?.let { path(it) }, (o["turn"] as? Json.Bool)?.value ?: false,
                ints(o["features"]), (o["reverse"] as? Json.Bool)?.value ?: false, (o["stagger"] as? Json.Bool)?.value ?: false,
            )
            "combine" -> CombineFeature(id, name, o.str("target"), strings(o.arr("tools")), Operation.valueOf(o.str("operation")), o.bool("keepTools"))
            "split" -> SplitFeature(id, name, o.str("body"), plane(o.obj("plane")), (o["keep"] as? Json.Num)?.value?.toInt() ?: 0, (o["tool"] as? Json.Str)?.value)
            "sweep" -> SweepFeature(id, name, o.int("sketch"), readRegions(o.arr("regions")), path(o.obj("path")), Operation.valueOf(o.str("operation")))
            "pipe" -> PipeFeature(id, name, path(o.obj("path")), o.num("diameter"), o.num("inner"), Operation.valueOf(o.str("operation")))
            "gear" -> com.rm.parrotmetric.design.GearFeature(
                id, name, plane(o.obj("plane")), o.num("u"), o.num("v"), o.num("module"), o.int("teeth"), o.num("thickness"),
                o.num("pressureAngle"), o.num("helix"), o.bool("herringbone"), o.num("bore"), o.num("clearance"), Operation.valueOf(o.str("operation")),
                o.numOr("turn", 0.0), (o["meshWith"] as? Json.Num)?.value?.toInt(), o.numOr("around", 0.0),
            )
            "coil" -> CoilFeature(
                id, name, plane(o.obj("plane")), o.num("u"), o.num("v"), o.num("diameter"), o.num("pitch"), o.num("turns"), o.num("section"),
                o.bool("square"), Operation.valueOf(o.str("operation")),
            )
            "thread" -> ThreadFeature(id, name, o.str("face"), o.num("pitch"), o.numOr("clearance", 0.0), o.bool("symbol"))
            "meshErase" -> com.rm.parrotmetric.design.MeshEraseFeature(id, name, o.str("body"), o.arr("spots").map { (it as Json.Num).value })
            "separate" -> com.rm.parrotmetric.design.SeparateFeature(id, name, o.str("body"))
            "link" -> com.rm.parrotmetric.design.LinkFeature(id, name, o.str("file"), o.str("text"), o.str("component"), o.num("dx"), o.num("dy"), o.num("dz"))
            "fastener" -> com.rm.parrotmetric.design.FastenerFeature(
                id, name, com.rm.parrotmetric.design.FastenerKind.valueOf(o.str("kind")), o.str("size"), o.num("length"), (o["hole"] as? Json.Str)?.value,
                o.bool("otherEnd"), plane(o.obj("plane")), o.num("u"), o.num("v"), o.bool("modelled"), o.num("clearance"), Operation.valueOf(o.str("operation")),
            )
            "snapFit" -> com.rm.parrotmetric.design.SnapFitFeature(
                id, name, o.int("sketch"),
                com.rm.parrotmetric.design.SnapFitSizes(o.num("length"), o.num("width"), o.num("thickness"), o.num("overhang"), o.num("catchHeight"), o.num("gap")),
                (o["catchIn"] as? Json.Str)?.value,
            )
            "lip" -> com.rm.parrotmetric.design.LipFeature(
                id, name, o.str("face"), o.num("width"), o.num("height"), o.num("gap"), (o["lid"] as? Json.Str)?.value,
            )
            "canvas" -> CanvasFeature(
                id, name, plane(o.obj("plane")), Base64.decode(o.str("image")), o.num("aspect"), o.num("width"), o.num("u"), o.num("v"),
                o.num("angle"), o.num("opacity"),
            )
            "loft" -> LoftFeature(
                id, name, o.arr("sections").map { s -> s as Json.Obj; LoftSection(s.int("sketch"), readRegions(listOf(s.obj("region"))).first()) },
                o.bool("ruled"), Operation.valueOf(o.str("operation")), o.numOr("twist", 0.0), (o["guide"] as? Json.Obj)?.let { path(it) },
            )
            "align" -> AlignFeature(
                id, name, strings(o.arr("bodies")), o.str("face"), plane(o.obj("target")), o.bool("sameWay"), o.bool("centred"), o.numOr("gap", 0.0),
            )
            "convert" -> ConvertFeature(id, name, o.str("body"))
            "plane" -> PlaneFeature(
                id, name, PlaneFeature.Kind.valueOf(o.str("kind")), plane(o.obj("base")), o.num("offset"), o.num("angle"),
                o.bool("turnRoundY"), (o["other"] as? Json.Obj)?.let { plane(it) },
                o.arr("points").map { pointRef(it as Json.Obj) }, strings(o.arr("edges")), (o["face"] as? Json.Str)?.value, o.numOr("along", 0.0),
            )
            "point" -> com.rm.parrotmetric.design.PointFeature(
                id, name, o.num("x"), o.num("y"), o.num("z"),
                (o["kind"] as? Json.Str)?.let { com.rm.parrotmetric.design.PointFeature.Kind.valueOf(it.value) } ?: com.rm.parrotmetric.design.PointFeature.Kind.Fixed,
                (o["ref"] as? Json.Obj)?.let { pointRef(it) }, o.arr("planes").map { plane(it as Json.Obj) },
            )
            "axis" -> AxisFeature(
                id, name, o.num("x"), o.num("y"), o.num("z"), Axis3.valueOf(o.str("along")),
                (o["kind"] as? Json.Str)?.let { AxisFeature.Kind.valueOf(it.value) } ?: AxisFeature.Kind.Fixed,
                (o["edge"] as? Json.Str)?.value, (o["face"] as? Json.Str)?.value, o.arr("points").map { pointRef(it as Json.Obj) },
            )
            "move" -> MoveFeature(
                id, name, strings(o.arr("bodies")), o.num("dx"), o.num("dy"), o.num("dz"), Axis3.valueOf(o.str("axis")), o.num("angle"), o.bool("copy"),
                o.numOr("sx", 1.0), o.numOr("sy", 1.0), o.numOr("sz", 1.0),
            )
            "extrude" -> ExtrudeFeature(
                id, name, o.int("sketch"), readRegions(o.arr("regions")), o.num("forward"), o.num("back"), Operation.valueOf(o.str("operation")),
                o.numOr("taper", 0.0), (o["upTo"] as? Json.Obj)?.let { plane(it) },
                o.bool("throughAll"), o.numOr("offset", 0.0), o.numOr("thin", 0.0), strings(o.arr("only")),
            )
            "primitive" -> PrimitiveFeature(
                id, name, PrimitiveKind.valueOf(o.str("kind")), plane(o["plane"] as Json.Obj), o.num("u"), o.num("v"),
                o.num("a"), o.num("b"), o.num("c"), Operation.valueOf(o.str("operation")), o.bool("flip"), strings(o.arr("only")),
            )
            "revolve" -> {
                val axis = when (val a = o.str("axis")) {
                    "x" -> AxisRef.SketchX
                    "y" -> AxisRef.SketchY
                    else -> AxisRef.SketchLine(a.removePrefix("line ").toInt())
                }
                RevolveFeature(id, name, o.int("sketch"), readRegions(o.arr("regions")), axis, o.num("angle"), Operation.valueOf(o.str("operation")), strings(o.arr("only")))
            }
            "fillet" -> FilletFeature(
                id, name, o.arr("edges").map { (it as Json.Str).value }, o.num("radius"),
                (o["kind"] as? Json.Str)?.let { FilletKind.valueOf(it.value) } ?: FilletKind.Constant, (o["second"] as? Json.Num)?.value ?: 0.0,
            )
            "chamfer" -> ChamferFeature(
                id, name, o.arr("edges").map { (it as Json.Str).value }, o.num("distance"),
                (o["kind"] as? Json.Str)?.let { com.rm.parrotmetric.design.ChamferKind.valueOf(it.value) } ?: com.rm.parrotmetric.design.ChamferKind.Equal,
                o.numOr("second", 0.0), (o["flip"] as? Json.Bool)?.value ?: false,
            )
            "import" -> ImportFeature(id, name, Base64.decode(o.str("data")), o.int("format"))
            "sculpt" -> SculptFeature(id, name, (o["body"] as? Json.Str)?.value, Base64.decode(o.str("mesh")))
            else -> throw IllegalArgumentException("This file has a step this version can't read: $type")
        }
    }

    private fun strings(j: List<Json>) = j.map { (it as Json.Str).value }
    private fun ints(j: Json?) = (j as? Json.Arr)?.items?.map { (it as Json.Num).value.toInt() } ?: emptyList()

    private fun plane(p: PlaneRef): Map<String, Any?> = when (p) {
        is PlaneRef.Fixed -> mapOf("name" to p.plane.name, "origin" to vec(p.plane.origin), "x" to vec(p.plane.x), "y" to vec(p.plane.y))
        is PlaneRef.OnFace -> mapOf("face" to p.face, "x" to vec(p.x), "fromOrigin" to p.fromOrigin)
        is PlaneRef.Construction -> mapOf("construction" to p.featureId)
    }

    private fun plane(p: Json.Obj): PlaneRef = when {
        p["construction"] != null -> PlaneRef.Construction(p.int("construction"))
        p["face"] != null -> PlaneRef.OnFace(p.str("face"), vec(p["x"]), p.bool("fromOrigin"))
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
                is Spline -> mapOf(
                    "type" to "spline", "id" to c.id, "through" to c.through.map { it.id }, "construction" to c.construction,
                    "shape" to c.shape.name, "rho" to c.rho,
                )
            }
        },
        "constraints" to s.constraints.mapNotNull { constraint(it) },
        "links" to s.links.map { l ->
            mapOf("section" to l.section, "bodies" to l.bodies, "points" to l.points.map { it.id }, "circles" to l.circles.map { it.id }, "middle" to l.middle)
        },
        "texts" to s.texts.map { t ->
            mapOf(
                "id" to t.id, "anchor" to t.anchor.id, "text" to t.text, "height" to t.height, "bold" to t.bold, "angle" to t.angle, "align" to t.align.name, "font" to t.font,
                "outline" to t.outline.map { listOf(it.kind.ordinal, it.x1, it.y1, it.x2, it.y2, it.cx1, it.cy1, it.cx2, it.cy2) },
            )
        },
    )

    private fun constraint(c: Constraint): Map<String, Any?>? = when (c) {
        is Constraint.ArcRadius -> null
        is Constraint.EllipseAxes -> null
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
        is Constraint.Collinear -> mapOf("type" to "collinear", "l1" to c.l1.id, "l2" to c.l2.id)
        is Constraint.TangentJoin -> mapOf("type" to "tangentJoin", "c1" to c.c1.id, "c2" to c.c2.id, "at" to c.at.id)
        is Constraint.OnLine -> mapOf("type" to "onLine", "p" to c.p.id, "line" to c.line.id)
        is Constraint.OnCircle -> mapOf("type" to "onCircle", "p" to c.p.id, "curve" to c.curve.id)
        is Constraint.TangentLine -> mapOf("type" to "tangentLine", "line" to c.line.id, "curve" to c.curve.id)
        is Constraint.TangentCircles -> mapOf("type" to "tangentCircles", "c1" to c.c1.id, "c2" to c.c2.id, "inside" to c.inside)
        is Constraint.Symmetric -> mapOf("type" to "symmetric", "p" to c.p.id, "q" to c.q.id, "line" to c.line.id)
        is Constraint.SameStep -> mapOf("type" to "sameStep", "a" to c.a.id, "b" to c.b.id, "c" to c.c.id, "d" to c.d.id)
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
                "spline" -> s.loadSpline(
                    id, c.arr("through").map { s.point((it as Json.Num).value.toInt()) ?: throw IllegalArgumentException("A sketch refers to a missing point") }, cons,
                    (c["shape"] as? Json.Str)?.let { Spline.Shape.valueOf(it.value) } ?: Spline.Shape.Through, (c["rho"] as? Json.Num)?.value ?: 0.5,
                )
            }
        }
        for (j in o.arr("texts")) {
            val t = j as Json.Obj
            val outline = t.arr("outline").map { r ->
                val n = (r as Json.Arr).items.map { (it as Json.Num).value }
                ProfileCurve(ProfileCurve.Kind.entries[n[0].toInt()], 0, n[1], n[2], n[3], n[4], cx1 = n[5], cy1 = n[6], cx2 = n[7], cy2 = n[8])
            }
            s.loadText(SketchText(t.int("id"), pt(t, "anchor"), t.str("text"), t.num("height"), t.bool("bold"), t.num("angle"), outline,
                TextAlign.entries.firstOrNull { it.name == (t["align"] as? Json.Str)?.value } ?: TextAlign.Left,
                (t["font"] as? Json.Str)?.value ?: com.rm.parrotmetric.sketch.builtInFonts.first()))
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
                "collinear" -> Constraint.Collinear(line(c, "l1"), line(c, "l2"))
                "tangentJoin" -> Constraint.TangentJoin(curve(c, "c1"), curve(c, "c2"), pt(c, "at"))
                "onLine" -> Constraint.OnLine(pt(c, "p"), line(c, "line"))
                "onCircle" -> Constraint.OnCircle(pt(c, "p"), curve(c, "curve"))
                "tangentLine" -> Constraint.TangentLine(line(c, "line"), curve(c, "curve"))
                "tangentCircles" -> Constraint.TangentCircles(curve(c, "c1"), curve(c, "c2"), c.bool("inside"))
                "symmetric" -> Constraint.Symmetric(pt(c, "p"), pt(c, "q"), line(c, "line"))
                "sameStep" -> Constraint.SameStep(pt(c, "a"), pt(c, "b"), pt(c, "c"), pt(c, "d"))
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
        for (j in o.arr("links")) {
            val l = j as Json.Obj
            val points = ints(l["points"]).mapNotNull { s.point(it) }
            val circles = ints(l["circles"]).mapNotNull { s.curve(it) as? com.rm.parrotmetric.sketch.Circle }
            s.links += com.rm.parrotmetric.sketch.ProjectionLink(l.bool("section"), strings(l.arr("bodies")), points, circles, l.bool("middle"))
        }
        return s
    }
}
