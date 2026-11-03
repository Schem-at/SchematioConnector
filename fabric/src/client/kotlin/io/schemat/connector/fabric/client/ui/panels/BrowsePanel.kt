package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiHoveredFlags
import imgui.type.ImBoolean
import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.theme.Icons
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import io.schemat.connector.fabric.client.ui.panels.SchematicListView.Context
import io.schemat.connector.fabric.client.ui.panels.SchematicListView.sameAs

/**
 * Standalone, dockable "Browse" window — public/community schematic browsing.
 *
 * Was the Browse tab of the former multi-tab `BrowserPanel`. Owns its own
 * [SchematicListView.SchematicListState] plus the browse-only context cycler and tag-filter
 * state; all list rendering is delegated to [SchematicListView].
 * Toggled from the toolbar / keybinds via [PanelManager].
 */
object BrowsePanel : Panel {

    override val id = "browse"

    private const val COMMUNITIES_POPUP = "##browse_communities"

    private val services: ClientServices get() = SchematioClientMod.instance.services

    private val state = SchematicListView.SchematicListState()

    // Browse-only extras (Mine has no context or tag filters)
    private var scope: BrowseScope = BrowseScope.All
    private var context: Context = Context.Public
    private var selectedTagIds: Set<String> = emptySet()
    private var tagConstraints: List<FilterConstraint> = emptyList()

    /** Reload from scratch (e.g. after an item is deleted/edited elsewhere). */
    fun invalidate() = resetAndLoad()

    override fun render() {
        val open = ImBoolean(true)
        ImGui.setNextWindowSize(820f, 600f, imgui.flag.ImGuiCond.FirstUseEver)
        val expanded = ImGui.begin("Browse###browse", open)
        if (expanded) ImGuiTheme.windowTitleAccent()
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
                showAuthor = scope !is BrowseScope.Mine,
                emptyNoSearchMsg = "No schematics found",
                emptySearchMsg = { q -> "No schematics match \"$q\"" },
                loadNext = { loadNextPage() },
            )
        } finally {
            ImGui.end()
        }
    }

    private fun renderControls() {
        // --- Scope selector: All | Mine | Communities ▾ ---
        scopeButton("All", scope is BrowseScope.All) { setScope(BrowseScope.All) }
        ImGui.sameLine()
        scopeButton("Mine", scope is BrowseScope.Mine) { setScope(BrowseScope.Mine) }
        ImGui.sameLine()
        renderCommunityPicker()
        // "Manage community" only in a Community scope
        (scope as? BrowseScope.Community)?.let { cs ->
            ImGui.sameLine()
            if (Widgets.secondaryButton("Manage")) {
                services.me?.communities?.firstOrNull { it.slug == cs.slug }?.let {
                    CommunityDetailPanel.show(it)   // existing show(summary) entry point
                }
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

    /**
     * "Communities ▾": a button that opens its list right under itself on click. (A beginMenu outside a
     * menu bar opens on hover, beside the whole panel, and closes again when the pointer crosses the panel.)
     */
    private fun renderCommunityPicker() {
        services.ensureMe()
        val me = services.me
        val communities = me?.communities.orEmpty()
        val current = scope as? BrowseScope.Community
        val label = "${current?.name ?: "Communities"}  ${Icons.CHEVRON_DOWN}##communities"
        if (communities.isEmpty()) ImGui.beginDisabled()
        val clicked = if (current != null) Widgets.primaryButton(label) else Widgets.secondaryButton(label)
        if (communities.isEmpty()) {
            ImGui.endDisabled()
            if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                ImGui.setTooltip(if (me == null) "Loading your communities..." else "You are not in a community yet.")
            }
        }
        if (clicked) ImGui.openPopup(COMMUNITIES_POPUP)
        ImGui.setNextWindowPos(ImGui.getItemRectMinX(), ImGui.getItemRectMaxY() + 2f)
        if (ImGui.beginPopup(COMMUNITIES_POPUP)) {
            for (c in communities) {
                if (ImGui.menuItem(c.name, "", current?.slug == c.slug)) setScope(BrowseScope.Community(c.slug, c.name))
            }
            ImGui.endPopup()
        }
    }

    private fun scopeButton(label: String, active: Boolean, onClick: () -> Unit) {
        if (active) { if (Widgets.primaryButton(label)) onClick() }
        else { if (Widgets.secondaryButton(label)) onClick() }
    }

    private fun setScope(next: BrowseScope) {
        if (next == scope) return
        scope = next
        context = BrowseScope.toContext(next)
        selectedTagIds = emptySet()
        tagConstraints = emptyList()
        resetAndLoad()
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
