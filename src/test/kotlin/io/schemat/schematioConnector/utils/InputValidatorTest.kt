package io.schemat.schematioConnector.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for InputValidator validation utilities.
 */
@DisplayName("InputValidator")
class InputValidatorTest {

    @Nested
    @DisplayName("validateSearchQuery")
    inner class ValidateSearchQueryTests {

        @Test
        fun `accepts valid search query`() {
            val result = InputValidator.validateSearchQuery("castle tower")
            assertTrue(result.isValid)
            assertEquals("castle tower", result.getOrNull())
        }

        @Test
        fun `accepts null search as empty`() {
            val result = InputValidator.validateSearchQuery(null)
            assertTrue(result.isValid)
            assertEquals("", result.getOrNull())
        }

        @Test
        fun `accepts empty search`() {
            val result = InputValidator.validateSearchQuery("")
            assertTrue(result.isValid)
            assertEquals("", result.getOrNull())
        }

        @Test
        fun `accepts blank search as empty`() {
            val result = InputValidator.validateSearchQuery("   ")
            assertTrue(result.isValid)
            assertEquals("", result.getOrNull())
        }

        @Test
        fun `trims whitespace`() {
            val result = InputValidator.validateSearchQuery("  test  ")
            assertTrue(result.isValid)
            assertEquals("test", result.getOrNull())
        }

        @Test
        fun `rejects query exceeding max length`() {
            val longQuery = "a".repeat(ValidationConstants.MAX_SEARCH_LENGTH + 1)
            val result = InputValidator.validateSearchQuery(longQuery)
            assertFalse(result.isValid)
            assertTrue(result is ValidationResult.Invalid)
        }

        @Test
        fun `accepts query at max length`() {
            val maxQuery = "a".repeat(ValidationConstants.MAX_SEARCH_LENGTH)
            val result = InputValidator.validateSearchQuery(maxQuery)
            assertTrue(result.isValid)
        }
    }

    @Nested
    @DisplayName("validatePageNumber")
    inner class ValidatePageNumberTests {

        @Test
        fun `accepts valid page number`() {
            val result = InputValidator.validatePageNumber(5)
            assertTrue(result.isValid)
            assertEquals(5, result.getOrNull())
        }

        @Test
        fun `defaults null to page 1`() {
            val result = InputValidator.validatePageNumber(null)
            assertTrue(result.isValid)
            assertEquals(1, result.getOrNull())
        }

        @Test
        fun `clamps negative to min page`() {
            val result = InputValidator.validatePageNumber(-5)
            assertTrue(result.isValid)
            assertEquals(ValidationConstants.MIN_PAGE, result.getOrNull())
        }

        @Test
        fun `clamps zero to min page`() {
            val result = InputValidator.validatePageNumber(0)
            assertTrue(result.isValid)
            assertEquals(ValidationConstants.MIN_PAGE, result.getOrNull())
        }

        @Test
        fun `rejects page above max`() {
            val result = InputValidator.validatePageNumber(ValidationConstants.MAX_PAGE + 1)
            assertFalse(result.isValid)
        }

        @Test
        fun `accepts page at max`() {
            val result = InputValidator.validatePageNumber(ValidationConstants.MAX_PAGE)
            assertTrue(result.isValid)
            assertEquals(ValidationConstants.MAX_PAGE, result.getOrNull())
        }
    }

    @Nested
    @DisplayName("parseAndValidatePageNumber")
    inner class ParseAndValidatePageNumberTests {

        @Test
        fun `parses valid page string`() {
            val result = InputValidator.parseAndValidatePageNumber("5")
            assertTrue(result.isValid)
            assertEquals(5, result.getOrNull())
        }

        @Test
        fun `defaults null to page 1`() {
            val result = InputValidator.parseAndValidatePageNumber(null)
            assertTrue(result.isValid)
            assertEquals(1, result.getOrNull())
        }

        @Test
        fun `defaults blank to page 1`() {
            val result = InputValidator.parseAndValidatePageNumber("  ")
            assertTrue(result.isValid)
            assertEquals(1, result.getOrNull())
        }

        @Test
        fun `rejects non-numeric string`() {
            val result = InputValidator.parseAndValidatePageNumber("abc")
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects float string`() {
            val result = InputValidator.parseAndValidatePageNumber("1.5")
            assertFalse(result.isValid)
        }
    }

    @Nested
    @DisplayName("validateSchematicId")
    inner class ValidateSchematicIdTests {

        @Test
        fun `accepts valid alphanumeric ID`() {
            val result = InputValidator.validateSchematicId("abc123")
            assertTrue(result.isValid)
            assertEquals("abc123", result.getOrNull())
        }

        @Test
        fun `accepts ID with dashes and underscores`() {
            val result = InputValidator.validateSchematicId("my-schematic_v2")
            assertTrue(result.isValid)
        }

        @Test
        fun `rejects null ID`() {
            val result = InputValidator.validateSchematicId(null)
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects empty ID`() {
            val result = InputValidator.validateSchematicId("")
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects ID with spaces`() {
            val result = InputValidator.validateSchematicId("my schematic")
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects ID with special characters`() {
            val result = InputValidator.validateSchematicId("my@schematic!")
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects ID exceeding max length`() {
            val longId = "a".repeat(33)
            val result = InputValidator.validateSchematicId(longId)
            assertFalse(result.isValid)
        }

        @Test
        fun `accepts ID at max length`() {
            val maxId = "a".repeat(32)
            val result = InputValidator.validateSchematicId(maxId)
            assertTrue(result.isValid)
        }

        @Test
        fun `trims whitespace`() {
            val result = InputValidator.validateSchematicId("  abc123  ")
            assertTrue(result.isValid)
            assertEquals("abc123", result.getOrNull())
        }
    }

    @Nested
    @DisplayName("validateQuickShareCode")
    inner class ValidateQuickShareCodeTests {

        @Test
        fun `accepts raw code`() {
            val result = InputValidator.validateQuickShareCode("abc12345")
            assertTrue(result.isValid)
        }

        @Test
        fun `accepts code with qs_ prefix`() {
            val result = InputValidator.validateQuickShareCode("qs_abc12345")
            assertTrue(result.isValid)
        }

        @Test
        fun `extracts code from URL`() {
            val result = InputValidator.validateQuickShareCode("https://schemat.io/share/abc12345")
            assertTrue(result.isValid)
            assertEquals("abc12345", result.getOrNull())
        }

        @Test
        fun `extracts code from quick-shares URL`() {
            val result = InputValidator.validateQuickShareCode("https://schemat.io/quick-shares/abc12345")
            assertTrue(result.isValid)
            assertEquals("abc12345", result.getOrNull())
        }

        @Test
        fun `rejects null code`() {
            val result = InputValidator.validateQuickShareCode(null)
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects empty code`() {
            val result = InputValidator.validateQuickShareCode("")
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects code too short`() {
            val result = InputValidator.validateQuickShareCode("abc12")
            assertFalse(result.isValid)
        }
    }

    @Nested
    @DisplayName("validatePassword")
    inner class ValidatePasswordTests {

        @Test
        fun `accepts valid password`() {
            val result = InputValidator.validatePassword("SecurePassword123")
            assertTrue(result.isValid)
        }

        @Test
        fun `rejects null password`() {
            val result = InputValidator.validatePassword(null)
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects empty password`() {
            val result = InputValidator.validatePassword("")
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects password below min length`() {
            val shortPassword = "a".repeat(ValidationConstants.MIN_PASSWORD_LENGTH - 1)
            val result = InputValidator.validatePassword(shortPassword)
            assertFalse(result.isValid)
        }

        @Test
        fun `accepts password at min length`() {
            val minPassword = "a".repeat(ValidationConstants.MIN_PASSWORD_LENGTH)
            val result = InputValidator.validatePassword(minPassword)
            assertTrue(result.isValid)
        }

        @Test
        fun `rejects password above max length`() {
            val longPassword = "a".repeat(ValidationConstants.MAX_PASSWORD_LENGTH + 1)
            val result = InputValidator.validatePassword(longPassword)
            assertFalse(result.isValid)
        }
    }

    @Nested
    @DisplayName("validateDownloadFormat")
    inner class ValidateDownloadFormatTests {

        @Test
        fun `accepts valid format`() {
            val result = InputValidator.validateDownloadFormat("schem")
            assertTrue(result.isValid)
            assertEquals("schem", result.getOrNull())
        }

        @Test
        fun `accepts schematic format`() {
            val result = InputValidator.validateDownloadFormat("schematic")
            assertTrue(result.isValid)
        }

        @Test
        fun `accepts mcedit format`() {
            val result = InputValidator.validateDownloadFormat("mcedit")
            assertTrue(result.isValid)
        }

        @Test
        fun `defaults null to first valid format`() {
            val result = InputValidator.validateDownloadFormat(null)
            assertTrue(result.isValid)
            assertEquals("schem", result.getOrNull())
        }

        @Test
        fun `normalizes to lowercase`() {
            val result = InputValidator.validateDownloadFormat("SCHEM")
            assertTrue(result.isValid)
            assertEquals("schem", result.getOrNull())
        }

        @Test
        fun `rejects invalid format`() {
            val result = InputValidator.validateDownloadFormat("invalid")
            assertFalse(result.isValid)
        }
    }

    @Nested
    @DisplayName("validateSchematicSize")
    inner class ValidateSchematicSizeTests {

        @Test
        fun `accepts valid size`() {
            val result = InputValidator.validateSchematicSize(1024)
            assertTrue(result.isValid)
        }

        @Test
        fun `rejects zero size`() {
            val result = InputValidator.validateSchematicSize(0)
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects negative size`() {
            val result = InputValidator.validateSchematicSize(-1)
            assertFalse(result.isValid)
        }

        @Test
        fun `rejects size above max`() {
            val result = InputValidator.validateSchematicSize(ValidationConstants.MAX_SCHEMATIC_SIZE_BYTES + 1)
            assertFalse(result.isValid)
        }

        @Test
        fun `accepts size at max`() {
            val result = InputValidator.validateSchematicSize(ValidationConstants.MAX_SCHEMATIC_SIZE_BYTES)
            assertTrue(result.isValid)
        }
    }

    @Nested
    @DisplayName("ValidationResult")
    inner class ValidationResultTests {

        @Test
        fun `getOrNull returns value for Valid`() {
            val result: ValidationResult<String> = ValidationResult.Valid("test")
            assertEquals("test", result.getOrNull())
        }

        @Test
        fun `getOrNull returns null for Invalid`() {
            val result: ValidationResult<String> = ValidationResult.Invalid("error")
            assertNull(result.getOrNull())
        }

        @Test
        fun `getOrDefault returns value for Valid`() {
            val result: ValidationResult<String> = ValidationResult.Valid("test")
            assertEquals("test", result.getOrDefault("default"))
        }

        @Test
        fun `getOrDefault returns default for Invalid`() {
            val result: ValidationResult<String> = ValidationResult.Invalid("error")
            assertEquals("default", result.getOrDefault("default"))
        }

        @Test
        fun `map transforms Valid value`() {
            val result: ValidationResult<Int> = ValidationResult.Valid(5)
            val mapped = result.map { it * 2 }
            assertEquals(10, mapped.getOrNull())
        }

        @Test
        fun `map preserves Invalid`() {
            val result: ValidationResult<Int> = ValidationResult.Invalid("error")
            val mapped = result.map { it * 2 }
            assertFalse(mapped.isValid)
        }
    }
}
