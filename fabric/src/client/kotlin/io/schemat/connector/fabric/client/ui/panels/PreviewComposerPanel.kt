package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiButtonFlags
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.render.BackgroundMode
import io.schemat.connector.fabric.client.render.CameraPose
import io.schemat.connector.fabric.client.render.CAPTURE_ASPECT
import io.schemat.connector.fabric.client.render.CaptureResult
import io.schemat.connector.fabric.client.render.Projection
import io.schemat.connector.fabric.client.render.SchematicRenderEngine
import io.schemat.connector.fabric.client.render.SchematicRenderSource
import io.schemat.connector.fabric.client.render.data.BlockStateMapper
import io.schemat.connector.fabric.client.render.data.NucleationSnapshotSource
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlin.math.pow

/**
 * In-game thumbnail composer (ImGui port of the deleted vanilla `ThumbnailComposerScreen`).
 *
 * Opens on a schematic's raw bytes, builds a Litematica-free render source via
 * [NucleationSnapshotSource], shows a live MC-native render through
 * [SchematicRenderEngine] (`ImGui.image` over the offscreen colour attachment), and
 * lets the user frame it — orbit (left-drag), pan (right-drag), zoom (scroll),
 * projection toggle, FOV, view presets, background — then capture a transparent PNG
 * handed back via [onCapture].
 *
 * The offscreen renderer is locked to [CAPTURE_ASPECT] (16:9), so the viewport keeps
 * that aspect; a multi-aspect toggle (4:3 / 1:1) needs a per-aspect render target and
 * is a follow-up.
 */
object PreviewComposerPanel : Panel {
    private val LOGGER = LoggerFactory.getLogger("schematioconnector-preview-composer")

    override val id = "preview-composer"

    // Interaction tuning — ported verbatim from the vanilla composer.
    private const val ORBIT_DEGREES_PER_PREVIEW_WIDTH = 220f
    private const val ZOOM_STEP = 1.12f
    private const val BUTTON_LEFT = 0
    private const val BUTTON_RIGHT = 1

    // FOV slider bounds (perspective only).
    private const val FOV_MIN = 20f
    private const val FOV_MAX = 90f

    // ---- per-session state -------------------------------------------------
    private var source: SchematicRenderSource? = null
    private var onCapture: ((ByteArray) -> Unit)? = null
    private var pose: CameraPose = CameraPose.ISO
    private var background: BackgroundMode = BackgroundMode.STUDIO
    private var capturing = false
    private var errorMessage: String? = null
    private var hiddenStateCount = 0

    /**
     * Open the composer for [sourceBytes] (any format Nucleation auto-detects). On
     * capture, [onCapture] receives the PNG bytes and the panel closes. Call on the
     * client/render thread (snapshot build touches registries + the GL pipeline).
     */
    fun show(sourceBytes: ByteArray, onCapture: (ByteArray) -> Unit) {
        this.onCapture = onCapture
        capturing = false
        errorMessage = null
        pose = CameraPose.ISO
        background = BackgroundMode.STUDIO

        // Reset so the "N hidden" note reflects THIS schematic, and drop any prior
        // engine target/cache.
        BlockStateMapper.clearCache()
        SchematicRenderEngine.release()
        source = try {
            NucleationSnapshotSource.snapshotFromBytes(sourceBytes)
        } catch (t: Throwable) {
            LOGGER.warn("Failed to build preview render source", t)
            errorMessage = "Could not load schematic for preview: ${t.message ?: t.javaClass.simpleName}"
            null
        }
        hiddenStateCount = BlockStateMapper.unresolvedCount
        PanelManager.open(this)
    }

    override fun render() {
        run {
            ImGui.setNextWindowSize(860f, 640f, ImGuiCond.FirstUseEver)
            val open = ImBoolean(true)
            if (!ImGui.begin("Compose preview###preview-composer", open, ImGuiWindowFlags.NoCollapse)) {
                ImGui.end()
                return
            }
            ImGuiTheme.windowTitleAccent()

            val src = source
            when {
                errorMessage != null -> renderError(errorMessage!!)
                src == null -> ImGui.text("Loading schematic…")
                else -> renderComposer(src)
            }

            ImGui.end()
            if (!open.get()) closePanel()
        }
    }

    private fun renderError(message: String) {
        Widgets.statusText(message, Widgets.StatusKind.DANGER)
        ImGui.spacing()
        if (Widgets.button("Close")) closePanel()
    }

    private fun renderComposer(src: SchematicRenderSource) {
        // ---- viewport (16:9), reserve space below for controls --------------
        val controlsReserve = 132f
        val availW = ImGui.getContentRegionAvailX()
        val availH = ImGui.getContentRegionAvailY()
        var w = availW
        var h = w / CAPTURE_ASPECT
        val maxH = (availH - controlsReserve).coerceAtLeast(120f)
        if (h > maxH) {
            h = maxH
            w = h * CAPTURE_ASPECT
        }

        val texId = SchematicRenderEngine.renderToTexture(src, pose, background)
        if (texId != 0) {
            // Drive interaction through an invisibleButton so ImGui treats a drag as
            // "manipulating an item" and does NOT move the window underneath; the
            // rendered texture is drawn into the button's rect via the draw list.
            // Framebuffer colour attachment is bottom-up (GL origin) → flip V:
            // uv0=(0,1), uv1=(1,0).
            val originX = ImGui.getCursorScreenPosX()
            val originY = ImGui.getCursorScreenPosY()
            ImGui.invisibleButton(
                "##preview-viewport", w, h,
                ImGuiButtonFlags.MouseButtonLeft or ImGuiButtonFlags.MouseButtonRight,
            )
            val hovered = ImGui.isItemHovered()
            val active = ImGui.isItemActive()
            ImGui.getWindowDrawList().addImage(
                texId.toLong(), originX, originY, originX + w, originY + h,
                0f, 1f, 1f, 0f,
            )
            handleViewportInput(w, h, hovered, active)
        } else {
            ImGui.dummy(w, h)
            //? if >=26.2 {
            /*ImGui.text("Schematic preview is not yet supported on Minecraft 26.2")
            *///?} else {
            ImGui.text("Preview unavailable (no GL texture)")
            //?}
        }

        ImGui.spacing()
        renderControls()
        renderNotes(src)
    }

    /**
     * Orbit (left-drag) / pan (right-drag) / zoom (scroll). [active] is the viewport
     * invisibleButton being held (so the window won't move under the drag); [hovered]
     * gates scroll-zoom.
     */
    private fun handleViewportInput(w: Float, h: Float, hovered: Boolean, active: Boolean) {
        if (capturing) return

        if (active) {
            if (ImGui.isMouseDragging(BUTTON_LEFT)) {
                val dx = ImGui.getMouseDragDeltaX(BUTTON_LEFT)
                val dy = ImGui.getMouseDragDeltaY(BUTTON_LEFT)
                val sensitivity = ORBIT_DEGREES_PER_PREVIEW_WIDTH / w.coerceAtLeast(1f)
                pose = pose.orbit(dx * sensitivity, -dy * sensitivity)
                ImGui.resetMouseDragDelta(BUTTON_LEFT)
            } else if (ImGui.isMouseDragging(BUTTON_RIGHT)) {
                val dx = ImGui.getMouseDragDeltaX(BUTTON_RIGHT)
                val dy = ImGui.getMouseDragDeltaY(BUTTON_RIGHT)
                pose = pose.pan(dx / h.coerceAtLeast(1f), dy / h.coerceAtLeast(1f))
                ImGui.resetMouseDragDelta(BUTTON_RIGHT)
            }
        }

        if (hovered) {
            val wheel = ImGui.getIO().getMouseWheel()
            if (wheel != 0f) {
                pose = pose.zoom(ZOOM_STEP.toDouble().pow((-wheel).toDouble()).toFloat())
            }
        }
    }

    private fun renderControls() {
        // Projection toggle (segmented: active segment in accent).
        segment("Isometric", pose.projection == Projection.ISOMETRIC) {
            pose = pose.copy(projection = Projection.ISOMETRIC).fit()
        }
        ImGui.sameLine()
        segment("Perspective", pose.projection == Projection.PERSPECTIVE) {
            pose = pose.copy(projection = Projection.PERSPECTIVE).fit()
        }

        // FOV (perspective only).
        if (pose.projection == Projection.PERSPECTIVE) {
            ImGui.sameLine()
            ImGui.setNextItemWidth(180f)
            val ref = floatArrayOf(pose.fovDeg)
            if (ImGui.sliderFloat("FOV", ref, FOV_MIN, FOV_MAX)) {
                pose = pose.copy(fovDeg = ref[0]).fit()
            }
        }

        // View presets — depend on projection.
        ImGui.spacing()
        if (pose.projection == Projection.ISOMETRIC) {
            preset("NW", CameraPose.ISO_NW); ImGui.sameLine()
            preset("NE", CameraPose.ISO_NE); ImGui.sameLine()
            preset("SW", CameraPose.ISO_SW); ImGui.sameLine()
            preset("SE", CameraPose.ISO_SE); ImGui.sameLine()
            preset("Top", CameraPose.TOP)
        } else {
            preset("Front", CameraPose.FRONT); ImGui.sameLine()
            preset("Back", CameraPose.BACK); ImGui.sameLine()
            preset("Left", CameraPose.LEFT); ImGui.sameLine()
            preset("Right", CameraPose.RIGHT); ImGui.sameLine()
            preset("Top", CameraPose.TOP)
        }

        // Background + reframe.
        ImGui.spacing()
        val bgLabel = if (background == BackgroundMode.STUDIO) "Background: Studio" else "Background: Transparent"
        if (Widgets.button(bgLabel)) {
            background =
                if (background == BackgroundMode.STUDIO) BackgroundMode.TRANSPARENT else BackgroundMode.STUDIO
        }
        ImGui.sameLine()
        if (Widgets.button("Reframe")) {
            pose = pose.fit()
        }

        // Capture.
        ImGui.spacing()
        if (capturing) {
            Widgets.statusText("Capturing…", Widgets.StatusKind.INFO)
        } else if (Widgets.button("Capture preview", accent = true)) {
            beginCapture()
        }
    }

    private fun renderNotes(src: SchematicRenderSource) {
        if (hiddenStateCount > 0) {
            ImGui.spacing()
            Widgets.statusText(
                "$hiddenStateCount block type(s) hidden (missing mods)",
                Widgets.StatusKind.WARNING,
            )
        }
        if (src.downsampled) {
            Widgets.statusText("Large build — preview may omit detail", Widgets.StatusKind.INFO)
        }
    }

    /** A segmented-control button: accented when [active]; runs [onSelect] on click. */
    private inline fun segment(label: String, active: Boolean, onSelect: () -> Unit) {
        if (Widgets.button(label, accent = active) && !active) onSelect()
    }

    /** A preset button: accented when the current pose already matches [presetPose]. */
    private fun preset(label: String, presetPose: CameraPose) {
        val active = pose.yaw == presetPose.yaw &&
            pose.pitch == presetPose.pitch &&
            pose.projection == presetPose.projection
        if (Widgets.button(label, accent = active)) {
            // Preserve the user's chosen FOV across preset switches, then refit.
            pose = presetPose.copy(fovDeg = pose.fovDeg).fit()
        }
    }

    private fun beginCapture() {
        val src = source ?: return
        if (capturing) return
        capturing = true
        SchematicRenderEngine.capture(src, pose, background) { result ->
            // capture() already fires on the render thread, but hop defensively.
            Minecraft.getInstance().execute {
                capturing = false
                when (result) {
                    is CaptureResult.Success -> {
                        val cb = onCapture
                        closePanel()
                        cb?.invoke(result.pngBytes)
                    }
                    is CaptureResult.Failure -> {
                        errorMessage = result.message
                    }
                }
            }
        }
    }

    private fun closePanel() {
        SchematicRenderEngine.release()
        source = null
        onCapture = null
        capturing = false
        PanelManager.close(id)
    }
}
