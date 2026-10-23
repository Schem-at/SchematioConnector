package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpcProtocolTest {

    @Test
    fun `a registration that includes our channel is detected`() {
        assertTrue(IpcProtocol.registersChannel(listOf("minecraft:brand", "schematio:c")))
    }

    @Test
    fun `a registration without our channel is ignored`() {
        assertFalse(IpcProtocol.registersChannel(listOf("minecraft:brand", "schematio:cx", "other:c")))
        assertFalse(IpcProtocol.registersChannel(emptyList()))
    }
}
