package io.schemat.connector.core.modapi

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.InvitationInfo
import io.schemat.connector.core.modapi.dto.MeSnapshot
import io.schemat.connector.core.modapi.dto.MemberInfo
import io.schemat.connector.core.modapi.dto.Page
import io.schemat.connector.core.modapi.dto.PageMeta
import io.schemat.connector.core.modapi.dto.QuickShareInfo
import io.schemat.connector.core.modapi.dto.RoleInfo
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.MultipartFile
import io.schemat.connector.core.modapi.transport.MultipartRequest
import io.schemat.connector.core.modapi.transport.TransportException
import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * One stored-value constraint of a browse query, applied AND-wise across filters
 * (`SchematicService::applyFilterValueConstraints`). Schematics without a stored value
 * for a constrained filter are excluded.
 */
sealed class FilterConstraint {
    /**
     * `filter[<filterId>]=value` - literal string match against the stored value
     * (enum/bool filters; bool values are stored normalized as "1"/"0").
     */
    data class Exact(val filterId: Long, val value: String) : FilterConstraint()

    /**
     * `filter_min[<filterId>]` / `filter_max[<filterId>]` - inclusive numeric range on
     * the stored value (int/float filters). Null bounds are omitted; a fully-null range
     * sends nothing.
     */
    data class Range(val filterId: Long, val min: Double?, val max: Double?) : FilterConstraint()
}

/**
 * Listing filters shared by the schematic browse endpoints
 * (`GET /mod/communities/{slug}/schematics` and the general `GET /schematics` index).
 *
 * Null [search]/[tag] and an empty [tags] are omitted from the query string. Prefer
 * [tags] (comma-separated ids, AND logic - the schematic must carry every listed tag);
 * the single [tag] is kept for back-compat. [filterConstraints] become bracketed
 * `filter[<id>]` / `filter_min[<id>]` / `filter_max[<id>]` params (see [FilterConstraint]).
 *
 * [sort] values accepted by the community endpoint: `created_at`, `updated_at`, `name`,
 * `downloads_count`; the general index maps `downloads` to `downloads_count` and falls
 * back to `created_at` for anything unknown. [perPage] is clamped server-side (1-50).
 */
data class BrowseQuery(
    val search: String? = null,
    val tag: String? = null,
    val tags: List<String> = emptyList(),
    val sort: String = "created_at",
    val order: String = "desc",
    val page: Int = 1,
    val perPage: Int = 20,
    val filterConstraints: List<FilterConstraint> = emptyList(),
) {
    internal fun toQueryMap(): Map<String, String> = buildMap {
        search?.let { put("search", it) }
        tag?.let { put("tag", it) }
        if (tags.isNotEmpty()) put("tags", tags.joinToString(","))
        filterConstraints.forEach { constraint ->
            when (constraint) {
                is FilterConstraint.Exact -> put("filter[${constraint.filterId}]", constraint.value)
                is FilterConstraint.Range -> {
                    constraint.min?.let { put("filter_min[${constraint.filterId}]", formatNumber(it)) }
                    constraint.max?.let { put("filter_max[${constraint.filterId}]", formatNumber(it)) }
                }
            }
        }
        put("sort", sort)
        put("order", order)
        put("page", page.toString())
        put("per_page", perPage.toString())
    }

    private companion object {
        /** "20" instead of "20.0" for whole-number bounds (server casts to REAL either way). */
        fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()
    }
}

/**
 * Inputs for `POST /schematics` (multipart). Field names mirror `SchematicStoreRequest::rules()`
 * in the backend: name, description, author_id, format, is_public, tags[], co_authors[],
 * community_id + file parts schematic_file / preview_image.
 *
 * equals/hashCode are overridden because the generated ones would compare the
 * [ByteArray] properties by identity (same pattern as [MultipartFile]).
 */
data class UploadRequest(
    val name: String,
    val description: String,
    /** Must equal the JWT subject - the server rejects mismatches with 403. */
    val authorId: String,
    val schematicBytes: ByteArray,
    /** e.g. "build.litematic" */
    val schematicFileName: String,
    /** `preview_image` is REQUIRED by the API (max 5 MB, must be an image). */
    val previewImagePng: ByteArray,
    val format: String = "litematic",
    val isPublic: Boolean = true,
    val tagIds: List<String> = emptyList(),
    /**
     * `tag_filters` map of tag filter id → value. Each filter must belong to one of
     * [tagIds] and the value must satisfy the filter's type/range/enum rules (deep
     * 422-validated server-side before the schematic is created). Bool values are
     * normalized server-side to "1"/"0". Encoded as repeated `tag_filters[<id>]`
     * multipart fields (PHP assembles those into the assoc array the backend reads).
     */
    val tagFilters: Map<Long, String> = emptyMap(),
    /**
     * Server caps `co_authors` at [SchematioApi.MAX_CO_AUTHORS] (10) entries; larger
     * lists fail fast as [ApiError.Validation] without touching the network.
     */
    val coAuthorIds: List<String> = emptyList(),
    /** The author must be an active member of the community (403 otherwise). */
    val communityId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UploadRequest) return false
        return name == other.name &&
            description == other.description &&
            authorId == other.authorId &&
            schematicBytes.contentEquals(other.schematicBytes) &&
            schematicFileName == other.schematicFileName &&
            previewImagePng.contentEquals(other.previewImagePng) &&
            format == other.format &&
            isPublic == other.isPublic &&
            tagIds == other.tagIds &&
            tagFilters == other.tagFilters &&
            coAuthorIds == other.coAuthorIds &&
            communityId == other.communityId
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + authorId.hashCode()
        result = 31 * result + schematicBytes.contentHashCode()
        result = 31 * result + schematicFileName.hashCode()
        result = 31 * result + previewImagePng.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + isPublic.hashCode()
        result = 31 * result + tagIds.hashCode()
        result = 31 * result + tagFilters.hashCode()
        result = 31 * result + coAuthorIds.hashCode()
        result = 31 * result + (communityId?.hashCode() ?: 0)
        return result
    }
}

/**
 * Inputs for `POST /mod/quick-shares`. Field names mirror `QuickShareController::store()`
 * validation: schematic_data (base64), name, format, expires_in, password, max_uses.
 *
 * Same equals/hashCode rationale as [UploadRequest].
 */
data class QuickShareRequest(
    val schematicBytes: ByteArray,
    val name: String,
    val format: String = "litematic",
    /** Server range 60..2_592_000 (30 days); server default is 7 days. */
    val expiresInSeconds: Int = 86_400,
    val password: String? = null,
    /**
     * When set, `limit_type: "total"` is sent alongside `max_uses` - the server's
     * `limit_type` defaults to `unlimited`, under which `max_uses` would be stored
     * but never enforced.
     */
    val maxUses: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuickShareRequest) return false
        return schematicBytes.contentEquals(other.schematicBytes) &&
            name == other.name &&
            format == other.format &&
            expiresInSeconds == other.expiresInSeconds &&
            password == other.password &&
            maxUses == other.maxUses
    }

    override fun hashCode(): Int {
        var result = schematicBytes.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + expiresInSeconds
        result = 31 * result + (password?.hashCode() ?: 0)
        result = 31 * result + (maxUses?.hashCode() ?: 0)
        return result
    }
}

/**
 * Typed client for the schemat.io /mod API (contract: docs/api/mod-namespace.md in the backend repo).
 *
 * All user-supplied path segments (slugs, uuids, tag ids) are URL-encoded defensively via [enc]
 * because [io.schemat.connector.core.modapi.transport.HttpTransport.buildUrl] only encodes the query string.
 *
 * @param tokenProvider returns the current player JWT (or null when signed out)
 * @param reauthenticate invoked once on a 401 - should refresh the session and return true on success
 */
class SchematioApi(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val reauthenticate: suspend () -> Boolean = { false },
) {

    companion object {
        /** Server-side cap on the `preview_image` multipart field (5 MB). */
        const val MAX_PREVIEW_IMAGE_BYTES = 5 * 1024 * 1024

        /** Server-side cap on the `co_authors` array (`max:10`). */
        const val MAX_CO_AUTHORS = 10
    }

    // ---- internal plumbing ----

    private suspend fun raw(request: ApiRequest, allowRetry: Boolean = true): ApiResult<JsonObject> {
        val response = try {
            transport.execute(request, tokenProvider())
        } catch (e: TransportException) {
            return ApiResult.Failure(ApiError.Offline)
        }
        if (response.status == 401 && allowRetry && reauthenticate()) {
            return raw(request, allowRetry = false)
        }
        if (!response.isSuccess) return ApiResult.Failure(ApiError.fromResponse(response))
        val json = parseJsonSafe(response.bodyAsString()) ?: JsonObject()
        return ApiResult.Success(json)
    }

    /** [raw] sibling for binary endpoints: no JSON parse on success, same Offline/401-retry/error mapping. */
    private suspend fun rawBinary(request: ApiRequest, allowRetry: Boolean = true): ApiResult<ByteArray> {
        val response = try {
            transport.execute(request, tokenProvider())
        } catch (e: TransportException) {
            return ApiResult.Failure(ApiError.Offline)
        }
        if (response.status == 401 && allowRetry && reauthenticate()) {
            return rawBinary(request, allowRetry = false)
        }
        if (!response.isSuccess) return ApiResult.Failure(ApiError.fromResponse(response))
        return ApiResult.Success(response.body ?: ByteArray(0))
    }

    /** Maps a successful raw response through [parse]; a null parse result becomes [ApiError.Unexpected]. */
    private fun <T> ApiResult<JsonObject>.parsedWith(parse: (JsonObject) -> T?): ApiResult<T> = when (this) {
        is ApiResult.Success -> parse(value)
            ?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError.Unexpected(200, "Malformed response body"))
        is ApiResult.Failure -> this
    }

    private suspend fun <T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
        parse: (JsonObject) -> T?,
    ): ApiResult<T> = raw(ApiRequest(HttpMethod.GET, path, query)).parsedWith(parse)

    private suspend fun <T> post(path: String, body: JsonObject? = null, parse: (JsonObject) -> T?): ApiResult<T> =
        raw(ApiRequest(HttpMethod.POST, path, jsonBody = body?.toString())).parsedWith(parse)

    private suspend fun <T> put(path: String, body: JsonObject? = null, parse: (JsonObject) -> T?): ApiResult<T> =
        raw(ApiRequest(HttpMethod.PUT, path, jsonBody = body?.toString())).parsedWith(parse)

    private suspend fun <T> patch(path: String, body: JsonObject? = null, parse: (JsonObject) -> T?): ApiResult<T> =
        raw(ApiRequest(HttpMethod.PATCH, path, jsonBody = body?.toString())).parsedWith(parse)

    private suspend fun <T> delete(path: String, parse: (JsonObject) -> T?): ApiResult<T> =
        raw(ApiRequest(HttpMethod.DELETE, path)).parsedWith(parse)

    /** URL-encode a single path segment (HttpTransport.buildUrl only encodes the query string). */
    private fun enc(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    private fun JsonObject.communityList(key: String): List<CommunitySummary> =
        safeGetArray(key).mapNotNull { if (it.isJsonObject) CommunitySummary.fromJson(it.asJsonObject) else null }

    private fun JsonObject.roleList(key: String): List<RoleInfo> =
        safeGetArray(key).mapNotNull { if (it.isJsonObject) RoleInfo.fromJson(it.asJsonObject) else null }

    private fun JsonObject.tagNodeList(key: String): List<TagNode> =
        safeGetArray(key).mapNotNull { if (it.isJsonObject) TagNode.fromJson(it.asJsonObject) else null }

    private fun JsonObject.schematicPage(): Page<SchematicSummary> = Page(
        items = safeGetArray("data")
            .mapNotNull { if (it.isJsonObject) SchematicSummary.fromJson(it.asJsonObject) else null },
        meta = PageMeta.fromJson(safeGetObject("meta")),
    )

    /** Detail/update responses wrap the resource in Laravel's default `data` envelope. */
    private fun JsonObject.schematicDetail(): SchematicDetail? =
        safeGetObject("data")?.let { SchematicDetail.fromJson(it) }

    /** The caller's player uuid, decoded from the current JWT's `sub` claim (null when signed out/opaque). */
    private fun currentPlayerUuid(): String? =
        tokenProvider()?.let { PlayerSession.fromToken(it)?.playerUuid }

    // ---- identity ----

    /** `GET /mod/me` - caller identity + active community memberships + pending invitation count. */
    suspend fun me(): ApiResult<MeSnapshot> = get("/mod/me") { MeSnapshot.fromJson(it) }

    // ---- communities ----

    /** `GET /mod/communities` - communities the caller is an active member of. */
    suspend fun communities(): ApiResult<List<CommunitySummary>> =
        get("/mod/communities") { it.communityList("communities") }

    /** `GET /mod/communities/{slug}` - 404 for private communities the caller is not in. */
    suspend fun community(slug: String): ApiResult<CommunitySummary> =
        get("/mod/communities/${enc(slug)}") { json ->
            json.safeGetObject("community")?.let { CommunitySummary.fromJson(it) }
        }

    /**
     * `GET /mod/communities/{slug}/roles` - the community's role definitions (system + custom),
     * ordered by `position`. Any member may list them; no extra permission required.
     */
    suspend fun communityRoles(slug: String): ApiResult<List<RoleInfo>> =
        get("/mod/communities/${enc(slug)}/roles") { it.roleList("roles") }

    // ---- members ----

    /** `GET /mod/communities/{slug}/members` - paginated, `per_page` clamped 1-100 server-side. */
    suspend fun communityMembers(slug: String, page: Int = 1, perPage: Int = 25): ApiResult<Page<MemberInfo>> =
        get(
            "/mod/communities/${enc(slug)}/members",
            query = mapOf("page" to page.toString(), "per_page" to perPage.toString()),
        ) { json ->
            Page(
                items = json.safeGetArray("data")
                    .mapNotNull { if (it.isJsonObject) MemberInfo.fromJson(it.asJsonObject) else null },
                meta = PageMeta.fromJson(json.safeGetObject("meta")),
            )
        }

    /** `DELETE /mod/communities/{slug}/members/{playerUuid}` - kick (requires `manage-members`). */
    suspend fun kickMember(slug: String, playerUuid: String): ApiResult<Unit> =
        delete("/mod/communities/${enc(slug)}/members/${enc(playerUuid)}") { }

    /**
     * `PUT /mod/communities/{slug}/members/{playerUuid}/roles` - replaces the target's non-system
     * roles with exactly [roleIds] (requires `manage-roles`). Returns the resulting full role set.
     */
    suspend fun syncMemberRoles(slug: String, playerUuid: String, roleIds: List<String>): ApiResult<List<RoleInfo>> {
        val body = JsonObject().apply {
            add("role_ids", JsonArray().apply { roleIds.forEach { add(it) } })
        }
        return put("/mod/communities/${enc(slug)}/members/${enc(playerUuid)}/roles", body) { it.roleList("roles") }
    }

    // ---- invitations ----

    /** `GET /mod/invitations` - pending invitations addressed to the caller. */
    suspend fun invitations(): ApiResult<List<InvitationInfo>> =
        get("/mod/invitations") { json ->
            json.safeGetArray("invitations")
                .mapNotNull { if (it.isJsonObject) InvitationInfo.fromJson(it.asJsonObject) else null }
        }

    /** `POST /mod/invitations/{id}/accept` - returns the joined community's slug. */
    suspend fun acceptInvitation(id: Long): ApiResult<String> =
        post("/mod/invitations/$id/accept") { it.safeGetString("community_slug") }

    /** `POST /mod/invitations/{id}/decline` */
    suspend fun declineInvitation(id: Long): ApiResult<Unit> =
        post("/mod/invitations/$id/decline") { }

    // ---- membership ----

    /** `DELETE /mod/communities/{slug}/membership` - leave (422 for owners). */
    suspend fun leaveCommunity(slug: String): ApiResult<Unit> =
        delete("/mod/communities/${enc(slug)}/membership") { }

    // ---- global tags ----

    /**
     * `GET /mod/tags` - the global "minecraft" tag tree (any authenticated player). The
     * list contains the minecraft root node itself; nodes with `is_manually_assignable`
     * false are category-only and must not be offered for direct assignment.
     */
    suspend fun globalTags(): ApiResult<List<TagNode>> =
        get("/mod/tags") { it.tagNodeList("tags") }

    // ---- community tags ----

    /** `GET /mod/communities/{slug}/tags` - subtree under the community root tag. */
    suspend fun communityTags(slug: String): ApiResult<List<TagNode>> =
        get("/mod/communities/${enc(slug)}/tags") { it.tagNodeList("tags") }

    /** `POST /mod/communities/{slug}/tags` - requires `manage-tags`. Null optionals are omitted. */
    suspend fun createCommunityTag(
        slug: String,
        name: String,
        scope: String,
        color: String? = null,
        parentId: String? = null,
        description: String? = null,
    ): ApiResult<TagNode> {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("scope", scope)
            color?.let { addProperty("color", it) }
            parentId?.let { addProperty("parent_id", it) }
            description?.let { addProperty("description", it) }
        }
        return post("/mod/communities/${enc(slug)}/tags", body) { json ->
            json.safeGetObject("tag")?.let { TagNode.fromJson(it) }
        }
    }

    /** `PATCH /mod/communities/{slug}/tags/{tagId}` - `parent_id` cannot be changed here. */
    suspend fun updateCommunityTag(
        slug: String,
        tagId: String,
        name: String,
        scope: String,
        color: String? = null,
        description: String? = null,
    ): ApiResult<TagNode> {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("scope", scope)
            color?.let { addProperty("color", it) }
            description?.let { addProperty("description", it) }
        }
        return patch("/mod/communities/${enc(slug)}/tags/${enc(tagId)}", body) { json ->
            json.safeGetObject("tag")?.let { TagNode.fromJson(it) }
        }
    }

    /** `DELETE /mod/communities/{slug}/tags/{tagId}` - 422 for the root tag or tags with schematics. */
    suspend fun deleteCommunityTag(slug: String, tagId: String): ApiResult<Unit> =
        delete("/mod/communities/${enc(slug)}/tags/${enc(tagId)}") { }

    // ---- schematics ----

    /**
     * `GET /mod/communities/{slug}/schematics` - schematics attributed to the community.
     * Private community: members only (`404` otherwise). Non-public schematics appear
     * only when the caller is an author.
     */
    suspend fun communitySchematics(slug: String, query: BrowseQuery = BrowseQuery()): ApiResult<Page<SchematicSummary>> =
        get("/mod/communities/${enc(slug)}/schematics", query.toQueryMap()) { it.schematicPage() }

    /**
     * `GET /schematics` - the general authenticated index (`SchematicController::index`).
     * With [mineOnly] the caller's own uuid (JWT `sub`) is sent as the `author` filter,
     * which the server honors for authenticated callers by including their non-public
     * schematics; fails with [ApiError.Unauthorized] when no decodable session exists.
     */
    suspend fun schematics(query: BrowseQuery = BrowseQuery(), mineOnly: Boolean = false): ApiResult<Page<SchematicSummary>> {
        val params = query.toQueryMap().toMutableMap()
        if (mineOnly) {
            val uuid = currentPlayerUuid()
                ?: return ApiResult.Failure(ApiError.Unauthorized("mineOnly requires an authenticated player session"))
            params["author"] = uuid
        }
        return get("/schematics", params) { it.schematicPage() }
    }

    /** `GET /schematics/{id}` - `{id}` may be the uuid, short_id, or slug; `404` when not visible. */
    suspend fun schematic(id: String): ApiResult<SchematicDetail> =
        get("/schematics/${enc(id)}") { it.schematicDetail() }

    /** `PUT /schematics/{id}` - partial update; only non-null fields are sent (`sometimes` rules). */
    suspend fun updateSchematic(
        id: String,
        name: String? = null,
        description: String? = null,
        isPublic: Boolean? = null,
    ): ApiResult<SchematicDetail> {
        val body = JsonObject().apply {
            name?.let { addProperty("name", it) }
            description?.let { addProperty("description", it) }
            isPublic?.let { addProperty("is_public", it) }
        }
        return put("/schematics/${enc(id)}", body) { it.schematicDetail() }
    }

    /** `DELETE /schematics/{id}` - `204` on success, `404` when not visible. */
    suspend fun deleteSchematic(id: String): ApiResult<Unit> =
        delete("/schematics/${enc(id)}") { }

    /**
     * `PUT /schematics/{id}/tags` - full replacement of the schematic's tag set
     * (authors only; community tags need add/remove permission). Returns the resulting
     * flat tag list (`tags: [{id, name}]` - no color/scope/children in this response).
     *
     * [tagFilters] (tag filter id → value) is sent as the `tag_filters` object only when
     * non-empty. Server semantics are FULL REPLACE: when the key is present, ALL stored
     * filter values are deleted and recreated from the map (including values of other
     * tags' filters); an empty map here omits the key entirely, leaving stored values
     * untouched. Bool values are normalized server-side to "1"/"0". Validation failures
     * are atomic 422s (`tag_filters.<id>` field errors, tags not persisted either).
     */
    suspend fun setTags(
        id: String,
        tagIds: List<String>,
        tagFilters: Map<Long, String> = emptyMap(),
    ): ApiResult<List<TagNode>> {
        val body = JsonObject().apply {
            add("tags", JsonArray().apply { tagIds.forEach { add(it) } })
            if (tagFilters.isNotEmpty()) {
                add(
                    "tag_filters",
                    JsonObject().apply {
                        tagFilters.entries.sortedBy { it.key }.forEach { (filterId, value) ->
                            addProperty(filterId.toString(), value)
                        }
                    },
                )
            }
        }
        return put("/schematics/${enc(id)}/tags", body) { it.tagNodeList("tags") }
    }

    /** `POST /schematics/{id}/co-authors` - authors only; idempotent. `422` for unknown players. */
    suspend fun addCoAuthor(id: String, playerUuid: String): ApiResult<Unit> =
        post("/schematics/${enc(id)}/co-authors", JsonObject().apply { addProperty("player_uuid", playerUuid) }) { }

    /** `DELETE /schematics/{id}/co-authors/{playerUuid}` - `422` when removing the last author. */
    suspend fun removeCoAuthor(id: String, playerUuid: String): ApiResult<Unit> =
        delete("/schematics/${enc(id)}/co-authors/${enc(playerUuid)}") { }

    /**
     * `POST /schematics/{id}/download` - streams the (optionally converted) schematic file.
     * Body matches what the fabric litematica bridge sends when loading: `format` always, the caller's
     * `player_uuid` when a session is decodable, `password` when non-blank (both are only
     * consulted for quick-share ids but are harmless for regular schematics).
     */
    suspend fun download(id: String, format: String = "litematic", password: String? = null): ApiResult<ByteArray> {
        val body = JsonObject().apply {
            addProperty("format", format)
            currentPlayerUuid()?.let { addProperty("player_uuid", it) }
            password?.takeIf { it.isNotBlank() }?.let { addProperty("password", it) }
        }
        return rawBinary(ApiRequest(HttpMethod.POST, "/schematics/${enc(id)}/download", jsonBody = body.toString()))
    }

    // ---- upload ----

    /**
     * Pre-flight size check shared by [uploadSchematic]/[createQuickShare]: both endpoints
     * cap the schematic at 10 MB server-side, so oversize payloads fail fast as
     * [ApiError.Validation] (keyed on [field]) without touching the network.
     */
    private fun schematicSizeError(bytes: ByteArray, field: String): ApiError.Validation? =
        (InputValidator.validateSchematicSize(bytes.size) as? ValidationResult.Invalid)
            ?.let { ApiError.Validation(it.message, mapOf(field to listOf(it.message))) }

    /**
     * `POST /schematics` - multipart upload (requires the `upload_schematic` JWT claim).
     * Booleans are sent as "1"/"0" and array fields as repeated `tags[]`/`co_authors[]`
     * keys (the standard multipart encoding for Laravel array inputs).
     */
    suspend fun uploadSchematic(request: UploadRequest): ApiResult<SchematicDetail> {
        schematicSizeError(request.schematicBytes, "schematic_file")?.let { return ApiResult.Failure(it) }
        if (request.previewImagePng.size > MAX_PREVIEW_IMAGE_BYTES) {
            val message = "Preview image too large (max ${MAX_PREVIEW_IMAGE_BYTES / (1024 * 1024)}MB)"
            return ApiResult.Failure(ApiError.Validation(message, mapOf("preview_image" to listOf(message))))
        }
        if (request.coAuthorIds.size > MAX_CO_AUTHORS) {
            val message = "Too many co-authors (max $MAX_CO_AUTHORS)"
            return ApiResult.Failure(ApiError.Validation(message, mapOf("co_authors" to listOf(message))))
        }
        val fields = buildList {
            add("name" to request.name)
            add("description" to request.description)
            add("author_id" to request.authorId)
            add("format" to request.format)
            add("is_public" to if (request.isPublic) "1" else "0")
            request.tagIds.forEach { add("tags[]" to it) }
            // tag_filters[<id>]=value - PHP reads repeated bracketed keys as the assoc array
            // the backend's `tag_filters` validation expects; sorted for determinism.
            request.tagFilters.entries.sortedBy { it.key }.forEach { (filterId, value) ->
                add("tag_filters[$filterId]" to value)
            }
            request.coAuthorIds.forEach { add("co_authors[]" to it) }
            request.communityId?.let { add("community_id" to it) }
        }
        val multipart = MultipartRequest(
            fields = fields,
            files = listOf(
                MultipartFile("schematic_file", request.schematicFileName, "application/octet-stream", request.schematicBytes),
                MultipartFile("preview_image", "preview.png", "image/png", request.previewImagePng),
            ),
        )
        return raw(ApiRequest(HttpMethod.POST, "/schematics", multipart = multipart))
            .parsedWith { it.schematicDetail() }
    }

    // ---- quick shares ----

    private fun JsonObject.quickShare(): QuickShareInfo? =
        safeGetObject("quick_share")?.let { QuickShareInfo.fromJson(it) }

    /** `POST /mod/quick-shares` - `schematic_data` is the base64-encoded schematic (decoded max 10 MB). */
    suspend fun createQuickShare(request: QuickShareRequest): ApiResult<QuickShareInfo> {
        schematicSizeError(request.schematicBytes, "schematic_data")?.let { return ApiResult.Failure(it) }
        val body = JsonObject().apply {
            addProperty("schematic_data", Base64.getEncoder().encodeToString(request.schematicBytes))
            addProperty("name", request.name)
            addProperty("format", request.format)
            addProperty("expires_in", request.expiresInSeconds)
            request.password?.let { addProperty("password", it) }
            request.maxUses?.let {
                addProperty("limit_type", "total")
                addProperty("max_uses", it)
            }
        }
        return post("/mod/quick-shares", body) { it.quickShare() }
    }

    /** `GET /mod/quick-shares` - the caller's shares (list shape: `is_active`/`current_uses`/`has_data`). */
    suspend fun quickShares(): ApiResult<List<QuickShareInfo>> =
        get("/mod/quick-shares") { json ->
            json.safeGetArray("quick_shares")
                .mapNotNull { if (it.isJsonObject) QuickShareInfo.fromJson(it.asJsonObject) else null }
        }

    /** `GET /mod/quick-shares/{accessCode}` - 404 unknown code, 403 when the caller cannot manage it. */
    suspend fun quickShare(accessCode: String): ApiResult<QuickShareInfo> =
        get("/mod/quick-shares/${enc(accessCode)}") { it.quickShare() }

    /** `DELETE /mod/quick-shares/{accessCode}` - same auth rules as show. */
    suspend fun revokeQuickShare(accessCode: String): ApiResult<Unit> =
        delete("/mod/quick-shares/${enc(accessCode)}") { }
}
