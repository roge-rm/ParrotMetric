package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Sketch

/**
 * The history: features in order, and the rollback marker. Features after
 * the marker are kept but not built, and new ones go in at the marker.
 */
class Design {
    private val list = mutableListOf<Feature>()

    /**
     * How each body is shown and grouped, by the label the history gives it
     * ("Body 3"): the name it's shown by, its component, and whether it's
     * hidden. Bodies not in here have their label as name and no component.
     */
    val bodies = mutableMapOf<String, BodyInfo>()

    data class BodyInfo(val name: String? = null, val component: String? = null, val hidden: Boolean = false)

    fun info(label: String) = bodies[label] ?: BodyInfo()

    /** The parameters, in order; each can use those above it. */
    val parameters = mutableListOf<Parameter>()

    /** Fields typed as expressions, by feature id and then field name (see [Parametrics.withValue]). */
    val expressions = mutableMapOf<Int, Map<String, String>>()
    fun nameOf(label: String) = info(label).name ?: label
    val features: List<Feature> get() = list

    /** How many features are built; the marker sits after this many. */
    var marker: Int = 0
        private set

    private var nextId = 1

    fun newId() = nextId++

    /** Puts features read from a file in place of what's here. */
    fun load(
        features: List<Feature>, marker: Int, bodyInfo: Map<String, BodyInfo> = emptyMap(),
        parameters: List<Parameter> = emptyList(), expressions: Map<Int, Map<String, String>> = emptyMap(),
    ) {
        bodies.clear()
        bodies.putAll(bodyInfo)
        this.parameters.clear()
        this.parameters += parameters
        this.expressions.clear()
        this.expressions.putAll(expressions)
        list.clear()
        list += features
        this.marker = marker.coerceIn(0, features.size)
        nextId = (features.maxOfOrNull { it.id } ?: 0) + 1
    }

    /** The features before the marker: what's built. */
    val active: List<Feature> get() = list.subList(0, marker)

    fun add(f: Feature) {
        list.add(marker, f)
        marker++
    }

    fun replace(f: Feature) {
        val i = list.indexOfFirst { it.id == f.id }
        if (i >= 0) list[i] = f
    }

    /** Takes a feature out; later features that use it fail on rebuild, saying why. */
    fun remove(id: Int) {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list.removeAt(i)
        if (i < marker) marker--
    }

    fun moveMarker(to: Int) {
        marker = to.coerceIn(0, list.size)
    }

    fun feature(id: Int) = list.firstOrNull { it.id == id }
    fun indexOf(id: Int) = list.indexOfFirst { it.id == id }

    /** Everything about the design as it is, for undo. */
    class Snapshot internal constructor(
        internal val features: List<Feature>,
        internal val marker: Int,
        internal val nextId: Int,
        internal val sketches: Map<Sketch, Sketch.Snapshot>,
        internal val bodies: Map<String, BodyInfo>,
        internal val parameters: List<Parameter>,
        internal val expressions: Map<Int, Map<String, String>>,
        internal val dimensionExpressions: Map<com.rm.parrotmetric.sketch.Constraint.Dimension, String?>,
    )

    fun snapshot() = Snapshot(
        list.toList(), marker, nextId,
        list.filterIsInstance<SketchFeature>().associate { it.sketch to it.sketch.snapshot() },
        bodies.toMap(),
        parameters.toList(),
        expressions.toMap(),
        list.filterIsInstance<SketchFeature>().flatMap { f -> f.sketch.constraints.filterIsInstance<com.rm.parrotmetric.sketch.Constraint.Dimension>() }
            .associateWith { it.expression },
    )

    fun restore(s: Snapshot) {
        list.clear()
        list += s.features
        marker = s.marker
        nextId = s.nextId
        for ((sketch, snap) in s.sketches) sketch.restore(snap)
        bodies.clear()
        bodies.putAll(s.bodies)
        parameters.clear()
        parameters += s.parameters
        expressions.clear()
        expressions.putAll(s.expressions)
        for ((d, e) in s.dimensionExpressions) d.expression = e
    }
}
