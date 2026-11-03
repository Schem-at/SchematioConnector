package io.schemat.connector.fabric.client.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeRefreshGateTest {
    @Test fun firstAttemptStartsImmediately() {
        assertTrue(MeRefreshGate(intervalMs = 30_000).tryStart(nowMs = 0))
    }

    @Test fun noSecondFetchWhileOneIsRunning() {
        val gate = MeRefreshGate(intervalMs = 0)
        assertTrue(gate.tryStart(nowMs = 0))
        assertFalse(gate.tryStart(nowMs = 10))
        gate.finish()
        assertTrue(gate.tryStart(nowMs = 20))
    }

    @Test fun failedFetchesAreRetriedAtMostOncePerInterval() {
        val gate = MeRefreshGate(intervalMs = 30_000)
        assertTrue(gate.tryStart(nowMs = 1_000)); gate.finish()
        assertFalse(gate.tryStart(nowMs = 20_000))
        assertTrue(gate.tryStart(nowMs = 31_000))
    }
}
