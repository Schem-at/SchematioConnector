package io.schemat.connector.fabric.client.ui.tabs

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.BrowseQuery
import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.core.modapi.StaleAware
import io.schemat.connector.core.modapi.dto.Page
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.HomeScreen
import io.schemat.connector.fabric.client.ui.SchematicDetailScreen
import io.schemat.connector.fabric.client.ui.TagSelectorScreen
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.disabledWhenOffline
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import io.schemat.connector.fabric.client.ui.widgets.SchematicGridWidget
import net.minecraft.client.Minecraft
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Browse / My Schematics tab: context dropdown (Public / Mine / each community -
 * hidden when [mineOnly] pins the tab to the caller's own schematics), debounced search,
 * a "Tags..." button opening the [TagSelectorScreen] in FILTER mode (global minecraft
 * tag tree for every context, with the community's own tree appended while a community
 * context is selected) with a compact selection summary next to it, sort + order
 * controls, and an infinite-scroll thumbnail grid backed by the cached API. The controls
 * wrap onto a second row when the screen is narrow (e.g. GUI scale 3).
 *
 * Pages accumulate into [items]; any filter change resets the accumulation. Filter and
 * result state live on the tab instance so they survive screen re-init (resize, tab
 * switches) within one HomeScreen.
 *
 * [onAction] (with [actionLabel]) places an optional host-provided action button at the
 * left of the controls row - used by the MINE tab for Upload. [onSecondaryAction] (with
 * [secondaryActionLabel]) places a second action button right after it - used by the
 * MINE tab for the one-click "Quick link" share flow.
 */
class BrowseTab(
    private val mineOnly: Boolean = false,
    private val actionLabel: String? = null,
    private val onAction: ((HomeScreen) -> Unit)? = null,
    private val secondaryActionLabel: String? = null,
    private val onSecondaryAction: ((HomeScreen) -> Unit)? = null,
) : HomeScreen.TabContent {

    /** What the listing is scoped to. */
    private sealed class Context(val label: String) {
        object Public : Context("Public")
        object Mine : Context("Mine")
        class Community(val slug: String, name: String) : Context(name)
    }

    companion object {
        private const val CONTROL_HEIGHT = Theme.BTN_H
        /** Gap between controls within a group. */
        private const val GAP = Theme.SM
        /** Gap between control groups (actions | scope | search | sort). */
        private const val GROUP_GAP = Theme.LG
        private const val CONTEXT_WIDTH = 110
        private const val ACTION_WIDTH = 70
        private const val SORT_WIDTH = 130
        private const val ORDER_WIDTH = 50
        private const val TAGS_BUTTON_WIDTH = 60
        /** Width reserved for the tag-selection summary text after the Tags button. */
        private const val TAG_SUMMARY_WIDTH = 100
        /** Cap on the search field so it doesn't stretch across the whole screen. */
        private const val SEARCH_MAX_WIDTH = 300
        /** Below this width the sort/order controls wrap onto a second controls row. */
        private const val TWO_ROW_THRESHOLD = 700

        /** 300ms at 20 ticks/s. */
        private const val SEARCH_DEBOUNCE_TICKS = 6

        private val SORT_OPTIONS = listOf("created_at", "updated_at", "name", "downloads")
        private val SORT_LABELS = mapOf(
            "created_at" to "Created",
            "updated_at" to "Updated",
            "name" to "Name",
            "downloads" to "Downloads",
        )

        /**
         * Session-persisted filter state per tab flavor (false = Browse, true = Mine).
         *
         * HomeScreen - and with it every BrowseTab instance - is constructed fresh on
         * each GUI open, so filter state kept on the tab instance was silently dropped
         * whenever the screen was closed and reopened: the tag selector then re-opened
         * with an empty selection even though the listing had been tag-filtered moments
         * before. Keeping the state here makes every reopen seed the prior selection
         * (tag ids + filter constraints) plus search/sort/context.
         */
        private val persistedFilters = mutableMapOf<Boolean, FilterState>()

        /** Tag id -> display name cache for the selection summary, shared across instances. */
        private val tagNameById = mutableMapOf<String, String>()
    }

    /** See [persistedFilters]. */
    private class FilterState(initialContext: Context) {
        var context: Context = initialContext
        var searchText: String = ""
        var sort: String = "created_at"
        var orderDesc: Boolean = true
        var selectedTagIds: Set<String> = emptySet()
        var tagConstraints: List<FilterConstraint> = emptyList()
    }

    private val services get() = SchematioClientMod.instance.services

    // ---- filter state (survives re-init AND screen reopens - see [persistedFilters]) ----
    private val filters = persistedFilters.getOrPut(mineOnly) {
        FilterState(if (mineOnly) Context.Mine else Context.Public)
    }
    private var context: Context
        get() = filters.context
        set(value) { filters.context = value }
    private var searchText: String
        get() = filters.searchText
        set(value) { filters.searchText = value }
    private var sort: String
        get() = filters.sort
        set(value) { filters.sort = value }
    private var orderDesc: Boolean
        get() = filters.orderDesc
        set(value) { filters.orderDesc = value }
    /** Multi-tag filter (AND logic) + stored-value constraints, set via the tag selector. */
    private var selectedTagIds: Set<String>
        get() = filters.selectedTagIds
        set(value) { filters.selectedTagIds = value }
    private var tagConstraints: List<FilterConstraint>
        get() = filters.tagConstraints
        set(value) { filters.tagConstraints = value }

    // ---- tag trees for the selector ----
    /** Global minecraft tag tree, or null while not yet loaded. */
    private var globalTagNodes: List<TagNode>? = null
    /** Tag tree of the current community context (empty for Public/Mine). */
    private var communityTagNodes: List<TagNode> = emptyList()
    /** Slug the current [communityTagNodes] were loaded for, or null when not loaded. */
    private var tagNodesSlug: String? = null
    private val tagsBusy = AtomicBoolean(false)
    private val globalTagsBusy = AtomicBoolean(false)
    private var tagSummaryX = 0
    private var tagSummaryY = 0

    // ---- result state ----
    private val items = mutableListOf<SchematicSummary>()
    private var nextPage = 1
    private var hasMore = false
    private var hasLoaded = false
    private var isStale = false
    private val loadBusy = AtomicBoolean(false)
    /** Bumped on every filter change so stale in-flight responses are dropped. */
    private var requestGeneration = 0
    /** Set when a page load fails; suppresses the grid's auto-fetch until Retry clears it. */
    private var loadFailed = false

    private val banner = NoticeBanner()

    // ---- widgets (rebuilt each init) ----
    private var screen: HomeScreen? = null
    private lateinit var grid: SchematicGridWidget
    private var searchField: EditBox? = null
    private var debounceTicks = -1
    private var pendingSearch: String? = null
    private var gridArea = intArrayOf(0, 0, 0, 0) // x, y, w, h for empty-state text
    private var searchWell = intArrayOf(0, 0, 0, 0) // x, y, w, h behind the search field

    override fun init(screen: HomeScreen, width: Int, height: Int) {
        this.screen = screen
        val pad = HomeScreen.PADDING
        var x = pad
        val controlsY = screen.contentTop
        // Narrow screens (GUI scale 3): sort/order wrap onto a second controls row so
        // the first row keeps a usable search field.
        val twoRows = width < TWO_ROW_THRESHOLD
        val row2Y = controlsY + CONTROL_HEIGHT + GAP

        if (actionLabel != null && onAction != null) {
            screen.register(
                FlatButton.primary(x, controlsY, ACTION_WIDTH, Component.literal(actionLabel)) { onAction.invoke(screen) }
                    .disabledWhenOffline(services)
            )
            x += ACTION_WIDTH + GAP
        }

        if (secondaryActionLabel != null && onSecondaryAction != null) {
            screen.register(
                FlatButton.secondary(x, controlsY, ACTION_WIDTH, Component.literal(secondaryActionLabel)) { onSecondaryAction.invoke(screen) }
                    .disabledWhenOffline(services)
            )
            x += ACTION_WIDTH + GAP
        }
        if (actionLabel != null || secondaryActionLabel != null) x += GROUP_GAP - GAP

        if (!mineOnly) {
            val contexts = buildList {
                add(Context.Public)
                add(Context.Mine)
                services.me?.communities?.forEach { add(Context.Community(it.slug, it.name)) }
            }
            // Re-resolve the current context against the fresh list (community membership may change)
            val current = contexts.firstOrNull { it sameAs context } ?: Context.Public
            if (!(current sameAs context)) {
                // Persisted context no longer resolves (e.g. lost community membership):
                // mirror the context-cycler/presetCommunity behaviour and drop the tag
                // selection so orphaned community tag ids don't filter the fallback listing.
                selectedTagIds = emptySet()
                tagConstraints = emptyList()
            }
            context = current
            screen.register(
                cycler(x, controlsY, CONTEXT_WIDTH, contexts, current, { it.label }) { value ->
                    if (!(value sameAs context)) {
                        context = value
                        selectedTagIds = emptySet()
                        tagConstraints = emptyList()
                        resetAndLoad()
                        // Rebuild the controls row so the tag options follow the context
                        screen.reinit()
                    }
                }
            )
            x += CONTEXT_WIDTH + GAP
        }

        // Tag filter: a button opening the tag selector (FILTER mode) - global minecraft
        // tree for every context, plus the community's own tree while a community context
        // is selected - with a compact selection summary text next to it.
        if (globalTagNodes == null) loadGlobalTagNodes()
        val ctx = context
        if (ctx is Context.Community) {
            if (tagNodesSlug != ctx.slug) {
                communityTagNodes = emptyList()
                loadCommunityTagNodes(ctx.slug)
            }
        } else if (communityTagNodes.isNotEmpty()) {
            communityTagNodes = emptyList()
            tagNodesSlug = null
        }
        screen.register(
            FlatButton.secondary(x, controlsY, TAGS_BUTTON_WIDTH, Component.literal("Tags...")) { openTagSelector() }
        )
        x += TAGS_BUTTON_WIDTH + GAP
        tagSummaryX = x
        tagSummaryY = controlsY + (CONTROL_HEIGHT - 8) / 2
        x += TAG_SUMMARY_WIDTH + GROUP_GAP

        val sortRowY = if (twoRows) row2Y else controlsY
        val sortX = width - pad - ORDER_WIDTH - GAP - SORT_WIDTH
        val searchRight = if (twoRows) width - pad else sortX - GROUP_GAP
        val searchWidth = (searchRight - x).coerceAtLeast(60).coerceAtMost(SEARCH_MAX_WIDTH)
        // The themed well (SURFACE_ALT + border) is drawn in [renderBackground];
        // the vanilla field is inset inside it with its own chrome disabled.
        searchWell = intArrayOf(x, controlsY, searchWidth, CONTROL_HEIGHT)
        val field = EditBox(
            Minecraft.getInstance().font,
            x + Theme.SM, controlsY + (CONTROL_HEIGHT - 8) / 2, searchWidth - Theme.SM * 2, 12,
            Component.literal("Search")
        )
        field.setBordered(false)
        // Full ARGB required: 1.21.6+ GuiGraphics.drawText drops alpha-0 colors.
        field.setTextColor(Theme.TEXT_PRIMARY)
        field.setHint(Component.literal("Search schematics...").withStyle { it.withColor(Theme.TEXT_FAINT and 0xFFFFFF) })
        field.value = searchText
        field.setResponder { text ->
            if (text != searchText) {
                pendingSearch = text
                debounceTicks = SEARCH_DEBOUNCE_TICKS
            }
        }
        searchField = screen.register(field)

        screen.register(
            cycler(sortX, sortRowY, SORT_WIDTH, SORT_OPTIONS, sort, { "Sort: ${SORT_LABELS[it] ?: it}" }) { value ->
                if (value != sort) {
                    sort = value
                    resetAndLoad()
                }
            }
        )

        screen.register(
            cycler(width - pad - ORDER_WIDTH, sortRowY, ORDER_WIDTH, listOf(true, false), orderDesc, { if (it) "Desc" else "Asc" }) { value ->
                if (value != orderDesc) {
                    orderDesc = value
                    resetAndLoad()
                }
            }
        )

        var gridTop = sortRowY + CONTROL_HEIGHT + Theme.MD
        screen.register(banner.layout(pad, gridTop, width - pad * 2))
        // Always reserve the banner strip so a banner appearing after init never overlaps row 1
        gridTop += NoticeBanner.HEIGHT + GAP

        val gridHeight = (height - gridTop - pad).coerceAtLeast(40)
        gridArea = intArrayOf(pad, gridTop, width - pad * 2, gridHeight)
        grid = screen.register(
            SchematicGridWidget(
                pad, gridTop, width - pad * 2, gridHeight,
                services.previewImages,
                onClickItem = ::openDetail,
                onNeedMore = { if (!loadFailed) loadNextPage() },
            )
        )
        grid.hasMore = hasMore
        grid.setItems(items.toList(), preserveScroll = true)

        if (!hasLoaded) resetAndLoad()
    }

    /**
     * Themed replacement for the vanilla cycling button: a [FlatButton.secondary]
     * that steps through [values] on click, relabelling itself and firing
     * [onChange] with the new value.
     */
    private fun <T> cycler(
        x: Int,
        y: Int,
        width: Int,
        values: List<T>,
        initial: T,
        label: (T) -> String,
        onChange: (T) -> Unit,
    ): FlatButton {
        var index = values.indexOf(initial).coerceAtLeast(0)
        lateinit var button: FlatButton
        button = FlatButton.secondary(x, y, width, Component.literal(label(values[index]))) {
            index = (index + 1) % values.size
            button.message = Component.literal(label(values[index]))
            onChange(values[index])
        }
        return button
    }

    private infix fun Context.sameAs(other: Context): Boolean = when {
        this is Context.Public && other is Context.Public -> true
        this is Context.Mine && other is Context.Mine -> true
        this is Context.Community && other is Context.Community -> slug == other.slug
        else -> false
    }

    /** External hook: drop accumulated results so the next init refetches (e.g. after a delete). */
    fun invalidate() {
        hasLoaded = false
    }

    /**
     * External hook: scope the listing to a community before the next init (used by the
     * community detail screen's "Browse schematics" jump). No-op for the MINE tab.
     */
    fun presetCommunity(slug: String, name: String) {
        if (mineOnly) return
        context = Context.Community(slug, name)
        selectedTagIds = emptySet()
        tagConstraints = emptyList()
        hasLoaded = false
    }

    /** Record tag names from [nodes] for the selection summary text. */
    private fun indexTagNames(nodes: List<TagNode>) {
        fun walk(node: TagNode) {
            tagNameById[node.id] = node.name.ifBlank { node.id }
            node.children.forEach { walk(it) }
        }
        nodes.forEach { walk(it) }
    }

    /**
     * Fetch the global minecraft tag tree into [globalTagNodes]. Failures are silent:
     * the selector just opens without that section until a later init retries.
     */
    private fun loadGlobalTagNodes() {
        services.call(
            busy = globalTagsBusy,
            block = { services.cached.globalTags() },
        ) { result ->
            if (result is ApiResult.Success && globalTagNodes == null) {
                globalTagNodes = result.value.value
                indexTagNames(result.value.value)
            }
        }
    }

    /**
     * Fetch the community tag tree into [communityTagNodes]. Failures are silent: the
     * selector just opens with the global tree only.
     */
    private fun loadCommunityTagNodes(slug: String) {
        services.call(
            busy = tagsBusy,
            block = { services.cached.communityTags(slug) },
        ) { result ->
            val ctx = context as? Context.Community
            if (ctx?.slug != slug) {
                // Context moved on while this request was in flight; if the new community's
                // own load was skipped by the busy guard, re-issue it now.
                if (ctx != null && tagNodesSlug != ctx.slug && !tagsBusy.get()) loadCommunityTagNodes(ctx.slug)
                return@call
            }
            if (result is ApiResult.Success) {
                communityTagNodes = result.value.value
                tagNodesSlug = slug
                indexTagNames(result.value.value)
            }
        }
    }

    /**
     * Open the [TagSelectorScreen] in FILTER mode over the host screen; Done stores the
     * selection and reloads the listing (an emptied selection means unfiltered).
     */
    private fun openTagSelector() {
        val host = screen ?: return
        val sections = buildList {
            globalTagNodes?.takeIf { it.isNotEmpty() }?.let { add(TagSelectorScreen.TagSection("Minecraft", it)) }
            val ctx = context
            if (ctx is Context.Community && communityTagNodes.isNotEmpty()) {
                add(TagSelectorScreen.TagSection(ctx.label, communityTagNodes))
            }
        }
        Minecraft.getInstance().setScreen(
            TagSelectorScreen(
                parent = host,
                title = Component.literal("Filter by tags"),
                sections = sections,
                mode = TagSelectorScreen.Mode.FILTER,
                initialSelection = selectedTagIds,
                initialConstraints = tagConstraints,
                onDone = { selection ->
                    selectedTagIds = selection.tagIds
                    tagConstraints = selection.filterConstraints
                    resetAndLoad()
                },
            )
        )
    }

    /** Compact summary of the current tag selection, drawn next to the Tags button. */
    private fun tagSummary(): String {
        val tags = selectedTagIds.size
        val filters = tagConstraints.size
        if (tags == 0 && filters == 0) return "All tags"
        val tagPart =
            if (tags == 1) tagNameById[selectedTagIds.first()] ?: "1 tag"
            else "$tags tags"
        if (filters == 0) return tagPart
        return "$tagPart, $filters filter" + if (filters == 1) "" else "s"
    }

    private fun resetAndLoad() {
        requestGeneration++
        items.clear()
        nextPage = 1
        hasMore = false
        isStale = false
        hasLoaded = true
        loadFailed = false
        banner.clear()
        if (::grid.isInitialized) {
            grid.hasMore = false
            grid.setItems(emptyList())
        }
        loadNextPage()
    }

    private fun loadNextPage() {
        val page = nextPage
        val generation = requestGeneration
        val ctx = context
        val query = BrowseQuery(
            search = searchText.takeIf { it.isNotBlank() },
            tags = selectedTagIds.toList(),
            filterConstraints = tagConstraints,
            sort = sort,
            order = if (orderDesc) "desc" else "asc",
            page = page,
        )
        services.call(
            busy = loadBusy,
            block = {
                when (ctx) {
                    is Context.Community -> services.cached.communitySchematics(ctx.slug, query)
                    is Context.Mine -> services.cached.schematics(query, mineOnly = true)
                    is Context.Public -> services.cached.schematics(query)
                }
            },
        ) { result -> onPageResult(generation, page, result) }
    }

    private fun onPageResult(generation: Int, page: Int, result: ApiResult<StaleAware<Page<SchematicSummary>>>) {
        if (generation != requestGeneration) {
            // Filters changed while this request was in flight: the reload they triggered
            // was skipped because this request still held loadBusy. The flag is released
            // before onResult runs, so re-issue the load for the current generation now.
            // (Single-flight busy guard means nothing newer can be in flight here, but
            // guard anyway so we never double-issue.)
            if (!loadBusy.get()) loadNextPage()
            return
        }
        when (result) {
            is ApiResult.Success -> {
                val stale = result.value
                if (page == 1) items.clear()
                items.addAll(stale.value.items)
                nextPage = page + 1
                hasMore = stale.value.hasMore
                loadFailed = false
                isStale = stale.isStale
                if (isStale) {
                    banner.show(NoticeBanner.Kind.STALE, "Offline - showing cached results", onRetry = ::resetAndLoad)
                } else {
                    banner.clear()
                }
                if (::grid.isInitialized) {
                    grid.hasMore = hasMore
                    grid.setItems(items.toList(), preserveScroll = page > 1)
                }
            }
            is ApiResult.Failure -> {
                // Stop the grid's underfill auto-fetch from hammering the failing endpoint;
                // only the explicit Retry below re-enables loading.
                loadFailed = true
                banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage()) {
                    loadFailed = false
                    banner.clear()
                    loadNextPage()
                }
            }
        }
    }

    private fun openDetail(summary: SchematicSummary) {
        val host = screen ?: return
        Minecraft.getInstance().setScreen(SchematicDetailScreen(host, summary.id, summary))
    }

    override fun tick() {
        if (debounceTicks > 0 && --debounceTicks == 0) {
            debounceTicks = -1
            val text = pendingSearch ?: return
            pendingSearch = null
            if (text != searchText) {
                searchText = text
                resetAndLoad()
            }
        }
    }

    override fun renderBackground(context: GuiGraphics, width: Int, height: Int) {
        // SURFACE_ALT well behind the (chrome-less) search field, accent-bordered while focused.
        if (searchWell[2] > 0) {
            val (wx, wy, ww, wh) = searchWell
            context.fill(wx, wy, wx + ww, wy + wh, Theme.SURFACE_ALT)
            val focused = searchField?.isFocused == true
            Theme.stroke(context, wx, wy, ww, wh, if (focused) Theme.BORDER_ACCENT else Theme.BORDER)
        }
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val font = Minecraft.getInstance().font
        banner.render(context, font)

        val hasSelection = selectedTagIds.isNotEmpty() || tagConstraints.isNotEmpty()
        Theme.muted(
            context, font,
            font.plainSubstrByWidth(tagSummary(), TAG_SUMMARY_WIDTH),
            tagSummaryX, tagSummaryY,
            if (hasSelection) Theme.TEXT_SECONDARY else Theme.TEXT_MUTED,
        )

        val centerX = gridArea[0] + gridArea[2] / 2
        if (items.isEmpty()) {
            if (loadBusy.get()) {
                LoadingSpinner.render(context, font, centerX, gridArea[1] + gridArea[3] / 2 - 4)
            } else if (hasLoaded && !banner.isVisible) {
                val message = if (searchText.isBlank()) "No schematics found" else "No schematics match \"$searchText\""
                Theme.emptyState(context, font, message, gridArea[0], gridArea[1], gridArea[2], gridArea[3])
            }
        } else if (loadBusy.get()) {
            LoadingSpinner.render(
                context, font,
                centerX, gridArea[1] + gridArea[3] - 12,
                "Loading more"
            )
        }
    }
}
