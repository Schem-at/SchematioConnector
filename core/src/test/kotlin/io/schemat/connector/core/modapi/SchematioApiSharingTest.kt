package io.schemat.connector.core.modapi

import com.google.gson.JsonParser
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.validation.ValidationConstants
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Upload (multipart `POST /schematics`) and quick-share endpoints of [SchematioApi]
 * against the contract in docs/api/mod-namespace.md (schemati repo). Field names mirror
 * `SchematicStoreRequest::rules()` (name, description, author_id, format, is_public,
 * tags[], co_authors[], community_id, schematic_file, preview_image) and
 * `QuickShareController::store()` validation (schematic_data, name, format, password,
 * limit_type, max_uses, expires_in).
 */
class SchematioApiSharingTest {

    private val transport = FakeTransport()
    private var token: String? = "tok"

    private fun api(reauthenticate: suspend () -> Boolean = { false }) =
        SchematioApi(transport, { token }, reauthenticate)

    private val schematicBytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3, 4)
    private val previewBytes = byteArrayOf(0x50, 0x4e, 0x47, 9)

    private fun uploadRequest(
        tagIds: List<String> = emptyList(),
        coAuthorIds: List<String> = emptyList(),
        communityId: String? = null,
        isPublic: Boolean = true,
        bytes: ByteArray = schematicBytes,
    ) = UploadRequest(
        name = "Castle",
        description = "A castle",
        authorId = "11111111-2222-3333-4444-555555555555",
        schematicBytes = bytes,
        schematicFileName = "build.litematic",
        previewImagePng = previewBytes,
        isPublic = isPublic,
        tagIds = tagIds,
        coAuthorIds = coAuthorIds,
        communityId = communityId,
    )

    private val uploadResponseBody = """
        {
          "data": {
            "id": "s-1", "short_id": "abc123", "name": "Castle", "description": "A castle",
            "slug": "castle", "format": "litematic", "is_public": true,
            "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z",
            "authors": [{ "uuid": "11111111-2222-3333-4444-555555555555", "last_seen_name": "Steve", "head_url": "https://x/head.png" }],
            "tags": [{ "name": "medieval", "color": "#aabbcc", "text_color": "#ffffff" }],
            "preview_image_url": "https://x/p.png", "preview_video_url": null, "download_link": "https://x/dl"
          }
        }
    """.trimIndent()

    private val quickShareCreatedBody = """
        {
          "message": "Quick share created successfully.",
          "quick_share": {
            "id": 1, "access_code": "QS-CODE", "name": "Castle", "description": null,
            "format": "litematic", "web_url": "https://x/qs/QS-CODE",
            "api_url": "https://x/api/v1/plugin/quick-shares/QS-CODE/download",
            "expires_at": "2026-06-11T00:00:00Z", "has_password": true, "has_whitelist": false,
            "limit_type": "total", "max_uses": 5,
            "created_by_player": { "uuid": "11111111-2222-3333-4444-555555555555", "last_seen_name": "Steve", "head_url": "https://x/head.png" }
          }
        }
    """.trimIndent()

    private fun bodyJson() = JsonParser.parseString(transport.lastRequest().jsonBody!!).asJsonObject

    // ---- uploadSchematic ----

    @Test
    fun `uploadSchematic sends multipart with all contract fields and parses the data envelope`() = runTest {
        transport.enqueue(201, uploadResponseBody)
        val result = api().uploadSchematic(
            uploadRequest(
                tagIds = listOf("t-1", "t-2"),
                coAuthorIds = listOf("c-1", "c-2"),
                communityId = "com-1",
            ),
        )
        val detail = assertNotNull(result.valueOrNull())
        assertEquals("s-1", detail.id)
        assertEquals("Castle", detail.name)

        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/schematics", request.path)
        assertNull(request.jsonBody)
        val multipart = assertNotNull(request.multipart)
        assertEquals(
            listOf(
                "name" to "Castle",
                "description" to "A castle",
                "author_id" to "11111111-2222-3333-4444-555555555555",
                "format" to "litematic",
                "is_public" to "1",
                "tags[]" to "t-1",
                "tags[]" to "t-2",
                "co_authors[]" to "c-1",
                "co_authors[]" to "c-2",
                "community_id" to "com-1",
            ),
            multipart.fields,
        )
        assertEquals(2, multipart.files.size)
        val schematicFile = multipart.files[0]
        assertEquals("schematic_file", schematicFile.fieldName)
        assertEquals("build.litematic", schematicFile.fileName)
        assertEquals("application/octet-stream", schematicFile.contentType)
        assertContentEquals(schematicBytes, schematicFile.bytes)
        val previewFile = multipart.files[1]
        assertEquals("preview_image", previewFile.fieldName)
        assertEquals("preview.png", previewFile.fileName)
        assertEquals("image/png", previewFile.contentType)
        assertContentEquals(previewBytes, previewFile.bytes)
    }

    @Test
    fun `uploadSchematic sends tag_filters as repeated bracketed multipart fields`() = runTest {
        // PHP reassembles repeated tag_filters[<id>]=value keys into the assoc array
        // the backend's `tag_filters` validation reads - assert the exact pair list.
        transport.enqueue(201, uploadResponseBody)
        api().uploadSchematic(
            uploadRequest(tagIds = listOf("t-1")).copy(tagFilters = mapOf(7L to "1", 3L to "12")),
        )
        val fields = assertNotNull(transport.lastRequest().multipart).fields
        assertEquals(
            listOf(
                "name" to "Castle",
                "description" to "A castle",
                "author_id" to "11111111-2222-3333-4444-555555555555",
                "format" to "litematic",
                "is_public" to "1",
                "tags[]" to "t-1",
                "tag_filters[3]" to "12",
                "tag_filters[7]" to "1",
            ),
            fields,
        )
    }

    @Test
    fun `uploadSchematic omits tag_filters fields when the map is empty`() = runTest {
        transport.enqueue(201, uploadResponseBody)
        api().uploadSchematic(uploadRequest(tagIds = listOf("t-1")))
        val fields = assertNotNull(transport.lastRequest().multipart).fields
        assertTrue(fields.none { it.first.startsWith("tag_filters") })
    }

    @Test
    fun `uploadSchematic omits empty arrays and null community and sends is_public 0`() = runTest {
        transport.enqueue(201, uploadResponseBody)
        api().uploadSchematic(uploadRequest(isPublic = false))
        val fields = assertNotNull(transport.lastRequest().multipart).fields
        assertEquals(
            listOf(
                "name" to "Castle",
                "description" to "A castle",
                "author_id" to "11111111-2222-3333-4444-555555555555",
                "format" to "litematic",
                "is_public" to "0",
            ),
            fields,
        )
    }

    @Test
    fun `uploadSchematic rejects oversize schematics before any network call`() = runTest {
        val oversize = ByteArray(ValidationConstants.MAX_SCHEMATIC_SIZE_BYTES + 1)
        val error = api().uploadSchematic(uploadRequest(bytes = oversize)).errorOrNull()
        val validation = assertIs<ApiError.Validation>(error)
        assertTrue(validation.fieldErrors.containsKey("schematic_file"))
        assertTrue(transport.requests.isEmpty(), "no request must be sent for an oversize payload")
    }

    @Test
    fun `uploadSchematic rejects oversize preview images before any network call`() = runTest {
        val oversizePreview = ByteArray(SchematioApi.MAX_PREVIEW_IMAGE_BYTES + 1)
        val error = api().uploadSchematic(uploadRequest().copy(previewImagePng = oversizePreview)).errorOrNull()
        val validation = assertIs<ApiError.Validation>(error)
        assertTrue(validation.fieldErrors.containsKey("preview_image"))
        assertTrue(transport.requests.isEmpty(), "no request must be sent for an oversize preview image")
    }

    @Test
    fun `uploadSchematic rejects more than 10 co-authors before any network call`() = runTest {
        val tooMany = (1..SchematioApi.MAX_CO_AUTHORS + 1).map { "c-$it" }
        val error = api().uploadSchematic(uploadRequest(coAuthorIds = tooMany)).errorOrNull()
        val validation = assertIs<ApiError.Validation>(error)
        assertTrue(validation.fieldErrors.containsKey("co_authors"))
        assertTrue(transport.requests.isEmpty(), "no request must be sent when the co-author cap is exceeded")
    }

    @Test
    fun `uploadSchematic maps 403 author mismatch`() = runTest {
        transport.enqueue(403, """{ "error": "author_id must match the authenticated player" }""")
        val error = api().uploadSchematic(uploadRequest()).errorOrNull()
        val forbidden = assertIs<ApiError.Forbidden>(error)
        assertEquals("author_id must match the authenticated player", forbidden.message)
    }

    @Test
    fun `uploadSchematic maps 422 validation errors envelope`() = runTest {
        transport.enqueue(422, """{ "errors": { "preview_image": ["The preview image field is required."] } }""")
        val error = api().uploadSchematic(uploadRequest()).errorOrNull()
        val validation = assertIs<ApiError.Validation>(error)
        assertEquals(listOf("The preview image field is required."), validation.fieldErrors["preview_image"])
    }

    // ---- createQuickShare ----

    @Test
    fun `createQuickShare posts base64 schematic_data with contract field names`() = runTest {
        transport.enqueue(201, quickShareCreatedBody)
        val result = api().createQuickShare(
            QuickShareRequest(
                schematicBytes = schematicBytes,
                name = "Castle",
                expiresInSeconds = 3600,
                password = "hunter2",
                maxUses = 5,
            ),
        )
        val share = assertNotNull(result.valueOrNull())
        assertEquals("QS-CODE", share.accessCode)
        assertEquals(5, share.maxUses)
        assertEquals("total", share.limitType)
        assertTrue(share.hasPassword)

        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/mod/quick-shares", request.path)
        val body = bodyJson()
        assertContentEquals(schematicBytes, Base64.getDecoder().decode(body.get("schematic_data").asString))
        assertEquals("Castle", body.get("name").asString)
        assertEquals("litematic", body.get("format").asString)
        assertEquals(3600, body.get("expires_in").asInt)
        assertEquals("hunter2", body.get("password").asString)
        assertEquals("total", body.get("limit_type").asString)
        assertEquals(5, body.get("max_uses").asInt)
    }

    @Test
    fun `createQuickShare omits password max_uses and limit_type when unset`() = runTest {
        transport.enqueue(201, quickShareCreatedBody)
        api().createQuickShare(QuickShareRequest(schematicBytes = schematicBytes, name = "Castle"))
        val body = bodyJson()
        assertEquals(86_400, body.get("expires_in").asInt)
        assertFalse(body.has("password"))
        assertFalse(body.has("max_uses"))
        assertFalse(body.has("limit_type"))
    }

    @Test
    fun `createQuickShare rejects oversize schematics before any network call`() = runTest {
        val oversize = ByteArray(ValidationConstants.MAX_SCHEMATIC_SIZE_BYTES + 1)
        val error = api().createQuickShare(QuickShareRequest(schematicBytes = oversize, name = "big")).errorOrNull()
        val validation = assertIs<ApiError.Validation>(error)
        assertTrue(validation.fieldErrors.containsKey("schematic_data"))
        assertTrue(transport.requests.isEmpty(), "no request must be sent for an oversize payload")
    }

    // ---- quickShares (list) ----

    @Test
    fun `quickShares parses the quick_shares list`() = runTest {
        transport.enqueue(
            200,
            """
            {
              "quick_shares": [
                { "id": 1, "access_code": "QS-A", "name": "Castle", "format": "schem",
                  "expires_at": "2026-06-11T00:00:00Z", "is_active": true, "current_uses": 2, "has_data": true },
                { "id": 2, "access_code": "QS-B", "name": null, "format": "litematic",
                  "expires_at": null, "is_active": false, "current_uses": 0, "has_data": false }
              ],
              "count": 2
            }
            """.trimIndent(),
        )
        val shares = assertNotNull(api().quickShares().valueOrNull())
        assertEquals(2, shares.size)
        assertEquals("QS-A", shares[0].accessCode)
        assertEquals(2, shares[0].currentUses)
        assertTrue(shares[0].isActive)
        assertFalse(shares[1].isActive)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/quick-shares", request.path)
    }

    @Test
    fun `quickShares maps 500 to Server`() = runTest {
        transport.enqueue(500, """{ "message": "Server Error" }""")
        assertIs<ApiError.Server>(api().quickShares().errorOrNull())
    }

    // ---- quickShare (show) ----

    @Test
    fun `quickShare fetches one share by encoded access code`() = runTest {
        transport.enqueue(200, quickShareCreatedBody)
        val share = assertNotNull(api().quickShare("QS CODE/x").valueOrNull())
        assertEquals("QS-CODE", share.accessCode)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/quick-shares/QS%20CODE%2Fx", request.path)
    }

    @Test
    fun `quickShare maps 404 not_found`() = runTest {
        transport.enqueue(404, """{ "error": "not_found", "message": "Quick share not found." }""")
        val error = assertIs<ApiError.NotFound>(api().quickShare("nope").errorOrNull())
        assertEquals("Quick share not found.", error.message)
    }

    // ---- revokeQuickShare ----

    @Test
    fun `revokeQuickShare sends DELETE and succeeds on 200`() = runTest {
        transport.enqueue(200, """{ "message": "Quick share revoked successfully." }""")
        assertIs<ApiResult.Success<Unit>>(api().revokeQuickShare("QS-CODE"))
        val request = transport.lastRequest()
        assertEquals(HttpMethod.DELETE, request.method)
        assertEquals("/mod/quick-shares/QS-CODE", request.path)
    }

    @Test
    fun `revokeQuickShare maps 403 unauthorized`() = runTest {
        transport.enqueue(403, """{ "error": "unauthorized", "message": "You cannot manage this quick share." }""")
        assertIs<ApiError.Forbidden>(api().revokeQuickShare("QS-CODE").errorOrNull())
    }
}
