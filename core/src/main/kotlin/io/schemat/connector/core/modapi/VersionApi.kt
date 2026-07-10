package io.schemat.connector.core.modapi

import com.google.gson.JsonObject
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetBoolean
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.modapi.dto.Page
import io.schemat.connector.core.modapi.dto.PageMeta
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.MultipartFile
import io.schemat.connector.core.modapi.transport.MultipartRequest
import io.schemat.connector.core.modapi.transport.TransportException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A branch of a versioned schematic (plugin version endpoints). */
data class BranchInfo(
    val id: String,
    val name: String,
    val headVersionId: String?,
    val isDefault: Boolean,
) {
    companion object {
        fun fromJson(json: JsonObject): BranchInfo? {
            val id = json.safeGetString("id") ?: return null
            return BranchInfo(
                id = id,
                name = json.safeGetString("name") ?: id,
                headVersionId = json.safeGetString("head_version_id"),
                isDefault = json.safeGetBoolean("is_default"),
            )
        }
    }
}

/** One committed version (SchematicVersionResource shape, parsed defensively). */
data class VersionInfo(
    val id: String,
    val branchId: String?,
    val message: String?,
    val authorName: String?,
    val authorUuid: String?,
    val createdAt: String?,
) {
    companion object {
        fun fromJson(json: JsonObject): VersionInfo? {
            val id = json.safeGetString("id") ?: return null
            val author = json.safeGetObject("author")
            return VersionInfo(
                id = id,
                branchId = json.safeGetString("branch_id"),
                message = json.safeGetString("message"),
                authorName = author.safeGetString("last_seen_name") ?: author.safeGetString("name"),
                authorUuid = author.safeGetString("uuid"),
                createdAt = json.safeGetString("created_at"),
            )
        }
    }
}

/**
 * Outcome of a commit attempt, mapping the spec's 409 contract: the branch HEAD moving
 * past `expected_head_version_id` is an expected, resolvable state (conflict resolution
 * flow), not a generic error.
 */
sealed class CommitResult {
    /** Commit accepted; [version] is the newly created HEAD. */
    data class Ok(val version: VersionInfo) : CommitResult()

    /** 409 `{error:"head_moved", head: ...}` - the branch HEAD is now [newHead]. */
    data class HeadMoved(val newHead: VersionInfo) : CommitResult()

    /** Any other failure (offline, auth, validation, quota, plain conflict...). */
    data class Error(val error: ApiError) : CommitResult()
}

/**
 * Client for the schemati plugin version endpoints (`/plugin/schematics/...`,
 * community-JWT authenticated like the other plugin routes):
 *
 * - `GET  plugin/schematics/{id}/branches`
 * - `GET  plugin/schematics/{id}/branches/{branchId}/commits` (paginated)
 * - `GET  plugin/schematics/{id}/versions/{versionId}/download`
 * - `POST plugin/schematics/{id}/branches/{branchId}/commits` (multipart, 409 head_moved contract)
 *
 * Community tokens are fixed at configuration time, so unlike [SchematioApi] there is
 * no 401 re-authentication hook.
 *
 * @param tokenProvider returns the current community JWT (or null when unconfigured)
 */
class VersionApi(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
) {

    // ---- internal plumbing (mirrors SchematioApi.raw/rawBinary, sans reauth) ----

    private suspend fun raw(request: ApiRequest): ApiResult<JsonObject> {
        val response = try {
            transport.execute(request, tokenProvider())
        } catch (e: TransportException) {
            return ApiResult.Failure(ApiError.Offline)
        }
        if (!response.isSuccess) return ApiResult.Failure(ApiError.fromResponse(response))
        val json = parseJsonSafe(response.bodyAsString()) ?: JsonObject()
        return ApiResult.Success(json)
    }

    private fun <T> ApiResult<JsonObject>.parsedWith(parse: (JsonObject) -> T?): ApiResult<T> = when (this) {
        is ApiResult.Success -> parse(value)
            ?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError.Unexpected(200, "Malformed response body"))
        is ApiResult.Failure -> this
    }

    /** URL-encode a single path segment (HttpTransport.buildUrl only encodes the query string). */
    private fun enc(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    /** Version resources arrive wrapped (`{data: {...}}`, Laravel resource default) or flat. */
    private fun JsonObject.versionResource(): VersionInfo? =
        safeGetObject("data")?.let { VersionInfo.fromJson(it) } ?: VersionInfo.fromJson(this)

    // ---- endpoints ----

    /** `GET /plugin/schematics/{id}/branches` - all branches of a versioned schematic. */
    suspend fun branches(schematicId: String): ApiResult<List<BranchInfo>> =
        raw(ApiRequest(HttpMethod.GET, "/plugin/schematics/${enc(schematicId)}/branches"))
            .parsedWith { json ->
                json.safeGetArray("data")
                    .mapNotNull { if (it.isJsonObject) BranchInfo.fromJson(it.asJsonObject) else null }
            }

    /** `GET /plugin/schematics/{id}/branches/{branchId}/commits` - paginated version history. */
    suspend fun commits(schematicId: String, branchId: String, page: Int = 1): ApiResult<Page<VersionInfo>> =
        raw(
            ApiRequest(
                HttpMethod.GET,
                "/plugin/schematics/${enc(schematicId)}/branches/${enc(branchId)}/commits",
                query = mapOf("page" to page.toString()),
            ),
        ).parsedWith { json ->
            Page(
                items = json.safeGetArray("data")
                    .mapNotNull { if (it.isJsonObject) VersionInfo.fromJson(it.asJsonObject) else null },
                meta = PageMeta.fromJson(json.safeGetObject("meta")),
            )
        }

    /** `GET /plugin/schematics/{id}/versions/{versionId}/download` - raw schematic bytes. */
    suspend fun downloadVersion(schematicId: String, versionId: String): ApiResult<ByteArray> {
        val request = ApiRequest(
            HttpMethod.GET,
            "/plugin/schematics/${enc(schematicId)}/versions/${enc(versionId)}/download",
        )
        val response = try {
            transport.execute(request, tokenProvider())
        } catch (e: TransportException) {
            return ApiResult.Failure(ApiError.Offline)
        }
        if (!response.isSuccess) return ApiResult.Failure(ApiError.fromResponse(response))
        return ApiResult.Success(response.body ?: ByteArray(0))
    }

    /**
     * `POST /plugin/schematics/{id}/branches/{branchId}/commits` - multipart commit.
     *
     * When the branch HEAD no longer equals [expectedHeadVersionId] the server answers
     * 409 `{error:"head_moved", head: <version resource>}`, surfaced as
     * [CommitResult.HeadMoved] so callers can start the conflict-resolution flow.
     * [expectedHeadVersionId] null means "no expectation" (caller should normally
     * resolve the current head first and pass it).
     */
    suspend fun commit(
        schematicId: String,
        branchId: String,
        schematicBytes: ByteArray,
        message: String,
        expectedHeadVersionId: String?,
        playerUuid: String,
        fileName: String = "$schematicId.schem",
    ): CommitResult {
        val fields = buildList {
            add("message" to message)
            add("player_uuid" to playerUuid)
            expectedHeadVersionId?.let { add("expected_head_version_id" to it) }
        }
        val request = ApiRequest(
            HttpMethod.POST,
            "/plugin/schematics/${enc(schematicId)}/branches/${enc(branchId)}/commits",
            multipart = MultipartRequest(
                fields = fields,
                files = listOf(MultipartFile("file", fileName, "application/octet-stream", schematicBytes)),
            ),
        )
        val response = try {
            transport.execute(request, tokenProvider())
        } catch (e: TransportException) {
            return CommitResult.Error(ApiError.Offline)
        }
        val json = parseJsonSafe(response.bodyAsString())
        if (response.status == 409 && json.safeGetString("error") == "head_moved") {
            val head = json.safeGetObject("head")?.let { VersionInfo.fromJson(it) }
                ?: return CommitResult.Error(ApiError.Unexpected(409, "head_moved response without head resource"))
            return CommitResult.HeadMoved(head)
        }
        if (!response.isSuccess) return CommitResult.Error(ApiError.fromResponse(response))
        val version = (json ?: JsonObject()).versionResource()
            ?: return CommitResult.Error(ApiError.Unexpected(response.status, "Malformed commit response body"))
        return CommitResult.Ok(version)
    }
}
