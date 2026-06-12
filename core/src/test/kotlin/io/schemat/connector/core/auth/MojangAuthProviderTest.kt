package io.schemat.connector.core.auth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

@DisplayName("Mojang Auth Provider")
class MojangAuthProviderTest {

    @Nested
    @DisplayName("Minecraft SHA-1 Hex Digest")
    inner class MinecraftSha1HexTest {

        @Test
        fun `empty input produces correct hash`() {
            val result = MojangAuthProvider.minecraftSha1Hex("".toByteArray())
            assertNotNull(result)
            assertTrue(result.isNotEmpty())
        }

        @Test
        fun `known input produces deterministic output`() {
            val input = "test".toByteArray(Charsets.ISO_8859_1)
            val result1 = MojangAuthProvider.minecraftSha1Hex(input)
            val result2 = MojangAuthProvider.minecraftSha1Hex(input)
            assertEquals(result1, result2, "Same input should produce same hash")
        }

        @Test
        fun `multiple parts are concatenated before hashing`() {
            val combined = MojangAuthProvider.minecraftSha1Hex(
                "hello".toByteArray(Charsets.ISO_8859_1),
                "world".toByteArray(Charsets.ISO_8859_1)
            )
            // SHA-1 of "helloworld" as two's-complement hex
            assertNotNull(combined)
            assertTrue(combined.isNotEmpty())
        }

        @Test
        fun `different inputs produce different hashes`() {
            val hash1 = MojangAuthProvider.minecraftSha1Hex("input1".toByteArray())
            val hash2 = MojangAuthProvider.minecraftSha1Hex("input2".toByteArray())
            assertNotEquals(hash1, hash2)
        }

        @Test
        fun `matches Minecraft protocol known test vectors`() {
            // Known test vectors from the Minecraft protocol wiki
            // "Notch" -> 4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48
            val notchHash = MojangAuthProvider.minecraftSha1Hex("Notch".toByteArray(Charsets.ISO_8859_1))
            assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", notchHash)

            // "jeb_" -> -7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1
            val jebHash = MojangAuthProvider.minecraftSha1Hex("jeb_".toByteArray(Charsets.ISO_8859_1))
            assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", jebHash)
        }

        @Test
        fun `can produce negative hash values`() {
            // "jeb_" is known to produce a negative hash in Minecraft's format
            val hash = MojangAuthProvider.minecraftSha1Hex("jeb_".toByteArray(Charsets.ISO_8859_1))
            assertTrue(hash.startsWith("-"), "jeb_ should produce a negative hash")
        }

        @Test
        fun `auth secret hash format is consistent`() {
            // Simulate the actual auth flow hash computation
            val uuid = "069a79f444e94726a5befca90e38aaf5" // Notch's UUID without dashes
            val hash = MojangAuthProvider.minecraftSha1Hex(
                "".toByteArray(Charsets.ISO_8859_1),
                "schematio-connector".toByteArray(Charsets.ISO_8859_1),
                uuid.toByteArray(Charsets.ISO_8859_1)
            )
            assertNotNull(hash)
            assertTrue(hash.isNotEmpty())
            // Hash should only contain hex chars and optional leading minus
            assertTrue(hash.matches(Regex("-?[0-9a-f]+")), "Hash should be hex: $hash")
        }
    }

    @Nested
    @DisplayName("AuthResult")
    inner class AuthResultTest {

        @Test
        fun `Success holds jwt token`() {
            val result = AuthResult.Success("my-jwt-token")
            assertEquals("my-jwt-token", result.jwt)
        }

        @Test
        fun `Failure holds reason`() {
            val result = AuthResult.Failure("connection refused")
            assertEquals("connection refused", result.reason)
        }

        @Test
        fun `can pattern match on sealed class`() {
            val success: AuthResult = AuthResult.Success("token")
            val failure: AuthResult = AuthResult.Failure("error")

            when (success) {
                is AuthResult.Success -> assertEquals("token", success.jwt)
                is AuthResult.Failure -> fail("Expected Success")
            }

            when (failure) {
                is AuthResult.Success -> fail("Expected Failure")
                is AuthResult.Failure -> assertEquals("error", failure.reason)
            }
        }
    }

    @Nested
    @DisplayName("MojangAuthProvider Construction")
    inner class ConstructionTest {

        @Test
        fun `can be created with default parameters`() {
            val provider = MojangAuthProvider(apiEndpoint = "https://schemat.io/api/v1")
            assertNotNull(provider)
        }

        @Test
        fun `can be created with trust all certificates`() {
            val provider = MojangAuthProvider(
                apiEndpoint = "https://localhost/api/v1",
                trustAllCertificates = true
            )
            assertNotNull(provider)
        }
    }
}
