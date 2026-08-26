package io.schemat.connector.core.modapi

import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.ResponseTooLargeException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipboardResolveClientTest {

    private val payload = ByteArray(5) { (it + 1).toByte() }
    private val okHeaders = mapOf("Content-Length" to "5", "X-Schematio-Format" to "litematic")

    private fun client(transport: FakeTransport, token: String? = "jwt") =
        ClipboardResolveClient(transport, { token })

    @Test
    fun `posts the reference and parses bytes + format header`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, okHeaders)

        val outcome = client(transport).resolve(
            "11111111-2222-3333-4444-555555555555",
            LoadRefType.SCHEMATIC,
            "my-schem",
            "v-1",
        )

        outcome as ClipboardResolveOutcome.Bytes
        assertTrue(outcome.bytes.contentEquals(payload))
        assertEquals("litematic", outcome.format)

        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/plugin/clipboard/resolve", request.path)
        assertEquals("jwt", transport.lastToken())
        assertEquals(
            """{"player_uuid":"11111111-2222-3333-4444-555555555555","ref_type":"schematic","ref_id":"my-schem","version_id":"v-1"}""",
            request.jsonBody,
        )
    }

    @Test
    fun `share refs post ref_type share and omit empty version_id`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, okHeaders)

        client(transport).resolve("uuid", LoadRefType.SHARE_TOKEN, "qs_abc", "")

        assertEquals(
            """{"player_uuid":"uuid","ref_type":"share","ref_id":"qs_abc"}""",
            transport.lastRequest().jsonBody,
        )
    }

    @Test
    fun `format falls back to schem and headers are case-insensitive`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, mapOf("content-length" to "5"))

        val outcome = client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", "")

        outcome as ClipboardResolveOutcome.Bytes
        assertEquals("schem", outcome.format)
    }

    @Test
    fun `missing Content-Length is a protocol violation (Error)`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, mapOf("X-Schematio-Format" to "schem"))

        assertEquals(
            ClipboardResolveOutcome.Error,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
    }

    @Test
    fun `oversize Content-Length or body maps to TooLarge`() = runTest {
        val transport = FakeTransport()
        // Header over cap (body small): rejected on the header alone.
        transport.enqueueBinary(200, payload, mapOf("Content-Length" to "9000000"))
        assertEquals(
            ClipboardResolveOutcome.TooLarge,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )

        // Lying (small) Content-Length with an over-cap body: rejected on the counted bytes.
        val fat = ByteArray(ClipboardResolveClient.MAX_SCHEMATIC_BYTES + 1)
        transport.enqueueBinary(200, fat, mapOf("Content-Length" to "5"))
        assertEquals(
            ClipboardResolveOutcome.TooLarge,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )

        // Transport-level hard cap (ResponseTooLargeException while streaming).
        transport.enqueueFailure(ResponseTooLargeException("too big"))
        assertEquals(
            ClipboardResolveOutcome.TooLarge,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
    }

    @Test
    fun `status codes map per the contract`() = runTest {
        val transport = FakeTransport()
        val client = client(transport)
        val cases = listOf(
            401 to ClipboardResolveOutcome.Denied,
            403 to ClipboardResolveOutcome.Denied,
            404 to ClipboardResolveOutcome.NotFound,
            413 to ClipboardResolveOutcome.TooLarge,
            429 to ClipboardResolveOutcome.RateLimited,
            500 to ClipboardResolveOutcome.Unavailable,
            503 to ClipboardResolveOutcome.Unavailable,
            402 to ClipboardResolveOutcome.Error,
        )
        for ((status, expected) in cases) {
            transport.enqueue(status, """{"error":"x"}""")
            assertEquals(expected, client.resolve("u", LoadRefType.SCHEMATIC, "r", ""), "status $status")
        }
    }

    @Test
    fun `no token or network failure maps to Unavailable`() = runTest {
        val silent = FakeTransport()
        assertEquals(
            ClipboardResolveOutcome.Unavailable,
            client(silent, token = null).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
        assertEquals(0, silent.requests.size) // no token -> no request at all

        val failing = FakeTransport()
        failing.enqueueNetworkFailure()
        assertEquals(
            ClipboardResolveOutcome.Unavailable,
            client(failing).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
    }
}
