package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.dto.Page
import io.schemat.connector.core.modapi.transport.HttpMethod
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [VersionApi] against the plugin version endpoints from the in-game diff viewer spec
 * (docs/superpowers/specs/2026-07-10-ingame-diff-viewer-design.md §1): branches,
 * commits listing, version download, and the commit POST with its 409 head_moved
 * contract `{error:"head_moved", head: <version resource>}`.
 */
class VersionApiTest {

    private val transport = FakeTransport()
    private val api = VersionApi(transport) { "community-jwt" }

    private val versionPayload = """
        {
          "id": "v-2", "branch_id": "b-1", "message": "add roof",
          "author": { "uuid": "11111111-2222-3333-4444-555555555555", "last_seen_name": "Steve" },
          "created_at": "2026-07-10T00:00:00Z"
        }
    """.trimIndent()

    // ---- branches ----

    @Test
    fun `branches hits the plugin endpoint with the community token`() = runTest {
        transport.enqueue(200, """{ "data": [ { "id": "b-1", "name": "main", "head_version_id": "v-2", "is_default": true } ] }""")
        val result = api.branches("s-1")

        assertEquals(HttpMethod.GET, transport.lastRequest().method)
        assertEquals("/plugin/schematics/s-1/branches", transport.lastRequest().path)
        assertEquals("community-jwt", transport.lastToken())

        val branches = assertIs<ApiResult.Success<List<BranchInfo>>>(result).value
        assertEquals(listOf(BranchInfo("b-1", "main", "v-2", true)), branches)
    }

    @Test
    fun `branches maps 404 to NotFound`() = runTest {
        transport.enqueue(404, """{ "message": "Schematic is not versioned" }""")
        val result = api.branches("s-1")
        assertIs<ApiError.NotFound>(assertIs<ApiResult.Failure>(result).error)
    }

    @Test
    fun `branches maps network failure to Offline`() = runTest {
        transport.enqueueNetworkFailure()
        val result = api.branches("s-1")
        assertIs<ApiError.Offline>(assertIs<ApiResult.Failure>(result).error)
    }

    // ---- commits listing ----

    @Test
    fun `commits parses a paginated version listing`() = runTest {
        transport.enqueue(
            200,
            """
            {
              "data": [ $versionPayload ],
              "meta": { "current_page": 1, "last_page": 2, "per_page": 20, "total": 21 }
            }
            """.trimIndent(),
        )
        val result = api.commits("s-1", "b-1", page = 1)

        assertEquals("/plugin/schematics/s-1/branches/b-1/commits", transport.lastRequest().path)
        assertEquals("1", transport.lastRequest().query["page"])

        val page = assertIs<ApiResult.Success<Page<VersionInfo>>>(result).value
        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
        val version = page.items.single()
        assertEquals("v-2", version.id)
        assertEquals("b-1", version.branchId)
        assertEquals("add roof", version.message)
        assertEquals("Steve", version.authorName)
    }

    // ---- version download ----

    @Test
    fun `downloadVersion returns the raw bytes`() = runTest {
        val bytes = byteArrayOf(0x1f, 0x08, 42, -1)
        transport.enqueueBinary(200, bytes)
        val result = api.downloadVersion("s-1", "v-2")

        assertEquals(HttpMethod.GET, transport.lastRequest().method)
        assertEquals("/plugin/schematics/s-1/versions/v-2/download", transport.lastRequest().path)
        assertContentEquals(bytes, assertIs<ApiResult.Success<ByteArray>>(result).value)
    }

    // ---- commit ----

    private suspend fun commit(expectedHead: String? = "v-2") = api.commit(
        schematicId = "s-1",
        branchId = "b-1",
        schematicBytes = byteArrayOf(1, 2, 3),
        message = "my change",
        expectedHeadVersionId = expectedHead,
        playerUuid = "11111111-2222-3333-4444-555555555555",
    )

    @Test
    fun `commit posts multipart with message, expected head and player uuid`() = runTest {
        transport.enqueue(201, """{ "data": $versionPayload }""")
        commit()

        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/plugin/schematics/s-1/branches/b-1/commits", request.path)
        val multipart = request.multipart!!
        val fields = multipart.fields.toMap()
        assertEquals("my change", fields["message"])
        assertEquals("v-2", fields["expected_head_version_id"])
        assertEquals("11111111-2222-3333-4444-555555555555", fields["player_uuid"])
        val file = multipart.files.single()
        assertEquals("file", file.fieldName)
        assertContentEquals(byteArrayOf(1, 2, 3), file.bytes)
    }

    @Test
    fun `commit omits expected_head_version_id when unknown`() = runTest {
        transport.enqueue(201, """{ "data": $versionPayload }""")
        commit(expectedHead = null)
        val fields = transport.lastRequest().multipart!!.fields.toMap()
        assertNull(fields["expected_head_version_id"])
    }

    @Test
    fun `commit success returns Ok with the new version`() = runTest {
        transport.enqueue(201, """{ "data": $versionPayload }""")
        val result = assertIs<CommitResult.Ok>(commit())
        assertEquals("v-2", result.version.id)
    }

    @Test
    fun `commit success parses an unwrapped version resource too`() = runTest {
        transport.enqueue(201, versionPayload)
        val result = assertIs<CommitResult.Ok>(commit())
        assertEquals("v-2", result.version.id)
    }

    @Test
    fun `commit 409 head_moved returns HeadMoved with the current head`() = runTest {
        transport.enqueue(
            409,
            """{ "error": "head_moved", "head": { "id": "v-9", "branch_id": "b-1", "message": "someone else", "author": { "last_seen_name": "Alex" } } }""",
        )
        val result = assertIs<CommitResult.HeadMoved>(commit())
        assertEquals("v-9", result.newHead.id)
        assertEquals("Alex", result.newHead.authorName)
    }

    @Test
    fun `commit 409 without head_moved marker is a plain error`() = runTest {
        transport.enqueue(409, """{ "message": "Some other conflict" }""")
        val result = assertIs<CommitResult.Error>(commit())
        assertIs<ApiError.Conflict>(result.error)
    }

    @Test
    fun `commit maps 422 to Validation error`() = runTest {
        transport.enqueue(422, """{ "message": "Too big", "errors": { "file": ["Too big"] } }""")
        val result = assertIs<CommitResult.Error>(commit())
        assertIs<ApiError.Validation>(result.error)
    }

    @Test
    fun `commit maps network failure to Offline error`() = runTest {
        transport.enqueueNetworkFailure()
        val result = assertIs<CommitResult.Error>(commit())
        assertIs<ApiError.Offline>(result.error)
    }
}
