package io.schemat.schematioConnector.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * Tests for argument parsing utilities used in commands.
 *
 * These tests verify the logic for parsing command arguments
 * without requiring Bukkit dependencies.
 */
class ArgumentParsingTest {

    /**
     * Parse arguments for the list command.
     * Format: [search terms...] [page number]
     * Returns Pair(searchTerm, pageNumber)
     */
    private fun parseListArgs(args: Array<String>): Pair<String?, Int> {
        if (args.isEmpty()) {
            return Pair(null, 1)
        }

        val lastArg = args.last()
        val pageNumber = lastArg.toIntOrNull()

        return when {
            pageNumber != null && args.size > 1 -> {
                // Last arg is page, rest is search
                Pair(args.dropLast(1).joinToString(" "), pageNumber.coerceAtLeast(1))
            }
            pageNumber != null && args.size == 1 -> {
                // Only arg is page number
                Pair(null, pageNumber.coerceAtLeast(1))
            }
            else -> {
                // All args are search term
                Pair(args.joinToString(" "), 1)
            }
        }
    }

    /**
     * Extract access code from URL or direct code.
     */
    private fun extractAccessCode(input: String): String? {
        // If it looks like an access code already
        if (input.startsWith("qs_")) {
            return input
        }

        // Try to extract from URL
        val patterns = listOf(
            Regex(".*/share/([a-zA-Z0-9_]+).*"),
            Regex(".*quick-shares/([a-zA-Z0-9_]+).*")
        )

        for (pattern in patterns) {
            val match = pattern.matchEntire(input)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        // Maybe it's just an access code without the qs_ prefix
        if (input.matches(Regex("[a-zA-Z0-9_]+"))) {
            return input
        }

        return null
    }

    @Nested
    @DisplayName("List Command Argument Parsing")
    inner class ListCommandTests {

        @Test
        fun `empty args returns null search and page 1`() {
            val (search, page) = parseListArgs(emptyArray())
            assertNull(search)
            assertEquals(1, page)
        }

        @Test
        fun `single search term`() {
            val (search, page) = parseListArgs(arrayOf("castle"))
            assertEquals("castle", search)
            assertEquals(1, page)
        }

        @Test
        fun `multiple search terms`() {
            val (search, page) = parseListArgs(arrayOf("medieval", "castle"))
            assertEquals("medieval castle", search)
            assertEquals(1, page)
        }

        @Test
        fun `single page number`() {
            val (search, page) = parseListArgs(arrayOf("5"))
            assertNull(search)
            assertEquals(5, page)
        }

        @Test
        fun `search term with page number`() {
            val (search, page) = parseListArgs(arrayOf("castle", "3"))
            assertEquals("castle", search)
            assertEquals(3, page)
        }

        @Test
        fun `multiple search terms with page number`() {
            val (search, page) = parseListArgs(arrayOf("medieval", "castle", "2"))
            assertEquals("medieval castle", search)
            assertEquals(2, page)
        }

        @Test
        fun `negative page number is coerced to 1`() {
            val (search, page) = parseListArgs(arrayOf("-5"))
            assertNull(search)
            assertEquals(1, page)
        }

        @Test
        fun `zero page number is coerced to 1`() {
            val (search, page) = parseListArgs(arrayOf("0"))
            assertNull(search)
            assertEquals(1, page)
        }

        @Test
        fun `search term that looks like number is treated as search`() {
            val (search, page) = parseListArgs(arrayOf("123abc"))
            assertEquals("123abc", search)
            assertEquals(1, page)
        }
    }

    @Nested
    @DisplayName("Quick Share Access Code Extraction")
    inner class AccessCodeTests {

        @Test
        fun `direct access code with qs_ prefix`() {
            val code = extractAccessCode("qs_abc123xy")
            assertEquals("qs_abc123xy", code)
        }

        @Test
        fun `full HTTPS URL with share path`() {
            val code = extractAccessCode("https://schemat.io/share/qs_abc123xy")
            assertEquals("qs_abc123xy", code)
        }

        @Test
        fun `full HTTP URL with share path`() {
            val code = extractAccessCode("http://schemat.io/share/qs_abc123xy")
            assertEquals("qs_abc123xy", code)
        }

        @Test
        fun `URL without protocol`() {
            val code = extractAccessCode("schemat.io/share/qs_abc123xy")
            assertEquals("qs_abc123xy", code)
        }

        @Test
        fun `URL with quick-shares path`() {
            val code = extractAccessCode("https://schemat.io/quick-shares/abc123")
            assertEquals("abc123", code)
        }

        @Test
        fun `plain alphanumeric code`() {
            val code = extractAccessCode("abc123")
            assertEquals("abc123", code)
        }

        @Test
        fun `code with underscores`() {
            val code = extractAccessCode("abc_123_xyz")
            assertEquals("abc_123_xyz", code)
        }

        @Test
        fun `invalid URL returns null`() {
            val code = extractAccessCode("not a valid input!")
            assertNull(code)
        }

        @Test
        fun `empty string returns null`() {
            val code = extractAccessCode("")
            assertNull(code)
        }

        @Test
        fun `URL with trailing slash`() {
            val code = extractAccessCode("https://schemat.io/share/qs_abc123xy/")
            assertEquals("qs_abc123xy", code)
        }
    }

    @Nested
    @DisplayName("Download Format Validation")
    inner class DownloadFormatTests {

        private val validFormats = listOf("schem", "schematic", "mcedit")

        private fun isValidFormat(format: String): Boolean {
            return format in validFormats
        }

        @Test
        fun `schem format is valid`() {
            assertTrue(isValidFormat("schem"))
        }

        @Test
        fun `schematic format is valid`() {
            assertTrue(isValidFormat("schematic"))
        }

        @Test
        fun `mcedit format is valid`() {
            assertTrue(isValidFormat("mcedit"))
        }

        @Test
        fun `unknown format is invalid`() {
            assertFalse(isValidFormat("nbt"))
        }

        @Test
        fun `uppercase format is invalid`() {
            assertFalse(isValidFormat("SCHEM"))
        }

        @Test
        fun `empty format is invalid`() {
            assertFalse(isValidFormat(""))
        }
    }
}
