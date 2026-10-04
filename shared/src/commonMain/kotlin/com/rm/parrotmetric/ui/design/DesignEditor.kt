package com.rm.parrotmetric.ui.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.parrotmetric.design.Axis3
import com.rm.parrotmetric.design.AxisRef
import com.rm.parrotmetric.design.CombineFeature
import com.rm.parrotmetric.design.DraftFeature
import com.rm.parrotmetric.design.HoleFeature
import com.rm.parrotmetric.design.HoleKind
import com.rm.parrotmetric.design.MirrorFeature
import com.rm.parrotmetric.design.MoveFeature
import com.rm.parrotmetric.design.PatternFeature
import com.rm.parrotmetric.design.ShellFeature
import com.rm.parrotmetric.design.SplitFeature
import com.rm.parrotmetric.design.Built
import com.rm.parrotmetric.design.ChamferFeature
import com.rm.parrotmetric.design.Design
import com.rm.parrotmetric.design.ExtrudeFeature
import com.rm.parrotmetric.design.Feature
import com.rm.parrotmetric.design.FilletFeature
import com.rm.parrotmetric.design.ImportFeature
import com.rm.parrotmetric.design.Kernel
import com.rm.parrotmetric.design.Operation
import com.rm.parrotmetric.design.PlaneRef
import com.rm.parrotmetric.design.Rebuilder
import com.rm.parrotmetric.design.RegionRef
import com.rm.parrotmetric.design.RevolveFeature
import com.rm.parrotmetric.design.SketchFeature
import com.rm.parrotmetric.io.DesignFile
import com.rm.parrotmetric.sketch.ProfileCurve
import com.rm.parrotmetric.sketch.RegionFinder
import com.rm.parrotmetric.sketch.Sketch
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import com.rm.parrotmetric.sketch.profileCurves
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The 3D view, as the design editor uses it. The platform supplies it. */
interface Viewport {
    /** Shows these bodies, then these sketches with their areas pickable. Clears the selection. */
    fun show(bodies: List<Long>, sketches: List<Pair<SketchPlane, List<ProfileCurve>>>, refit: Boolean)
    fun selectedEdges(): List<String>
    /** Selected faces as body number (in the order shown) and face name. */
    fun selectedFaces(): List<Pair<Int, String>>
    /** Selected sketch areas as sketch number (in the order shown) and area number. */
    fun selectedRegions(): List<Pair<Int, Int>>
    fun select(edges: List<String>, regions: List<Pair<Int, Int>>, faces: List<String> = emptyList())
    fun clearSelection()
    fun viewFrom(yaw: Float, pitch: Float)
    fun fit()
    fun isMesh(body: Long): Boolean
    fun triangles(): Int
}

/** A step for the history bar. */
data class HistoryEntry(val id: Int, val name: String, val kind: Kind, val error: String?, val active: Boolean) {
    enum class Kind { Sketch, Create, Modify, Import }
}

/**
 * Editing the design: the history, rebuilding it, the feature panels and
 * undo. Rebuilds run off the main thread, one at a time.
 */
class DesignEditor(
    private val kernel: Kernel,
    private val finder: RegionFinder,
    private val viewport: Viewport,
    private val scope: CoroutineScope,
) {
    val design = Design()
    private val rebuilder = Rebuilder(kernel)
    private val lock = Mutex()

    /** Goes up when the history changes. */
    var version by mutableIntStateOf(0)
        private set
    var built by mutableStateOf<Built?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var panel by mutableStateOf<FeatureDraft?>(null)
        private set

    /** Called when the history changes, for autosave. */
    var onHistoryChanged: () -> Unit = {}

    /** Called on the main thread after each rebuild is shown, which clears the view's selection. */
    var onShown: () -> Unit = {}

    /** The sketches shown, in the order the viewport numbers them. */
    private var shownSketches: List<SketchFeature> = emptyList()

    private val undoStack = ArrayDeque<Design.Snapshot>()
    private val redoStack = ArrayDeque<Design.Snapshot>()
    val canUndo get() = version >= 0 && undoStack.isNotEmpty() && panel == null
    val canRedo get() = version >= 0 && redoStack.isNotEmpty() && panel == null

    fun history(): List<HistoryEntry> {
        val errors = built?.errors ?: emptyMap()
        return design.features.mapIndexed { i, f ->
            val kind = when (f) {
                is SketchFeature -> HistoryEntry.Kind.Sketch
                is ExtrudeFeature, is RevolveFeature -> HistoryEntry.Kind.Create
                is ImportFeature -> HistoryEntry.Kind.Import
                else -> HistoryEntry.Kind.Modify
            }
            HistoryEntry(f.id, f.name, kind, errors[f.id], i < design.marker)
        }
    }

    /** Every body there is now, for export. */
    fun bodies(): List<Long> = built?.bodies?.map { it.handle } ?: emptyList()

    // Changing the history.

    /** Call before a change that undo should take back. */
    fun checkpoint() {
        undoStack.addLast(design.snapshot())
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(design.snapshot())
        design.restore(s)
        changed()
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(design.snapshot())
        design.restore(s)
        changed()
    }

    private fun changed(refit: Boolean = false) {
        version++
        onHistoryChanged()
        rebuild(refit = refit)
    }

    /** The design as a .pmet file. */
    fun fileText(title: String): String = DesignFile.write(design, title)

    /** Replaces the design with a file's. Returns its title; throws IllegalArgumentException if it can't be read. */
    fun openFile(text: String): String {
        val title = DesignFile.read(text, design)
        afterReplace()
        return title
    }

    /** Starts again with nothing. Undo brings the old design back. */
    fun newDesign() {
        checkpoint()
        design.load(emptyList(), 0)
        panel = null
        changed(refit = true)
    }

    private fun afterReplace() {
        undoStack.clear()
        redoStack.clear()
        panel = null
        changed(refit = true)
    }

    fun addSketch(name: String, plane: PlaneRef, sketch: Sketch): SketchFeature {
        val f = SketchFeature(design.newId(), name, plane, sketch)
        design.add(f)
        changed()
        return f
    }

    /** A sketch was edited in place; its feature is already in the history. */
    fun sketchChanged() = changed()

    fun importFile(name: String, data: ByteArray, format: Int) {
        checkpoint()
        design.add(ImportFeature(design.newId(), name, data, format))
        changed(refit = true)
    }

    fun delete(id: Int) {
        checkpoint()
        design.remove(id)
        changed()
    }

    /** Rolls the marker to just after the feature at this place in the history. */
    fun rollTo(index: Int) {
        checkpoint()
        design.moveMarker(index + 1)
        changed()
    }

    fun nextSketchName() = "Sketch ${design.features.count { it is SketchFeature } + 1}"

    /** Where a sketch is now, after the last rebuild. */
    fun planeOf(sketch: SketchFeature): SketchPlane? = built?.sketchPlanes?.get(sketch.id)

    /**
     * A plane on the selected flat face, with its x along the view's right
     * squared up to the nearest side, or null if one flat face isn't selected.
     */
    fun faceUnderSelection(yaw: Float): PlaneRef? {
        val faces = viewport.selectedFaces()
        if (faces.size != 1) return null
        val quarter = PI / 2
        val square = kotlin.math.round(yaw / quarter) * quarter
        val x = Vec3(-sin(square), cos(square), 0.0)
        return PlaneRef.OnFace(faces[0].second, x)
    }

    // Rebuilding.

    private var pending = false
    private var pendingRefit = false

    /** Rebuilds what's active, with the open panel's feature in place if it has one. */
    fun rebuild(refit: Boolean = false) {
        pendingRefit = pendingRefit || refit
        if (busy) {
            pending = true
            return
        }
        busy = true
        scope.launch {
            do {
                pending = false
                val refitNow = pendingRefit
                pendingRefit = false
                val features = featuresToBuild()
                val draft = panel
                // The first body to appear is framed.
                val hadBodies = built?.bodies?.isNotEmpty() == true
                val result = withContext(Dispatchers.Default) {
                    lock.withLock {
                        val b = rebuilder.rebuild(features)
                        val sketches = sketchesToShow(features, draft)
                        val shown = sketches.mapNotNull { s -> b.sketchPlanes[s.id]?.let { s to it } }
                        val refit = refitNow || (!hadBodies && b.bodies.isNotEmpty())
                        viewport.show(b.bodies.map { it.handle }, shown.map { it.second to it.first.sketch.profileCurves() }, refit)
                        Triple(b, shown.map { it.first }, draft)
                    }
                }
                built = result.first
                shownSketches = result.second
                onShown()
                // Show what the panel has picked on the fresh display.
                result.third?.let { d -> if (d === panel) highlight(d) }
            } while (pending)
            busy = false
        }
    }

    private fun featuresToBuild(): List<Feature> {
        val active = design.active
        val draft = panel ?: return active
        // Fillets and chamfers pick edges on the body before them, so they're left out while being picked.
        val f = draft.feature() ?: return active
        val i = active.indexOfFirst { it.id == f.id }
        return when {
            draft is EdgeDraft || draft is FaceDraft -> if (i >= 0) active.subList(0, i) else active
            i >= 0 -> active.toMutableList().also { it[i] = f }
            else -> active + f
        }
    }

    /** Sketches nothing has used yet, and the panel's sketch. */
    private fun sketchesToShow(features: List<Feature>, draft: FeatureDraft?): List<SketchFeature> {
        val used = features.mapNotNull {
            when (it) {
                is ExtrudeFeature -> it.sketchId
                is RevolveFeature -> it.sketchId
                else -> null
            }
        }.toSet()
        val keep = (draft as? AreaDraft)?.sketchId
        return features.filterIsInstance<SketchFeature>().filter { it.id !in used || it.id == keep }
    }

    // Panels.

    /** Opens Extrude or Revolve, starting from any sketch areas already selected. */
    fun startExtrude() = openArea(ExtrudeDraft(null))
    fun startRevolve() = openArea(RevolveDraft(null))

    private fun openArea(d: AreaDraft) {
        panel = d
        selectionChanged()
        if (d.regions.isEmpty()) rebuild()
    }

    /** Opens Fillet or Chamfer with the selected edges. */
    fun startFillet() = openEdges(EdgeDraft(null, chamfer = false))
    fun startChamfer() = openEdges(EdgeDraft(null, chamfer = true))

    private fun openEdges(d: EdgeDraft) {
        d.edges = viewport.selectedEdges()
        panel = d
        rebuild()
    }

    fun startShell() = openFaces(FaceDraft(null, tilt = false))
    fun startDraft() = openFaces(FaceDraft(null, tilt = true))

    private fun openFaces(d: FaceDraft) {
        d.faces = viewport.selectedFaces().map { it.second }
        panel = d
        rebuild()
    }

    fun startHole() {
        panel = HoleDraft(null)
        rebuild()
    }

    fun startMirror() = openBodies(MirrorDraft(null))
    fun startPattern() = openBodies(PatternDraft(null))
    fun startCombine() = openBodies(CombineDraft(null))
    fun startSplit() = openBodies(SplitDraft(null))
    fun startMove() = openBodies(MoveDraft(null))

    private fun openBodies(d: BodyDraft) {
        d.planes = planeChoices()
        d.bodies = pickedBodies()
        panel = d
        rebuild()
    }

    /** Bodies under the selected faces, in the order they were tapped. */
    private fun pickedBodies(): List<String> {
        val bodies = built?.bodies ?: return emptyList()
        return viewport.selectedFaces().mapNotNull { bodies.getOrNull(it.first)?.label }.distinct()
    }

    /** Planes to mirror or split across: the origin planes, and the selected flat face if there is one. */
    private fun planeChoices(): List<Pair<String, PlaneRef>> {
        val out = mutableListOf(
            "Top" to PlaneRef.Fixed(SketchPlane.Top) as PlaneRef,
            "Front" to PlaneRef.Fixed(SketchPlane.Front),
            "Right" to PlaneRef.Fixed(SketchPlane.Right),
        )
        viewport.selectedFaces().firstOrNull()?.let { out += "The face" to PlaneRef.OnFace(it.second, Vec3(1.0, 0.0, 0.0)) }
        return out
    }

    /** Sketches with lone points, for holes, newest first. */
    fun holeSketches(): List<SketchFeature> = design.active.filterIsInstance<SketchFeature>().filter { f ->
        val s = f.sketch
        s.points.any { p -> p !== s.origin && s.curves.none { p in it.points() } }
    }.reversed()

    /** Opens a feature's panel to change it. */
    fun edit(id: Int): Feature? {
        val f = design.feature(id) ?: return null
        val d: FeatureDraft = when (f) {
            is ExtrudeFeature -> ExtrudeDraft(f)
            is RevolveFeature -> RevolveDraft(f)
            is FilletFeature -> EdgeDraft(f, chamfer = false)
            is ChamferFeature -> EdgeDraft(f, chamfer = true)
            is ShellFeature -> FaceDraft(f, tilt = false)
            is DraftFeature -> FaceDraft(f, tilt = true)
            is HoleFeature -> HoleDraft(f)
            is MirrorFeature -> MirrorDraft(f)
            is PatternFeature -> PatternDraft(f)
            is CombineFeature -> CombineDraft(f)
            is SplitFeature -> SplitDraft(f)
            is MoveFeature -> MoveDraft(f)
            else -> return f
        }
        if (d is BodyDraft) d.planes = planeChoices().let { choices ->
            val own = (f as? MirrorFeature)?.plane ?: (f as? SplitFeature)?.plane
            if (own != null && choices.none { it.second == own }) choices + ("Its face" to own) else choices
        }
        panel = d
        rebuild()
        return f
    }

    /** The selection in the view changed while a panel is open. */
    fun selectionChanged() {
        when (val d = panel) {
            is AreaDraft -> {
                val picked = viewport.selectedRegions()
                if (picked.isEmpty()) {
                    d.regions = emptyList()
                    return
                }
                val sketch = shownSketches.getOrNull(picked[0].first) ?: return
                val regions = finder.find(sketch.sketch.profileCurves())
                d.sketchId = sketch.id
                d.regions = picked.filter { it.first == picked[0].first }.mapNotNull { (_, r) ->
                    regions.getOrNull(r)?.let { RegionRef(it.curveIds, it.insideU, it.insideV) }
                }
                rebuild()
            }
            is EdgeDraft -> d.edges = viewport.selectedEdges()
            is FaceDraft -> {
                val faces = viewport.selectedFaces().map { it.second }
                if (d.tilt && d.pickingPivot) {
                    faces.lastOrNull()?.let { d.neutral = it }
                    d.pickingPivot = false
                    viewport.select(emptyList(), emptyList())
                } else d.faces = faces
            }
            is BodyDraft -> {
                val picked = pickedBodies()
                if (picked.isNotEmpty() || viewport.selectedFaces().isEmpty()) {
                    d.bodies = picked
                    rebuild()
                }
            }
            else -> {}
        }
    }

    /** Puts the panel's picks back on the display after a rebuild. */
    private fun highlight(d: FeatureDraft) {
        when (d) {
            is AreaDraft -> {
                val s = shownSketches.indexOfFirst { it.id == d.sketchId }
                if (s < 0) return
                val regions = finder.find(shownSketches[s].sketch.profileCurves())
                val pairs = d.regions.mapNotNull { ref ->
                    val i = regions.indexOfFirst { it.curveIds == ref.curveIds }
                    if (i >= 0) s to i else null
                }
                viewport.select(emptyList(), pairs)
            }
            is EdgeDraft -> viewport.select(d.edges, emptyList())
            is FaceDraft -> viewport.select(emptyList(), emptyList(), d.faces + listOfNotNull(d.neutral))
        }
    }

    /** A value in the panel changed: show it. */
    fun draftChanged() = rebuild()

    fun cancelPanel() {
        panel = null
        rebuild()
    }

    /** Puts the panel's feature into the history. False, with a message, if it isn't ready. */
    fun confirmPanel(): Boolean {
        val d = panel ?: return false
        val f = d.feature() ?: run {
            message = d.missing()
            return false
        }
        checkpoint()
        if (design.feature(f.id) != null) design.replace(f) else design.add(f)
        panel = null
        changed()
        return true
    }

    /** Ids for new features come from the design; drafts ask for one when they first make a feature. */
    internal fun newId() = design.newId()

    abstract inner class FeatureDraft {
        /** The feature as set up so far, or null if something's missing. */
        abstract fun feature(): Feature?
        abstract fun missing(): String
    }

    abstract inner class AreaDraft(editing: Feature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        var sketchId by mutableStateOf<Int?>(null)
        var regions by mutableStateOf<List<RegionRef>>(emptyList())
        var operation by mutableStateOf(Operation.NewBody)
        override fun missing() = "Tap an area of a sketch"
    }

    enum class Direction { OneSide, Symmetric, TwoSides }

    inner class ExtrudeDraft(editing: ExtrudeFeature?) : AreaDraft(editing) {
        private val name = editing?.name ?: "Extrude ${design.features.count { it is ExtrudeFeature } + 1}"
        var distance by mutableStateOf(10.0)
        var other by mutableStateOf(10.0)
        var direction by mutableStateOf(Direction.OneSide)

        init {
            if (editing != null) {
                sketchId = editing.sketchId
                regions = editing.regions
                operation = editing.operation
                when {
                    editing.back == 0.0 -> distance = editing.forward
                    editing.forward == editing.back -> { direction = Direction.Symmetric; distance = editing.forward * 2 }
                    else -> { direction = Direction.TwoSides; distance = editing.forward; other = editing.back }
                }
            }
        }

        override fun feature(): Feature? {
            val s = sketchId ?: return null
            if (regions.isEmpty()) return null
            val (fwd, back) = when (direction) {
                Direction.OneSide -> distance to 0.0
                Direction.Symmetric -> distance / 2 to distance / 2
                Direction.TwoSides -> distance to other
            }
            return ExtrudeFeature(id, name, s, regions, fwd, back, operation)
        }
    }

    inner class RevolveDraft(editing: RevolveFeature?) : AreaDraft(editing) {
        private val name = editing?.name ?: "Revolve ${design.features.count { it is RevolveFeature } + 1}"
        var axis by mutableStateOf<AxisRef>(AxisRef.SketchY)
        var degrees by mutableStateOf(360.0)

        init {
            if (editing != null) {
                sketchId = editing.sketchId
                regions = editing.regions
                operation = editing.operation
                axis = editing.axis
                degrees = editing.angle * 180 / PI
            }
        }

        override fun feature(): Feature? {
            val s = sketchId ?: return null
            if (regions.isEmpty()) return null
            return RevolveFeature(id, name, s, regions, axis, degrees * PI / 180, operation)
        }
    }

    private fun nextName(prefix: String, count: Int) = "$prefix ${count + 1}"

    /** Shell (faces left open) or Draft (faces tilted, and the face they pivot on). */
    inner class FaceDraft(editing: Feature?, val tilt: Boolean) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: if (tilt) nextName("Draft", design.features.count { it is DraftFeature })
        else nextName("Shell", design.features.count { it is ShellFeature })
        var faces by mutableStateOf<List<String>>(emptyList())
        var neutral by mutableStateOf<String?>(null)
        var pickingPivot by mutableStateOf(false)
        var size by mutableStateOf(if (tilt) 3.0 else 2.0)  // Degrees for a draft, mm for a shell.

        init {
            when (editing) {
                is ShellFeature -> { faces = editing.faces; size = editing.thickness }
                is DraftFeature -> { faces = editing.faces; neutral = editing.neutral; size = editing.angle * 180 / PI }
                else -> {}
            }
        }

        override fun feature(): Feature? = if (tilt) {
            val n = neutral
            if (faces.isEmpty() || n == null) null else DraftFeature(id, name, faces - n, n, size * PI / 180)
        } else {
            if (faces.isEmpty()) null else ShellFeature(id, name, faces, size)
        }

        override fun missing() = if (tilt && neutral == null) "Pick the face they pivot on" else "Tap the faces"
    }

    inner class HoleDraft(editing: HoleFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Hole", design.features.count { it is HoleFeature })
        var sketchId by mutableStateOf(editing?.sketchId ?: holeSketches().firstOrNull()?.id)
        var diameter by mutableStateOf(editing?.diameter ?: 3.0)
        var depth by mutableStateOf(editing?.depth ?: 10.0)
        var through by mutableStateOf(editing?.depth == 0.0)
        var kind by mutableStateOf(editing?.kind ?: HoleKind.Simple)
        var topDiameter by mutableStateOf(editing?.topDiameter ?: 6.0)
        var topDepth by mutableStateOf(editing?.topDepth ?: 3.0)

        override fun feature(): Feature? {
            val s = sketchId ?: return null
            return HoleFeature(id, name, s, diameter, if (through) 0.0 else depth, kind, topDiameter, topDepth)
        }

        override fun missing() = "Draw points with the Point tool in a sketch first"
    }

    /** Features that work on whole bodies. An empty pick means every body, except where noted. */
    abstract inner class BodyDraft(editing: Feature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        var bodies by mutableStateOf<List<String>>(emptyList())
        var planes: List<Pair<String, PlaneRef>> = emptyList()
        override fun missing() = "Tap a face of each body to use"
    }

    inner class MirrorDraft(editing: MirrorFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Mirror", design.features.count { it is MirrorFeature })
        var plane by mutableStateOf<PlaneRef>(editing?.plane ?: PlaneRef.Fixed(SketchPlane.Right))
        var join by mutableStateOf(editing?.join ?: true)
        init { if (editing != null) bodies = editing.bodies }
        override fun feature() = MirrorFeature(id, name, bodies, plane, join)
    }

    inner class PatternDraft(editing: PatternFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Pattern", design.features.count { it is PatternFeature })
        var circular by mutableStateOf(editing?.circular ?: false)
        var axis by mutableStateOf(editing?.axis ?: Axis3.X)
        var count by mutableStateOf((editing?.count ?: 3).toDouble())
        var spacing by mutableStateOf(editing?.spacing ?: 20.0)
        var degrees by mutableStateOf((editing?.angle ?: (2 * PI)) * 180 / PI)
        var axis2 by mutableStateOf<Axis3?>(editing?.axis2)
        var count2 by mutableStateOf((editing?.count2 ?: 2).toDouble())
        var spacing2 by mutableStateOf(editing?.spacing2 ?: 20.0)
        var join by mutableStateOf(editing?.join ?: true)
        init { if (editing != null) bodies = editing.bodies }
        override fun feature() = PatternFeature(
            id, name, bodies, circular, axis, count.toInt(), spacing, degrees * PI / 180, axis2, count2.toInt(), spacing2, join,
        )
    }

    inner class CombineDraft(editing: CombineFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Combine", design.features.count { it is CombineFeature })
        var operation by mutableStateOf(editing?.operation ?: Operation.Join)
        var keepTools by mutableStateOf(editing?.keepTools ?: false)
        init { if (editing != null) bodies = listOf(editing.target) + editing.tools }
        override fun feature(): Feature? =
            if (bodies.size < 2) null else CombineFeature(id, name, bodies[0], bodies.drop(1), operation, keepTools)
        override fun missing() = "Tap a face of the body to keep, then of each body to combine with it"
    }

    inner class SplitDraft(editing: SplitFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Split", design.features.count { it is SplitFeature })
        var plane by mutableStateOf<PlaneRef>(editing?.plane ?: PlaneRef.Fixed(SketchPlane.Right))
        init { if (editing != null) bodies = listOf(editing.body) }
        override fun feature(): Feature? = bodies.firstOrNull()?.let { SplitFeature(id, name, it, plane) }
        override fun missing() = "Tap a face of the body to split"
    }

    inner class MoveDraft(editing: MoveFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Move", design.features.count { it is MoveFeature })
        var dx by mutableStateOf(editing?.dx ?: 0.0)
        var dy by mutableStateOf(editing?.dy ?: 0.0)
        var dz by mutableStateOf(editing?.dz ?: 0.0)
        var axis by mutableStateOf(editing?.axis ?: Axis3.Z)
        var degrees by mutableStateOf((editing?.angle ?: 0.0) * 180 / PI)
        var copy by mutableStateOf(editing?.copy ?: false)
        init { if (editing != null) bodies = editing.bodies }
        override fun feature() = MoveFeature(id, name, bodies, dx, dy, dz, axis, degrees * PI / 180, copy)
    }

    inner class EdgeDraft(editing: Feature?, val chamfer: Boolean) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = when (editing) {
            null -> if (chamfer) "Chamfer ${design.features.count { it is ChamferFeature } + 1}" else "Fillet ${design.features.count { it is FilletFeature } + 1}"
            else -> editing.name
        }
        var edges by mutableStateOf<List<String>>(emptyList())
        var size by mutableStateOf(if (chamfer) 1.0 else 2.0)

        init {
            when (editing) {
                is FilletFeature -> { edges = editing.edges; size = editing.radius }
                is ChamferFeature -> { edges = editing.edges; size = editing.distance }
                else -> {}
            }
        }

        override fun feature(): Feature? {
            if (edges.isEmpty()) return null
            return if (chamfer) ChamferFeature(id, name, edges, size) else FilletFeature(id, name, edges, size)
        }

        override fun missing() = "Tap the edges to ${if (chamfer) "bevel" else "round"}"
    }
}
