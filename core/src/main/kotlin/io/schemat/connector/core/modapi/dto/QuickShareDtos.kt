package io.schemat.connector.core.modapi.dto

import com.google.gson.JsonObject
import io.schemat.connector.core.json.safeGetBoolean
import io.schemat.connector.core.json.safeGetInt
import io.schemat.connector.core.json.safeGetLong
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString

/**
 * Quick-share DTO for `/api/v1/mod/quick-shares` (see `docs/api/mod-namespace.md`).
 *
 * One type covers both payload variants: the store/show responses (full detail with
 * `web_url`, `api_url`, `created_by_player`, ...) and the list entries (`is_active`,
 * `current_uses`, `has_data`). Fields absent from a given variant fall back to defaults.
 */
data class QuickShareInfo(
    val id: Long,
    val accessCode: String,
    val name: String?,
    val description: String?,
    val format: String,
    val webUrl: String?,
    val apiUrl: String?,
    val expiresAt: String?,
    val hasPassword: Boolean,
    val hasWhitelist: Boolean,
    val limitType: String,
    val maxUses: Int?,
    val isActive: Boolean,
    val currentUses: Int,
    val hasData: Boolean,
    val createdByPlayer: PlayerProfile?,
) {
    companion object {
        fun fromJson(json: JsonObject): QuickShareInfo? {
            val accessCode = json.safeGetString("access_code") ?: return null
            return QuickShareInfo(
                id = json.safeGetLong("id", 0L),
                accessCode = accessCode,
                name = json.safeGetString("name"),
                description = json.safeGetString("description"),
                format = json.safeGetString("format") ?: "schem",
                webUrl = json.safeGetString("web_url"),
                apiUrl = json.safeGetString("api_url"),
                expiresAt = json.safeGetString("expires_at"),
                hasPassword = json.safeGetBoolean("has_password", false),
                hasWhitelist = json.safeGetBoolean("has_whitelist", false),
                limitType = json.safeGetString("limit_type") ?: "unlimited",
                maxUses = if (json.has("max_uses")) json.safeGetInt("max_uses", -1).takeIf { it >= 0 } else null,
                isActive = json.safeGetBoolean("is_active", true),
                currentUses = json.safeGetInt("current_uses", 0),
                hasData = json.safeGetBoolean("has_data", false),
                createdByPlayer = json.safeGetObject("created_by_player")?.let { PlayerProfile.fromJson(it) },
            )
        }
    }
}
