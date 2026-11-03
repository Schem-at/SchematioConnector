package io.schemat.connector.core.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.Base64

/**
 * A value for one tool input, encoded as the run request's `inputs.<name>` JSON. File
 * and schematic inputs are either uploaded inline ([Upload]) or referenced
 * ([SchematicRef], [QuickShareRef], [ClipboardRef]).
 */
sealed class ToolInputValue {
    abstract fun toJson(): JsonElement

    data class Text(val value: String) : ToolInputValue() {
        override fun toJson(): JsonElement = JsonPrimitive(value)
    }

    data class Number(val value: Double) : ToolInputValue() {
        // Whole numbers go out as integers ("64", not "64.0").
        override fun toJson(): JsonElement =
            if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    }

    data class Bool(val value: Boolean) : ToolInputValue() {
        override fun toJson(): JsonElement = JsonPrimitive(value)
    }

    /** Inline bytes: `{"$base64": ..., "filename": ...}`. */
    class Upload(val bytes: ByteArray, val filename: String) : ToolInputValue() {
        override fun toJson(): JsonElement = JsonObject().apply {
            addProperty("\$base64", Base64.getEncoder().encodeToString(bytes))
            addProperty("filename", filename)
        }

        override fun toString(): String = "Upload($filename, ${bytes.size} bytes)"
    }

    /** A schemat.io schematic the caller may download: `{"$schematic": shortId}`. */
    data class SchematicRef(val id: String) : ToolInputValue() {
        override fun toJson(): JsonElement = JsonObject().apply { addProperty("\$schematic", id) }
    }

    data class QuickShareRef(val code: String) : ToolInputValue() {
        override fun toJson(): JsonElement = JsonObject().apply { addProperty("\$quickshare", code) }
    }

    /** A clipboard draft the plugin already uploaded (`/plugin/clipboard/drafts` id). */
    data class ClipboardRef(val ref: String) : ToolInputValue() {
        override fun toJson(): JsonElement = JsonObject().apply { addProperty("\$clipboard", ref) }
    }

    /**
     * "Use my clipboard" from chat or a default: the caller must replace it with a real
     * value (an [Upload] of the clipboard) before sending.
     */
    data object ClipboardPlaceholder : ToolInputValue() {
        override fun toJson(): JsonElement =
            throw IllegalStateException("ClipboardPlaceholder must be resolved to the clipboard's bytes before sending")
    }
}
