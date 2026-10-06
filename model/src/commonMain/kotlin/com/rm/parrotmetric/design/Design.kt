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

    /** [colour] is 0xRRGGBB, or null for the usual grey. */
    data class BodyInfo(val name: String? = null, val component: String? = null, val hidden: Boolean = false, val colour: Int? = null)

    fun info(label: String) = bodies[label] ?: BodyInfo()

    /** The parameters, in order; each can use those above it. */
    val parameters = mutableListOf<Parameter>()

    /** Fields typed as expressions, by feature id and then field name (see [Parametrics.withValue]). */
    val expressions = mutableMapOf<Int, Map<String, String>>()

    /** Features turned off: kept in the history but not built. */
    val suppressed = mutableSetOf<Int>()

    /** Construction planes hidden from view, by feature id. */
    val hiddenPlanes = mutableSetOf<Int>()

    /** A named version of the design: its parameters' expressions and which features are off. */
    data class Configuration(val name: String, val parameters: Map<String, String>, val suppressed: Set<Int>)

    /** The design's drawing sheet, if it has one. */
    var drawing: com.rm.parrotmetric.drawing.Drawing? = null

    /** The design's configurations, and the one in use, if any. */
    val configurations = mutableListOf<Configuration>()
    var configuration: String? = null

    /** Whether the parameters or features turned off differ from what the configuration in use keeps. */
    val configurationChanged: Boolean get() {
        val c = configurations.firstOrNull { it.name == configuration } ?: return false
        return c.parameters != parameters.associate { it.name to it.expression } || c.suppressed != suppressed.toSet()
    }

    /** Keeps the parameters and features turned off as they are now, as [name], and uses it. */
    fun saveConfiguration(name: String) {
        val c = Configuration(name, parameters.associate { it.name to it.expression }, suppressed.toSet())
        val i = configurations.indexOfFirst { it.name == name }
        if (i >= 0) configurations[i] = c else configurations += c
        configuration = name
    }

    /** Puts a configuration's parameter values and features turned off in place. Parameters it doesn't have keep theirs. */
    fun useConfiguration(name: String) {
        val c = configurations.firstOrNull { it.name == name } ?: return
        for (i in parameters.indices) c.parameters[parameters[i].name]?.let { parameters[i] = parameters[i].copy(expression = it) }
        suppressed.clear()
        suppressed += c.suppressed.filter { id -> list.any { it.id == id } }
        configuration = name
    }

    fun removeConfiguration(name: String) {
        configurations.removeAll { it.name == name }
        if (configuration == name) configuration = null
    }

    /** Where the faces and edges features use last were, to find them again (see Built.hints). */
    val hints = mutableMapOf<String, DoubleArray>()
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
        suppressed: Set<Int> = emptySet(), hints: Map<String, DoubleArray> = emptyMap(),
        configurations: List<Configuration> = emptyList(), configuration: String? = null,
        hiddenPlanes: Set<Int> = emptySet(),
    ) {
        drawing = null
        this.hiddenPlanes.clear()
        this.hiddenPlanes += hiddenPlanes
        this.configurations.clear()
        this.configurations += configurations
        this.configuration = configuration?.takeIf { n -> configurations.any { it.name == n } }
        this.suppressed.clear()
        this.suppressed += suppressed
        this.hints.clear()
        this.hints.putAll(hints)
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

    /** The features before the marker: what's built, unless turned off. */
    val active: List<Feature> get() = list.subList(0, marker)

    /** The active features that are on. */
    val built: List<Feature> get() = active.filter { it.id !in suppressed }

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
        internal val suppressed: Set<Int>,
        internal val configurations: List<Configuration> = emptyList(),
        internal val configuration: String? = null,
        internal val hiddenPlanes: Set<Int> = emptySet(),
        internal val drawing: com.rm.parrotmetric.drawing.Drawing? = null,
    )

    fun snapshot() = Snapshot(
        list.toList(), marker, nextId,
        list.filterIsInstance<SketchFeature>().associate { it.sketch to it.sketch.snapshot() },
        bodies.toMap(),
        parameters.toList(),
        expressions.toMap(),
        list.filterIsInstance<SketchFeature>().flatMap { f -> f.sketch.constraints.filterIsInstance<com.rm.parrotmetric.sketch.Constraint.Dimension>() }
            .associateWith { it.expression },
        suppressed.toSet(),
        configurations.toList(),
        configuration,
        hiddenPlanes.toSet(),
        drawing,
    )

    fun restore(s: Snapshot) {
        list.clear()
        list += s.features
        marker = s.marker
        nextId = s.nextId
        for ((sketch, snap) in s.sketches) sketch.restore(snap)
        bodies.clear()
        bodies.putAll(s.bodies)
        hiddenPlanes.clear()
        hiddenPlanes += s.hiddenPlanes
        parameters.clear()
        parameters += s.parameters
        expressions.clear()
        expressions.putAll(s.expressions)
        for ((d, e) in s.dimensionExpressions) d.expression = e
        suppressed.clear()
        suppressed += s.suppressed
        configurations.clear()
        configurations += s.configurations
        configuration = s.configuration
        drawing = s.drawing
    }
}
