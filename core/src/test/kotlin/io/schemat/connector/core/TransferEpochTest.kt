package io.schemat.connector.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CompletableFuture

class TransferEpochTest {
    @Test fun `a late result cannot reclaim a cancelled or newer request`() {
        val epoch = TransferEpoch()
        val first = epoch.begin()
        epoch.cancel()
        val second = epoch.begin()
        assertFalse(epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(second))
    }

    @Test fun `cancellation is visible to a destination on another thread`() {
        val epoch = TransferEpoch()
        val ticket = epoch.begin()
        CompletableFuture.runAsync { epoch.cancel() }.join()
        assertFalse(epoch.isCurrent(ticket))
    }
}
