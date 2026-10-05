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
        // Both joins hold: they're kept, not dropped as already set.
        assertEquals(2, s.constraints.count { it is Constraint.TangentJoin })
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

    @Test
    fun roundingADimensionedCornerKeepsItsDimensions() {
        // A 65 x 30 rectangle from the origin with its sides measured, and a hole 3.5 in from the left side.
        val s = Sketch()
        val o = s.origin
        val br = s.addPoint(65.0, 0.0); val tr = s.addPoint(65.0, 30.0); val tl = s.addPoint(0.0, 30.0)
        val bottom = s.addLine(o, br); val right = s.addLine(br, tr); val top = s.addLine(tr, tl); val left = s.addLine(tl, o)
        s.add(Constraint.Horizontal(bottom)); s.add(Constraint.Horizontal(top))
        s.add(Constraint.Vertical(right)); s.add(Constraint.Vertical(left))
        s.add(Constraint.Length(bottom, 65.0)); s.add(Constraint.Length(right, 30.0))
        val hole = s.addCircle(s.addPoint(3.5, 3.5), 1.375)
        s.add(Constraint.Radius(hole, true, 2.75))
        s.add(Constraint.PointLineDistance(hole.centre, left, 3.5))
        s.add(Constraint.PointLineDistance(hole.centre, bottom, 3.5))
        s.solve()
        assertEquals(0, s.freedom().count, "before")
        assertNull(SketchOps.filletCorner(s, tr, 3.0))
        assertEquals(0, s.freedom().count, "after one")
        assertNull(SketchOps.filletCorner(s, tl, 3.0))
        assertEquals(0, s.freedom().count, "after two")
        assertNull(SketchOps.filletCorner(s, o, 3.0))
        assertEquals(0, s.freedom().count, "after three")
        // The kept corners still measure the sides: changing the width moves the right side.
        val width = s.constraints.filterIsInstance<Constraint.Distance>().first { abs(it.value - 65.0) < 1e-9 }
        width.value = 70.0
        s.solve()
        near(70.0, s.x(br))
        near(3.5, s.x(hole.centre)); near(3.5, s.y(hole.centre))
        // The top left corner had nothing measured to it, so it went.
        assertTrue(tl !in s.points)
        assertTrue(tr in s.points)
    }

    /** A square corner at the origin: one line along x and one up y, 10 long. */
    private fun corner(s: Sketch): Point {
        val p = s.addPoint(0.0, 0.0)
        val a = s.addLine(p, s.addPoint(10.0, 0.0))
        s.add(Constraint.Horizontal(a))
        s.addLine(p, s.addPoint(0.0, 10.0))
        return p
    }

    @Test
    fun cuttingACornerLeavesThreeLines() {
        val s = Sketch()
        val p = corner(s)
        assertNull(SketchOps.chamferCorner(s, p, 2.0))
        val lines = s.curves.filterIsInstance<Line>()
        assertEquals(3, lines.size)
        near(2 * kotlin.math.sqrt(2.0), lines.minOf { s.length(it) })
        assertTrue(p !in s.points)
        // The level line stays level.
        assertTrue(s.constraints.any { it is Constraint.Horizontal })
        val other = Sketch()
        assertNotNull(SketchOps.chamferCorner(other, corner(other), 20.0))
    }

    @Test
    fun breakingALineMakesTwoInLine() {
        val s = Sketch()
        val l = s.addLine(s.addPoint(0.0, 0.0), s.addPoint(10.0, 0.0))
        assertNull(SketchOps.breakAt(s, l, 4.0, 0.3))
        val lines = s.curves.filterIsInstance<Line>()
        assertEquals(2, lines.size)
        near(10.0, lines.sumOf { s.length(it) })
        near(4.0, lines.minOf { s.length(it) })
        assertNotNull(SketchOps.breakAt(s, lines[0], 0.0, 0.0))
    }

    @Test
    fun mirroringCopiesAcrossALineAndKeepsItThere() {
        val s = Sketch()
        val axis = s.addLine(s.addPoint(0.0, -10.0), s.addPoint(0.0, 10.0))
        s.add(Constraint.Vertical(axis))
        s.add(Constraint.Fixed(axis.a, 0.0, -10.0))
        val c = s.addCircle(s.addPoint(5.0, 2.0), 3.0)
        val l = s.addLine(s.addPoint(2.0, 0.0), s.addPoint(6.0, 6.0))
        assertNull(SketchOps.mirror(s, listOf(c, l), axis))
        val circles = s.curves.filterIsInstance<Circle>()
        assertEquals(2, circles.size)
        val copy = circles.first { it !== c }
        near(-5.0, s.x(copy.centre))
        near(3.0, s.radius(copy))
        // Moving the original moves the copy with it.
        s.drag(c.centre, 7.0, 2.0)
        near(-s.x(c.centre), s.x(copy.centre))
    }

    @Test
    fun movingScalesSizesWithIt() {
        val s = Sketch()
        val l = s.addLine(s.addPoint(0.0, 0.0), s.addPoint(10.0, 0.0))
        s.add(Constraint.Length(l, 10.0))
        assertNull(SketchOps.move(s, listOf(l), 2.0) { x, y -> x * 2 to y * 2 })
        near(20.0, s.length(l))
        val copies = SketchOps.copy(s, listOf(l), 1.0, false) { x, y -> x to y + 5 }
        assertEquals(2, copies.size)
        assertEquals(2, s.curves.size)
    }
}
