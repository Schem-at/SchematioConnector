package io.schemat.connector.fabric.client.ui

import io.schemat.connector.fabric.client.render.BackgroundMode
import io.schemat.connector.fabric.client.render.CAPTURE_ASPECT
import io.schemat.connector.fabric.client.render.CAPTURE_HEIGHT
import io.schemat.connector.fabric.client.render.CAPTURE_WIDTH
import io.schemat.connector.fabric.client.render.CameraPose
import io.schemat.connector.fabric.client.render.FOV_OPTIONS_DEG
import io.schemat.connector.fabric.client.render.CaptureResult
import io.schemat.connector.fabric.client.render.OffscreenTarget
import io.schemat.connector.fabric.client.render.Projection
import io.schemat.connector.fabric.client.render.SchematicRenderSource
import io.schemat.connector.fabric.client.render.ThumbnailCapture
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import kotlin.math.pow

/**
 * Task 8: interactive thumbnail "studio" - a live offscreen preview of the
 * schematic with orbit/pan/zoom camera controls (left-drag orbit, right-drag
 * pan, scroll zoom - matching the website's OrbitControls), projection toggle,
 * pose presets and a Capture button that delivers PNG bytes back to the caller
 * (the upload wizard) via [onCaptured].
 *
 * Layout: the 16:9 preview is maximized in the area above a slim full-width
 * BOTTOM control bar (view controls on row 1; status hint + Cancel/Capture on
 * row 2), so the preview gets the whole screen width instead of ceding a
 * right-hand column.
 *
 * Framing: the camera pose auto-fits ([CameraPose.fit]) on open and whenever
 * a preset or the projection changes, so the build always fills the frame
 * with ~10% padding; the Fit button re-runs the fit at the current angle.
 *
 * Draw ordering: `super.render` paints the opaque scrim (via the
 * [renderBackground] override) and the control widgets; the preview blit +
 * text are drawn afterwards. That is safe because the preview rect and the
 * control bar never overlap, so z-order between them is irrelevant and the
 * widgets stay clickable.
 *
 * Resource lifecycle: the display [OffscreenTarget] is allocated lazily in
 * [init] (which also runs on window resize - the target is only created once)
 * and freed in [removed], together with [ThumbnailCapture.releasePooledTarget]
 * so the pooled 1280x720 capture target does not outlive the composer session.
 */
class ThumbnailComposerScreen(
    private val parent: Screen,
    private val source: SchematicRenderSource,
    private val onCaptured: (ByteArray) -> Unit,
) : Screen(Component.literal("Compose preview")) {

    companion object {
        /**
         * 16:9 live-preview framebuffer resolution. Must be >= the preview
         * rect's PIXEL size (rect GUI units × GUI scale factor) or the blit
         * upscales and blurs; 1280x720 covers a ~640x360-unit rect at GUI
         * scale 2 (and matches CAPTURE_WIDTH/HEIGHT, so preview == captured
         * output). The preview rect is forced 16:9 and the renderer's
         * projection aspect is CAPTURE_ASPECT, so blitting this 16:9 target
         * never stretches.
         */
        private const val DISPLAY_WIDTH = CAPTURE_WIDTH
        private const val DISPLAY_HEIGHT = CAPTURE_HEIGHT
        private const val MARGIN = 16
        /** Inner padding of the bottom control bar. */
        private const val BAR_PAD = 8
        private const val BUTTON_HEIGHT = 20
        private const val SMALL_BUTTON_HEIGHT = 16
        private const val GAP = 4
        /** Horizontal breathing room between control groups in the bar. */
        private const val SECTION_GAP = 10

        /**
         * Orbit tuning: dragging across the FULL preview width rotates this
         * many degrees, regardless of preview size or GUI scale - so the drag
         * feel is identical on every screen (≈ a half-turn plus a bit).
         */
        private const val ORBIT_DEGREES_PER_PREVIEW_WIDTH = 220f

        /** Zoom per scroll notch (trackpads scroll fractionally → exponent). */
        private const val ZOOM_STEP = 1.12f

        // GLFW mouse button ids (MouseButtonEvent.button()).
        private const val BUTTON_LEFT = 0
        private const val BUTTON_RIGHT = 1

        // Checkerboard backing for the TRANSPARENT preview (subtle dark greys
        // on-theme): the GUI blit alpha-blends the framebuffer over it, so
        // transparent capture pixels show the checkerboard - the standard
        // "this will be transparent" affordance.
        private const val CHECKER_CELL = 8
        private const val CHECKER_LIGHT = 0xFF3A3A40.toInt()
        private const val CHECKER_DARK = 0xFF26262B.toInt()
    }

    /** Auto-fit on open: the build fills the frame immediately. */
    private var pose = CameraPose.ISO.fit()
    private var background = BackgroundMode.STUDIO
    private var capturing = false
    private var displayTarget: OffscreenTarget? = null

    /**
     * Pose the display target was last rendered with. The offscreen pass can
     * cost up to ~VOLUME_CAP³ block lookups, so [render] only re-renders when
     * the pose changed (or on the first frame); otherwise the previous frame's
     * framebuffer contents are re-blitted as-is.
     */
    private var lastRenderedPose: CameraPose? = null

    /** Background mode of the last offscreen pass - part of the dirty check, like the pose. */
    private var lastRenderedBackground: BackgroundMode? = null

    /**
     * False until one GUI frame has been presented. The first offscreen pass
     * (potentially VOLUME_CAP³ lookups) is deferred past that frame so the
     * screen appears instantly with a "Rendering preview…" placeholder instead
     * of opening frozen.
     */
    private var firstFrameDrawn = false

    private val banner = NoticeBanner()

    /** True while a drag that STARTED inside the preview rect is in progress. */
    private var draggingPreview = false

    // Preview rect (16:9, matching the capture target), recomputed in init().
    private var previewX = 0
    private var previewY = 0
    private var previewW = 0
    private var previewH = 0

    private val controlButtons = mutableListOf<AbstractWidget>()

    // Bottom control bar rect + status-hint baseline, recomputed in init().
    private var barX = 0
    private var barY = 0
    private var barW = 0
    private var barH = 0
    private var hintY = 0

    override fun init() {
        // Allocate once; init() re-runs on resize and must not leak targets.
        if (displayTarget == null) {
            displayTarget = OffscreenTarget(DISPLAY_WIDTH, DISPLAY_HEIGHT)
        }
        controlButtons.clear()

        // -- Bottom control bar: row 1 = view controls, row 2 = hint + actions --
        val bannerY = height - MARGIN - NoticeBanner.HEIGHT
        barX = MARGIN
        barW = width - 2 * MARGIN
        barH = BAR_PAD + SMALL_BUTTON_HEIGHT + GAP + BUTTON_HEIGHT + BAR_PAD
        barY = bannerY - GAP - barH

        // Largest centered 16:9 preview in the full-width area above the bar.
        // Rect aspect == display-target aspect == projection aspect, so the
        // blit never stretches or letterboxes.
        val contentTop = MARGIN + 14
        val contentBottom = barY - Theme.MD
        val areaW = barW
        val areaH = contentBottom - contentTop
        var w = areaW
        var h = (w / CAPTURE_ASPECT).toInt()
        if (h > areaH) {
            h = areaH
            w = (h * CAPTURE_ASPECT).toInt()
        }
        previewW = w.coerceAtLeast(64)
        previewH = h.coerceAtLeast(36)
        previewX = MARGIN + (areaW - previewW) / 2
        previewY = contentTop + (areaH - previewH) / 2

        fun track(button: AbstractWidget): AbstractWidget {
            controlButtons += addRenderableWidget(button)
            return button
        }

        /** Buttons size to their label so the bar stays uncramped at any width. */
        fun fitWidth(label: String, pad: Int = 14) = font.width(label) + pad

        // -- Row 1: projection segments | presets | background/FOV | Fit/Reset --
        // Several controls (preset set, FOV visibility, BG/FOV labels) depend
        // on the current projection/background, so any click that changes them
        // rebuilds the row via rebuildWidgets() - the vanilla idiom for
        // state-dependent widget sets. The display target survives rebuilds
        // (allocated once above) and the pose-dirty cache keys on the pose
        // itself, so a rebuild never costs an extra offscreen pass.
        var x = barX + BAR_PAD
        val row1Y = barY + BAR_PAD

        // Projection: 2-segment toggle, active segment in accent (legacy
        // supplier constructor - the active segment restyles reactively).
        // Switching projection re-fits so the build stays well-framed.
        fun segment(label: String, projection: Projection) {
            val segW = fitWidth(label)
            track(
                FlatButton(
                    x, row1Y, segW, SMALL_BUTTON_HEIGHT, Component.literal(label),
                    bg = { if (pose.projection == projection) Theme.ACCENT else Theme.SURFACE_ALT },
                    fg = { if (pose.projection == projection) Theme.TEXT_PRIMARY else Theme.TEXT_MUTED },
                    border = { if (pose.projection == projection) Theme.ACCENT_DIM else Theme.BORDER },
                ) {
                    pose = pose.copy(projection = projection).fit()
                    rebuildWidgets() // preset set + FOV control follow the projection
                },
            )
            x += segW + 1
        }
        segment("Isometric", Projection.ISOMETRIC)
        segment("Perspective", Projection.PERSPECTIVE)
        x += SECTION_GAP - 1

        // View presets - the set follows the current projection (website
        // parity: isometric corners vs perspective faces, plus shared
        // top-down). Each lands auto-fitted and keeps the user's FOV choice;
        // Top switches to ISOMETRIC, so every preset click rebuilds the row.
        fun preset(label: String, presetPose: CameraPose) {
            val presetW = fitWidth(label)
            track(
                FlatButton.secondary(x, row1Y, presetW, Component.literal(label), height = SMALL_BUTTON_HEIGHT) {
                    pose = presetPose.copy(fovDeg = pose.fovDeg).fit()
                    rebuildWidgets()
                },
            )
            x += presetW + GAP
        }
        if (pose.projection == Projection.ISOMETRIC) {
            preset("NW", CameraPose.ISO_NW)
            preset("NE", CameraPose.ISO_NE)
            preset("SW", CameraPose.ISO_SW)
            preset("SE", CameraPose.ISO_SE)
        } else {
            preset("Front", CameraPose.FRONT)
            preset("Back", CameraPose.BACK)
            preset("Left", CameraPose.LEFT)
            preset("Right", CameraPose.RIGHT)
        }
        preset("Top", CameraPose.TOP)
        x += SECTION_GAP - GAP

        // Background: STUDIO (solid/HDRI backdrop) | TRANSPARENT (alpha-0 PNG,
        // checkerboard preview). Cycler - two states don't warrant segments.
        val bgLabel = if (background == BackgroundMode.STUDIO) "BG: Studio" else "BG: Transparent"
        val bgW = fitWidth(bgLabel)
        track(
            FlatButton.secondary(x, row1Y, bgW, Component.literal(bgLabel), height = SMALL_BUTTON_HEIGHT) {
                background = if (background == BackgroundMode.STUDIO) {
                    BackgroundMode.TRANSPARENT
                } else {
                    BackgroundMode.STUDIO
                }
                rebuildWidgets() // label width changes
            },
        )
        x += bgW + GAP

        // FOV cycler (PERSPECTIVE only - ortho has no FOV): 35/45/55/70°,
        // re-fitting on change so the framing math stays exact.
        if (pose.projection == Projection.PERSPECTIVE) {
            val fovLabel = "FOV ${pose.fovDeg.toInt()}°"
            val fovW = fitWidth(fovLabel)
            track(
                FlatButton.secondary(x, row1Y, fovW, Component.literal(fovLabel), height = SMALL_BUTTON_HEIGHT) {
                    val idx = FOV_OPTIONS_DEG.indexOfFirst { it == pose.fovDeg }
                    // indexOfFirst yields -1 for a non-listed value (can't
                    // happen via UI) → (-1 + 1) = 0, cycling into the list.
                    pose = pose.copy(fovDeg = FOV_OPTIONS_DEG[(idx + 1) % FOV_OPTIONS_DEG.size]).fit()
                    rebuildWidgets() // label shows the new FOV
                },
            )
            x += fovW + GAP
        }
        x += SECTION_GAP - GAP

        // Fit: re-frame at the current angle (also recenters - fit() zeroes the
        // pan offset). Reset: back to the fitted, centered ISO pose (default
        // FOV; background is left as chosen).
        val fitW = fitWidth("Fit")
        track(
            FlatButton.secondary(x, row1Y, fitW, Component.literal("Fit"), height = SMALL_BUTTON_HEIGHT) {
                pose = pose.fit()
            },
        )
        x += fitW + GAP
        track(
            FlatButton.ghost(x, row1Y, fitWidth("Reset"), Component.literal("Reset"), height = SMALL_BUTTON_HEIGHT) {
                pose = CameraPose.ISO.fit()
                rebuildWidgets() // back to the isometric preset set
            },
        )

        // -- Row 2: status hint (drawn in render) left, Cancel + Capture right --
        val row2Y = row1Y + SMALL_BUTTON_HEIGHT + GAP
        hintY = row2Y + (BUTTON_HEIGHT - font.lineHeight) / 2
        val captureW = fitWidth("Capture", 28)
        val cancelW = fitWidth("Cancel", 20)
        track(
            FlatButton.success(barX + barW - BAR_PAD - captureW, row2Y, captureW, Component.literal("Capture")) {
                beginCapture()
            },
        )
        track(
            FlatButton.ghost(
                barX + barW - BAR_PAD - captureW - GAP - cancelW, row2Y, cancelW, Component.literal("Cancel"),
            ) { onClose() },
        )

        addRenderableWidget(banner.layout(MARGIN, bannerY, width - 2 * MARGIN))
        updateControlsEnabled()
    }

    private fun updateControlsEnabled() {
        controlButtons.forEach { it.active = !capturing }
    }

    private fun beginCapture() {
        if (capturing) return
        capturing = true
        banner.clear()
        updateControlsEnabled()
        ThumbnailCapture.capture(source, pose, background) { result ->
            // ThumbnailCapture fires on the render thread, but hop defensively.
            Minecraft.getInstance().execute {
                capturing = false
                // The screen may have been replaced EXTERNALLY mid-readback
                // (disconnect, another mod's setScreen). Don't yank navigation
                // or deliver into a dead wizard - drop the result silently.
                if (Minecraft.getInstance().screen !== this) {
                    return@execute
                }
                when (result) {
                    is CaptureResult.Success -> {
                        // TagSelectorScreen idiom: restore parent first, then callback
                        // (the callback may itself navigate, e.g. back into the wizard).
                        minecraft!!.setScreen(parent)
                        onCaptured(result.pngBytes)
                    }
                    is CaptureResult.Failure -> {
                        banner.show(NoticeBanner.Kind.ERROR, result.message)
                        updateControlsEnabled()
                    }
                }
            }
        }
    }

    private fun isInPreview(x: Double, y: Double): Boolean =
        x >= previewX && x < previewX + previewW && y >= previewY && y < previewY + previewH

    /**
     * Checkerboard backing for the TRANSPARENT preview, clipped to the preview
     * rect. Cell parity (row + col) gives the alternating pattern; edge cells
     * are clamped so the board never bleeds past the border ring.
     */
    private fun drawCheckerboard(context: GuiGraphics) {
        val right = previewX + previewW
        val bottom = previewY + previewH
        var cy = previewY
        var row = 0
        while (cy < bottom) {
            val cellBottom = minOf(cy + CHECKER_CELL, bottom)
            var cx = previewX
            var col = row
            while (cx < right) {
                val cellRight = minOf(cx + CHECKER_CELL, right)
                context.fill(cx, cy, cellRight, cellBottom, if (col % 2 == 0) CHECKER_LIGHT else CHECKER_DARK)
                cx = cellRight
                col++
            }
            cy = cellBottom
            row++
        }
    }

    //? if >=26.1 {
    /*override fun extractRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    *///?} else {
    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
    //?}
        // Render the schematic into the offscreen target FIRST: it temporarily
        // redirects RenderSystem output overrides (restored in its finally),
        // so it must not interleave with the GUI quad submission below.
        // Pose-dirty caching: only re-render when the pose changed since the
        // last offscreen pass (CameraPose is a data class - structural equality).
        // The very FIRST offscreen pass is deferred one frame (firstFrameDrawn)
        // so the screen opens instantly with a "Rendering preview…" placeholder.
        val target = displayTarget
        if (target != null && firstFrameDrawn &&
            (pose != lastRenderedPose || background != lastRenderedBackground)
        ) {
            ThumbnailCapture.renderToFramebufferForDisplay(source, pose, target, background)
            lastRenderedPose = pose
            lastRenderedBackground = background
        }
        firstFrameDrawn = true

        // Scrim (renderBackground override) + control widgets.
        //? if >=26.1 {
        /*super.extractRenderState(context, mouseX, mouseY, delta)
        *///?} else {
        super.render(context, mouseX, mouseY, delta)
        //?}

        // Preview blit + chrome. The preview rect never overlaps the control
        // bar, so drawing after super.render keeps buttons visible/clickable.
        // A clean 1px BORDER ring frames the 16:9 preview. TRANSPARENT mode
        // backs the preview with a checkerboard instead of the flat surface:
        // the GUI blit alpha-blends the framebuffer over it, so exactly the
        // pixels that will be transparent in the PNG show the checker.
        if (background == BackgroundMode.TRANSPARENT) {
            drawCheckerboard(context)
        } else {
            context.fill(previewX, previewY, previewX + previewW, previewY + previewH, Theme.SURFACE_ALT)
        }
        Theme.stroke(context, previewX - 1, previewY - 1, previewW + 2, previewH + 2, Theme.BORDER)
        if (target != null && lastRenderedPose != null) {
            ThumbnailCapture.drawTargetInto(context, target, previewX, previewY, previewW, previewH)
        } else {
            // First frame only: nothing rendered into the target yet.
            Theme.emptyState(context, font, "Rendering preview…", previewX, previewY, previewW, previewH)
        }

        val boldTitle = Component.literal(title.string).withStyle { it.withBold(true) }
        context.drawString(
            font, boldTitle,
            (width - font.width(boldTitle)) / 2, MARGIN / 2 + 2,
            Theme.TEXT_PRIMARY, false,
        )

        // Status hint in the bar: projection + background + zoom + controls.
        val zoomPct = (CameraPose.fitDistance(pose.projection, pose.fovDeg) / pose.distance * 100f).toInt()
        val projectionName = if (pose.projection == Projection.ISOMETRIC) "Isometric" else "Perspective"
        val bgName = if (background == BackgroundMode.TRANSPARENT) "transparent bg" else "studio bg"
        Theme.hint(
            context, font,
            "$projectionName · $bgName · zoom $zoomPct% · drag orbit · right-drag pan · scroll zoom",
            barX + BAR_PAD, hintY,
        )

        if (source.downsampled) {
            // Overlaid INSIDE the preview's bottom edge (the maximized preview
            // leaves no reliable room below it), on a translucent strip.
            val warn = "Large build - preview may omit detail"
            val warnW = font.width(warn)
            val wx = previewX + (previewW - warnW) / 2
            val wy = previewY + previewH - font.lineHeight - Theme.SM
            context.fill(
                wx - Theme.XS, wy - 2, wx + warnW + Theme.XS, wy + font.lineHeight + 2,
                Theme.withAlpha(Theme.BG, 0xB0),
            )
            context.drawString(font, warn, wx, wy, Theme.WARNING, false)
        }

        if (capturing) {
            LoadingSpinner.render(
                context, font,
                previewX + previewW / 2, previewY + previewH / 2,
                "Capturing",
            )
        }

        banner.render(context, font)
    }

    // 26.x renamed the Screen render hooks: render -> extractRenderState,
    // renderBackground -> extractBackground.
    //? if >=26.1 {
    /*override fun extractBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
    *///?} else {
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context, mouseX, mouseY, delta)
    //?}
        Theme.scrim(context, width, height)
        // Control bar card behind the widgets (renderBackground runs before
        // super.render draws the buttons, so the card never covers them).
        if (barH > 0) {
            Theme.card(context, barX, barY, barW, barH)
        }
    }

    // --- Mouse input ---------------------------------------------------------------
    // 1.21.9 changed the Screen mouse handlers to take MouseButtonEvent objects;
    // on 1.21.8 the primitive overrides call super themselves and then delegate
    // to the shared handle* methods below.

    //? if <1.21.9 {
    /*override fun mouseClicked(clickX: Double, clickY: Double, btn: Int): Boolean {
        if (super.mouseClicked(clickX, clickY, btn)) return true
        return handleMouseClicked(clickX, clickY, btn)
    }

    override fun mouseDragged(clickX: Double, clickY: Double, btn: Int, offsetX: Double, offsetY: Double): Boolean {
        if (super.mouseDragged(clickX, clickY, btn, offsetX, offsetY)) return true
        return handleMouseDragged(btn, offsetX, offsetY)
    }

    override fun mouseReleased(clickX: Double, clickY: Double, btn: Int): Boolean {
        draggingPreview = false
        return super.mouseReleased(clickX, clickY, btn)
    }
    *///?}

    //? if >=1.21.9 {
    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(click, doubled)) return true
        return handleMouseClicked(click.x(), click.y(), click.button())
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (super.mouseDragged(click, offsetX, offsetY)) return true
        return handleMouseDragged(click.button(), offsetX, offsetY)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        draggingPreview = false
        return super.mouseReleased(click)
    }
    //?}

    private fun handleMouseClicked(clickX: Double, clickY: Double, button: Int): Boolean {
        // Left = orbit, right = pan (website OrbitControls). Only a drag that
        // STARTS inside the preview rect manipulates the camera.
        if (!capturing && (button == BUTTON_LEFT || button == BUTTON_RIGHT) &&
            isInPreview(clickX, clickY)
        ) {
            draggingPreview = true
            return true
        }
        return false
    }

    private fun handleMouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
        if (draggingPreview && !capturing) {
            when (button) {
                BUTTON_LEFT -> {
                    // Degrees-per-pixel scaled to the preview width: a full-width
                    // drag is always ORBIT_DEGREES_PER_PREVIEW_WIDTH, independent
                    // of GUI scale or window size.
                    val sensitivity = ORBIT_DEGREES_PER_PREVIEW_WIDTH / previewW.coerceAtLeast(1)
                    pose = pose.orbit(
                        offsetX.toFloat() * sensitivity,
                        -offsetY.toFloat() * sensitivity,
                    )
                }
                BUTTON_RIGHT -> {
                    // Pan: deltas normalized by the preview HEIGHT (the frame's
                    // binding extent at 16:9) - CameraPose.pan scales by the
                    // visible frame height so the build tracks the cursor 1:1.
                    val h = previewH.coerceAtLeast(1).toFloat()
                    pose = pose.pan(offsetX.toFloat() / h, offsetY.toFloat() / h)
                }
                else -> return false
            }
            return true
        }
        return false
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
        if (!capturing && isInPreview(mouseX, mouseY)) {
            // Exponential in the scroll amount: whole notches give crisp
            // ZOOM_STEP increments, fractional trackpad deltas zoom smoothly.
            pose = pose.zoom(ZOOM_STEP.pow(-verticalAmount.toFloat()))
            return true
        }
        return false
    }

    // --- Lifecycle ----------------------------------------------------------

    override fun isPauseScreen(): Boolean = false

    /** Don't let Esc tear the screen down while a capture readback is in flight. */
    override fun shouldCloseOnEsc(): Boolean = !capturing

    override fun onClose() {
        minecraft!!.setScreen(parent)
    }

    override fun removed() {
        // Render thread: free the display framebuffer (unregistering the
        // preview wrapper texture FIRST, so the TextureManager never holds a
        // texture over a deleted framebuffer) and request release of the
        // pooled 1280x720 capture target. ThumbnailCapture owns the pooled
        // target: if the screen was replaced externally while a capture
        // readback is in flight, releasePooledTarget() defers the actual
        // close until the readback callback fires.
        displayTarget?.let { target ->
            ThumbnailCapture.unregisterPreviewTexture(target)
            target.close()
        }
        displayTarget = null
        lastRenderedPose = null
        lastRenderedBackground = null
        ThumbnailCapture.releasePooledTarget()
        // The render source is a frozen snapshot (no live Litematica placement or
        // world behind it), so there is nothing further to tear down here.
        super.removed()
    }
}
