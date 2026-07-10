package io.schemat.connector.fabric.client.ui.widgets
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors

import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.core.modapi.dto.TagFilterDef
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.widgets.tagselector.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global singleton ImGui tag selector popup, replacing vanilla `TagSelectorScreen` and its
 * embedded `TagPickerPanel`.
 *
 * Tabbed by section ("Minecraft" global tree first, then one tab per community), with a
 * search filter scoped to the active tab, an auto-expanding tree whose selected ancestors
 * stay open, a shared selection-chips summary (chips show every selected tag regardless of
 * the active tab), per-tag colour-chip swatches drawn from [TagNode.color], hover
 * highlighting and indent guides.
 *
 * ## Attributes / filters
 *
 * A [Mode] controls how a selected tag's [TagNode.filters] are surfaced:
 *
 *  - [Mode.ASSIGN] (upload / edit): each filter renders a literal value input (enum
 *    cycler, bool cycler, numeric/text field) with live [TagFilterDef.validate] errors.
 *    Required filters are starred (`*`) and block **Apply** until set. The collected
 *    literal values are returned as `Map<filterId, String>` for `UploadRequest.tagFilters`.
 *  - [Mode.FILTER] (browse): each filter renders a browse constraint — numeric filters get
 *    min/max range fields, enum/bool get a value cycler — and the result is a
 *    `List<FilterConstraint>` (Exact / Range) for `BrowseQuery.filterConstraints`.
 *
 * ## Usage
 *
 *   // simple id multi-picker (ASSIGN, filters ignored) — back-compat:
 *   TagSelectorPopup.show(currentTagIds) { ids -> applyTags(ids) }
 *
 *   // full result with filter values / constraints:
 *   TagSelectorPopup.show(
 *       preselectedTagIds = ids,
 *       mode = TagSelectorPopup.Mode.FILTER,
 *       preselectedConstraints = constraints,
 *   ) { selectedIds, filterValues, constraints -> ... }
 *
 * The popup is rendered every frame via [render], called from `ImGuiOverlay.render`.
 * Nothing is drawn unless the popup is open.
 *
 * This widget lives in `ui/imgui/` (NOT `ui/imgui/panels/`), so it is exempt from
 * `checkThemeDiscipline` and may parse the tag hex colours directly for the swatches.
 */
object TagSelectorPopup {

    enum class Mode { ASSIGN, FILTER }

    private const val POPUP_ID = "##tag-selector-popup"
    private const val SEARCH_BUF_LEN = 128
    private const val FILTER_BUF_LEN = 64

    // ---- Tree-row geometry (drawn chrome, NOT default widgets) ----
    /** Per-depth indent, in pixels. */
    private const val INDENT = 14f
    /** Extra vertical padding above/below the row text inside the hover/selection band. */
    private const val ROW_PAD_Y = 3f
    /** Disclosure triangle hit-area / glyph size. */
    private const val TRI_SIZE = 9f
    /** Drawn-checkbox edge length. */
    private const val BOX_SIZE = 12f
    /** Tag colour-chip circle radius. */
    private const val CHIP_RADIUS = 4f
    /** Gap between the row's drawn glyphs. */
    private const val GLYPH_GAP = 6f
    /** Selection-chip corner rounding / inner padding. */
    private const val CHIP_ROUNDING = 4f
    private const val CHIP_PAD_X = 7f
    private const val CHIP_PAD_Y = 3f
    private const val CHIP_GAP = 5f

    // ---- State ----

    private var pendingOpen = false
    private var _isOpen = false

    /** True while the popup is visible (pending or currently shown). */
    fun isOpen(): Boolean = pendingOpen || _isOpen

    internal var mode: Mode = Mode.ASSIGN

    /** Ids pre-selected when show() was called; mutable during the popup session. */
    internal val selectedIds = linkedSetOf<String>()

    /** ASSIGN: filterId -> literal value buffer. */
    private val assignBufs = mutableMapOf<Long, ImString>()
    /** FILTER (enum/bool/other exact): filterId -> value buffer. */
    private val exactBufs = mutableMapOf<Long, ImString>()
    /** FILTER numeric ranges: filterId -> min / max buffers. */
    private val rangeMinBufs = mutableMapOf<Long, ImString>()
    private val rangeMaxBufs = mutableMapOf<Long, ImString>()

    /** Full callback (preferred). Receives ids + ASSIGN values + FILTER constraints. */
    private var onApplyFull: ((Set<String>, Map<Long, String>, List<FilterConstraint>) -> Unit)? = null
    /** Back-compat callback (ids only). */
    private var onApplyIds: ((Set<String>) -> Unit)? = null

    // ---- Tag data ----

    /** Sections loaded from the API: (sectionLabel, nodes). One tab each. */
    internal var sections: List<Pair<String, List<TagNode>>> = emptyList()
    private val loadBusy = AtomicBoolean(false)
    private var loadError: String? = null

    /** Bumped on each [show]; stale async callbacks compare against it and bail. */
    private var loadGeneration = 0

    /** Cached node/filter lookups, rebuilt whenever [sections] reference changes. */
    internal var sectionsSnapshot: List<Pair<String, List<TagNode>>> = emptyList()
    internal var flatNodesBySection: Map<String, List<TagNode>> = emptyMap()
    internal val nodeById = mutableMapOf<String, TagNode>()
    internal val parentById = mutableMapOf<String, String>()
    internal val filterById = mutableMapOf<Long, TagFilterDef>()

    // ---- Search / tree state ----
    private val searchBuf = ImString(SEARCH_BUF_LEN)
    private var activeTab = 0
    /** Expanded category node ids (tree state; ignored while searching). */
    internal val expanded = mutableSetOf<String>()

    // ---- Services accessor ----
    private val services get() = SchematioClientMod.instance.services

    // ---- Public entry points ----

    /**
     * Back-compat overload: a simple id multi-picker in [Mode.ASSIGN]. Filter values are
     * still editable in the UI but not surfaced to [onApply].
     */
    fun show(preselectedTagIds: Set<String>, onApply: (Set<String>) -> Unit) {
        reset(preselectedTagIds, Mode.ASSIGN, emptyMap(), emptyList())
        onApplyIds = onApply
        onApplyFull = null
        beginOpen()
    }

    /**
     * Full overload exposing tag attributes/filters.
     *
     * @param mode [Mode.ASSIGN] collects literal filter values (required filters block
     *   Apply); [Mode.FILTER] collects browse constraints.
     * @param preselectedFilterValues ASSIGN seed (filterId -> literal value).
     * @param preselectedConstraints FILTER seed.
     * @param onApply receives the chosen ids, the ASSIGN value map and the FILTER
     *   constraint list. In ASSIGN mode the constraint list is empty; in FILTER mode the
     *   value map is empty.
     */
    fun show(
        preselectedTagIds: Set<String>,
        mode: Mode,
        preselectedFilterValues: Map<Long, String> = emptyMap(),
        preselectedConstraints: List<FilterConstraint> = emptyList(),
        onApply: (Set<String>, Map<Long, String>, List<FilterConstraint>) -> Unit,
    ) {
        reset(preselectedTagIds, mode, preselectedFilterValues, preselectedConstraints)
        onApplyFull = onApply
        onApplyIds = null
        beginOpen()
    }

    private fun reset(
        preselectedTagIds: Set<String>,
        mode: Mode,
        filterValues: Map<Long, String>,
        constraints: List<FilterConstraint>,
    ) {
        this.mode = mode
        selectedIds.clear()
        selectedIds.addAll(preselectedTagIds)
        searchBuf.set("")
        activeTab = 0

        assignBufs.clear()
        exactBufs.clear()
        rangeMinBufs.clear()
        rangeMaxBufs.clear()
        filterValues.forEach { (id, v) -> assignBuf(id).set(v) }
        constraints.forEach { c ->
            when (c) {
                is FilterConstraint.Exact -> exactBuf(c.filterId).set(c.value)
                is FilterConstraint.Range -> {
                    c.min?.let { rangeMinBuf(c.filterId).set(formatNumber(it)) }
                    c.max?.let { rangeMaxBuf(c.filterId).set(formatNumber(it)) }
                }
            }
        }

        // Bump generation so any in-flight callbacks from a previous session are discarded.
        loadGeneration++
        // Reset sections so the loader always re-fetches fresh data.
        sections = emptyList()
        sectionsSnapshot = emptyList()
        flatNodesBySection = emptyMap()
        nodeById.clear()
        parentById.clear()
        filterById.clear()
        expanded.clear()
        loadError = null
        loadBusy.set(false)
    }

    private fun beginOpen() {
        pendingOpen = true
        loadTagsIfNeeded()
    }

    // ---- Buffer accessors ----

    internal fun assignBuf(id: Long) = assignBufs.getOrPut(id) { ImString(FILTER_BUF_LEN) }
    internal fun exactBuf(id: Long) = exactBufs.getOrPut(id) { ImString(FILTER_BUF_LEN) }
    internal fun rangeMinBuf(id: Long) = rangeMinBufs.getOrPut(id) { ImString(FILTER_BUF_LEN) }
    internal fun rangeMaxBuf(id: Long) = rangeMaxBufs.getOrPut(id) { ImString(FILTER_BUF_LEN) }

    // ---- Render ----

    fun render() {
        if (pendingOpen) {
            ImGui.openPopup(POPUP_ID)
            pendingOpen = false
            _isOpen = true
        }
        if (!_isOpen) return

        val displayW = ImGui.getIO().displaySizeX
        val displayH = ImGui.getIO().displaySizeY
        ImGui.setNextWindowPos(displayW / 2f, displayH / 2f, imgui.flag.ImGuiCond.Always, 0.5f, 0.5f)
        ImGui.setNextWindowSize(460f, 560f, imgui.flag.ImGuiCond.Always)

        val flags = ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoResize or ImGuiWindowFlags.NoCollapse

        if (ImGui.beginPopupModal(POPUP_ID, flags)) {
            renderContents()
            ImGui.endPopup()
        } else {
            _isOpen = false
        }
    }

    private fun renderContents() {
        rebuildCachesIfNeeded()

        // ---- Title row ----
        val title = if (mode == Mode.ASSIGN) "Assign Tags" else "Filter by Tags"
        ImGui.textColored(
            ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
            ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w, title,
        )
        ImGui.separator()

        // ---- Selection chips (shared across tabs) ----
        renderChips()

        // ---- Tabs ----
        if (sections.size > 1 && ImGui.beginTabBar("##tag-tabs")) {
            sections.forEachIndexed { i, (label, _) ->
                if (ImGui.beginTabItem("$label##tab-$i")) {
                    if (activeTab != i) {
                        activeTab = i
                        // Reset search when changing tabs to avoid confusion.
                    }
                    ImGui.endTabItem()
                }
            }
            ImGui.endTabBar()
        } else if (activeTab >= sections.size) {
            activeTab = 0
        }

        // ---- Search ----
        ImGui.setNextItemWidth(-1f)
        Widgets.textField("##tag-search", searchBuf, hint = "Search tags...")
        ImGui.separator()

        val filter = searchBuf.get().trim().lowercase()

        // ---- Tree + filter list in a scrollable region ----
        val childHeight = (ImGui.getContentRegionAvailY() - 48f).coerceAtLeast(80f)
        ImGui.beginChild("##tag-list", 0f, childHeight, false)
        renderTree(filter)
        ImGui.endChild()

        ImGui.separator()

        // ---- Validation + buttons ----
        val validationError = validationError()
        if (validationError != null) {
            Widgets.statusText(validationError, Widgets.StatusKind.DANGER)
        } else {
            ImGui.spacing()
        }

        if (Widgets.button("Cancel")) {
            closePopup()
        }
        ImGui.sameLine()
        val applyBlocked = validationError != null
        if (applyBlocked) ImGui.beginDisabled()
        val apply = Widgets.button("Apply", accent = true)
        if (applyBlocked) ImGui.endDisabled()
        if (apply && !applyBlocked) {
            val ids = selectedIds.toCollection(linkedSetOf())
            val values = collectAssignValues()
            val constraints = collectConstraints()
            val full = onApplyFull
            val idsOnly = onApplyIds
            closePopup()
            full?.invoke(ids, values, constraints)
            idsOnly?.invoke(ids)
        }
    }

    // ---- Tree rendering (custom drawn chrome — see drawDisclosure / drawCheckbox / drawColorChip) ----

    private fun renderTree(filter: String) {
        when {
            loadBusy.get() && sections.isEmpty() -> ImGui.textDisabled("Loading tags...")
            loadError != null && sections.isEmpty() ->
                Widgets.statusText("Failed to load tags", Widgets.StatusKind.DANGER)
            sections.isEmpty() -> ImGui.textDisabled("No tags available")
            else -> {
                val section = sections.getOrNull(activeTab) ?: sections.first()
                if (filter.isEmpty()) {
                    val roots = section.second
                    if (roots.isEmpty()) {
                        ImGui.textDisabled("No tags in this tab")
                    } else {
                        for (node in roots) renderNode(node, 0)
                    }
                } else {
                    // Flat, deduped substring-match list while searching, each row showing
                    // its ancestor path for context (mirrors the old buildSearchRows).
                    val matches = (flatNodesBySection[section.first] ?: emptyList())
                        .filter { filter in it.name.lowercase() }
                    if (matches.isEmpty()) {
                        ImGui.textDisabled("No matches")
                    } else {
                        val seen = mutableSetOf<String>()
                        for (node in matches) {
                            if (!seen.add(node.id)) continue
                            if (node.isManuallyAssignable) renderRow(node, 0, searching = true)
                        }
                    }
                }
            }
        }
    }

    /**
     * Render a category/leaf node and (when expanded) its descendants. Category nodes draw a
     * disclosure triangle and toggle expansion; assignable nodes draw a checkbox + colour chip
     * and toggle selection. An assignable category draws BOTH (the disclosure toggles, the
     * checkbox selects) on the same row. Selected tags reveal their filter inputs inline.
     */
    private fun renderNode(node: TagNode, depth: Int) {
        val hasChildren = node.children.isNotEmpty()
        renderRow(node, depth, searching = false)
        if (hasChildren && node.id in expanded) {
            for (child in node.children) renderNode(child, depth + 1)
        }
    }

    /**
     * A single tree row drawn entirely with [ImGui.getWindowDrawList]: a full-width
     * hover/selection background band, depth indent guides, an optional disclosure triangle,
     * an optional checkbox + colour chip, and the node name (path-prefixed while searching).
     * Hit-testing uses a single full-width [ImGui.invisibleButton]; the click X decides
     * whether the disclosure or the selection toggle fires.
     */
    private fun renderRow(node: TagNode, depth: Int, searching: Boolean) {
        val hasChildren = node.children.isNotEmpty() && !searching
        val selectable = node.isManuallyAssignable
        val isSelected = node.id in selectedIds
        val col = hexColor(node.color)
        val dl = ImGui.getWindowDrawList()

        val lineH = ImGui.getTextLineHeight()
        val rowH = lineH + ROW_PAD_Y * 2f
        val origin = ImGui.getCursorScreenPos()
        val rowX = origin.x
        val rowY = origin.y
        val fullW = ImGui.getContentRegionAvailX().coerceAtLeast(40f)

        // Full-row hit area first so isItemHovered() reflects the whole band.
        ImGui.invisibleButton("##row-${node.id}-$depth", fullW, rowH)
        val hovered = ImGui.isItemHovered()
        val clicked = ImGui.isItemClicked(ImGuiMouseButton.Left)

        // --- background band ---
        when {
            isSelected -> dl.addRectFilled(
                rowX, rowY, rowX + fullW, rowY + rowH,
                u32(ImGuiColors.ACCENT, 0.16f), 3f,
            )
            hovered -> dl.addRectFilled(
                rowX, rowY, rowX + fullW, rowY + rowH,
                u32(ImGuiColors.SURFACE_HOVER), 3f,
            )
        }

        // --- indent guides ---
        for (d in 1..depth) {
            val gx = rowX + INDENT * d - INDENT / 2f
            dl.addLine(gx, rowY, gx, rowY + rowH, u32(ImGuiColors.BORDER_SUBTLE), 1f)
        }

        var x = rowX + 3f + depth * INDENT
        val midY = rowY + rowH / 2f

        // --- disclosure triangle (categories) ---
        val triX = x
        if (hasChildren) {
            drawDisclosure(dl, triX, midY - TRI_SIZE / 2f, node.id in expanded)
        }
        x += TRI_SIZE + GLYPH_GAP

        // --- checkbox + colour chip (assignable rows) ---
        if (selectable) {
            drawCheckbox(dl, x, midY - BOX_SIZE / 2f, isSelected, enabled = true)
            x += BOX_SIZE + GLYPH_GAP
            drawColorChip(dl, x + CHIP_RADIUS, midY, col)
            x += CHIP_RADIUS * 2f + GLYPH_GAP
        }

        // --- name (path-prefixed while searching) ---
        val nameColor = when {
            !selectable && !hasChildren -> u32(ImGuiColors.TEXT_FAINT)
            isSelected -> u32(ImGuiColors.TEXT_PRIMARY)
            hasChildren && !selectable -> u32(ImGuiColors.TEXT_PRIMARY)
            else -> u32(ImGuiColors.TEXT_SECONDARY)
        }
        val label = if (searching) {
            val path = nodePath(node.id)
            if (path.isEmpty()) node.name else "$path > ${node.name}"
        } else {
            node.name
        }
        val textY = rowY + (rowH - lineH) / 2f
        dl.addText(x, textY, nameColor, label)

        // --- click routing ---
        if (clicked) {
            val mouseX = ImGui.getMousePosX()
            val triHit = hasChildren && mouseX < triX + TRI_SIZE + GLYPH_GAP / 2f
            when {
                triHit -> if (node.id in expanded) expanded.remove(node.id) else expanded.add(node.id)
                selectable -> if (isSelected) selectedIds.remove(node.id) else selectedIds.add(node.id)
                hasChildren -> if (node.id in expanded) expanded.remove(node.id) else expanded.add(node.id)
            }
        }

        // --- inline filter inputs for a selected tag ---
        if (isSelected && node.filters.isNotEmpty()) {
            ImGui.indent(INDENT + depth * INDENT)
            node.filters.forEach { renderFilterRow(it) }
            ImGui.unindent(INDENT + depth * INDENT)
        }
    }

    /** A small left/right chevron glyph centred in a (w × h) button at (x, y). */
    internal fun drawChevron(dl: imgui.ImDrawList, x: Float, y: Float, w: Float, h: Float, left: Boolean) {
        val col = u32(ImGuiColors.TEXT_SECONDARY)
        val cx = x + w / 2f
        val cy = y + h / 2f
        val r = 3f
        if (left) {
            dl.addLine(cx + r * 0.6f, cy - r, cx - r * 0.6f, cy, col, 1.5f)
            dl.addLine(cx - r * 0.6f, cy, cx + r * 0.6f, cy + r, col, 1.5f)
        } else {
            dl.addLine(cx - r * 0.6f, cy - r, cx + r * 0.6f, cy, col, 1.5f)
            dl.addLine(cx + r * 0.6f, cy, cx - r * 0.6f, cy + r, col, 1.5f)
        }
    }

    // ---- Selection chips ----

    private fun renderChips() {
        val label = if (mode == Mode.FILTER) "Tag filters" else "Selected"
        if (selectedIds.isEmpty()) {
            ImGui.textColored(
                ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
                ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
                if (mode == Mode.FILTER) "No tag filters" else "No tags selected",
            )
            ImGui.separator()
            return
        }
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "$label (${selectedIds.size}):",
        )

        val dl = ImGui.getWindowDrawList()
        val lineH = ImGui.getTextLineHeight()
        val chipH = lineH + CHIP_PAD_Y * 2f
        val closeW = lineH * 0.55f
        val rowStart = ImGui.getCursorScreenPos()
        val avail = ImGui.getContentRegionAvailX()
        var cx = rowStart.x
        var cy = rowStart.y
        var rows = 1

        // iterate a snapshot so removal during the loop is safe
        for (id in selectedIds.toList()) {
            val node = nodeById[id]
            val name = node?.name?.ifBlank { id } ?: id
            val tint = hexColor(node?.color)
            val nameW = ImGui.calcTextSize(name).x
            val chipW = CHIP_PAD_X + nameW + GLYPH_GAP + closeW + CHIP_PAD_X

            // wrap
            if (cx > rowStart.x && cx + chipW > rowStart.x + avail) {
                cx = rowStart.x
                cy += chipH + CHIP_GAP
                rows++
            }

            // chip background: tag-colour tint over a surface base, rounded, with a hairline border.
            val baseCol = if (tint != null) {
                u32(tint[0], tint[1], tint[2], 0.30f)
            } else {
                u32(ImGuiColors.ACCENT, 0.22f)
            }
            dl.addRectFilled(cx, cy, cx + chipW, cy + chipH, baseCol, CHIP_ROUNDING)
            val borderCol = if (tint != null) u32(tint[0], tint[1], tint[2], 0.9f) else u32(ImGuiColors.BORDER_ACCENT)
            dl.addRect(cx, cy, cx + chipW, cy + chipH, borderCol, CHIP_ROUNDING)

            // name
            dl.addText(cx + CHIP_PAD_X, cy + CHIP_PAD_Y, u32(ImGuiColors.TEXT_PRIMARY), name)

            // drawn close "x"
            val xx = cx + CHIP_PAD_X + nameW + GLYPH_GAP
            val xcx = xx + closeW / 2f
            val xcy = cy + chipH / 2f
            val r = closeW / 2f
            val closeHovered = ImGui.isMouseHoveringRect(xx, cy, xx + closeW, cy + chipH)
            val xCol = if (closeHovered) u32(ImGuiColors.DANGER) else u32(ImGuiColors.TEXT_MUTED)
            dl.addLine(xcx - r, xcy - r, xcx + r, xcy + r, xCol, 1.5f)
            dl.addLine(xcx - r, xcy + r, xcx + r, xcy - r, xCol, 1.5f)

            // hit area for the whole chip (click anywhere removes it, matching the old close affordance)
            ImGui.setCursorScreenPos(cx, cy)
            ImGui.invisibleButton("##chip-$id", chipW, chipH)
            if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
                selectedIds.remove(id)
            }

            cx += chipW + CHIP_GAP
        }

        // advance the ImGui cursor past the manually-drawn chip block.
        ImGui.setCursorScreenPos(rowStart.x, cy + chipH + 4f)
        ImGui.dummy(0f, 0f)
        ImGui.separator()
    }

    // ---- Color / number helpers ----

    private fun hexColor(hex: String?): FloatArray? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        val r = h.substring(0, 2).toIntOrNull(16) ?: return null
        val g = h.substring(2, 4).toIntOrNull(16) ?: return null
        val b = h.substring(4, 6).toIntOrNull(16) ?: return null
        return floatArrayOf(r / 255f, g / 255f, b / 255f)
    }

    // ---- Drawing primitives (mirror the old vanilla Theme chrome) ----

    /** Pack an [imgui.ImVec4] into the U32 colour ImDrawList expects. */
    internal fun u32(c: imgui.ImVec4, alpha: Float = c.w): Int =
        ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, alpha)

    private fun u32(r: Float, g: Float, b: Float, a: Float = 1f): Int =
        ImGui.colorConvertFloat4ToU32(r, g, b, a)

    /**
     * Disclosure triangle: right-pointing when collapsed, down-pointing when expanded —
     * filled, in [ImGuiColors.TEXT_MUTED]. Centred in a [TRI_SIZE]² box at (x, y).
     */
    private fun drawDisclosure(dl: imgui.ImDrawList, x: Float, y: Float, expanded: Boolean) {
        val col = u32(ImGuiColors.TEXT_MUTED)
        val s = TRI_SIZE
        // Inset so the glyph reads as a small triangle, not a full-cell block.
        val pad = 1.5f
        if (expanded) {
            // Down-pointing: apex at bottom-centre.
            dl.addTriangleFilled(
                x + pad, y + pad,
                x + s - pad, y + pad,
                x + s / 2f, y + s - pad,
                col,
            )
        } else {
            // Right-pointing: apex at right-centre.
            dl.addTriangleFilled(
                x + pad, y + pad,
                x + pad, y + s - pad,
                x + s - pad, y + s / 2f,
                col,
            )
        }
    }

    /**
     * Drawn checkbox: a rounded box (accent border when checked, subtle/normal otherwise),
     * a surface interior, and — when [checked] — an accent fill plus a two-stroke checkmark.
     */
    private fun drawCheckbox(dl: imgui.ImDrawList, x: Float, y: Float, checked: Boolean, enabled: Boolean) {
        val s = BOX_SIZE
        val border = when {
            !enabled -> ImGuiColors.BORDER_SUBTLE
            checked -> ImGuiColors.ACCENT
            else -> ImGuiColors.BORDER
        }
        dl.addRectFilled(x, y, x + s, y + s, u32(ImGuiColors.SURFACE), 2f)
        dl.addRect(x, y, x + s, y + s, u32(border), 2f)
        if (checked) {
            val accent = if (enabled) ImGuiColors.ACCENT else ImGuiColors.ACCENT_DIM
            dl.addRectFilled(x + 2f, y + 2f, x + s - 2f, y + s - 2f, u32(accent, accent.w * 0.55f), 1.5f)
            // Checkmark: short down-stroke into a long up-stroke.
            val mark = u32(ImGuiColors.TEXT_PRIMARY)
            dl.addLine(x + 2.5f, y + s / 2f, x + s * 0.42f, y + s - 3f, mark, 1.6f)
            dl.addLine(x + s * 0.42f, y + s - 3f, x + s - 2.5f, y + 2.5f, mark, 1.6f)
        }
    }

    /** Tag colour-chip: a filled circle (or hollow when no colour) with a subtle dark ring. */
    private fun drawColorChip(dl: imgui.ImDrawList, cx: Float, cy: Float, col: FloatArray?) {
        if (col != null) {
            dl.addCircleFilled(cx, cy, CHIP_RADIUS, u32(col[0], col[1], col[2], 1f), 12)
            dl.addCircle(cx, cy, CHIP_RADIUS, u32(ImGuiColors.BORDER, 0.6f), 12, 1f)
        } else {
            dl.addCircle(cx, cy, CHIP_RADIUS, u32(ImGuiColors.TEXT_FAINT), 12, 1f)
        }
    }

    private fun closePopup() {
        _isOpen = false
        onApplyFull = null
        onApplyIds = null
        ImGui.closeCurrentPopup()
    }

    // ---- Tag loading (mirrors the previous BrowseTab pattern) ----

    private fun loadTagsIfNeeded() {
        if (sections.isNotEmpty() || loadBusy.get()) return
        loadError = null
        val gen = loadGeneration

        services.call(
            busy = loadBusy,
            block = { services.cached.globalTags() },
        ) { globalResult ->
            if (loadGeneration != gen) return@call

            val globalNodes: List<TagNode> = when (globalResult) {
                is ApiResult.Success -> globalResult.value.value
                is ApiResult.Failure -> {
                    loadError = "Failed to load tags"
                    emptyList()
                }
            }

            val built = mutableListOf<Pair<String, List<TagNode>>>()
            if (globalNodes.isNotEmpty()) built.add("Minecraft" to globalNodes)

            services.call(
                block = { services.cached.me() },
            ) { meResult ->
                if (loadGeneration != gen) return@call

                if (meResult is ApiResult.Success) {
                    val communities = meResult.value.value.communities
                    if (communities.isEmpty()) {
                        sections = built
                    } else {
                        var remaining = communities.size
                        for (community in communities) {
                            services.call(
                                block = { services.cached.communityTags(community.slug) },
                            ) { tagResult ->
                                if (loadGeneration != gen) return@call

                                if (tagResult is ApiResult.Success) {
                                    val nodes = tagResult.value.value
                                    if (nodes.isNotEmpty()) built.add(community.name to nodes)
                                }
                                remaining--
                                if (remaining == 0) sections = built
                            }
                        }
                    }
                } else {
                    sections = built
                }
            }
        }
    }
}
