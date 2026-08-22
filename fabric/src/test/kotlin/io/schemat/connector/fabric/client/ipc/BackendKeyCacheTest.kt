package io.schemat.connector.fabric.client.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class BackendKeyCacheTest {

    private val keyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    @Test
    fun `parses the well-known document`() {
        val keys = BackendKeyCache.parse(
            """{"keys":[{"kid":"k1","alg":"Ed25519","key":"$keyB64"}]}""",
        )
        assertEquals(setOf("k1"), keys.keys)
        assertTrue(keys.getValue("k1").contentEquals(ByteArray(32) { 5 }))
    }

    @Test
    fun `parses multiple keys (rotation) and skips junk entries`() {
        val keys = BackendKeyCache.parse(
            """{"keys":[
                {"kid":"k1","alg":"Ed25519","key":"$keyB64"},
                {"kid":"k0","alg":"Ed25519","key":"$keyB64"},
                {"kid":"rsa","alg":"RS256","key":"$keyB64"},
                {"kid":"short","alg":"Ed25519","key":"AAAA"},
                {"kid":"bad-b64","alg":"Ed25519","key":"!!!"},
                {"alg":"Ed25519","key":"$keyB64"},
                "not-an-object"
            ]}""",
        )
        assertEquals(setOf("k1", "k0"), keys.keys)
    }

    @Test
    fun `malformed documents parse to empty`() {
        assertEquals(emptyMap<String, ByteArray>(), BackendKeyCache.parse("not json"))
        assertEquals(emptyMap<String, ByteArray>(), BackendKeyCache.parse("{}"))
        assertEquals(emptyMap<String, ByteArray>(), BackendKeyCache.parse("""{"keys":"nope"}"""))
    }

    @Test
    fun `fetches once, caches, and refetches after invalidate`() {
        var calls = 0
        val cache = BackendKeyCache {
            calls++
            """{"keys":[{"kid":"k$calls","alg":"Ed25519","key":"$keyB64"}]}"""
        }
        assertEquals(setOf("k1"), cache.keysByKid().keys)
        assertEquals(setOf("k1"), cache.keysByKid().keys)
        assertEquals(1, calls)

        cache.invalidate()
        assertEquals(setOf("k2"), cache.keysByKid().keys)
        assertEquals(2, calls)
    }

    @Test
    fun `a failed fetch keeps the previous keys and does not cache the failure forever`() {
        var fail = false
        var calls = 0
        val cache = BackendKeyCache {
            calls++
            if (fail) null else """{"keys":[{"kid":"k1","alg":"Ed25519","key":"$keyB64"}]}"""
        }
        assertEquals(setOf("k1"), cache.keysByKid().keys)
        fail = true
        cache.invalidate()
        assertEquals(setOf("k1"), cache.keysByKid().keys) // stale-but-valid beats empty
        assertEquals(2, calls)
    }

    @Test
    fun `origin strips a trailing api version segment`() {
        assertEquals("https://schemat.io", AttestFlow.originOf("https://schemat.io/api/v1"))
        assertEquals("https://schemati.test", AttestFlow.originOf("https://schemati.test/api/v1/"))
        assertEquals("http://localhost:8080", AttestFlow.originOf("http://localhost:8080/api/v2"))
        assertEquals("https://schemat.io", AttestFlow.originOf("https://schemat.io"))
    }
}
