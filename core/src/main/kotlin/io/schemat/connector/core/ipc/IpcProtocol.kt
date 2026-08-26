package io.schemat.connector.core.ipc

/** Wire protocol constants shared by the Bukkit plugin and the Fabric client. */
object IpcProtocol {
    /** Plugin-messaging channel id. Short to minimize per-packet bytes. */
    const val CHANNEL: String = "schematio:c"

    /** Current protocol version. Sent as a varint in every message; bump on breaking changes. */
    const val VERSION: Int = 2
}

/** First byte of every payload; selects the message type on a single multiplexed channel. */
object IpcOpcode {
    const val HELLO_SERVER: Int = 1
    const val HELLO_CLIENT: Int = 2

    /**
     * DEAD-RESERVED. The v1 raw-bytes clipboard load (POC) was removed in protocol v2:
     * receivers ignore this opcode (one rate-limited log line) and 3 must never be reused.
     */
    const val LOAD_CLIPBOARD: Int = 3

    /** S2C: backend-signed attestation of the server's community binding (protocol v2). */
    const val ATTEST: Int = 4

    /** C2S: reference-pull clipboard load — the client sends a schematic REFERENCE, never bytes. */
    const val LOAD_REQUEST: Int = 5

    /** S2C: progress/terminal status for a client request. Generic: sub-project C reuses it. */
    const val STATUS: Int = 6

    /** C->S: "push MY current WE clipboard to the backend as a draft" (sub-project C). */
    const val UPLOAD_CLIPBOARD: Int = 7

    /** S->C: the backend created a draft; draftId is an opaque id for the CLIENT's own API. */
    const val DRAFT_CREATED: Int = 8

    /**
     * S2C: command-ownership handoff — ask the client to open a UI surface instead of a
     * chat menu (protocol v2, sub-project D). Honored client-side only on a VERIFIED
     * session, with the user's allow-server-open-ui toggle on, at most once per 2 s.
     */
    const val OPEN_UI: Int = 9
}

/** Capability flags advertised in the handshake (varint bitset). */
object Capabilities {
    const val DOWNLOAD_CMD: Int = 1 shl 0
    /** Server accepts UPLOAD_CLIPBOARD: push the player's server-side WE clipboard to the backend as a draft (sub-project C). */
    const val UPLOAD: Int = 1 shl 1
    const val VERSION_CONTROL: Int = 1 shl 2      // reserved (north star)
    const val WANTS_COMMAND_OWNERSHIP: Int = 1 shl 3
    const val LOAD_CLIPBOARD: Int = 1 shl 4       // server can pull a REFERENCED schematic from the backend into a player's WorldEdit clipboard (reference-pull, protocol >= 2)

    fun has(flags: Int, bit: Int): Boolean = (flags and bit) != 0
}

/** Server platform advertised in a v2 HELLO_SERVER. */
enum class IpcPlatform(val wire: Int) {
    PAPER_PLUGIN(0),
    FABRIC_SERVER(1),
    ;

    companion object {
        /** Null for wire values from newer protocol revisions (forward compat). */
        fun fromWire(wire: Int): IpcPlatform? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Per-opcode maximum encoded payload size, enforced at the TOP of every decode
 * function: an over-cap buffer throws [IpcPayloadTooLargeException] before any
 * parsing (spec: drop quietly, no parse attempt).
 */
object IpcCaps {
    const val HELLO_SERVER: Int = 2048
    const val HELLO_CLIENT: Int = 512
    const val ATTEST: Int = 4096
    const val LOAD_REQUEST: Int = 256
    const val STATUS: Int = 512
    const val UPLOAD_CLIPBOARD: Int = 64
    const val DRAFT_CREATED: Int = 256
    const val OPEN_UI: Int = 128

    /** Null for opcodes without a live decoder (unknown / dead-reserved). */
    fun forOpcode(opcode: Int): Int? = when (opcode) {
        IpcOpcode.HELLO_SERVER -> HELLO_SERVER
        IpcOpcode.HELLO_CLIENT -> HELLO_CLIENT
        IpcOpcode.ATTEST -> ATTEST
        IpcOpcode.LOAD_REQUEST -> LOAD_REQUEST
        IpcOpcode.STATUS -> STATUS
        IpcOpcode.UPLOAD_CLIPBOARD -> UPLOAD_CLIPBOARD
        IpcOpcode.DRAFT_CREATED -> DRAFT_CREATED
        IpcOpcode.OPEN_UI -> OPEN_UI
        else -> null
    }
}

/** Reference kind carried by a LOAD_REQUEST. */
enum class LoadRefType(val wire: Int) {
    SCHEMATIC(0),
    SHARE_TOKEN(1),
    ;

    companion object {
        fun fromWire(wire: Int): LoadRefType? = entries.firstOrNull { it.wire == wire }
    }
}

/** STATUS states. Wire values are frozen protocol; states >= OK are terminal. */
enum class StatusState(val wire: Int) {
    RESOLVING(0),
    DOWNLOADING(1),
    OK(2),
    DENIED(3),
    NOT_FOUND(4),
    TOO_LARGE(5),
    RATE_LIMITED(6),
    UNAVAILABLE(7),
    ERROR(8),
    ;

    /** Terminal states complete a request; RESOLVING/DOWNLOADING are progress. */
    val isTerminal: Boolean get() = wire >= OK.wire

    companion object {
        fun fromWire(wire: Int): StatusState? = entries.firstOrNull { it.wire == wire }
    }
}

/** UI surface targeted by an OPEN_UI handoff. Wire values are frozen protocol. */
enum class OpenUiSurface(val wire: Int) {
    BROWSE(0),
    UPLOAD(1),
    SHARES(2),
    SCHEMATIC_DETAIL(3),
    SETTINGS(4),
    ;

    companion object {
        /** Null for wire values from newer protocol revisions (forward compat). */
        fun fromWire(wire: Int): OpenUiSurface? = entries.firstOrNull { it.wire == wire }
    }
}
