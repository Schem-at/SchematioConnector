package io.schemat.connector.fabric.client.ipc

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenUiGateTest {

    @Test
    fun `spec invariant 1 - OPEN_UI from a non-attested session is dropped`() {
        val gate = OpenUiGate()
        assertFalse(gate.tryAccept(TrustState.NONE, allowServerOpenUi = true, nowMs = 0L))
        assertFalse(gate.tryAccept(TrustState.LEGACY_V1, allowServerOpenUi = true, nowMs = 0L))
        assertFalse(gate.tryAccept(TrustState.UNVERIFIED, allowServerOpenUi = true, nowMs = 0L))
        assertTrue(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = true, nowMs = 0L))
    }

    @Test
    fun `spec invariant 2 - toggle off drops even a VERIFIED session`() {
        val gate = OpenUiGate()
        assertFalse(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = false, nowMs = 0L))
    }

    @Test
    fun `spec invariant 3 - at most one accept per 2 seconds`() {
        val gate = OpenUiGate()
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_000L))
        assertFalse(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_001L))
        assertFalse(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 11_999L))
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 12_000L))
    }

    @Test
    fun `denied attempts do not consume the rate slot`() {
        val gate = OpenUiGate()
        assertFalse(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = false, nowMs = 10_000L))
        assertFalse(gate.tryAccept(TrustState.UNVERIFIED, allowServerOpenUi = true, nowMs = 10_001L))
        // The very next qualifying attempt is accepted — denials never started the window.
        assertTrue(gate.tryAccept(TrustState.VERIFIED, allowServerOpenUi = true, nowMs = 10_002L))
    }

    @Test
    fun `reset reopens the window immediately`() {
        val gate = OpenUiGate()
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_000L))
        gate.reset()
        assertTrue(gate.tryAccept(TrustState.VERIFIED, true, nowMs = 10_001L))
    }

    @Test
    fun `prefs constants match the spec (key name, default ON)`() {
        org.junit.jupiter.api.Assertions.assertEquals("allow_server_open_ui", OpenUiPrefs.KEY)
        assertTrue(OpenUiPrefs.DEFAULT)
    }
}
