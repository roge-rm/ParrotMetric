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

    override fun extrude(id: Int, plane: DoubleArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, pickCounts: IntArray, pickIds: IntArray, pickPoints: DoubleArray, forward: Double, back: Double): Long {
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

    override fun fillet(id: Int, body: Long, edges: Array<String>, radius: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(edges)
        args.double(radius)
        return call(4, args).long()
    }

    override fun chamfer(id: Int, body: Long, edges: Array<String>, distance: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(edges)
        args.double(distance)
        return call(5, args).long()
    }

    override fun overlaps(a: Long, b: Long): Boolean {
        val args = Args()
        args.long(a)
        args.long(b)
        return call(6, args).boolean()
    }

    override fun facePlane(body: Long, name: String): DoubleArray {
        val args = Args()
        args.long(body)
        args.string(name)
        return call(7, args).doubles()!!
    }

    override fun faceNames(body: Long): Array<String> {
        val args = Args()
        args.long(body)
        return call(8, args).strings()!!
    }

    override fun importBody(id: Int, data: ByteArray, format: Int): Long {
        val args = Args()
        args.int(id)
        args.bytes(data)
        args.int(format)
        return call(9, args).long()
    }

    override fun shell(id: Int, body: Long, open: Array<String>, thickness: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(open)
        args.double(thickness)
        return call(10, args).long()
    }

    override fun draft(id: Int, body: Long, faces: Array<String>, neutral: String, angle: Double): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.strings(faces)
        args.string(neutral)
        args.double(angle)
        return call(11, args).long()
    }

    override fun transform(id: Int, body: Long, m: DoubleArray, tag: String): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        args.doubles(m)
        args.string(tag)
        return call(12, args).long()
    }

    override fun split(id: Int, body: Long, plane: DoubleArray): LongArray {
        val args = Args()
        args.int(id)
        args.long(body)
        args.doubles(plane)
        return call(13, args).longs()!!
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
        return call(14, args).long()
    }

    override fun repairReport(data: ByteArray, format: Int): String {
        val args = Args()
        args.bytes(data)
        args.int(format)
        return call(15, args).string()!!
    }

    override fun convertToSolid(id: Int, body: Long): Long {
        val args = Args()
        args.int(id)
        args.long(body)
        return call(16, args).long()
    }

    override fun bodyCentre(body: Long): DoubleArray {
        val args = Args()
        args.long(body)
        return call(17, args).doubles()!!
    }

    override fun section(bodies: LongArray, plane: DoubleArray): DoubleArray {
        val args = Args()
        args.longs(bodies)
        args.doubles(plane)
        return call(18, args).doubles()!!
    }

    override fun selectedMeshPlane(): DoubleArray? {
        val args = Args()
        return call(19, args).doubles()
    }

    override fun retain(body: Long) {
        val args = Args()
        args.long(body)
        call(20, args)
    }

    override fun release(body: Long) {
        val args = Args()
        args.long(body)
        call(21, args)
    }

    override fun isMesh(body: Long): Boolean {
        val args = Args()
        args.long(body)
        return call(22, args).boolean()
    }

    override fun exportBodies(bodies: LongArray, names: Array<String>, format: Int, quality: Int): ByteArray? {
        val args = Args()
        args.longs(bodies)
        args.strings(names)
        args.int(format)
        args.int(quality)
        return call(23, args).bytes()
    }

    override fun show(bodies: LongArray, planes: DoubleArray, curveCounts: IntArray, kinds: IntArray, ids: IntArray, nums: DoubleArray, constructionPlanes: DoubleArray, axes: DoubleArray, refit: Boolean) {
        val args = Args()
        args.longs(bodies)
        args.doubles(planes)
        args.ints(curveCounts)
        args.ints(kinds)
        args.ints(ids)
        args.doubles(nums)
        args.doubles(constructionPlanes)
        args.doubles(axes)
        args.boolean(refit)
        call(24, args)
    }

    override fun selectedPlanes(): IntArray {
        val args = Args()
        return call(25, args).ints()!!
    }

    override fun measure(): Array<String> {
        val args = Args()
        return call(26, args).strings()!!
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
        call(27, args)
    }

    override fun faceOutline(body: Long, face: String, plane: DoubleArray): DoubleArray {
        val args = Args()
        args.long(body)
        args.string(face)
        args.doubles(plane)
        return call(28, args).doubles()!!
    }

    override fun shownTriangles(): Int {
        val args = Args()
        return call(29, args).int()
    }

    override fun tap(x: Float, y: Float): IntArray {
        val args = Args()
        args.float(x)
        args.float(y)
        return call(30, args).ints()!!
    }

    override fun clearSelection() {
        val args = Args()
        call(31, args)
    }

    override fun selectedEdges(): Array<String> {
        val args = Args()
        return call(32, args).strings()!!
    }

    override fun selectedFaces(): Array<String> {
        val args = Args()
        return call(33, args).strings()!!
    }

    override fun selectedRegions(): IntArray {
        val args = Args()
        return call(34, args).ints()!!
    }

    override fun select(edges: Array<String>, regions: IntArray, faces: Array<String>) {
        val args = Args()
        args.strings(edges)
        args.ints(regions)
        args.strings(faces)
        call(35, args)
    }

    override fun findRegions(kinds: IntArray, ids: IntArray, numbers: DoubleArray): FloatArray {
        val args = Args()
        args.ints(kinds)
        args.ints(ids)
        args.doubles(numbers)
        return call(36, args).floats()!!
    }

    override fun surfaceCreated() {
        val args = Args()
        call(37, args)
    }

    override fun surfaceChanged(width: Int, height: Int) {
        val args = Args()
        args.int(width)
        args.int(height)
        call(38, args)
    }

    override fun drawFrame(): Boolean {
        val args = Args()
        return call(39, args).boolean()
    }

    override fun setDensity(density: Float) {
        val args = Args()
        args.float(density)
        call(40, args)
    }

    override fun orbit(dx: Float, dy: Float) {
        val args = Args()
        args.float(dx)
        args.float(dy)
        call(41, args)
    }

    override fun pan(dx: Float, dy: Float) {
        val args = Args()
        args.float(dx)
        args.float(dy)
        call(42, args)
    }

    override fun zoom(factor: Float) {
        val args = Args()
        args.float(factor)
        call(43, args)
    }

    override fun fit() {
        val args = Args()
        call(44, args)
    }

    override fun viewFrom(yaw: Float, pitch: Float) {
        val args = Args()
        args.float(yaw)
        args.float(pitch)
        call(45, args)
    }

    override fun cameraState(): FloatArray {
        val args = Args()
        return call(46, args).floats()!!
    }
}
