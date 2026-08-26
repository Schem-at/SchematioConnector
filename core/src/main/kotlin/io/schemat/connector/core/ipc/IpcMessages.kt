package io.schemat.connector.core.ipc

data class HelloServer(
    val protocolVersion: Int,
    val pluginVersion: String,
    val capabilities: Int,
    // --- v2 identity fields; defaults represent "not sent" (v1 peer) ---
    val platform: IpcPlatform? = null,
    val serverSoftware: String = "",
    val mcVersion: String = "",
    val backendHost: String = "",
    val communityId: String = "",
    val communitySlug: String = "",
)

/**
 * v2 adds [nonce]: 16 SecureRandom bytes the client expects back inside the signed
 * attestation payload (hex-lowercase). Empty nonce = "don't attest me" (v1, or opt-out).
 * Not a data class: ByteArray needs content equality for round-trip tests.
 */
class HelloClient(
    val protocolVersion: Int,
    val modVersion: String,
    val clientFlags: Int,
    val nonce: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelloClient) return false
        return protocolVersion == other.protocolVersion &&
            modVersion == other.modVersion &&
            clientFlags == other.clientFlags &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + modVersion.hashCode()
        result = 31 * result + clientFlags
        result = 31 * result + nonce.contentHashCode()
        return result
    }

    override fun toString(): String =
        "HelloClient(protocolVersion=$protocolVersion, modVersion=$modVersion, clientFlags=$clientFlags, nonce=${nonce.size}B)"
}

/**
 * S2C: the backend's signed attestation, relayed VERBATIM by the server. [payloadJson] is the
 * exact canonical string the backend signed (the client must verify the received bytes, never
 * re-serialize); [signature] is a 64-byte detached Ed25519 signature; [keyId] selects the
 * public key in the backend's /.well-known/schematio-keys.json document.
 */
class Attest(
    val protocolVersion: Int,
    val payloadJson: String,
    val signature: ByteArray,
    val keyId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attest) return false
        return protocolVersion == other.protocolVersion &&
            payloadJson == other.payloadJson &&
            signature.contentEquals(other.signature) &&
            keyId == other.keyId
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + payloadJson.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + keyId.hashCode()
        return result
    }

    override fun toString(): String =
        "Attest(protocolVersion=$protocolVersion, payload=${payloadJson.length}ch, signature=${signature.size}B, keyId=$keyId)"
}

/**
 * C2S: reference-pull clipboard load. Carries a REFERENCE only — never schematic
 * bytes (spec invariant 1). [refType] and lengths are validated at construction;
 * [versionId] == "" means "default branch head".
 */
data class LoadRequest(
    val protocolVersion: Int,
    val requestId: Int,
    val refType: Int,
    val refId: String,
    val versionId: String = "",
) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
        require(LoadRefType.fromWire(refType) != null) { "unknown refType $refType" }
        require(refId.isNotEmpty() && refId.length <= MAX_REF_CHARS) { "refId must be 1..$MAX_REF_CHARS chars" }
        require(versionId.length <= MAX_REF_CHARS) { "versionId must be at most $MAX_REF_CHARS chars" }
    }

    companion object {
        const val MAX_REF_CHARS: Int = 64
    }
}

/**
 * S2C: progress/terminal status for [requestId]. [detail] is optional human text —
 * clients render it as PLAIN text (formatting codes stripped client-side).
 */
data class Status(
    val protocolVersion: Int,
    val requestId: Int,
    val state: Int,
    val detail: String = "",
) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
        require(StatusState.fromWire(state) != null) { "unknown status state $state" }
        require(detail.length <= MAX_DETAIL_CHARS) { "detail must be at most $MAX_DETAIL_CHARS chars" }
    }

    companion object {
        const val MAX_DETAIL_CHARS: Int = 256
    }
}

/**
 * C->S (opcode 7): upload the requesting player's CURRENT server-side WorldEdit
 * clipboard as a backend draft. Deliberately field-free beyond the requestId —
 * the subject is always "my clipboard" (spec).
 */
data class UploadClipboard(val protocolVersion: Int, val requestId: Int) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
    }
}

/**
 * S->C (opcode 8): the backend created a draft. [draftId] is an OPAQUE id the client
 * resolves against its OWN backend with the USER's auth — nothing else from the
 * server is trusted (spec).
 */
data class DraftCreated(val protocolVersion: Int, val requestId: Int, val draftId: String) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
        require(draftId.isNotEmpty() && draftId.length <= MAX_DRAFT_ID_CHARS) {
            "draftId must be 1..$MAX_DRAFT_ID_CHARS chars"
        }
    }

    companion object {
        const val MAX_DRAFT_ID_CHARS = 64
    }
}

/**
 * S2C: command-ownership handoff (sub-project D). The server asks the client to open a
 * UI surface instead of printing a chat menu. The client honors it ONLY on a VERIFIED
 * (attested) session, only while the user's "server may open UI" toggle is on, and at
 * most once per 2 s — a hostile-but-attested server may not pop UI against the user's
 * will. [contextId] is an OPAQUE id (SCHEMATIC_DETAIL only) that the client resolves
 * exclusively through its OWN authenticated backend API — never trusted for anything else.
 */
data class OpenUi(
    val protocolVersion: Int,
    val surface: Int,
    val contextId: String = "",
) {
    init {
        require(OpenUiSurface.fromWire(surface) != null) { "unknown surface $surface" }
        require(contextId.length <= MAX_CONTEXT_CHARS) { "contextId must be at most $MAX_CONTEXT_CHARS chars" }
        require(contextId.isEmpty() || surface == OpenUiSurface.SCHEMATIC_DETAIL.wire) {
            "contextId is only valid for SCHEMATIC_DETAIL"
        }
    }

    companion object {
        const val MAX_CONTEXT_CHARS: Int = 64
    }
}

/** Encodes/decodes IPC messages to/from raw byte arrays (the plugin-message body). */
object IpcCodec {

    /** Spec: payloads over their opcode cap are dropped without any parse attempt. */
    private fun checkCap(bytes: ByteArray, opcode: Int) {
        val cap = IpcCaps.forOpcode(opcode) ?: return
        if (bytes.size > cap) {
            throw IpcPayloadTooLargeException("opcode $opcode payload ${bytes.size}B exceeds cap ${cap}B")
        }
    }

    fun encodeHelloServer(msg: HelloServer): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_SERVER)
        writeVarInt(msg.protocolVersion)
        writeString(msg.pluginVersion)
        writeVarInt(msg.capabilities)
        if (msg.protocolVersion >= 2) {
            writeVarInt(requireNotNull(msg.platform) { "platform is required for v2 HELLO_SERVER" }.wire)
            writeString(msg.serverSoftware)
            writeString(msg.mcVersion)
            writeString(msg.backendHost)
            writeString(msg.communityId)
            writeString(msg.communitySlug)
        }
    }.toByteArray()

    fun encodeHelloClient(msg: HelloClient): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_CLIENT)
        writeVarInt(msg.protocolVersion)
        writeString(msg.modVersion)
        writeVarInt(msg.clientFlags)
        if (msg.protocolVersion >= 2) {
            writeBytes(msg.nonce)
        }
    }.toByteArray()

    fun encodeAttest(msg: Attest): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.ATTEST)
        writeVarInt(msg.protocolVersion)
        writeString(msg.payloadJson)
        writeBytes(msg.signature)
        writeString(msg.keyId)
    }.toByteArray()

    /** Reads only the leading opcode without consuming the rest. */
    fun peekOpcode(bytes: ByteArray): Int = IpcReader(bytes).readByte()

    fun decodeHelloServer(bytes: ByteArray): HelloServer {
        checkCap(bytes, IpcOpcode.HELLO_SERVER)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_SERVER) throw IpcFormatException("expected HELLO_SERVER, got $op")
        val protocolVersion = r.readVarInt()
        val pluginVersion = r.readString()
        val capabilities = r.readVarInt()
        if (protocolVersion < 2) {
            return HelloServer(protocolVersion, pluginVersion, capabilities)
        }
        return HelloServer(
            protocolVersion = protocolVersion,
            pluginVersion = pluginVersion,
            capabilities = capabilities,
            platform = IpcPlatform.fromWire(r.readVarInt()),
            serverSoftware = r.readString(),
            mcVersion = r.readString(),
            backendHost = r.readString(),
            communityId = r.readString(),
            communitySlug = r.readString(),
        )
    }

    fun decodeHelloClient(bytes: ByteArray): HelloClient {
        checkCap(bytes, IpcOpcode.HELLO_CLIENT)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_CLIENT) throw IpcFormatException("expected HELLO_CLIENT, got $op")
        val protocolVersion = r.readVarInt()
        val modVersion = r.readString()
        val clientFlags = r.readVarInt()
        val nonce = if (protocolVersion >= 2 && r.remaining() > 0) r.readBytes() else ByteArray(0)
        return HelloClient(protocolVersion, modVersion, clientFlags, nonce)
    }

    fun decodeAttest(bytes: ByteArray): Attest {
        checkCap(bytes, IpcOpcode.ATTEST)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.ATTEST) throw IpcFormatException("expected ATTEST, got $op")
        return Attest(
            protocolVersion = r.readVarInt(),
            payloadJson = r.readString(),
            signature = r.readBytes(),
            keyId = r.readString(),
        )
    }

    fun encodeLoadRequest(msg: LoadRequest): ByteArray {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.LOAD_REQUEST)
            writeVarInt(msg.protocolVersion)
            writeVarInt(msg.requestId)
            writeByte(msg.refType)
            writeString(msg.refId)
            writeString(msg.versionId)
        }.toByteArray()
        require(bytes.size <= IpcCaps.LOAD_REQUEST) {
            "encoded LOAD_REQUEST is ${bytes.size}B (cap ${IpcCaps.LOAD_REQUEST}B) — refs must be ASCII ids"
        }
        return bytes
    }

    /** If [msg.detail] pushes the frame over the cap (multibyte text), it is sent empty instead. */
    fun encodeStatus(msg: Status): ByteArray {
        val bytes = rawEncodeStatus(msg)
        if (bytes.size <= IpcCaps.STATUS) return bytes
        return rawEncodeStatus(Status(msg.protocolVersion, msg.requestId, msg.state, ""))
    }

    private fun rawEncodeStatus(msg: Status): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.STATUS)
        writeVarInt(msg.protocolVersion)
        writeVarInt(msg.requestId)
        writeByte(msg.state)
        writeString(msg.detail)
    }.toByteArray()

    fun decodeLoadRequest(bytes: ByteArray): LoadRequest {
        checkCap(bytes, IpcOpcode.LOAD_REQUEST)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.LOAD_REQUEST) throw IpcFormatException("expected LOAD_REQUEST, got $op")
        try {
            return LoadRequest(
                protocolVersion = r.readVarInt(),
                requestId = r.readVarInt(),
                refType = r.readByte(),
                refId = r.readString(),
                versionId = r.readString(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid LOAD_REQUEST: ${e.message}")
        }
    }

    fun decodeStatus(bytes: ByteArray): Status {
        checkCap(bytes, IpcOpcode.STATUS)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.STATUS) throw IpcFormatException("expected STATUS, got $op")
        try {
            return Status(
                protocolVersion = r.readVarInt(),
                requestId = r.readVarInt(),
                state = r.readByte(),
                detail = r.readString(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid STATUS: ${e.message}")
        }
    }

    fun encodeUploadClipboard(msg: UploadClipboard): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.UPLOAD_CLIPBOARD)
        writeVarInt(msg.protocolVersion)
        writeVarInt(msg.requestId)
    }.toByteArray()

    fun decodeUploadClipboard(bytes: ByteArray): UploadClipboard {
        checkCap(bytes, IpcOpcode.UPLOAD_CLIPBOARD)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.UPLOAD_CLIPBOARD) throw IpcFormatException("expected UPLOAD_CLIPBOARD, got $op")
        try {
            return UploadClipboard(
                protocolVersion = r.readVarInt(),
                requestId = r.readVarInt(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid UPLOAD_CLIPBOARD: ${e.message}")
        }
    }

    fun encodeDraftCreated(msg: DraftCreated): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.DRAFT_CREATED)
        writeVarInt(msg.protocolVersion)
        writeVarInt(msg.requestId)
        writeString(msg.draftId)
    }.toByteArray()

    fun decodeDraftCreated(bytes: ByteArray): DraftCreated {
        checkCap(bytes, IpcOpcode.DRAFT_CREATED)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.DRAFT_CREATED) throw IpcFormatException("expected DRAFT_CREATED, got $op")
        try {
            return DraftCreated(
                protocolVersion = r.readVarInt(),
                requestId = r.readVarInt(),
                draftId = r.readString(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid DRAFT_CREATED: ${e.message}")
        }
    }

    fun encodeOpenUi(msg: OpenUi): ByteArray {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.OPEN_UI)
            writeVarInt(msg.protocolVersion)
            writeByte(msg.surface)
            writeString(msg.contextId)
        }.toByteArray()
        require(bytes.size <= IpcCaps.OPEN_UI) {
            "encoded OPEN_UI is ${bytes.size}B (cap ${IpcCaps.OPEN_UI}B) — contextIds must be ASCII ids"
        }
        return bytes
    }

    fun decodeOpenUi(bytes: ByteArray): OpenUi {
        checkCap(bytes, IpcOpcode.OPEN_UI)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.OPEN_UI) throw IpcFormatException("expected OPEN_UI, got $op")
        try {
            return OpenUi(
                protocolVersion = r.readVarInt(),
                surface = r.readByte(),
                contextId = r.readString(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid OPEN_UI: ${e.message}")
        }
    }
}
