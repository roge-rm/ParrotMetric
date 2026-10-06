package com.rm.parrotmetric.design

import kotlin.test.Test
import kotlin.test.assertEquals

class DesignTest {
    @Test
    fun isolatingShowsOnlyThosePickedUntilTurnedOff() {
        val d = Design()
        val all = listOf("Body 1", "Body 2", "Body 3")
        d.bodies["Body 3"] = Design.BodyInfo(hidden = true)
        assertEquals(listOf("Body 1", "Body 2"), d.shown(all))
        d.isolated += "Body 2"
        assertEquals(listOf("Body 2"), d.shown(all))
        // Undo puts it back as it was.
        val before = d.snapshot()
        d.isolated.clear()
        assertEquals(listOf("Body 1", "Body 2"), d.shown(all))
        d.restore(before)
        assertEquals(listOf("Body 2"), d.shown(all))
        // With the isolated bodies gone or hidden, the rest show again rather than nothing.
        assertEquals(listOf("Body 1"), d.shown(listOf("Body 1")))
        d.bodies["Body 2"] = Design.BodyInfo(hidden = true)
        assertEquals(listOf("Body 1"), d.shown(all))
    }
}
