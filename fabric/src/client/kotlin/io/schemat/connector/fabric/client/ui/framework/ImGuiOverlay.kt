package io.schemat.connector.fabric.client.ui.framework

import io.schemat.connector.fabric.client.ui.widgets.ConfirmModal
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup
import net.minecraft.client.Minecraft

/**
 * Entry point for the ImGui overlay rendering pipeline — the dockable Schematio workspace.
 *
 * The workspace is "active" when [overlayOpen] is set OR any panel/modal is open. While
 * active, [render] draws (in order): the full-viewport [DockHost] (transparent dockspace
 * with a passthrough center → the game shows through and gets clicks there), the [Toolbar],
 * the docked/floating tool panels, then the modals.
 *
 * [isFocused] returns true while the workspace is active AND no vanilla Screen is covering
 * it. It is the single source of truth consumed by input/mouse mixins.
 *
 * [render] runs every frame from the render mixin. [reconcileCursor] is called first —
 * before the early-return — so the cursor is released/grabbed on the exact frame the
 * active state changes, including the frame the last panel closes itself.
 */
object ImGuiOverlay {

    /**
     * Master workspace visibility. When true the Schematio workspace (DockHost + Toolbar)
     * is shown even if no tool window is open yet. Toggled by [toggleOverlay] (master
     * keybind) and force-set true whenever a tool window is opened, so opening any tool
     * always brings up the workspace chrome.
     */
    @Volatile private var overlayOpen = false

    /** Tracks the cursor state WE last applied, to avoid redundant calls. */
    @Volatile private var cursorReleased = false

    /** Toggles the master workspace visibility (toolbar + dockspace chrome). */
    @JvmStatic
    fun toggleOverlay() {
        overlayOpen = !overlayOpen
        // Closing only hides the workspace chrome; open panels keep their state (they are
        // re-shown next time the workspace opens). reconcileCursor() handles the cursor.
    }

    /** Ensures the workspace chrome is visible. Called when any tool window is opened. */
    @JvmStatic
    fun ensureOpen() {
        overlayOpen = true
    }

    /**
     * Escape handler target. Closes the topmost panel if any are open; otherwise closes
     * the whole workspace. Mirrors the previous "Escape dismisses one panel at a time"
     * behavior and extends it so a final Escape (no panels left) hides the workspace.
     */
    @JvmStatic
    fun onEscape() {
        if (PanelManager.anyOpen()) PanelManager.closeTop() else overlayOpen = false
    }

    /**
     * Reconciles the OS cursor grab/release state against [isFocused].
     * Called at the TOP of [render], before any early-return, so it runs even
     * on the frame the last panel closes. Also keeps the cursor free while a modal
     * is open even if its parent panel closed.
     *
     * Cooperation with MouseMixin: the mixin cancels [net.minecraft.client.MouseHandler.grabMouse]
     * while [isFocused] is true. When the last panel closes but a modal is still open,
     * [isFocused] stays true, so the cursor remains released for the modal.
     */
    private fun reconcileCursor() {
        val mc = Minecraft.getInstance()
        // While a vanilla Screen is open, MC owns the cursor — don't fight it.
        // Mark our state stale so we re-release for the overlay once the screen closes.
        // 26.2 moved screen management from Minecraft onto Gui.
        //? if >=26.2 {
        /*if (mc.gui.screen() != null) {
        *///?} else {
        if (mc.screen != null) {
        //?}
            cursorReleased = false
            return
        }
        val shouldRelease = overlayActive()
        if (shouldRelease == cursorReleased) return
        if (shouldRelease) mc.mouseHandler.releaseMouse() else mc.mouseHandler.grabMouse()
        cursorReleased = shouldRelease
    }

    /**
     * True when the workspace should be live, ignoring whether a vanilla screen is
     * covering it. The workspace is active when explicitly opened ([overlayOpen]) OR
     * when any panel/modal is open.
     */
    private fun overlayActive(): Boolean =
        overlayOpen || PanelManager.anyOpen() || ConfirmModal.isOpen() || TagSelectorPopup.isOpen()

    /**
     * Single source of truth for input/mouse mixins + the render gate. False while a
     * vanilla Screen is open so the overlay SUSPENDS (input goes to the screen, cursor
     * to MC) — panels stay open and resume when the screen closes, so in-progress work
     * (e.g. a half-filled upload) is never lost.
     */
    @JvmStatic
    fun isFocused(): Boolean =
        //? if >=26.2 {
        /*Minecraft.getInstance().gui.screen() == null && overlayActive()
        *///?} else {
        Minecraft.getInstance().screen == null && overlayActive()
        //?}

    @JvmStatic
    fun render() {
        reconcileCursor()
        ImGuiManager.initIfNeeded()
        if (!isFocused()) return
        ImGuiManager.startFrame(true)
        ImGuiTheme.apply()
        // Host FIRST (its top menu bar is the Schematio toolbar; its passthrough
        // dockspace shows the game) so the dockspace exists this frame for windows to dock.
        DockHost.render()
        PanelManager.renderAll()
        ConfirmModal.render()
        TagSelectorPopup.render()
        ImGuiTheme.unapply()
        ImGuiManager.endFrame()
    }
}
