package com.rm.parrotmetric.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchOpsTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) =
        assertTrue(abs(expected - actual) < tol, "expected $expected, got $actual")

    /** Two lines crossing like a plus sign, each 20 long, crossing in the middle. */
    private fun plus(s: Sketch): Pair<Line, Line> {
        val h = s.addLine(s.addPoint(-10.0, 0.0), s.addPoint(10.0, 0.0))
        val v = s.addLine(s.addPoint(0.0, -10.0), s.addPoint(0.0, 10.0))
        return h to v
    }

    @Test
    fun trimmingTheEndOfACrossedLineShortensIt() {
        val s = Sketch()
        val (h, _) = plus(s)
        assertNull(SketchOps.trim(s, h, 7.0, 0.0))
        val lines = s.curves.filterIsInstance<Line>()
        assertEquals(2, lines.size)
        val trimmed = lines.first { abs(s.y(it.a)) < 1e-9 && abs(s.y(it.b)) < 1e-9 }
        near(10.0, s.length(trimmed))
        // Its new end stays on the other line.
        val end = trimmed.points().first { abs(s.x(it)) < 1e-9 }
        assertTrue(s.constraints.any { it is Constraint.OnLine && it.p === end })
    }

    @Test
    fun trimmingBetweenTwoCrossingsSplitsTheLine() {
        val s = Sketch()
        val h = s.addLine(s.addPoint(-20.0, 0.0), s.addPoint(20.0, 0.0))
        s.add(Constraint.Horizontal(h))
        s.addLine(s.addPoint(-5.0, -10.0), s.addPoint(-5.0, 10.0))
        s.addLine(s.addPoint(5.0, -10.0), s.addPoint(5.0, 10.0))
        assertNull(SketchOps.trim(s, h, 0.0, 0.0))
        val pieces = s.curves.filterIsInstance<Line>().filter { abs(s.y(it.a)) < 1e-9 && abs(s.y(it.b)) < 1e-9 }
        assertEquals(2, pieces.size)
        pieces.forEach { near(15.0, s.length(it)) }
        // Both pieces stay level.
        assertEquals(2, s.constraints.count { it is Constraint.Horizontal })
    }

    @Test
    fun aLineNothingCrossesIsTrimmedAway() {
        val s = Sketch()
        val l = s.addLine(s.addPoint(0.0, 0.0), s.addPoint(5.0, 0.0))
        assertNull(SketchOps.trim(s, l, 2.0, 0.0))
        assertTrue(s.curves.isEmpty())
    }

    @Test
    fun trimmingACircleLeavesAnArc() {
        val s = Sketch()
        val c = s.addCircle(s.addPoint(0.0, 0.0), 10.0)
        s.addLine(s.addPoint(-20.0, 0.0), s.addPoint(20.0, 0.0))
        // Tap the top half: the bottom half stays.
        assertNull(SketchOps.trim(s, c, 0.0, 10.0))
        val arc = s.curves.filterIsInstance<Arc>().single()
        near(10.0, s.radius(arc))
        // Anticlockwise from (-10, 0) through the bottom to (10, 0).
        near(-10.0, s.x(arc.start)); near(10.0, s.x(arc.end))
    }

    @Test
    fun extendingALineRunsItToTheNextCurve() {
        val s = Sketch()
        val l = s.addLine(s.addPoint(0.0, 0.0), s.addPoint(5.0, 0.0))
        s.addLine(s.addPoint(12.0, -5.0), s.addPoint(12.0, 5.0))
        assertNull(SketchOps.extend(s, l, 5.0, 0.0))
        near(12.0, s.length(l))
        assertNotNull(SketchOps.extend(s, l, 0.0, 0.0))
    }

    @Test
    fun anOffsetRectangleIsBiggerAllRound() {
        val s = Sketch()
        val p = listOf(s.addPoint(0.0, 0.0), s.addPoint(40.0, 0.0), s.addPoint(40.0, 20.0), s.addPoint(0.0, 20.0))
        val sides = (0 until 4).map { s.addLine(p[it], p[(it + 1) % 4]) }
        assertNull(SketchOps.offset(s, sides, 2.0))
        val copies = s.curves.filterIsInstance<Line>().filter { it !in sides }
        assertEquals(4, copies.size)
        val xs = copies.flatMap { it.points() }.map { s.x(it) }
        val ys = copies.flatMap { it.points() }.map { s.y(it) }
        near(-2.0, xs.min()); near(42.0, xs.max())
        near(-2.0, ys.min()); near(22.0, ys.max())
        // Corners are shared: four new points, not eight.
        assertEquals(4, copies.flatMap { it.points() }.toSet().size)
    }

    @Test
    fun aCornerFilletTouchesBothLines() {
        val s = Sketch()
        val corner = s.addPoint(0.0, 0.0)
        s.addLine(corner, s.addPoint(20.0, 0.0))
        s.addLine(corner, s.addPoint(0.0, 20.0))
        assertNull(SketchOps.filletCorner(s, corner, 5.0))
        val arc = s.curves.filterIsInstance<Arc>().single()
        near(5.0, s.radius(arc))
        near(5.0, s.x(arc.centre)); near(5.0, s.y(arc.centre))
        // The lines now stop where the arc starts.
        val lines = s.curves.filterIsInstance<Line>()
        lines.forEach { near(15.0, s.length(it)) }
        assertTrue(corner !in s.points)
        // A quarter turn, the short way round.
        val a0 = kotlin.math.atan2(s.y(arc.start) - 5, s.x(arc.start) - 5)
        var a1 = kotlin.math.atan2(s.y(arc.end) - 5, s.x(arc.end) - 5)
        while (a1 <= a0) a1 += 2 * PI
        near(PI / 2, a1 - a0)
        assertNotNull(SketchOps.filletCorner(s, s.addPoint(100.0, 100.0), 1.0))
    }
}
