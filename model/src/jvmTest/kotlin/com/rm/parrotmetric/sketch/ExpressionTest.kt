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
