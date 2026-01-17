package io.schemat.schematioConnector.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.util.Date

/**
 * Tests for JWT token parsing and permission checking.
 *
 * These tests verify the logic used in HttpUtil for checking
 * token permissions without requiring network calls.
 */
class JwtParsingTest {

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
     * Note: In production, tokens are signed by the server.
     * For testing, we create unsigned tokens (JWT.decode works on unsigned tokens).
     */
    private fun createTestToken(permissions: List<String>): String {
        return JWT.create()
            .withClaim("permissions", permissions)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000)) // 1 hour
            .sign(Algorithm.none()) // Unsigned for testing
    }

    @Nested
    @DisplayName("Permission Checking")
    inner class PermissionTests {

        @Test
        fun `token with permission returns true`() {
            val token = createTestToken(listOf("canManagePasswords", "canUpload"))
            assertTrue(hasPermission(token, "canManagePasswords"))
        }

        @Test
        fun `token without permission returns false`() {
            val token = createTestToken(listOf("canUpload"))
            assertFalse(hasPermission(token, "canManagePasswords"))
        }

        @Test
        fun `token with empty permissions returns false`() {
            val token = createTestToken(emptyList())
            assertFalse(hasPermission(token, "canManagePasswords"))
        }

        @Test
        fun `token with multiple permissions`() {
            val token = createTestToken(listOf("canUpload", "canDownload", "canManagePasswords"))
            assertTrue(hasPermission(token, "canUpload"))
            assertTrue(hasPermission(token, "canDownload"))
            assertTrue(hasPermission(token, "canManagePasswords"))
            assertFalse(hasPermission(token, "canDelete"))
        }

        @Test
        fun `invalid token returns false`() {
            assertFalse(hasPermission("not.a.valid.jwt", "canManagePasswords"))
        }

        @Test
        fun `empty token returns false`() {
            assertFalse(hasPermission("", "canManagePasswords"))
        }

        @Test
        fun `permission check is case sensitive`() {
            val token = createTestToken(listOf("canManagePasswords"))
            assertFalse(hasPermission(token, "CANMANAGEPASSWORDS"))
            assertFalse(hasPermission(token, "canmanagepasswords"))
        }
    }

    @Nested
    @DisplayName("Token Structure")
    inner class TokenStructureTests {

        @Test
        fun `can decode token without verifying signature`() {
            val token = createTestToken(listOf("test"))
            val decoded = JWT.decode(token)
            assertNotNull(decoded)
        }

        @Test
        fun `can read expiration claim`() {
            val token = createTestToken(listOf("test"))
            val decoded = JWT.decode(token)
            assertNotNull(decoded.expiresAt)
        }

        @Test
        fun `token without permissions claim returns false for any permission`() {
            // Create token without permissions claim
            val token = JWT.create()
                .withClaim("user_id", "12345")
                .sign(Algorithm.none())

            assertFalse(hasPermission(token, "canManagePasswords"))
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `permission with special characters`() {
            val token = createTestToken(listOf("can:manage:passwords", "can.upload"))
            assertTrue(hasPermission(token, "can:manage:passwords"))
            assertTrue(hasPermission(token, "can.upload"))
        }

        @Test
        fun `very long permission list`() {
            val permissions = (1..100).map { "permission_$it" }
            val token = createTestToken(permissions)
            assertTrue(hasPermission(token, "permission_50"))
            assertTrue(hasPermission(token, "permission_100"))
            assertFalse(hasPermission(token, "permission_101"))
        }

        @Test
        fun `whitespace in permission name`() {
            val token = createTestToken(listOf("can manage passwords"))
            assertTrue(hasPermission(token, "can manage passwords"))
        }

        @Test
        fun `unicode in permission name`() {
            val token = createTestToken(listOf("can_manage_\u00E9"))
            assertTrue(hasPermission(token, "can_manage_\u00E9"))
        }
    }
}
