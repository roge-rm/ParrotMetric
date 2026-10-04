package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Sketch

/**
 * The history: features in order, and the rollback marker. Features after
 * the marker are kept but not built, and new ones go in at the marker.
 */
class Design {
    private val list = mutableListOf<Feature>()
    val features: List<Feature> get() = list

    /** How many features are built; the marker sits after this many. */
    var marker: Int = 0
        private set

    private var nextId = 1

    fun newId() = nextId++

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
    )

    fun snapshot() = Snapshot(
        list.toList(), marker, nextId,
        list.filterIsInstance<SketchFeature>().associate { it.sketch to it.sketch.snapshot() },
    )

    fun restore(s: Snapshot) {
        list.clear()
        list += s.features
        marker = s.marker
        nextId = s.nextId
        for ((sketch, snap) in s.sketches) sketch.restore(snap)
    }
}
