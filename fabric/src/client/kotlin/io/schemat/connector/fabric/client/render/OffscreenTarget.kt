package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Screenshot
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Offscreen render target for thumbnail capture (MC 1.21.11 pipeline).
 *
 * Verified against the remapped 1.21.11 jar (javap):
 * - `TextureTarget(String name, int width, int height, boolean useDepth)`
 * - `Framebuffer` in 1.21.11 has NO `beginWrite`/`bind`/`clear` methods. The 1.21.6+
 *   idiom redirects RenderLayer output via the static fields
 *   `RenderSystem.outputColorTextureOverride` / `outputDepthTextureOverride`
 *   (both `GpuTextureView`). All vanilla `RenderLayer`/`RenderPhase` draws consult
 *   these overrides when creating their RenderPass.
 * - Clearing goes through the device command encoder:
 *   `RenderSystem.getDevice().createCommandEncoder()
 *       .clearColorAndDepthTextures(GpuTexture color, int argb, GpuTexture depth, double depthValue)`
 * - Readback: `Screenshot.takeScreenshot(Framebuffer, Consumer<NativeImage>)`
 *   (async - the consumer fires after the GPU readback completes, ~1 frame later).
 *   `NativeImage.writeToFile(Path)` writes PNG; there is no byte[] API, so we round-trip
 *   through a temp file.
 *
 * All methods must be called on the render thread.
 */
class OffscreenTarget(
    val width: Int = CAPTURE_WIDTH,
    val height: Int = CAPTURE_HEIGHT,
) : AutoCloseable {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")
    }

    val framebuffer = TextureTarget("schemat-capture", width, height, /* useDepth = */ true)

    private var closed = false

    /** Clear color + depth via the GpuDevice command encoder. Components in 0..1. */
    fun clear(r: Float, g: Float, b: Float, a: Float) {
        // UNCERTAIN (runtime-unverified): the int color is assumed to be packed ARGB,
        // matching vanilla call sites that pass ARGB constants to clearColorTexture.
        val argb = (((a * 255f).toInt() and 0xFF) shl 24) or
            (((r * 255f).toInt() and 0xFF) shl 16) or
            (((g * 255f).toInt() and 0xFF) shl 8) or
            ((b * 255f).toInt() and 0xFF)
        // colorTexture/depthTexture are @Nullable on RenderTarget but always set
        // for a TextureTarget created with useDepth = true.
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            framebuffer.colorTexture!!, argb,
            framebuffer.depthTexture!!, 1.0,
        )
    }

    /**
     * Redirect subsequent RenderLayer output into this framebuffer.
     *
     * UNCERTAIN (runtime-unverified): this is the 1.21.6+ replacement for the removed
     * `Framebuffer.beginWrite`. Vanilla's SpecialGuiElementRenderer uses the same
     * override fields. If the spike comes out blank, this redirect is the prime suspect.
     */
    fun bind() {
        RenderSystem.outputColorTextureOverride = framebuffer.colorTextureView
        RenderSystem.outputDepthTextureOverride = framebuffer.depthTextureView
    }

    /** Restore output to the main framebuffer. */
    fun unbind() {
        RenderSystem.outputColorTextureOverride = null
        RenderSystem.outputDepthTextureOverride = null
    }

    /**
     * Async readback to PNG bytes. The framebuffer must stay alive until [onResult]
     * fires; [onResult] receives null on any failure. Callback thread is whatever
     * thread the GPU readback completes on (render thread in practice).
     *
     * [preserveAlpha]: `Screenshot.takeScreenshot` FORCES every pixel
     * opaque (bytecode-verified: its pixel loop ORs `0xFF000000` into each
     * color before `NativeImage.setColor`), so transparent-background captures
     * route through [readPngWithAlpha] - a re-implementation of the exact same
     * GpuBuffer readback minus the alpha-force.
     */
    fun readPng(preserveAlpha: Boolean = false, onResult: (ByteArray?) -> Unit) {
        if (preserveAlpha) {
            readPngWithAlpha(onResult)
            return
        }
        try {
            Screenshot.takeScreenshot(framebuffer) { image ->
                var bytes: ByteArray? = null
                try {
                    bytes = encodePng(image)
                } catch (e: Exception) {
                    LOGGER.error("SCHEMAT-SPIKE: PNG readback failed", e)
                } finally {
                    image.close()
                }
                onResult(bytes)
            }
        } catch (e: Exception) {
            LOGGER.error("SCHEMAT-SPIKE: takeScreenshot failed", e)
            onResult(null)
        }
    }

    /**
     * Alpha-preserving framebuffer→PNG readback, mirroring 1.21.11's
     * `Screenshot.takeScreenshot(Framebuffer, int, Consumer)` flow
     * step-for-step (bytecode-verified against the remapped yarn jar):
     *   1. `device.createBuffer(name, USAGE_MAP_READ | USAGE_COPY_DST,
     *      w·h·pixelSize)` (vanilla passes the literal usage 9 = the same OR),
     *   2. `encoder.copyTextureToBuffer(colorAttachment, buffer, 0L, callback, 0)`,
     *   3. in the callback: `encoder.mapBuffer(buffer, read=true, write=false)`,
     *      then per pixel `data().getInt((x + y·w)·pixelSize)` →
     *      `NativeImage.setColor(x, h-1-y, color)` (vertical flip - GPU
     *      framebuffers are bottom-up).
     * The ONLY behavioral difference from vanilla is the omitted
     * `color | 0xFF000000`, so the alpha channel written by the render passes
     * (0 where only the alpha-0 clear is visible) survives into the PNG.
     */
    private fun readPngWithAlpha(onResult: (ByteArray?) -> Unit) {
        try {
            val device = RenderSystem.getDevice()
            val texture = framebuffer.colorTexture
                ?: throw IllegalStateException("capture framebuffer has no color attachment")
            val pixelSize = texture.format.pixelSize()
            // 1.21.11 widened GpuDevice.createBuffer's size and the
            // copyTextureToBuffer offset parameters from int to long.
            //? if >=1.21.11 {
            val bufferSize = width.toLong() * height.toLong() * pixelSize
            val copyOffset = 0L
            //?} else {
            /*val bufferSize = width * height * pixelSize
            val copyOffset = 0
            *///?}
            val gpuBuffer = device.createBuffer(
                { "schemat-capture alpha readback" },
                GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST,
                bufferSize,
            )
            device.createCommandEncoder().copyTextureToBuffer(
                texture, gpuBuffer, copyOffset,
                {
                    var bytes: ByteArray? = null
                    try {
                        RenderSystem.getDevice().createCommandEncoder()
                            .mapBuffer(gpuBuffer, /* read = */ true, /* write = */ false).use { view ->
                                val data = view.data()
                                NativeImage(width, height, false).use { image ->
                                    for (y in 0 until height) {
                                        val rowBase = y * width
                                        val outY = height - 1 - y
                                        for (x in 0 until width) {
                                            image.setPixelABGR(x, outY, data.getInt((rowBase + x) * pixelSize))
                                        }
                                    }
                                    bytes = encodePng(image)
                                }
                            }
                    } catch (e: Exception) {
                        LOGGER.error("SCHEMAT-CAPTURE: alpha readback failed", e)
                    } finally {
                        gpuBuffer.close()
                    }
                    onResult(bytes)
                },
                /* mipLevel = */ 0,
            )
        } catch (e: Exception) {
            LOGGER.error("SCHEMAT-CAPTURE: alpha readback setup failed", e)
            onResult(null)
        }
    }

    /** PNG-encode via the temp-file round-trip (NativeImage has no byte[] encoder). */
    private fun encodePng(image: NativeImage): ByteArray {
        val temp = Files.createTempFile("schemat-capture", ".png")
        try {
            image.writeToFile(temp)
            return Files.readAllBytes(temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Delete the framebuffer's GPU resources. Render thread only. */
    override fun close() {
        if (!closed) {
            closed = true
            framebuffer.destroyBuffers()
        }
    }
}
