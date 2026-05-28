package io.schemat.connector.fabric.client.ui

import com.google.gson.JsonObject
import io.schemat.connector.core.json.*

data class SchematicEntry(
    val shortId: String,
    val name: String,
    val isPublic: Boolean,
    val downloadCount: Int,
    val width: Int,
    val height: Int,
    val length: Int,
    val authorName: String?,
    val description: String?,
    val tags: List<String>,
    val previewImageUrl: String?
) {
    companion object {
        fun fromJson(json: JsonObject): SchematicEntry {
            return SchematicEntry(
                shortId = json.safeGetStringOrDefault("short_id", ""),
                name = json.safeGetStringOrDefault("name", "Unknown"),
                isPublic = json.safeGetBoolean("is_public", false),
                downloadCount = json.safeGetInt("download_count", 0),
                width = json.safeGetInt("width", 0),
                height = json.safeGetInt("height", 0),
                length = json.safeGetInt("length", 0),
                authorName = json.safeGetString("author_name")
                    ?: json.safeGetArray("authors").firstOrNull()
                        ?.asJsonObjectOrNull()?.safeGetString("last_seen_name"),
                description = json.safeGetString("description"),
                tags = json.safeGetArray("tags").mapNotNull {
                    it.asJsonObjectOrNull()?.safeGetString("name")
                },
                previewImageUrl = json.safeGetString("preview_image_url")
            )
        }
    }

    val dimensionsText: String
        get() = if (width > 0 || height > 0 || length > 0) "${width}x${height}x${length}" else ""
}
