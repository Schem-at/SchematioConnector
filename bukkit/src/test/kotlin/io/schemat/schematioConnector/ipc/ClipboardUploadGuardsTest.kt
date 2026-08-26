package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardUploadOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ClipboardUploadGuardsTest {

    @Test
    fun `guard order is spec order - WE, clipboard, permission, attestation`() {
        assertEquals(
            ClipboardUploadGuards.Failure.WORLDEDIT_MISSING,
            ClipboardUploadGuards.firstFailure(false, false, false, requireAttested = true, attested = false),
        )
        assertEquals(
            ClipboardUploadGuards.Failure.EMPTY_CLIPBOARD,
            ClipboardUploadGuards.firstFailure(true, false, false, requireAttested = true, attested = false),
        )
        assertEquals(
            ClipboardUploadGuards.Failure.NO_PERMISSION,
            ClipboardUploadGuards.firstFailure(true, true, false, requireAttested = true, attested = false),
        )
        assertEquals(
            ClipboardUploadGuards.Failure.NOT_ATTESTED,
            ClipboardUploadGuards.firstFailure(true, true, true, requireAttested = true, attested = false),
        )
        assertNull(
            ClipboardUploadGuards.firstFailure(true, true, true, requireAttested = true, attested = true),
        )
    }

    @Test
    fun `the standalone chat path never requires attestation`() {
        assertNull(
            ClipboardUploadGuards.firstFailure(true, true, true, requireAttested = false, attested = false),
        )
    }

    @Test
    fun `failure states map to the spec's terminal statuses`() {
        assertEquals(StatusState.UNAVAILABLE, ClipboardUploadGuards.Failure.WORLDEDIT_MISSING.state)
        assertEquals(StatusState.UNAVAILABLE, ClipboardUploadGuards.Failure.EMPTY_CLIPBOARD.state)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.Failure.NO_PERMISSION.state)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.Failure.NOT_ATTESTED.state)
    }

    @Test
    fun `token bucket allows 2 per minute then limits`() {
        val limiter = RateLimiter(
            maxRequests = ClipboardUploadGuards.REQUESTS_PER_MINUTE,
            windowMs = ClipboardUploadGuards.WINDOW_MS,
        )
        val player = UUID.randomUUID()
        repeat(2) { assertNotNull(limiter.tryAcquire(player), "request ${it + 1} should pass") }
        assertNull(limiter.tryAcquire(player), "3rd request within the window must be limited")
        assertNotNull(limiter.tryAcquire(UUID.randomUUID()), "other players unaffected")
    }

    @Test
    fun `upload outcomes map to exactly one terminal status`() {
        assertNull(ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Created("d", "url", "exp")))
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.NotLinked)!!.first)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Denied)!!.first)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.QuotaExceeded)!!.first)
        assertEquals(StatusState.TOO_LARGE, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.TooLarge)!!.first)
        assertEquals(StatusState.RATE_LIMITED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.RateLimited)!!.first)
        assertEquals(StatusState.UNAVAILABLE, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Unavailable)!!.first)
        assertEquals(StatusState.ERROR, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Error)!!.first)
        // The quota detail must tell the user WHAT to do (it is not a retry-later case).
        assertTrue(ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.QuotaExceeded)!!.second.contains("publish or delete", ignoreCase = true))
    }
}
