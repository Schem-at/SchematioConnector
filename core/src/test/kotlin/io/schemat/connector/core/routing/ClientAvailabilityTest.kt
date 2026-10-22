package io.schemat.connector.core.routing

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.routing.Destination.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ClientAvailabilityTest {

    private fun input(
        singleplayer: Boolean = false,
        pluginPresent: Boolean = true,
        caps: Int = Capabilities.SERVER_WORLDEDIT,
        localWorldEdit: Boolean = true,
        litematica: Boolean = true,
    ) = AvailabilityInput(singleplayer, pluginPresent, caps, localWorldEdit, litematica)

    @Test
    fun `singleplayer has no server destinations`() {
        val m = ClientAvailability.unavailable(input(singleplayer = true))
        assertEquals("Singleplayer has no separate server", m[SERVER_WORLDEDIT])
    }

    @Test
    fun `no plugin`() {
        val m = ClientAvailability.unavailable(input(pluginPresent = false, caps = 0))
        assertEquals("This server has no Schematio Connector", m[SERVER_WORLDEDIT])
    }

    @Test
    fun `plugin without worldedit bit`() {
        val m = ClientAvailability.unavailable(input(caps = Capabilities.UPLOAD))
        assertEquals("This server's Schematio can't load into WorldEdit for you", m[SERVER_WORLDEDIT])
    }

    @Test
    fun `legacy paper LOAD_CLIPBOARD counts as server worldedit`() {
        val m = ClientAvailability.unavailable(input(caps = Capabilities.LOAD_CLIPBOARD))
        assertFalse(SERVER_WORLDEDIT in m)
    }

    @Test
    fun `local worldedit needs the bridge`() {
        val m = ClientAvailability.unavailable(input(localWorldEdit = false))
        assertEquals("WorldEdit is only available in singleplayer or on a LAN host", m[LOCAL_WORLDEDIT])
    }

    @Test
    fun `litematica missing`() {
        val m = ClientAvailability.unavailable(input(litematica = false))
        assertEquals("Litematica is not installed", m[LOCAL_LITEMATICA])
    }

    @Test
    fun `file always available`() {
        val m = ClientAvailability.unavailable(input(true, false, 0, false, false))
        assertFalse(LOCAL_FILE in m)
    }
}
