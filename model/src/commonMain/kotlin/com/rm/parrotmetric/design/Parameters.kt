package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Constraint
import com.rm.parrotmetric.sketch.Expression
import kotlin.math.PI

/** A named value: its expression can use the parameters above it. Lengths in mm, angles in degrees. */
data class Parameter(val name: String, val expression: String)

/**
 * Putting the parameters into the design before it's built. Features keep
 * plain numbers; a field typed as an expression is kept in
 * [Design.expressions] and its number worked out again here, so changing a
 * parameter changes everything that uses it.
 */
object Parametrics {
    /** The parameters' values, in order. Ones that can't be worked out are left out. */
    fun values(parameters: List<Parameter>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (p in parameters) Expression.evaluate(p.expression, out)?.let { out[p.name] = it }
        return out
    }

    /**
     * The features with their expression fields worked out. Sketch
     * dimensions with expressions are set and solved in place.
     */
    fun apply(design: Design, features: List<Feature>): List<Feature> {
        val names = values(design.parameters)
        return features.map { f ->
            if (f is SketchFeature) {
                var changed = false
                for (c in f.sketch.constraints) {
                    if (c is Constraint.Dimension) {
                        val text = c.expression ?: continue
                        var v = Expression.evaluate(text, names) ?: continue
                        if (c is Constraint.Angle) v = (if (c.value < 0) -v else v) * PI / 180
                        if (v != c.value) { c.value = v; changed = true }
                    }
                }
                if (changed) f.sketch.solve()
                return@map f
            }
            val exprs = design.expressions[f.id] ?: return@map f
            var out = f
            for ((field, text) in exprs) {
                val v = Expression.evaluate(text, names) ?: continue
                out = withValue(out, field, v)
            }
            out
        }
    }

    /** A feature with one field set. Angles come in degrees. */
    fun withValue(f: Feature, field: String, v: Double): Feature {
        val rad = v * PI / 180
        return when (f) {
            is ExtrudeFeature -> when (field) {
                "forward" -> f.copy(forward = v)
                "back" -> f.copy(back = v)
                "both" -> f.copy(forward = v / 2, back = v / 2)
                "taper" -> f.copy(taper = rad)
                "offset" -> f.copy(offset = v)
                "thin" -> f.copy(thin = v)
                else -> f
            }
            is PrimitiveFeature -> when (field) {
                "a" -> f.copy(a = v)
                "b" -> f.copy(b = v)
                "c" -> f.copy(c = v)
                "u" -> f.copy(u = v)
                "v" -> f.copy(v = v)
                else -> f
            }
            is RevolveFeature -> if (field == "angle") f.copy(angle = rad) else f
            is LoftFeature -> if (field == "twist") f.copy(twist = rad) else f
            is FilletFeature -> when (field) {
                "size" -> f.copy(radius = v)
                "second" -> f.copy(second = v)
                else -> f
            }
            is OffsetFaceFeature -> if (field == "size") f.copy(distance = v) else f
            is RibFeature -> if (field == "size") f.copy(thickness = v) else f
            is ThickenFeature -> if (field == "size") f.copy(thickness = v) else f
            is EmbossFeature -> if (field == "size") f.copy(depth = v) else f
            is JointFeature -> when (field) {
                "value" -> f.copy(value = if (f.kind == JointKind.Slide) v else rad)
                "value2" -> f.copy(value2 = if (f.kind == JointKind.Ball) rad else v)
                "value3" -> f.copy(value3 = if (f.kind == JointKind.Ball) rad else v)
                else -> f
            }
            is MeshEditFeature -> when (field) {
                "size" -> f.copy(size = v)
                "steps" -> f.copy(steps = v.toInt())
                else -> f
            }
            is ChamferFeature -> when (field) {
                "size" -> f.copy(distance = v)
                "second" -> f.copy(second = if (f.kind == ChamferKind.DistanceAngle) rad else v)
                else -> f
            }
            is ShellFeature -> if (field == "size") f.copy(thickness = v) else f
            is DraftFeature -> if (field == "size") f.copy(angle = rad) else f
            is HoleFeature -> when (field) {
                "diameter" -> f.copy(diameter = v)
                "depth" -> f.copy(depth = v)
                "topDiameter" -> f.copy(topDiameter = v)
                "topDepth" -> f.copy(topDepth = v)
                else -> f
            }
            is PatternFeature -> when (field) {
                "count" -> f.copy(count = v.toInt())
                "spacing" -> f.copy(spacing = v)
                "angle" -> f.copy(angle = rad)
                "count2" -> f.copy(count2 = v.toInt())
                "spacing2" -> f.copy(spacing2 = v)
                else -> f
            }
            is MoveFeature -> when (field) {
                "dx" -> f.copy(dx = v)
                "dy" -> f.copy(dy = v)
                "dz" -> f.copy(dz = v)
                "angle" -> f.copy(angle = rad)
                "sx" -> f.copy(sx = v)
                "sy" -> f.copy(sy = v)
                "sz" -> f.copy(sz = v)
                "scale" -> f.copy(sx = v, sy = v, sz = v)
                else -> f
            }
            is PlaneFeature -> when (field) {
                "offset" -> f.copy(offset = v)
                "angle" -> f.copy(angle = rad)
                "along" -> f.copy(along = v / 100)
                else -> f
            }
            is AlignFeature -> if (field == "gap") f.copy(gap = v) else f
            is PipeFeature -> when (field) {
                "diameter" -> f.copy(diameter = v)
                "inner" -> f.copy(inner = v)
                else -> f
            }
            is GearFeature -> when (field) {
                "module" -> f.copy(module = v)
                "teeth" -> f.copy(teeth = v.toInt())
                "thickness" -> f.copy(thickness = v)
                "helix" -> f.copy(helix = rad)
                "pressure" -> f.copy(pressureAngle = rad)
                "bore" -> f.copy(bore = v)
                "clearance" -> f.copy(clearance = v)
                "turn" -> f.copy(turn = rad)
                "around" -> f.copy(around = rad)
                "u" -> f.copy(u = v)
                "v" -> f.copy(v = v)
                else -> f
            }
            is CoilFeature -> when (field) {
                "diameter" -> f.copy(diameter = v)
                "pitch" -> f.copy(pitch = v)
                "turns" -> f.copy(turns = v)
                "section" -> f.copy(section = v)
                "u" -> f.copy(u = v)
                "v" -> f.copy(v = v)
                else -> f
            }
            is LinkFeature -> when (field) {
                "dx" -> f.copy(dx = v)
                "dy" -> f.copy(dy = v)
                "dz" -> f.copy(dz = v)
                else -> f
            }
            is FastenerFeature -> when (field) {
                "length" -> f.copy(length = v)
                "clearance" -> f.copy(clearance = v)
                "u" -> f.copy(u = v)
                "v" -> f.copy(v = v)
                else -> f
            }
            is ThreadFeature -> when (field) {
                "pitch" -> f.copy(pitch = v)
                "clearance" -> f.copy(clearance = v)
                else -> f
            }
            is SnapFitFeature -> f.copy(sizes = f.sizes.let { z ->
                when (field) {
                    "length" -> z.copy(length = v)
                    "width" -> z.copy(width = v)
                    "thickness" -> z.copy(thickness = v)
                    "overhang" -> z.copy(overhang = v)
                    "catchHeight" -> z.copy(catchHeight = v)
                    "gap" -> z.copy(gap = v)
                    else -> z
                }
            })
            is LipFeature -> when (field) {
                "width" -> f.copy(width = v)
                "height" -> f.copy(height = v)
                "gap" -> f.copy(gap = v)
                else -> f
            }
            is CanvasFeature -> when (field) {
                "width" -> f.copy(width = v)
                "u" -> f.copy(u = v)
                "v" -> f.copy(v = v)
                "angle" -> f.copy(angle = rad)
                else -> f
            }
            is PointFeature -> when (field) {
                "x" -> f.copy(x = v)
                "y" -> f.copy(y = v)
                "z" -> f.copy(z = v)
                else -> f
            }
            is AxisFeature -> when (field) {
                "x" -> f.copy(x = v)
                "y" -> f.copy(y = v)
                "z" -> f.copy(z = v)
                else -> f
            }
            else -> f
        }
    }
}
