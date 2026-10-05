package com.rm.parrotmetric.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolePresetsTest {
    @Test
    fun anInsertPocketIsWiderAndDeeperThanASelfTapHole() {
        for (size in HolePresets.sizes) {
            assertTrue(HolePresets.diameter(HoleFit.Insert, size) > HolePresets.diameter(HoleFit.SelfTap, size), size)
            assertTrue((HolePresets.depth(HoleFit.Insert, size) ?: 0.0) > 0.0, size)
            assertNull(HolePresets.depth(HoleFit.SelfTap, size))
        }
        assertEquals(4.0, HolePresets.diameter(HoleFit.Insert, "M3"))
    }

    @Test
    fun aSavedHoleIsKnownAgainByItsDiameter() {
        assertEquals(HoleFit.Insert to "M3", HolePresets.match(4.0))
        assertEquals(HoleFit.SelfTap to "M2.5", HolePresets.match(2.2))
        assertNull(HolePresets.match(3.3))
    }
}
