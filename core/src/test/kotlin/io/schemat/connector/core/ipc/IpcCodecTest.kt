package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class IpcCodecTest {

    @Test
    fun `hello server v2 round-trips with identity fields`() {
        val msg = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = "1.3.0",
            capabilities = Capabilities.DOWNLOAD_CMD or Capabilities.LOAD_CLIPBOARD,
            platform = IpcPlatform.PAPER_PLUGIN,
            serverSoftware = "Paper 1.21.8",
            mcVersion = "1.21.8",
            backendHost = "https://schemat.io",
            communityId = "11111111-2222-3333-4444-555555555555",
            communitySlug = "build-team",
        )
        val bytes = IpcCodec.encodeHelloServer(msg)
        assertEquals(IpcOpcode.HELLO_SERVER, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloServer(bytes))
    }

    @Test
    fun `hello server v1 decodes with v2 defaults (legacy peer)`() {
        // Hand-built v1 frame: exactly what a 1.3.0 peer puts on the wire.
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.HELLO_SERVER)
            writeVarInt(1)
            writeString("1.2.4")
            writeVarInt(Capabilities.DOWNLOAD_CMD)
        }.toByteArray()
        val decoded = IpcCodec.decodeHelloServer(bytes)
        assertEquals(1, decoded.protocolVersion)
        assertEquals("1.2.4", decoded.pluginVersion)
        assertEquals(null, decoded.platform)
        assertEquals("", decoded.communityId)
    }

    @Test
    fun `hello server v2 frame is v1-readable (trailing bytes ignored)`() {
        val bytes = IpcCodec.encodeHelloServer(
            HelloServer(2, "1.4.0", 1, IpcPlatform.FABRIC_SERVER, "Fabric", "1.21.11", "https://x", "c", "s"),
        )
        // Simulate a v1 decoder: read only the v1 fields and stop.
        val r = IpcReader(bytes)
        assertEquals(IpcOpcode.HELLO_SERVER, r.readByte())
        assertEquals(2, r.readVarInt())
        assertEquals("1.4.0", r.readString())
        assertEquals(1, r.readVarInt())
        // v1 peers simply never read the remainder — must not have thrown by here.
    }

    @Test
    fun `unknown platform wire value decodes as null (forward compat)`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.HELLO_SERVER)
            writeVarInt(2)
            writeString("9.9.9")
            writeVarInt(0)
            writeVarInt(9) // platform from the future
            writeString(""); writeString(""); writeString(""); writeString(""); writeString("")
        }.toByteArray()
        assertEquals(null, IpcCodec.decodeHelloServer(bytes).platform)
    }

    @Test
    fun `hello client v2 round-trips with nonce`() {
        val nonce = ByteArray(16) { it.toByte() }
        val msg = HelloClient(IpcProtocol.VERSION, "1.4.0", 0, nonce)
        val bytes = IpcCodec.encodeHelloClient(msg)
        assertEquals(IpcOpcode.HELLO_CLIENT, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloClient(bytes))
    }

    @Test
    fun `hello client v1 decodes with empty nonce (server skips attestation)`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.HELLO_CLIENT)
            writeVarInt(1)
            writeString("1.2.4")
            writeVarInt(0)
        }.toByteArray()
        val decoded = IpcCodec.decodeHelloClient(bytes)
        assertEquals(1, decoded.protocolVersion)
        assertEquals(0, decoded.nonce.size)
    }

    @Test
    fun `attest round-trips`() {
        val msg = Attest(
            protocolVersion = IpcProtocol.VERSION,
            payloadJson = """{"communityId":"c","issuedAt":1,"nonce":"00","platform":"PAPER_PLUGIN","tokenId":"t"}""",
            signature = ByteArray(64) { (it + 1).toByte() },
            keyId = "k1",
        )
        val bytes = IpcCodec.encodeAttest(msg)
        assertEquals(IpcOpcode.ATTEST, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeAttest(bytes))
    }

    @Test
    fun `decoding attest with wrong opcode throws`() {
        val bytes = IpcCodec.encodeHelloClient(HelloClient(1, "x", 0))
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeAttest(bytes) }
    }

    @Test
    fun `opcode 3 is dead-reserved with no decoder or cap`() {
        assertEquals(3, IpcOpcode.LOAD_CLIPBOARD)
        assertEquals(null, IpcCaps.forOpcode(IpcOpcode.LOAD_CLIPBOARD))
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

    @Test
    fun `load request round-trips`() {
        val msg = LoadRequest(
            protocolVersion = IpcProtocol.VERSION,
            requestId = 42,
            refType = LoadRefType.SCHEMATIC.wire,
            refId = "11111111-2222-3333-4444-555555555555",
            versionId = "66666666-7777-8888-9999-aaaaaaaaaaaa",
        )
        val bytes = IpcCodec.encodeLoadRequest(msg)
        assertEquals(IpcOpcode.LOAD_REQUEST, IpcCodec.peekOpcode(bytes))
        assertTrue(bytes.size <= IpcCaps.LOAD_REQUEST)
        assertEquals(msg, IpcCodec.decodeLoadRequest(bytes))
    }

    @Test
    fun `load request with empty versionId round-trips (default branch head)`() {
        val msg = LoadRequest(IpcProtocol.VERSION, 1, LoadRefType.SHARE_TOKEN.wire, "qs_abc123", "")
        assertEquals(msg, IpcCodec.decodeLoadRequest(IpcCodec.encodeLoadRequest(msg)))
    }

    @Test
    fun `load request rejects bad fields at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, -1, 0, "x") // negative requestId
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 7, "x") // unknown refType
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 0, "") // empty refId
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 0, "a".repeat(65)) // refId over 64 chars
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 0, "x", "a".repeat(65)) // versionId over 64 chars
        }
    }

    @Test
    fun `decoding a hand-built load request with a bad refType throws IpcFormatException`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.LOAD_REQUEST)
            writeVarInt(2)
            writeVarInt(1)
            writeByte(9) // refType from the future
            writeString("ref")
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeLoadRequest(bytes) }
    }

    @Test
    fun `status round-trips including empty detail`() {
        val msg = Status(IpcProtocol.VERSION, 42, StatusState.DENIED.wire, "no access")
        val bytes = IpcCodec.encodeStatus(msg)
        assertEquals(IpcOpcode.STATUS, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeStatus(bytes))

        val bare = Status(IpcProtocol.VERSION, 7, StatusState.OK.wire, "")
        assertEquals(bare, IpcCodec.decodeStatus(IpcCodec.encodeStatus(bare)))
    }

    @Test
    fun `status rejects unknown states and oversize detail at construction`() {
        assertThrows(IllegalArgumentException::class.java) { Status(2, 1, 99, "") }
        assertThrows(IllegalArgumentException::class.java) { Status(2, 1, 0, "a".repeat(257)) }
    }

    @Test
    fun `encodeStatus drops the detail rather than exceed the 512-byte cap`() {
        // 200 x '✓' = 200 chars (legal) but 600 UTF-8 bytes — over the wire cap.
        val fat = Status(IpcProtocol.VERSION, 1, StatusState.ERROR.wire, "✓".repeat(200))
        val bytes = IpcCodec.encodeStatus(fat)
        assertTrue(bytes.size <= IpcCaps.STATUS)
        assertEquals("", IpcCodec.decodeStatus(bytes).detail)
    }

    @Test
    fun `open ui round-trips for every surface`() {
        for (surface in OpenUiSurface.entries) {
            val msg = OpenUi(
                protocolVersion = IpcProtocol.VERSION,
                surface = surface.wire,
                contextId = if (surface == OpenUiSurface.SCHEMATIC_DETAIL) "11111111-2222-3333-4444-555555555555" else "",
            )
            val bytes = IpcCodec.encodeOpenUi(msg)
            assertEquals(IpcOpcode.OPEN_UI, IpcCodec.peekOpcode(bytes))
            assertTrue(bytes.size <= IpcCaps.OPEN_UI)
            assertEquals(msg, IpcCodec.decodeOpenUi(bytes))
        }
    }

    @Test
    fun `open ui rejects bad fields at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenUi(2, 9) // unknown surface
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenUi(2, OpenUiSurface.SCHEMATIC_DETAIL.wire, "a".repeat(65)) // contextId over 64 chars
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenUi(2, OpenUiSurface.BROWSE.wire, "some-id") // contextId only valid for SCHEMATIC_DETAIL
        }
    }

    @Test
    fun `decoding a hand-built open ui with an unknown surface throws IpcFormatException`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.OPEN_UI)
            writeVarInt(2)
            writeByte(9) // surface from the future
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeOpenUi(bytes) }
    }

    @Test
    fun `decoding open ui with wrong opcode throws`() {
        val bytes = IpcCodec.encodeStatus(Status(2, 1, StatusState.OK.wire, ""))
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeOpenUi(bytes) }
    }

    @Test
    fun `oversize open ui payloads are rejected before parsing`() {
        val fat = ByteArray(IpcCaps.OPEN_UI + 1).also { it[0] = IpcOpcode.OPEN_UI.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeOpenUi(fat) }
    }

    @Test
    fun `oversize payloads are rejected before parsing`() {
        val fatLoad = ByteArray(IpcCaps.LOAD_REQUEST + 1).also { it[0] = IpcOpcode.LOAD_REQUEST.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeLoadRequest(fatLoad) }

        val fatStatus = ByteArray(IpcCaps.STATUS + 1).also { it[0] = IpcOpcode.STATUS.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeStatus(fatStatus) }

        val fatHello = ByteArray(IpcCaps.HELLO_SERVER + 1).also { it[0] = IpcOpcode.HELLO_SERVER.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeHelloServer(fatHello) }
    }

    @Test
    fun `every live opcode has a size cap`() {
        for (opcode in intArrayOf(
            IpcOpcode.HELLO_SERVER, IpcOpcode.HELLO_CLIENT, IpcOpcode.ATTEST,
            IpcOpcode.LOAD_REQUEST, IpcOpcode.STATUS, IpcOpcode.OPEN_UI,
        )) {
            assertNotNull(IpcCaps.forOpcode(opcode), "opcode $opcode has no cap")
        }
    }

    @Test
    fun `strings must be valid utf-8`() {
        // A LOAD_REQUEST whose refId bytes are an invalid UTF-8 sequence.
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.LOAD_REQUEST)
            writeVarInt(2)
            writeVarInt(1)
            writeByte(0)
            writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) // varint len 2 + invalid continuation
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeLoadRequest(bytes) }
    }

    @Test
    fun `upload clipboard round-trips within its cap`() {
        val msg = UploadClipboard(IpcProtocol.VERSION, 42)
        val bytes = IpcCodec.encodeUploadClipboard(msg)
        assertEquals(IpcOpcode.UPLOAD_CLIPBOARD, IpcCodec.peekOpcode(bytes))
        assertTrue(bytes.size <= IpcCaps.UPLOAD_CLIPBOARD)
        assertEquals(msg, IpcCodec.decodeUploadClipboard(bytes))
    }

    @Test
    fun `upload clipboard rejects a negative requestId at construction`() {
        assertThrows(IllegalArgumentException::class.java) { UploadClipboard(2, -1) }
    }

    @Test
    fun `over-cap upload clipboard payload throws before parsing`() {
        val padded = IpcCodec.encodeUploadClipboard(UploadClipboard(2, 1)) +
            ByteArray(IpcCaps.UPLOAD_CLIPBOARD)
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeUploadClipboard(padded) }
    }

    @Test
    fun `draft created round-trips within its cap`() {
        val msg = DraftCreated(IpcProtocol.VERSION, 7, "11111111-2222-3333-4444-555555555555")
        val bytes = IpcCodec.encodeDraftCreated(msg)
        assertEquals(IpcOpcode.DRAFT_CREATED, IpcCodec.peekOpcode(bytes))
        assertTrue(bytes.size <= IpcCaps.DRAFT_CREATED)
        assertEquals(msg, IpcCodec.decodeDraftCreated(bytes))
    }

    @Test
    fun `draft created rejects bad fields at construction`() {
        assertThrows(IllegalArgumentException::class.java) { DraftCreated(2, -1, "x") }
        assertThrows(IllegalArgumentException::class.java) { DraftCreated(2, 1, "") }
        assertThrows(IllegalArgumentException::class.java) { DraftCreated(2, 1, "a".repeat(65)) }
    }

    @Test
    fun `decoding a hand-built draft created with an empty draftId throws IpcFormatException`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.DRAFT_CREATED)
            writeVarInt(2)
            writeVarInt(1)
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeDraftCreated(bytes) }
    }

    @Test
    fun `caps table covers the new opcodes`() {
        assertEquals(IpcCaps.UPLOAD_CLIPBOARD, IpcCaps.forOpcode(IpcOpcode.UPLOAD_CLIPBOARD))
        assertEquals(IpcCaps.DRAFT_CREATED, IpcCaps.forOpcode(IpcOpcode.DRAFT_CREATED))
    }
}
