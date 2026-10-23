package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginIpcCapabilitiesTest {

    private fun caps(
        worldEdit: Boolean,
        upload: Boolean,
        token: Boolean = true,
        download: Boolean = true,
        perm: Boolean = true,
    ): Int = PluginIpcService.capabilitiesFor(worldEdit, upload, token, download, perm)

    @Test
    fun `server worldedit needs permission`() {
        assertFalse(Capabilities.has(caps(true, true, perm = false), Capabilities.SERVER_WORLDEDIT))
        assertTrue(Capabilities.has(caps(true, true, token = true, download = true, perm = true), Capabilities.SERVER_WORLDEDIT))
        assertFalse(Capabilities.has(caps(false, true), Capabilities.SERVER_WORLDEDIT))
        assertFalse(Capabilities.has(caps(true, true, token = false), Capabilities.SERVER_WORLDEDIT))
        assertFalse(Capabilities.has(caps(true, true, download = false), Capabilities.SERVER_WORLDEDIT))
    }

    @Test
    fun `load clipboard unchanged`() {
        assertTrue(Capabilities.has(caps(true, false, token = false, download = false, perm = false), Capabilities.LOAD_CLIPBOARD))
        assertFalse(Capabilities.has(caps(false, true), Capabilities.LOAD_CLIPBOARD))
    }

    @Test
    fun `UPLOAD is advertised only with WorldEdit AND a configured backend`() {
        // Spec: "advertised only when WorldEdit + backend configured".
        assertTrue(Capabilities.has(caps(true, true), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(caps(true, false), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(caps(false, true), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(caps(false, false), Capabilities.UPLOAD))
    }

    @Test
    fun `existing bits are unchanged`() {
        val caps = caps(true, true)
        assertTrue(Capabilities.has(caps, Capabilities.DOWNLOAD_CMD))
        assertTrue(Capabilities.has(caps, Capabilities.WANTS_COMMAND_OWNERSHIP))
        assertTrue(Capabilities.has(caps, Capabilities.LOAD_CLIPBOARD))
        assertFalse(Capabilities.has(caps(false, false), Capabilities.LOAD_CLIPBOARD))
    }
}
