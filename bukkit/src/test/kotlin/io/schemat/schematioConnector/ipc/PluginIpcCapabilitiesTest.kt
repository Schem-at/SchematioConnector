package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginIpcCapabilitiesTest {

    @Test
    fun `UPLOAD is advertised only with WorldEdit AND a configured backend`() {
        // Spec: "advertised only when WorldEdit + backend configured".
        assertTrue(Capabilities.has(PluginIpcService.capabilitiesFor(true, true), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(true, false), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(false, true), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(false, false), Capabilities.UPLOAD))
    }

    @Test
    fun `existing bits are unchanged`() {
        val caps = PluginIpcService.capabilitiesFor(worldEditAvailable = true, uploadConfigured = true)
        assertTrue(Capabilities.has(caps, Capabilities.DOWNLOAD_CMD))
        assertTrue(Capabilities.has(caps, Capabilities.WANTS_COMMAND_OWNERSHIP))
        assertTrue(Capabilities.has(caps, Capabilities.LOAD_CLIPBOARD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(false, false), Capabilities.LOAD_CLIPBOARD))
    }
}
