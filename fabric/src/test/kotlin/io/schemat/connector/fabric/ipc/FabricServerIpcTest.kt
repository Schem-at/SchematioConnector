package io.schemat.connector.fabric.ipc

import io.schemat.connector.core.ipc.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `greets only a dedicated server's routing-capable clients`() {
        val routes = Capabilities.ROUTES_DOWNLOADS
        assertTrue(FabricServerIpc.shouldGreet(dedicated = true, clientFlags = routes))
        assertTrue(FabricServerIpc.shouldGreet(dedicated = true, clientFlags = routes or Capabilities.WANTS_COMMAND_OWNERSHIP))
        // Released 1.5.x clients send HELLO_CLIENT without the bit: they never see a Fabric server.
        assertFalse(FabricServerIpc.shouldGreet(dedicated = true, clientFlags = 0))
        assertFalse(FabricServerIpc.shouldGreet(dedicated = true, clientFlags = Capabilities.WANTS_COMMAND_OWNERSHIP))
        // Integrated servers (singleplayer / LAN) never greet.
        assertFalse(FabricServerIpc.shouldGreet(dedicated = false, clientFlags = routes))
        assertFalse(FabricServerIpc.shouldGreet(dedicated = false, clientFlags = 0))
    }

    @Test
    fun `never advertises attestation-gated bits`() {
        val gated = Capabilities.LOAD_CLIPBOARD or Capabilities.UPLOAD or Capabilities.WANTS_COMMAND_OWNERSHIP
        val caps = FabricServerIpc.capabilitiesFor(true, true, true, true)!!
        assertEquals(0, caps and gated)
    }
}
