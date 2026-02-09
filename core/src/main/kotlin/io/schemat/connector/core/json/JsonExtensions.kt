package io.schemat.connector.core.json

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException

/**
 * Safe JSON parsing extensions to prevent crashes from malformed API responses.
 *
 * These extensions provide null-safe access to JSON fields, returning default values
 * when fields are missing, null, or of an unexpected type. This prevents crashes
 * when the API returns unexpected responses.
 *
 * ## Usage Example
 * ```kotlin
 * val json = parseJsonSafe(responseBody)
 * val page = json.safeGetInt("current_page", 1)
 * val name = json.safeGetString("name") ?: "Unknown"
 * val isPublic = json.safeGetBoolean("is_public", false)
 * ```
 */

private val gson = Gson()

/**
 * Safely get an integer value from a JSON object.
 *
 * @param key The key to look up
 * @param default The default value if key is missing, null, or wrong type
 * @return The integer value or default
 */
fun JsonObject?.safeGetInt(key: String, default: Int = 0): Int {
    if (this == null) return default
    return try {
        val element = this.get(key)
        when {
            element == null -> default
            element.isJsonNull -> default
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

/**
 * Safely get a long value from a JSON object.
 *
 * @param key The key to look up
 * @param default The default value if key is missing, null, or wrong type
 * @return The long value or default
 */
fun JsonObject?.safeGetLong(key: String, default: Long = 0L): Long {
    if (this == null) return default
    return try {
        val element = this.get(key)
        when {
            element == null -> default
            element.isJsonNull -> default
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asLong
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

/**
 * Safely get a float value from a JSON object.
 *
 * @param key The key to look up
 * @param default The default value if key is missing, null, or wrong type
 * @return The float value or default
 */
fun JsonObject?.safeGetFloat(key: String, default: Float = 0f): Float {
    if (this == null) return default
    return try {
        val element = this.get(key)
        when {
            element == null -> default
            element.isJsonNull -> default
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asFloat
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

/**
 * Safely get a string value from a JSON object.
 *
 * @param key The key to look up
 * @param default The default value if key is missing, null, or wrong type
 * @return The string value or default (null by default)
 */
fun JsonObject?.safeGetString(key: String, default: String? = null): String? {
    if (this == null) return default
    return try {
        val element = this.get(key)
        when {
            element == null -> default
            element.isJsonNull -> default
            element.isJsonPrimitive -> element.asString
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

/**
 * Safely get a non-null string value from a JSON object.
 * Useful when a default non-null value is needed.
 *
 * @param key The key to look up
 * @param default The default value if key is missing, null, or wrong type
 * @return The string value or default
 */
fun JsonObject?.safeGetStringOrDefault(key: String, default: String): String {
    return safeGetString(key, default) ?: default
}

/**
 * Safely get a boolean value from a JSON object.
 *
 * @param key The key to look up
 * @param default The default value if key is missing, null, or wrong type
 * @return The boolean value or default
 */
fun JsonObject?.safeGetBoolean(key: String, default: Boolean = false): Boolean {
    if (this == null) return default
    return try {
        val element = this.get(key)
        when {
            element == null -> default
            element.isJsonNull -> default
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
            // Also handle 1/0 as true/false
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt != 0
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

/**
 * Safely get a nested JSON object.
 *
 * @param key The key to look up
 * @return The nested JsonObject or null if missing/wrong type
 */
fun JsonObject?.safeGetObject(key: String): JsonObject? {
    if (this == null) return null
    return try {
        val element = this.get(key)
        when {
            element == null -> null
            element.isJsonNull -> null
            element.isJsonObject -> element.asJsonObject
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Safely get a JSON array as a list of elements.
 *
 * @param key The key to look up
 * @return List of JsonElement, or empty list if missing/wrong type
 */
fun JsonObject?.safeGetArray(key: String): List<JsonElement> {
    if (this == null) return emptyList()
    return try {
        val element = this.get(key)
        when {
            element == null -> emptyList()
            element.isJsonNull -> emptyList()
            element.isJsonArray -> element.asJsonArray.toList()
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Safely get a JSON array.
 *
 * @param key The key to look up
 * @return The JsonArray or null if missing/wrong type
 */
fun JsonObject?.safeGetJsonArray(key: String): JsonArray? {
    if (this == null) return null
    return try {
        val element = this.get(key)
        when {
            element == null -> null
            element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse a JSON string safely, returning null if parsing fails.
 *
 * @param json The JSON string to parse
 * @return The parsed JsonObject or null if parsing fails
 */
fun parseJsonSafe(json: String?): JsonObject? {
    if (json.isNullOrBlank()) return null
    return try {
        gson.fromJson(json, JsonObject::class.java)
    } catch (e: JsonSyntaxException) {
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Parse a JSON string safely as a specific type.
 *
 * @param json The JSON string to parse
 * @param clazz The target class
 * @return The parsed object or null if parsing fails
 */
fun <T> parseJsonSafe(json: String?, clazz: Class<T>): T? {
    if (json.isNullOrBlank()) return null
    return try {
        gson.fromJson(json, clazz)
    } catch (e: JsonSyntaxException) {
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Extension for JsonElement to safely convert to JsonObject.
 */
fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
    if (this == null) return null
    return try {
        if (this.isJsonObject) this.asJsonObject else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Extension for JsonElement to safely get a string value.
 */
fun JsonElement?.asStringOrNull(): String? {
    if (this == null) return null
    return try {
        when {
            this.isJsonNull -> null
            this.isJsonPrimitive -> this.asString
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Extension for JsonElement to safely get an int value.
 */
fun JsonElement?.asIntOrDefault(default: Int = 0): Int {
    if (this == null) return default
    return try {
        when {
            this.isJsonNull -> default
            this.isJsonPrimitive && this.asJsonPrimitive.isNumber -> this.asInt
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

/**
 * Extension for JsonElement to safely get a boolean value.
 */
fun JsonElement?.asBooleanOrDefault(default: Boolean = false): Boolean {
    if (this == null) return default
    return try {
        when {
            this.isJsonNull -> default
            this.isJsonPrimitive && this.asJsonPrimitive.isBoolean -> this.asBoolean
            this.isJsonPrimitive && this.asJsonPrimitive.isNumber -> this.asInt != 0
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}
