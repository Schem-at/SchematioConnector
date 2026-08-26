package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.HelloClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginIpcAttestGateTest {

    @Test
    fun `attests a v2 hello with a 16-byte nonce`() {
        assertTrue(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(16))))
    }

    @Test
    fun `skips v1 hellos (legacy client)`() {
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(1, "1.2.4", 0)))
    }

    @Test
    fun `skips v2 hellos with a missing or malformed nonce`() {
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(0))))
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(8))))
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(32))))
    }
}
