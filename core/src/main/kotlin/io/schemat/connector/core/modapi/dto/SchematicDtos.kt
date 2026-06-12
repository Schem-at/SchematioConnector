package io.schemat.connector.core.modapi.dto

import com.google.gson.JsonObject
import io.schemat.connector.core.json.asBooleanOrDefault
import io.schemat.connector.core.json.asStringOrNull
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetObject
import io.schemat.connector.core.json.safeGetString

/**
 * Schematic DTOs matching the `SchematicResource` shape (see `docs/api/mod-namespace.md`
 * and `app/Http/Resources/SchematicResource.php` in the schemati repo). The same shape
 * is returned by community browse, upload responses, and schematic detail.
 */

/** `authors` entry: `{ uuid, last_seen_name, head_url }`. */
data class AuthorInfo(val uuid: String, val lastSeenName: String, val headUrl: String?) {
    companion object {
        fun fromJson(json: JsonObject): AuthorInfo? {
            val uuid = json.safeGetString("uuid") ?: return null
            return AuthorInfo(uuid, json.safeGetString("last_seen_name") ?: "", json.safeGetString("head_url"))
        }
    }
}

/**
 * `tags` entry on a schematic resource: `{ id, name, color, text_color }`.
 * `id` is nullable because servers older than the tag-id API addition omit it.
 */
data class SchematicTag(val name: String, val color: String?, val textColor: String?, val id: String? = null) {
    companion object {
        fun fromJson(json: JsonObject): SchematicTag? {
            val name = json.safeGetString("name") ?: return null
            return SchematicTag(
                name,
                json.safeGetString("color"),
                json.safeGetString("text_color"),
                json.safeGetString("id"),
            )
        }
    }
}

/** One schematic as it appears in listings (`data` entries of the browse endpoint). */
data class SchematicSummary(
    val id: String,
    val shortId: String?,
    val slug: String?,
    val name: String,
    val description: String?,
    val format: String?,
    val isPublic: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
    val authors: List<AuthorInfo>,
    val tags: List<SchematicTag>,
    val previewImageUrl: String?,
    val previewVideoUrl: String?,
    val downloadLink: String?,
    /** Canonical public web page (`web_url`, short_id-keyed); null on older servers. */
    val webUrl: String? = null,
) {
    companion object {
        fun fromJson(json: JsonObject): SchematicSummary? {
            val id = json.safeGetString("id") ?: return null
            return SchematicSummary(
                id = id,
                shortId = json.safeGetString("short_id"),
                slug = json.safeGetString("slug"),
                name = json.safeGetString("name") ?: "",
                description = json.safeGetString("description"),
                format = json.safeGetString("format"),
                isPublic = json.get("is_public").asBooleanOrDefault(true),
                createdAt = json.safeGetString("created_at"),
                updatedAt = json.safeGetString("updated_at"),
                authors = json.safeGetArray("authors")
                    .mapNotNull { if (it.isJsonObject) AuthorInfo.fromJson(it.asJsonObject) else null },
                tags = json.safeGetArray("tags")
                    .mapNotNull { if (it.isJsonObject) SchematicTag.fromJson(it.asJsonObject) else null },
                previewImageUrl = json.safeGetString("preview_image_url"),
                previewVideoUrl = json.safeGetString("preview_video_url"),
                downloadLink = json.safeGetString("download_link"),
                webUrl = json.safeGetString("web_url"),
            )
        }
    }
}

/**
 * Full schematic detail. Currently the API returns the identical `SchematicResource`
 * shape for detail and upload responses, so this mirrors [SchematicSummary]; kept as a
 * distinct type so detail-only fields can be added without touching listings.
 *
 * [tagFilterValues] maps tag filter ids (integers - sent as string keys in
 * `tag_filter_values`) to the schematic's stored values, so edit forms can be seeded.
 * Bool values arrive normalized as `"1"` / `"0"`. Empty when the schematic has no
 * stored values or the server predates the field.
 */
data class SchematicDetail(
    val id: String,
    val shortId: String?,
    val slug: String?,
    val name: String,
    val description: String?,
    val format: String?,
    val isPublic: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
    val authors: List<AuthorInfo>,
    val tags: List<SchematicTag>,
    val previewImageUrl: String?,
    val previewVideoUrl: String?,
    val downloadLink: String?,
    val tagFilterValues: Map<Long, String> = emptyMap(),
    /** Canonical public web page (`web_url`, short_id-keyed); null on older servers. */
    val webUrl: String? = null,
) {
    companion object {
        fun fromJson(json: JsonObject): SchematicDetail? {
            val summary = SchematicSummary.fromJson(json) ?: return null
            return SchematicDetail(
                id = summary.id,
                shortId = summary.shortId,
                slug = summary.slug,
                name = summary.name,
                description = summary.description,
                format = summary.format,
                isPublic = summary.isPublic,
                createdAt = summary.createdAt,
                updatedAt = summary.updatedAt,
                authors = summary.authors,
                tags = summary.tags,
                previewImageUrl = summary.previewImageUrl,
                previewVideoUrl = summary.previewVideoUrl,
                downloadLink = summary.downloadLink,
                tagFilterValues = json.safeGetObject("tag_filter_values")
                    ?.entrySet()
                    ?.mapNotNull { (key, value) ->
                        val id = key.toLongOrNull() ?: return@mapNotNull null
                        val stored = value.asStringOrNull() ?: return@mapNotNull null
                        id to stored
                    }
                    ?.toMap()
                    ?: emptyMap(),
                webUrl = summary.webUrl,
            )
        }
    }
}
