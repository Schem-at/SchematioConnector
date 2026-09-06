package io.schemat.connector.core

import java.util.concurrent.atomic.AtomicLong

/** A request owns a destination only until it is cancelled or replaced. */
class TransferEpoch {
    private val generation = AtomicLong()
    fun begin(): Long = generation.incrementAndGet()
    fun cancel() { generation.incrementAndGet() }
    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket
}
