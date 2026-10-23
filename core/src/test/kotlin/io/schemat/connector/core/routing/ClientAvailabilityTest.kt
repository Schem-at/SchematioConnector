package io.schemat.connector.core.routing

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.routing.Destination.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientAvailabilityTest {

    private fun input(
        singleplayer: Boolean = false,
        pluginPresent: Boolean = true,
        caps: Int = Capabilities.SERVER_WORLDEDIT,
        localWorldEdit: Boolean = true,
        litematica: Boolean = true,
        pluginVersion: String? = "1.6.0",
    ) = AvailabilityInput(singleplayer, pluginPresent, caps, localWorldEdit, litematica, pluginVersion)

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
        // Pre-1.6.0 plugins never advertise SERVER_WORLDEDIT; LOAD_CLIPBOARD is their only signal.
        val m = ClientAvailability.unavailable(input(caps = Capabilities.LOAD_CLIPBOARD, pluginVersion = "1.5.1"))
        assertFalse(SERVER_WORLDEDIT in m)
    }

    @Test
    fun `new plugin LOAD_CLIPBOARD alone does not override per-player gating`() {
        // 1.6.0+ withholds SERVER_WORLDEDIT per player (permission, disabled download); the
        // static LOAD_CLIPBOARD bit must not re-enable the destination.
        for (v in listOf("1.6.0", "1.6.0-SNAPSHOT", "1.7.2", "unknown", null)) {
            val m = ClientAvailability.unavailable(input(caps = Capabilities.LOAD_CLIPBOARD, pluginVersion = v))
            assertEquals("This server's Schematio can't load into WorldEdit for you", m[SERVER_WORLDEDIT], "version=$v")
        }
    }

    @Test
    fun `plugin version gate for the SERVER_WORLDEDIT bit`() {
        for (v in listOf("1.6.0", "1.6.1", "1.10.0", "2.0", "1.6.0-SNAPSHOT", "1.6.0+build.7", "unknown", "", "99999999999.1", null)) {
            assertTrue(ClientAvailability.advertisesServerWorldEditBit(v), "version=$v counts as new")
        }
        for (v in listOf("1.5.1", "1.5.0", "1.5", "1.4.0", "0.9.9", "1.5.1-SNAPSHOT")) {
            assertFalse(ClientAvailability.advertisesServerWorldEditBit(v), "version=$v is legacy")
        }
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
        val m = ClientAvailability.unavailable(input(true, false, 0, false, false, null))
        assertFalse(LOCAL_FILE in m)
    }
}
