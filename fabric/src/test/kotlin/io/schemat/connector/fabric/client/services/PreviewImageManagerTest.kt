package io.schemat.connector.fabric.client.services

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class PreviewImageManagerTest {
    @Test fun `a large result list cannot enqueue unbounded preview work`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = PreviewImageManager(scope) { error("Rendering demand must not start network work") }
            repeat(500) { manager.getEntry("$it", "https://schemat.io/$it.png") }
            assertEquals(32, manager.stats().queued)
            assertEquals(0, manager.stats().inFlight)
            repeat(50) { manager.getEntry("0", "https://schemat.io/0.png") }
            assertEquals(32, manager.stats().queued)
        } finally { scope.cancel() }
    }

    @Test fun `dimensions are checked before allocating the native image`() {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", out)
        PreviewImageManager.checkDimensions(out.toByteArray())
        val png = out.toByteArray()
        java.nio.ByteBuffer.wrap(png).putInt(16, 4096).putInt(20, 4096)
        val crc = java.util.zip.CRC32().apply { update(png, 12, 17) }
        java.nio.ByteBuffer.wrap(png).putInt(29, crc.value.toInt())
        assertFailsWith<IllegalArgumentException> { PreviewImageManager.checkDimensions(png) }
    }
}
