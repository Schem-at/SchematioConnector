package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.dto.TagFilterDef
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [TagFilterDef.validate] - client-side mirror of the server's per-filter
 * `tag_filters.<id>` 422 rules (docs/api/mod-namespace.md, schemati repo):
 * int/float numeric + within min_value/max_value, bool in true/false/0/1,
 * enum in enum_values. Null result = acceptable value.
 */
class TagFilterValidationTest {

    @Nested
    @DisplayName("int filters")
    inner class IntFilters {

        private val filter = TagFilterDef(id = 1, name = "Tick Rate", type = "int", minValue = 1.0, maxValue = 20.0)

        @Test
        fun `accepts an integer within range`() {
            assertNull(filter.validate("10"))
            assertNull(filter.validate("1"), "min bound is inclusive")
            assertNull(filter.validate("20"), "max bound is inclusive")
        }

        @Test
        fun `rejects non-integer values`() {
            assertEquals("Tick Rate must be an integer", filter.validate("fast"))
            assertEquals("Tick Rate must be an integer", filter.validate("2.5"))
            assertEquals("Tick Rate must be an integer", filter.validate(""))
        }

        @Test
        fun `rejects out-of-range integers with the bound in the message`() {
            assertEquals("Tick Rate must be at least 1", filter.validate("0"))
            assertEquals("Tick Rate must be at most 20", filter.validate("21"))
        }

        @Test
        fun `unbounded int accepts any integer`() {
            val unbounded = TagFilterDef(id = 2, name = "Count", type = "int")
            assertNull(unbounded.validate("-999999"))
            assertNull(unbounded.validate("999999"))
        }
    }

    @Nested
    @DisplayName("float filters")
    inner class FloatFilters {

        private val filter = TagFilterDef(id = 3, name = "Efficiency", type = "float", minValue = 0.5, maxValue = 99.9)

        @Test
        fun `accepts numbers within range including decimals`() {
            assertNull(filter.validate("50"))
            assertNull(filter.validate("0.5"), "min bound is inclusive")
            assertNull(filter.validate("99.9"), "max bound is inclusive")
        }

        @Test
        fun `rejects non-numeric values`() {
            assertEquals("Efficiency must be a number", filter.validate("high"))
            assertEquals("Efficiency must be a number", filter.validate(""))
            assertEquals("Efficiency must be a number", filter.validate("NaN"))
            assertEquals("Efficiency must be a number", filter.validate("Infinity"))
        }

        @Test
        fun `rejects out-of-range numbers`() {
            assertEquals("Efficiency must be at least 0.5", filter.validate("0.4"))
            assertEquals("Efficiency must be at most 99.9", filter.validate("100"))
        }
    }

    @Nested
    @DisplayName("bool filters")
    inner class BoolFilters {

        private val filter = TagFilterDef(id = 4, name = "Stackable", type = "bool")

        @Test
        fun `accepts the four server-accepted spellings`() {
            assertNull(filter.validate("1"))
            assertNull(filter.validate("0"))
            assertNull(filter.validate("true"))
            assertNull(filter.validate("false"))
        }

        @Test
        fun `rejects anything else`() {
            assertEquals("Stackable must be true or false", filter.validate("yes"))
            assertEquals("Stackable must be true or false", filter.validate("2"))
            assertEquals("Stackable must be true or false", filter.validate(""))
        }
    }

    @Nested
    @DisplayName("enum filters")
    inner class EnumFilters {

        private val filter = TagFilterDef(
            id = 5,
            name = "Mob",
            type = "enum",
            enumValues = listOf("zombie", "skeleton"),
        )

        @Test
        fun `accepts a listed value`() {
            assertNull(filter.validate("zombie"))
            assertNull(filter.validate("skeleton"))
        }

        @Test
        fun `rejects unlisted values and lists the options`() {
            assertEquals("Mob must be one of: zombie, skeleton", filter.validate("creeper"))
            assertEquals("Mob must be one of: zombie, skeleton", filter.validate(""))
        }

        @Test
        fun `enum match is case-sensitive (server compares stored strings literally)`() {
            assertNotNull(filter.validate("Zombie"))
        }
    }

    @Test
    fun `unknown filter types are accepted (forward compatibility)`() {
        val future = TagFilterDef(id = 6, name = "Mystery", type = "color")
        assertNull(future.validate("anything"))
    }

    @Test
    fun `numeric values are trimmed before parsing`() {
        val filter = TagFilterDef(id = 7, name = "Tick Rate", type = "int", minValue = 1.0, maxValue = 20.0)
        assertNull(filter.validate(" 10 "))
        assertTrue(filter.validate(" abc ") != null)
    }
}
