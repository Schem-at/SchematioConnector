package io.schemat.connector.fabric.client.ui.framework

import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiDockNodeFlags
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags

/**
 * Full-viewport ImGui HOST window: an invisible ([ImGuiWindowFlags.NoBackground])
 * backdrop carrying a top MENU BAR (the Schematio toolbar) and a transparent dockspace
 * ([ImGuiDockNodeFlags.PassthruCentralNode]).
 *
 * The result: the GAME shows through the whole central area (and receives clicks there,
 * since the passthrough central node reports `wantCaptureMouse=false`), with only the
 * thin top bar and any docked tool windows painting over it — so the workspace is
 * non-intrusive, and completely invisible when the overlay is disabled.
 *
 * NO default split is built. An empty `PassthruCentralNode` dockspace is fully
 * transparent; a manual split, by contrast, leaves EMPTY (non-central) dock nodes that
 * paint the opaque `DockingEmptyBg` over the viewport and hide the game. Tool windows
 * float when first opened; the user docks them where they like (e.g. the right edge)
 * and the layout persists via the imgui.ini. A correct auto-right-dock default — one
 * that keeps the central node transparent — is a follow-up.
 *
 * Render order: [render] MUST run BEFORE the tool panels each frame so the dockspace
 * exists for windows to dock into on the same frame.
 */
object DockHost {

    /** Stable dockspace id (also the imgui.ini key for the persisted layout). */
    const val DOCKSPACE_ID = "SchematioDockSpace"

    private val HOST_FLAGS =
        ImGuiWindowFlags.NoTitleBar or
        ImGuiWindowFlags.NoCollapse or
        ImGuiWindowFlags.NoResize or
        ImGuiWindowFlags.NoMove or
        ImGuiWindowFlags.NoBringToFrontOnFocus or
        ImGuiWindowFlags.NoNavFocus or
        ImGuiWindowFlags.NoBackground or
        ImGuiWindowFlags.NoDocking or
        ImGuiWindowFlags.NoScrollbar or
        ImGuiWindowFlags.NoScrollWithMouse or
        ImGuiWindowFlags.MenuBar

    fun render() {
        // Fill the whole viewport.
        val vp = ImGui.getMainViewport()
        if (vp != null) {
            ImGui.setNextWindowPos(vp.workPosX, vp.workPosY, ImGuiCond.Always)
            ImGui.setNextWindowSize(vp.workSizeX, vp.workSizeY, ImGuiCond.Always)
            ImGui.setNextWindowViewport(vp.id)
        } else {
            val io = ImGui.getIO()
            ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
            ImGui.setNextWindowSize(io.displaySizeX, io.displaySizeY, ImGuiCond.Always)
        }

        // Zero rounding/border/padding so the host is a seamless, invisible backdrop.
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)

        ImGui.begin("##SchematioDockHost", HOST_FLAGS)
        // Pop WindowPadding immediately so it does not affect the dockspace; rounding +
        // border are popped after end().
        ImGui.popStyleVar()

        // Top bar — the Schematio toolbar.
        if (ImGui.beginMenuBar()) {
            Toolbar.renderMenuBar()
            ImGui.endMenuBar()
        }

        // Transparent dockspace: the central node passes through to the game
        // (PassthruCentralNode) AND can never be docked into (NoDockingInCentralNode),
        // so a tool window can never land in the center and cover the game — it always
        // docks to an edge, leaving the game as the bounded central viewport.
        val dockId = ImGui.getID(DOCKSPACE_ID)
        ImGui.dockSpace(
            dockId, 0f, 0f,
            ImGuiDockNodeFlags.PassthruCentralNode or ImGuiDockNodeFlags.NoDockingInCentralNode,
        )

        ImGui.end()
        ImGui.popStyleVar(2) // WindowRounding + WindowBorderSize
    }
}
