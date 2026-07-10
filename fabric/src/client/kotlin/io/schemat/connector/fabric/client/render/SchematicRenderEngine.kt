package io.schemat.connector.fabric.client.render

import com.mojang.blaze3d.opengl.GlTexture
import org.lwjgl.opengl.GL11C
import org.slf4j.LoggerFactory

/**
 * ImGui-facing facade over the existing offscreen render stack
 * ([OffscreenSchematicRenderer] / [OffscreenTarget] / [ThumbnailCapture]).
 *
 * The render package already renders a [SchematicRenderSource] into a framebuffer
 * and reads it back as a PNG; what it lacked was a way to hand the *live* result to
 * the pure-ImGui overlay. `ThumbnailCapture.drawTargetInto` blits via [
 * net.minecraft.client.gui.GuiGraphics], which the overlay no longer uses. This
 * engine fills that gap: [renderToTexture] renders into an owned offscreen target and
 * returns the GL texture id of its colour attachment for `ImGui.image(...)`.
 *
 * Design note: this deliberately REUSES [CameraPose] / [BackgroundMode] / [Projection]
 * rather than introducing parallel "PreviewCamera"/"PreviewBackground" types — those
 * already model orbit/zoom/pan/fit, the projection toggle and FOV, and the renderer's
 * matrices are built from them. The composer panel drives a [CameraPose] directly.
 *
 * Render thread only (call from inside the ImGui frame, which runs on the render
 * thread). [capture] is the exception — it hops to the render thread itself.
 */
object SchematicRenderEngine {
    private val LOGGER = LoggerFactory.getLogger("schematioconnector-render-engine")

    /**
     * Live-preview target dimensions: FULL capture resolution (1280x720, 16:9).
     *
     * The old preview rendered at half-res (640x360) behind a ~50 ms drag
     * throttle as a band-aid for the per-frame re-tessellation that made big
     * builds lag during an orbit. That lag is now fixed at the source: blocks +
     * fluids are tessellated ONCE per source into a GPU-geometry cache and
     * redrawn each frame with just the camera matrix (see [CachedSchematicMesh] /
     * [OffscreenSchematicRenderer]). With re-tessellation gone the per-frame cost
     * is a memcpy + GPU draw, so the preview renders at full quality every frame
     * with no throttle, and what you frame is exactly what you capture.
     */
    private const val LIVE_WIDTH = CAPTURE_WIDTH    // 1280, 16:9
    private const val LIVE_HEIGHT = CAPTURE_HEIGHT  // 720, 16:9

    /** Owned live-preview target (LIVE_WIDTH x LIVE_HEIGHT, 16:9), lazily created, reused across frames. */
    private var target: OffscreenTarget? = null

    /** GL id we've already patched to LINEAR filtering (see [renderToTexture]). */
    private var linearFixedFor: Int = 0

    // Pose-dirty cache: re-render only when the inputs change. Rendering the
    // scene every ImGui frame is wasteful; CameraPose is a data class so
    // structural equality detects pose/projection/fov/pan changes.
    private var lastSource: SchematicRenderSource? = null
    private var lastPose: CameraPose? = null
    private var lastBackground: BackgroundMode? = null

    /**
     * Render [source] with [pose] and [background] into the offscreen target and return
     * the GL texture id of its colour attachment, suitable for `ImGui.image(id.toLong(),
     * w, h)`. Returns 0 when the texture isn't available (not on the GL backend yet, or
     * render produced no attachment) — callers should show a placeholder for 0.
     *
     * Re-renders only when (source, pose, background) differ from the last call — an
     * idle preview costs nothing, and a moving pose renders every frame at full quality
     * (no throttle): the geometry is cached in GPU buffers, so a pose change is just a
     * camera-matrix update + redraw, not a re-tessellation. A source switch additionally
     * keeps no stale geometry (the renderer's cache re-tessellates on source identity
     * change). [capture] is unaffected and always renders at full quality.
     */
    fun renderToTexture(
        source: SchematicRenderSource,
        pose: CameraPose,
        background: BackgroundMode,
    ): Int {
        val t = target ?: OffscreenTarget(LIVE_WIDTH, LIVE_HEIGHT).also { target = it }

        val dirty = source !== lastSource || pose != lastPose || background != lastBackground
        if (dirty) renderNow(source, pose, t, background)

        val glId = (t.framebuffer.colorTexture as? GlTexture)?.glId() ?: return 0
        if (glId == 0) return 0

        // A freshly-created framebuffer colour attachment samples black under ImGui's
        // raw GL (Apple GL 4.1 core profile) unless MIN/MAG filtering is LINEAR and no
        // mipmaps are required. Patch once per GL id — same fix the thumbnail grid uses.
        if (glId != linearFixedFor) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glId)
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR)
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0)
            linearFixedFor = glId
        }
        return glId
    }

    /** Render into the live target and update the pose-dirty cache. */
    private fun renderNow(
        source: SchematicRenderSource,
        pose: CameraPose,
        t: OffscreenTarget,
        background: BackgroundMode,
    ) {
        ThumbnailCapture.renderToFramebufferForDisplay(source, pose, t, background)
        lastSource = source
        lastPose = pose
        lastBackground = background
    }

    /**
     * Capture [source]/[pose]/[background] to PNG bytes (transparent when [background]
     * is [BackgroundMode.TRANSPARENT]). Async — [onResult] fires on the render thread
     * roughly one frame later (GPU readback). Delegates to [ThumbnailCapture.capture],
     * which serialises captures and manages its own pooled target.
     */
    fun capture(
        source: SchematicRenderSource,
        pose: CameraPose,
        background: BackgroundMode,
        onResult: (CaptureResult) -> Unit,
    ) = ThumbnailCapture.capture(source, pose, background, onResult)

    /**
     * Release the owned live-preview target and reset the cache. Call when the composer
     * closes so the framebuffer isn't held across sessions.
     */
    fun release() {
        runCatching { target?.close() }.onFailure { LOGGER.debug("Error closing preview target: {}", it.message) }
        // Free the renderer's GPU-geometry cache too, so the off-heap vertex
        // buffers + scratch builders aren't held across composer sessions.
        runCatching { OffscreenSchematicRenderer.releaseCache() }
            .onFailure { LOGGER.debug("Error releasing mesh cache: {}", it.message) }
        target = null
        lastSource = null
        lastPose = null
        lastBackground = null
        linearFixedFor = 0
    }
}
