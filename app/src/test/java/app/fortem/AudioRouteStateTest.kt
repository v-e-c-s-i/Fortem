// SPDX-License-Identifier: GPL-3.0-only
package app.fortem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteStateTest {
    @Test
    fun identicalDeviceSnapshotIsIgnored() {
        val state = AudioRouteState(setOf(1, 2))

        assertFalse(state.update(setOf(2, 1)))
    }

    @Test
    fun addedDeviceTriggersOnlyOnce() {
        val state = AudioRouteState(setOf(1))

        assertTrue(state.update(setOf(1, 7)))
        assertFalse(state.update(setOf(1, 7)))
    }

    @Test
    fun removedDeviceTriggersRestart() {
        val state = AudioRouteState(setOf(1, 7))

        assertTrue(state.update(setOf(1)))
    }

    @Test
    fun resetEstablishesNewBaseline() {
        val state = AudioRouteState(setOf(1))

        state.reset(setOf(1, 7))

        assertFalse(state.update(setOf(1, 7)))
    }
}
