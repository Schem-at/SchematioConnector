package io.schemat.connector.core.modapi

import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.modapi.transport.HttpMethod
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Identity & community endpoints of [SchematioApi] against the contract in
 * docs/api/mod-namespace.md (schemati repo). Sample bodies mirror the doc.
 */
class SchematioApiIdentityTest {

    private val transport = FakeTransport()
    private var token: String? = "tok"

    private fun api(reauthenticate: suspend () -> Boolean = { false }) =
        SchematioApi(transport, { token }, reauthenticate)

    private val communityPayload = """
        {
          "id": "c-1", "slug": "builders", "name": "Builders", "description": "We build",
          "is_public": true, "member_count": 12, "is_member": true,
          "permissions": ["manage-tags"],
          "roles": [{ "id": "r-1", "name": "Builder", "color": "#aabbcc" }]
        }
    """.trimIndent()

    // ---- me ----

    @Test
    fun `me parses snapshot and sends GET mod me with bearer`() = runTest {
        transport.enqueue(
            200,
            """
            {
              "player": { "id": "11111111-2222-3333-4444-555555555555", "name": "Steve" },
              "communities": [$communityPayload],
              "pending_invitations": 2
            }
            """.trimIndent(),
        )
        val result = api().me()
        val me = assertNotNull(result.valueOrNull())
        assertEquals("Steve", me.player?.name)
        assertEquals(1, me.communities.size)
        assertEquals("builders", me.communities[0].slug)
        assertEquals(2, me.pendingInvitations)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/me", request.path)
        assertEquals("tok", transport.lastToken())
    }

    @Test
    fun `me maps 401 to Unauthorized when reauth fails`() = runTest {
        transport.enqueue(401, """{ "message": "Unknown player" }""")
        val error = api().me().errorOrNull()
        assertIs<ApiError.Unauthorized>(error)
        assertEquals("Unknown player", error.message)
        assertEquals(1, transport.requests.size)
    }

    // ---- communities ----

    @Test
    fun `communities parses list`() = runTest {
        transport.enqueue(200, """{ "communities": [$communityPayload] }""")
        val list = assertNotNull(api().communities().valueOrNull())
        assertEquals(1, list.size)
        assertEquals("Builders", list[0].name)
        assertEquals(12, list[0].memberCount)
        assertTrue(list[0].can("manage-tags"))
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/communities", request.path)
    }

    @Test
    fun `communities maps 500 to Server error`() = runTest {
        transport.enqueue(500, """{ "message": "Server Error" }""")
        assertIs<ApiError.Server>(api().communities().errorOrNull())
    }

    // ---- community(slug) ----

    @Test
    fun `community parses single payload`() = runTest {
        transport.enqueue(200, """{ "community": $communityPayload }""")
        val community = assertNotNull(api().community("builders").valueOrNull())
        assertEquals("c-1", community.id)
        assertEquals("builders", community.slug)
        assertEquals("We build", community.description)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/communities/builders", request.path)
    }

    @Test
    fun `community maps 404 for private or unknown`() = runTest {
        transport.enqueue(404, """{ "message": "Not found" }""")
        assertIs<ApiError.NotFound>(api().community("secret").errorOrNull())
    }

    @Test
    fun `path segments are url-encoded`() = runTest {
        transport.enqueue(404, """{ "message": "Not found" }""")
        api().community("weird slug/รัก")
        assertEquals("/mod/communities/weird%20slug%2F%E0%B8%A3%E0%B8%B1%E0%B8%81", transport.lastRequest().path)
    }

    // ---- communityMembers ----

    @Test
    fun `communityMembers parses page and sends pagination query`() = runTest {
        transport.enqueue(
            200,
            """
            {
              "data": [
                {
                  "id": "11111111-2222-3333-4444-555555555555", "name": "Steve",
                  "joined_at": "2026-01-01T00:00:00Z", "is_owner": false,
                  "roles": [{ "id": "r-1", "name": "Builder", "color": "#aabbcc" }]
                }
              ],
              "meta": { "total": 42, "current_page": 1, "last_page": 2 }
            }
            """.trimIndent(),
        )
        val page = assertNotNull(api().communityMembers("builders", page = 1, perPage = 25).valueOrNull())
        assertEquals(1, page.items.size)
        assertEquals("Steve", page.items[0].name)
        assertFalse(page.items[0].isOwner)
        assertEquals("Builder", page.items[0].roles[0].name)
        assertEquals(42, page.meta.total)
        assertTrue(page.hasMore)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/communities/builders/members", request.path)
        assertEquals(mapOf("page" to "1", "per_page" to "25"), request.query)
    }

    @Test
    fun `communityMembers maps 403 for non-member`() = runTest {
        transport.enqueue(403, """{ "message": "Forbidden" }""")
        assertIs<ApiError.Forbidden>(api().communityMembers("builders").errorOrNull())
    }

    // ---- kickMember ----

    @Test
    fun `kickMember sends DELETE to member path`() = runTest {
        transport.enqueue(200, """{ "message": "Member removed" }""")
        val result = api().kickMember("builders", "11111111-2222-3333-4444-555555555555")
        assertIs<ApiResult.Success<Unit>>(result)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.DELETE, request.method)
        assertEquals("/mod/communities/builders/members/11111111-2222-3333-4444-555555555555", request.path)
        assertNull(request.jsonBody)
    }

    @Test
    fun `kickMember maps 403 owner protection`() = runTest {
        transport.enqueue(403, """{ "message": "Cannot kick the community owner" }""")
        val error = api().kickMember("builders", "uuid").errorOrNull()
        assertIs<ApiError.Forbidden>(error)
        assertEquals("Cannot kick the community owner", error.message)
    }

    // ---- syncMemberRoles ----

    @Test
    fun `syncMemberRoles sends PUT with role_ids and parses resulting roles`() = runTest {
        transport.enqueue(
            200,
            """{ "message": "Roles updated", "roles": [{ "id": "r-1", "name": "Builder" }, { "id": "r-2", "name": "Member" }] }""",
        )
        val roles = assertNotNull(
            api().syncMemberRoles("builders", "11111111-2222-3333-4444-555555555555", listOf("r-1")).valueOrNull(),
        )
        assertEquals(listOf("r-1", "r-2"), roles.map { it.id })
        assertEquals("Builder", roles[0].name)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.PUT, request.method)
        assertEquals("/mod/communities/builders/members/11111111-2222-3333-4444-555555555555/roles", request.path)
        val body = assertNotNull(parseJsonSafe(request.jsonBody))
        val ids = body.getAsJsonArray("role_ids").map { it.asString }
        assertEquals(listOf("r-1"), ids)
    }

    @Test
    fun `syncMemberRoles maps 422 validation`() = runTest {
        transport.enqueue(422, """{ "message": "Invalid", "errors": { "role_ids.0": ["Unknown role"] } }""")
        val error = api().syncMemberRoles("builders", "uuid", listOf("bad")).errorOrNull()
        assertIs<ApiError.Validation>(error)
        assertEquals(listOf("Unknown role"), error.fieldErrors["role_ids.0"])
    }

    // ---- communityRoles ----

    @Test
    fun `communityRoles parses role definitions from GET roles`() = runTest {
        transport.enqueue(
            200,
            """
            {
              "roles": [
                { "id": "r-admin", "name": "Admin", "color": "#ff0000",
                  "is_system": true, "permissions": ["manage-members", "manage-roles"], "position": 0 },
                { "id": "r-1", "name": "Builder", "color": "#aabbcc",
                  "is_system": false, "permissions": [], "position": 1 }
              ]
            }
            """.trimIndent(),
        )
        val roles = assertNotNull(api().communityRoles("builders").valueOrNull())
        assertEquals(listOf("r-admin", "r-1"), roles.map { it.id })
        assertTrue(roles[0].isSystem)
        assertEquals(listOf("manage-members", "manage-roles"), roles[0].permissions)
        assertEquals(0, roles[0].position)
        assertFalse(roles[1].isSystem)
        assertEquals(1, roles[1].position)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/communities/builders/roles", request.path)
    }

    @Test
    fun `communityRoles maps 404 for private community`() = runTest {
        transport.enqueue(404, """{ "message": "Not found" }""")
        assertIs<ApiError.NotFound>(api().communityRoles("secret").errorOrNull())
    }

    // ---- invitations ----

    @Test
    fun `invitations parses list`() = runTest {
        transport.enqueue(
            200,
            """
            {
              "invitations": [
                {
                  "id": 7,
                  "community": { "slug": "builders", "name": "Builders" },
                  "invited_by": "Alex", "message": "join us", "expires_at": "2026-07-01T00:00:00Z"
                }
              ]
            }
            """.trimIndent(),
        )
        val invitations = assertNotNull(api().invitations().valueOrNull())
        assertEquals(1, invitations.size)
        assertEquals(7L, invitations[0].id)
        assertEquals("builders", invitations[0].communitySlug)
        assertEquals("Alex", invitations[0].invitedBy)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/invitations", request.path)
    }

    @Test
    fun `invitations maps 401`() = runTest {
        transport.enqueue(401, """{ "message": "Unauthenticated." }""")
        assertIs<ApiError.Unauthorized>(api().invitations().errorOrNull())
    }

    // ---- acceptInvitation / declineInvitation ----

    @Test
    fun `acceptInvitation posts and returns community slug`() = runTest {
        transport.enqueue(200, """{ "message": "Invitation accepted", "community_slug": "builders" }""")
        assertEquals("builders", api().acceptInvitation(7L).valueOrNull())
        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/mod/invitations/7/accept", request.path)
    }

    @Test
    fun `acceptInvitation maps 404 for someone else's invitation`() = runTest {
        transport.enqueue(404, """{ "message": "Not found" }""")
        assertIs<ApiError.NotFound>(api().acceptInvitation(99L).errorOrNull())
    }

    @Test
    fun `declineInvitation posts to decline path`() = runTest {
        transport.enqueue(200, """{ "message": "Invitation declined" }""")
        assertIs<ApiResult.Success<Unit>>(api().declineInvitation(7L))
        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/mod/invitations/7/decline", request.path)
    }

    @Test
    fun `declineInvitation maps 422 when no longer pending`() = runTest {
        transport.enqueue(422, """{ "message": "Invitation is no longer pending" }""")
        assertIs<ApiError.Validation>(api().declineInvitation(7L).errorOrNull())
    }

    // ---- leaveCommunity ----

    @Test
    fun `leaveCommunity sends DELETE membership`() = runTest {
        transport.enqueue(200, """{ "message": "You left the community" }""")
        assertIs<ApiResult.Success<Unit>>(api().leaveCommunity("builders"))
        val request = transport.lastRequest()
        assertEquals(HttpMethod.DELETE, request.method)
        assertEquals("/mod/communities/builders/membership", request.path)
    }

    @Test
    fun `leaveCommunity maps 422 for owner`() = runTest {
        transport.enqueue(422, """{ "message": "Owners must transfer ownership before leaving" }""")
        val error = api().leaveCommunity("builders").errorOrNull()
        assertIs<ApiError.Validation>(error)
        assertEquals("Owners must transfer ownership before leaving", error.message)
    }

    // ---- globalTags ----

    @Test
    fun `globalTags parses the minecraft root tree from GET mod tags`() = runTest {
        // Per docs/api/mod-namespace.md: the minecraft root itself is included and
        // is_manually_assignable=false marks category-only nodes.
        transport.enqueue(
            200,
            """
            {
              "tags": [
                {
                  "id": "g-root", "name": "minecraft", "color": null, "scope": "public_use",
                  "is_manually_assignable": true,
                  "children": [
                    {
                      "id": "g-cat", "name": "Buildings", "color": "#ff0000", "scope": "public_use",
                      "is_manually_assignable": false,
                      "children": []
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val tags = assertNotNull(api().globalTags().valueOrNull())
        assertEquals(1, tags.size)
        assertEquals("minecraft", tags[0].name)
        assertTrue(tags[0].isManuallyAssignable)
        assertEquals("Buildings", tags[0].children[0].name)
        assertFalse(tags[0].children[0].isManuallyAssignable)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/tags", request.path)
    }

    @Test
    fun `globalTags maps 401 when unauthenticated`() = runTest {
        transport.enqueue(401, """{ "message": "Unauthenticated." }""")
        assertIs<ApiError.Unauthorized>(api().globalTags().errorOrNull())
    }

    // ---- communityTags ----

    @Test
    fun `communityTags parses tree`() = runTest {
        transport.enqueue(
            200,
            """
            {
              "tags": [
                {
                  "id": "t-1", "name": "Medieval", "color": "#aabbcc", "scope": "public_use",
                  "children": [{ "id": "t-2", "name": "Castles", "color": null, "scope": "private", "children": [] }]
                }
              ]
            }
            """.trimIndent(),
        )
        val tags = assertNotNull(api().communityTags("builders").valueOrNull())
        assertEquals(1, tags.size)
        assertEquals("Medieval", tags[0].name)
        assertEquals("Castles", tags[0].children[0].name)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/mod/communities/builders/tags", request.path)
    }

    @Test
    fun `communityTags maps 404 for private community`() = runTest {
        transport.enqueue(404, """{ "message": "Not found" }""")
        assertIs<ApiError.NotFound>(api().communityTags("secret").errorOrNull())
    }

    // ---- createCommunityTag ----

    @Test
    fun `createCommunityTag posts fields and parses tag`() = runTest {
        transport.enqueue(201, """{ "tag": { "id": "t-9", "name": "Towers", "color": "#112233", "scope": "private" } }""")
        val tag = assertNotNull(
            api().createCommunityTag(
                "builders",
                name = "Towers",
                scope = "private",
                color = "#112233",
                parentId = "t-1",
                description = "tall things",
            ).valueOrNull(),
        )
        assertEquals("t-9", tag.id)
        assertEquals("Towers", tag.name)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/mod/communities/builders/tags", request.path)
        val body = assertNotNull(parseJsonSafe(request.jsonBody))
        assertEquals("Towers", body.get("name").asString)
        assertEquals("private", body.get("scope").asString)
        assertEquals("#112233", body.get("color").asString)
        assertEquals("t-1", body.get("parent_id").asString)
        assertEquals("tall things", body.get("description").asString)
    }

    @Test
    fun `createCommunityTag omits null optionals from body`() = runTest {
        transport.enqueue(201, """{ "tag": { "id": "t-9", "name": "Towers", "scope": "private" } }""")
        api().createCommunityTag("builders", name = "Towers", scope = "private")
        val body = assertNotNull(parseJsonSafe(transport.lastRequest().jsonBody))
        assertEquals(setOf("name", "scope"), body.keySet())
    }

    @Test
    fun `createCommunityTag maps 403 without manage-tags`() = runTest {
        transport.enqueue(403, """{ "message": "Forbidden" }""")
        assertIs<ApiError.Forbidden>(
            api().createCommunityTag("builders", name = "Towers", scope = "private").errorOrNull(),
        )
    }

    // ---- updateCommunityTag ----

    @Test
    fun `updateCommunityTag patches fields and parses tag`() = runTest {
        transport.enqueue(200, """{ "tag": { "id": "t-9", "name": "Keeps", "color": "#445566", "scope": "public_viewing" } }""")
        val tag = assertNotNull(
            api().updateCommunityTag("builders", "t-9", name = "Keeps", scope = "public_viewing", color = "#445566").valueOrNull(),
        )
        assertEquals("Keeps", tag.name)
        assertEquals("public_viewing", tag.scope)
        val request = transport.lastRequest()
        assertEquals(HttpMethod.PATCH, request.method)
        assertEquals("/mod/communities/builders/tags/t-9", request.path)
        val body = assertNotNull(parseJsonSafe(request.jsonBody))
        assertEquals("Keeps", body.get("name").asString)
        assertEquals("public_viewing", body.get("scope").asString)
        assertEquals("#445566", body.get("color").asString)
        assertFalse(body.has("parent_id"))
    }

    @Test
    fun `updateCommunityTag maps 404 for foreign tag`() = runTest {
        transport.enqueue(404, """{ "message": "Not found" }""")
        assertIs<ApiError.NotFound>(
            api().updateCommunityTag("builders", "t-other", name = "X", scope = "private").errorOrNull(),
        )
    }

    // ---- deleteCommunityTag ----

    @Test
    fun `deleteCommunityTag sends DELETE`() = runTest {
        transport.enqueue(200, """{ "message": "Tag deleted" }""")
        assertIs<ApiResult.Success<Unit>>(api().deleteCommunityTag("builders", "t-9"))
        val request = transport.lastRequest()
        assertEquals(HttpMethod.DELETE, request.method)
        assertEquals("/mod/communities/builders/tags/t-9", request.path)
    }

    @Test
    fun `deleteCommunityTag maps 422 when schematics attached`() = runTest {
        transport.enqueue(422, """{ "message": "Tag has schematics attached" }""")
        val error = api().deleteCommunityTag("builders", "t-9").errorOrNull()
        assertIs<ApiError.Validation>(error)
        assertEquals("Tag has schematics attached", error.message)
    }

    // ---- shared plumbing (once, not per method) ----

    @Test
    fun `401 triggers one reauth retry with the refreshed token`() = runTest {
        transport.enqueue(401, """{ "message": "Unauthenticated." }""")
        transport.enqueue(200, """{ "player": { "id": "p-1", "name": "Steve" }, "communities": [], "pending_invitations": 0 }""")
        token = "old"
        val result = api(reauthenticate = { token = "new"; true }).me()
        assertNotNull(result.valueOrNull())
        assertEquals(2, transport.requests.size)
        assertEquals("old", transport.requests[0].second)
        assertEquals("new", transport.requests[1].second)
    }

    @Test
    fun `401 with failing reauth makes a single request and returns Unauthorized`() = runTest {
        transport.enqueue(401, """{ "message": "Unauthenticated." }""")
        val result = api(reauthenticate = { false }).me()
        assertIs<ApiError.Unauthorized>(result.errorOrNull())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `network failure maps to Offline`() = runTest {
        transport.enqueueNetworkFailure()
        assertEquals(ApiError.Offline, api().me().errorOrNull())
    }
}
