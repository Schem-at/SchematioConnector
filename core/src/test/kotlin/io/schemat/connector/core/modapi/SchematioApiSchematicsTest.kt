package io.schemat.connector.core.modapi

import com.google.gson.JsonParser
import io.schemat.connector.core.modapi.transport.HttpMethod
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
 * Schematic endpoints of [SchematioApi] against the contract in docs/api/mod-namespace.md
 * (schemati repo). Sample bodies mirror the doc's `SchematicResource` shape; the
 * `GET /schematics` index filter params mirror `SchematicController::index` +
 * `SchematicService::getPaginatedSchematics` (search/tag/sort/order/page/per_page,
 * `author` = caller uuid for "mine").
 */
class SchematioApiSchematicsTest {

    private val transport = FakeTransport()
    private var token: String? = "tok"

    private fun api(reauthenticate: suspend () -> Boolean = { false }) =
        SchematioApi(transport, { token }, reauthenticate)

    /** Unsigned-but-well-formed JWT whose `sub` is [sub] (signature is never verified client-side). */
    private fun unsignedJwt(sub: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"$sub"}""".toByteArray())
        return "$header.$payload."
    }

    private val schematicPayload = """
        {
          "id": "s-1", "short_id": "abc123", "name": "Castle", "description": "A castle",
          "slug": "castle", "format": "litematic", "is_public": true,
          "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-02T00:00:00Z",
          "authors": [{ "uuid": "11111111-2222-3333-4444-555555555555", "last_seen_name": "Steve", "head_url": "https://x/head.png" }],
          "tags": [{ "name": "medieval", "color": "#aabbcc", "text_color": "#ffffff" }],
          "preview_image_url": "https://x/p.png", "preview_video_url": null, "download_link": "https://x/dl"
        }
    """.trimIndent()

    private val pageBody = """
        {
          "data": [$schematicPayload],
          "links": {},
          "meta": { "current_page": 1, "last_page": 3, "per_page": 20, "total": 41 }
        }
    """.trimIndent()

    private fun bodyJson() = JsonParser.parseString(transport.lastRequest().jsonBody!!).asJsonObject

    // ---- BrowseQuery.toQueryMap ----

    @Test
    fun `toQueryMap joins tags with commas and keeps the legacy tag param`() {
        val query = BrowseQuery(tag = "t-legacy", tags = listOf("t-1", "t-2", "t-3")).toQueryMap()
        assertEquals("t-1,t-2,t-3", query["tags"])
        assertEquals("t-legacy", query["tag"])
    }

    @Test
    fun `toQueryMap omits tags when empty`() = runTest {
        assertFalse(BrowseQuery().toQueryMap().containsKey("tags"))
    }

    @Test
    fun `toQueryMap encodes exact constraints as bracketed filter keys`() {
        val query = BrowseQuery(
            filterConstraints = listOf(
                FilterConstraint.Exact(7, "1"),
                FilterConstraint.Exact(12, "zombie"),
            ),
        ).toQueryMap()
        assertEquals("1", query["filter[7]"])
        assertEquals("zombie", query["filter[12]"])
    }

    @Test
    fun `toQueryMap encodes range constraints as bracketed filter_min and filter_max keys`() {
        val query = BrowseQuery(
            filterConstraints = listOf(FilterConstraint.Range(3, min = 5.0, max = 17.5)),
        ).toQueryMap()
        assertEquals("5", query["filter_min[3]"], "whole-number bounds must not carry a trailing .0")
        assertEquals("17.5", query["filter_max[3]"])
    }

    @Test
    fun `toQueryMap omits null range bounds`() {
        val minOnly = BrowseQuery(filterConstraints = listOf(FilterConstraint.Range(3, min = 2.0, max = null))).toQueryMap()
        assertEquals("2", minOnly["filter_min[3]"])
        assertFalse(minOnly.containsKey("filter_max[3]"))

        val maxOnly = BrowseQuery(filterConstraints = listOf(FilterConstraint.Range(3, min = null, max = 9.0))).toQueryMap()
        assertFalse(maxOnly.containsKey("filter_min[3]"))
        assertEquals("9", maxOnly["filter_max[3]"])

        val neither = BrowseQuery(filterConstraints = listOf(FilterConstraint.Range(3, min = null, max = null))).toQueryMap()
        assertTrue(neither.keys.none { it.startsWith("filter") }, "a fully-null range must send nothing")
    }

    @Test
    fun `browse query with tags and constraints reaches the request query string`() = runTest {
        transport.enqueue(200, pageBody)
        val query = BrowseQuery(
            tags = listOf("t-1", "t-2"),
            filterConstraints = listOf(
                FilterConstraint.Exact(7, "1"),
                FilterConstraint.Range(3, min = 1.0, max = 20.0),
            ),
        )
        assertNotNull(api().communitySchematics("builders", query).valueOrNull())
        val sent = transport.lastRequest().query
        assertEquals("t-1,t-2", sent["tags"])
        assertEquals("1", sent["filter[7]"])
        assertEquals("1", sent["filter_min[3]"])
        assertEquals("20", sent["filter_max[3]"])
    }

    // ---- communitySchematics ----

    @Test
    fun `communitySchematics parses page and sends browse query`() = runTest {
        transport.enqueue(200, pageBody)
        val query = BrowseQuery(search = "cas", tag = "t-1", sort = "name", order = "asc", page = 2, perPage = 10)
        val page = assertNotNull(api().communitySchematics("builders", query).valueOrNull())
        assertEquals(1, page.items.size)
        assertEquals("Castle", page.items[0].name)
        assertEquals("Steve", page.items[0].authors[0].lastSeenName)
        assertEquals("medieval", page.items[0].tags[0].name)
        assertEquals(41, page.meta.total)
        assertTrue(page.hasMore)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/communities/builders/schematics", request.path)
        assertEquals(
            mapOf(
                "search" to "cas", "tag" to "t-1", "sort" to "name",
                "order" to "asc", "page" to "2", "per_page" to "10",
            ),
            request.query,
        )
    }

    @Test
    fun `communitySchematics omits null search and tag`() = runTest {
        transport.enqueue(200, pageBody)
        api().communitySchematics("builders")
        assertEquals(
            mapOf("sort" to "created_at", "order" to "desc", "page" to "1", "per_page" to "20"),
            transport.lastRequest().query,
        )
    }

    @Test
    fun `communitySchematics maps 404 for private community`() = runTest {
        transport.enqueue(404, """{ "message": "Not found" }""")
        assertIs<ApiError.NotFound>(api().communitySchematics("secret").errorOrNull())
    }

    // ---- schematics (general index) ----

    @Test
    fun `schematics parses page and sends index filter params`() = runTest {
        transport.enqueue(200, pageBody)
        val query = BrowseQuery(search = "castle", tag = "t-9", sort = "updated_at", order = "asc", page = 3, perPage = 50)
        val page = assertNotNull(api().schematics(query).valueOrNull())
        assertEquals("s-1", page.items[0].id)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/schematics", request.path)
        assertEquals(
            mapOf(
                "search" to "castle", "tag" to "t-9", "sort" to "updated_at",
                "order" to "asc", "page" to "3", "per_page" to "50",
            ),
            request.query,
        )
    }

    @Test
    fun `schematics mineOnly filters by the caller uuid from the JWT subject`() = runTest {
        token = unsignedJwt("11111111-2222-3333-4444-555555555555")
        transport.enqueue(200, pageBody)
        assertNotNull(api().schematics(mineOnly = true).valueOrNull())
        assertEquals("11111111-2222-3333-4444-555555555555", transport.lastRequest().query["author"])
    }

    @Test
    fun `schematics mineOnly without a usable session fails without a request`() = runTest {
        token = "not-a-jwt"
        val error = api().schematics(mineOnly = true).errorOrNull()
        assertIs<ApiError.Unauthorized>(error)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `schematics maps 500 to Server error`() = runTest {
        transport.enqueue(500, """{ "message": "Server Error" }""")
        assertIs<ApiError.Server>(api().schematics().errorOrNull())
    }

    // ---- schematic(id) ----

    @Test
    fun `schematic parses detail from data wrapper`() = runTest {
        transport.enqueue(200, """{ "data": $schematicPayload }""")
        val detail = assertNotNull(api().schematic("s-1").valueOrNull())
        assertEquals("s-1", detail.id)
        assertEquals("abc123", detail.shortId)
        assertEquals("A castle", detail.description)
        assertTrue(detail.isPublic)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/schematics/s-1", request.path)
    }

    @Test
    fun `schematic maps 404 for invisible schematic`() = runTest {
        transport.enqueue(404, """{ "error": "Schematic not found" }""")
        val error = api().schematic("nope").errorOrNull()
        assertIs<ApiError.NotFound>(error)
        assertEquals("Schematic not found", error.message)
    }

    // ---- updateSchematic ----

    @Test
    fun `updateSchematic sends only non-null fields and parses detail`() = runTest {
        transport.enqueue(200, """{ "data": $schematicPayload }""")
        val detail = assertNotNull(api().updateSchematic("s-1", name = "Castle v2", isPublic = false).valueOrNull())
        assertEquals("Castle", detail.name)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.PUT, request.method)
        assertEquals("/schematics/s-1", request.path)
        val body = bodyJson()
        assertEquals("Castle v2", body.get("name").asString)
        assertFalse(body.get("is_public").asBoolean)
        assertNull(body.get("description"))
    }

    @Test
    fun `updateSchematic maps 422 validation errors`() = runTest {
        transport.enqueue(
            422,
            """{ "errors": { "name": ["The name may not be longer than 255 characters"] } }""",
        )
        val error = api().updateSchematic("s-1", name = "x".repeat(300)).errorOrNull()
        assertIs<ApiError.Validation>(error)
        assertEquals(listOf("The name may not be longer than 255 characters"), error.fieldErrors["name"])
    }

    // ---- deleteSchematic ----

    @Test
    fun `deleteSchematic sends DELETE and accepts 204`() = runTest {
        transport.enqueue(204, "")
        assertNotNull(api().deleteSchematic("s-1").valueOrNull())
        val request = transport.lastRequest()
        assertEquals(HttpMethod.DELETE, request.method)
        assertEquals("/schematics/s-1", request.path)
    }

    @Test
    fun `deleteSchematic maps 404`() = runTest {
        transport.enqueue(404, """{ "error": "Schematic not found" }""")
        assertIs<ApiError.NotFound>(api().deleteSchematic("nope").errorOrNull())
    }

    // ---- setTags ----

    @Test
    fun `setTags sends full replacement and parses flat tag list`() = runTest {
        transport.enqueue(
            200,
            """{ "message": "Tags updated", "tags": [{ "id": "t-1", "name": "medieval" }] }""",
        )
        val tags = assertNotNull(api().setTags("s-1", listOf("t-1")).valueOrNull())
        assertEquals(1, tags.size)
        assertEquals("t-1", tags[0].id)
        assertEquals("medieval", tags[0].name)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.PUT, request.method)
        assertEquals("/schematics/s-1/tags", request.path)
        assertEquals(listOf("t-1"), bodyJson().getAsJsonArray("tags").map { it.asString })
    }

    @Test
    fun `setTags with empty list sends empty array`() = runTest {
        transport.enqueue(200, """{ "message": "Tags updated", "tags": [] }""")
        val tags = assertNotNull(api().setTags("s-1", emptyList()).valueOrNull())
        assertTrue(tags.isEmpty())
        assertEquals(0, bodyJson().getAsJsonArray("tags").size())
    }

    @Test
    fun `setTags omits tag_filters when the map is empty (stored values stay untouched)`() = runTest {
        transport.enqueue(200, """{ "message": "Tags updated", "tags": [] }""")
        api().setTags("s-1", listOf("t-1"))
        assertFalse(bodyJson().has("tag_filters"), "absent tag_filters must leave server-side values untouched")
    }

    @Test
    fun `setTags sends tag_filters as an id-keyed object when non-empty`() = runTest {
        transport.enqueue(200, """{ "message": "Tags updated", "tags": [{ "id": "t-1", "name": "farms" }] }""")
        assertNotNull(api().setTags("s-1", listOf("t-1"), mapOf(7L to "1", 3L to "12")).valueOrNull())
        val body = bodyJson()
        assertEquals(listOf("t-1"), body.getAsJsonArray("tags").map { it.asString })
        val filters = body.getAsJsonObject("tag_filters")
        assertEquals(setOf("3", "7"), filters.keySet())
        assertEquals("12", filters.get("3").asString)
        assertEquals("1", filters.get("7").asString)
    }

    @Test
    fun `setTags maps 422 tag_filters field errors`() = runTest {
        transport.enqueue(
            422,
            """{ "errors": { "tag_filters.3": ["The value must be between 1 and 20."] } }""",
        )
        val error = api().setTags("s-1", listOf("t-1"), mapOf(3L to "99")).errorOrNull()
        val validation = assertIs<ApiError.Validation>(error)
        assertEquals(listOf("The value must be between 1 and 20."), validation.fieldErrors["tag_filters.3"])
    }

    @Test
    fun `setTags maps 403 community tag permission`() = runTest {
        transport.enqueue(403, """{ "message": "You lack permission for this community tag" }""")
        val error = api().setTags("s-1", listOf("t-2")).errorOrNull()
        assertIs<ApiError.Forbidden>(error)
        assertEquals("You lack permission for this community tag", error.message)
    }

    // ---- co-authors ----

    @Test
    fun `addCoAuthor posts player_uuid`() = runTest {
        transport.enqueue(201, """{ "message": "Co-author added" }""")
        assertNotNull(api().addCoAuthor("s-1", "11111111-2222-3333-4444-555555555555").valueOrNull())
        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/schematics/s-1/co-authors", request.path)
        assertEquals("11111111-2222-3333-4444-555555555555", bodyJson().get("player_uuid").asString)
    }

    @Test
    fun `addCoAuthor maps 422 unknown player`() = runTest {
        transport.enqueue(422, """{ "message": "Unknown player" }""")
        val error = api().addCoAuthor("s-1", "ghost").errorOrNull()
        assertIs<ApiError.Validation>(error)
        assertEquals("Unknown player", error.message)
    }

    @Test
    fun `removeCoAuthor sends DELETE with encoded uuid`() = runTest {
        transport.enqueue(200, """{ "message": "Co-author removed" }""")
        assertNotNull(api().removeCoAuthor("s-1", "1111 weird").valueOrNull())
        val request = transport.lastRequest()
        assertEquals(HttpMethod.DELETE, request.method)
        assertEquals("/schematics/s-1/co-authors/1111%20weird", request.path)
    }

    @Test
    fun `removeCoAuthor maps 422 last author`() = runTest {
        transport.enqueue(422, """{ "message": "Cannot remove the last author" }""")
        val error = api().removeCoAuthor("s-1", "u-1").errorOrNull()
        assertIs<ApiError.Validation>(error)
        assertEquals("Cannot remove the last author", error.message)
    }

    // ---- download ----

    @Test
    fun `download posts format password and player_uuid and returns the raw bytes`() = runTest {
        token = unsignedJwt("11111111-2222-3333-4444-555555555555")
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x42)
        transport.enqueueBinary(200, bytes)
        val result = api().download("s-1", format = "schem", password = "hunter2")
        assertContentEquals(bytes, assertNotNull(result.valueOrNull()))
        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/schematics/s-1/download", request.path)
        val body = bodyJson()
        assertEquals("schem", body.get("format").asString)
        assertEquals("hunter2", body.get("password").asString)
        assertEquals("11111111-2222-3333-4444-555555555555", body.get("player_uuid").asString)
    }

    @Test
    fun `download omits player_uuid and password when unavailable`() = runTest {
        token = "opaque-token"
        transport.enqueueBinary(200, byteArrayOf(1))
        assertNotNull(api().download("s-1").valueOrNull())
        val body = bodyJson()
        assertEquals("litematic", body.get("format").asString)
        assertNull(body.get("player_uuid"))
        assertNull(body.get("password"))
    }

    @Test
    fun `download maps 403 password required`() = runTest {
        transport.enqueue(403, """{ "error": "Password required" }""")
        val error = api().download("s-1").errorOrNull()
        assertIs<ApiError.Forbidden>(error)
        assertEquals("Password required", error.message)
    }

    @Test
    fun `download retries once through rawBinary after successful reauth`() = runTest {
        val bytes = byteArrayOf(7, 7, 7)
        transport.enqueue(401, """{ "message": "Token expired" }""")
        transport.enqueueBinary(200, bytes)
        val result = api(reauthenticate = { token = "fresh"; true }).download("s-1")
        assertContentEquals(bytes, assertNotNull(result.valueOrNull()))
        assertEquals(2, transport.requests.size)
        assertEquals("fresh", transport.lastToken())
    }

    @Test
    fun `download maps network failure to Offline`() = runTest {
        transport.enqueueNetworkFailure()
        assertIs<ApiError.Offline>(api().download("s-1").errorOrNull())
    }
}
