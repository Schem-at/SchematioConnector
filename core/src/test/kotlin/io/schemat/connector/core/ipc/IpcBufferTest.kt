package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

class IpcBufferTest {

    @Test
    fun `byte round-trips as unsigned`() {
        val bytes = IpcWriter().apply { writeByte(200) }.toByteArray()
        assertEquals(200, IpcReader(bytes).readByte())
    }

    @Test
    fun `varint round-trips across boundaries`() {
        for (v in intArrayOf(0, 1, 127, 128, 255, 256, 16383, 16384, 2097151, Int.MAX_VALUE)) {
            val bytes = IpcWriter().apply { writeVarInt(v) }.toByteArray()
            assertEquals(v, IpcReader(bytes).readVarInt(), "varint $v")
        }
    }

    @Test
    fun `string round-trips including unicode`() {
        val s = "Schematio v1.2.4 — café ✓"
        val bytes = IpcWriter().apply { writeString(s) }.toByteArray()
        assertEquals(s, IpcReader(bytes).readString())
    }

    @Test
    fun `mixed sequence reads back in order`() {
        val bytes = IpcWriter().apply {
            writeByte(1); writeVarInt(300); writeString("hi")
        }.toByteArray()
        val r = IpcReader(bytes)
        assertEquals(1, r.readByte())
        assertEquals(300, r.readVarInt())
        assertEquals("hi", r.readString())
        assertEquals(0, r.remaining())
    }

    @Test
    fun `truncated buffer throws IpcFormatException`() {
        assertThrows(IpcFormatException::class.java) { IpcReader(ByteArray(0)).readByte() }
    }
}
