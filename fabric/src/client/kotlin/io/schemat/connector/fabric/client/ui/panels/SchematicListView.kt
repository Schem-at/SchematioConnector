package io.schemat.connector.fabric.client.ui.panels

import com.mojang.blaze3d.opengl.GlTexture
import imgui.ImGui
import imgui.type.ImString
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.BrowseQuery
import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.core.modapi.StaleAware
import io.schemat.connector.core.modapi.dto.Page
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.opengl.GL11C
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared, reusable schematic-listing component extracted from the former `BrowserPanel`.
 *
 * Owns ALL the list-rendering machinery — card grid, search/sort/order controls, status
 * banners, search debounce, pagination/infinite-scroll, thumbnail GL-texture resolution —
 * so that each standalone window ([BrowsePanel], [MySchematicsPanel], …) reuses it instead
 * of copy-pasting. Behavior is identical to the old single-window browser.
 *
 * Each window owns one [SchematicListState] (so its scroll/filter/result state persists
 * across close/reopen) and drives it through the helpers here. The component is stateless
 * itself apart from [glLinearFixed] (a process-wide GL-completeness cache).
 */
internal object SchematicListView {

    // ---- card grid constants ----

    /** Width of each card in logical pixels. */
    private const val CARD_W = 152f
    /** Thumbnail height: 16:9-ish crop of card width. */
    private const val THUMB_H = 86f
    /** Horizontal + vertical gap between cards. */
    private const val CARD_GAP = 10f
    /** Metadata area height below the thumbnail. */
    private const val CARD_META_H = 52f
    /** Total card height (thumb + meta). */
    private const val CARD_H = THUMB_H + CARD_META_H

    // ---- GL_LINEAR completeness fix ----
    /**
     * Set of GL texture IDs whose min/mag filter has been set to GL_LINEAR.
     *
     * DynamicTexture created by PreviewImageManager uses NativeImage, which MC uploads with
     * default min-filter (GL_NEAREST_MIPMAP_LINEAR on some drivers) but without mipmap data —
     * making the texture "incomplete" on a core-profile forward-compatible context (Apple GL 4.1).
     * An incomplete texture renders as black when sampled.
     *
     * We fix this exactly once per GL texture ID by binding the texture and setting
     * GL_TEXTURE_MIN_FILTER = GL_LINEAR and GL_TEXTURE_MAG_FILTER = GL_LINEAR.
     * This is safe: DynamicTexture never generates mipmaps, so GL_LINEAR is always valid.
     */
    private val glLinearFixed: MutableSet<Int> = HashSet()

    private val services: ClientServices get() = SchematioClientMod.instance.services

    // ---- listing context ----

    /** What a listing is scoped to. */
    sealed class Context(val label: String) {
        object Public : Context("Public")
        object Mine : Context("Mine")
        class Community(val slug: String, name: String) : Context(name)
    }

    infix fun Context.sameAs(other: Context): Boolean = when {
        this is Context.Public && other is Context.Public -> true
        this is Context.Mine && other is Context.Mine -> true
        this is Context.Community && other is Context.Community -> slug == other.slug
        else -> false
    }

    val SORT_OPTIONS = listOf("created_at", "updated_at", "name", "downloads")
    private val SORT_LABELS = mapOf(
        "created_at" to "Created",
        "updated_at" to "Updated",
        "name" to "Name",
        "downloads" to "Downloads",
    )

    // ---- shared schematic-list state ----

    /**
     * All per-list state for a schematic listing (Browse or Mine).
     * Allocated once per list; ImString and AtomicBoolean are never re-created.
     *
     * @param initialSearch  Initial search string (used to seed [searchBuf]).
     * @param initialSort    Initial sort key (must be a key in [SORT_OPTIONS]).
     */
    class SchematicListState(
        initialSearch: String = "",
        initialSort: String = "created_at",
    ) {
        var search: String = initialSearch
        var sort: String = initialSort
        var orderDesc: Boolean = true

        /** ImGui input buffer — allocated once, never re-created. */
        val searchBuf: ImString = ImString(256).apply { set(initialSearch) }

        /** Debounce: timestamp of the last keystroke, 0 when no pending change. */
        var searchLastChangeMs: Long = 0L
        var pendingSearchText: String? = null

        val items: MutableList<SchematicSummary> = mutableListOf()
        var nextPage: Int = 1
        var hasMore: Boolean = false
        var hasLoaded: Boolean = false
        val loadBusy: AtomicBoolean = AtomicBoolean(false)

        /** Bumped on every filter change so stale in-flight responses are ignored. */
        var requestGeneration: Int = 0

        var loadFailed: Boolean = false
        var errorMessage: String? = null
        var isStale: Boolean = false

        /** Sort cycling index into [SORT_OPTIONS]. */
        var sortIndex: Int = SORT_OPTIONS.indexOf(initialSort).coerceAtLeast(0)
    }

    // ---- shared schematic-list controls ----

    /** Renders the ImGui search text field and records pending text for debouncing. */
    fun renderSearchField(state: SchematicListState, fieldId: String, placeholder: String) {
        ImGui.setNextItemWidth(280f)
        if (Widgets.textField(fieldId, state.searchBuf, placeholder)) {
            val txt = state.searchBuf.get()
            if (txt != state.pendingSearchText) {
                state.pendingSearchText = txt
                state.searchLastChangeMs = System.currentTimeMillis()
            }
        }
    }

    /** Renders the Sort cycler button and advances the sort index on click. */
    fun renderSortCycler(
        state: SchematicListState,
        sortButtonSuffix: String,
        resetAndLoad: () -> Unit,
    ) {
        val label = "Sort: ${SORT_LABELS[SORT_OPTIONS[state.sortIndex]] ?: SORT_OPTIONS[state.sortIndex]}$sortButtonSuffix"
        if (ImGui.button(label)) {
            state.sortIndex = (state.sortIndex + 1) % SORT_OPTIONS.size
            state.sort = SORT_OPTIONS[state.sortIndex]
            resetAndLoad()
        }
    }

    /** Renders the Desc/Asc toggle button. [orderButtonSuffix] is appended to disambiguate imgui IDs. */
    fun renderOrderToggle(
        state: SchematicListState,
        orderButtonSuffix: String,
        resetAndLoad: () -> Unit,
    ) {
        val label = if (state.orderDesc) "Desc$orderButtonSuffix" else "Asc$orderButtonSuffix"
        if (ImGui.button(label)) {
            state.orderDesc = !state.orderDesc
            resetAndLoad()
        }
    }

    /**
     * Renders the stale-cache warning and the error banner with its Retry button.
     * [retryButtonId] must include any imgui-disambiguating suffix (e.g. "Retry##mine").
     */
    fun renderStatusBanners(
        state: SchematicListState,
        retryButtonId: String,
        onRetry: () -> Unit,
    ) {
        if (state.isStale) {
            Widgets.statusText("Offline — showing cached results", Widgets.StatusKind.WARNING)
        }
        if (state.loadFailed) {
            val msg = state.errorMessage ?: "Failed to load schematics"
            Widgets.statusText(msg, Widgets.StatusKind.DANGER)
            ImGui.sameLine()
            if (Widgets.button(retryButtonId)) {
                state.loadFailed = false
                state.errorMessage = null
                onRetry()
            }
        }
    }

    /**
     * Fires the search query after 300 ms of no changes (framerate-independent).
     * Clears the pending text and resets the timer once fired.
     */
    fun tickSearchDebounce(state: SchematicListState, resetAndLoad: () -> Unit) {
        if (state.pendingSearchText != null && state.searchLastChangeMs != 0L) {
            if (System.currentTimeMillis() - state.searchLastChangeMs >= 300L) {
                val text = state.pendingSearchText ?: ""
                state.pendingSearchText = null
                state.searchLastChangeMs = 0L
                if (text != state.search) {
                    state.search = text
                    resetAndLoad()
                }
            }
        }
    }

    // ---- card grid ----

    /**
     * Resolve the GL texture ID from a registered [Identifier] and ensure GL_LINEAR
     * completeness (fixes the black-texture bug on Apple GL 4.1 core profile).
     *
     * DynamicTexture created by PreviewImageManager is uploaded by MC without explicit
     * min-filter, which may default to GL_NEAREST_MIPMAP_LINEAR. Without mipmap data
     * that makes the texture "texture-incomplete" on strict core-profile drivers (Apple
     * M-series GL 4.1), sampling returns black. We patch it once per GL id.
     *
     * Returns null if the texture is not registered or the backend is not OpenGL.
     */
    fun resolvePreviewGlId(id: Identifier): Int? {
        return try {
            val mc = Minecraft.getInstance()
            val abstractTexture = mc.textureManager.getTexture(id)
            val gpuTexture = abstractTexture.getTexture()
            if (gpuTexture !is GlTexture) return null
            val glId = gpuTexture.glId()
            if (glId == 0) return null
            // Fix completeness exactly once per GL texture ID
            if (glLinearFixed.add(glId)) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glId)
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR)
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0)
            }
            glId
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Renders a single schematic card at the current cursor position.
     *
     * The invisible button spans the full card and opens SchematicDetailPanel on click.
     *
     * @param item         The schematic to render.
     * @param cardId       Unique imgui ID string (e.g. "##card_<id>").
     * @param showAuthor   Whether to show the first author instead of format in the subtitle.
     */
    private fun renderSchematicCard(
        item: SchematicSummary,
        cardId: String,
        showAuthor: Boolean,
    ) {
        val drawList = ImGui.getWindowDrawList()
        val cursorX = ImGui.getCursorScreenPos().x
        val cursorY = ImGui.getCursorScreenPos().y

        // ---- Invisible button spanning the full card (handles click + hover) ----
        val clicked = ImGui.invisibleButton(cardId, CARD_W, CARD_H)
        val hovered = ImGui.isItemHovered()

        // ---- Card background ----
        val bgColor = if (hovered) {
            val c = ImGuiColors.SURFACE_HOVER
            ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, c.w)
        } else {
            val c = ImGuiColors.SURFACE
            ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, c.w)
        }
        drawList.addRectFilled(cursorX, cursorY, cursorX + CARD_W, cursorY + CARD_H, bgColor, 4f)

        // ---- Card border ----
        val borderColor = if (hovered) {
            val c = ImGuiColors.BORDER_ACCENT
            ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, c.w)
        } else {
            val c = ImGuiColors.BORDER_SUBTLE
            ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, c.w)
        }
        drawList.addRect(cursorX, cursorY, cursorX + CARD_W, cursorY + CARD_H, borderColor, 4f)

        // ---- Thumbnail area ----
        val thumbX1 = cursorX
        val thumbY1 = cursorY
        val thumbX2 = cursorX + CARD_W
        val thumbY2 = cursorY + THUMB_H

        val previewKey = item.id
        val previewUrl = item.previewImageUrl
        val entry = services.previewImages.getEntry(previewKey, previewUrl)

        if (entry != null) {
            val glId = resolvePreviewGlId(entry.id)
            if (glId != null) {
                // Draw the image via draw list so it layers correctly over the card background.
                // UV (0,0)→(1,1) = full image; ImGui handles sampling.
                drawList.addImage(
                    glId.toLong(),
                    thumbX1 + 1f, thumbY1 + 1f, thumbX2 - 1f, thumbY2 - 1f,
                    0f, 0f, 1f, 1f,
                    -1, // white tint (RGBA all-ones = -1 as signed Int; no tinting)
                )
            } else {
                // glId not yet resolved (texture registering) — show placeholder
                renderThumbPlaceholder(drawList, thumbX1, thumbY1, thumbX2, thumbY2, "...")
            }
        } else {
            // Determine placeholder label
            val label = when {
                services.previewImages.isLoading(previewKey) -> "..."
                previewUrl.isNullOrBlank() -> ""
                else -> "?"
            }
            renderThumbPlaceholder(drawList, thumbX1, thumbY1, thumbX2, thumbY2, label)
        }

        // ---- Thumbnail bottom separator ----
        val sepColor = ImGui.colorConvertFloat4ToU32(
            ImGuiColors.BORDER_SUBTLE.x, ImGuiColors.BORDER_SUBTLE.y,
            ImGuiColors.BORDER_SUBTLE.z, ImGuiColors.BORDER_SUBTLE.w
        )
        drawList.addLine(thumbX1, thumbY2, thumbX2, thumbY2, sepColor)

        // ---- Metadata area (rendered as overlaid ImGui text after restoring cursor) ----
        val metaX = cursorX + 6f
        val metaY = thumbY2 + 5f

        // Name line
        val nameLabel = truncateText(item.name, CARD_W - 12f)
        val textColor = ImGui.colorConvertFloat4ToU32(
            ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
            ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w
        )
        drawList.addText(metaX, metaY, textColor, nameLabel)

        // Subtitle line: author or format | tags
        val subtitleY = metaY + ImGui.getTextLineHeight() + 3f
        val subtitle = buildSubtitle(item, showAuthor)
        val mutedColor = ImGui.colorConvertFloat4ToU32(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w
        )
        drawList.addText(metaX, subtitleY, mutedColor, truncateText(subtitle, CARD_W - 12f))

        // ---- Click handler ----
        if (clicked) {
            SchematicDetailPanel.show(item)
        }
    }

    /** Fills the thumbnail area with a themed placeholder rect and centered label. */
    private fun renderThumbPlaceholder(
        drawList: imgui.ImDrawList,
        x1: Float, y1: Float, x2: Float, y2: Float,
        label: String,
    ) {
        val fillColor = ImGui.colorConvertFloat4ToU32(
            ImGuiColors.SURFACE_ALT.x, ImGuiColors.SURFACE_ALT.y,
            ImGuiColors.SURFACE_ALT.z, ImGuiColors.SURFACE_ALT.w
        )
        drawList.addRectFilled(x1 + 1f, y1 + 1f, x2 - 1f, y2 - 1f, fillColor, 3f)
        if (label.isNotEmpty()) {
            val mutedColor = ImGui.colorConvertFloat4ToU32(
                ImGuiColors.TEXT_FAINT.x, ImGuiColors.TEXT_FAINT.y,
                ImGuiColors.TEXT_FAINT.z, ImGuiColors.TEXT_FAINT.w
            )
            val lw = ImGui.calcTextSize(label).x
            val lh = ImGui.getTextLineHeight()
            val lx = x1 + ((x2 - x1) - lw) / 2f
            val ly = y1 + ((y2 - y1) - lh) / 2f
            drawList.addText(lx, ly, mutedColor, label)
        }
    }

    /** Builds the subtitle string for a card's second text line. */
    private fun buildSubtitle(item: SchematicSummary, showAuthor: Boolean): String {
        return if (showAuthor) {
            val author = item.authors.firstOrNull()?.lastSeenName
            val tagPart = item.tags.take(2).joinToString(", ") { it.name }
            when {
                author != null && tagPart.isNotEmpty() -> "$author · $tagPart"
                author != null -> author
                tagPart.isNotEmpty() -> tagPart
                else -> item.format ?: "—"
            }
        } else {
            val fmt = item.format ?: ""
            val tagPart = item.tags.take(2).joinToString(", ") { it.name }
            when {
                fmt.isNotEmpty() && tagPart.isNotEmpty() -> "$fmt · $tagPart"
                fmt.isNotEmpty() -> fmt
                tagPart.isNotEmpty() -> tagPart
                else -> "—"
            }
        }
    }

    /**
     * Truncates [text] so that it fits within [maxWidth] pixels using ImGui's font.
     * Appends "…" when truncation occurs.
     */
    private fun truncateText(text: String, maxWidth: Float): String {
        if (ImGui.calcTextSize(text).x <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = ImGui.calcTextSize(ellipsis).x
        var end = text.length
        while (end > 0 && ImGui.calcTextSize(text.substring(0, end)).x + ellipsisW > maxWidth) {
            end--
        }
        return text.substring(0, end.coerceAtLeast(0)) + ellipsis
    }

    /**
     * Renders the scrollable schematic card grid for either the Browse or Mine listing.
     *
     * Cards-per-row = floor(availContentWidth / (CARD_W + CARD_GAP)), minimum 1.
     * Infinite-scroll: triggers [loadNext] when scroll position is within 20% of bottom.
     *
     * @param state             The per-window list state.
     * @param scrollId          Unique imgui child-window id (e.g. "##browse_scroll").
     * @param showAuthor        Browse shows author; Mine shows format/tags.
     * @param emptyNoSearchMsg  Message when the list is empty and no search is active.
     * @param emptySearchMsg    Message when the list is empty and a search is active.
     * @param loadNext          Called to fetch the next page (infinite scroll).
     */
    fun renderSchematicGrid(
        state: SchematicListState,
        scrollId: String,
        showAuthor: Boolean,
        emptyNoSearchMsg: String,
        emptySearchMsg: (String) -> String,
        loadNext: () -> Unit,
    ) {
        if (state.items.isEmpty()) {
            if (state.loadBusy.get()) {
                ImGui.textDisabled("Loading...")
            } else if (state.hasLoaded && !state.loadFailed) {
                val msg = if (state.search.isBlank()) emptyNoSearchMsg
                          else emptySearchMsg(state.search)
                ImGui.textDisabled(msg)
            }
            return
        }

        val availW = ImGui.getContentRegionAvail().x
        val availH = ImGui.getContentRegionAvail().y
        val scrollH = (availH - 4f).coerceAtLeast(80f)

        // Compute responsive columns
        val cols = ((availW + CARD_GAP) / (CARD_W + CARD_GAP)).toInt().coerceAtLeast(1)

        // Scrollable child window so infinite-scroll detection works
        if (ImGui.beginChild(scrollId, availW, scrollH, false)) {
            val items = state.items.toList()
            items.forEachIndexed { index, item ->
                val col = index % cols
                val row = index / cols

                if (col > 0) {
                    // Same row: advance X by gap + card width
                    ImGui.sameLine(0f, CARD_GAP)
                } else if (row > 0) {
                    // New row: add vertical gap
                    ImGui.dummy(0f, CARD_GAP)
                    // sameLine with 0 offset resets X to the start of the line after dummy
                }

                val cardId = "##card_${item.id}_${if (showAuthor) "b" else "m"}"
                renderSchematicCard(item, cardId, showAuthor)
            }

            // Loading-more indicator
            if (state.loadBusy.get() && state.items.isNotEmpty()) {
                ImGui.spacing()
                ImGui.textDisabled("Loading more...")
            }

            // Infinite-scroll: load next page when near the bottom
            if (state.hasMore && !state.loadBusy.get() && !state.loadFailed) {
                val scrollY = ImGui.getScrollY()
                val scrollMaxY = ImGui.getScrollMaxY()
                if (scrollMaxY > 0f && scrollY >= scrollMaxY - scrollH * 0.2f) {
                    loadNext()
                }
            }
        }
        ImGui.endChild()
    }

    // ---- data loading ----

    /**
     * Reset [state] to page 1 and load. [load] is the window's page loader (it captures the
     * window's own context/tag-filter state).
     */
    fun resetAndLoad(state: SchematicListState, load: (SchematicListState) -> Unit) {
        state.requestGeneration++
        state.items.clear()
        state.nextPage = 1
        state.hasMore = false
        state.isStale = false
        state.hasLoaded = true
        state.loadFailed = false
        state.errorMessage = null
        load(state)
    }

    /**
     * Issue a page request for [state]. [context] selects the API route (Public/Mine/Community,
     * or null for the My-Schematics window which is always mineOnly). [tags] and
     * [filterConstraints] are applied only when the caller passes them (Browse window).
     * [reload] is used to re-issue when a stale in-flight response is dropped.
     */
    fun loadNextPage(
        state: SchematicListState,
        context: Context?,
        tags: List<String>,
        filterConstraints: List<FilterConstraint>,
        reload: (SchematicListState) -> Unit,
    ) {
        val page = state.nextPage
        val generation = state.requestGeneration

        val query = BrowseQuery(
            search = state.search.takeIf { it.isNotBlank() },
            tags = tags,
            filterConstraints = filterConstraints,
            sort = state.sort,
            order = if (state.orderDesc) "desc" else "asc",
            page = page,
        )
        services.call(
            busy = state.loadBusy,
            block = {
                when (context) {
                    is Context.Community -> services.cached.communitySchematics(context.slug, query)
                    is Context.Mine -> services.cached.schematics(query, mineOnly = true)
                    is Context.Public -> services.cached.schematics(query)
                    null -> services.cached.schematics(query, mineOnly = true) // My Schematics window
                }
            },
        ) { result -> onPageResult(state, generation, page, result, reload) }
    }

    private fun onPageResult(
        state: SchematicListState,
        generation: Int,
        page: Int,
        result: ApiResult<StaleAware<Page<SchematicSummary>>>,
        reload: (SchematicListState) -> Unit,
    ) {
        if (generation != state.requestGeneration) {
            // Stale in-flight: re-issue for current generation if nothing else is in flight.
            if (!state.loadBusy.get()) reload(state)
            return
        }
        when (result) {
            is ApiResult.Success -> {
                val stale = result.value
                if (page == 1) state.items.clear()
                state.items.addAll(stale.value.items)
                state.nextPage = page + 1
                state.hasMore = stale.value.hasMore
                state.loadFailed = false
                state.errorMessage = null
                state.isStale = stale.isStale
            }
            is ApiResult.Failure -> {
                state.loadFailed = true
                state.errorMessage = when (val e = result.error) {
                    is io.schemat.connector.core.modapi.ApiError.Offline -> "Offline — can't reach schemat.io"
                    is io.schemat.connector.core.modapi.ApiError.Unauthorized -> "Not signed in"
                    is io.schemat.connector.core.modapi.ApiError.NotFound -> "Not found (${e.message})"
                    is io.schemat.connector.core.modapi.ApiError.Server -> "Server error (${e.status})"
                    is io.schemat.connector.core.modapi.ApiError.Unexpected -> e.message
                    else -> "Error loading schematics"
                }
            }
        }
    }
}
