package io.schemat.connector.fabric.worldedit

import java.util.Timer
import java.util.TimerTask
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stands in for WorldEdit's FutureProgressListener: a private static, non-daemon Timer. */
object FakeProgressListener {
    private val timer = Timer()

    fun schedule() = timer.schedule(object : TimerTask() { override fun run() {} }, 60_000)
}

object NoTimerHolder {
    @Suppress("unused")
    private val notATimer = "timer"
}

class WorldEditShutdownTest {

    @Test
    fun `cancels the static progress timer so its thread no longer holds the JVM open`() {
        FakeProgressListener.schedule()

        assertTrue(WorldEditShutdown.cancelStaticTimer(FakeProgressListener::class.java))
        assertFailsWith<IllegalStateException> { FakeProgressListener.schedule() }
    }

    @Test
    fun `reports a class without a static Timer as nothing to cancel`() {
        assertFalse(WorldEditShutdown.cancelStaticTimer(NoTimerHolder::class.java))
    }
}
