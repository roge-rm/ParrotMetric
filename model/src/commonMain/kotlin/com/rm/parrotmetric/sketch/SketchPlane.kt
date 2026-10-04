package com.rm.parrotmetric.sketch

/**
 * Where a sketch lies in 3D: its origin and its x and y directions, unit
 * length and at right angles. Its normal, x × y, points towards whoever is
 * drawing on it.
 */
data class SketchPlane(val name: String, val origin: Vec3, val x: Vec3, val y: Vec3) {
    val normal: Vec3 get() = x.cross(y)

    fun toWorld(u: Double, v: Double) = origin + x * u + y * v

    companion object {
        val Top = SketchPlane("Top", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val Front = SketchPlane("Front", Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))
        val Right = SketchPlane("Right", Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
    }
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
}
