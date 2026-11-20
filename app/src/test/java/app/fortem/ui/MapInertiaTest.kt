// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInertiaTest {
    @Test fun differentRefreshRatesFollowTheSamePath() {
        fun afterOneSecond(rate: Int): MapInertia {
            var state = MapInertia(10f, 12f)
            repeat(rate) { state = state.advance(1f / rate, 44f) }
            return state
        }
        val sixty = afterOneSecond(60)
        val oneTwenty = afterOneSecond(120)
        assertEquals(sixty.position, oneTwenty.position, .0001f)
        assertEquals(sixty.velocity, oneTwenty.velocity, .0001f)
        assertTrue(sixty.position > 10f)
    }
    @Test fun hittingEitherBoundaryStopsTheMotion() {
        assertEquals(MapInertia(0f, 0f), MapInertia(.01f, -30f).advance(.1f, 44f))
        assertEquals(MapInertia(44f, 0f), MapInertia(43.99f, 30f).advance(.1f, 44f))
    }
}
