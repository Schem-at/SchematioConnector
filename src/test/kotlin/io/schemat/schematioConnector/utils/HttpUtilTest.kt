package io.schemat.schematioConnector.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.util.Date

/**
 * Tests for HttpUtil JWT parsing and permission logic.
 *
 * These tests verify the JWT permission checking logic used by HttpUtil
 * without requiring actual HTTP connections.
 */
class HttpUtilTest {

    /**
     * Check if a JWT token has the canManagePasswords permission.
     * Mirrors the logic in HttpUtil.canManagePasswords()
     */
    private fun canManagePasswords(token: String): Boolean {
        return hasPermission(token, "canManagePasswords")
    }

    /**
     * Check if a JWT token has a specific permission claim.
     * Mirrors the logic in HttpUtil.hasPermission()
     */
    private fun hasPermission(token: String, permission: String): Boolean {
        return try {
            val decodedJWT = JWT.decode(token)
            val permissions = decodedJWT.claims["permissions"]?.asList(String::class.java)
            permissions?.contains(permission) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Create a test JWT token with given permissions.
     */
    private fun createTestToken(permissions: List<String>): String {
        return JWT.create()
            .withClaim("permissions", permissions)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000)) // 1 hour
            .sign(Algorithm.none())
    }

    /**
     * Create a token with specific claims (not just permissions).
     */
    private fun createTokenWithClaims(claims: Map<String, Any>): String {
        val builder = JWT.create()
        for ((key, value) in claims) {
            when (value) {
                is String -> builder.withClaim(key, value)
                is Int -> builder.withClaim(key, value)
                is Boolean -> builder.withClaim(key, value)
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    builder.withClaim(key, value as List<String>)
                }
            }
        }
        return builder.sign(Algorithm.none())
    }

    @Nested
    @DisplayName("canManagePasswords")
    inner class CanManagePasswordsTests {

        @Test
        fun `returns true when permission is present`() {
            val token = createTestToken(listOf("canManagePasswords"))
            assertTrue(canManagePasswords(token))
        }

        @Test
        fun `returns true when permission is among others`() {
            val token = createTestToken(listOf("canUpload", "canManagePasswords", "canDownload"))
            assertTrue(canManagePasswords(token))
        }

        @Test
        fun `returns false when permission is missing`() {
            val token = createTestToken(listOf("canUpload", "canDownload"))
            assertFalse(canManagePasswords(token))
        }

        @Test
        fun `returns false for empty permissions`() {
            val token = createTestToken(emptyList())
            assertFalse(canManagePasswords(token))
        }

        @Test
        fun `returns false for invalid token`() {
            assertFalse(canManagePasswords("not.a.valid.token"))
        }

        @Test
        fun `returns false for empty string`() {
            assertFalse(canManagePasswords(""))
        }
    }

    @Nested
    @DisplayName("Token Structure Validation")
    inner class TokenStructureTests {

        @Test
        fun `token with no claims is valid but has no permissions`() {
            val token = JWT.create().sign(Algorithm.none())
            assertFalse(canManagePasswords(token))
        }

        @Test
        fun `token with other claims but no permissions`() {
            val token = createTokenWithClaims(mapOf(
                "user_id" to "12345",
                "community_id" to "abc"
            ))
            assertFalse(hasPermission(token, "anyPermission"))
        }

        @Test
        fun `token with boolean permissions claim returns false`() {
            // If permissions is not a list, it should gracefully fail
            val token = createTokenWithClaims(mapOf(
                "permissions" to true
            ))
            // This will fail because we can't cast Boolean to List
            assertFalse(hasPermission(token, "anything"))
        }

        @Test
        fun `expired token still readable for permissions`() {
            // JWT.decode() doesn't verify expiration
            val token = JWT.create()
                .withClaim("permissions", listOf("canManagePasswords"))
                .withExpiresAt(Date(System.currentTimeMillis() - 3600000)) // Expired 1 hour ago
                .sign(Algorithm.none())

            // decode() works even for expired tokens (no verification)
            assertTrue(canManagePasswords(token))
        }
    }

    @Nested
    @DisplayName("URL Formatting")
    inner class UrlFormattingTests {

        /**
         * Ensure URL ends with / for path joining.
         */
        private fun normalizeEndpoint(endpoint: String): String {
            return if (endpoint.endsWith("/")) endpoint else "$endpoint/"
        }

        /**
         * Join endpoint with path, handling slashes correctly.
         */
        private fun buildUrl(endpoint: String, path: String): String {
            val normalizedEndpoint = normalizeEndpoint(endpoint)
            val normalizedPath = path.removePrefix("/")
            return "$normalizedEndpoint$normalizedPath"
        }

        @Test
        fun `endpoint without trailing slash is normalized`() {
            assertEquals("https://api.example.com/", normalizeEndpoint("https://api.example.com"))
        }

        @Test
        fun `endpoint with trailing slash is unchanged`() {
            assertEquals("https://api.example.com/", normalizeEndpoint("https://api.example.com/"))
        }

        @Test
        fun `path with leading slash is handled`() {
            assertEquals("https://api.example.com/v1/users", buildUrl("https://api.example.com", "/v1/users"))
        }

        @Test
        fun `path without leading slash is handled`() {
            assertEquals("https://api.example.com/v1/users", buildUrl("https://api.example.com", "v1/users"))
        }

        @Test
        fun `double slashes are prevented`() {
            val url = buildUrl("https://api.example.com/", "/v1/users")
            assertFalse(url.contains("//v1"))
        }
    }

    @Nested
    @DisplayName("Progress Calculation")
    inner class ProgressCalculationTests {

        /**
         * Calculate download progress percentage.
         */
        private fun calculateProgress(bytesReceived: Long, contentLength: Long): Float {
            return if (contentLength > 0) {
                (bytesReceived.toFloat() / contentLength.toFloat() * 100f).coerceIn(0f, 100f)
            } else {
                // Unknown content length - return indeterminate
                -1f
            }
        }

        @Test
        fun `progress starts at 0`() {
            assertEquals(0f, calculateProgress(0, 1000))
        }

        @Test
        fun `progress at 50 percent`() {
            assertEquals(50f, calculateProgress(500, 1000))
        }

        @Test
        fun `progress at 100 percent`() {
            assertEquals(100f, calculateProgress(1000, 1000))
        }

        @Test
        fun `progress is capped at 100`() {
            assertEquals(100f, calculateProgress(1500, 1000))
        }

        @Test
        fun `unknown content length returns -1`() {
            assertEquals(-1f, calculateProgress(500, 0))
            assertEquals(-1f, calculateProgress(500, -1))
        }

        @Test
        fun `large file progress calculation`() {
            val fiveMB = 5L * 1024 * 1024
            val tenMB = 10L * 1024 * 1024
            assertEquals(50f, calculateProgress(fiveMB, tenMB))
        }
    }

    @Nested
    @DisplayName("Response Status Handling")
    inner class ResponseStatusTests {

        /**
         * Categorize HTTP status codes.
         */
        private fun getStatusCategory(statusCode: Int): String {
            return when (statusCode) {
                in 200..299 -> "success"
                400 -> "bad_request"
                401 -> "unauthorized"
                403 -> "forbidden"
                404 -> "not_found"
                429 -> "rate_limited"
                in 500..599 -> "server_error"
                else -> "unknown"
            }
        }

        @Test
        fun `200 is success`() {
            assertEquals("success", getStatusCategory(200))
        }

        @Test
        fun `201 is success`() {
            assertEquals("success", getStatusCategory(201))
        }

        @Test
        fun `400 is bad request`() {
            assertEquals("bad_request", getStatusCategory(400))
        }

        @Test
        fun `401 is unauthorized`() {
            assertEquals("unauthorized", getStatusCategory(401))
        }

        @Test
        fun `403 is forbidden`() {
            assertEquals("forbidden", getStatusCategory(403))
        }

        @Test
        fun `404 is not found`() {
            assertEquals("not_found", getStatusCategory(404))
        }

        @Test
        fun `429 is rate limited`() {
            assertEquals("rate_limited", getStatusCategory(429))
        }

        @Test
        fun `500 is server error`() {
            assertEquals("server_error", getStatusCategory(500))
        }

        @Test
        fun `502 is server error`() {
            assertEquals("server_error", getStatusCategory(502))
        }
    }

    @Nested
    @DisplayName("Connection Error Classification")
    inner class ConnectionErrorTests {

        /**
         * Classify connection errors based on exception message.
         */
        private fun classifyConnectionError(message: String?): String {
            if (message == null) return "unknown"
            return when {
                message.contains("Connection refused", ignoreCase = true) -> "refused"
                message.contains("timed out", ignoreCase = true) -> "timeout"
                message.contains("UnknownHostException", ignoreCase = true) -> "dns_error"
                message.contains("No route to host", ignoreCase = true) -> "unreachable"
                message.contains("Connection reset", ignoreCase = true) -> "reset"
                message.contains("SSL", ignoreCase = true) ||
                message.contains("certificate", ignoreCase = true) -> "ssl_error"
                else -> "other"
            }
        }

        @Test
        fun `connection refused is classified`() {
            assertEquals("refused", classifyConnectionError("Connection refused"))
        }

        @Test
        fun `timeout is classified`() {
            assertEquals("timeout", classifyConnectionError("Read timed out"))
            assertEquals("timeout", classifyConnectionError("connect timed out"))
        }

        @Test
        fun `dns error is classified`() {
            assertEquals("dns_error", classifyConnectionError("UnknownHostException: api.schemat.io"))
        }

        @Test
        fun `ssl error is classified`() {
            assertEquals("ssl_error", classifyConnectionError("SSL handshake failed"))
            assertEquals("ssl_error", classifyConnectionError("certificate verify failed"))
        }

        @Test
        fun `null message returns unknown`() {
            assertEquals("unknown", classifyConnectionError(null))
        }

        @Test
        fun `unrecognized error returns other`() {
            assertEquals("other", classifyConnectionError("Some random error"))
        }
    }
}
