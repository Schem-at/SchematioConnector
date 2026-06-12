package io.schemat.connector.core.modapi

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * TTL caching, stale-offline fallback and mutation invalidation of [CachedSchematioApi].
 * Uses an injected fake clock - no real sleeps.
 */
class CachedSchematioApiTest {

    private val transport = FakeTransport()
    private var now = 1_000_000L
    private val ttlMs = 5 * 60 * 1000L

    private fun cachedApi() = CachedSchematioApi(
        api = SchematioApi(transport, { "tok" }),
        ttlMs = ttlMs,
        clock = { now },
    )

    private val communitiesBody = """
        { "communities": [ { "id": "c-1", "slug": "builders", "name": "Builders" } ] }
    """.trimIndent()

    private val emptyPageBody = """
        { "data": [], "meta": { "current_page": 1, "last_page": 1, "per_page": 20, "total": 0 } }
    """.trimIndent()

    private val rolesBody = """
        {
          "roles": [
            { "id": "r-admin", "name": "Admin", "color": "#ff0000", "is_system": true, "permissions": ["manage-members"], "position": 0 },
            { "id": "r-1", "name": "Builder", "color": "#aabbcc", "is_system": false, "permissions": [], "position": 1 }
          ]
        }
    """.trimIndent()

    private val globalTagsBody = """
        {
          "tags": [
            {
              "id": "g-root", "name": "minecraft", "color": null, "scope": "public_use",
              "is_manually_assignable": true,
              "children": [
                { "id": "g-cat", "name": "Buildings", "color": "#ff0000", "scope": "public_use",
                  "is_manually_assignable": false, "children": [] }
              ]
            }
          ]
        }
    """.trimIndent()

    // ---- caching of reads ----

    @Test
    fun `two consecutive communities calls hit the transport once`() = runTest {
        transport.enqueue(200, communitiesBody)
        val api = cachedApi()

        val first = assertNotNull(api.communities().valueOrNull())
        assertFalse(first.isStale)
        assertEquals("builders", first.value.single().slug)

        val second = assertNotNull(api.communities().valueOrNull())
        assertFalse(second.isStale)
        assertEquals(first.value, second.value)
        assertEquals(1, transport.requests.size, "second call within TTL must be served from cache")
    }

    @Test
    fun `expired entry triggers a refetch`() = runTest {
        transport.enqueue(200, communitiesBody)
        transport.enqueue(200, """{ "communities": [] }""")
        val api = cachedApi()

        api.communities()
        now += ttlMs + 1

        val refetched = assertNotNull(api.communities().valueOrNull())
        assertFalse(refetched.isStale)
        assertTrue(refetched.value.isEmpty(), "fresh server data must replace the expired entry")
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `distinct browse queries cache under distinct keys`() = runTest {
        transport.enqueue(200, emptyPageBody)
        transport.enqueue(200, emptyPageBody)
        val api = cachedApi()

        api.communitySchematics("builders", BrowseQuery(page = 1))
        api.communitySchematics("builders", BrowseQuery(page = 2))
        assertEquals(2, transport.requests.size)

        api.communitySchematics("builders", BrowseQuery(page = 1))
        assertEquals(2, transport.requests.size, "page 1 must still be cached")
    }

    @Test
    fun `different tags and filter constraints cache under distinct keys`() = runTest {
        // Cache keys derive from the full sorted query map, so tags / filter[<id>] /
        // filter_min/max[<id>] params must isolate entries from each other.
        transport.enqueue(200, emptyPageBody)
        transport.enqueue(200, emptyPageBody)
        transport.enqueue(200, emptyPageBody)
        transport.enqueue(200, emptyPageBody)
        val api = cachedApi()

        val unfiltered = BrowseQuery()
        val tagged = BrowseQuery(tags = listOf("t-1", "t-2"))
        val exact = BrowseQuery(tags = listOf("t-1", "t-2"), filterConstraints = listOf(FilterConstraint.Exact(7, "1")))
        val ranged = BrowseQuery(tags = listOf("t-1", "t-2"), filterConstraints = listOf(FilterConstraint.Range(7, 1.0, 20.0)))

        api.schematics(unfiltered)
        api.schematics(tagged)
        api.schematics(exact)
        api.schematics(ranged)
        assertEquals(4, transport.requests.size, "each filter set must miss the others' cache entries")

        api.schematics(exact)
        assertEquals(4, transport.requests.size, "an identical filter set must be served from cache")
    }

    // ---- stale fallback ----

    @Test
    fun `offline failure after expiry serves stale data flagged with its real age`() = runTest {
        transport.enqueue(200, communitiesBody)
        transport.enqueueNetworkFailure()
        val api = cachedApi()

        api.communities()
        now += ttlMs + 60_000

        val stale = assertNotNull(api.communities().valueOrNull())
        assertTrue(stale.isStale)
        assertEquals(ttlMs + 60_000, stale.ageMs)
        assertEquals("builders", stale.value.single().slug)
    }

    @Test
    fun `offline failure with no cached entry propagates Offline`() = runTest {
        transport.enqueueNetworkFailure()
        assertIs<ApiError.Offline>(cachedApi().communities().errorOrNull())
    }

    @Test
    fun `403 after expiry is NOT masked by stale data`() = runTest {
        transport.enqueue(200, communitiesBody)
        transport.enqueue(403, """{ "message": "Forbidden" }""")
        val api = cachedApi()

        api.communities()
        now += ttlMs + 1

        val error = api.communities().errorOrNull()
        assertIs<ApiError.Forbidden>(error, "an authorization failure must never be hidden by cached data")
    }

    // ---- mutation invalidation ----

    @Test
    fun `leaveCommunity invalidates communities so the next call refetches`() = runTest {
        transport.enqueue(200, communitiesBody)
        transport.enqueue(204, "")
        transport.enqueue(200, """{ "communities": [] }""")
        val api = cachedApi()

        api.communities()
        assertIs<ApiResult.Success<Unit>>(api.leaveCommunity("builders"))

        val after = assertNotNull(api.communities().valueOrNull())
        assertTrue(after.value.isEmpty())
        assertEquals(3, transport.requests.size, "communities must be refetched after leaving one")
    }

    @Test
    fun `setTags invalidates community schematic listings`() = runTest {
        transport.enqueue(200, emptyPageBody)
        transport.enqueue(200, """{ "tags": [] }""")
        transport.enqueue(200, emptyPageBody)
        val api = cachedApi()

        api.communitySchematics("builders")
        assertNotNull(api.setTags("s-1", listOf("t-1"), tagFilters = mapOf(3L to "12")).valueOrNull())

        api.communitySchematics("builders")
        assertEquals(3, transport.requests.size, "listing must be refetched after a tag mutation")
    }

    @Test
    fun `failed mutation does not invalidate the cache`() = runTest {
        transport.enqueue(200, communitiesBody)
        transport.enqueue(422, """{ "message": "Owners cannot leave their community." }""")
        val api = cachedApi()

        api.communities()
        assertIs<ApiError.Validation>(api.leaveCommunity("builders").errorOrNull())

        assertNotNull(api.communities().valueOrNull())
        assertEquals(2, transport.requests.size, "a failed mutation changed nothing, so the cache must survive")
    }

    @Test
    fun `communityRoles is cached per slug within the TTL`() = runTest {
        transport.enqueue(200, rolesBody)
        val api = cachedApi()

        val first = assertNotNull(api.communityRoles("builders").valueOrNull())
        assertFalse(first.isStale)
        assertEquals(listOf("r-admin", "r-1"), first.value.map { it.id })
        assertTrue(first.value[0].isSystem)

        val second = assertNotNull(api.communityRoles("builders").valueOrNull())
        assertEquals(first.value, second.value)
        assertEquals(1, transport.requests.size, "second call within TTL must be served from cache")
    }

    @Test
    fun `syncMemberRoles invalidates the slug's cached roles`() = runTest {
        transport.enqueue(200, rolesBody)
        transport.enqueue(200, """{ "message": "Roles updated", "roles": [] }""")
        transport.enqueue(200, """{ "roles": [] }""")
        val api = cachedApi()

        api.communityRoles("builders")
        assertNotNull(api.syncMemberRoles("builders", "p-1", listOf("r-1")).valueOrNull())

        val after = assertNotNull(api.communityRoles("builders").valueOrNull())
        assertTrue(after.value.isEmpty())
        assertEquals(3, transport.requests.size, "roles must be refetched after a community-scope mutation")
    }

    @Test
    fun `community-scope invalidation does not touch another slug's roles`() = runTest {
        transport.enqueue(200, rolesBody)
        transport.enqueue(204, "")
        val api = cachedApi()

        api.communityRoles("builders")
        assertIs<ApiResult.Success<Unit>>(api.leaveCommunity("redstoners"))

        assertNotNull(api.communityRoles("builders").valueOrNull())
        assertEquals(2, transport.requests.size, "another community's mutation must not evict these roles")
    }

    @Test
    fun `globalTags is cached within the TTL`() = runTest {
        transport.enqueue(200, globalTagsBody)
        val api = cachedApi()

        val first = assertNotNull(api.globalTags().valueOrNull())
        assertFalse(first.isStale)
        assertEquals("minecraft", first.value.single().name)
        assertFalse(first.value.single().children[0].isManuallyAssignable)

        val second = assertNotNull(api.globalTags().valueOrNull())
        assertEquals(first.value, second.value)
        assertEquals(1, transport.requests.size, "second call within TTL must be served from cache")
    }

    @Test
    fun `community mutations do not evict the global tag tree`() = runTest {
        transport.enqueue(200, globalTagsBody)
        transport.enqueue(204, "")
        val api = cachedApi()

        api.globalTags()
        assertIs<ApiResult.Success<Unit>>(api.leaveCommunity("builders"))

        assertNotNull(api.globalTags().valueOrNull())
        assertEquals(2, transport.requests.size, "the global tree is invalidated only by clear()")
    }

    @Test
    fun `clear drops the cached global tag tree`() = runTest {
        transport.enqueue(200, globalTagsBody)
        transport.enqueue(200, """{ "tags": [] }""")
        val api = cachedApi()

        api.globalTags()
        api.clear()

        val refetched = assertNotNull(api.globalTags().valueOrNull())
        assertTrue(refetched.value.isEmpty(), "clear() must force a live refetch")
        assertEquals(2, transport.requests.size)
    }

    // ---- pass-through reads ----

    @Test
    fun `communityMembers is never cached`() = runTest {
        transport.enqueue(200, emptyPageBody)
        transport.enqueue(200, emptyPageBody)
        val api = cachedApi()

        api.communityMembers("builders")
        api.communityMembers("builders")
        assertEquals(2, transport.requests.size, "moderation needs a fresh member list on every call")
    }
}
