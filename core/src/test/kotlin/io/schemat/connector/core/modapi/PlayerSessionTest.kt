package io.schemat.connector.core.modapi

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PlayerSessionTest {

    private fun token(expiresAt: Instant, sub: String = "11111111-2222-3333-4444-555555555555"): String =
        JWT.create().withSubject(sub).withExpiresAt(Date.from(expiresAt)).sign(Algorithm.HMAC256("test"))

    @Test
    fun `parses subject and expiry`() {
        val exp = Instant.now().plusSeconds(3600)
        val session = PlayerSession.fromToken(token(exp))!!
        assertEquals("11111111-2222-3333-4444-555555555555", session.playerUuid)
        assertFalse(session.isExpired())
    }

    @Test
    fun `expired token reports expired (with safety margin)`() {
        val session = PlayerSession.fromToken(token(Instant.now().plusSeconds(20)))!!
        // 30s safety margin: a token expiring in 20s counts as expired
        assertTrue(session.isExpired(marginSeconds = 30))
    }

    @Test
    fun `token already past expiry reports expired with zero margin`() {
        val session = PlayerSession.fromToken(token(Instant.now().minusSeconds(60)))!!
        assertTrue(session.isExpired(marginSeconds = 0))
    }

    @Test
    fun `token without exp claim never expires`() {
        val token = JWT.create().withSubject("abc").sign(Algorithm.HMAC256("test"))
        val session = PlayerSession.fromToken(token)!!
        assertEquals("abc", session.playerUuid)
        assertFalse(session.isExpired(marginSeconds = 30))
    }

    @Test
    fun `token without subject has null playerUuid`() {
        val token = JWT.create().withExpiresAt(Date.from(Instant.now().plusSeconds(3600))).sign(Algorithm.HMAC256("test"))
        val session = PlayerSession.fromToken(token)!!
        assertNull(session.playerUuid)
        assertFalse(session.isExpired())
    }

    @Test
    fun `isExpired honors injected clock`() {
        val exp = Instant.parse("2026-06-10T12:00:00Z")
        val session = PlayerSession.fromToken(token(exp))!!
        assertFalse(session.isExpired(marginSeconds = 0, now = exp.minusSeconds(120)))
        assertTrue(session.isExpired(marginSeconds = 0, now = exp.plusSeconds(1)))
        // margin pushes the effective expiry earlier
        assertTrue(session.isExpired(marginSeconds = 300, now = exp.minusSeconds(120)))
    }

    @Test
    fun `garbage token returns null`() {
        assertNull(PlayerSession.fromToken("not-a-jwt"))
    }

    @Test
    fun `empty token returns null`() {
        assertNull(PlayerSession.fromToken(""))
    }

    @Test
    fun `keeps the raw token for transport use`() {
        val raw = token(Instant.now().plusSeconds(3600))
        val session = PlayerSession.fromToken(raw)!!
        assertEquals(raw, session.token)
    }

    // ---- uuid normalization (Mojang-issued tokens carry undashed subs) ----

    @Test
    fun `undashed 32-hex sub is normalized to dashed lowercase`() {
        val session = PlayerSession.fromToken(
            token(Instant.now().plusSeconds(3600), sub = "AbCdEf01234567890123456789AbCdEf"),
        )!!
        assertEquals("abcdef01-2345-6789-0123-456789abcdef", session.playerUuid)
    }

    @Test
    fun `already-dashed sub is left unchanged`() {
        val session = PlayerSession.fromToken(
            token(Instant.now().plusSeconds(3600), sub = "11111111-2222-3333-4444-555555555555"),
        )!!
        assertEquals("11111111-2222-3333-4444-555555555555", session.playerUuid)
    }

    @Test
    fun `non-uuid sub is left as-is`() {
        val opaque = "player:steve" // not 32 hex chars - must pass through untouched
        val session = PlayerSession.fromToken(token(Instant.now().plusSeconds(3600), sub = opaque))!!
        assertEquals(opaque, session.playerUuid)
    }
}
