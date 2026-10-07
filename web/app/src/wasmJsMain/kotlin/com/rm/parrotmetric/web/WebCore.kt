// Written by web/core/gen_bridge.py from NativeCore.kt; run that instead of editing this.
package com.rm.parrotmetric.web

import com.rm.parrotmetric.app.NativeCore

/** The core in WebAssembly (web/core), each call numbered in NativeCore's order. */
object WebCore : NativeCore {
    override fun setScratchDirectory(path: String) {
        val args = Args()
        args.string(path)
        call(0, args)
    }

    override fun extrude(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, forward: Double, back: Double, taper: Double, thin: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.ints(pickCounts)
        args.ints(pickIds)
        args.doubles(pickPoints)
        args.double(forward)
        args.double(back)
        args.double(taper)
        args.double(thin)
        return call(1, args).long()
    }

    override fun revolve(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.ints(pickCounts)
        args.ints(pickIds)
        args.doubles(pickPoints)
        args.double(ax)
        args.double(ay)
        args.double(dx)
        args.double(dy)
        args.double(angle)
        return call(2, args).long()
    }

    override fun combine(id: Int, target: Long, tool: Long, how: Int): Long {
        val args = Args()
        args.int(id)
        args.long(target)
        args.long(tool)
        args.int(how)
        return call(3, args).long()
    }

    override fun fillet(id: Int, body: Long, edges: Array<String>, radius: Double, kind: Int, second: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(edges)
        args.double(radius)
        args.int(kind)
        args.double(second)
        return call(4, args).long()
    }

    override fun offsetFaces(id: Int, body: Long, faces: Array<String>, distance: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(faces)
        args.double(distance)
        return call(5, args).long()
    }

    override fun deleteFaces(id: Int, body: Long, faces: Array<String>): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(faces)
        return call(6, args).long()
    }

    override fun meshEdit(id: Int, body: Long, kind: Int, size: Double, steps: Int): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.int(kind)
        args.double(size)
        args.int(steps)
        return call(7, args).long()
    }

    override fun patch(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.ints(pickCounts)
        args.ints(pickIds)
        args.doubles(pickPoints)
        return call(8, args).long()
    }

    override fun patchEdges(id: Int, body: Long, edges: Array<String>): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(edges)
        return call(9, args).long()
    }

    override fun stitch(id: Int, bodies: LongArray): Long {
        val args = Args()
        args.int(id)
        args.longs(bodies)
        return call(10, args).long()
    }

    override fun gather(bodies: LongArray): Long {
        val args = Args()
        args.longs(bodies)
        return call(11, args).long()
    }

    override fun emboss(id: Int, body: Long, face: String, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, depth: Double, sink: Boolean): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.string(face)
        args.doubles(plane)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.ints(pickCounts)
        args.ints(pickIds)
        args.doubles(pickPoints)
        args.double(depth)
        args.boolean(sink)
        return call(12, args).long()
    }

    override fun thicken(id: Int, body: Long, thickness: Double, both: Boolean): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.double(thickness)
        args.boolean(both)
        return call(13, args).long()
    }

    override fun rib(id: Int, body: Long, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, thickness: Double, flip: Boolean, web: Boolean): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.doubles(plane)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.double(thickness)
        args.boolean(flip)
        args.boolean(web)
        return call(14, args).long()
    }

    override fun chamfer(id: Int, body: Long, edges: Array<String>, distance: Double, kind: Int, second: Double, flip: Boolean): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(edges)
        args.double(distance)
        args.int(kind)
        args.double(second)
        args.boolean(flip)
        return call(15, args).long()
    }

    override fun overlaps(a: Long, b: Long): Boolean {
        val args = Args()
        args.long(a)
        args.long(b)
        return call(16, args).boolean()
    }

    override fun facePlane(body: Long, name: String): DoubleArray {
        val args = Args()
        args.long(body)
        args.string(name)
        return call(17, args).doubles()!!
    }

    override fun faceNames(body: Long): Array<String> {
        val args = Args()
        args.long(body)
        return call(18, args).strings()!!
    }

    override fun signature(body: Long, name: String, edge: Boolean): DoubleArray? {
        val args = Args()
        args.long(body)
        args.string(name)
        args.boolean(edge)
        return call(19, args).doubles()
    }

    override fun relocate(body: Long, signature: DoubleArray): String? {
        val args = Args()
        args.long(body)
        args.doubles(signature)
        return call(20, args).string()
    }

    override fun importBody(id: Int, data: ByteArray, format: Int): Long {
        val args = Args()
        args.int(id)
        args.bytes(data)
        args.int(format)
        return call(21, args).long()
    }

    override fun shell(id: Int, body: Long, open: Array<String>, thickness: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(open)
        args.double(thickness)
        return call(22, args).long()
    }

    override fun draft(id: Int, body: Long, faces: Array<String>, neutral: String, angle: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(faces)
        args.string(neutral)
        args.double(angle)
        return call(23, args).long()
    }

    override fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.doubles(m)
        args.string(tag)
        return call(24, args).long()
    }

    override fun split(id: Int, body: Long, plane: DoubleArray): LongArray {
        val args = Args()
        args.int(id)
        args.long(body)
        args.doubles(plane)
        return call(25, args).longs()!!
    }

    override fun holeTool(id: Int, plane: DoubleArray, points: DoubleArray, diameter: Double, depth: Double, kind: Int, topDiameter: Double, topDepth: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.doubles(points)
        args.double(diameter)
        args.double(depth)
        args.int(kind)
        args.double(topDiameter)
        args.double(topDepth)
        return call(26, args).long()
    }

    override fun snapFitTool(id: Int, plane: DoubleArray, points: DoubleArray, middle: DoubleArray, length: Double, width: Double, thickness: Double, overhang: Double, catchHeight: Double, gap: Double, catchPart: Boolean, tag: String): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.doubles(points)
        args.doubles(middle)
        args.double(length)
        args.double(width)
        args.double(thickness)
        args.double(overhang)
        args.double(catchHeight)
        args.double(gap)
        args.boolean(catchPart)
        args.string(tag)
        return call(27, args).long()
    }

    override fun repairReport(data: ByteArray, format: Int): String {
        val args = Args()
        args.bytes(data)
        args.int(format)
        return call(28, args).string()!!
    }

    override fun convertToSolid(id: Int, body: Long): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        return call(29, args).long()
    }

    override fun bodyCentre(body: Long): DoubleArray {
        val args = Args()
        args.long(body)
        return call(30, args).doubles()!!
    }

    override fun sweep(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.ints(pickCounts)
        args.ints(pickIds)
        args.doubles(pickPoints)
        args.doubles(pathPlane)
        args.ints(pathKinds)
        args.ints(pathIds)
        args.doubles(pathNums)
        args.long(pathBody)
        args.strings(pathEdges)
        return call(31, args).long()
    }

    override fun pipe(id: Int, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>, diameter: Double, inner: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(pathPlane)
        args.ints(pathKinds)
        args.ints(pathIds)
        args.doubles(pathNums)
        args.long(pathBody)
        args.strings(pathEdges)
        args.double(diameter)
        args.double(inner)
        return call(32, args).long()
    }

    override fun pathPlaces(pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>, count: Int, spacing: Double, turn: Boolean, reverse: Boolean): DoubleArray? {
        val args = Args()
        args.doubles(pathPlane)
        args.ints(pathKinds)
        args.ints(pathIds)
        args.doubles(pathNums)
        args.long(pathBody)
        args.strings(pathEdges)
        args.int(count)
        args.double(spacing)
        args.boolean(turn)
        args.boolean(reverse)
        return call(33, args).doubles()
    }

    override fun coil(id: Int, plane: DoubleArray, u: Double, v: Double, diameter: Double, pitch: Double, turns: Double, section: Double, square: Boolean): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.double(u)
        args.double(v)
        args.double(diameter)
        args.double(pitch)
        args.double(turns)
        args.double(section)
        args.boolean(square)
        return call(34, args).long()
    }

    override fun thread(id: Int, body: Long, face: String, pitch: Double, clearance: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.string(face)
        args.double(pitch)
        args.double(clearance)
        return call(35, args).long()
    }

    override fun lipTool(id: Int, body: Long, face: String, inside: Double, outside: Double, height: Double, tag: String): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.string(face)
        args.double(inside)
        args.double(outside)
        args.double(height)
        args.string(tag)
        return call(36, args).long()
    }

    override fun textOutline(text: String, height: Double, bold: Boolean, font: Int, data: ByteArray): DoubleArray {
        val args = Args()
        args.string(text)
        args.double(height)
        args.boolean(bold)
        args.int(font)
        args.bytes(data)
        return call(37, args).doubles()!!
    }

    override fun canvasImage(key: Int, bytes: ByteArray): IntArray? {
        val args = Args()
        args.int(key)
        args.bytes(bytes)
        return call(38, args).ints()
    }

    override fun loft(id: Int, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, ruled: Boolean, twist: Double, hasGuide: Boolean, pathPlane: DoubleArray, pathKinds: IntArray, pathIds: IntArray, pathNums: DoubleArray, pathBody: Long, pathEdges: Array<String>): Long {
        val args = Args()
        args.int(id)
        args.doubles(planes)
        args.ints(curveCounts)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.ints(pickCounts)
        args.ints(pickIds)
        args.doubles(pickPoints)
        args.boolean(ruled)
        args.double(twist)
        args.boolean(hasGuide)
        args.doubles(pathPlane)
        args.ints(pathKinds)
        args.ints(pathIds)
        args.doubles(pathNums)
        args.long(pathBody)
        args.strings(pathEdges)
        return call(39, args).long()
    }

    override fun primitive(id: Int, plane: DoubleArray, kind: Int, u: Double, v: Double, a: Double, b: Double, c: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.int(kind)
        args.double(u)
        args.double(v)
        args.double(a)
        args.double(b)
        args.double(c)
        return call(40, args).long()
    }

    override fun bounds(body: Long): DoubleArray {
        val args = Args()
        args.long(body)
        return call(41, args).doubles()!!
    }

    override fun splitBy(id: Int, body: Long, tool: Long): LongArray {
        val args = Args()
        args.int(id)
        args.long(body)
        args.long(tool)
        return call(42, args).longs()!!
    }

    override fun overlapVolume(a: Long, b: Long): Double {
        val args = Args()
        args.long(a)
        args.long(b)
        return call(43, args).double()
    }

    override fun selectedCorners(): Array<String> {
        val args = Args()
        return call(44, args).strings()!!
    }

    override fun corner(body: Long, name: String): DoubleArray? {
        val args = Args()
        args.long(body)
        args.string(name)
        return call(45, args).doubles()
    }

    override fun shapeOf(body: Long, name: String, edge: Boolean): DoubleArray? {
        val args = Args()
        args.long(body)
        args.string(name)
        args.boolean(edge)
        return call(46, args).doubles()
    }

    override fun alongEdge(body: Long, name: String, t: Double): DoubleArray? {
        val args = Args()
        args.long(body)
        args.string(name)
        args.double(t)
        return call(47, args).doubles()
    }

    override fun properties(body: Long): DoubleArray {
        val args = Args()
        args.long(body)
        return call(48, args).doubles()!!
    }

    override fun section(bodies: LongArray, plane: DoubleArray): DoubleArray {
        val args = Args()
        args.longs(bodies)
        args.doubles(plane)
        return call(49, args).doubles()!!
    }

    override fun projectView(bodies: LongArray, view: DoubleArray, hidden: Boolean, fast: Double): DoubleArray {
        val args = Args()
        args.longs(bodies)
        args.doubles(view)
        args.boolean(hidden)
        args.double(fast)
        return call(50, args).doubles()!!
    }

    override fun selectedMeshPlane(): DoubleArray? {
        val args = Args()
        return call(51, args).doubles()
    }

    override fun retain(body: Long) {
        val args = Args()
        args.long(body)
        call(52, args)
    }

    override fun release(body: Long) {
        val args = Args()
        args.long(body)
        call(53, args)
    }

    override fun isMesh(body: Long): Boolean {
        val args = Args()
        args.long(body)
        return call(54, args).boolean()
    }

    override fun exportBodies(bodies: LongArray, names: Array<String>, colours: IntArray, format: Int, quality: Int): ByteArray? {
        val args = Args()
        args.longs(bodies)
        args.strings(names)
        args.ints(colours)
        args.int(format)
        args.int(quality)
        return call(55, args).bytes()
    }

    override fun show(bodies: LongArray, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, constructionPlanes: DoubleArray, axes: DoubleArray, points: DoubleArray, colours: IntArray, canvases: DoubleArray, refit: Boolean) {
        val args = Args()
        args.longs(bodies)
        args.doubles(planes)
        args.ints(curveCounts)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.doubles(constructionPlanes)
        args.doubles(axes)
        args.doubles(points)
        args.ints(colours)
        args.doubles(canvases)
        args.boolean(refit)
        call(56, args)
    }

    override fun selectedPlanes(): IntArray {
        val args = Args()
        return call(57, args).ints()!!
    }

    override fun measure(): Array<String> {
        val args = Args()
        return call(58, args).strings()!!
    }

    override fun setSection(on: Boolean, ox: Double, oy: Double, oz: Double, nx: Double, ny: Double, nz: Double) {
        val args = Args()
        args.boolean(on)
        args.double(ox)
        args.double(oy)
        args.double(oz)
        args.double(nx)
        args.double(ny)
        args.double(nz)
        call(59, args)
    }

    override fun setAnalysis(mode: Int, limit: Double) {
        val args = Args()
        args.int(mode)
        args.double(limit)
        call(60, args)
    }

    override fun faceOutline(body: Long, face: String, plane: DoubleArray): DoubleArray {
        val args = Args()
        args.long(body)
        args.string(face)
        args.doubles(plane)
        return call(61, args).doubles()!!
    }

    override fun shownTriangles(): Int {
        val args = Args()
        return call(62, args).int()
    }

    override fun setDisplayDetail(level: Int) {
        val args = Args()
        args.int(level)
        call(63, args)
    }

    override fun speedTest(): Double {
        val args = Args()
        return call(64, args).double()
    }

    override fun tap(x: Float, y: Float): IntArray {
        val args = Args()
        args.float(x)
        args.float(y)
        return call(65, args).ints()!!
    }

    override fun click(x: Float, y: Float, add: Boolean): IntArray {
        val args = Args()
        args.float(x)
        args.float(y)
        args.boolean(add)
        return call(66, args).ints()!!
    }

    override fun clickChain(x: Float, y: Float, add: Boolean): IntArray {
        val args = Args()
        args.float(x)
        args.float(y)
        args.boolean(add)
        return call(67, args).ints()!!
    }

    override fun selectBox(x0: Float, y0: Float, x1: Float, y1: Float, crossing: Boolean, add: Boolean): IntArray {
        val args = Args()
        args.float(x0)
        args.float(y0)
        args.float(x1)
        args.float(y1)
        args.boolean(crossing)
        args.boolean(add)
        return call(68, args).ints()!!
    }

    override fun clearSelection() {
        val args = Args()
        call(69, args)
    }

    override fun selectedEdges(): Array<String> {
        val args = Args()
        return call(70, args).strings()!!
    }

    override fun selectedFaces(): Array<String> {
        val args = Args()
        return call(71, args).strings()!!
    }

    override fun selectedRegions(): IntArray {
        val args = Args()
        return call(72, args).ints()!!
    }

    override fun select(edges: Array<String>, regions: IntArray, faces: Array<String>, corners: Array<String>) {
        val args = Args()
        args.strings(edges)
        args.ints(regions)
        args.strings(faces)
        args.strings(corners)
        call(73, args)
    }

    override fun findRegions(kinds: IntArray, ids: IntArray, numbers: DoubleArray): FloatArray {
        val args = Args()
        args.ints(kinds)
        args.ints(ids)
        args.doubles(numbers)
        return call(74, args).floats()!!
    }

    override fun surfaceCreated() {
        val args = Args()
        call(75, args)
    }

    override fun surfaceChanged(width: Int, height: Int) {
        val args = Args()
        args.int(width)
        args.int(height)
        call(76, args)
    }

    override fun drawFrame(): Boolean {
        val args = Args()
        return call(77, args).boolean()
    }

    override fun setDensity(density: Float) {
        val args = Args()
        args.float(density)
        call(78, args)
    }

    override fun orbit(dx: Float, dy: Float) {
        val args = Args()
        args.float(dx)
        args.float(dy)
        call(79, args)
    }

    override fun pan(dx: Float, dy: Float) {
        val args = Args()
        args.float(dx)
        args.float(dy)
        call(80, args)
    }

    override fun zoom(factor: Float) {
        val args = Args()
        args.float(factor)
        call(81, args)
    }

    override fun zoomAt(factor: Float, x: Float, y: Float) {
        val args = Args()
        args.float(factor)
        args.float(x)
        args.float(y)
        call(82, args)
    }

    override fun fit() {
        val args = Args()
        call(83, args)
    }

    override fun setCovered(left: Float, top: Float, right: Float, bottom: Float) {
        val args = Args()
        args.float(left)
        args.float(top)
        args.float(right)
        args.float(bottom)
        call(84, args)
    }

    override fun viewFrom(yaw: Float, pitch: Float) {
        val args = Args()
        args.float(yaw)
        args.float(pitch)
        call(85, args)
    }

    override fun cameraState(): FloatArray {
        val args = Args()
        return call(86, args).floats()!!
    }

    override fun setAreasFirst(on: Boolean) {
        val args = Args()
        args.boolean(on)
        call(87, args)
    }

    override fun sculptStart(body: Long, packed: ByteArray, shape: Int, size: Double, maxTriangles: Int): Boolean {
        val args = Args()
        args.long(body)
        args.bytes(packed)
        args.int(shape)
        args.double(size)
        args.int(maxTriangles)
        return call(88, args).boolean()
    }

    override fun sculptHit(x: Float, y: Float): Boolean {
        val args = Args()
        args.float(x)
        args.float(y)
        return call(89, args).boolean()
    }

    override fun sculptBegin(x: Float, y: Float, pressure: Float, brush: Int, radius: Float, strength: Float, invert: Boolean, mirror: Int, dynamic: Boolean, detail: Float, pressureSize: Boolean, pressureStrength: Boolean): Boolean {
        val args = Args()
        args.float(x)
        args.float(y)
        args.float(pressure)
        args.int(brush)
        args.float(radius)
        args.float(strength)
        args.boolean(invert)
        args.int(mirror)
        args.boolean(dynamic)
        args.float(detail)
        args.boolean(pressureSize)
        args.boolean(pressureStrength)
        return call(90, args).boolean()
    }

    override fun sculptMove(x: Float, y: Float, pressure: Float) {
        val args = Args()
        args.float(x)
        args.float(y)
        args.float(pressure)
        call(91, args)
    }

    override fun sculptEnd() {
        val args = Args()
        call(92, args)
    }

    override fun sculptUndo(redo: Boolean): Boolean {
        val args = Args()
        args.boolean(redo)
        return call(93, args).boolean()
    }

    override fun sculptInfo(): DoubleArray {
        val args = Args()
        return call(94, args).doubles()!!
    }

    override fun sculptChange(what: Int, edge: Double) {
        val args = Args()
        args.int(what)
        args.double(edge)
        call(95, args)
    }

    override fun sculptFinish(keep: Boolean): ByteArray {
        val args = Args()
        args.boolean(keep)
        return call(96, args).bytes()!!
    }

    override fun sculptedBody(id: Int, packed: ByteArray, input: Long): Long {
        val args = Args()
        args.int(id)
        args.bytes(packed)
        args.long(input)
        return call(97, args).long()
    }

    override fun sculptPack(): ByteArray {
        val args = Args()
        return call(98, args).bytes()!!
    }

    override fun sculptLook(look: Int, wire: Boolean) {
        val args = Args()
        args.int(look)
        args.boolean(wire)
        call(99, args)
    }

    override fun gear(id: Int, plane: DoubleArray, u: Double, v: Double, turn: Double, module: Double, teeth: Int, pressureAngle: Double, thickness: Double, helix: Double, herringbone: Boolean, bore: Double, clearance: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.double(u)
        args.double(v)
        args.double(turn)
        args.double(module)
        args.int(teeth)
        args.double(pressureAngle)
        args.double(thickness)
        args.double(helix)
        args.boolean(herringbone)
        args.double(bore)
        args.double(clearance)
        return call(100, args).long()
    }

    override fun threadMarks(bodies: LongArray, faces: Array<String>, pitches: DoubleArray) {
        val args = Args()
        args.longs(bodies)
        args.strings(faces)
        args.doubles(pitches)
        call(101, args)
    }

    override fun bodyOffsets(bodies: LongArray, offsets: DoubleArray) {
        val args = Args()
        args.longs(bodies)
        args.doubles(offsets)
        call(102, args)
    }

    override fun tappedMeshPoint(): DoubleArray? {
        val args = Args()
        return call(103, args).doubles()
    }

    override fun meshErase(id: Int, body: Long, spots: DoubleArray): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.doubles(spots)
        return call(104, args).long()
    }

    override fun separate(id: Int, body: Long): LongArray {
        val args = Args()
        args.int(id)
        args.long(body)
        return call(105, args).longs()!!
    }

    override fun fastener(id: Int, seat: DoubleArray, kind: Int, d: Double, length: Double, head: Double, headHeight: Double, socket: Double, angle: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(seat)
        args.int(kind)
        args.double(d)
        args.double(length)
        args.double(head)
        args.double(headHeight)
        args.double(socket)
        args.double(angle)
        return call(106, args).long()
    }

    override fun currentView(): FloatArray {
        val args = Args()
        return call(107, args).floats()!!
    }

    override fun setView(view: FloatArray) {
        val args = Args()
        args.floats(view)
        call(108, args)
    }

    override fun setPull(x: Double, y: Double, z: Double) {
        val args = Args()
        args.double(x)
        args.double(y)
        args.double(z)
        call(109, args)
    }

    override fun surfaceFromLines(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, revolve: Boolean, forward: Double, back: Double, ax: Double, ay: Double, dx: Double, dy: Double, angle: Double): Long {
        val args = Args()
        args.int(id)
        args.doubles(plane)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.boolean(revolve)
        args.double(forward)
        args.double(back)
        args.double(ax)
        args.double(ay)
        args.double(dx)
        args.double(dy)
        args.double(angle)
        return call(110, args).long()
    }

    override fun offsetSurface(id: Int, body: Long, faces: Array<String>, distance: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(faces)
        args.double(distance)
        return call(111, args).long()
    }
}
