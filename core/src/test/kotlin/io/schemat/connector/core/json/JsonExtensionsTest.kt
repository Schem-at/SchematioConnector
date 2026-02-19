package io.schemat.connector.core.json

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("JsonExtensions")
class JsonExtensionsTest {

    @Nested
    @DisplayName("safeGetInt")
    inner class SafeGetIntTests {

        @Test
        fun `returns value when present and valid`() {
            val json = JsonObject().apply { addProperty("count", 42) }
            assertEquals(42, json.safeGetInt("count"))
        }

        @Test
        fun `returns default when key missing`() {
            val json = JsonObject()
            assertEquals(0, json.safeGetInt("count"))
            assertEquals(10, json.safeGetInt("count", 10))
        }

        @Test
        fun `returns default when value is null`() {
            val json = JsonObject().apply { add("count", JsonNull.INSTANCE) }
            assertEquals(0, json.safeGetInt("count"))
        }

        @Test
        fun `returns default when value is wrong type`() {
            val json = JsonObject().apply { addProperty("count", "not a number") }
            assertEquals(0, json.safeGetInt("count"))
        }

        @Test
        fun `returns default when JsonObject is null`() {
            val json: JsonObject? = null
            assertEquals(0, json.safeGetInt("count"))
        }

        @Test
        fun `handles negative numbers`() {
            val json = JsonObject().apply { addProperty("count", -5) }
            assertEquals(-5, json.safeGetInt("count"))
        }
    }

    @Nested
    @DisplayName("safeGetString")
    inner class SafeGetStringTests {

        @Test
        fun `returns value when present and valid`() {
            val json = JsonObject().apply { addProperty("name", "test") }
            assertEquals("test", json.safeGetString("name"))
        }

        @Test
        fun `returns null when key missing`() {
            val json = JsonObject()
            assertNull(json.safeGetString("name"))
        }

        @Test
        fun `returns default when key missing`() {
            val json = JsonObject()
            assertEquals("default", json.safeGetString("name", "default"))
        }

        @Test
        fun `returns null when value is JsonNull`() {
            val json = JsonObject().apply { add("name", JsonNull.INSTANCE) }
            assertNull(json.safeGetString("name"))
        }

        @Test
        fun `returns default when JsonObject is null`() {
            val json: JsonObject? = null
            assertNull(json.safeGetString("name"))
            assertEquals("default", json.safeGetString("name", "default"))
        }

        @Test
        fun `handles empty string`() {
            val json = JsonObject().apply { addProperty("name", "") }
            assertEquals("", json.safeGetString("name"))
        }
    }

    @Nested
    @DisplayName("safeGetBoolean")
    inner class SafeGetBooleanTests {

        @Test
        fun `returns true when value is true`() {
            val json = JsonObject().apply { addProperty("active", true) }
            assertTrue(json.safeGetBoolean("active"))
        }

        @Test
        fun `returns false when value is false`() {
            val json = JsonObject().apply { addProperty("active", false) }
            assertFalse(json.safeGetBoolean("active"))
        }

        @Test
        fun `returns default when key missing`() {
            val json = JsonObject()
            assertFalse(json.safeGetBoolean("active"))
            assertTrue(json.safeGetBoolean("active", true))
        }

        @Test
        fun `returns default when value is null`() {
            val json = JsonObject().apply { add("active", JsonNull.INSTANCE) }
            assertFalse(json.safeGetBoolean("active"))
        }

        @Test
        fun `treats 1 as true`() {
            val json = JsonObject().apply { addProperty("active", 1) }
            assertTrue(json.safeGetBoolean("active"))
        }

        @Test
        fun `treats 0 as false`() {
            val json = JsonObject().apply { addProperty("active", 0) }
            assertFalse(json.safeGetBoolean("active"))
        }

        @Test
        fun `returns default when JsonObject is null`() {
            val json: JsonObject? = null
            assertFalse(json.safeGetBoolean("active"))
        }
    }

    @Nested
    @DisplayName("safeGetObject")
    inner class SafeGetObjectTests {

        @Test
        fun `returns nested object when present`() {
            val nested = JsonObject().apply { addProperty("id", 1) }
            val json = JsonObject().apply { add("data", nested) }

            val result = json.safeGetObject("data")
            assertNotNull(result)
            assertEquals(1, result?.safeGetInt("id"))
        }

        @Test
        fun `returns null when key missing`() {
            val json = JsonObject()
            assertNull(json.safeGetObject("data"))
        }

        @Test
        fun `returns null when value is not object`() {
            val json = JsonObject().apply { addProperty("data", "string") }
            assertNull(json.safeGetObject("data"))
        }

        @Test
        fun `returns null when value is JsonNull`() {
            val json = JsonObject().apply { add("data", JsonNull.INSTANCE) }
            assertNull(json.safeGetObject("data"))
        }
    }

    @Nested
    @DisplayName("safeGetArray")
    inner class SafeGetArrayTests {

        @Test
        fun `returns list when array present`() {
            val array = JsonArray().apply {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
            val json = JsonObject().apply { add("items", array) }

            val result = json.safeGetArray("items")
            assertEquals(2, result.size)
        }

        @Test
        fun `returns empty list when key missing`() {
            val json = JsonObject()
            assertTrue(json.safeGetArray("items").isEmpty())
        }

        @Test
        fun `returns empty list when value is not array`() {
            val json = JsonObject().apply { addProperty("items", "not array") }
            assertTrue(json.safeGetArray("items").isEmpty())
        }

        @Test
        fun `returns empty list when JsonObject is null`() {
            val json: JsonObject? = null
            assertTrue(json.safeGetArray("items").isEmpty())
        }
    }

    @Nested
    @DisplayName("parseJsonSafe")
    inner class ParseJsonSafeTests {

        @Test
        fun `parses valid JSON object`() {
            val result = parseJsonSafe("""{"name": "test", "count": 42}""")
            assertNotNull(result)
            assertEquals("test", result?.safeGetString("name"))
            assertEquals(42, result?.safeGetInt("count"))
        }

        @Test
        fun `returns null for null input`() {
            assertNull(parseJsonSafe(null))
        }

        @Test
        fun `returns null for empty string`() {
            assertNull(parseJsonSafe(""))
        }

        @Test
        fun `returns null for blank string`() {
            assertNull(parseJsonSafe("   "))
        }

        @Test
        fun `returns null for invalid JSON`() {
            assertNull(parseJsonSafe("not json"))
        }
    }

    @Nested
    @DisplayName("asJsonObjectOrNull extension")
    inner class AsJsonObjectOrNullTests {

        @Test
        fun `returns JsonObject for object element`() {
            val obj = JsonObject().apply { addProperty("id", 1) }
            val result = obj.asJsonObjectOrNull()
            assertNotNull(result)
            assertEquals(1, result?.safeGetInt("id"))
        }

        @Test
        fun `returns null for null element`() {
            val element: com.google.gson.JsonElement? = null
            assertNull(element.asJsonObjectOrNull())
        }

        @Test
        fun `returns null for primitive element`() {
            val element = JsonPrimitive("string")
            assertNull(element.asJsonObjectOrNull())
        }

        @Test
        fun `returns null for array element`() {
            val element = JsonArray()
            assertNull(element.asJsonObjectOrNull())
        }
    }

    @Nested
    @DisplayName("asStringOrNull extension")
    inner class AsStringOrNullTests {

        @Test
        fun `returns string for string primitive`() {
            val element = JsonPrimitive("hello")
            assertEquals("hello", element.asStringOrNull())
        }

        @Test
        fun `returns null for null element`() {
            val element: com.google.gson.JsonElement? = null
            assertNull(element.asStringOrNull())
        }

        @Test
        fun `returns null for JsonNull`() {
            val element = JsonNull.INSTANCE
            assertNull(element.asStringOrNull())
        }

        @Test
        fun `returns string for number primitive`() {
            val element = JsonPrimitive(42)
            assertEquals("42", element.asStringOrNull())
        }
    }
}
