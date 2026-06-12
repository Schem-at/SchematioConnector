package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
// The ui.compat alias resolves to net.minecraft.client.gui.GuiGraphics on
// 1.21.x and to net.minecraft.client.gui.GuiGraphicsExtractor on 26.x (which
// kept the identical 12-arg blit(RenderPipeline, Identifier, x, y, u, v, w, h,
// regionW, regionH, texW, texH) used by drawTargetInto - javap-confirmed).
import io.schemat.connector.fabric.client.ui.compat.GuiGraphics
import net.minecraft.client.renderer.texture.AbstractTexture
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Screenshot
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.nio.file.Files

/**
 * Task 7: thumbnail capture orchestration - wires [OffscreenSchematicRenderer]
 * + [OffscreenTarget] into a one-call PNG capture, and provides the live
 * preview path used by the composer screen.
 *
 * All entry points are render-thread safe: they hop via
 * `Minecraft.execute` when called from another thread.
 *
 * GUI blit idiom (verified by javap/bytecode against the remapped 1.21.11 yarn jar):
 * vanilla `SpecialGuiElementRenderer.renderElement` draws its offscreen
 * framebuffer into the GUI with a `TexturedQuadGuiElementRenderState`
 * (`GUI_TEXTURED_PREMULTIPLIED_ALPHA`, `TextureSetup.of(colorAttachmentView,
 * samplerCache.getRepeated(NEAREST))`, u1=0 u2=1 **v1=1 v2=0** - V flipped,
 * confirming framebuffers are bottom-up vs. the top-down GUI). That exact path
 * needs `GuiRenderState`, which is package-private on both `GuiGraphics.state`
 * and `GameRenderer.guiState`, so [drawTargetInto] takes the public-API
 * equivalent instead: register the framebuffer's color attachment as an
 * [AbstractTexture] in the TextureManager and draw it with
 * `GuiGraphics.blit(pipeline, id, ...)`, encoding the V-flip as
 * `v = texHeight, regionHeight = -texHeight` (bytecode-verified to yield
 * v1=1, v2=0). UNCERTAIN (runtime-unverified): the wrapper-texture
 * registration itself - if the preview rect comes out blank/garbled in the
 * composer, this blit is the prime suspect; the fix would be an access
 * widener for `GuiGraphics.state` + a `TexturedQuadGuiElementRenderState`
 * submission mirroring SpecialGuiElementRenderer.
 */
object ThumbnailCapture {

    private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")

    /** Hard cap from the API: preview uploads above this are rejected. */
    private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024

    /** Downscale size (16:9, like the capture) used only if a full-size PNG somehow exceeds the cap. */
    private const val DOWNSCALE_WIDTH = 640
    private const val DOWNSCALE_HEIGHT = 360

    /**
     * FALLBACK switch (default OFF). When true, [capture] routes through
     * [captureViaMainFramebuffer] - Litematica's PreviewGenerator precedent of
     * screenshotting the MAIN framebuffer and cropping - instead of the
     * offscreen target. Flip this only if the offscreen spike fails at runtime
     * (blank/garbled PNGs). See MANUAL_TESTING.md.
     */
    const val USE_MAIN_FRAMEBUFFER_FALLBACK = false

    private val PREVIEW_TEXTURE_ID: Identifier = Identifier.fromNamespaceAndPath("schematioconnector", "thumbnail/preview")

    /**
     * Pooled capture target, reused across captures.
     *
     * OWNERSHIP: ThumbnailCapture is the SOLE owner of this target. Callers
     * (the composer) never close it directly - they call [releasePooledTarget],
     * which is a release *request*: honored immediately when idle, but DEFERRED
     * to the readback callback while a capture is in flight (the async GPU
     * readback must not have its framebuffer freed under it).
     */
    private var pooledTarget: OffscreenTarget? = null

    /** True between issuing a capture readback and its callback firing. Render thread only. */
    private var captureInFlight = false

    /** Set when [releasePooledTarget] is called mid-readback; honored by the readback callback. */
    private var pooledReleaseRequested = false

    /** Which target the preview wrapper texture is currently registered for. */
    private var previewRegisteredFor: OffscreenTarget? = null

    /**
     * Render [source] with [pose] and deliver PNG bytes asynchronously.
     * [onResult] fires on the render thread (Screenshot's readback
     * completes there), possibly one or more frames later.
     *
     * [background]: STUDIO captures the solid/HDRI backdrop as before;
     * TRANSPARENT clears to alpha 0, skips the backdrop and routes the
     * readback through the alpha-preserving path, so the PNG carries real
     * transparency (WYSIWYG with the composer's checkerboard preview).
     */
    fun capture(
        source: SchematicRenderSource,
        pose: CameraPose,
        background: BackgroundMode = BackgroundMode.STUDIO,
        onResult: (CaptureResult) -> Unit,
    ) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { capture(source, pose, background, onResult) }
            return
        }
        if (USE_MAIN_FRAMEBUFFER_FALLBACK) {
            captureViaMainFramebuffer(source, pose, onResult)
            return
        }
        try {
            val target = obtainPooledTarget()
            OffscreenSchematicRenderer.render(source, pose, target, background)
            // NOTE: readback is async (~1 frame). captureInFlight keeps the
            // pooled target alive until the callback fires (releasePooledTarget
            // defers while it is set); avoid issuing a second capture before
            // the callback fires or the later render may overwrite the pixels
            // being read. The composer serializes captures behind its button.
            captureInFlight = true
            target.readPng(preserveAlpha = background == BackgroundMode.TRANSPARENT) { bytes ->
                captureInFlight = false
                try {
                    if (bytes == null) {
                        onResult(CaptureResult.Failure("Thumbnail capture failed (see log, prefix SCHEMAT-CAPTURE)"))
                    } else {
                        onResult(enforceSizeCap(bytes))
                    }
                } finally {
                    if (pooledReleaseRequested) {
                        pooledReleaseRequested = false
                        releasePooledTarget()
                    }
                }
            }
        } catch (e: Exception) {
            captureInFlight = false
            LOGGER.error("SCHEMAT-CAPTURE: capture failed", e)
            onResult(CaptureResult.Failure("Thumbnail capture failed: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            // Double-guard: OffscreenSchematicRenderer restores these in its own
            // finally, but if anything threw mid-pass we must never leave the
            // GUI rendering into our capture target.
            RenderSystem.outputColorTextureOverride = null
            RenderSystem.outputDepthTextureOverride = null
        }
    }

    /**
     * Live-preview path for the composer: render into [target] WITHOUT
     * readback. Call once per frame (or on pose change), then blit the result
     * into the screen with [drawTargetInto]. Render thread only.
     */
    fun renderToFramebufferForDisplay(
        source: SchematicRenderSource,
        pose: CameraPose,
        target: OffscreenTarget,
        background: BackgroundMode = BackgroundMode.STUDIO,
    ) {
        try {
            OffscreenSchematicRenderer.render(source, pose, target, background)
        } finally {
            RenderSystem.outputColorTextureOverride = null
            RenderSystem.outputDepthTextureOverride = null
        }
    }

    /**
     * Blit [target]'s color attachment into the GUI rect (x, y, w, h).
     *
     * Mechanism: the framebuffer's color attachment is wrapped in a no-close
     * [AbstractTexture] registered under [PREVIEW_TEXTURE_ID]; the quad is
     * drawn with `GUI_TEXTURED` and V flipped (v = texH, regionH = -texH →
     * v1=1, v2=0), matching vanilla's framebuffer-to-GUI orientation in
     * SpecialGuiElementRenderer. See the class doc for the runtime-unverified
     * caveat.
     */
    fun drawTargetInto(ctx: GuiGraphics, target: OffscreenTarget, x: Int, y: Int, w: Int, h: Int) {
        if (previewRegisteredFor !== target) {
            Minecraft.getInstance().textureManager
                .register(PREVIEW_TEXTURE_ID, FramebufferColorTexture(target))
            previewRegisteredFor = target
        }
        val texW = target.width
        val texH = target.height
        ctx.blit(
            RenderPipelines.GUI_TEXTURED, PREVIEW_TEXTURE_ID,
            x, y,
            /* u = */ 0f, /* v = */ texH.toFloat(),
            /* quad width/height = */ w, h,
            /* region = */ texW, -texH, // negative height flips V (bottom-up framebuffer → top-down GUI)
            /* texture size = */ texW, texH,
        )
    }

    /**
     * Request release of the pooled capture target (e.g. when the composer
     * closes). Render thread only.
     *
     * If a capture readback is in flight, the actual close is DEFERRED to the
     * readback callback (freeing the framebuffer mid-readback would hand the
     * GPU a dead attachment); otherwise it happens immediately. Either way the
     * preview wrapper texture is unregistered so the TextureManager never holds
     * a [FramebufferColorTexture] over a closed framebuffer.
     */
    fun releasePooledTarget() {
        if (captureInFlight) {
            pooledReleaseRequested = true
            return
        }
        pooledTarget?.let { unregisterPreviewTexture(it) }
        pooledTarget?.close()
        pooledTarget = null
    }

    /**
     * Drop the preview wrapper texture registered by [drawTargetInto] if it
     * currently points at [target]. MUST be called before closing any target
     * that was ever passed to [drawTargetInto] (e.g. the composer's display
     * target in `removed()`), otherwise the TextureManager keeps an
     * [AbstractTexture] wrapping a deleted framebuffer attachment. Safe no-op
     * when the registration is for a different target (or absent).
     * [FramebufferColorTexture.close] is a GPU no-op, so destroyTexture only
     * removes the TextureManager entry. Render thread only.
     */
    fun unregisterPreviewTexture(target: OffscreenTarget) {
        if (previewRegisteredFor !== target) return
        previewRegisteredFor = null
        Minecraft.getInstance().textureManager.release(PREVIEW_TEXTURE_ID)
    }

    private fun obtainPooledTarget(): OffscreenTarget =
        pooledTarget ?: OffscreenTarget(CAPTURE_WIDTH, CAPTURE_HEIGHT).also { pooledTarget = it }

    /**
     * Defensive: a 1280x720 PNG is realistically well under 5 MB, but if it
     * isn't, decode → downscale to [DOWNSCALE_WIDTH]x[DOWNSCALE_HEIGHT]
     * (still 16:9) → re-encode.
     */
    private fun enforceSizeCap(bytes: ByteArray): CaptureResult {
        if (bytes.size <= MAX_UPLOAD_BYTES) return CaptureResult.Success(bytes)
        LOGGER.warn(
            "SCHEMAT-CAPTURE: PNG is {} bytes (> {} cap), downscaling to {}x{}",
            bytes.size, MAX_UPLOAD_BYTES, DOWNSCALE_WIDTH, DOWNSCALE_HEIGHT,
        )
        return try {
            NativeImage.read(bytes).use { full ->
                NativeImage(DOWNSCALE_WIDTH, DOWNSCALE_HEIGHT, false).use { small ->
                    full.resizeSubRectTo(0, 0, full.width, full.height, small)
                    CaptureResult.Success(encodePng(small))
                }
            }
        } catch (e: Exception) {
            LOGGER.error("SCHEMAT-CAPTURE: downscale failed", e)
            CaptureResult.Failure("Thumbnail exceeded 5 MB and downscale failed: ${e.message}")
        }
    }

    /** NativeImage has no byte[] PNG encoder - round-trip through a temp file. */
    private fun encodePng(image: NativeImage): ByteArray {
        val temp = Files.createTempFile("schemat-capture", ".png")
        try {
            image.writeToFile(temp)
            return Files.readAllBytes(temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * FALLBACK (Litematica GuiSchematicManager\$PreviewGenerator precedent):
     * screenshot the MAIN framebuffer and crop the largest centered 16:9
     * region down to [CAPTURE_WIDTH]x[CAPTURE_HEIGHT] via
     * `NativeImage.resizeSubRectTo`.
     *
     * v1 stub semantics: this does NOT issue its own render passes - it
     * assumes the composer has just drawn the live preview into the current
     * frame (it renders the schematic every frame anyway), so the main
     * framebuffer already contains the scene. Full parity (rendering the three
     * passes into a dedicated viewport rect first) is only worth building if
     * the offscreen path fails at runtime; this compiles and is switchable via
     * [USE_MAIN_FRAMEBUFFER_FALLBACK].
     */
    fun captureViaMainFramebuffer(
        @Suppress("UNUSED_PARAMETER") source: SchematicRenderSource,
        @Suppress("UNUSED_PARAMETER") pose: CameraPose,
        onResult: (CaptureResult) -> Unit,
    ) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { captureViaMainFramebuffer(source, pose, onResult) }
            return
        }
        try {
            Screenshot.takeScreenshot(client.mainRenderTarget) { image ->
                var result: CaptureResult
                try {
                    // Largest centered 16:9 crop of the screenshot.
                    var cropW = image.width
                    var cropH = (cropW * CAPTURE_HEIGHT) / CAPTURE_WIDTH
                    if (cropH > image.height) {
                        cropH = image.height
                        cropW = (cropH * CAPTURE_WIDTH) / CAPTURE_HEIGHT
                    }
                    val srcX = (image.width - cropW) / 2
                    val srcY = (image.height - cropH) / 2
                    NativeImage(CAPTURE_WIDTH, CAPTURE_HEIGHT, false).use { cropped ->
                        image.resizeSubRectTo(srcX, srcY, cropW, cropH, cropped)
                        result = enforceSizeCap(encodePng(cropped))
                    }
                } catch (e: Exception) {
                    LOGGER.error("SCHEMAT-CAPTURE: main-framebuffer fallback failed", e)
                    result = CaptureResult.Failure("Fallback capture failed: ${e.message}")
                } finally {
                    image.close()
                }
                onResult(result)
            }
        } catch (e: Exception) {
            LOGGER.error("SCHEMAT-CAPTURE: main-framebuffer screenshot failed", e)
            onResult(CaptureResult.Failure("Fallback capture failed: ${e.message}"))
        }
    }

    /**
     * No-close [AbstractTexture] view over an [OffscreenTarget]'s color
     * attachment, so `GuiGraphics.drawTexture` can sample it by Identifier.
     * close() is a no-op - the framebuffer owns the GPU texture and
     * TextureManager must never delete it (it calls close() when a texture is
     * replaced or destroyed).
     */
    private class FramebufferColorTexture(target: OffscreenTarget) : AbstractTexture() {
        init {
            // Protected fields on AbstractTexture.
            texture = target.framebuffer.colorTexture
            textureView = target.framebuffer.colorTextureView
            // Explicit clamped LINEAR sampler, NO mipmaps: the display target
            // (1280x720) is blitted DOWN to the preview rect, so linear
            // minification is correct, and a default sampler with mip
            // filtering over this non-mipmapped attachment would blur (or
            // sample garbage). javap: SamplerCache.get(FilterMode) →
            // clamped, non-mipmapped GpuSampler.
            // 1.21.11 introduced explicit GpuSampler objects + RenderSystem.getSamplerCache();
            // <=1.21.10 configures sampling on the texture itself (blur=true, mipmap=false).
            //? if >=1.21.11 {
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            //?} else {
            /*setFilter(true, false)
            setClamp(true)
            *///?}
        }

        override fun close() {
            // Intentionally empty: do NOT delete the framebuffer's texture.
            texture = null
            textureView = null
        }
    }
}
