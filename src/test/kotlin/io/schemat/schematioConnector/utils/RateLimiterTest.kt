package io.schemat.schematioConnector.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tests for RateLimiter per-player rate limiting.
 */
@DisplayName("RateLimiter")
class RateLimiterTest {

    private lateinit var rateLimiter: RateLimiter
    private val testPlayer1 = UUID.randomUUID()
    private val testPlayer2 = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create rate limiter with small values for testing
        rateLimiter = RateLimiter(maxRequests = 3, windowMs = 1000)
    }

    @Nested
    @DisplayName("tryAcquire")
    inner class TryAcquireTests {

        @Test
        fun `allows requests within limit`() {
            // First request should succeed
            val result1 = rateLimiter.tryAcquire(testPlayer1)
            assertNotNull(result1)
            assertEquals(2, result1) // 2 remaining after first request

            // Second request should succeed
            val result2 = rateLimiter.tryAcquire(testPlayer1)
            assertNotNull(result2)
            assertEquals(1, result2) // 1 remaining

            // Third request should succeed
            val result3 = rateLimiter.tryAcquire(testPlayer1)
            assertNotNull(result3)
            assertEquals(0, result3) // 0 remaining
        }

        @Test
        fun `blocks requests over limit`() {
            // Use up all 3 requests
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)

            // Fourth request should be blocked
            val result = rateLimiter.tryAcquire(testPlayer1)
            assertNull(result)
        }

        @Test
        fun `tracks different players independently`() {
            // Player 1 uses all requests
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            assertNull(rateLimiter.tryAcquire(testPlayer1)) // blocked

            // Player 2 should still be allowed
            val result = rateLimiter.tryAcquire(testPlayer2)
            assertNotNull(result)
            assertEquals(2, result)
        }
    }

    @Nested
    @DisplayName("canAcquire")
    inner class CanAcquireTests {

        @Test
        fun `returns true when under limit`() {
            assertTrue(rateLimiter.canAcquire(testPlayer1))
            rateLimiter.tryAcquire(testPlayer1)
            assertTrue(rateLimiter.canAcquire(testPlayer1))
        }

        @Test
        fun `returns false when at limit`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            assertFalse(rateLimiter.canAcquire(testPlayer1))
        }

        @Test
        fun `returns true for unknown player`() {
            assertTrue(rateLimiter.canAcquire(UUID.randomUUID()))
        }
    }

    @Nested
    @DisplayName("getRemainingRequests")
    inner class GetRemainingRequestsTests {

        @Test
        fun `returns max for new player`() {
            assertEquals(3, rateLimiter.getRemainingRequests(testPlayer1))
        }

        @Test
        fun `decreases with each request`() {
            rateLimiter.tryAcquire(testPlayer1)
            assertEquals(2, rateLimiter.getRemainingRequests(testPlayer1))

            rateLimiter.tryAcquire(testPlayer1)
            assertEquals(1, rateLimiter.getRemainingRequests(testPlayer1))

            rateLimiter.tryAcquire(testPlayer1)
            assertEquals(0, rateLimiter.getRemainingRequests(testPlayer1))
        }

        @Test
        fun `never goes negative`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1) // blocked
            assertEquals(0, rateLimiter.getRemainingRequests(testPlayer1))
        }
    }

    @Nested
    @DisplayName("getWaitTimeMs")
    inner class GetWaitTimeMsTests {

        @Test
        fun `returns 0 when under limit`() {
            assertEquals(0, rateLimiter.getWaitTimeMs(testPlayer1))
            rateLimiter.tryAcquire(testPlayer1)
            assertEquals(0, rateLimiter.getWaitTimeMs(testPlayer1))
        }

        @Test
        fun `returns positive value when at limit`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)

            val waitTime = rateLimiter.getWaitTimeMs(testPlayer1)
            assertTrue(waitTime > 0)
            assertTrue(waitTime <= 1000) // Within window
        }

        @Test
        fun `returns 0 for unknown player`() {
            assertEquals(0, rateLimiter.getWaitTimeMs(UUID.randomUUID()))
        }
    }

    @Nested
    @DisplayName("getWaitTimeSeconds")
    inner class GetWaitTimeSecondsTests {

        @Test
        fun `returns 0 when not rate limited`() {
            assertEquals(0, rateLimiter.getWaitTimeSeconds(testPlayer1))
        }

        @Test
        fun `rounds up to next second`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)

            val waitSeconds = rateLimiter.getWaitTimeSeconds(testPlayer1)
            assertTrue(waitSeconds >= 1)
        }
    }

    @Nested
    @DisplayName("removePlayer")
    inner class RemovePlayerTests {

        @Test
        fun `removes player data`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)

            rateLimiter.removePlayer(testPlayer1)

            // Player should have full quota again
            assertEquals(3, rateLimiter.getRemainingRequests(testPlayer1))
        }

        @Test
        fun `does not affect other players`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer2)

            rateLimiter.removePlayer(testPlayer1)

            // Player 2 should still have reduced quota
            assertEquals(2, rateLimiter.getRemainingRequests(testPlayer2))
        }
    }

    @Nested
    @DisplayName("clear")
    inner class ClearTests {

        @Test
        fun `removes all player data`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer2)

            rateLimiter.clear()

            assertEquals(3, rateLimiter.getRemainingRequests(testPlayer1))
            assertEquals(3, rateLimiter.getRemainingRequests(testPlayer2))
            assertEquals(0, rateLimiter.getTrackedPlayerCount())
        }
    }

    @Nested
    @DisplayName("cleanup")
    inner class CleanupTests {

        @Test
        fun `removes expired entries after window`() {
            // Create a rate limiter with very short window
            val shortLimiter = RateLimiter(maxRequests = 3, windowMs = 50)
            shortLimiter.tryAcquire(testPlayer1)

            // Wait for window to expire
            Thread.sleep(100)

            shortLimiter.cleanup()

            // Player should have full quota again
            assertEquals(3, shortLimiter.getRemainingRequests(testPlayer1))
        }
    }

    @Nested
    @DisplayName("getStats")
    inner class GetStatsTests {

        @Test
        fun `returns correct stats`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer2)

            val stats = rateLimiter.getStats()

            assertEquals(2, stats["trackedPlayers"])
            assertEquals(3, stats["totalActiveRequests"])
            assertEquals(3, stats["maxRequests"])
            assertEquals(1L, stats["windowSeconds"])
        }

        @Test
        fun `counts players at limit`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1) // At limit

            val stats = rateLimiter.getStats()
            assertEquals(1, stats["playersAtLimit"])
        }
    }

    @Nested
    @DisplayName("getTrackedPlayerCount")
    inner class GetTrackedPlayerCountTests {

        @Test
        fun `returns 0 initially`() {
            assertEquals(0, rateLimiter.getTrackedPlayerCount())
        }

        @Test
        fun `increases with new players`() {
            rateLimiter.tryAcquire(testPlayer1)
            assertEquals(1, rateLimiter.getTrackedPlayerCount())

            rateLimiter.tryAcquire(testPlayer2)
            assertEquals(2, rateLimiter.getTrackedPlayerCount())
        }

        @Test
        fun `does not increase for same player`() {
            rateLimiter.tryAcquire(testPlayer1)
            rateLimiter.tryAcquire(testPlayer1)
            assertEquals(1, rateLimiter.getTrackedPlayerCount())
        }
    }

    @Nested
    @DisplayName("Window expiration")
    inner class WindowExpirationTests {

        @Test
        fun `resets after window expires`() {
            // Create a rate limiter with very short window
            val shortLimiter = RateLimiter(maxRequests = 2, windowMs = 50)

            // Use all requests
            shortLimiter.tryAcquire(testPlayer1)
            shortLimiter.tryAcquire(testPlayer1)
            assertNull(shortLimiter.tryAcquire(testPlayer1)) // blocked

            // Wait for window to expire
            Thread.sleep(100)

            // Should be allowed again
            val result = shortLimiter.tryAcquire(testPlayer1)
            assertNotNull(result)
        }
    }
}
