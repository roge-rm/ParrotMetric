package com.rm.parrotmetric.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberPadTest {
    private fun keys(start: TextFieldValue, keys: String) = keys.fold(start) { v, k -> padKey(v, k) }

    @Test
    fun theFirstKeyReplacesWhatsSelected() {
        val v = keys(TextFieldValue("10", TextRange(0, 2)), "25")
        assertEquals("25", v.text)
        assertEquals(TextRange(2), v.selection)
    }

    @Test
    fun keysTypeAtTheCursorAndSumsUseTheParsersSigns() {
        val v = keys(TextFieldValue("10", TextRange(2)), "×(3−1)÷2")
        assertEquals("10*(3-1)/2", v.text)
        assertEquals(com.rm.parrotmetric.sketch.Expression.evaluate(v.text, emptyMap()), 10.0)
    }

    @Test
    fun backspaceTakesTheSelectionOrTheOneBefore() {
        assertEquals("1", keys(TextFieldValue("12", TextRange(2)), "⌫").text)
        assertEquals("", keys(TextFieldValue("12", TextRange(0, 2)), "⌫").text)
        assertEquals("12", keys(TextFieldValue("12", TextRange(0)), "⌫").text)
    }
}
