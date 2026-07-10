package io.schemat.connector.core.ipc

/** Wire protocol constants shared by the Bukkit plugin and the Fabric client. */
object IpcProtocol {
    /** Plugin-messaging channel id. Short to minimize per-packet bytes. */
    const val CHANNEL: String = "schematio:c"

    /** Current protocol version. Sent as a varint in every message; bump on breaking changes. */
    const val VERSION: Int = 1
}

/** First byte of every payload; selects the message type on a single multiplexed channel. */
object IpcOpcode {
    const val HELLO_SERVER: Int = 1
    const val HELLO_CLIENT: Int = 2

    /** C2S: client asks the server to load schematic bytes into its WorldEdit clipboard. */
    const val LOAD_CLIPBOARD: Int = 3
}

/** Capability flags advertised in the handshake (varint bitset). */
object Capabilities {
    const val DOWNLOAD_CMD: Int = 1 shl 0
    const val UPLOAD: Int = 1 shl 1               // reserved, off for POC
    const val VERSION_CONTROL: Int = 1 shl 2      // reserved (north star)
    const val WANTS_COMMAND_OWNERSHIP: Int = 1 shl 3
    const val LOAD_CLIPBOARD: Int = 1 shl 4       // server can load schematic bytes into a player's WorldEdit clipboard

    fun has(flags: Int, bit: Int): Boolean = (flags and bit) != 0
}
