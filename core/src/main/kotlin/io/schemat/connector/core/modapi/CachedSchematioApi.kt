package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.InvitationInfo
import io.schemat.connector.core.modapi.dto.MeSnapshot
import io.schemat.connector.core.modapi.dto.MemberInfo
import io.schemat.connector.core.modapi.dto.Page
import io.schemat.connector.core.modapi.dto.QuickShareInfo
import io.schemat.connector.core.modapi.dto.RoleInfo
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.core.modapi.dto.TagNode
import java.util.concurrent.ConcurrentHashMap

/**
 * A cached read result. [isStale] is false for fresh cache hits and live fetches;
 * true only when an [ApiError.Offline] failure was bridged with expired cached data.
 * [ageMs] is the time since the value was fetched from the server.
 */
data class StaleAware<T>(val value: T, val isStale: Boolean, val ageMs: Long)

/**
 * TTL caching decorator around [SchematioApi].
 *
 * ## What is cached
 * Read-only listing/identity endpoints only: [me], [communities], [community],
 * [communityTags], [communitySchematics], [schematics] (keyed by full sorted query)
 * and [invitations]. These return `ApiResult<StaleAware<T>>` so UI can surface staleness.
 *
 * ## What is never cached
 * - [download] / [uploadSchematic] - binary payloads.
 * - [schematic] detail - typically viewed right after mutations (tags/co-authors/update),
 *   so a cache would mostly serve wrong data.
 * - [communityMembers] - NOT cached by design: moderation flows ([kickMember],
 *   [syncMemberRoles]) act on this list and need real-time freshness; a stale list could
 *   show already-kicked members or outdated roles and prompt wrong moderation actions.
 * - Quick shares - they carry live `current_uses`/`is_active` state and expiry.
 *
 * ## Stale fallback semantics
 * On a cache miss/expiry the live call runs. If it fails with [ApiError.Offline] and an
 * expired entry exists, the entry is served flagged stale (mirrors `SchematicsApiService`
 * offline behavior). Any other error (401/403/404/422/5xx) is propagated unchanged -
 * masking an authorization failure with cached data would leak content the server just
 * refused to serve.
 *
 * ## Invalidation
 * Mutations delegate first and invalidate only on success (a failed mutation changed
 * nothing server-side, so the cache is still correct).
 *
 * @param ttlMs entry freshness window (default 5 minutes, matching `SchematicCache`)
 * @param clock millisecond clock, injectable for tests
 */
class CachedSchematioApi(
    private val api: SchematioApi,
    private val ttlMs: Long = 5 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ---- internal TTL cache (modeled on cache/SchematicCache, which bukkit owns and we must not touch) ----

    private class Entry(val value: Any, val cachedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Fresh hit → cached value (isStale=false). Otherwise fetch live: success is cached
     * and returned fresh; [ApiError.Offline] with an expired-but-present entry serves it
     * stale; any other failure propagates (see class KDoc - never mask 403/404).
     */
    private suspend fun <T : Any> cached(key: String, fetch: suspend () -> ApiResult<T>): ApiResult<StaleAware<T>> {
        val entry = cache[key]
        val now = clock()
        if (entry != null && now - entry.cachedAt <= ttlMs) {
            @Suppress("UNCHECKED_CAST")
            return ApiResult.Success(StaleAware(entry.value as T, isStale = false, ageMs = now - entry.cachedAt))
        }
        return when (val result = fetch()) {
            is ApiResult.Success -> {
                cache[key] = Entry(result.value, clock())
                ApiResult.Success(StaleAware(result.value, isStale = false, ageMs = 0))
            }
            is ApiResult.Failure ->
                if (result.error is ApiError.Offline && entry != null) {
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Success(StaleAware(entry.value as T, isStale = true, ageMs = clock() - entry.cachedAt))
                } else {
                    result
                }
        }
    }

    /** Drops everything - e.g. on sign-out, when cached identity-scoped data becomes wrong. */
    fun clear() = cache.clear()

    private fun invalidate(vararg keys: String) = keys.forEach { cache.remove(it) }

    private fun invalidatePrefix(prefix: String) = cache.keys.removeIf { it.startsWith(prefix) }

    /** Everything scoped to one community: detail, tags, roles and its schematic listings. */
    private fun invalidateCommunityScope(slug: String) {
        invalidate(communityKey(slug), tagsKey(slug), rolesKey(slug))
        invalidatePrefix("$COMMUNITY_SCHEMATICS_PREFIX$slug?")
    }

    /** All schematic listing keys, community-scoped and general. */
    private fun invalidateAllListings() {
        invalidatePrefix(COMMUNITY_SCHEMATICS_PREFIX)
        invalidatePrefix(SCHEMATICS_PREFIX)
    }

    private fun <T> ApiResult<T>.invalidatingOnSuccess(invalidate: (T) -> Unit): ApiResult<T> {
        if (this is ApiResult.Success) invalidate(value)
        return this
    }

    // ---- cache keys ----

    private companion object {
        const val ME_KEY = "me"
        const val COMMUNITIES_KEY = "communities"
        const val INVITATIONS_KEY = "invitations"
        const val GLOBAL_TAGS_KEY = "globalTags"
        const val COMMUNITY_SCHEMATICS_PREFIX = "communitySchematics:"
        const val SCHEMATICS_PREFIX = "schematics?"
    }

    private fun communityKey(slug: String) = "community:$slug"
    private fun tagsKey(slug: String) = "communityTags:$slug"
    private fun rolesKey(slug: String) = "communityRoles:$slug"

    /** Deterministic key fragment: the query map sorted by key. */
    private fun BrowseQuery.cacheKeyPart(): String =
        toQueryMap().entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }

    // ---- cached reads ----

    suspend fun me(): ApiResult<StaleAware<MeSnapshot>> = cached(ME_KEY) { api.me() }

    suspend fun communities(): ApiResult<StaleAware<List<CommunitySummary>>> =
        cached(COMMUNITIES_KEY) { api.communities() }

    suspend fun community(slug: String): ApiResult<StaleAware<CommunitySummary>> =
        cached(communityKey(slug)) { api.community(slug) }

    suspend fun communityTags(slug: String): ApiResult<StaleAware<List<TagNode>>> =
        cached(tagsKey(slug)) { api.communityTags(slug) }

    /**
     * The global minecraft tag tree changes rarely and no mod mutation can alter it,
     * so the entry is invalidated only by TTL expiry or [clear].
     */
    suspend fun globalTags(): ApiResult<StaleAware<List<TagNode>>> =
        cached(GLOBAL_TAGS_KEY) { api.globalTags() }

    /** Role definitions change rarely; cached under the community scope (invalidated with the slug). */
    suspend fun communityRoles(slug: String): ApiResult<StaleAware<List<RoleInfo>>> =
        cached(rolesKey(slug)) { api.communityRoles(slug) }

    suspend fun communitySchematics(
        slug: String,
        query: BrowseQuery = BrowseQuery(),
    ): ApiResult<StaleAware<Page<SchematicSummary>>> =
        cached("$COMMUNITY_SCHEMATICS_PREFIX$slug?${query.cacheKeyPart()}") { api.communitySchematics(slug, query) }

    suspend fun schematics(
        query: BrowseQuery = BrowseQuery(),
        mineOnly: Boolean = false,
    ): ApiResult<StaleAware<Page<SchematicSummary>>> =
        cached("$SCHEMATICS_PREFIX${query.cacheKeyPart()}|mine=$mineOnly") { api.schematics(query, mineOnly) }

    suspend fun invitations(): ApiResult<StaleAware<List<InvitationInfo>>> =
        cached(INVITATIONS_KEY) { api.invitations() }

    // ---- uncached reads (see class KDoc for the rationale per endpoint) ----

    suspend fun communityMembers(slug: String, page: Int = 1, perPage: Int = 25): ApiResult<Page<MemberInfo>> =
        api.communityMembers(slug, page, perPage)

    suspend fun schematic(id: String): ApiResult<SchematicDetail> = api.schematic(id)

    suspend fun download(id: String, format: String = "litematic", password: String? = null): ApiResult<ByteArray> =
        api.download(id, format, password)

    suspend fun quickShares(): ApiResult<List<QuickShareInfo>> = api.quickShares()

    suspend fun quickShare(accessCode: String): ApiResult<QuickShareInfo> = api.quickShare(accessCode)

    // ---- mutations: delegate, then invalidate on success only ----

    suspend fun kickMember(slug: String, playerUuid: String): ApiResult<Unit> =
        api.kickMember(slug, playerUuid).invalidatingOnSuccess { invalidateCommunityScope(slug) }

    suspend fun syncMemberRoles(slug: String, playerUuid: String, roleIds: List<String>): ApiResult<List<RoleInfo>> =
        api.syncMemberRoles(slug, playerUuid, roleIds).invalidatingOnSuccess { invalidateCommunityScope(slug) }

    suspend fun acceptInvitation(id: Long): ApiResult<String> =
        api.acceptInvitation(id).invalidatingOnSuccess { joinedSlug ->
            invalidate(INVITATIONS_KEY, COMMUNITIES_KEY, ME_KEY)
            invalidateCommunityScope(joinedSlug)
        }

    suspend fun declineInvitation(id: Long): ApiResult<Unit> =
        api.declineInvitation(id).invalidatingOnSuccess { invalidate(INVITATIONS_KEY, COMMUNITIES_KEY, ME_KEY) }

    suspend fun leaveCommunity(slug: String): ApiResult<Unit> =
        api.leaveCommunity(slug).invalidatingOnSuccess {
            invalidateCommunityScope(slug)
            invalidate(COMMUNITIES_KEY, ME_KEY)
        }

    suspend fun createCommunityTag(
        slug: String,
        name: String,
        scope: String,
        color: String? = null,
        parentId: String? = null,
        description: String? = null,
    ): ApiResult<TagNode> =
        api.createCommunityTag(slug, name, scope, color, parentId, description)
            .invalidatingOnSuccess { invalidateTagScope(slug) }

    suspend fun updateCommunityTag(
        slug: String,
        tagId: String,
        name: String,
        scope: String,
        color: String? = null,
        description: String? = null,
    ): ApiResult<TagNode> =
        api.updateCommunityTag(slug, tagId, name, scope, color, description)
            .invalidatingOnSuccess { invalidateTagScope(slug) }

    suspend fun deleteCommunityTag(slug: String, tagId: String): ApiResult<Unit> =
        api.deleteCommunityTag(slug, tagId).invalidatingOnSuccess { invalidateTagScope(slug) }

    /** Tag mutations: the slug's tags + schematic listings (tag filters/labels) + communities + me. */
    private fun invalidateTagScope(slug: String) {
        invalidateCommunityScope(slug)
        invalidate(COMMUNITIES_KEY, ME_KEY)
    }

    suspend fun uploadSchematic(request: UploadRequest): ApiResult<SchematicDetail> =
        api.uploadSchematic(request).invalidatingOnSuccess { invalidateAllListings() }

    suspend fun updateSchematic(
        id: String,
        name: String? = null,
        description: String? = null,
        isPublic: Boolean? = null,
    ): ApiResult<SchematicDetail> =
        api.updateSchematic(id, name, description, isPublic).invalidatingOnSuccess { invalidateAllListings() }

    suspend fun deleteSchematic(id: String): ApiResult<Unit> =
        api.deleteSchematic(id).invalidatingOnSuccess { invalidateAllListings() }

    suspend fun setTags(
        id: String,
        tagIds: List<String>,
        tagFilters: Map<Long, String> = emptyMap(),
    ): ApiResult<List<TagNode>> =
        api.setTags(id, tagIds, tagFilters).invalidatingOnSuccess { invalidateAllListings() }

    suspend fun addCoAuthor(id: String, playerUuid: String): ApiResult<Unit> =
        api.addCoAuthor(id, playerUuid).invalidatingOnSuccess { invalidateAllListings() }

    suspend fun removeCoAuthor(id: String, playerUuid: String): ApiResult<Unit> =
        api.removeCoAuthor(id, playerUuid).invalidatingOnSuccess { invalidateAllListings() }

    // ---- quick-share mutations: nothing quick-share-related is cached ----

    suspend fun createQuickShare(request: QuickShareRequest): ApiResult<QuickShareInfo> =
        api.createQuickShare(request)

    suspend fun revokeQuickShare(accessCode: String): ApiResult<Unit> = api.revokeQuickShare(accessCode)
}
