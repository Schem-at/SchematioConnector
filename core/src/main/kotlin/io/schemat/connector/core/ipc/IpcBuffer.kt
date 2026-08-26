package io.schemat.connector.core.ipc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Thrown when a buffer is malformed or truncated. */
open class IpcFormatException(message: String) : RuntimeException(message)

/**
 * Thrown by decoders when a payload exceeds its opcode's [IpcCaps] limit. Distinct
 * from plain format errors so handlers can drop it QUIETLY (spec: no parse, no log spam).
 */
class IpcPayloadTooLargeException(message: String) : IpcFormatException(message)

/**
 * Minimal writer using Minecraft-compatible primitives:
 * varints and varint-length-prefixed UTF-8 strings.
 */
class IpcWriter {
    private val out = ArrayList<Byte>(32)

    fun writeByte(v: Int) { out.add((v and 0xFF).toByte()) }

    fun writeVarInt(v: Int) {
        var value = v
        while (true) {
            if ((value and 0x7F.inv()) == 0) { out.add(value.toByte()); return }
            out.add(((value and 0x7F) or 0x80).toByte())
            value = value ushr 7
        }
    }

    fun writeString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarInt(bytes.size)
        for (b in bytes) out.add(b)
    }

    /** Writes a raw byte blob with a varint length prefix (mirrors [IpcReader.readBytes]). */
    fun writeBytes(data: ByteArray) {
        writeVarInt(data.size)
        for (b in data) out.add(b)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads the primitives written by [IpcWriter]. */
class IpcReader(private val bytes: ByteArray) {
    private var pos = 0

    fun remaining(): Int = bytes.size - pos

    fun readByte(): Int {
        if (pos >= bytes.size) throw IpcFormatException("readByte past end of buffer")
        return bytes[pos++].toInt() and 0xFF
    }

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = readByte()
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift >= 35) throw IpcFormatException("varint too long")
        }
    }

    fun readString(): String {
        val len = readVarInt()
        if (len < 0 || len > remaining()) throw IpcFormatException("string length $len exceeds buffer")
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val s = try {
            decoder.decode(ByteBuffer.wrap(bytes, pos, len)).toString()
        } catch (e: CharacterCodingException) {
            throw IpcFormatException("string is not valid UTF-8")
        }
        pos += len
        return s
    }

    /** Reads a varint-length-prefixed raw byte blob (mirrors [IpcWriter.writeBytes]). */
    fun readBytes(): ByteArray {
        val len = readVarInt()
        if (len < 0 || len > remaining()) throw IpcFormatException("byte blob length $len exceeds buffer")
        val out = bytes.copyOfRange(pos, pos + len)
        pos += len
        return out
    }
}
