package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerReadinessTest {

    @Test
    fun `ready only when all four hold`() {
        for (mask in 0 until 16) {
            val a = mask and 1 != 0
            val b = mask and 2 != 0
            val c = mask and 4 != 0
            val d = mask and 8 != 0
            assertEquals(mask == 15, ServerReadiness.serverWorldEdit(a, b, c, d), "mask=$mask")
        }
    }

    @Test
    fun `not found names the community`() {
        assertTrue("join the Redshift community" in ServerReadiness.notFoundMessage("Redshift"))
    }

    @Test
    fun `not found falls back`() {
        assertTrue("join this server's community" in ServerReadiness.notFoundMessage(null))
        assertTrue("join this server's community" in ServerReadiness.notFoundMessage(""))
    }

    @Test
    fun `bit is distinct`() {
        val existing = listOf(
            Capabilities.DOWNLOAD_CMD, Capabilities.UPLOAD, Capabilities.VERSION_CONTROL,
            Capabilities.WANTS_COMMAND_OWNERSHIP, Capabilities.LOAD_CLIPBOARD,
        )
        for (bit in existing) assertEquals(0, Capabilities.SERVER_WORLDEDIT and bit)
        assertEquals(1 shl 5, Capabilities.SERVER_WORLDEDIT)
    }
}
