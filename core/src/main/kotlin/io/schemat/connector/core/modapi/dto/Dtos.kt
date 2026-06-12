package io.schemat.connector.core.modapi.dto

import com.google.gson.JsonObject
import io.schemat.connector.core.json.asBooleanOrDefault
import io.schemat.connector.core.json.asIntOrDefault
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetInt
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString

/**
 * Core identity/community DTOs for the `/api/v1/mod` namespace.
 *
 * All parsers are tolerant of missing/null/mistyped keys (see `docs/api/mod-namespace.md`
 * in the schemati repo for the contract): required identity keys return `null` from
 * `fromJson`, everything else falls back to a safe default.
 */

/**
 * A community role as returned in `roles` arrays (`/mod/me`, `/mod/communities`, members)
 * and as a full definition from `GET /mod/communities/{slug}/roles`. The definition-only
 * keys (`is_system`, `permissions`, `position`) are optional - older payloads omit them
 * and fall back to the defaults.
 */
data class RoleInfo(
    val id: String,
    val name: String,
    val color: String?,
    val isSystem: Boolean = false,
    val permissions: List<String> = emptyList(),
    val position: Int = 0,
) {
    companion object {
        fun fromJson(json: JsonObject): RoleInfo? {
            val id = json.safeGetString("id") ?: return null
            return RoleInfo(
                id = id,
                name = json.safeGetString("name") ?: "",
                color = json.safeGetString("color"),
                isSystem = json.get("is_system").asBooleanOrDefault(false),
                permissions = json.safeGetArray("permissions")
                    .mapNotNull { if (it.isJsonPrimitive) it.asString else null },
                position = json.get("position").asIntOrDefault(0),
            )
        }
    }
}

/** `player` object from `GET /mod/me` (also `created_by_player` on quick shares). */
data class PlayerProfile(val id: String, val name: String) {
    companion object {
        fun fromJson(json: JsonObject): PlayerProfile? {
            val id = json.safeGetString("id") ?: return null
            return PlayerProfile(id, json.safeGetString("name") ?: "")
        }
    }
}

/**
 * Community payload from `GET /mod/communities` / `GET /mod/communities/{slug}`.
 * The `/mod/me` variant omits `description`, `member_count` and `is_member` -
 * those default safely.
 */
data class CommunitySummary(
    val id: String,
    val slug: String,
    val name: String,
    val description: String?,
    val isPublic: Boolean,
    val memberCount: Int?,
    val isMember: Boolean,
    val permissions: Set<String>,
    val roles: List<RoleInfo>,
) {
    /** True when the caller holds the given effective community permission (e.g. `manage-tags`). */
    fun can(permission: String): Boolean = permission in permissions

    companion object {
        fun fromJson(json: JsonObject): CommunitySummary? {
            val slug = json.safeGetString("slug") ?: return null
            return CommunitySummary(
                id = json.safeGetString("id") ?: "",
                slug = slug,
                name = json.safeGetString("name") ?: slug,
                description = json.safeGetString("description"),
                isPublic = json.get("is_public").asBooleanOrDefault(true),
                memberCount = if (json.has("member_count")) json.safeGetInt("member_count", -1).takeIf { it >= 0 } else null,
                isMember = json.get("is_member").asBooleanOrDefault(true),
                permissions = json.safeGetArray("permissions")
                    .mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                    .toSet(),
                roles = json.safeGetArray("roles")
                    .mapNotNull { if (it.isJsonObject) RoleInfo.fromJson(it.asJsonObject) else null },
            )
        }
    }
}

/** Full `GET /mod/me` response: caller identity + memberships + pending invitation count. */
data class MeSnapshot(
    val player: PlayerProfile?,
    val communities: List<CommunitySummary>,
    val pendingInvitations: Int,
) {
    companion object {
        fun fromJson(json: JsonObject): MeSnapshot = MeSnapshot(
            player = json.safeGetObject("player")?.let { PlayerProfile.fromJson(it) },
            communities = json.safeGetArray("communities")
                .mapNotNull { if (it.isJsonObject) CommunitySummary.fromJson(it.asJsonObject) else null },
            pendingInvitations = json.get("pending_invitations").asIntOrDefault(0),
        )
    }
}

/** Laravel paginator `meta` block (`current_page`, `last_page`, `per_page`, `total`). */
data class PageMeta(val currentPage: Int, val lastPage: Int, val perPage: Int, val total: Int) {
    companion object {
        fun fromJson(json: JsonObject?): PageMeta = PageMeta(
            currentPage = json?.get("current_page").asIntOrDefault(1),
            lastPage = json?.get("last_page").asIntOrDefault(1),
            perPage = json?.get("per_page").asIntOrDefault(0),
            total = json?.get("total").asIntOrDefault(0),
        )
    }
}

/** One page of a paginated listing. */
data class Page<T>(val items: List<T>, val meta: PageMeta) {
    val hasMore: Boolean get() = meta.currentPage < meta.lastPage
}
