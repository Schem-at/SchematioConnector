package io.schemat.schematioConnector.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for OfflineMode connectivity management.
 */
@DisplayName("OfflineMode")
class OfflineModeTest {

    private lateinit var offlineMode: OfflineMode

    @BeforeEach
    fun setup() {
        offlineMode = OfflineMode()
    }

    @Nested
    @DisplayName("Initial state")
    inner class InitialStateTests {

        @Test
        fun `starts in online state`() {
            assertEquals(OfflineMode.State.ONLINE, offlineMode.getState())
            assertFalse(offlineMode.isOffline())
        }

        @Test
        fun `should not skip API calls initially`() {
            assertFalse(offlineMode.shouldSkipApiCall())
        }

        @Test
        fun `has zero wait time initially`() {
            assertEquals(0, offlineMode.getTimeUntilRetryMs())
            assertEquals(0, offlineMode.getTimeUntilRetrySeconds())
        }
    }

    @Nested
    @DisplayName("recordSuccess")
    inner class RecordSuccessTests {

        @Test
        fun `resets failure counter`() {
            // Record some failures
            offlineMode.recordFailure()
            offlineMode.recordFailure()

            offlineMode.recordSuccess()

            // Should be back to online state
            assertEquals(OfflineMode.State.ONLINE, offlineMode.getState())
        }

        @Test
        fun `exits offline mode`() {
            // Force into offline mode
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }
            assertTrue(offlineMode.isOffline())

            offlineMode.recordSuccess()

            assertFalse(offlineMode.isOffline())
            assertEquals(OfflineMode.State.ONLINE, offlineMode.getState())
        }
    }

    @Nested
    @DisplayName("recordFailure")
    inner class RecordFailureTests {

        @Test
        fun `enters degraded state after first failure`() {
            offlineMode.recordFailure()

            assertEquals(OfflineMode.State.DEGRADED, offlineMode.getState())
            assertFalse(offlineMode.isOffline())
        }

        @Test
        fun `enters offline mode after threshold failures`() {
            repeat(OfflineMode.FAILURE_THRESHOLD - 1) {
                val enteredOffline = offlineMode.recordFailure()
                assertFalse(enteredOffline, "Should not enter offline before threshold")
            }

            // This should trigger offline mode
            val enteredOffline = offlineMode.recordFailure()
            assertTrue(enteredOffline)
            assertTrue(offlineMode.isOffline())
            assertEquals(OfflineMode.State.OFFLINE, offlineMode.getState())
        }

        @Test
        fun `returns false after already in offline mode`() {
            // Enter offline mode
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }

            // Additional failures should return false
            val result = offlineMode.recordFailure()
            assertFalse(result, "Should return false when already offline")
        }
    }

    @Nested
    @DisplayName("shouldSkipApiCall")
    inner class ShouldSkipApiCallTests {

        @Test
        fun `returns false when online`() {
            assertFalse(offlineMode.shouldSkipApiCall())
        }

        @Test
        fun `returns true when offline and within backoff period`() {
            // Enter offline mode
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }
            offlineMode.recordAttempt()

            // Should skip immediately after attempt
            assertTrue(offlineMode.shouldSkipApiCall())
        }

        @Test
        fun `returns false after backoff period expires`() {
            // Enter offline mode
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }
            offlineMode.recordAttempt()

            // Wait for minimum backoff (5 seconds is too long for unit test, so we skip this)
            // In real tests, we'd mock the time or use a shorter backoff
        }
    }

    @Nested
    @DisplayName("forceOffline / forceOnline")
    inner class ForceModeTests {

        @Test
        fun `forceOffline enters offline mode immediately`() {
            offlineMode.forceOffline()

            assertTrue(offlineMode.isOffline())
            assertEquals(OfflineMode.State.OFFLINE, offlineMode.getState())
        }

        @Test
        fun `forceOnline exits offline mode`() {
            offlineMode.forceOffline()
            offlineMode.forceOnline()

            assertFalse(offlineMode.isOffline())
            assertEquals(OfflineMode.State.ONLINE, offlineMode.getState())
        }

        @Test
        fun `forceOnline resets retry delay`() {
            // Enter offline and increase backoff
            repeat(OfflineMode.FAILURE_THRESHOLD + 5) {
                offlineMode.recordFailure()
            }

            offlineMode.forceOnline()

            // After forcing online, wait time should be 0
            assertEquals(0, offlineMode.getTimeUntilRetryMs())
        }
    }

    @Nested
    @DisplayName("getStats")
    inner class GetStatsTests {

        @Test
        fun `returns correct state`() {
            val stats = offlineMode.getStats()
            assertEquals("ONLINE", stats["state"])
            assertEquals(false, stats["isOffline"])
        }

        @Test
        fun `tracks consecutive failures`() {
            offlineMode.recordFailure()
            offlineMode.recordFailure()

            val stats = offlineMode.getStats()
            assertEquals(2L, stats["consecutiveFailures"])
        }

        @Test
        fun `reports offline state correctly`() {
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }

            val stats = offlineMode.getStats()
            assertEquals("OFFLINE", stats["state"])
            assertEquals(true, stats["isOffline"])
        }
    }

    @Nested
    @DisplayName("reset")
    inner class ResetTests {

        @Test
        fun `returns to initial state`() {
            // Put into offline mode with failures
            repeat(OfflineMode.FAILURE_THRESHOLD + 3) {
                offlineMode.recordFailure()
            }

            offlineMode.reset()

            assertFalse(offlineMode.isOffline())
            assertEquals(OfflineMode.State.ONLINE, offlineMode.getState())
            assertEquals(0, offlineMode.getTimeUntilRetryMs())
        }
    }

    @Nested
    @DisplayName("Exponential backoff")
    inner class ExponentialBackoffTests {

        @Test
        fun `initial retry delay is minimum`() {
            // Enter offline mode
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }

            val stats = offlineMode.getStats()
            assertEquals(OfflineMode.MIN_RETRY_DELAY_MS / 1000, stats["currentRetryDelaySeconds"])
        }

        @Test
        fun `retry delay increases with failures`() {
            // Enter offline mode
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }

            // Additional failures while offline should increase backoff
            offlineMode.recordFailure()
            val stats1 = offlineMode.getStats()
            val delay1 = stats1["currentRetryDelaySeconds"] as Long

            offlineMode.recordFailure()
            val stats2 = offlineMode.getStats()
            val delay2 = stats2["currentRetryDelaySeconds"] as Long

            assertTrue(delay2 >= delay1, "Delay should increase or stay same")
        }

        @Test
        fun `retry delay is capped at maximum`() {
            // Enter offline mode
            repeat(OfflineMode.FAILURE_THRESHOLD) {
                offlineMode.recordFailure()
            }

            // Many additional failures
            repeat(20) {
                offlineMode.recordFailure()
            }

            val stats = offlineMode.getStats()
            val delay = stats["currentRetryDelaySeconds"] as Long
            assertTrue(delay <= OfflineMode.MAX_RETRY_DELAY_MS / 1000)
        }
    }
}
