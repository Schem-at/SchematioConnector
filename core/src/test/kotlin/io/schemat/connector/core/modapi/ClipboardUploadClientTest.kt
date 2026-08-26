package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.transport.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipboardUploadClientTest {

    private val bytes = ByteArray(16) { it.toByte() }

    private fun client(transport: FakeTransport, token: String? = "community-jwt") =
        ClipboardUploadClient(transport, { token })

    @Test
    fun `201 parses Created and posts the right multipart request`() = runTest {
        val transport = FakeTransport()
        transport.enqueue(
            201,
            """{"draft_id":"d-1","short_id":"s1","web_url":"https://schemat.io/schematics/upload/s1","expires_at":"2026-07-19T12:00:00+00:00"}""",
        )

        val outcome = client(transport).upload("player-uuid", bytes)

        val created = outcome as ClipboardUploadOutcome.Created
        assertEquals("d-1", created.draftId)
        assertEquals("https://schemat.io/schematics/upload/s1", created.webUrl)
        assertEquals("2026-07-19T12:00:00+00:00", created.expiresAt)

        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/plugin/clipboard/drafts", request.path)
        val multipart = request.multipart!!
        assertEquals(listOf("player_uuid" to "player-uuid"), multipart.fields)
        assertEquals("file", multipart.files.single().fieldName)
        assertEquals("clipboard.schem", multipart.files.single().fileName)
        assertTrue(multipart.files.single().bytes.contentEquals(bytes))
        // COMMUNITY token only — user tokens never reach this client (spec invariant 1).
        assertEquals("community-jwt", transport.lastToken())
    }

    @Test
    fun `error statuses map per the contract table`() = runTest {
        suspend fun outcomeFor(status: Int, body: String): ClipboardUploadOutcome {
            val transport = FakeTransport()
            transport.enqueue(status, body)
            return client(transport).upload("p", bytes)
        }

        assertEquals(ClipboardUploadOutcome.NotLinked, outcomeFor(400, """{"error":"player_not_linked"}"""))
        assertEquals(ClipboardUploadOutcome.Error, outcomeFor(400, """{"error":"something_else"}"""))
        assertEquals(ClipboardUploadOutcome.Denied, outcomeFor(403, """{"error":"community_token_required"}"""))
        assertEquals(ClipboardUploadOutcome.QuotaExceeded, outcomeFor(409, """{"error":"draft_quota_exceeded"}"""))
        assertEquals(ClipboardUploadOutcome.TooLarge, outcomeFor(413, """{"error":"too_large"}"""))
        assertEquals(ClipboardUploadOutcome.RateLimited, outcomeFor(429, "{}"))
        assertEquals(ClipboardUploadOutcome.Unavailable, outcomeFor(500, "oops"))
        assertEquals(ClipboardUploadOutcome.Error, outcomeFor(422, """{"message":"validation"}"""))
    }

    @Test
    fun `a 201 with a malformed body is Error, not a crash`() = runTest {
        val transport = FakeTransport()
        transport.enqueue(201, "not-json")
        assertEquals(ClipboardUploadOutcome.Error, client(transport).upload("p", bytes))
    }

    @Test
    fun `transport failure maps to Unavailable`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNetworkFailure()
        assertEquals(ClipboardUploadOutcome.Unavailable, client(transport).upload("p", bytes))
    }

    @Test
    fun `oversize bytes are rejected client-side without any transport call`() = runTest {
        val transport = FakeTransport()
        val oversize = ByteArray(ClipboardUploadClient.MAX_UPLOAD_BYTES + 1)
        assertEquals(ClipboardUploadOutcome.TooLarge, client(transport).upload("p", oversize))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `missing community token is Unavailable without any transport call`() = runTest {
        val transport = FakeTransport()
        assertEquals(ClipboardUploadOutcome.Unavailable, client(transport, token = null).upload("p", bytes))
        assertTrue(transport.requests.isEmpty())
    }
}
