package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

class IpcCodecTest {

    @Test
    fun `hello server round-trips`() {
        val msg = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = "1.2.4",
            capabilities = Capabilities.DOWNLOAD_CMD or Capabilities.WANTS_COMMAND_OWNERSHIP,
        )
        val bytes = IpcCodec.encodeHelloServer(msg)
        assertEquals(IpcOpcode.HELLO_SERVER, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloServer(bytes))
    }

    @Test
    fun `hello client round-trips`() {
        val msg = HelloClient(IpcProtocol.VERSION, "1.2.4", 0)
        val bytes = IpcCodec.encodeHelloClient(msg)
        assertEquals(IpcOpcode.HELLO_CLIENT, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloClient(bytes))
    }

    @Test
    fun `load clipboard round-trips`() {
        val payload = ByteArray(257) { (it % 256).toByte() }  // exercises >127 varint length + signed bytes
        val msg = LoadClipboard(IpcProtocol.VERSION, "schem", payload)
        val bytes = IpcCodec.encodeLoadClipboard(msg)
        assertEquals(IpcOpcode.LOAD_CLIPBOARD, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeLoadClipboard(bytes))
    }

    @Test
    fun `load clipboard round-trips empty payload`() {
        val msg = LoadClipboard(IpcProtocol.VERSION, "schem", ByteArray(0))
        val bytes = IpcCodec.encodeLoadClipboard(msg)
        assertEquals(msg, IpcCodec.decodeLoadClipboard(bytes))
    }

    @Test
    fun `decoding wrong opcode throws`() {
        val bytes = IpcCodec.encodeHelloClient(HelloClient(1, "x", 0))
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeHelloServer(bytes) }
    }

    @Test
    fun `peekOpcode on empty buffer throws`() {
        assertThrows(IpcFormatException::class.java) { IpcCodec.peekOpcode(ByteArray(0)) }
    }
}
