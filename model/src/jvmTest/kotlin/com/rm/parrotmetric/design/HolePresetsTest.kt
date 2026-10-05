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
        assertEquals(6.0, HolePresets.depth(HoleFit.Insert, "M3"))
    }

    @Test
    fun aClearanceHoleIsWiderThanTheScrewAndItsHeadWiderStill() {
        for (size in HolePresets.sizes) {
            val screw = size.drop(1).toDouble()
            val hole = HolePresets.diameter(HoleFit.Clearance, size)
            assertTrue(hole > screw, size)
            assertTrue(HolePresets.top(HoleKind.Countersink, size)!!.first > hole, size)
            assertTrue(HolePresets.top(HoleKind.Counterbore, size)!!.second > 0, size)
        }
        // Each size of each kind can be told apart when a hole is opened again.
        val all = HoleFit.entries.flatMap { f -> HolePresets.sizes.map { HolePresets.diameter(f, it) } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun aSavedHoleIsKnownAgainByItsDiameter() {
        assertEquals(HoleFit.Insert to "M3", HolePresets.match(4.0))
        assertEquals(HoleFit.SelfTap to "M3", HolePresets.match(2.0))
        assertEquals(HoleFit.Clearance to "M3", HolePresets.match(3.5))
        assertNull(HolePresets.match(3.1))
    }
}
