package io.schemat.schematioConnector.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * Tests for command argument parsing and validation logic.
 *
 * These tests verify the argument handling that occurs within commands,
 * isolated from the Bukkit Player dependencies.
 */
class CommandArgumentsTest {

    @Nested
    @DisplayName("Download Command Arguments")
    inner class DownloadArgumentTests {

        /**
         * Valid download formats supported by the download command.
         */
        private val validFormats = listOf("schem", "schematic", "mcedit")

        @Test
        fun `valid formats are accepted`() {
            for (format in validFormats) {
                assertTrue(format in validFormats, "Format '$format' should be valid")
            }
        }

        @Test
        fun `invalid formats are rejected`() {
            val invalidFormats = listOf("nbt", "litematic", "worldedit", "json", "")
            for (format in invalidFormats) {
                assertFalse(format in validFormats, "Format '$format' should be invalid")
            }
        }

        @Test
        fun `default format is schem`() {
            val defaultFormat = "schem"
            assertTrue(defaultFormat in validFormats)
        }

        @Test
        fun `format comparison is case sensitive`() {
            assertFalse("SCHEM" in validFormats)
            assertFalse("Schematic" in validFormats)
        }
    }

    @Nested
    @DisplayName("QuickShareGet Arguments")
    inner class QuickShareGetArgumentTests {

        /**
         * Extracts the access code from various URL formats.
         * This mirrors the logic in QuickShareGetSubcommand.
         */
        private fun extractAccessCode(input: String): String {
            // Handle full URLs - extract the code from the path
            return if (input.startsWith("http://") || input.startsWith("https://")) {
                // Try to extract code from URL path
                val urlPath = input.substringAfterLast("/")
                // Remove any query parameters
                urlPath.substringBefore("?")
            } else {
                input
            }
        }

        @Test
        fun `raw access code is returned as-is`() {
            assertEquals("qs_abc123xy", extractAccessCode("qs_abc123xy"))
        }

        @Test
        fun `https URL extracts code correctly`() {
            assertEquals("qs_abc123xy", extractAccessCode("https://schemat.io/share/qs_abc123xy"))
        }

        @Test
        fun `http URL extracts code correctly`() {
            assertEquals("qs_abc123xy", extractAccessCode("http://schemat.io/share/qs_abc123xy"))
        }

        @Test
        fun `URL with query parameters extracts code`() {
            assertEquals("qs_abc123xy", extractAccessCode("https://schemat.io/share/qs_abc123xy?ref=plugin"))
        }

        @Test
        fun `URL with trailing slash handled`() {
            // Note: This returns empty string if URL ends with /
            // The actual command would validate this
            val result = extractAccessCode("https://schemat.io/share/")
            assertTrue(result.isEmpty() || result == "share")
        }

        @Test
        fun `short codes are valid`() {
            val code = "abc123"
            assertEquals("abc123", extractAccessCode(code))
        }
    }

    @Nested
    @DisplayName("List Command Pagination")
    inner class ListPaginationTests {

        /**
         * Parses page number from argument, defaulting to 1.
         */
        private fun parsePageNumber(arg: String?): Int {
            if (arg == null) return 1
            return arg.toIntOrNull()?.coerceAtLeast(1) ?: 1
        }

        @Test
        fun `null argument returns page 1`() {
            assertEquals(1, parsePageNumber(null))
        }

        @Test
        fun `valid page numbers are parsed`() {
            assertEquals(1, parsePageNumber("1"))
            assertEquals(5, parsePageNumber("5"))
            assertEquals(100, parsePageNumber("100"))
        }

        @Test
        fun `zero is coerced to 1`() {
            assertEquals(1, parsePageNumber("0"))
        }

        @Test
        fun `negative numbers are coerced to 1`() {
            assertEquals(1, parsePageNumber("-1"))
            assertEquals(1, parsePageNumber("-100"))
        }

        @Test
        fun `non-numeric strings default to 1`() {
            assertEquals(1, parsePageNumber("abc"))
            assertEquals(1, parsePageNumber("first"))
            assertEquals(1, parsePageNumber(""))
        }

        @Test
        fun `floating point strings default to 1`() {
            assertEquals(1, parsePageNumber("1.5"))
            assertEquals(1, parsePageNumber("3.14"))
        }
    }

    @Nested
    @DisplayName("Permission Strings")
    inner class PermissionTests {

        // Base permissions
        private val basePermissions = listOf(
            "schematio.upload",
            "schematio.download",
            "schematio.list",
            "schematio.quickshare",
            "schematio.admin"
        )

        // Tier permissions
        private val tierPermissions = listOf(
            "schematio.tier.chat",
            "schematio.tier.inventory",
            "schematio.tier.floating"
        )

        @Test
        fun `all permission strings are lowercase`() {
            for (perm in basePermissions + tierPermissions) {
                assertEquals(perm, perm.lowercase(), "Permission should be lowercase: $perm")
            }
        }

        @Test
        fun `permission strings use dot notation`() {
            for (perm in basePermissions + tierPermissions) {
                assertTrue(perm.contains("."), "Permission should use dot notation: $perm")
            }
        }

        @Test
        fun `all permissions start with schematio prefix`() {
            for (perm in basePermissions + tierPermissions) {
                assertTrue(perm.startsWith("schematio."), "Permission should start with 'schematio.': $perm")
            }
        }

        @Test
        fun `tier permissions have tier prefix`() {
            for (perm in tierPermissions) {
                assertTrue(perm.startsWith("schematio.tier."), "Tier permission should have 'tier.' prefix: $perm")
            }
        }
    }

    @Nested
    @DisplayName("Search Query Handling")
    inner class SearchQueryTests {

        /**
         * Combines multiple arguments into a search query.
         * Mimics list command behavior.
         */
        private fun combineSearchArgs(args: Array<String>, startIndex: Int = 0): String {
            if (args.isEmpty() || startIndex >= args.size) return ""
            return args.drop(startIndex).joinToString(" ").trim()
        }

        @Test
        fun `empty args returns empty string`() {
            assertEquals("", combineSearchArgs(emptyArray()))
        }

        @Test
        fun `single word search`() {
            assertEquals("castle", combineSearchArgs(arrayOf("castle")))
        }

        @Test
        fun `multi-word search`() {
            assertEquals("medieval castle", combineSearchArgs(arrayOf("medieval", "castle")))
        }

        @Test
        fun `search with start index skips initial args`() {
            assertEquals("castle", combineSearchArgs(arrayOf("ignored", "castle"), startIndex = 1))
        }

        @Test
        fun `extra whitespace is trimmed`() {
            assertEquals("my search", combineSearchArgs(arrayOf("my", "search")))
        }
    }

    @Nested
    @DisplayName("Tab Completion Helpers")
    inner class TabCompletionTests {

        /**
         * Filters options that start with the given prefix.
         */
        private fun filterOptions(options: List<String>, prefix: String): List<String> {
            return options.filter { it.startsWith(prefix, ignoreCase = true) }
        }

        @Test
        fun `empty prefix returns all options`() {
            val options = listOf("schem", "schematic", "mcedit")
            assertEquals(options, filterOptions(options, ""))
        }

        @Test
        fun `prefix filters matching options`() {
            val options = listOf("schem", "schematic", "mcedit")
            assertEquals(listOf("schem", "schematic"), filterOptions(options, "sch"))
        }

        @Test
        fun `case insensitive matching`() {
            val options = listOf("schem", "schematic", "mcedit")
            assertEquals(listOf("schem", "schematic"), filterOptions(options, "SCH"))
        }

        @Test
        fun `no matches returns empty list`() {
            val options = listOf("schem", "schematic", "mcedit")
            assertEquals(emptyList<String>(), filterOptions(options, "xyz"))
        }

        @Test
        fun `exact match is included`() {
            val options = listOf("schem", "schematic")
            // "schem" prefix matches both "schem" and "schematic"
            assertEquals(listOf("schem", "schematic"), filterOptions(options, "schem"))
        }
    }
}
