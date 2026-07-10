package io.schemat.connector.core.ipc

data class HelloServer(
    val protocolVersion: Int,
    val pluginVersion: String,
    val capabilities: Int,
)

data class HelloClient(
    val protocolVersion: Int,
    val modVersion: String,
    val clientFlags: Int,
)

/**
 * C2S request to load schematic [bytes] into the sender's server-side WorldEdit clipboard.
 * [format] is an advisory hint (e.g. "schem", "sponge", "mcedit"); the server may auto-detect.
 *
 * Note: equals/hashCode are overridden because [bytes] is a [ByteArray] (data-class identity
 * equality would break round-trip tests).
 */
class LoadClipboard(
    val protocolVersion: Int,
    val format: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadClipboard) return false
        return protocolVersion == other.protocolVersion &&
            format == other.format &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + format.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "LoadClipboard(protocolVersion=$protocolVersion, format=$format, bytes=${bytes.size}B)"
}

/** Encodes/decodes IPC messages to/from raw byte arrays (the plugin-message body). */
object IpcCodec {

    fun encodeHelloServer(msg: HelloServer): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_SERVER)
        writeVarInt(msg.protocolVersion)
        writeString(msg.pluginVersion)
        writeVarInt(msg.capabilities)
    }.toByteArray()

    fun encodeHelloClient(msg: HelloClient): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_CLIENT)
        writeVarInt(msg.protocolVersion)
        writeString(msg.modVersion)
        writeVarInt(msg.clientFlags)
    }.toByteArray()

    fun encodeLoadClipboard(msg: LoadClipboard): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.LOAD_CLIPBOARD)
        writeVarInt(msg.protocolVersion)
        writeString(msg.format)
        writeBytes(msg.bytes)
    }.toByteArray()

    /** Reads only the leading opcode without consuming the rest. */
    fun peekOpcode(bytes: ByteArray): Int = IpcReader(bytes).readByte()

    fun decodeHelloServer(bytes: ByteArray): HelloServer {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_SERVER) throw IpcFormatException("expected HELLO_SERVER, got $op")
        return HelloServer(
            protocolVersion = r.readVarInt(),
            pluginVersion = r.readString(),
            capabilities = r.readVarInt(),
        )
    }

    fun decodeHelloClient(bytes: ByteArray): HelloClient {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_CLIENT) throw IpcFormatException("expected HELLO_CLIENT, got $op")
        return HelloClient(
            protocolVersion = r.readVarInt(),
            modVersion = r.readString(),
            clientFlags = r.readVarInt(),
        )
    }

    fun decodeLoadClipboard(bytes: ByteArray): LoadClipboard {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.LOAD_CLIPBOARD) throw IpcFormatException("expected LOAD_CLIPBOARD, got $op")
        return LoadClipboard(
            protocolVersion = r.readVarInt(),
            format = r.readString(),
            bytes = r.readBytes(),
        )
    }
}
