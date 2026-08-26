package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardResolveOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class LoadRequestGuardsTest {

    @Test
    fun `guard order is UNAVAILABLE then DENIED (attest) then DENIED (permission)`() {
        // Spec invariant 2: an unattested session is DENIED before any permission
        // or rate-limit consideration — and the caller never reaches the backend.
        assertEquals(
            LoadRequestGuards.Failure.WORLDEDIT_MISSING,
            LoadRequestGuards.firstFailure(worldEditAvailable = false, attested = false, hasPermission = false),
        )
        assertEquals(
            LoadRequestGuards.Failure.NOT_ATTESTED,
            LoadRequestGuards.firstFailure(worldEditAvailable = true, attested = false, hasPermission = false),
        )
        assertEquals(
            LoadRequestGuards.Failure.NO_PERMISSION,
            LoadRequestGuards.firstFailure(worldEditAvailable = true, attested = true, hasPermission = false),
        )
        assertNull(
            LoadRequestGuards.firstFailure(worldEditAvailable = true, attested = true, hasPermission = true),
        )
    }

    @Test
    fun `failure states map to the spec's terminal statuses`() {
        assertEquals(StatusState.UNAVAILABLE, LoadRequestGuards.Failure.WORLDEDIT_MISSING.state)
        assertEquals(StatusState.DENIED, LoadRequestGuards.Failure.NOT_ATTESTED.state)
        assertEquals(StatusState.DENIED, LoadRequestGuards.Failure.NO_PERMISSION.state)
    }

    @Test
    fun `token bucket allows 5 per minute then limits`() {
        val limiter = RateLimiter(
            maxRequests = LoadRequestGuards.REQUESTS_PER_MINUTE,
            windowMs = LoadRequestGuards.WINDOW_MS,
        )
        val player = UUID.randomUUID()
        repeat(5) { assertNotNull(limiter.tryAcquire(player), "request ${it + 1} should pass") }
        assertNull(limiter.tryAcquire(player), "6th request within the window must be limited")
        // Another player is unaffected (per-player bucket).
        assertNotNull(limiter.tryAcquire(UUID.randomUUID()))
    }

    @Test
    fun `resolve outcomes map to exactly one terminal status`() {
        assertNull(LoadRequestGuards.statusFor(ClipboardResolveOutcome.Bytes(ByteArray(1), "schem")))
        assertEquals(StatusState.DENIED, LoadRequestGuards.statusFor(ClipboardResolveOutcome.Denied)!!.first)
        assertEquals(StatusState.NOT_FOUND, LoadRequestGuards.statusFor(ClipboardResolveOutcome.NotFound)!!.first)
        assertEquals(StatusState.TOO_LARGE, LoadRequestGuards.statusFor(ClipboardResolveOutcome.TooLarge)!!.first)
        assertEquals(StatusState.RATE_LIMITED, LoadRequestGuards.statusFor(ClipboardResolveOutcome.RateLimited)!!.first)
        assertEquals(StatusState.UNAVAILABLE, LoadRequestGuards.statusFor(ClipboardResolveOutcome.Unavailable)!!.first)
        assertEquals(StatusState.ERROR, LoadRequestGuards.statusFor(ClipboardResolveOutcome.Error)!!.first)
    }
}
