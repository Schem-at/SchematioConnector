package io.schemat.connector.core.modapi.dto

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.schemat.connector.core.json.asStringOrNull
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetBoolean
import io.schemat.connector.core.json.safeGetDoubleOrNull
import io.schemat.connector.core.json.safeGetLong
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString

/**
 * Community sub-resource DTOs: members, invitations, and the community tag tree.
 * Shapes per `docs/api/mod-namespace.md` (schemati repo).
 */

/** `data` entry from `GET /mod/communities/{slug}/members`. */
data class MemberInfo(
    val id: String,
    val name: String,
    val joinedAt: String?,
    val isOwner: Boolean,
    val roles: List<RoleInfo>,
) {
    companion object {
        fun fromJson(json: JsonObject): MemberInfo? {
            val id = json.safeGetString("id") ?: return null
            return MemberInfo(
                id = id,
                name = json.safeGetString("name") ?: "",
                joinedAt = json.safeGetString("joined_at"),
                isOwner = json.safeGetBoolean("is_owner", false),
                roles = json.safeGetArray("roles")
                    .mapNotNull { if (it.isJsonObject) RoleInfo.fromJson(it.asJsonObject) else null },
            )
        }
    }
}

/** `invitations` entry from `GET /mod/invitations`. Invitation ids are numeric. */
data class InvitationInfo(
    val id: Long,
    val communitySlug: String,
    val communityName: String,
    val invitedBy: String?,
    val message: String?,
    val expiresAt: String?,
) {
    companion object {
        fun fromJson(json: JsonObject): InvitationInfo? {
            val idElement = json.get("id")
            if (idElement == null || !idElement.isJsonPrimitive || !idElement.asJsonPrimitive.isNumber) return null
            val community = json.safeGetObject("community")
            return InvitationInfo(
                id = json.safeGetLong("id"),
                communitySlug = community.safeGetString("slug") ?: "",
                communityName = community.safeGetString("name") ?: "",
                invitedBy = json.safeGetString("invited_by"),
                message = json.safeGetString("message"),
                expiresAt = json.safeGetString("expires_at"),
            )
        }
    }
}

/**
 * Tag filter **definition** carried by tag tree nodes (`filters` entries):
 * `{ id, name, type, min_value, max_value, enum_values, is_required, default_value, unit }`.
 *
 * Filter ids are integers (not UUIDs). [type] is one of `int`, `float`, `bool`, `enum`;
 * [enumValues] is non-empty only for `enum` filters (`enum_values` is `null` otherwise).
 * Optional-safe: every key except `id` may be absent (older servers omit `filters`
 * entirely, which [TagNode.filters] already defaults).
 */
data class TagFilterDef(
    val id: Long,
    val name: String,
    val type: String,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val enumValues: List<String> = emptyList(),
    val isRequired: Boolean = false,
    val defaultValue: String? = null,
    val unit: String? = null,
) {

    /**
     * Client-side check mirroring the server's per-filter validation
     * (`tag_filters.<id>` 422 rules): int/float must be numeric and within
     * [minValue]..[maxValue], bool must be one of `1/0/true/false`, enum must be one
     * of [enumValues]. Returns null when [value] is acceptable, else a user-facing
     * message. Unknown types are accepted (forward compatibility - let the server decide).
     */
    fun validate(value: String): String? = when (type) {
        "int" -> {
            val parsed = value.trim().toLongOrNull()
            when {
                parsed == null -> "$name must be an integer"
                else -> rangeError(parsed.toDouble())
            }
        }
        "float" -> {
            val parsed = value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            when {
                parsed == null -> "$name must be a number"
                else -> rangeError(parsed)
            }
        }
        "bool" -> if (value.trim().lowercase() in BOOL_VALUES) null else "$name must be true or false"
        "enum" -> if (value in enumValues) null else "$name must be one of: ${enumValues.joinToString(", ")}"
        else -> null
    }

    private fun rangeError(value: Double): String? = when {
        minValue != null && value < minValue -> "$name must be at least ${trimNumber(minValue)}"
        maxValue != null && value > maxValue -> "$name must be at most ${trimNumber(maxValue)}"
        else -> null
    }

    companion object {
        private val BOOL_VALUES = setOf("1", "0", "true", "false")

        /** "20" instead of "20.0" for whole-number bounds in messages. */
        private fun trimNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        /**
         * Defensive `enum_values` parsing. The expected shape is a JSON array of
         * strings, but older/buggy servers stored the list as a single comma-joined
         * string (sometimes wrapped in literal quote characters, e.g. `"1.15`).
         * Accepts both shapes; every option is trimmed, stripped of stray
         * surrounding quotes, and blanks are dropped.
         */
        internal fun parseEnumValues(element: JsonElement?): List<String> {
            val raw: List<String> = when {
                element == null || element.isJsonNull -> emptyList()
                element.isJsonArray -> element.asJsonArray.mapNotNull { it.asStringOrNull() }
                element.isJsonPrimitive -> element.asStringOrNull()?.split(',') ?: emptyList()
                else -> emptyList()
            }
            return raw
                .map { it.trim().trim('"', '\'').trim() }
                .filter { it.isNotEmpty() }
        }

        fun fromJson(json: JsonObject): TagFilterDef? {
            val idElement = json.get("id")
            if (idElement == null || !idElement.isJsonPrimitive || !idElement.asJsonPrimitive.isNumber) return null
            return TagFilterDef(
                id = json.safeGetLong("id"),
                name = json.safeGetString("name") ?: "",
                type = json.safeGetString("type") ?: "",
                minValue = json.safeGetDoubleOrNull("min_value"),
                maxValue = json.safeGetDoubleOrNull("max_value"),
                enumValues = parseEnumValues(json.get("enum_values")),
                isRequired = json.safeGetBoolean("is_required", false),
                defaultValue = json.safeGetString("default_value"),
                unit = json.safeGetString("unit"),
            )
        }
    }
}

/**
 * Node of a tag tree from `GET /mod/communities/{slug}/tags` or `GET /mod/tags`:
 * `{ id, name, color, scope, is_manually_assignable, filters: [<filter>], children: [<node>] }`,
 * recursive.
 *
 * [isManuallyAssignable] is optional-safe: payloads without the key (community tag
 * endpoints, older servers) default to assignable. `false` marks category-only nodes
 * the mod must not let the user assign directly.
 *
 * [filters] carries the tag's [TagFilterDef] definitions to render as inputs when the
 * tag is selected; empty for payloads without the key (older servers).
 */
data class TagNode(
    val id: String,
    val name: String,
    val color: String?,
    val scope: String?,
    val children: List<TagNode>,
    val isManuallyAssignable: Boolean = true,
    val filters: List<TagFilterDef> = emptyList(),
) {
    companion object {
        fun fromJson(json: JsonObject): TagNode? {
            val id = json.safeGetString("id") ?: return null
            return TagNode(
                id = id,
                name = json.safeGetString("name") ?: "",
                color = json.safeGetString("color"),
                scope = json.safeGetString("scope"),
                children = json.safeGetArray("children")
                    .mapNotNull { if (it.isJsonObject) fromJson(it.asJsonObject) else null },
                isManuallyAssignable = json.safeGetBoolean("is_manually_assignable", true),
                filters = json.safeGetArray("filters")
                    .mapNotNull { if (it.isJsonObject) TagFilterDef.fromJson(it.asJsonObject) else null },
            )
        }
    }
}
