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
import com.rm.parrotmetric.design.PlaneFeature
import com.rm.parrotmetric.design.AxisFeature
import com.rm.parrotmetric.design.ShellFeature
import com.rm.parrotmetric.design.SplitFeature
import com.rm.parrotmetric.design.Built
import com.rm.parrotmetric.design.ChamferFeature
import com.rm.parrotmetric.design.ChamferKind
import com.rm.parrotmetric.design.PointFeature
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
    /** Shows these bodies, these sketches with their areas pickable, and construction planes, axes and points. Clears the selection. */
    fun show(
        bodies: List<Long>, sketches: List<Pair<SketchPlane, List<ProfileCurve>>>,
        planes: List<SketchPlane>, axes: List<Pair<Vec3, Vec3>>, points: List<Vec3>, refit: Boolean,
    )
    /** Selected construction planes, by their place in the list shown. */
    fun selectedPlanes(): List<Int>
    /** The selected flat part of a mesh: its middle and outward normal, or null. */
    fun selectedMeshPlane(): Pair<Vec3, Vec3>?
    /** Where these bodies cross a plane, as curves on it. */
    fun section(bodies: List<Long>, plane: SketchPlane): List<ProfileCurve>
    /** Lines describing what's selected, for Measure. */
    fun measure(): List<String>
    /** Hides what's behind a plane through origin facing normal, or shows everything again. */
    fun setSection(on: Boolean, origin: Vec3, normal: Vec3)
    /** A face's edges as curves on a plane, for projecting into a sketch. Throws if the face is gone. */
    fun faceOutline(body: Long, face: String, plane: SketchPlane): List<ProfileCurve>
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

/** A step for the history bar. [warning] is set when it built only after finding something again by its shape; [off] when turned off. */
data class HistoryEntry(
    val id: Int, val name: String, val kind: Kind, val error: String?, val active: Boolean,
    val warning: String? = null, val off: Boolean = false,
) {
    enum class Kind { Sketch, Create, Modify, Construct, Import }
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
    /** Triangles in the mesh bodies shown, for the top bar. */
    var triangles by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
    /** The tool last started, by its id in Tools, for Repeat. */
    var lastTool by mutableStateOf<String?>(null)
    var panel by mutableStateOf<FeatureDraft?>(null)
        private set

    /** Called when the history changes, for autosave. */
    var onHistoryChanged: () -> Unit = {}

    /** Called on the main thread after each rebuild is shown, which clears the view's selection. */
    var onShown: () -> Unit = {}

    /** The bodies shown, in the order the viewport numbers them. Hidden ones aren't. */
    private var shownBodies: List<com.rm.parrotmetric.design.BodyState> = emptyList()

    /** The construction planes shown, in the order the viewport numbers them. */
    private var shownPlanes: List<PlaneFeature> = emptyList()

    /** The sketches shown, in the order the viewport numbers them. */
    private var shownSketches: List<SketchFeature> = emptyList()

    private val undoStack = ArrayDeque<Design.Snapshot>()
    private val redoStack = ArrayDeque<Design.Snapshot>()
    val canUndo get() = version >= 0 && undoStack.isNotEmpty() && panel == null
    val canRedo get() = version >= 0 && redoStack.isNotEmpty() && panel == null

    fun history(): List<HistoryEntry> {
        val errors = built?.errors ?: emptyMap()
        val warnings = built?.warnings ?: emptyMap()
        return design.features.mapIndexed { i, f ->
            val kind = when (f) {
                is SketchFeature -> HistoryEntry.Kind.Sketch
                is ExtrudeFeature, is RevolveFeature -> HistoryEntry.Kind.Create
                is ImportFeature -> HistoryEntry.Kind.Import
                is PlaneFeature, is AxisFeature, is PointFeature -> HistoryEntry.Kind.Construct
                else -> HistoryEntry.Kind.Modify
            }
            val off = f.id in design.suppressed
            HistoryEntry(f.id, f.name, kind, if (off) null else errors[f.id], i < design.marker, if (off) null else warnings[f.id], off)
        }
    }

    /** Every body there is now, labels and handles, hidden ones too. */
    fun allBodies(): List<com.rm.parrotmetric.design.BodyState> = built?.bodies ?: emptyList()

    /** The bodies that aren't hidden, for export. */
    fun bodies(): List<Long> = shownBodies.map { it.handle }

    // Measure and section, which change only what's shown.

    var measuring by mutableStateOf(false)
        private set
    var measureLines by mutableStateOf<List<String>>(emptyList())
        private set

    fun startMeasuring() {
        measuring = true
        measureLines = viewport.measure()
    }

    fun stopMeasuring() {
        measuring = false
    }

    var sectionOn by mutableStateOf(false)
        private set
    var sectionPlane by mutableStateOf<PlaneRef>(PlaneRef.Fixed(SketchPlane.Front))
    var sectionOffset by mutableStateOf(0.0)
    var sectionFlip by mutableStateOf(false)
    var sectionPlanes: List<Pair<String, PlaneRef>> = emptyList()
        private set

    fun startSection() {
        sectionPlanes = planeChoices()
        sectionOn = true
        updateSection()
    }

    fun stopSection() {
        sectionOn = false
        viewport.setSection(false, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))
    }

    fun updateSection() {
        if (!sectionOn) return
        val plane = resolve(sectionPlane) ?: return
        val n = if (sectionFlip) -plane.normal else plane.normal
        viewport.setSection(true, plane.origin + plane.normal * sectionOffset, n)
    }

    /** Where a plane reference is now: fixed planes as they are, construction planes and faces as last built. */
    private fun resolve(ref: PlaneRef): SketchPlane? = when (ref) {
        is PlaneRef.Fixed -> ref.plane
        is PlaneRef.Construction -> built?.sketchPlanes?.get(ref.featureId)
        is PlaneRef.OnFace -> {
            val body = shownBodies.firstOrNull { ref.face in kernel.faceNames(it.handle) }
            val d = body?.let { try { kernel.facePlane(it.handle, ref.face) } catch (e: Exception) { null } }
            d?.let {
                val n = Vec3(it[3], it[4], it[5])
                val x = if (kotlin.math.abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0).cross(n) else Vec3(1.0, 0.0, 0.0).cross(n)
                SketchPlane("Face", Vec3(it[0], it[1], it[2]), x, n.cross(x))
            }
        }
    }

    // The parts list.

    private fun setInfo(label: String, change: (Design.BodyInfo) -> Design.BodyInfo) {
        checkpoint()
        design.bodies[label] = change(design.info(label))
        changed()
    }

    fun setHidden(label: String, hidden: Boolean) = setInfo(label) { it.copy(hidden = hidden) }

    /** Hides the bodies under the selected faces. */
    fun hideSelectedBodies() {
        val labels = pickedBodies()
        if (labels.isEmpty()) return
        checkpoint()
        for (l in labels) design.bodies[l] = design.info(l).copy(hidden = true)
        changed()
    }
    fun rename(label: String, name: String) = setInfo(label) { it.copy(name = name.trim().ifEmpty { null }) }
    fun setComponent(label: String, component: String?) = setInfo(label) { it.copy(component = component) }

    /** Components in use, in the order their first body comes. */
    fun components(): List<String> = allBodies().mapNotNull { design.info(it.label).component }.distinct()

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

    /**
     * The design as a .pmet file. A new sketch still being drawn ([drawing]:
     * its name, plane and sketch) goes in as if finished, so autosave keeps it.
     */
    fun fileText(title: String, drawing: Triple<String, PlaneRef, Sketch>? = null): String {
        if (drawing == null || drawing.third.curves.isEmpty()) return DesignFile.write(design, title)
        val before = design.snapshot()
        design.add(SketchFeature(design.newId(), drawing.first, drawing.second, drawing.third))
        return try {
            DesignFile.write(design, title)
        } finally {
            design.restore(before)
        }
    }

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

    /** Turns a step off, leaving it in the history unbuilt, or on again. */
    fun setOff(id: Int, off: Boolean) {
        checkpoint()
        if (off) design.suppressed += id else design.suppressed -= id
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
     * Where a new sketch on the selection goes: a selected construction plane,
     * or the one selected flat face. Null, with a message, if neither.
     */
    fun sketchPlaneUnderSelection(yaw: Float, name: String): Pair<PlaneRef, SketchPlane>? {
        viewport.selectedPlanes().firstOrNull()?.let { i ->
            val f = shownPlanes.getOrNull(i) ?: return null
            val p = built?.sketchPlanes?.get(f.id) ?: return null
            return PlaneRef.Construction(f.id) to p.copy(name = f.name)
        }
        val ref = faceUnderSelection(yaw) as? PlaneRef.OnFace ?: run {
            // A flat part of a mesh: a fixed plane where it is now, x squared up to the view.
            val (o, n) = viewport.selectedMeshPlane() ?: return null
            val quarter = PI / 2
            val square = kotlin.math.round(yaw / quarter) * quarter
            val hint = Vec3(-sin(square), cos(square), 0.0)
            var x = hint - n * hint.dot(n)
            if (x.dot(x) < 1e-12) x = if (kotlin.math.abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0).cross(n) else Vec3(1.0, 0.0, 0.0).cross(n)
            x = x * (1 / kotlin.math.sqrt(x.dot(x)))
            val plane = SketchPlane("On a mesh", o, x, n.cross(x))
            return PlaneRef.Fixed(plane) to plane
        }
        val (bodyIndex, _) = viewport.selectedFaces().first { it.second.isNotEmpty() }
        val body = shownBodies.getOrNull(bodyIndex) ?: return null
        val d = try {
            kernel.facePlane(body.handle, ref.face)
        } catch (e: com.rm.parrotmetric.design.KernelException) {
            message = e.message
            null
        } ?: return null
        val n = Vec3(d[3], d[4], d[5])
        var x = ref.x - n * ref.x.dot(n)
        if (x.dot(x) < 1e-12) x = if (kotlin.math.abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0).cross(n) else Vec3(1.0, 0.0, 0.0).cross(n)
        x = x * (1 / kotlin.math.sqrt(x.dot(x)))
        return ref to SketchPlane("On a face", Vec3(d[0], d[1], d[2]), x, n.cross(x))
    }

    /** For projecting into a sketch on a plane: where the shown bodies cross it. */
    fun sectionThrough(plane: SketchPlane): List<ProfileCurve>? = try {
        // Each body a hair behind the plane, so a sketch on a body's flat top still finds its
        // outline; else a hair in front, for a body standing on the plane.
        fun at(body: Long, offset: Double) = viewport.section(listOf(body), plane.copy(origin = plane.origin + plane.normal * offset))
        shownBodies.flatMap { b -> at(b.handle, -0.01).ifEmpty { at(b.handle, 0.01) } }.ifEmpty { null }
    } catch (e: RuntimeException) {
        null
    }

    /** For projecting into a sketch on a face: that face's edges as curves on the sketch's plane. */
    fun outlineOf(ref: PlaneRef, plane: SketchPlane): List<ProfileCurve>? {
        val face = (ref as? PlaneRef.OnFace)?.face ?: return null
        val body = built?.bodies?.firstOrNull { face in kernel.faceNames(it.handle) } ?: return null
        return try {
            viewport.faceOutline(body.handle, face, plane)
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * A plane on the selected flat face, with its x along the view's right
     * squared up to the nearest side, or null if one flat face isn't selected.
     */
    fun faceUnderSelection(yaw: Float): PlaneRef? {
        val faces = viewport.selectedFaces().filter { it.second.isNotEmpty() }
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
                val hints = design.hints.toMap()
                val draft = panel
                // The first body to appear is framed.
                val hadBodies = built?.bodies?.isNotEmpty() == true
                val result = withContext(Dispatchers.Default) {
                    lock.withLock {
                        val b = rebuilder.rebuild(features, hints)
                        val sketches = sketchesToShow(features, draft)
                        val shown = sketches.mapNotNull { s -> b.sketchPlanes[s.id]?.let { s to it } }
                        val refit = refitNow || (!hadBodies && b.bodies.isNotEmpty())
                        val planeFeatures = features.filterIsInstance<PlaneFeature>().filter { b.sketchPlanes.containsKey(it.id) }
                        val visible = b.bodies.filter { !design.info(it.label).hidden }
                        shownBodies = visible
                        viewport.show(
                            visible.map { it.handle }, shown.map { it.second to it.first.sketch.profileCurves() },
                            planeFeatures.map { b.sketchPlanes.getValue(it.id) }, b.axes.values.toList(), b.points.values.toList(), refit,
                        )
                        shownPlanes = planeFeatures
                        Triple(b, shown.map { it.first }, draft)
                    }
                }
                built = result.first
                design.hints.clear()
                design.hints.putAll(result.first.hints)
                shownSketches = result.second
                triangles = viewport.triangles()
                onShown()
                // Show what the panel has picked on the fresh display.
                result.third?.let { d -> if (d === panel) highlight(d) }
            } while (pending)
            busy = false
        }
    }

    /** The parameters' values, for number fields. */
    fun names(): Map<String, Double> = com.rm.parrotmetric.design.Parametrics.values(design.parameters)

    fun setParameters(list: List<com.rm.parrotmetric.design.Parameter>) {
        checkpoint()
        design.parameters.clear()
        design.parameters += list
        changed()
    }

    private fun featuresToBuild(): List<Feature> = com.rm.parrotmetric.design.Parametrics.apply(design, rawFeatures())

    private fun rawFeatures(): List<Feature> {
        val active = design.built
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
    fun startExtrude() = openArea(ExtrudeDraft(null).also { it.planes = planeChoices() })
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
        d.faces = viewport.selectedFaces().map { it.second }.filter { it.isNotEmpty() }
        panel = d
        rebuild()
    }

    fun startPlane(kind: PlaneFeature.Kind) {
        val d = PlaneDraft(null, kind)
        d.planes = planeChoices()
        // Start from what's selected: a plane, else a face, else the top plane.
        val pick = viewport.selectedPlanes().firstOrNull()?.let { shownPlanes.getOrNull(it) }?.let { PlaneRef.Construction(it.id) }
            ?: d.planes.firstOrNull { it.first == "The face" }?.second
        if (pick != null) d.base = pick
        panel = d
        rebuild()
    }

    /** A box, cylinder, sphere, torus or cone, on the selected face or plane, else the top plane. */
    fun startPrimitive(kind: com.rm.parrotmetric.design.PrimitiveKind) {
        val d = PrimitiveDraft(null, kind)
        d.planes = planeChoices()
        val pick = viewport.selectedPlanes().firstOrNull()?.let { shownPlanes.getOrNull(it) }?.let { PlaneRef.Construction(it.id) }
            ?: d.planes.firstOrNull { it.first == "The face" }?.second
        if (pick != null) d.plane = pick
        panel = d
        rebuild()
    }

    fun startPoint() {
        val d = PointDraft(null)
        // At the middle of a selected flat face, if there is one.
        viewport.selectedFaces().firstOrNull { it.second.isNotEmpty() }?.let { (b, face) ->
            shownBodies.getOrNull(b)?.let { body -> kernel.facePlane(body.handle, face) }?.let { c -> d.x = c[0]; d.y = c[1]; d.z = c[2] }
        }
        panel = d
        rebuild()
    }

    fun startAxis() {
        panel = AxisDraft(null)
        rebuild()
    }

    /** Construction points as last built, for Project in sketches. */
    fun constructionPoints(): List<Vec3> = built?.points?.values?.toList().orEmpty()

    /** Construction axes so far, for patterns round them. */
    fun axisFeatures(): List<AxisFeature> = design.active.filterIsInstance<AxisFeature>()

    fun startHole() {
        panel = HoleDraft(null)
        rebuild()
    }

    fun startMirror() = openBodies(MirrorDraft(null))
    fun startPattern() = openBodies(PatternDraft(null))
    fun startCombine() = openBodies(CombineDraft(null))
    fun startSplit() = openBodies(SplitDraft(null))
    fun startMove() = openBodies(MoveDraft(null))
    fun startPlaneCut() = openBodies(SplitDraft(null).also { it.keep = 1 })
    fun startConvert() = openBodies(ConvertDraft(null))

    private fun openBodies(d: BodyDraft) {
        d.planes = planeChoices()
        d.bodies = pickedBodies()
        panel = d
        rebuild()
    }

    /** Bodies under the selected faces, in the order they were tapped. */
    private fun pickedBodies(): List<String> =
        viewport.selectedFaces().mapNotNull { shownBodies.getOrNull(it.first)?.label }.distinct()

    /** Planes to mirror or split across: the origin planes, and the selected flat face if there is one. */
    private fun planeChoices(): List<Pair<String, PlaneRef>> {
        val out = mutableListOf(
            "Top" to PlaneRef.Fixed(SketchPlane.Top) as PlaneRef,
            "Front" to PlaneRef.Fixed(SketchPlane.Front),
            "Right" to PlaneRef.Fixed(SketchPlane.Right),
        )
        viewport.selectedFaces().firstOrNull { it.second.isNotEmpty() }?.let { out += "The face" to PlaneRef.OnFace(it.second, Vec3(1.0, 0.0, 0.0)) }
        for (p in design.active.filterIsInstance<PlaneFeature>()) out += p.name to PlaneRef.Construction(p.id)
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
            is com.rm.parrotmetric.design.ConvertFeature -> ConvertDraft(f)
            is PointFeature -> PointDraft(f)
            is PlaneFeature -> PlaneDraft(f, f.kind).also { it.planes = planeChoices().filter { c -> c.second != PlaneRef.Construction(f.id) } }
            is AxisFeature -> AxisDraft(f)
            is com.rm.parrotmetric.design.PrimitiveFeature -> PrimitiveDraft(f, f.kind).also { it.planes = planeChoices() }
            else -> return f
        }
        design.expressions[id]?.let { d.exprs.putAll(it) }
        // Faces and edges found again by shape are picked in place of their lost names, to check.
        built?.found?.get(id)?.let { found ->
            fun now(n: String) = found[n] ?: n
            when (d) {
                is EdgeDraft -> d.edges = d.edges.map(::now)
                is FaceDraft -> { d.faces = d.faces.map(::now); d.neutral = d.neutral?.let(::now) }
                else -> {}
            }
        }
        if (d is ExtrudeDraft) d.planes = planeChoices().let { choices ->
            val own = d.upTo
            if (own != null && choices.none { it.second == own }) choices + ("Its face" to own) else choices
        }
        if (d is BodyDraft) d.planes = planeChoices().let { choices ->
            val own = (f as? MirrorFeature)?.plane ?: (f as? SplitFeature)?.plane
            if (own != null && choices.none { it.second == own }) choices + ("Its face" to own) else choices
        }
        panel = d
        rebuild()
        return f
    }

    /** The selection in the view changed. */
    fun selectionChanged() {
        if (measuring) measureLines = viewport.measure()
        when (val d = panel) {
            is AreaDraft -> {
                // Up to a face: the face tapped last.
                if (d is ExtrudeDraft && d.upToOn) viewport.selectedFaces().lastOrNull { it.second.isNotEmpty() }?.let { (_, face) ->
                    val ref = PlaneRef.OnFace(face, Vec3(1.0, 0.0, 0.0))
                    d.planes = d.planes.filter { it.first != "The face" } + ("The face" to ref)
                    d.upTo = ref
                }
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
                val faces = viewport.selectedFaces().map { it.second }.filter { it.isNotEmpty() }
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
        if (d.exprs.isEmpty()) design.expressions.remove(f.id) else design.expressions[f.id] = d.exprs.toMap()
        panel = null
        changed()
        return true
    }

    /** Ids for new features come from the design; drafts ask for one when they first make a feature. */
    internal fun newId() = design.newId()

    abstract inner class FeatureDraft {
        /** Fields typed as expressions, by the names Parametrics.withValue uses. */
        val exprs = androidx.compose.runtime.mutableStateMapOf<String, String>()

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
        var taperDegrees by mutableStateOf((editing?.taper ?: 0.0) * 180 / PI)
        /** Going as far as [upTo] in place of a distance. */
        var upToOn by mutableStateOf(editing?.upTo != null)
        var upTo by mutableStateOf<PlaneRef?>(editing?.upTo)
        /** What it can go up to: the origin planes, the tapped face and construction planes. */
        var planes by mutableStateOf<List<Pair<String, PlaneRef>>>(emptyList())
        /** Right through every body. */
        var throughAll by mutableStateOf(editing?.throughAll ?: false)
        /** Through all the other way (behind the sketch). */
        var backwards by mutableStateOf(editing != null && editing.throughAll && editing.forward < 0)
        var offset by mutableStateOf(editing?.offset ?: 0.0)
        var thinOn by mutableStateOf((editing?.thin ?: 0.0) > 0)
        var thin by mutableStateOf(editing?.thin?.takeIf { it > 0 } ?: 1.0)

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
            val taper = taperDegrees * PI / 180
            val wall = if (thinOn) thin else 0.0
            if (throughAll) {
                // Only the directions count; the rebuild works out how far.
                val (fwd, back) = when {
                    direction != Direction.OneSide -> 1.0 to 1.0
                    backwards -> -1.0 to 0.0
                    else -> 1.0 to 0.0
                }
                return ExtrudeFeature(id, name, s, regions, fwd, back, operation, taper, null, true, offset, wall)
            }
            if (upToOn) {
                val target = upTo ?: return null
                return ExtrudeFeature(id, name, s, regions, 0.0, 0.0, operation, taper, target, false, offset, wall)
            }
            val (fwd, back) = when (direction) {
                Direction.OneSide -> distance to 0.0
                Direction.Symmetric -> distance / 2 to distance / 2
                Direction.TwoSides -> distance to other
            }
            return ExtrudeFeature(id, name, s, regions, fwd, back, operation, taper, null, false, offset, wall)
        }

        override fun missing() = if (regions.isEmpty()) "Tap an area of a sketch" else "Tap the face to go up to"
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
        var axisFeature by mutableStateOf(editing?.axisFeature)
        init { if (editing != null) bodies = editing.bodies }
        override fun feature() = PatternFeature(
            id, name, bodies, circular, axis, count.toInt(), spacing, degrees * PI / 180, axis2, count2.toInt(), spacing2, join,
            if (circular) axisFeature else null,
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
        var keep by mutableStateOf(editing?.keep ?: 0)
        private val name = editing?.name ?: nextName("Split", design.features.count { it is SplitFeature })
        var plane by mutableStateOf<PlaneRef>(editing?.plane ?: PlaneRef.Fixed(SketchPlane.Right))
        init { if (editing != null) bodies = listOf(editing.body) }
        override fun feature(): Feature? = bodies.firstOrNull()?.let { SplitFeature(id, name, it, plane, keep) }
        override fun missing() = "Tap a face of the body to split"
    }

    inner class ConvertDraft(editing: com.rm.parrotmetric.design.ConvertFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("To solid", design.features.count { it is com.rm.parrotmetric.design.ConvertFeature })
        init { if (editing != null) bodies = listOf(editing.body) }
        override fun feature(): Feature? = bodies.firstOrNull()?.let { com.rm.parrotmetric.design.ConvertFeature(id, name, it) }
        override fun missing() = "Tap the mesh to make solid"
    }

    inner class MoveDraft(editing: MoveFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Move", design.features.count { it is MoveFeature })
        var dx by mutableStateOf(editing?.dx ?: 0.0)
        var dy by mutableStateOf(editing?.dy ?: 0.0)
        var dz by mutableStateOf(editing?.dz ?: 0.0)
        var axis by mutableStateOf(editing?.axis ?: Axis3.Z)
        var degrees by mutableStateOf((editing?.angle ?: 0.0) * 180 / PI)
        var copy by mutableStateOf(editing?.copy ?: false)
        var sx by mutableStateOf(editing?.sx ?: 1.0)
        var sy by mutableStateOf(editing?.sy ?: 1.0)
        var sz by mutableStateOf(editing?.sz ?: 1.0)
        /** The same scale every way, or one for each. */
        var evenly by mutableStateOf(editing == null || (editing.sx == editing.sy && editing.sy == editing.sz))
        init { if (editing != null) bodies = editing.bodies }
        override fun feature() =
            if (evenly) MoveFeature(id, name, bodies, dx, dy, dz, axis, degrees * PI / 180, copy, sx, sx, sx)
            else MoveFeature(id, name, bodies, dx, dy, dz, axis, degrees * PI / 180, copy, sx, sy, sz)
    }

    inner class PlaneDraft(editing: PlaneFeature?, val kind: PlaneFeature.Kind) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Plane", design.features.count { it is PlaneFeature })
        var planes: List<Pair<String, PlaneRef>> = emptyList()
        var base by mutableStateOf<PlaneRef>(editing?.base ?: PlaneRef.Fixed(SketchPlane.Top))
        var other by mutableStateOf<PlaneRef?>(editing?.other)
        var offset by mutableStateOf(editing?.offset ?: 10.0)
        var degrees by mutableStateOf((editing?.angle ?: (PI / 4)) * 180 / PI)
        var turnRoundY by mutableStateOf(editing?.turnRoundY ?: false)
        override fun feature(): Feature? {
            if (kind == PlaneFeature.Kind.Midway && other == null) return null
            return PlaneFeature(id, name, kind, base, offset, degrees * PI / 180, turnRoundY, other)
        }
        override fun missing() = "Pick the second plane"
    }

    inner class PrimitiveDraft(editing: com.rm.parrotmetric.design.PrimitiveFeature?, kind: com.rm.parrotmetric.design.PrimitiveKind) : FeatureDraft() {
        val id = editing?.id ?: newId()
        val kind = editing?.kind ?: kind
        private val name = editing?.name ?: nextName(this.kind.name, design.features.count { it is com.rm.parrotmetric.design.PrimitiveFeature && it.kind == this.kind })
        var planes: List<Pair<String, PlaneRef>> = emptyList()
        var plane by mutableStateOf<PlaneRef>(editing?.plane ?: PlaneRef.Fixed(SketchPlane.Top))
        var u by mutableStateOf(editing?.u ?: 0.0)
        var v by mutableStateOf(editing?.v ?: 0.0)
        var a by mutableStateOf(editing?.a ?: when (this.kind) {
            com.rm.parrotmetric.design.PrimitiveKind.Torus -> 40.0
            else -> 20.0
        })
        var b by mutableStateOf(editing?.b ?: when (this.kind) {
            com.rm.parrotmetric.design.PrimitiveKind.Torus -> 8.0
            com.rm.parrotmetric.design.PrimitiveKind.Cone -> 0.0
            else -> 20.0
        })
        var c by mutableStateOf(editing?.c ?: 20.0)
        var operation by mutableStateOf(editing?.operation ?: Operation.NewBody)
        override fun feature() = com.rm.parrotmetric.design.PrimitiveFeature(id, name, kind, plane, u, v, a, b, c, operation)
        override fun missing() = ""
    }

    inner class PointDraft(editing: PointFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Point", design.features.count { it is PointFeature })
        var x by mutableStateOf(editing?.x ?: 0.0)
        var y by mutableStateOf(editing?.y ?: 0.0)
        var z by mutableStateOf(editing?.z ?: 0.0)
        override fun feature() = PointFeature(id, name, x, y, z)
        override fun missing() = ""
    }

    inner class AxisDraft(editing: AxisFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Axis", design.features.count { it is AxisFeature })
        var x by mutableStateOf(editing?.x ?: 0.0)
        var y by mutableStateOf(editing?.y ?: 0.0)
        var z by mutableStateOf(editing?.z ?: 0.0)
        var along by mutableStateOf(editing?.along ?: Axis3.Z)
        override fun feature() = AxisFeature(id, name, x, y, z, along)
        override fun missing() = ""
    }

    inner class EdgeDraft(editing: Feature?, val chamfer: Boolean) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = when (editing) {
            null -> if (chamfer) "Chamfer ${design.features.count { it is ChamferFeature } + 1}" else "Fillet ${design.features.count { it is FilletFeature } + 1}"
            else -> editing.name
        }
        var edges by mutableStateOf<List<String>>(emptyList())
        var size by mutableStateOf(if (chamfer) 1.0 else 2.0)
        var kind by mutableStateOf(ChamferKind.Equal)
        /** The second distance in mm, or the angle in degrees. */
        var second by mutableStateOf(1.0)
        var flip by mutableStateOf(false)

        init {
            when (editing) {
                is FilletFeature -> { edges = editing.edges; size = editing.radius }
                is ChamferFeature -> {
                    edges = editing.edges; size = editing.distance; kind = editing.kind; flip = editing.flip
                    second = if (editing.kind == ChamferKind.DistanceAngle) editing.second * 180 / PI else editing.second
                }
                else -> {}
            }
        }

        override fun feature(): Feature? {
            if (edges.isEmpty()) return null
            if (!chamfer) return FilletFeature(id, name, edges, size)
            return when (kind) {
                ChamferKind.Equal -> ChamferFeature(id, name, edges, size)
                ChamferKind.TwoDistances -> ChamferFeature(id, name, edges, size, kind, second, flip)
                ChamferKind.DistanceAngle -> ChamferFeature(id, name, edges, size, kind, second * PI / 180, flip)
            }
        }

        override fun missing() = "Tap the edges to ${if (chamfer) "bevel" else "round"}"
    }
}
