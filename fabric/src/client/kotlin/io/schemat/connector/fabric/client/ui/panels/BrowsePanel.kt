package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.type.ImBoolean
import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import io.schemat.connector.fabric.client.ui.panels.SchematicListView.Context
import io.schemat.connector.fabric.client.ui.panels.SchematicListView.sameAs

/**
 * Standalone, dockable "Browse" window — public/community schematic browsing.
 *
 * Was the Browse tab of the former multi-tab `BrowserPanel`. Owns its own
 * [SchematicListView.SchematicListState] plus the browse-only context cycler and tag-filter
 * state; all list rendering is delegated to [SchematicListView] (shared with
 * [MySchematicsPanel]). Toggled from the toolbar / keybinds via [PanelManager].
 */
object BrowsePanel : Panel {

    override val id = "browse"

    private val services: ClientServices get() = SchematioClientMod.instance.services

    private val state = SchematicListView.SchematicListState()

    // Browse-only extras (Mine has no context or tag filters)
    private var context: Context = Context.Public
    private var selectedTagIds: Set<String> = emptySet()
    private var tagConstraints: List<FilterConstraint> = emptyList()
    private var contextIndex = 0

    /** Reload from scratch (e.g. after an item is deleted/edited elsewhere). */
    fun invalidate() = resetAndLoad()

    override fun render() {
        val open = ImBoolean(true)
        ImGui.setNextWindowSize(820f, 600f, imgui.flag.ImGuiCond.FirstUseEver)
        val expanded = ImGui.begin("Browse###browse", open)
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
                scrollId = "##browse_scroll",
                showAuthor = true,
                emptyNoSearchMsg = "No schematics found",
                emptySearchMsg = { q -> "No schematics match \"$q\"" },
                loadNext = { loadNextPage() },
            )
        } finally {
            ImGui.end()
        }
    }

    private fun renderControls() {
        // Context cycler (Public / Mine / community names)
        val contexts = buildContextList()
        if (contextIndex >= contexts.size) contextIndex = 0
        val currentContext = contexts[contextIndex]
        if (!(currentContext sameAs context)) {
            context = currentContext
        }

        if (ImGui.button("Context: ${currentContext.label}")) {
            contextIndex = (contextIndex + 1) % contexts.size
            val newCtx = contexts[contextIndex]
            if (!(newCtx sameAs context)) {
                context = newCtx
                selectedTagIds = emptySet()
                tagConstraints = emptyList()
                resetAndLoad()
            }
        }

        ImGui.sameLine()

        SchematicListView.renderSearchField(state, "##search", "Search schematics...")

        ImGui.sameLine()
        SchematicListView.renderSortCycler(state, sortButtonSuffix = "") { resetAndLoad() }

        ImGui.sameLine()
        SchematicListView.renderOrderToggle(state, orderButtonSuffix = "") { resetAndLoad() }

        // Tag filter: opens the tag-tree selector; applies the chosen tags to the query.
        ImGui.sameLine()
        val tagCount = selectedTagIds.size + tagConstraints.size
        if (Widgets.button(if (tagCount > 0) "Tags ($tagCount)" else "Tags")) {
            TagSelectorPopup.show(
                preselectedTagIds = selectedTagIds,
                mode = TagSelectorPopup.Mode.FILTER,
                preselectedConstraints = tagConstraints,
            ) { newIds, _, constraints ->
                selectedTagIds = newIds
                tagConstraints = constraints
                resetAndLoad()
            }
        }
        if (tagCount > 0) {
            ImGui.sameLine()
            if (Widgets.button("Clear tags")) {
                selectedTagIds = emptySet()
                tagConstraints = emptyList()
                resetAndLoad()
            }
        }

        SchematicListView.renderStatusBanners(state, retryButtonId = "Retry") { loadNextPage() }

        SchematicListView.tickSearchDebounce(state) { resetAndLoad() }
    }

    private fun buildContextList(): List<Context> = buildList {
        add(Context.Public)
        add(Context.Mine)
        services.me?.communities?.forEach { add(Context.Community(it.slug, it.name)) }
    }

    private fun resetAndLoad() {
        SchematicListView.resetAndLoad(state) { loadNextPage() }
    }

    private fun loadNextPage() {
        SchematicListView.loadNextPage(
            state = state,
            context = context,
            tags = selectedTagIds.toList(),
            filterConstraints = tagConstraints,
            reload = { loadNextPage() },
        )
    }
}
