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
import com.rm.parrotmetric.design.PointRef
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
        planes: List<SketchPlane>, axes: List<Pair<Vec3, Vec3>>, points: List<Vec3>, colours: List<Int>,
        canvases: List<com.rm.parrotmetric.design.PlacedCanvas>, refit: Boolean,
    )
    /** Reads a picture and keeps it under key for canvases; its width and height in pixels, or null if it can't be read. */
    fun canvasImage(key: Int, bytes: ByteArray): IntArray?
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
    /** Colours bodies to check them for printing: 0 off, 1 overhangs past limit radians, 2 walls thinner than limit mm. */
    fun setAnalysis(mode: Int, limit: Double) {}
    /** A face's edges as curves on a plane, for projecting into a sketch. Throws if the face is gone. */
    fun faceOutline(body: Long, face: String, plane: SketchPlane): List<ProfileCurve>
    fun selectedEdges(): List<String>
    /** Names of the selected corners of solids. */
    fun selectedCorners(): List<String>
    /** Selected faces as body number (in the order shown) and face name. */
    fun selectedFaces(): List<Pair<Int, String>>
    /** Selected sketch areas as sketch number (in the order shown) and area number. */
    fun selectedRegions(): List<Pair<Int, Int>>
    fun select(edges: List<String>, regions: List<Pair<Int, Int>>, faces: List<String> = emptyList(), corners: List<String> = emptyList())
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
    /** The tool that makes this kind of step, for its icon, or null. */
    val tool: String? = null,
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
                is ExtrudeFeature, is RevolveFeature, is com.rm.parrotmetric.design.PrimitiveFeature, is com.rm.parrotmetric.design.SweepFeature,
                is com.rm.parrotmetric.design.PipeFeature, is com.rm.parrotmetric.design.CoilFeature, is com.rm.parrotmetric.design.LoftFeature -> HistoryEntry.Kind.Create
                is ImportFeature -> HistoryEntry.Kind.Import
                is PlaneFeature, is AxisFeature, is PointFeature, is com.rm.parrotmetric.design.CanvasFeature -> HistoryEntry.Kind.Construct
                else -> HistoryEntry.Kind.Modify
            }
            val off = f.id in design.suppressed
            HistoryEntry(f.id, f.name, kind, if (off) null else errors[f.id], i < design.marker, if (off) null else warnings[f.id], off, toolFor(f))
        }
    }

    /** The tool a step comes from, by the tool ids in Tools, for its icon. Named here, since release builds rename classes. */
    private fun toolFor(f: Feature): String? = when (f) {
        is com.rm.parrotmetric.design.PrimitiveFeature -> f.kind.name.lowercase()
        is com.rm.parrotmetric.design.MeshEditFeature -> f.kind.name.lowercase()
        is ExtrudeFeature -> "extrude"
        is RevolveFeature -> "revolve"
        is com.rm.parrotmetric.design.SweepFeature -> "sweep"
        is com.rm.parrotmetric.design.LoftFeature -> "loft"
        is com.rm.parrotmetric.design.PipeFeature -> "pipe"
        is com.rm.parrotmetric.design.CoilFeature -> "coil"
        is com.rm.parrotmetric.design.PatchFeature -> "patch"
        is com.rm.parrotmetric.design.StitchFeature -> "stitch"
        is com.rm.parrotmetric.design.ThickenFeature -> "thicken"
        is FilletFeature -> "fillet"
        is ChamferFeature -> "chamfer"
        is com.rm.parrotmetric.design.ShellFeature -> "shell"
        is com.rm.parrotmetric.design.OffsetFaceFeature -> "presspull"
        is com.rm.parrotmetric.design.DeleteFaceFeature -> "deleteface"
        is com.rm.parrotmetric.design.DraftFeature -> "draft"
        is com.rm.parrotmetric.design.RibFeature -> if (f.web) "web" else "rib"
        is com.rm.parrotmetric.design.EmbossFeature -> "emboss"
        is HoleFeature -> "hole"
        is com.rm.parrotmetric.design.ThreadFeature -> "thread"
        is com.rm.parrotmetric.design.LipFeature -> "lip"
        is MirrorFeature -> "mirror"
        is PatternFeature -> "pattern"
        is com.rm.parrotmetric.design.JointFeature -> "joint"
        is CombineFeature -> "combine"
        is com.rm.parrotmetric.design.SplitFeature -> "split"
        is com.rm.parrotmetric.design.MoveFeature -> "move"
        is com.rm.parrotmetric.design.AlignFeature -> "align"
        is com.rm.parrotmetric.design.ConvertFeature -> "tosolid"
        is PlaneFeature -> "plane.offset"
        is AxisFeature -> "axis"
        is PointFeature -> "point"
        is com.rm.parrotmetric.design.CanvasFeature -> "canvas"
        is ImportFeature -> "open"
        else -> null
    }

    /** Every body there is now, labels and handles, hidden ones too. */
    fun allBodies(): List<com.rm.parrotmetric.design.BodyState> = built?.bodies ?: emptyList()

    /** The body with this face, if one has it. */
    fun bodyWithFace(face: String): String? = allBodies().firstOrNull { face in kernel.faceNames(it.handle) }?.label

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

    /** Colouring bodies to check them: 0 off, 1 overhangs, 2 thin walls, 3 zebra stripes, 4 curvature. */
    var printCheck by mutableStateOf(0)
    var zebraStripes by mutableStateOf(8.0)
    /** Curves this tight or tighter show in full colour, mm. */
    var curvatureRadius by mutableStateOf(10.0)
    /** Overhangs steeper than this from straight up need support, degrees. */
    var overhangAngle by mutableStateOf(45.0)
    var thinWall by mutableStateOf(1.2)

    fun updatePrintCheck() {
        val limit = when (printCheck) {
            1 -> (90 - overhangAngle) * kotlin.math.PI / 180
            2 -> thinWall
            3 -> zebraStripes
            else -> curvatureRadius
        }
        viewport.setAnalysis(printCheck, limit)
        rebuild()
    }

    fun stopPrintCheck() {
        printCheck = 0
        updatePrintCheck()
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
                SketchPlane("Face", ref.origin(Vec3(it[0], it[1], it[2]), n), x, n.cross(x))
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

    /** Shows or hides every body in a component. */
    fun setComponentHidden(component: String, hidden: Boolean) {
        checkpoint()
        for (b in allBodies()) if (design.info(b.label).component == component) design.bodies[b.label] = design.info(b.label).copy(hidden = hidden)
        changed()
    }

    /** The component new bodies go into as they're made, or null for none. */
    var activeComponent by mutableStateOf<String?>(null)

    // The bodies there were after the last build, so ones a step has just made can be told apart.
    private var knownLabels: Set<String>? = null
    private val placed = mutableSetOf<String>()

    /** Construction planes there are, by id and name, for the parts list. */
    fun planes(): List<Pair<Int, String>> = design.active.filterIsInstance<PlaneFeature>().map { it.id to it.name }

    fun setPlaneHidden(id: Int, hidden: Boolean) {
        checkpoint()
        if (hidden) design.hiddenPlanes += id else design.hiddenPlanes -= id
        changed()
    }

    /** 0xRRGGBB, or null for the usual grey. */
    fun setColour(label: String, colour: Int?) = setInfo(label) { it.copy(colour = colour) }

    /** Picked for Measure's mass: by its index in Materials. */
    var material by mutableStateOf(0)

    /** The body under the first selected face or edge, else the only body shown: its label and properties (Kernel.properties). */
    fun measuredBody(): Pair<String, DoubleArray>? {
        val shown = shownBodies
        val index = viewport.selectedFaces().firstOrNull()?.first ?: if (shown.size == 1) 0 else return null
        val b = shown.getOrNull(index) ?: return null
        return b.label to (kernel.properties(b.handle) ?: return null)
    }

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
        return ref to SketchPlane("On a face", ref.origin(Vec3(d[0], d[1], d[2]), n), x, n.cross(x))
    }

    /** The bodies shown now, by label and the name they're shown by. */
    fun shownNames(): List<Pair<String, String>> = shownBodies.map { it.label to design.nameOf(it.label) }

    /** A shown body's outline flattened onto a plane: where its middle, along the plane's normal, crosses it. */
    fun bodyOutline(label: String, plane: SketchPlane): List<ProfileCurve>? = try {
        val b = shownBodies.firstOrNull { it.label == label }
        b?.let {
            val box = kernel.bounds(it.handle)
            val centre = com.rm.parrotmetric.sketch.Vec3((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2)
            viewport.section(listOf(it.handle), plane.copy(origin = plane.origin + plane.normal * (centre - plane.origin).dot(plane.normal)))
        }?.ifEmpty { null }
    } catch (e: RuntimeException) {
        null
    }

    /** The labels of the bodies shown now, as a projection through them remembers them. */
    fun shownLabels(): List<String> = shownBodies.map { it.label }

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

    /** A plane or flat face is picked, that a sketch could be moved onto. */
    fun hasPickedPlane(): Boolean =
        viewport.selectedPlanes().size == 1 || viewport.selectedFaces().count { it.second.isNotEmpty() } == 1

    /**
     * Puts a sketch on the picked construction plane or flat face, keeping
     * what's drawn in it, its x running as near as it can to the way it did.
     */
    fun moveSketch(id: Int) {
        val f = design.features.firstOrNull { it.id == id } as? SketchFeature ?: return
        val was = built?.sketchPlanes?.get(id)
        val ref = viewport.selectedPlanes().singleOrNull()?.let { i -> shownPlanes.getOrNull(i)?.let { PlaneRef.Construction(it.id) } }
            ?: viewport.selectedFaces().filter { it.second.isNotEmpty() }.singleOrNull()?.let { PlaneRef.OnFace(it.second, was?.x ?: Vec3(1.0, 0.0, 0.0)) }
            ?: run { message = "Pick a plane or a flat face to move it onto"; return }
        if (ref is PlaneRef.Construction && ref.featureId == id) return
        checkpoint()
        design.replace(SketchFeature(f.id, f.name, ref, f.sketch))
        changed()
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
                val components = design.bodies.mapNotNull { (label, info) -> info.component?.let { label to it } }.toMap()
                val draft = panel
                // The first body to appear is framed.
                val hadBodies = built?.bodies?.isNotEmpty() == true
                val result = withContext(Dispatchers.Default) {
                    lock.withLock {
                        val b = rebuilder.rebuild(features, hints, components)
                        val sketches = sketchesToShow(features, draft)
                        val shown = sketches.mapNotNull { s -> b.sketchPlanes[s.id]?.let { s to it } }
                        val refit = refitNow || (!hadBodies && b.bodies.isNotEmpty())
                        val planeFeatures = features.filterIsInstance<PlaneFeature>().filter { b.sketchPlanes.containsKey(it.id) && it.id !in design.hiddenPlanes }
                        val visible = b.bodies.filter { !design.info(it.label).hidden }
                        shownBodies = visible
                        viewport.show(
                            visible.map { it.handle }, shown.map { it.second to it.first.sketch.profileCurves() },
                            planeFeatures.map { b.sketchPlanes.getValue(it.id) }, b.axes.values.toList(), b.points.values.toList(), visible.map { design.info(it.label).colour ?: -1 }, canvasesToShow(b), refit,
                        )
                        shownPlanes = planeFeatures
                        Triple(b, shown.map { it.first }, draft)
                    }
                }
                built = result.first
                // New bodies go into the component that's taking them.
                val labels = result.first.bodies.map { it.label }.toSet()
                val into = activeComponent
                if (into != null) knownLabels?.let { known ->
                    for (l in labels - known) if (design.info(l).component == null) {
                        design.bodies[l] = design.info(l).copy(component = into)
                        placed += l
                    }
                }
                // A body put in a component that's gone again (a cancelled preview) is let go.
                for (l in placed - labels) if (design.info(l).component != null) design.bodies[l] = design.info(l).copy(component = null)
                placed.retainAll(labels)
                knownLabels = labels
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

    fun useConfiguration(name: String) {
        checkpoint()
        design.useConfiguration(name)
        changed()
    }

    fun saveConfiguration(name: String) {
        checkpoint()
        design.saveConfiguration(name)
        changed()
    }

    fun removeConfiguration(name: String) {
        checkpoint()
        design.removeConfiguration(name)
        changed()
    }

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
        fun pathSketch(p: com.rm.parrotmetric.design.PathRef?) = (p as? com.rm.parrotmetric.design.PathRef.Sketch)?.sketchId
        val used = features.flatMap {
            when (it) {
                is ExtrudeFeature -> listOf(it.sketchId)
                is RevolveFeature -> listOf(it.sketchId)
                is HoleFeature -> listOf(it.sketchId)
                is com.rm.parrotmetric.design.RibFeature -> listOf(it.sketchId)
                is com.rm.parrotmetric.design.EmbossFeature -> listOf(it.sketchId)
                is com.rm.parrotmetric.design.SweepFeature -> listOfNotNull(it.sketchId, pathSketch(it.path))
                is com.rm.parrotmetric.design.PipeFeature -> listOfNotNull(pathSketch(it.path))
                is com.rm.parrotmetric.design.LoftFeature -> it.sections.map { s -> s.sketchId }
                is com.rm.parrotmetric.design.PatchFeature -> listOfNotNull(it.sketchId)
                is PatternFeature -> listOfNotNull(pathSketch(it.path))
                else -> emptyList()
            }
        }.toSet()
        val keep = (draft as? AreaDraft)?.sketchId
        return features.filterIsInstance<SketchFeature>().filter { it.id !in used || it.id == keep }
    }

    // Panels.

    /** Opens Extrude or Revolve, starting from any sketch areas already selected. */
    fun startExtrude() = openArea(ExtrudeDraft(null).also { it.planes = planeChoices() })
    fun startRevolve() = openArea(RevolveDraft(null))
    fun startSweep() {
        val d = SweepDraft(null)
        openArea(d)
        // The path is most often the newest other sketch.
        if (!d.pathByEdges && d.pathSketch == null) {
            d.pathSketch = sketchChoices(d.sketchId).lastOrNull()?.second
            if (d.pathSketch != null) rebuild()
        }
    }

    fun startPatch() {
        val d = PatchDraft(null)
        d.edges = viewport.selectedEdges()
        d.byEdges = d.edges.isNotEmpty()
        openArea(d)
    }

    fun startStitch() = openBodies(StitchDraft(null))

    fun startEmboss() {
        val d = EmbossDraft(null)
        embossFace(d)
        openArea(d)
        // Text drawn through a part is inside it, where its areas can't be tapped.
        if (d.regions.isEmpty()) wholeSketch(d)
    }

    /** The newest sketch's areas, leaving out holes such as the middle of a letter. */
    private fun wholeSketch(d: AreaDraft) {
        for (f in design.active.filterIsInstance<SketchFeature>().reversed()) {
            val regions = finder.find(f.sketch.profileCurves())
            if (regions.isEmpty()) continue
            // An area inside an odd number of other outlines is a hole in one of them.
            val solid = regions.filter { r -> regions.count { o -> o !== r && insideLoop(o.loops[0], r.insideU, r.insideV) } % 2 == 0 }
            d.sketchId = f.id
            d.regions = solid.map { RegionRef(it.curveIds, it.insideU, it.insideV) }
            rebuild()
            return
        }
    }

    /** Whether (u, v) is inside a loop of x, y pairs. */
    private fun insideLoop(loop: FloatArray, u: Double, v: Double): Boolean {
        var inside = false
        var j = loop.size - 2
        for (i in 0 until loop.size step 2) {
            val xi = loop[i].toDouble(); val yi = loop[i + 1].toDouble()
            val xj = loop[j].toDouble(); val yj = loop[j + 1].toDouble()
            if ((yi > v) != (yj > v) && u < (xj - xi) * (v - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun embossFace(d: EmbossDraft) {
        viewport.selectedFaces().lastOrNull { it.second.isNotEmpty() }?.let { d.face = it.second }
    }

    fun startJoint() {
        val comps = components()
        if (comps.isEmpty()) {
            message = "Put bodies into components first, in the parts list"
            return
        }
        val d = JointDraft(null)
        // The component of the body tapped, if any, moves.
        pickedBodies().firstOrNull()?.let { design.info(it).component }?.let { d.moving = it }
        jointPick(d)
        panel = d
        rebuild()
    }

    private fun jointPick(d: JointDraft) {
        viewport.selectedEdges().lastOrNull()?.let { d.edge = it; d.face = null }
            ?: viewport.selectedFaces().lastOrNull { it.second.isNotEmpty() }?.let { d.face = it.second; d.edge = null }
    }
    fun startThicken() = openBodies(ThickenDraft(null))

    fun startPipe() {
        val d = PipeDraft(null)
        d.pathEdges = viewport.selectedEdges()
        d.pathByEdges = d.pathEdges.isNotEmpty() || sketchChoices(null).isEmpty()
        if (!d.pathByEdges) d.pathSketch = sketchChoices(null).lastOrNull()?.second
        panel = d
        rebuild()
    }

    fun startCoil() {
        val d = CoilDraft(null)
        d.planes = planeChoices()
        d.planes.firstOrNull { it.first == "The face" }?.let { d.plane = it.second }
        panel = d
        rebuild()
    }

    fun startThread() {
        val d = ThreadDraft(null)
        threadPick(d)
        panel = d
        rebuild()
    }

    fun startLip() {
        val d = LipDraft(null)
        lipPick(d)
        panel = d
        rebuild()
    }

    /** A lip's rim from the selection: the first flat face. */
    private fun lipPick(d: LipDraft) {
        d.face = viewport.selectedFaces().map { it.second }.firstOrNull { it.isNotEmpty() && faceKind(it) == 4.0 } ?: return
    }

    fun startLoft() {
        val d = LoftDraft(null)
        d.sections = loftPicks()
        panel = d
        rebuild()
    }

    /** Sketches to sweep along, by name, leaving out [except]. */
    fun sketchChoices(except: Int?): List<Pair<String, Int>> =
        design.active.filterIsInstance<SketchFeature>().filter { it.id != except }.map { it.name to it.id }

    /** The areas picked, as one per sketch in the order tapped, for a loft. */
    private fun loftPicks(): List<com.rm.parrotmetric.design.LoftSection> {
        val out = mutableListOf<com.rm.parrotmetric.design.LoftSection>()
        for ((s, r) in viewport.selectedRegions()) {
            val sketch = shownSketches.getOrNull(s) ?: continue
            if (out.any { it.sketchId == sketch.id }) continue
            val region = finder.find(sketch.sketch.profileCurves()).getOrNull(r) ?: continue
            out += com.rm.parrotmetric.design.LoftSection(sketch.id, RegionRef(region.curveIds, region.insideU, region.insideV))
        }
        return out
    }

    /** A thread's face from the selection, the first round one, and the ISO size that fits it. */
    private fun threadPick(d: ThreadDraft) {
        val face = viewport.selectedFaces().map { it.second }.firstOrNull { it.isNotEmpty() && faceKind(it) == 2.0 } ?: return
        d.face = face
        val r = shownBodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, face, false) }?.get(7) ?: return
        d.pitch = ThreadSizes.fitting(2 * r).second
    }

    /** Areas for a sweep, from the selection; kept as they were if none are picked, as when picking its path. */
    private fun takeAreas(d: AreaDraft) {
        val picked = viewport.selectedRegions()
        if (picked.isEmpty()) return
        val sketch = shownSketches.getOrNull(picked[0].first) ?: return
        val regions = finder.find(sketch.sketch.profileCurves())
        d.sketchId = sketch.id
        d.regions = picked.filter { it.first == picked[0].first }.mapNotNull { (_, r) ->
            regions.getOrNull(r)?.let { RegionRef(it.curveIds, it.insideU, it.insideV) }
        }
    }

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

    fun startShell() = openFaces(FaceDraft(null, FaceTool.Shell))
    fun startDraft() = openFaces(FaceDraft(null, FaceTool.Draft))
    fun startPressPull() = openFaces(FaceDraft(null, FaceTool.PressPull))
    fun startDeleteFace() = openFaces(FaceDraft(null, FaceTool.Delete))

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
        if (pick != null && kind != PlaneFeature.Kind.Tangent) d.base = pick
        constructionPicks(d)
        panel = d
        rebuild()
    }

    /** A construction draft's picks, shown selected again, so more can be added to them. */
    private fun showPicks(points: List<PointRef>, edges: List<String>, faces: List<String>) = viewport.select(
        edges + points.mapNotNull { (it as? PointRef.CentreOf)?.edge }, emptyList(), faces,
        points.mapNotNull { (it as? PointRef.Corner)?.name },
    )

    /** Corners and the centres of round edges picked in the view, in that order. */
    private fun pickedPoints(): List<PointRef> =
        viewport.selectedCorners().map { PointRef.Corner(it) } + viewport.selectedEdges().filter { edgeKind(it) == 1.0 }.map { PointRef.CentreOf(it) }

    /** What shape a named edge is (Kernel.shapeOf's kind), or null. */
    private fun edgeKind(name: String): Double? = shownBodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, name, true) }?.get(0)

    /** What shape a named face is (Kernel.shapeOf's kind), or null. */
    private fun faceKind(name: String): Double? = shownBodies.firstNotNullOfOrNull { kernel.shapeOf(it.handle, name, false) }?.get(0)

    /** Planes picked in the view: construction planes and flat faces. */
    private fun pickedPlanes(): List<PlaneRef> =
        viewport.selectedPlanes().mapNotNull { shownPlanes.getOrNull(it)?.let { p -> PlaneRef.Construction(p.id) } } +
            viewport.selectedFaces().map { it.second }.filter { it.isNotEmpty() && faceKind(it) == 4.0 }.map { PlaneRef.OnFace(it, Vec3(1.0, 0.0, 0.0)) }

    /** Fills a construction draft's picks from the selection, for kinds that use them. */
    private fun constructionPicks(d: FeatureDraft) {
        when (d) {
            is PlaneDraft -> when (d.kind) {
                PlaneFeature.Kind.ThreePoints -> d.points = pickedPoints()
                PlaneFeature.Kind.TwoEdges -> d.edges = viewport.selectedEdges().filter { edgeKind(it) == 0.0 }
                PlaneFeature.Kind.AlongEdge -> d.edges = viewport.selectedEdges().take(1)
                PlaneFeature.Kind.Tangent -> viewport.selectedFaces().map { it.second }.firstOrNull { it.isNotEmpty() && faceKind(it) == 2.0 }?.let { d.face = it }
                else -> {}
            }
            is AxisDraft -> when (d.kind) {
                AxisFeature.Kind.Edge -> d.edge = viewport.selectedEdges().firstOrNull { edgeKind(it) == 0.0 }
                AxisFeature.Kind.Round -> {
                    d.face = viewport.selectedFaces().map { it.second }.firstOrNull { it.isNotEmpty() && faceKind(it) == 2.0 }
                    d.edge = if (d.face == null) viewport.selectedEdges().firstOrNull { edgeKind(it) == 1.0 } else null
                }
                AxisFeature.Kind.TwoPoints -> d.points = pickedPoints()
                else -> {}
            }
            is PointDraft -> when (d.kind) {
                PointFeature.Kind.At -> pickedPoints().firstOrNull()?.let { d.ref = it }
                PointFeature.Kind.ThreePlanes -> d.planes = pickedPlanes()
                else -> {}
            }
            else -> {}
        }
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
        // The kind that suits what's picked: a corner or round edge, three planes, else where a flat face's middle is.
        d.kind = when {
            pickedPoints().isNotEmpty() -> PointFeature.Kind.At
            pickedPlanes().size >= 3 -> PointFeature.Kind.ThreePlanes
            else -> PointFeature.Kind.Fixed
        }
        if (d.kind == PointFeature.Kind.Fixed) viewport.selectedFaces().firstOrNull { it.second.isNotEmpty() }?.let { (b, face) ->
            shownBodies.getOrNull(b)?.let { body -> kernel.facePlane(body.handle, face) }?.let { c -> d.x = c[0]; d.y = c[1]; d.z = c[2] }
        }
        constructionPicks(d)
        panel = d
        rebuild()
    }

    /** Switches a construction draft to another kind, taking its picks from the selection. */
    fun setConstructionKind(d: FeatureDraft, kind: Enum<*>) {
        when (d) {
            is AxisDraft -> d.kind = kind as AxisFeature.Kind
            is PointDraft -> d.kind = kind as PointFeature.Kind
            else -> {}
        }
        constructionPicks(d)
        rebuild()
    }

    fun startAxis() {
        val d = AxisDraft(null)
        // The kind that suits what's picked.
        val edges = viewport.selectedEdges()
        d.kind = when {
            pickedPoints().size >= 2 -> AxisFeature.Kind.TwoPoints
            viewport.selectedFaces().any { it.second.isNotEmpty() && faceKind(it.second) == 2.0 } || edges.any { edgeKind(it) == 1.0 } -> AxisFeature.Kind.Round
            edges.any { edgeKind(it) == 0.0 } -> AxisFeature.Kind.Edge
            else -> AxisFeature.Kind.Fixed
        }
        constructionPicks(d)
        panel = d
        rebuild()
    }

    /** Pictures given to the view, by feature id, so each is read once. */
    private val registered = mutableMapOf<Int, ByteArray>()

    /** The canvases built, their pictures given to the view first. */
    private fun canvasesToShow(b: Built): List<com.rm.parrotmetric.design.PlacedCanvas> = b.canvases.values.filter { c ->
        if (registered[c.featureId] !== c.image) {
            if (viewport.canvasImage(c.featureId, c.image) == null) return@filter false
            registered[c.featureId] = c.image
        }
        true
    }

    /** Starts a canvas from a picture file. */
    fun startCanvas(name: String, bytes: ByteArray) {
        val d = CanvasDraft(null, bytes)
        val size = viewport.canvasImage(d.id, bytes)
        if (size == null || size[0] <= 0) {
            message = "That picture couldn't be read: use PNG or JPEG"
            return
        }
        registered[d.id] = bytes
        d.aspect = size[1].toDouble() / size[0]
        d.label = name.substringBeforeLast('.')
        d.planes = planeChoices()
        d.planes.firstOrNull { it.first == "The face" }?.let { d.plane = it.second }
        panel = d
        rebuild()
    }

    /** Construction points as last built, for Project in sketches. */
    fun constructionPoints(): List<Vec3> = built?.points?.values?.toList().orEmpty()

    /** Features before [id] that add or take away a shape, to pattern or mirror. */
    fun toolFeatures(id: Int): List<Feature> {
        val all = design.active
        val i = all.indexOfFirst { it.id == id }.let { if (it < 0) all.size else it }
        return all.subList(0, i).filter {
            it is ExtrudeFeature || it is RevolveFeature || it is HoleFeature || it is com.rm.parrotmetric.design.SweepFeature ||
                it is com.rm.parrotmetric.design.PipeFeature || it is com.rm.parrotmetric.design.CoilFeature ||
                it is com.rm.parrotmetric.design.LoftFeature || it is com.rm.parrotmetric.design.PrimitiveFeature
        }
    }

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
    fun startScale() = openBodies(MoveDraft(null, scaling = true))

    fun startAlign() {
        val d = AlignDraft(null)
        alignPicks(d)
        panel = d
        rebuild()
    }

    /** Align's picks from the selection: the first face tapped moves, the second is where to. */
    private fun alignPicks(d: AlignDraft) {
        val faces = viewport.selectedFaces().filter { it.second.isNotEmpty() }
        faces.getOrNull(0)?.let { d.face = it.second }
        val choices = planeChoices().filter { it.first != "The face" }.toMutableList()
        faces.getOrNull(1)?.let { (_, f) ->
            val ref = PlaneRef.OnFace(f, Vec3(1.0, 0.0, 0.0))
            choices.add(3, "The face" to ref)
            d.target = ref
        }
        (d.target as? PlaneRef.OnFace)?.let { t -> if (choices.none { it.second == t }) choices.add(3, "The face" to t) }
        d.planes = choices
    }

    /** Pairs of shown bodies that overlap, and by how much (mm³). */
    suspend fun interference(): List<Triple<String, String, Double>> = withContext(Dispatchers.Default) {
        lock.withLock {
            val shown = shownBodies
            val out = mutableListOf<Triple<String, String, Double>>()
            for (i in shown.indices) for (j in i + 1 until shown.size) {
                val v = kernel.overlapVolume(shown[i].handle, shown[j].handle)
                if (v > 1e-6) out += Triple(shown[i].label, shown[j].label, v)
            }
            out
        }
    }
    fun startPlaneCut() = openBodies(SplitDraft(null).also { it.keep = 1 })
    fun startConvert() = openBodies(ConvertDraft(null))

    fun startRib(web: Boolean) {
        panel = RibDraft(null, web)
        rebuild()
    }
    fun startMeshEdit(kind: com.rm.parrotmetric.design.MeshEdit) = openBodies(MeshEditDraft(null, kind))

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
            is ShellFeature -> FaceDraft(f, FaceTool.Shell)
            is DraftFeature -> FaceDraft(f, FaceTool.Draft)
            is com.rm.parrotmetric.design.OffsetFaceFeature -> FaceDraft(f, FaceTool.PressPull)
            is com.rm.parrotmetric.design.DeleteFaceFeature -> FaceDraft(f, FaceTool.Delete)
            is HoleFeature -> HoleDraft(f)
            is MirrorFeature -> MirrorDraft(f)
            is PatternFeature -> PatternDraft(f)
            is CombineFeature -> CombineDraft(f)
            is SplitFeature -> SplitDraft(f)
            is MoveFeature -> MoveDraft(f, scaling = f.dx == 0.0 && f.dy == 0.0 && f.dz == 0.0 && f.angle == 0.0 && f.scaled)
            is com.rm.parrotmetric.design.AlignFeature -> AlignDraft(f).also { alignPicks(it) }
            is com.rm.parrotmetric.design.ConvertFeature -> ConvertDraft(f)
            is com.rm.parrotmetric.design.MeshEditFeature -> MeshEditDraft(f, f.kind)
            is com.rm.parrotmetric.design.RibFeature -> RibDraft(f, f.web)
            is com.rm.parrotmetric.design.PatchFeature -> PatchDraft(f)
            is com.rm.parrotmetric.design.EmbossFeature -> EmbossDraft(f)
            is com.rm.parrotmetric.design.JointFeature -> JointDraft(f)
            is com.rm.parrotmetric.design.StitchFeature -> StitchDraft(f)
            is com.rm.parrotmetric.design.ThickenFeature -> ThickenDraft(f)
            is PointFeature -> PointDraft(f)
            is PlaneFeature -> PlaneDraft(f, f.kind).also { it.planes = planeChoices().filter { c -> c.second != PlaneRef.Construction(f.id) } }
            is AxisFeature -> AxisDraft(f)
            is com.rm.parrotmetric.design.PrimitiveFeature -> PrimitiveDraft(f, f.kind).also { it.planes = planeChoices() }
            is com.rm.parrotmetric.design.SweepFeature -> SweepDraft(f)
            is com.rm.parrotmetric.design.PipeFeature -> PipeDraft(f)
            is com.rm.parrotmetric.design.CoilFeature -> CoilDraft(f).also { it.planes = planeChoices() }
            is com.rm.parrotmetric.design.ThreadFeature -> ThreadDraft(f)
            is com.rm.parrotmetric.design.LipFeature -> LipDraft(f)
            is com.rm.parrotmetric.design.LoftFeature -> LoftDraft(f)
            is com.rm.parrotmetric.design.CanvasFeature -> CanvasDraft(f, f.image).also { it.planes = planeChoices() }
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
            is JointDraft -> {
                jointPick(d)
                rebuild()
            }
            is EmbossDraft -> {
                takeAreas(d)
                embossFace(d)
                rebuild()
            }
            is PatchDraft -> {
                if (d.byEdges) d.edges = viewport.selectedEdges() else takeAreas(d)
                rebuild()
            }
            is SweepDraft -> {
                takeAreas(d)
                if (d.pathByEdges) d.pathEdges = viewport.selectedEdges()
                rebuild()
            }
            is PipeDraft -> if (d.pathByEdges) {
                d.pathEdges = viewport.selectedEdges()
                rebuild()
            }
            is LoftDraft -> {
                // A tap on empty space keeps what's picked, as Sweep does.
                loftPicks().takeIf { it.isNotEmpty() }?.let { d.sections = it }
                rebuild()
            }
            is ThreadDraft -> {
                threadPick(d)
                rebuild()
            }
            is LipDraft -> {
                lipPick(d)
                rebuild()
            }
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
            is AlignDraft -> {
                alignPicks(d)
                rebuild()
            }
            is PlaneDraft, is AxisDraft, is PointDraft -> {
                constructionPicks(d)
                rebuild()
            }
            is BodyDraft -> {
                if (d is PatternDraft && d.alongPath && d.pathByEdges) {
                    d.pathEdges = viewport.selectedEdges()
                    rebuild()
                    if (d.byFeatures) return
                }
                if ((d as? PatternDraft)?.byFeatures == true || (d as? MirrorDraft)?.byFeatures == true) return
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
            is PatchDraft -> viewport.select(d.edges, regionPairs(d.sketchId, d.regions))
            is EmbossDraft -> viewport.select(emptyList(), regionPairs(d.sketchId, d.regions), listOfNotNull(d.face))
            is JointDraft -> viewport.select(listOfNotNull(d.edge), emptyList(), listOfNotNull(d.face))
            is SweepDraft -> viewport.select(d.pathEdges, regionPairs(d.sketchId, d.regions))
            is PipeDraft -> viewport.select(d.pathEdges, emptyList())
            is LoftDraft -> viewport.select(emptyList(), d.sections.flatMap { regionPairs(it.sketchId, listOf(it.region)) })
            is ThreadDraft -> viewport.select(emptyList(), emptyList(), listOfNotNull(d.face))
            is LipDraft -> viewport.select(emptyList(), emptyList(), listOfNotNull(d.face))
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
            is AlignDraft -> viewport.select(emptyList(), emptyList(), listOfNotNull(d.face, (d.target as? PlaneRef.OnFace)?.face))
            is PlaneDraft -> showPicks(d.points, d.edges, listOfNotNull(d.face))
            is AxisDraft -> showPicks(d.points, listOfNotNull(d.edge), listOfNotNull(d.face))
            is PointDraft -> showPicks(listOfNotNull(d.ref), emptyList(), d.planes.mapNotNull { (it as? PlaneRef.OnFace)?.face })
        }
    }

    /** Picked areas of a shown sketch, as the view numbers them. */
    private fun regionPairs(sketchId: Int?, refs: List<RegionRef>): List<Pair<Int, Int>> {
        val s = shownSketches.indexOfFirst { it.id == sketchId }
        if (s < 0) return emptyList()
        val regions = finder.find(shownSketches[s].sketch.profileCurves())
        return refs.mapNotNull { ref -> regions.indexOfFirst { it.curveIds == ref.curveIds }.takeIf { it >= 0 }?.let { s to it } }
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

        /** The bodies a join, cut or intersect may change, or empty for any it reaches. */
        var only by mutableStateOf<List<String>>(emptyList())

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

        init { only = editing?.only ?: emptyList() }
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
                return ExtrudeFeature(id, name, s, regions, fwd, back, operation, taper, null, true, offset, wall, only)
            }
            if (upToOn) {
                val target = upTo ?: return null
                return ExtrudeFeature(id, name, s, regions, 0.0, 0.0, operation, taper, target, false, offset, wall, only)
            }
            val (fwd, back) = when (direction) {
                Direction.OneSide -> distance to 0.0
                Direction.Symmetric -> distance / 2 to distance / 2
                Direction.TwoSides -> distance to other
            }
            return ExtrudeFeature(id, name, s, regions, fwd, back, operation, taper, null, false, offset, wall, only)
        }

        override fun missing() = if (regions.isEmpty()) "Tap an area of a sketch" else "Tap the face to go up to"
    }

    /** Areas swept along a path: another sketch, or picked edges. */
    inner class SweepDraft(editing: com.rm.parrotmetric.design.SweepFeature?) : AreaDraft(editing) {
        private val name = editing?.name ?: nextName("Sweep", design.features.count { it is com.rm.parrotmetric.design.SweepFeature })
        var pathByEdges by mutableStateOf(editing?.path is com.rm.parrotmetric.design.PathRef.Edges)
        var pathSketch by mutableStateOf((editing?.path as? com.rm.parrotmetric.design.PathRef.Sketch)?.sketchId)
        var pathEdges by mutableStateOf((editing?.path as? com.rm.parrotmetric.design.PathRef.Edges)?.names ?: emptyList())
        init {
            if (editing != null) {
                sketchId = editing.sketchId
                regions = editing.regions
                operation = editing.operation
            }
        }
        override fun feature(): Feature? {
            val s = sketchId ?: return null
            if (regions.isEmpty()) return null
            val path = if (pathByEdges) pathEdges.takeIf { it.isNotEmpty() }?.let { com.rm.parrotmetric.design.PathRef.Edges(it) }
            else pathSketch?.let { com.rm.parrotmetric.design.PathRef.Sketch(it) }
            return com.rm.parrotmetric.design.SweepFeature(id, name, s, regions, path ?: return null, operation)
        }
        override fun missing() = if (regions.isEmpty()) "Tap an area of a sketch" else "Pick the path to follow"
    }

    /** A round tube along a path. */
    inner class PipeDraft(editing: com.rm.parrotmetric.design.PipeFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Pipe", design.features.count { it is com.rm.parrotmetric.design.PipeFeature })
        var pathByEdges by mutableStateOf(editing?.path is com.rm.parrotmetric.design.PathRef.Edges)
        var pathSketch by mutableStateOf((editing?.path as? com.rm.parrotmetric.design.PathRef.Sketch)?.sketchId)
        var pathEdges by mutableStateOf((editing?.path as? com.rm.parrotmetric.design.PathRef.Edges)?.names ?: emptyList())
        var diameter by mutableStateOf(editing?.diameter ?: 6.0)
        var hollow by mutableStateOf((editing?.inner ?: 0.0) > 0)
        var inner by mutableStateOf(editing?.inner?.takeIf { it > 0 } ?: 4.0)
        var operation by mutableStateOf(editing?.operation ?: Operation.NewBody)
        override fun feature(): Feature? {
            val path = if (pathByEdges) pathEdges.takeIf { it.isNotEmpty() }?.let { com.rm.parrotmetric.design.PathRef.Edges(it) }
            else pathSketch?.let { com.rm.parrotmetric.design.PathRef.Sketch(it) }
            return com.rm.parrotmetric.design.PipeFeature(id, name, path ?: return null, diameter, if (hollow) inner else 0.0, operation)
        }
        override fun missing() = "Pick the path to follow"
    }

    inner class CoilDraft(editing: com.rm.parrotmetric.design.CoilFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Coil", design.features.count { it is com.rm.parrotmetric.design.CoilFeature })
        var planes: List<Pair<String, PlaneRef>> = emptyList()
        var plane by mutableStateOf<PlaneRef>(editing?.plane ?: PlaneRef.Fixed(SketchPlane.Top))
        var u by mutableStateOf(editing?.u ?: 0.0)
        var v by mutableStateOf(editing?.v ?: 0.0)
        var diameter by mutableStateOf(editing?.diameter ?: 20.0)
        var pitch by mutableStateOf(editing?.pitch ?: 5.0)
        var turns by mutableStateOf(editing?.turns ?: 5.0)
        var section by mutableStateOf(editing?.section ?: 2.0)
        var square by mutableStateOf(editing?.square ?: false)
        var operation by mutableStateOf(editing?.operation ?: Operation.NewBody)
        override fun feature() = com.rm.parrotmetric.design.CoilFeature(id, name, plane, u, v, diameter, pitch, turns, section, square, operation)
        override fun missing() = ""
    }

    inner class ThreadDraft(editing: com.rm.parrotmetric.design.ThreadFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Thread", design.features.count { it is com.rm.parrotmetric.design.ThreadFeature })
        var face by mutableStateOf(editing?.face)
        var pitch by mutableStateOf(editing?.pitch ?: 1.0)
        override fun feature(): Feature? = face?.let { com.rm.parrotmetric.design.ThreadFeature(id, name, it, pitch) }
        override fun missing() = "Tap the round face of a shaft or hole"
    }

    inner class LipDraft(editing: com.rm.parrotmetric.design.LipFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Lip", design.features.count { it is com.rm.parrotmetric.design.LipFeature })
        var face by mutableStateOf(editing?.face)
        var width by mutableStateOf(editing?.width ?: 1.0)
        var height by mutableStateOf(editing?.height ?: 2.0)
        var gap by mutableStateOf(editing?.gap ?: 0.2)
        var lid by mutableStateOf(editing?.lid)
        override fun feature(): Feature? = face?.let { com.rm.parrotmetric.design.LipFeature(id, name, it, width, height, gap, lid) }
        override fun missing() = "Tap the top of a wall, round the opening"
    }

    /** A loft through areas of sketches, in the order they're tapped. */
    inner class LoftDraft(editing: com.rm.parrotmetric.design.LoftFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Loft", design.features.count { it is com.rm.parrotmetric.design.LoftFeature })
        var sections by mutableStateOf(editing?.sections ?: emptyList())
        var ruled by mutableStateOf(editing?.ruled ?: false)
        var operation by mutableStateOf(editing?.operation ?: Operation.NewBody)
        override fun feature(): Feature? = if (sections.size < 2) null else com.rm.parrotmetric.design.LoftFeature(id, name, sections, ruled, operation)
        override fun missing() = "Tap an area in each of two or more sketches"
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
            return RevolveFeature(id, name, s, regions, axis, degrees * PI / 180, operation, only)
        }
    }

    private fun nextName(prefix: String, count: Int) = "$prefix ${count + 1}"

    enum class FaceTool(val title: String) { Shell("Shell"), Draft("Draft"), PressPull("Press pull"), Delete("Delete face") }

    /**
     * Features on picked faces: Shell (faces left open), Draft (faces tilted,
     * and the face they pivot on), Press pull (faces moved) and Delete face.
     */
    inner class FaceDraft(editing: Feature?, val tool: FaceTool) : FeatureDraft() {
        val tilt get() = tool == FaceTool.Draft
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName(tool.title, design.features.count {
            when (tool) {
                FaceTool.Shell -> it is ShellFeature
                FaceTool.Draft -> it is DraftFeature
                FaceTool.PressPull -> it is com.rm.parrotmetric.design.OffsetFaceFeature
                FaceTool.Delete -> it is com.rm.parrotmetric.design.DeleteFaceFeature
            }
        })
        var faces by mutableStateOf<List<String>>(emptyList())
        var neutral by mutableStateOf<String?>(null)
        var pickingPivot by mutableStateOf(false)
        // Degrees for a draft, mm otherwise.
        var size by mutableStateOf(when (tool) { FaceTool.Draft -> 3.0; FaceTool.PressPull -> 1.0; else -> 2.0 })

        init {
            when (editing) {
                is ShellFeature -> { faces = editing.faces; size = editing.thickness }
                is DraftFeature -> { faces = editing.faces; neutral = editing.neutral; size = editing.angle * 180 / PI }
                is com.rm.parrotmetric.design.OffsetFaceFeature -> { faces = editing.faces; size = editing.distance }
                is com.rm.parrotmetric.design.DeleteFaceFeature -> faces = editing.faces
                else -> {}
            }
        }

        override fun feature(): Feature? = when (tool) {
            FaceTool.Draft -> {
                val n = neutral
                if (faces.isEmpty() || n == null) null else DraftFeature(id, name, faces - n, n, size * PI / 180)
            }
            FaceTool.Shell -> if (faces.isEmpty()) null else ShellFeature(id, name, faces, size)
            FaceTool.PressPull -> if (faces.isEmpty()) null else com.rm.parrotmetric.design.OffsetFaceFeature(id, name, faces, size)
            FaceTool.Delete -> if (faces.isEmpty()) null else com.rm.parrotmetric.design.DeleteFaceFeature(id, name, faces)
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
        private val matched = editing?.let { com.rm.parrotmetric.design.HolePresets.match(it.diameter) }
        /** What the hole is for, or null for a size typed in. */
        var fit by mutableStateOf(matched?.first)
        var size by mutableStateOf(matched?.second ?: "M3")

        init { only = editing?.only ?: emptyList() }

        /** Sets the size, and the depth for an insert, from what it's for. */
        fun usePreset(newFit: com.rm.parrotmetric.design.HoleFit?, newSize: String) {
            fit = newFit
            size = newSize
            if (newFit == null) return
            diameter = com.rm.parrotmetric.design.HolePresets.diameter(newFit, newSize)
            exprs.remove("diameter")
            com.rm.parrotmetric.design.HolePresets.depth(newFit, newSize)?.let {
                depth = it
                through = false
                exprs.remove("depth")
            }
            if (newFit == com.rm.parrotmetric.design.HoleFit.Clearance) com.rm.parrotmetric.design.HolePresets.top(kind, newSize)?.let { (across, deep) ->
                topDiameter = across
                exprs.remove("topDiameter")
                if (kind == com.rm.parrotmetric.design.HoleKind.Counterbore) {
                    topDepth = deep
                    exprs.remove("topDepth")
                }
            }
        }

        override fun feature(): Feature? {
            val s = sketchId ?: return null
            return HoleFeature(id, name, s, diameter, if (through) 0.0 else depth, kind, topDiameter, topDepth, only)
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
        var byFeatures by mutableStateOf(editing?.features?.isNotEmpty() ?: false)
        var features by mutableStateOf(editing?.features ?: emptyList())
        init { if (editing != null) bodies = editing.bodies }
        override fun feature() = if (byFeatures && features.isEmpty()) null
        else MirrorFeature(id, name, bodies, plane, join, if (byFeatures) features else emptyList())
        override fun missing() = if (byFeatures) "Tick the features to mirror" else super.missing()
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
        var alongPath by mutableStateOf(editing?.path != null)
        var pathByEdges by mutableStateOf(editing?.path is com.rm.parrotmetric.design.PathRef.Edges)
        var pathSketch by mutableStateOf((editing?.path as? com.rm.parrotmetric.design.PathRef.Sketch)?.sketchId)
        var pathEdges by mutableStateOf((editing?.path as? com.rm.parrotmetric.design.PathRef.Edges)?.names ?: emptyList())
        /** Along a path: spread over all of it, else [spacing] apart. */
        var spread by mutableStateOf(editing?.path == null || editing.spacing <= 0)
        var turn by mutableStateOf(editing?.turn ?: true)
        var reverse by mutableStateOf(editing?.reverse ?: false)
        var byFeatures by mutableStateOf(editing?.features?.isNotEmpty() ?: false)
        var features by mutableStateOf(editing?.features ?: emptyList())
        init { if (editing != null) bodies = editing.bodies }
        override fun feature(): PatternFeature? {
            if (byFeatures && features.isEmpty()) return null
            val path = if (!alongPath) null
            else if (pathByEdges) pathEdges.takeIf { it.isNotEmpty() }?.let { com.rm.parrotmetric.design.PathRef.Edges(it) } ?: return null
            else pathSketch?.let { com.rm.parrotmetric.design.PathRef.Sketch(it) } ?: return null
            return PatternFeature(
                id, name, bodies, circular && !alongPath, axis, count.toInt(), if (alongPath && spread) 0.0 else spacing, degrees * PI / 180,
                if (alongPath) null else axis2, count2.toInt(), spacing2, join, if (circular && !alongPath) axisFeature else null,
                path, turn, if (byFeatures) features else emptyList(), reverse,
            )
        }
        override fun missing() = when {
            byFeatures && features.isEmpty() -> "Tick the features to repeat"
            alongPath -> "Pick the path to follow"
            else -> super.missing()
        }
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
        /** Split by another body, the second one picked, in place of a plane. */
        var byBody by mutableStateOf(editing?.tool != null)
        init { if (editing != null) bodies = listOfNotNull(editing.body, editing.tool) }
        override fun feature(): Feature? {
            val body = bodies.firstOrNull() ?: return null
            if (!byBody) return SplitFeature(id, name, body, plane, keep)
            val tool = bodies.getOrNull(1) ?: return null
            return SplitFeature(id, name, body, plane, keep, tool)
        }
        override fun missing() = if (byBody) "Tap the body to split, then the one to split it by" else "Tap a face of the body to split"
    }

    /** Lines up a face of a body with another face or a plane. */
    inner class AlignDraft(editing: com.rm.parrotmetric.design.AlignFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Align", design.features.count { it is com.rm.parrotmetric.design.AlignFeature })
        var bodies by mutableStateOf(editing?.bodies ?: emptyList())
        var face by mutableStateOf(editing?.face)
        var target by mutableStateOf<PlaneRef?>(editing?.target)
        var planes by mutableStateOf<List<Pair<String, PlaneRef>>>(emptyList())
        var sameWay by mutableStateOf(editing?.sameWay ?: false)
        var centred by mutableStateOf(editing?.centred ?: false)
        var gap by mutableStateOf(editing?.gap ?: 0.0)
        override fun feature(): Feature? {
            val f = face ?: return null
            val t = target ?: return null
            return com.rm.parrotmetric.design.AlignFeature(id, name, bodies, f, t, sameWay, centred, gap)
        }
        override fun missing() = if (face == null) "Tap the flat face to move" else "Tap the face to line it up with, or pick a plane"
    }

    inner class ConvertDraft(editing: com.rm.parrotmetric.design.ConvertFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("To solid", design.features.count { it is com.rm.parrotmetric.design.ConvertFeature })
        init { if (editing != null) bodies = listOf(editing.body) }
        override fun feature(): Feature? = bodies.firstOrNull()?.let { com.rm.parrotmetric.design.ConvertFeature(id, name, it) }
        override fun missing() = "Tap the mesh to make solid"
    }

    /** A joint between components; see JointFeature. Angles here are in degrees. */
    inner class JointDraft(editing: com.rm.parrotmetric.design.JointFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Joint", design.features.count { it is com.rm.parrotmetric.design.JointFeature })
        var kind by mutableStateOf(editing?.kind ?: com.rm.parrotmetric.design.JointKind.Turn)
        var moving by mutableStateOf(editing?.moving ?: components().first())
        var fixed by mutableStateOf(editing?.fixed)
        var edge by mutableStateOf(editing?.edge)
        var face by mutableStateOf(editing?.face)
        var axis by mutableStateOf(editing?.axis ?: Axis3.Z)
        /** Degrees for a turn, mm for a slide. */
        var value by mutableStateOf(editing?.let { if (it.kind == com.rm.parrotmetric.design.JointKind.Slide) it.value else it.value * 180 / PI } ?: 0.0)
        var value2 by mutableStateOf(editing?.value2 ?: 0.0)
        val turns get() = kind == com.rm.parrotmetric.design.JointKind.Turn || kind == com.rm.parrotmetric.design.JointKind.TurnSlide

        override fun feature(): Feature = com.rm.parrotmetric.design.JointFeature(
            id, name, kind, moving, fixed, edge, face, null, axis,
            if (kind == com.rm.parrotmetric.design.JointKind.Slide) value else value * PI / 180, value2,
        )
        override fun missing() = "Pick the components"
    }

    /** Sketch areas raised from or sunk into a face. */
    inner class EmbossDraft(editing: com.rm.parrotmetric.design.EmbossFeature?) : AreaDraft(editing) {
        private val name = editing?.name ?: nextName("Emboss", design.features.count { it is com.rm.parrotmetric.design.EmbossFeature })
        var face by mutableStateOf(editing?.face)
        var depth by mutableStateOf(editing?.depth ?: 1.0)
        var sink by mutableStateOf(editing?.sink ?: false)
        init {
            if (editing != null) {
                sketchId = editing.sketchId
                regions = editing.regions
            }
        }
        override fun feature(): Feature? {
            val s = sketchId ?: return null
            val f = face ?: return null
            if (regions.isEmpty()) return null
            return com.rm.parrotmetric.design.EmbossFeature(id, name, s, regions, f, depth, sink)
        }
        override fun missing() = if (regions.isEmpty()) "Tap an area of a sketch" else "Tap the face to put it on"
    }

    /** A surface from sketch areas, or filling a loop of edges. */
    inner class PatchDraft(editing: com.rm.parrotmetric.design.PatchFeature?) : AreaDraft(editing) {
        private val name = editing?.name ?: nextName("Patch", design.features.count { it is com.rm.parrotmetric.design.PatchFeature })
        var byEdges by mutableStateOf(editing != null && editing.sketchId == null)
        var edges by mutableStateOf(editing?.edges ?: emptyList())
        init {
            if (editing != null) {
                sketchId = editing.sketchId
                regions = editing.regions
            }
        }
        override fun feature(): Feature? = if (byEdges) {
            if (edges.size < 2) null else com.rm.parrotmetric.design.PatchFeature(id, name, null, emptyList(), edges)
        } else {
            val s = sketchId
            if (s == null || regions.isEmpty()) null else com.rm.parrotmetric.design.PatchFeature(id, name, s, regions, emptyList())
        }
        override fun missing() = if (byEdges) "Tap the edges round the gap" else "Tap an area of a sketch"
    }

    inner class StitchDraft(editing: com.rm.parrotmetric.design.StitchFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Stitch", design.features.count { it is com.rm.parrotmetric.design.StitchFeature })
        init { if (editing != null) bodies = editing.bodies }
        override fun feature(): Feature? = if (bodies.size < 2) null else com.rm.parrotmetric.design.StitchFeature(id, name, bodies)
        override fun missing() = "Tap the surfaces to stitch"
    }

    inner class ThickenDraft(editing: com.rm.parrotmetric.design.ThickenFeature?) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName("Thicken", design.features.count { it is com.rm.parrotmetric.design.ThickenFeature })
        var thickness by mutableStateOf(editing?.thickness ?: 2.0)
        var both by mutableStateOf(editing?.both ?: false)
        init { if (editing != null) bodies = listOf(editing.body) }
        override fun feature(): Feature? = bodies.firstOrNull()?.let { com.rm.parrotmetric.design.ThickenFeature(id, name, it, thickness, both) }
        override fun missing() = "Tap the surface"
    }

    /** A rib or web from a sketch's open lines. */
    inner class RibDraft(editing: com.rm.parrotmetric.design.RibFeature?, web: Boolean) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName(if (web) "Web" else "Rib", design.features.count { it is com.rm.parrotmetric.design.RibFeature && it.web == web })
        var sketchId by mutableStateOf(editing?.sketchId ?: sketchChoices(null).lastOrNull()?.second)
        var thickness by mutableStateOf(editing?.thickness ?: 2.0)
        var flip by mutableStateOf(editing?.flip ?: false)
        var web by mutableStateOf(web)
        override fun feature(): Feature? = sketchId?.let { com.rm.parrotmetric.design.RibFeature(id, name, it, thickness, flip, web) }
        override fun missing() = "Draw an open line in a sketch first"
    }

    /** Reduce, remesh or smooth a body's triangles. */
    inner class MeshEditDraft(editing: com.rm.parrotmetric.design.MeshEditFeature?, kind: com.rm.parrotmetric.design.MeshEdit) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName(kind.name, design.features.count { it is com.rm.parrotmetric.design.MeshEditFeature && it.kind == kind })
        var kind by mutableStateOf(kind)
        var size by mutableStateOf(editing?.size ?: defaultSize(kind))
        var steps by mutableStateOf((editing?.steps ?: 2).toDouble())
        init { if (editing != null) bodies = listOf(editing.body) }

        fun defaultSize(k: com.rm.parrotmetric.design.MeshEdit) = when (k) {
            com.rm.parrotmetric.design.MeshEdit.Reduce -> 0.05
            com.rm.parrotmetric.design.MeshEdit.Remesh -> 1.0
            com.rm.parrotmetric.design.MeshEdit.Smooth -> 30.0
        }

        override fun feature(): Feature? = bodies.firstOrNull()?.let {
            com.rm.parrotmetric.design.MeshEditFeature(id, name, it, kind, size, steps.toInt())
        }
        override fun missing() = "Tap the body"
    }

    /** Move, or with [scaling] just Scale. */
    inner class MoveDraft(editing: MoveFeature?, val scaling: Boolean = false) : BodyDraft(editing) {
        private val name = editing?.name ?: nextName(if (scaling) "Scale" else "Move", design.features.count { it is MoveFeature })
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
        var degrees by mutableStateOf((editing?.angle ?: (if (kind == PlaneFeature.Kind.Tangent) 0.0 else PI / 4)) * 180 / PI)
        var turnRoundY by mutableStateOf(editing?.turnRoundY ?: false)
        var points by mutableStateOf(editing?.points ?: emptyList())
        var edges by mutableStateOf(editing?.edges ?: emptyList())
        var face by mutableStateOf(editing?.face)
        /** How far along the edge, percent. */
        var alongPercent by mutableStateOf((editing?.along ?: 0.5) * 100)
        override fun feature(): Feature? {
            when (kind) {
                PlaneFeature.Kind.Midway -> if (other == null) return null
                PlaneFeature.Kind.ThreePoints -> if (points.size < 3) return null
                PlaneFeature.Kind.TwoEdges -> if (edges.size < 2) return null
                PlaneFeature.Kind.Tangent -> if (face == null) return null
                PlaneFeature.Kind.AlongEdge -> if (edges.isEmpty()) return null
                else -> {}
            }
            return PlaneFeature(id, name, kind, base, offset, degrees * PI / 180, turnRoundY, other, points, edges, face, alongPercent / 100)
        }
        override fun missing() = when (kind) {
            PlaneFeature.Kind.ThreePoints -> "Tap three corners or round edges"
            PlaneFeature.Kind.TwoEdges -> "Tap two straight edges"
            PlaneFeature.Kind.Tangent -> "Tap a cylinder's face"
            PlaneFeature.Kind.AlongEdge -> "Tap an edge"
            else -> "Pick the second plane"
        }
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
        var flip by mutableStateOf(editing?.flip ?: false)

        init { only = editing?.only ?: emptyList() }
        override fun feature() = com.rm.parrotmetric.design.PrimitiveFeature(id, name, kind, plane, u, v, a, b, c, operation, flip, only)
        override fun missing() = ""
    }

    /** A picture on a plane. */
    inner class CanvasDraft(editing: com.rm.parrotmetric.design.CanvasFeature?, val image: ByteArray) : FeatureDraft() {
        val id = editing?.id ?: newId()
        var label = editing?.name ?: "Canvas"
        var aspect = editing?.aspect ?: 1.0
        var planes: List<Pair<String, PlaneRef>> = emptyList()
        var plane by mutableStateOf<PlaneRef>(editing?.plane ?: PlaneRef.Fixed(SketchPlane.Front))
        var width by mutableStateOf(editing?.width ?: 100.0)
        var u by mutableStateOf(editing?.u ?: 0.0)
        var v by mutableStateOf(editing?.v ?: 0.0)
        var degrees by mutableStateOf((editing?.angle ?: 0.0) * 180 / PI)
        var opacityPercent by mutableStateOf((editing?.opacity ?: 0.5) * 100)
        override fun feature() = com.rm.parrotmetric.design.CanvasFeature(
            id, label, plane, image, aspect, width, u, v, degrees * PI / 180, (opacityPercent / 100).coerceIn(0.05, 1.0),
        )
        override fun missing() = ""
    }

    inner class PointDraft(editing: PointFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Point", design.features.count { it is PointFeature })
        var x by mutableStateOf(editing?.x ?: 0.0)
        var y by mutableStateOf(editing?.y ?: 0.0)
        var z by mutableStateOf(editing?.z ?: 0.0)
        var kind by mutableStateOf(editing?.kind ?: PointFeature.Kind.Fixed)
        var ref by mutableStateOf(editing?.ref)
        var planes by mutableStateOf(editing?.planes ?: emptyList())
        override fun feature(): Feature? = when (kind) {
            PointFeature.Kind.Fixed -> PointFeature(id, name, x, y, z)
            PointFeature.Kind.At -> ref?.let { PointFeature(id, name, x, y, z, kind, it) }
            PointFeature.Kind.ThreePlanes -> if (planes.size < 3) null else PointFeature(id, name, x, y, z, kind, null, planes)
        }
        override fun missing() = if (kind == PointFeature.Kind.At) "Tap a corner or round edge" else "Tap three flat faces or planes"
    }

    inner class AxisDraft(editing: AxisFeature?) : FeatureDraft() {
        val id = editing?.id ?: newId()
        private val name = editing?.name ?: nextName("Axis", design.features.count { it is AxisFeature })
        var x by mutableStateOf(editing?.x ?: 0.0)
        var y by mutableStateOf(editing?.y ?: 0.0)
        var z by mutableStateOf(editing?.z ?: 0.0)
        var along by mutableStateOf(editing?.along ?: Axis3.Z)
        var kind by mutableStateOf(editing?.kind ?: AxisFeature.Kind.Fixed)
        var edge by mutableStateOf(editing?.edge)
        var face by mutableStateOf(editing?.face)
        var points by mutableStateOf(editing?.points ?: emptyList())
        override fun feature(): Feature? = when (kind) {
            AxisFeature.Kind.Fixed -> AxisFeature(id, name, x, y, z, along)
            AxisFeature.Kind.Edge -> edge?.let { AxisFeature(id, name, x, y, z, along, kind, it) }
            AxisFeature.Kind.Round -> if (face == null && edge == null) null else AxisFeature(id, name, x, y, z, along, kind, edge, face)
            AxisFeature.Kind.TwoPoints -> if (points.size < 2) null else AxisFeature(id, name, x, y, z, along, kind, points = points)
        }
        override fun missing() = when (kind) {
            AxisFeature.Kind.Edge -> "Tap a straight edge"
            AxisFeature.Kind.Round -> "Tap a cylinder's face or a round edge"
            else -> "Tap two corners or round edges"
        }
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
        var filletKind by mutableStateOf(com.rm.parrotmetric.design.FilletKind.Constant)
        /** The second distance in mm (a fillet's end radius), or the angle in degrees. */
        var second by mutableStateOf(1.0)
        var flip by mutableStateOf(false)

        init {
            when (editing) {
                is FilletFeature -> { edges = editing.edges; size = editing.radius; filletKind = editing.kind; second = editing.second }
                is ChamferFeature -> {
                    edges = editing.edges; size = editing.distance; kind = editing.kind; flip = editing.flip
                    second = if (editing.kind == ChamferKind.DistanceAngle) editing.second * 180 / PI else editing.second
                }
                else -> {}
            }
        }

        override fun feature(): Feature? {
            if (edges.isEmpty()) return null
            if (!chamfer) return FilletFeature(id, name, edges, size, filletKind, if (filletKind == com.rm.parrotmetric.design.FilletKind.Variable) second else 0.0)
            return when (kind) {
                ChamferKind.Equal -> ChamferFeature(id, name, edges, size)
                ChamferKind.TwoDistances -> ChamferFeature(id, name, edges, size, kind, second, flip)
                ChamferKind.DistanceAngle -> ChamferFeature(id, name, edges, size, kind, second * PI / 180, flip)
            }
        }

        override fun missing() = "Tap the edges to ${if (chamfer) "bevel" else "round"}"
    }
}
