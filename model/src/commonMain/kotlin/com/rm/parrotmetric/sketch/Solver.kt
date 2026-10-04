package com.rm.parrotmetric.sketch

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Newton steps on the sketch's constraint equations. Each step is the
 * smallest change (weighted, so dragged points resist moving) that would make
 * every residual zero if the equations were straight lines, so a sketch with
 * room to move changes as little as it can.
 */
internal class Solver(private val sketch: Sketch) {
    private val constraints = sketch.constraints
    private val values = sketch.values

    /** The slots that can move: everything the constraints read, except the origin. */
    private val unknowns: IntArray = constraints.flatMap { it.slots().asList() }
        .distinct()
        .filter { it != sketch.origin.x && it != sketch.origin.y }
        .sorted()
        .toIntArray()
    private val column = unknowns.withIndex().associate { (i, s) -> s to i }

    private fun residuals(): DoubleArray {
        val out = mutableListOf<Double>()
        for (c in constraints) for (r in c.residuals(::read)) out += r
        return out.toDoubleArray()
    }

    private fun read(i: Int) = values[i]

    /** d residual / d unknown, by central differences on just the slots each constraint reads. */
    private fun jacobian(): Matrix {
        val rows = constraints.sumOf { it.residuals(::read).size }
        val j = Matrix(rows, unknowns.size)
        var row = 0
        for (c in constraints) {
            val n = c.residuals(::read).size
            for (s in c.slots()) {
                val col = column[s] ?: continue
                val x = values[s]
                val h = 1e-7 * max(1.0, abs(x))
                values[s] = x + h
                val plus = c.residuals(::read)
                values[s] = x - h
                val minus = c.residuals(::read)
                values[s] = x
                for (k in 0 until n) j[row + k, col] = (plus[k] - minus[k]) / (2 * h)
            }
            row += n
        }
        return j
    }

    private fun error(r: DoubleArray) = r.fold(0.0) { m, x -> max(m, abs(x)) }
    private fun norm(r: DoubleArray) = sqrt(r.sumOf { it * it })

    /**
     * Solves, after first putting the dragged slots at their targets.
     * True if every constraint holds afterwards.
     */
    fun solve(dragged: List<Int> = emptyList(), targets: List<Double> = emptyList()): Boolean {
        for (i in dragged.indices) values[dragged[i]] = targets[i]
        if (constraints.isEmpty()) return true
        // Inverse weights: dragged slots move a millionth as readily.
        val inverseWeight = DoubleArray(unknowns.size) { 1.0 }
        for (s in dragged) column[s]?.let { inverseWeight[it] = 1e-6 }

        var r = residuals()
        repeat(60) {
            if (error(r) < 1e-10) return true
            val j = jacobian()
            val m = j.rows
            // A = J W⁻¹ Jᵀ, with a touch added to the diagonal so rows that say the same thing don't make it singular.
            val a = Matrix(m, m)
            for (p in 0 until m) for (q in p until m) {
                var s = 0.0
                for (c in 0 until j.cols) s += j[p, c] * inverseWeight[c] * j[q, c]
                a[p, q] = s
                a[q, p] = s
            }
            for (p in 0 until m) a[p, p] += 1e-12 + a[p, p] * 1e-10
            val y = solveLinear(a, r) ?: return false
            val step = DoubleArray(unknowns.size)
            for (c in 0 until j.cols) {
                var s = 0.0
                for (p in 0 until m) s += j[p, c] * y[p]
                step[c] = -inverseWeight[c] * s
            }
            // Take the whole step if it helps, else shorter ones.
            val start = DoubleArray(unknowns.size) { values[unknowns[it]] }
            val before = norm(r)
            var scale = 1.0
            while (true) {
                for (c in unknowns.indices) values[unknowns[c]] = start[c] + scale * step[c]
                val next = residuals()
                if (norm(next) < before || scale < 1e-3) {
                    r = next
                    break
                }
                scale /= 2
            }
        }
        return error(r) < 1e-7
    }

    /** How many independent equations the constraints make at the current positions. */
    fun rank(): Int = if (constraints.isEmpty()) 0 else rowEchelon(jacobian()).second.size

    fun freedom(): Sketch.Freedom {
        // Every slot a live point or circle has, origin aside, can move unless the constraints pin it.
        val live = mutableSetOf<Int>()
        for (p in sketch.points) if (p !== sketch.origin) { live += p.x; live += p.y }
        for (c in sketch.curves) if (c is Circle) live += c.r

        val pinned = mutableSetOf<Int>()
        var rank = 0
        if (constraints.isNotEmpty()) {
            val (e, pivots) = rowEchelon(jacobian())
            rank = pivots.size
            val pivotSet = pivots.toSet()
            val freeColumns = (0 until e.cols).filter { it !in pivotSet }
            // A pivot slot is pinned if no free slot changes it.
            for ((row, col) in pivots.withIndex()) {
                if (freeColumns.all { abs(e[row, it]) < 1e-9 }) pinned += unknowns[col]
            }
        }
        val freeSlots = live - pinned
        val freePoints = sketch.points.filter { it !== sketch.origin && (it.x in freeSlots || it.y in freeSlots) }.toSet()
        val freeCurves = sketch.curves.filter { c ->
            c.points().any { it in freePoints } || (c is Circle && c.r in freeSlots)
        }.toSet()
        return Sketch.Freedom(live.size - rank, freePoints, freeCurves)
    }
}
