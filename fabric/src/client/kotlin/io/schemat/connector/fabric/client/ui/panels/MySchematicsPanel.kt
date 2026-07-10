package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.type.ImBoolean
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.ui.widgets.Widgets

/**
 * Standalone, dockable "My Schematics" window — the caller's own uploads.
 *
 * Was the My Schematics tab of the former multi-tab `BrowserPanel`. The listing is always
 * scoped mineOnly (no context cycler). Owns its own [SchematicListView.SchematicListState];
 * list rendering is delegated to the shared [SchematicListView]. Toggled from the toolbar /
 * keybinds via [PanelManager].
 */
object MySchematicsPanel : Panel {

    override val id = "my-schematics"

    private val state = SchematicListView.SchematicListState()

    /** Reload from scratch (e.g. after an upload/edit/delete elsewhere). */
    fun invalidate() = resetAndLoad()

    override fun render() {
        val open = ImBoolean(true)
        ImGui.setNextWindowSize(820f, 600f, imgui.flag.ImGuiCond.FirstUseEver)
        val expanded = ImGui.begin("My Schematics###my-schematics", open)
        try {
            if (!open.get()) {
                PanelManager.close(id)
                return
            }
            if (!expanded) return

            if (!state.hasLoaded) resetAndLoad()

            renderControls()
            ImGui.separator()
            SchematicListView.renderSchematicGrid(
                state = state,
                scrollId = "##mine_scroll",
                showAuthor = false,
                emptyNoSearchMsg = "You have no schematics yet",
                emptySearchMsg = { q -> "No schematics match \"$q\"" },
                loadNext = { loadNextPage() },
            )
        } finally {
            ImGui.end()
        }
    }

    private fun renderControls() {
        SchematicListView.renderSearchField(state, "##mine_search", "Search my schematics...")

        ImGui.sameLine()
        SchematicListView.renderSortCycler(state, sortButtonSuffix = "") { resetAndLoad() }

        ImGui.sameLine()
        SchematicListView.renderOrderToggle(state, orderButtonSuffix = "##mine") { resetAndLoad() }

        ImGui.sameLine()
        if (Widgets.button("Upload", accent = true)) {
            UploadWizardPanel.open()
        }
        ImGui.sameLine()
        // "Quick link" opens the local-source quick-share flow (no per-row selection in this list).
        if (Widgets.button("Quick link")) {
            QuickShareCreatePanel.show(null)
        }

        SchematicListView.renderStatusBanners(state, retryButtonId = "Retry##mine") { loadNextPage() }

        SchematicListView.tickSearchDebounce(state) { resetAndLoad() }
    }

    private fun resetAndLoad() {
        SchematicListView.resetAndLoad(state) { loadNextPage() }
    }

    private fun loadNextPage() {
        SchematicListView.loadNextPage(
            state = state,
            context = null, // My Schematics is always mineOnly
            tags = emptyList(),
            filterConstraints = emptyList(),
            reload = { loadNextPage() },
        )
    }
}
