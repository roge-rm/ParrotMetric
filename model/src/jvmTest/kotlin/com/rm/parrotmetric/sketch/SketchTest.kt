package com.rm.parrotmetric.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SketchTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) =
        assertTrue(abs(expected - actual) < tol, "expected $expected, got $actual")

    /** A rough rectangle as drawn, with its sides level and upright. */
    private class Rect(val s: Sketch) {
        val p1 = s.addPoint(1.0, 2.0)
        val p2 = s.addPoint(30.0, 3.0)
        val p3 = s.addPoint(29.0, 18.0)
        val p4 = s.addPoint(2.0, 17.0)
        val bottom = s.addLine(p1, p2)
        val right = s.addLine(p2, p3)
        val top = s.addLine(p3, p4)
        val left = s.addLine(p4, p1)

        init {
            listOf(Constraint.Horizontal(bottom), Constraint.Vertical(right), Constraint.Horizontal(top), Constraint.Vertical(left))
                .forEach { assertEquals(Sketch.Added.Yes, s.add(it)) }
        }
    }

    @Test
    fun aRectangleIsSetByACornerAndTwoSides() {
        val s = Sketch()
        val r = Rect(s)
        assertEquals(4, s.freedom().count)
        near(s.y(r.p1), s.y(r.p2))
        near(s.x(r.p2), s.x(r.p3))

        assertEquals(Sketch.Added.Yes, s.add(Constraint.Coincident(r.p1, s.origin)))
        assertEquals(2, s.freedom().count)
        assertEquals(Sketch.Added.Yes, s.add(Constraint.Length(r.bottom, 40.0)))
        assertEquals(Sketch.Added.Yes, s.add(Constraint.Length(r.left, 25.0)))
        val f = s.freedom()
        assertEquals(0, f.count)
        assertTrue(f.freePoints.isEmpty())
        assertTrue(f.freeCurves.isEmpty())
        near(40.0, s.x(r.p3))
        near(25.0, s.y(r.p3))
    }

    @Test
    fun freePartsAreTheOnesStillLoose() {
        val s = Sketch()
        val r = Rect(s)
        s.add(Constraint.Coincident(r.p1, s.origin))
        s.add(Constraint.Length(r.bottom, 40.0))
        // The bottom is pinned; the top can still slide up and down.
        val f = s.freedom()
        assertEquals(1, f.count)
        assertTrue(r.p2 !in f.freePoints)
        assertTrue(r.p3 in f.freePoints && r.p4 in f.freePoints)
        assertTrue(r.bottom !in f.freeCurves)
    }

    @Test
    fun changingADimensionMovesWhatItHolds() {
        val s = Sketch()
        val r = Rect(s)
        s.add(Constraint.Coincident(r.p1, s.origin))
        val width = Constraint.Length(r.bottom, 40.0)
        s.add(width)
        s.add(Constraint.Length(r.left, 25.0))
        assertTrue(s.setDimension(width, 55.5))
        near(55.5, s.x(r.p2))
        near(55.5, s.x(r.p3))
        near(25.0, s.y(r.p4))
    }

    @Test
    fun constraintsThatAddNothingOrFightAreTurnedAway() {
        val s = Sketch()
        val r = Rect(s)
        assertEquals(Sketch.Added.AlreadySet, s.add(Constraint.Horizontal(r.bottom)))
        assertEquals(Sketch.Added.AlreadySet, s.add(Constraint.Parallel(r.bottom, r.top)))
        assertEquals(Sketch.Added.Conflicts, s.add(Constraint.Vertical(r.bottom)))
        // Nothing changed for being asked.
        assertEquals(4, s.freedom().count)
        assertEquals(4, s.constraints.size)
    }

    @Test
    fun aDimensionThatCantBeMetIsRefused() {
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0)
        val b = s.addPoint(10.0, 0.0)
        val c = s.addPoint(5.0, 8.0)
        s.addLine(a, b); s.addLine(b, c); s.addLine(c, a)
        s.add(Constraint.Distance(a, b, 10.0))
        s.add(Constraint.Distance(b, c, 8.0))
        val third = Constraint.Distance(c, a, 7.0)
        assertEquals(Sketch.Added.Yes, s.add(third))
        // The third side can't be longer than the other two together.
        assertTrue(!s.setDimension(third, 20.0))
        near(7.0, third.value)
        near(7.0, s.distance(c, a), 1e-6)
    }

    @Test
    fun aLineCanTouchACircle() {
        val s = Sketch()
        val c = s.addCircle(s.addPoint(0.0, 0.0), 10.0)
        s.add(Constraint.Coincident(c.centre, s.origin))
        s.add(Constraint.Radius(c, diameter = true, value = 20.0))
        val a = s.addPoint(-20.0, 13.0)
        val b = s.addPoint(20.0, 12.0)
        val l = s.addLine(a, b)
        s.add(Constraint.Horizontal(l))
        assertEquals(Sketch.Added.Yes, s.add(Constraint.TangentLine(l, c)))
        near(10.0, s.y(a))
        near(10.0, s.radius(c))
    }

    @Test
    fun anglesAndPerpendicularsHold() {
        val s = Sketch()
        val o = s.addPoint(0.0, 0.0)
        val l1 = s.addLine(o, s.addPoint(10.0, 0.5))
        val l2 = s.addLine(o, s.addPoint(1.0, 10.0))
        s.add(Constraint.Horizontal(l1))
        s.add(Constraint.Perpendicular(l1, l2))
        near(PI / 2, s.angle(l2) - s.angle(l1))
        val l3 = s.addLine(o, s.addPoint(8.0, 6.0))
        s.add(Constraint.Angle(l1, l3, PI / 6))
        near(PI / 6, s.angle(l3) - s.angle(l1))
    }

    @Test
    fun draggingMovesWhatsFreeAndLeavesWhatsPinned() {
        val s = Sketch()
        val r = Rect(s)
        s.add(Constraint.Coincident(r.p1, s.origin))
        // The far corner is free, so it goes where it's dragged and the rectangle follows.
        assertTrue(s.drag(r.p3, 50.0, 30.0))
        near(50.0, s.x(r.p3), 1e-3)
        near(30.0, s.y(r.p3), 1e-3)
        near(50.0, s.x(r.p2), 1e-3)
        near(0.0, s.y(r.p2))

        s.add(Constraint.Length(r.bottom, 40.0))
        s.add(Constraint.Length(r.left, 25.0))
        s.drag(r.p3, 70.0, 70.0)
        near(40.0, s.x(r.p3))
        near(25.0, s.y(r.p3))
    }

    @Test
    fun anArcKeepsItsEndsOnOneCircle() {
        val s = Sketch()
        val c = s.addPoint(0.0, 0.0)
        val arc = s.addArc(c, s.addPoint(10.0, 0.0), s.addPoint(0.0, 10.0))
        s.add(Constraint.Coincident(c, s.origin))
        s.drag(arc.start, 15.0, 1.0)
        val rs = hypot(s.x(arc.start), s.y(arc.start))
        val re = hypot(s.x(arc.end), s.y(arc.end))
        near(rs, re)
        s.add(Constraint.Radius(arc, diameter = false, value = 12.0))
        near(12.0, s.radius(arc))
    }

    @Test
    fun removingACurveTakesItsConstraintsAndLonePoints() {
        val s = Sketch()
        val r = Rect(s)
        s.remove(r.top)
        assertEquals(3, s.curves.size)
        assertEquals(3, s.constraints.size)
        // Both of the top's points are still used by the sides.
        assertEquals(5, s.points.size)
        // p4 was only the left side's by then.
        s.remove(r.left)
        assertEquals(4, s.points.size)
        assertTrue(r.p4 !in s.points)
    }
}
