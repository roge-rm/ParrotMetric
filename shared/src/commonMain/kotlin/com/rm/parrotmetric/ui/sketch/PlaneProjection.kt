package com.rm.parrotmetric.ui.sketch

import androidx.compose.ui.geometry.Offset
import com.rm.parrotmetric.sketch.SketchPlane
import com.rm.parrotmetric.sketch.Vec3
import kotlin.math.abs

/** The 3D view's camera as last drawn: what the sketch overlay needs to line up with it. */
class CameraState(val yaw: Float, val pitch: Float, val width: Float, val height: Float, val viewProjection: FloatArray) {
    companion object {
        /** From the core's flattened form: yaw, pitch, width, height, then the 16 matrix values. */
        fun from(a: FloatArray) = CameraState(a[0], a[1], a[2], a[3], a.copyOfRange(4, 20))
    }
}

/**
 * Maps between a sketch plane's (u, v) in mm and screen pixels, through the
 * camera. Screen to plane casts a ray through the pixel and meets the plane.
 */
class PlaneProjection(private val camera: CameraState, val plane: SketchPlane) {
    private val m = camera.viewProjection
    private val inverse = invert(m.map { it.toDouble() }.toDoubleArray())

    fun toScreen(u: Double, v: Double): Offset = toScreen(plane.toWorld(u, v))

    fun toScreen(p: Vec3): Offset {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        return Offset(((x / w + 1) / 2 * camera.width).toFloat(), ((1 - y / w) / 2 * camera.height).toFloat())
    }

    /** The plane point under a screen pixel, or null if the ray misses it. */
    fun toPlane(s: Offset): Pair<Double, Double>? {
        val inv = inverse ?: return null
        val nx = s.x / camera.width * 2.0 - 1
        val ny = 1 - s.y / camera.height * 2.0
        fun unproject(z: Double): Vec3 {
            val x = inv[0] * nx + inv[4] * ny + inv[8] * z + inv[12]
            val y = inv[1] * nx + inv[5] * ny + inv[9] * z + inv[13]
            val zz = inv[2] * nx + inv[6] * ny + inv[10] * z + inv[14]
            val w = inv[3] * nx + inv[7] * ny + inv[11] * z + inv[15]
            return Vec3(x / w, y / w, zz / w)
        }
        val a = unproject(-1.0)
        val b = unproject(1.0)
        val dir = b - a
        val n = plane.normal
        val denom = dir.dot(n)
        if (abs(denom) < 1e-12) return null
        val t = (plane.origin - a).dot(n) / denom
        val hit = a + dir * t - plane.origin
        return hit.dot(plane.x) to hit.dot(plane.y)
    }

    /** Roughly how many mm one screen pixel covers on the plane, near the screen's middle. */
    fun mmPerPixel(): Double {
        val c = Offset(camera.width / 2, camera.height / 2)
        val a = toPlane(c) ?: return 0.1
        val b = toPlane(c + Offset(100f, 0f)) ?: return 0.1
        return kotlin.math.hypot(b.first - a.first, b.second - a.second) / 100
    }

    private companion object {
        /** Inverse of a column-major 4x4 matrix, or null if it has none. */
        fun invert(a: DoubleArray): DoubleArray? {
            val n = 4
            val m = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) a[c * 4 + r] else if (c - n == r) 1.0 else 0.0 } }
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
                if (abs(m[pivot][col]) < 1e-15) return null
                val t = m[col]; m[col] = m[pivot]; m[pivot] = t
                val p = m[col][col]
                for (c in 0 until 2 * n) m[col][c] /= p
                for (r in 0 until n) if (r != col) {
                    val f = m[r][col]
                    for (c in 0 until 2 * n) m[r][c] -= f * m[col][c]
                }
            }
            return DoubleArray(16) { i -> m[i % 4][n + i / 4] }
        }
    }
}
