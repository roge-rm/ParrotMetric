package com.rm.parrotmetric.design

import com.rm.parrotmetric.sketch.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** 3x4 matrices, rows of rotation then translation, as the kernel takes them. */
internal object Transforms {
    fun unit(a: Axis3) = when (a) {
        Axis3.X -> Vec3(1.0, 0.0, 0.0)
        Axis3.Y -> Vec3(0.0, 1.0, 0.0)
        Axis3.Z -> Vec3(0.0, 0.0, 1.0)
    }

    fun translate(v: Vec3) = doubleArrayOf(1.0, 0.0, 0.0, v.x, 0.0, 1.0, 0.0, v.y, 0.0, 0.0, 1.0, v.z)

    /** Scaling by (x, y, z) about the point c. */
    fun scale(x: Double, y: Double, z: Double, c: Vec3) =
        doubleArrayOf(x, 0.0, 0.0, c.x * (1 - x), 0.0, y, 0.0, c.y * (1 - y), 0.0, 0.0, z, c.z * (1 - z))

    /** Turning round an axis through the origin. */
    fun rotate(axis: Vec3, angle: Double): DoubleArray {
        val c = cos(angle); val s = sin(angle); val t = 1 - c
        val (x, y, z) = Triple(axis.x, axis.y, axis.z)
        return doubleArrayOf(
            t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0.0,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x, 0.0,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c, 0.0,
        )
    }

    /** Reflecting across the plane through o with unit normal n. */
    fun mirror(o: Vec3, n: Vec3): DoubleArray {
        val d = 2 * n.dot(o)
        return doubleArrayOf(
            1 - 2 * n.x * n.x, -2 * n.x * n.y, -2 * n.x * n.z, d * n.x,
            -2 * n.y * n.x, 1 - 2 * n.y * n.y, -2 * n.y * n.z, d * n.y,
            -2 * n.z * n.x, -2 * n.z * n.y, 1 - 2 * n.z * n.z, d * n.z,
        )
    }

    /** The turn round the origin that takes unit vector a to unit vector b. */
    fun turnOnto(a: Vec3, b: Vec3): DoubleArray {
        val axis = a.cross(b)
        val sine = kotlin.math.sqrt(axis.dot(axis))
        val cosine = a.dot(b)
        if (sine < 1e-9) {
            if (cosine > 0) return rotate(Vec3(0.0, 0.0, 1.0), 0.0)
            // Opposite: half a turn round anything square to a.
            val other = if (kotlin.math.abs(a.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val square = a.cross(other)
            return rotate(square * (1 / kotlin.math.sqrt(square.dot(square))), kotlin.math.PI)
        }
        return rotate(axis * (1 / sine), kotlin.math.atan2(sine, cosine))
    }

    /** a then b. */
    fun then(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(12)
        for (r in 0 until 3) {
            for (c in 0 until 3) out[r * 4 + c] = (0 until 3).sumOf { k -> b[r * 4 + k] * a[k * 4 + c] }
            out[r * 4 + 3] = (0 until 3).sumOf { k -> b[r * 4 + k] * a[k * 4 + 3] } + b[r * 4 + 3]
        }
        return out
    }
}
