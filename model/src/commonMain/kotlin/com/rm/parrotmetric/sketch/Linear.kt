package com.rm.parrotmetric.sketch

import kotlin.math.abs

/** A dense row-major matrix. Sketches are small enough that nothing cleverer is needed. */
internal class Matrix(val rows: Int, val cols: Int) {
    val data = DoubleArray(rows * cols)

    operator fun get(r: Int, c: Int) = data[r * cols + c]
    operator fun set(r: Int, c: Int, v: Double) {
        data[r * cols + c] = v
    }

    fun copy() = Matrix(rows, cols).also { data.copyInto(it.data) }
}

/**
 * Solves a x = b for square a by Gaussian elimination with partial pivoting.
 * Returns null if a is singular. a and b are left as they were.
 */
internal fun solveLinear(a: Matrix, b: DoubleArray): DoubleArray? {
    val n = a.rows
    val m = a.copy()
    val x = b.copyOf()
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) if (abs(m[r, col]) > abs(m[pivot, col])) pivot = r
        if (abs(m[pivot, col]) < 1e-14) return null
        if (pivot != col) {
            for (c in 0 until n) {
                val t = m[col, c]; m[col, c] = m[pivot, c]; m[pivot, c] = t
            }
            val t = x[col]; x[col] = x[pivot]; x[pivot] = t
        }
        for (r in col + 1 until n) {
            val f = m[r, col] / m[col, col]
            if (f == 0.0) continue
            for (c in col until n) m[r, c] -= f * m[col, c]
            x[r] -= f * x[col]
        }
    }
    for (r in n - 1 downTo 0) {
        var s = x[r]
        for (c in r + 1 until n) s -= m[r, c] * x[c]
        x[r] = s / m[r, r]
    }
    return x
}

/**
 * Reduced row echelon form of a, with each row scaled to unit length first so
 * the tolerance means the same for lengths and angles. Returns the pivot
 * column of each independent row; their count is the rank.
 */
internal fun rowEchelon(a: Matrix, tolerance: Double = 1e-9): Pair<Matrix, List<Int>> {
    val m = a.copy()
    for (r in 0 until m.rows) {
        var len = 0.0
        for (c in 0 until m.cols) len += m[r, c] * m[r, c]
        len = kotlin.math.sqrt(len)
        if (len > 0) for (c in 0 until m.cols) m[r, c] /= len
    }
    val pivots = mutableListOf<Int>()
    var row = 0
    for (col in 0 until m.cols) {
        if (row == m.rows) break
        var best = row
        for (r in row + 1 until m.rows) if (abs(m[r, col]) > abs(m[best, col])) best = r
        if (abs(m[best, col]) < tolerance) continue
        if (best != row) for (c in 0 until m.cols) {
            val t = m[row, c]; m[row, c] = m[best, c]; m[best, c] = t
        }
        val p = m[row, col]
        for (c in 0 until m.cols) m[row, c] /= p
        for (r in 0 until m.rows) {
            if (r == row) continue
            val f = m[r, col]
            if (f == 0.0) continue
            for (c in 0 until m.cols) m[r, c] -= f * m[row, c]
        }
        pivots += col
        row++
    }
    return m to pivots
}
