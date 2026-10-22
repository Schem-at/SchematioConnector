package io.schemat.connector.fabric.ipc

import io.schemat.connector.core.ipc.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FabricServerIpcTest {
    @Test
    fun `integrated server never greets`() {
        for (we in listOf(true, false)) for (tok in listOf(true, false)) for (dl in listOf(true, false)) {
            assertNull(FabricServerIpc.capabilitiesFor(false, we, tok, dl))
        }
    }

    @Test
    fun `dedicated with worldedit and token advertises server worldedit`() {
        assertEquals(
            Capabilities.DOWNLOAD_CMD or Capabilities.SERVER_WORLDEDIT,
            FabricServerIpc.capabilitiesFor(true, true, true, true),
        )
    }

    @Test
    fun `disabled download hides server worldedit`() {
        assertEquals(Capabilities.DOWNLOAD_CMD, FabricServerIpc.capabilitiesFor(true, true, true, false))
    }

    @Test
    fun `never advertises attestation-gated bits`() {
        val gated = Capabilities.LOAD_CLIPBOARD or Capabilities.UPLOAD or Capabilities.WANTS_COMMAND_OWNERSHIP
        val caps = FabricServerIpc.capabilitiesFor(true, true, true, true)!!
        assertEquals(0, caps and gated)
    }
}
