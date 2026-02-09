package io.schemat.connector.core.validation

/**
 * Validation constants for user input and API parameters.
 *
 * These limits prevent abuse (DoS via large payloads) and ensure
 * inputs conform to expected formats before sending to the API.
 */
object ValidationConstants {
    // Search queries
    const val MAX_SEARCH_LENGTH = 100

    // Pagination
    const val MIN_PAGE = 1
    const val MAX_PAGE = 1000
    const val DEFAULT_PER_PAGE = 10
    const val MAX_PER_PAGE = 50

    // Schematic IDs - alphanumeric with dashes/underscores, 1-32 chars
    val SCHEMATIC_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,32}$")

    // QuickShare codes - optional qs_ prefix, 6-16 alphanumeric chars
    val QUICKSHARE_CODE_REGEX = Regex("^(qs_)?[a-zA-Z0-9]{6,16}$")

    // Passwords
    const val MIN_PASSWORD_LENGTH = 10
    const val MAX_PASSWORD_LENGTH = 128

    // File sizes
    const val MAX_SCHEMATIC_SIZE_BYTES = 10 * 1024 * 1024  // 10MB

    // Rate limiting defaults
    const val DEFAULT_RATE_LIMIT_REQUESTS = 10
    const val DEFAULT_RATE_LIMIT_WINDOW_SECONDS = 60
}

/**
 * Result of a validation operation.
 *
 * Use pattern matching to handle valid vs invalid cases:
 * ```kotlin
 * when (val result = InputValidator.validateSearchQuery(query)) {
 *     is ValidationResult.Valid -> useValue(result.value)
 *     is ValidationResult.Invalid -> showError(result.message)
 * }
 * ```
 */
sealed class ValidationResult<out T> {
    /**
     * Validation passed. Contains the validated (and possibly sanitized) value.
     */
    data class Valid<T>(val value: T) : ValidationResult<T>()

    /**
     * Validation failed. Contains a user-friendly error message.
     */
    data class Invalid(val message: String) : ValidationResult<Nothing>()

    /**
     * Returns true if this is a Valid result.
     */
    val isValid: Boolean get() = this is Valid

    /**
     * Returns the value if Valid, or null if Invalid.
     */
    fun getOrNull(): T? = when (this) {
        is Valid -> value
        is Invalid -> null
    }

    /**
     * Returns the value if Valid, or the default if Invalid.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Valid -> value
        is Invalid -> default
    }

    /**
     * Maps the value if Valid, returns Invalid unchanged.
     */
    inline fun <R> map(transform: (T) -> R): ValidationResult<R> = when (this) {
        is Valid -> Valid(transform(value))
        is Invalid -> this
    }
}

/**
 * Input validation utilities for user-provided data.
 *
 * All validation functions return a [ValidationResult] that is either:
 * - [ValidationResult.Valid] with the sanitized value
 * - [ValidationResult.Invalid] with a user-friendly error message
 *
 * ## Thread Safety
 * All functions are stateless and thread-safe.
 */
object InputValidator {

    /**
     * Validate a search query string.
     *
     * - Empty/null queries are valid (returns empty string)
     * - Trims whitespace
     * - Rejects queries exceeding [ValidationConstants.MAX_SEARCH_LENGTH]
     *
     * @param query The search query to validate
     * @return Valid with trimmed query, or Invalid with error message
     */
    fun validateSearchQuery(query: String?): ValidationResult<String> {
        if (query.isNullOrBlank()) {
            return ValidationResult.Valid("")
        }

        val trimmed = query.trim()

        if (trimmed.length > ValidationConstants.MAX_SEARCH_LENGTH) {
            return ValidationResult.Invalid(
                "Search query too long (max ${ValidationConstants.MAX_SEARCH_LENGTH} characters)"
            )
        }

        return ValidationResult.Valid(trimmed)
    }

    /**
     * Validate a page number for pagination.
     *
     * - Null defaults to page 1
     * - Values below [ValidationConstants.MIN_PAGE] are clamped to MIN_PAGE
     * - Values above [ValidationConstants.MAX_PAGE] return Invalid
     *
     * @param page The page number to validate
     * @return Valid with clamped page number, or Invalid if exceeds max
     */
    fun validatePageNumber(page: Int?): ValidationResult<Int> {
        val p = page ?: ValidationConstants.MIN_PAGE

        if (p < ValidationConstants.MIN_PAGE) {
            return ValidationResult.Valid(ValidationConstants.MIN_PAGE)
        }

        if (p > ValidationConstants.MAX_PAGE) {
            return ValidationResult.Invalid(
                "Page number too high (max ${ValidationConstants.MAX_PAGE})"
            )
        }

        return ValidationResult.Valid(p)
    }

    /**
     * Parse and validate a page number from a string argument.
     *
     * @param pageArg The page argument string (may be null)
     * @return Valid with page number, or Invalid if not a valid number or out of range
     */
    fun parseAndValidatePageNumber(pageArg: String?): ValidationResult<Int> {
        if (pageArg.isNullOrBlank()) {
            return ValidationResult.Valid(ValidationConstants.MIN_PAGE)
        }

        val page = pageArg.toIntOrNull()
        if (page == null) {
            return ValidationResult.Invalid("Page must be a number")
        }

        return validatePageNumber(page)
    }

    /**
     * Validate a schematic ID.
     *
     * Schematic IDs must be 1-32 characters, alphanumeric with dashes and underscores.
     *
     * @param id The schematic ID to validate
     * @return Valid with the ID, or Invalid with error message
     */
    fun validateSchematicId(id: String?): ValidationResult<String> {
        if (id.isNullOrBlank()) {
            return ValidationResult.Invalid("Schematic ID is required")
        }

        val trimmed = id.trim()

        if (!trimmed.matches(ValidationConstants.SCHEMATIC_ID_REGEX)) {
            return ValidationResult.Invalid(
                "Invalid schematic ID format (use letters, numbers, dashes, underscores; max 32 chars)"
            )
        }

        return ValidationResult.Valid(trimmed)
    }

    /**
     * Validate and extract a QuickShare access code.
     *
     * Accepts:
     * - Raw codes (e.g., "abc123")
     * - Codes with qs_ prefix (e.g., "qs_abc123")
     * - Full URLs (extracts the code from the path)
     *
     * @param input The input string (code or URL)
     * @return Valid with extracted code, or Invalid with error message
     */
    fun validateQuickShareCode(input: String?): ValidationResult<String> {
        if (input.isNullOrBlank()) {
            return ValidationResult.Invalid("Quick share code is required")
        }

        val trimmed = input.trim()

        // Try to extract code from URL
        val code = extractQuickShareCode(trimmed)

        if (code == null) {
            return ValidationResult.Invalid("Invalid quick share code or URL format")
        }

        // Validate the extracted code format
        if (!code.matches(ValidationConstants.QUICKSHARE_CODE_REGEX)) {
            return ValidationResult.Invalid("Invalid quick share code format")
        }

        return ValidationResult.Valid(code)
    }

    /**
     * Extract a QuickShare code from a URL or raw input.
     *
     * @param input The input string
     * @return The extracted code, or null if extraction fails
     */
    private fun extractQuickShareCode(input: String): String? {
        // If it looks like a URL, extract the code from the path
        if (input.startsWith("http://") || input.startsWith("https://")) {
            // Try common URL patterns
            val patterns = listOf(
                Regex(".*/share/([a-zA-Z0-9_]+).*"),
                Regex(".*/quick-shares?/([a-zA-Z0-9_]+).*"),
                Regex(".*/qs/([a-zA-Z0-9_]+).*")
            )

            for (pattern in patterns) {
                val match = pattern.find(input)
                if (match != null && match.groupValues.size > 1) {
                    return match.groupValues[1]
                }
            }

            // Last resort: try to get the last path segment
            val lastSegment = input
                .substringAfterLast("/")
                .substringBefore("?")
                .substringBefore("#")

            if (lastSegment.isNotBlank() && lastSegment.matches(Regex("[a-zA-Z0-9_]+"))) {
                return lastSegment
            }

            return null
        }

        // It's a raw code, return as-is
        return input
    }

    /**
     * Validate a password for strength requirements.
     *
     * @param password The password to validate
     * @return Valid with the password, or Invalid with error message
     */
    fun validatePassword(password: String?): ValidationResult<String> {
        if (password.isNullOrEmpty()) {
            return ValidationResult.Invalid("Password is required")
        }

        if (password.length < ValidationConstants.MIN_PASSWORD_LENGTH) {
            return ValidationResult.Invalid(
                "Password must be at least ${ValidationConstants.MIN_PASSWORD_LENGTH} characters"
            )
        }

        if (password.length > ValidationConstants.MAX_PASSWORD_LENGTH) {
            return ValidationResult.Invalid(
                "Password too long (max ${ValidationConstants.MAX_PASSWORD_LENGTH} characters)"
            )
        }

        return ValidationResult.Valid(password)
    }

    /**
     * Validate a download format.
     *
     * @param format The format string
     * @param validFormats List of valid format strings
     * @return Valid with the format, or Invalid with error message
     */
    fun validateDownloadFormat(format: String?, validFormats: List<String> = listOf("schem", "schematic", "mcedit")): ValidationResult<String> {
        if (format.isNullOrBlank()) {
            // Default to first valid format
            return ValidationResult.Valid(validFormats.first())
        }

        val normalized = format.trim().lowercase()

        if (normalized !in validFormats) {
            return ValidationResult.Invalid(
                "Invalid format. Valid formats: ${validFormats.joinToString(", ")}"
            )
        }

        return ValidationResult.Valid(normalized)
    }

    /**
     * Validate schematic file size.
     *
     * @param sizeBytes The file size in bytes
     * @return Valid with the size, or Invalid if too large
     */
    fun validateSchematicSize(sizeBytes: Int): ValidationResult<Int> {
        if (sizeBytes <= 0) {
            return ValidationResult.Invalid("Schematic is empty")
        }

        if (sizeBytes > ValidationConstants.MAX_SCHEMATIC_SIZE_BYTES) {
            val maxMb = ValidationConstants.MAX_SCHEMATIC_SIZE_BYTES / (1024 * 1024)
            return ValidationResult.Invalid("Schematic too large (max ${maxMb}MB)")
        }

        return ValidationResult.Valid(sizeBytes)
    }
}
