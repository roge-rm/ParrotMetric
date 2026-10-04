package com.rm.parrotmetric.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpressionTest {
    @Test
    fun readsNumbersSumsAndUnits() {
        assertEquals(12.5, Expression.evaluate("12.5"))
        assertEquals(12.5, Expression.evaluate("12,5"))
        assertEquals(30.0, Expression.evaluate("10 + 4 * 5"))
        assertEquals(70.0, Expression.evaluate("(10 + 4) * 5"))
        assertEquals(25.4, Expression.evaluate("1in"))
        assertEquals(50.8, Expression.evaluate("2\""))
        assertEquals(15.0, Expression.evaluate("1.5 cm"))
        assertEquals(1000.0, Expression.evaluate("1m"))
        assertEquals(1.0, Expression.evaluate("1mm"))
        assertEquals(45.0, Expression.evaluate("90° / 2"))
        assertEquals(-3.0, Expression.evaluate("-3"))
    }

    @Test
    fun turnsAwayWhatItCantRead() {
        assertNull(Expression.evaluate(""))
        assertNull(Expression.evaluate("12 +"))
        assertNull(Expression.evaluate("abc"))
        assertNull(Expression.evaluate("1 / 0"))
        assertNull(Expression.evaluate("(2"))
    }
}

class ParameterTest {
    @Test
    fun namesAreLookedUp() {
        val names = mapOf("wall" to 2.0, "width" to 40.0)
        assertEquals(44.0, Expression.evaluate("width + wall*2", names))
        assertEquals(4.0, Expression.evaluate("wall in / 25.4 * 2", names))
        assertNull(Expression.evaluate("depth", names))
        assertEquals(true, Expression.usesNames("width*2"))
        assertEquals(false, Expression.usesNames("12mm + 1in"))
    }

    @Test
    fun parametersCanUseTheOnesAbove() {
        val v = com.rm.parrotmetric.design.Parametrics.values(
            listOf(
                com.rm.parrotmetric.design.Parameter("wall", "2"),
                com.rm.parrotmetric.design.Parameter("outer", "wall * 10"),
                com.rm.parrotmetric.design.Parameter("bad", "nope + 1"),
            ),
        )
        assertEquals(20.0, v["outer"])
        assertEquals(null, v["bad"])
    }

    @Test
    fun changingAParameterChangesWhatUsesIt() {
        val d = com.rm.parrotmetric.design.Design()
        val s = Sketch()
        val a = s.addPoint(0.0, 0.0); val b = s.addPoint(10.0, 0.0)
        val l = s.addLine(a, b)
        s.add(Constraint.Fixed(a, 0.0, 0.0))
        s.add(Constraint.Horizontal(l))
        val len = Constraint.Length(l, 10.0).also { it.expression = "width" }
        s.add(len)
        d.add(com.rm.parrotmetric.design.SketchFeature(d.newId(), "Sketch", com.rm.parrotmetric.design.PlaneRef.Fixed(SketchPlane.Top), s))
        val ex = com.rm.parrotmetric.design.ExtrudeFeature(d.newId(), "Extrude", 1, emptyList(), 5.0, 0.0, com.rm.parrotmetric.design.Operation.NewBody)
        d.add(ex)
        d.expressions[ex.id] = mapOf("forward" to "width / 2")
        d.parameters += com.rm.parrotmetric.design.Parameter("width", "30")
        val built = com.rm.parrotmetric.design.Parametrics.apply(d, d.active)
        assertEquals(15.0, (built[1] as com.rm.parrotmetric.design.ExtrudeFeature).forward)
        assertEquals(30.0, s.length(l), 1e-6)
    }
}
