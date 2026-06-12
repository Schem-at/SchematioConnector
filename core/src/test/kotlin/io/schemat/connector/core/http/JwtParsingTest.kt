package io.schemat.connector.core.http

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.util.Date

@DisplayName("JWT Parsing and Permission Checking")
class JwtParsingTest {

    private fun hasPermission(token: String, permission: String): Boolean {
        return try {
            val decodedJWT = JWT.decode(token)
            val permissions = decodedJWT.claims["permissions"]?.asList(String::class.java)
            permissions?.contains(permission) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun canManagePasswords(token: String): Boolean {
        return hasPermission(token, "canManagePasswords")
    }

    private fun createTestToken(permissions: List<String>): String {
        return JWT.create()
            .withClaim("permissions", permissions)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.none())
    }

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
            val token = JWT.create()
                .withClaim("user_id", "12345")
                .sign(Algorithm.none())

            assertFalse(hasPermission(token, "canManagePasswords"))
        }

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
            val token = createTokenWithClaims(mapOf(
                "permissions" to true
            ))
            assertFalse(hasPermission(token, "anything"))
        }

        @Test
        fun `expired token still readable for permissions`() {
            val token = JWT.create()
                .withClaim("permissions", listOf("canManagePasswords"))
                .withExpiresAt(Date(System.currentTimeMillis() - 3600000))
                .sign(Algorithm.none())

            assertTrue(canManagePasswords(token))
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
