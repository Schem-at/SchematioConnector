package io.schemat.connector.fabric.client.ui.widgets

import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.core.modapi.dto.TagFilterDef
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.TabBarWidget
import io.schemat.connector.fabric.client.ui.foundation.ThemedTextField
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/**
 * Embeddable, TABBED tag picker - the tag-tree/search/chips/filter-value UI shared by
 * the inline upload-wizard details step and the [io.schemat.connector.fabric.client.ui.TagSelectorScreen]
 * popup. One [Tab] per tag tree (e.g. "Minecraft" first/default, then a community);
 * the active tab scopes the tree AND the search, while the selection (chips + filter
 * values/constraints) is SHARED across tabs - the chips row always shows everything
 * currently selected, regardless of which tab is active.
 *
 * Hosting (mirrors [PlayerListEditorWidget]): the panel is a long-lived field on the
 * host screen so its state survives `clearAndInit`; each `init()` the host calls
 * [layout] with the panel's rect and registers the returned widgets via
 * `addRenderableWidget`, calls [render] every frame for the labels / tab underline /
 * filter rows, and forwards unhandled [mouseScrolled] for the filter column. MouseButtonEvent,
 * typing and per-widget scrolling flow through the screen's normal child handling.
 * Selection mutations invoke [onMutated] - the host should `rebuildWidgets()` so the
 * filter rows rebuild (host screens keep their own state in fields, so this is safe).
 *
 * [Mode.ASSIGN] (upload / edit): non-[TagNode.isManuallyAssignable] rows render
 * disabled; filter rows edit literal values (enum cycler, bool toggle, numeric field
 * with live [TagFilterDef.validate] errors). Required filters are starred and
 * [validationError] reports them until set.
 *
 * [Mode.FILTER] (browse): every row is selectable; filter rows edit browse
 * constraints - enum/bool as an Any/value cycler ([FilterConstraint.Exact]), int/float
 * as min/max fields ([FilterConstraint.Range]).
 *
 * Results are read via [selection] (or [selectedTagIds] / [filterValues] /
 * [constraints]); the host decides when (popup Done button, wizard upload). Branches
 * containing the selection auto-expand on [setInitial]/[setTabs] so a re-shown picker
 * never hides its checked rows.
 */
class TagPickerPanel(
    private val mode: Mode,
    private val onMutated: () -> Unit,
) {

    enum class Mode { ASSIGN, FILTER }

    /**
     * One tab: a labeled tag tree. [emptyHint] is the tree's empty-state message -
     * used to explain a deliberately empty tab (e.g. "Pick a community to use its
     * tags" while the upload wizard has no community selected).
     */
    data class Tab(val label: String, val nodes: List<TagNode>, val emptyHint: String = "No tags available")

    /**
     * Current picker state: the chosen tag ids plus - mode-dependent - either literal
     * [filterValues] (ASSIGN; for `setTags` / `UploadRequest.tagFilters`) or browse
     * [filterConstraints] (FILTER; for `BrowseQuery.filterConstraints`).
     */
    data class Selection(
        val tagIds: Set<String>,
        val filterValues: Map<Long, String>,
        val filterConstraints: List<FilterConstraint>,
    )

    companion object {
        private const val GAP = Theme.MD
        private const val TAB_BAR_HEIGHT = 16
        private const val CONTROL_HEIGHT = 18
        private const val TREE_ROW_HEIGHT = 14
        private const val INDENT = 10
        private const val BOX_SIZE = 8
        private const val FILTER_ROW_HEIGHT = 42
        private const val CHIPS_HEIGHT = 34
        private const val CHIPS_HEIGHT_COMPACT = 22
        /** Sentinel cycler entry meaning "no value" (Unset in ASSIGN, Any in FILTER). */
        private const val UNSET = ""

        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()
    }

    private val font get() = Minecraft.getInstance().font
    private val TagNode.displayName: String get() = name.ifBlank { id }

    // ---- tabs ----
    private var tabs: List<Tab> = emptyList()
    private var activeTab = 0
    /** Root ids already auto-expanded once - re-[setTabs] must not undo user collapses. */
    private val seenRootIds = mutableSetOf<String>()

    // ---- selection state (survives relayout) ----
    private val selected = LinkedHashSet<String>()
    /** ASSIGN: filter id -> literal value (also the FILTER store for unknown-type exact text). */
    private val assignValues = mutableMapOf<Long, String>()
    /** FILTER: filter id -> exact constraint value (enum/bool). */
    private val exactValues = mutableMapOf<Long, String>()
    /** FILTER: raw min/max text per numeric filter id. */
    private val rangeMinText = mutableMapOf<Long, String>()
    private val rangeMaxText = mutableMapOf<Long, String>()

    private var searchText = ""
    private val expanded = mutableSetOf<String>()
    private var treeScroll = 0.0
    private var filterScroll = 0

    // ---- node lookups (rebuilt by setTabs) ----
    private val nodeById = mutableMapOf<String, TagNode>()
    /** Ancestor path ("A > B") per node id, used as context in search results. */
    private val pathById = mutableMapOf<String, String>()
    /** Parent id per node id, used to auto-expand branches holding the selection. */
    private val parentById = mutableMapOf<String, String>()
    /** Every filter id defined by any node in any tab. */
    private val knownFilterIds = mutableSetOf<Long>()

    // ---- state seeding ----

    /** Seed selection + filter state (replaces any current state). */
    fun setInitial(
        selection: Set<String>,
        filterValues: Map<Long, String> = emptyMap(),
        constraints: List<FilterConstraint> = emptyList(),
    ) {
        selected.clear()
        selected.addAll(selection)
        assignValues.clear()
        assignValues.putAll(filterValues)
        exactValues.clear()
        rangeMinText.clear()
        rangeMaxText.clear()
        constraints.forEach { constraint ->
            when (constraint) {
                is FilterConstraint.Exact -> exactValues[constraint.filterId] = constraint.value
                is FilterConstraint.Range -> {
                    constraint.min?.let { rangeMinText[constraint.filterId] = formatNumber(it) }
                    constraint.max?.let { rangeMaxText[constraint.filterId] = formatNumber(it) }
                }
            }
        }
        expandSelectionAncestors()
    }

    /**
     * (Re)build the tabs and node lookups, keeping the selection. New roots start
     * expanded (depth 1); roots the user already collapsed stay collapsed.
     *
     * [pruneSelectionToKnown] drops selected ids (and their filter state) that no
     * longer exist in any tab - the upload wizard passes `true` when the community
     * changes so the old community's tags don't linger invisibly. The popup wrapper
     * passes `false`: unknown ids there are deliberate (the edit screen re-submits
     * current tags that fall outside the loaded trees).
     */
    fun setTabs(newTabs: List<Tab>, pruneSelectionToKnown: Boolean = false) {
        tabs = newTabs
        if (activeTab >= tabs.size) activeTab = 0
        nodeById.clear()
        pathById.clear()
        parentById.clear()
        knownFilterIds.clear()
        tabs.forEach { tab ->
            fun walk(node: TagNode, path: String) {
                nodeById.putIfAbsent(node.id, node)
                pathById.putIfAbsent(node.id, path)
                node.filters.forEach { knownFilterIds.add(it.id) }
                val childPath = if (path.isEmpty()) node.displayName else "$path > ${node.displayName}"
                node.children.forEach { child ->
                    parentById.putIfAbsent(child.id, node.id)
                    walk(child, childPath)
                }
            }
            tab.nodes.forEach { root ->
                if (seenRootIds.add(root.id)) expanded.add(root.id)
                walk(root, "")
            }
        }
        if (pruneSelectionToKnown) {
            selected.retainAll { it in nodeById }
            val allowed = selected
                .flatMap { id -> nodeById[id]?.filters?.map { it.id } ?: emptyList() }
                .toSet()
            assignValues.keys.retainAll(allowed)
            exactValues.keys.retainAll(allowed)
            rangeMinText.keys.retainAll(allowed)
            rangeMaxText.keys.retainAll(allowed)
        }
        expandSelectionAncestors()
        treeWidget?.rebuildRows()
    }

    /** Expand every ancestor of a selected node so checked rows aren't hidden. */
    private fun expandSelectionAncestors() {
        selected.forEach { id ->
            var parent = parentById[id]
            while (parent != null) {
                expanded.add(parent)
                parent = parentById[parent]
            }
        }
    }

    // ---- results ----

    fun selectedTagIds(): Set<String> = selected.toSet()

    /**
     * ASSIGN literal values, trimmed and pruned to the active selection's filters -
     * EXCEPT values for unknown filter ids (filters of tags outside the loaded trees,
     * e.g. the edit screen's "Current" tags), which are kept so they aren't silently
     * cleared server-side. Empty in FILTER mode.
     */
    fun filterValues(): Map<Long, String> {
        if (mode == Mode.FILTER) return emptyMap()
        val activeIds = activeFilters().map { it.second.id }.toSet()
        return assignValues
            .filterKeys { it in activeIds || it !in knownFilterIds }
            .mapValues { it.value.trim() }
            .filterValues { it.isNotEmpty() }
    }

    /** FILTER-mode browse constraints built from the filter rows. Empty in ASSIGN mode. */
    fun constraints(): List<FilterConstraint> {
        if (mode == Mode.ASSIGN) return emptyList()
        val out = mutableListOf<FilterConstraint>()
        activeFilters().forEach { (_, filter) ->
            when (filter.type) {
                "int", "float" -> {
                    val min = rangeMinText[filter.id]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                    val max = rangeMaxText[filter.id]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                    if (min != null || max != null) out.add(FilterConstraint.Range(filter.id, min, max))
                }
                "enum", "bool" ->
                    exactValues[filter.id]?.takeIf { it.isNotBlank() }
                        ?.let { out.add(FilterConstraint.Exact(filter.id, it)) }
                else ->
                    exactValues[filter.id]?.takeIf { it.isNotBlank() }
                        ?.let { out.add(FilterConstraint.Exact(filter.id, it.trim())) }
            }
        }
        return out
    }

    /** The full current state, mode-shaped (see [Selection]). */
    fun selection(): Selection = Selection(selectedTagIds(), filterValues(), constraints())

    /**
     * Blocking problem with the current state, or null when submittable: an invalid
     * filter value / malformed range, or (ASSIGN) a required filter left unset.
     */
    fun validationError(): String? {
        val filters = activeFilters()
        filters.firstNotNullOfOrNull { (_, filter) -> filterError(filter) }?.let { return it }
        if (mode == Mode.ASSIGN) {
            val missing = filters.filter { (_, filter) -> filter.isRequired && assignValues[filter.id].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                return "Required filter(s) not set: " + missing.joinToString(", ") { it.second.name }
            }
        }
        return null
    }

    // ---- layout ----

    private var rightX = 0
    private var rightW = 0
    private var chipsLabelY = 0
    private var filtersLabelY = 0
    private var filterAreaTop = 0
    private var filterAreaBottom = 0
    private var totalFilterRows = 0
    private var visibleFilterRows = 1
    private var tabBar: TabBarWidget? = null
    private var treeWidget: TreeWidget? = null

    private class FilterRowDraw(val label: String, val y: Int, val error: () -> String?)
    private val filterRowDraws = mutableListOf<FilterRowDraw>()

    /**
     * (Re)build the panel's widgets inside the given rect. The host must register
     * every returned widget via `addRenderableWidget` and call [render] each frame.
     */
    fun layout(x: Int, y: Int, w: Int, h: Int): List<AbstractWidget> {
        val out = mutableListOf<AbstractWidget>()
        filterRowDraws.clear()
        var cy = y
        tabBar = null
        if (tabs.size > 1) {
            val bar = TabBarWidget(
                x, cy, w, TAB_BAR_HEIGHT,
                labels = tabs.map { it.label },
                selectedIndex = { activeTab },
            ) { index ->
                if (index != activeTab) {
                    activeTab = index
                    treeScroll = 0.0
                    treeWidget?.rebuildRows()
                }
            }
            out += bar.widgets()
            tabBar = bar
            cy += TAB_BAR_HEIGHT + GAP
        }

        val search = ThemedTextField(
            font, x, cy, w, CONTROL_HEIGHT,
            Component.literal("Search tags"), placeholder = "Search tags...",
        )
        search.value = searchText
        search.setResponder { text ->
            if (text != searchText) {
                searchText = text
                treeScroll = 0.0
                treeWidget?.rebuildRows()
            }
        }
        out += search
        cy += CONTROL_HEIGHT + GAP

        val contentTop = cy
        val contentBottom = y + h
        val treeW = (w - GAP) * 11 / 20
        val tree = TreeWidget(x, contentTop, treeW, (contentBottom - contentTop).coerceAtLeast(40))
        treeWidget = tree
        out += tree

        rightX = x + treeW + GAP
        rightW = w - treeW - GAP
        chipsLabelY = contentTop
        val chipsTop = contentTop + 10
        // Tight hosts (the inline upload panel on small GUI scales) get a shorter
        // chip box so at least one filter row stays visible.
        val chipsHeight = if (contentBottom - chipsTop >= 110) CHIPS_HEIGHT else CHIPS_HEIGHT_COMPACT
        out += ChipsWidget(rightX, chipsTop, rightW, chipsHeight)

        filtersLabelY = chipsTop + chipsHeight + GAP
        filterAreaTop = filtersLabelY + 10
        filterAreaBottom = contentBottom
        buildFilterRows(out)
        return out
    }

    // ---- filter rows ----

    /** Filters of the currently selected tags, in selection order, deduped by filter id. */
    private fun activeFilters(): List<Pair<TagNode, TagFilterDef>> {
        val seen = mutableSetOf<Long>()
        val out = mutableListOf<Pair<TagNode, TagFilterDef>>()
        selected.forEach { id ->
            val node = nodeById[id] ?: return@forEach
            node.filters.forEach { filter -> if (seen.add(filter.id)) out.add(node to filter) }
        }
        return out
    }

    private fun buildFilterRows(out: MutableList<AbstractWidget>) {
        val specs = activeFilters()
        totalFilterRows = specs.size
        val areaHeight = (filterAreaBottom - filterAreaTop).coerceAtLeast(0)
        visibleFilterRows = (areaHeight / FILTER_ROW_HEIGHT).coerceAtLeast(1)
        filterScroll = filterScroll.coerceIn(0, (totalFilterRows - visibleFilterRows).coerceAtLeast(0))
        var y = filterAreaTop
        specs.drop(filterScroll).take(visibleFilterRows).forEach { (node, filter) ->
            addFilterRow(out, node, filter, y)
            y += FILTER_ROW_HEIGHT
        }
    }

    private fun addFilterRow(out: MutableList<AbstractWidget>, node: TagNode, filter: TagFilterDef, y: Int) {
        val unitSuffix = filter.unit?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val requiredMark = if (mode == Mode.ASSIGN && filter.isRequired) " *" else ""
        val label = "${node.displayName}: ${filter.name}$unitSuffix$requiredMark"
        val controlY = y + 11
        val isEnum = filter.type == "enum"
        val isBool = filter.type == "bool"
        val isNumeric = filter.type == "int" || filter.type == "float"
        when {
            isEnum || isBool -> {
                val store = if (mode == Mode.ASSIGN) assignValues else exactValues
                val unsetLabel = if (mode == Mode.ASSIGN) "Unset" else "Any"
                val values: List<String>
                val display: (String) -> String
                if (isEnum) {
                    values = listOf(UNSET) + filter.enumValues
                    display = { v -> if (v == UNSET) unsetLabel else v }
                } else if (mode == Mode.ASSIGN) {
                    values = listOf(UNSET, "true", "false")
                    display = { v -> if (v == UNSET) unsetLabel else if (v == "true") "True" else "False" }
                } else {
                    values = listOf(UNSET, "1", "0")
                    display = { v -> if (v == UNSET) unsetLabel else if (v == "1") "Yes" else "No" }
                }
                val current = store[filter.id]?.takeIf { it in values } ?: UNSET
                out += cyclerButton(rightX, controlY, rightW, values, current, display) { value ->
                    setOrRemove(store, filter.id, value)
                }
            }
            isNumeric && mode == Mode.FILTER -> {
                val half = (rightW - GAP) / 2
                out += textValueField(rightX, controlY, half, "Min", rangeMinText, filter.id)
                out += textValueField(rightX + half + GAP, controlY, rightW - half - GAP, "Max", rangeMaxText, filter.id)
            }
            else -> {
                // ASSIGN numeric/unknown types, FILTER unknown types: free-text value.
                val store = if (mode == Mode.ASSIGN) assignValues else exactValues
                out += textValueField(rightX, controlY, rightW, "Value", store, filter.id)
            }
        }
        filterRowDraws.add(FilterRowDraw(label, y) { filterError(filter) })
    }

    /** Themed value cycler: a [FlatButton.secondary] stepping through [values] on click. */
    private fun cyclerButton(
        x: Int,
        y: Int,
        buttonWidth: Int,
        values: List<String>,
        current: String,
        display: (String) -> String,
        onChange: (String) -> Unit,
    ): FlatButton {
        var index = values.indexOf(current).coerceAtLeast(0)
        lateinit var button: FlatButton
        button = FlatButton.secondary(x, y, buttonWidth, Component.literal(display(values[index])), height = CONTROL_HEIGHT) {
            index = (index + 1) % values.size
            button.message = Component.literal(display(values[index]))
            onChange(values[index])
        }
        return button
    }

    private fun textValueField(
        x: Int,
        y: Int,
        fieldWidth: Int,
        placeholder: String,
        store: MutableMap<Long, String>,
        filterId: Long,
    ): ThemedTextField {
        val field = ThemedTextField(
            font, x, y, fieldWidth, CONTROL_HEIGHT,
            Component.literal(placeholder), placeholder = placeholder,
        )
        field.value = store[filterId] ?: ""
        field.setResponder { value -> setOrRemove(store, filterId, value) }
        return field
    }

    private fun setOrRemove(store: MutableMap<Long, String>, filterId: Long, value: String) {
        if (value.isBlank()) store.remove(filterId) else store[filterId] = value
    }

    /** Live per-row error: invalid literal value (ASSIGN) or malformed range (FILTER). */
    private fun filterError(filter: TagFilterDef): String? = when {
        mode == Mode.ASSIGN ->
            assignValues[filter.id]?.takeIf { it.isNotBlank() }?.let { filter.validate(it.trim()) }
        filter.type == "int" || filter.type == "float" -> rangeError(filter.id)
        else -> null
    }

    private fun rangeError(filterId: Long): String? {
        val minText = rangeMinText[filterId]?.trim().orEmpty()
        val maxText = rangeMaxText[filterId]?.trim().orEmpty()
        val min = minText.toDoubleOrNull()
        val max = maxText.toDoubleOrNull()
        return when {
            minText.isNotEmpty() && min == null -> "Min must be a number"
            maxText.isNotEmpty() && max == null -> "Max must be a number"
            min != null && max != null && min > max -> "Min is greater than max"
            else -> null
        }
    }

    // ---- selection mutation ----

    private fun toggleTag(node: TagNode) {
        if (node.id in selected) {
            selected.remove(node.id)
            removeFilterState(node)
        } else {
            selected.add(node.id)
            if (mode == Mode.ASSIGN) {
                // Seed newly selected tags' filter defaults (never for pre-existing
                // selections - their absence may be meaningful server-side).
                node.filters.forEach { filter ->
                    val default = filter.defaultValue
                    if (default != null && filter.id !in assignValues) assignValues[filter.id] = default
                }
            }
        }
        onMutated()
    }

    private fun removeFilterState(node: TagNode) {
        node.filters.forEach { filter ->
            assignValues.remove(filter.id)
            exactValues.remove(filter.id)
            rangeMinText.remove(filter.id)
            rangeMaxText.remove(filter.id)
        }
    }

    // ---- host-driven scrolling (filter column; tree/chips scroll as widgets) ----

    /** Forward from the host screen when its own `mouseScrolled` didn't consume. */
    fun mouseScrolled(mouseX: Double, mouseY: Double, verticalAmount: Double): Boolean {
        if (mouseX >= rightX && mouseX < rightX + rightW && mouseY >= filterAreaTop && mouseY < filterAreaBottom) {
            val maxScroll = (totalFilterRows - visibleFilterRows).coerceAtLeast(0)
            if (maxScroll > 0) {
                val next = (filterScroll + if (verticalAmount > 0) -1 else 1).coerceIn(0, maxScroll)
                if (next != filterScroll) {
                    filterScroll = next
                    onMutated()
                }
                return true
            }
        }
        return false
    }

    // ---- rendering ----

    /** Draw the labels, tab underline and filter rows; widgets render themselves. */
    fun render(context: GuiGraphics) {
        tabBar?.render(context)
        Theme.sectionLabel(context, font, "Selected (${selected.size})", rightX, chipsLabelY)
        if (totalFilterRows > 0) {
            val suffix = if (totalFilterRows > visibleFilterRows) " (scroll for more)" else ""
            Theme.sectionLabel(
                context, font,
                font.plainSubstrByWidth("Filters$suffix", rightW),
                rightX, filtersLabelY,
            )
        }
        filterRowDraws.forEach { row ->
            Theme.label(
                context, font,
                font.plainSubstrByWidth(row.label, rightW),
                rightX, row.y,
            )
            row.error()?.let { error ->
                Theme.label(
                    context, font,
                    font.plainSubstrByWidth(error, rightW),
                    rightX, row.y + 30, Theme.DANGER,
                )
            }
        }
    }

    // ---- tree widget ----

    private class TreeRow(val node: TagNode, val depth: Int, val label: String, val hasChildren: Boolean)

    private inner class TreeWidget(x: Int, y: Int, w: Int, h: Int) :
        AbstractWidget(x, y, w, h, Component.literal("Tag tree")) {

        private var rows: List<TreeRow> = emptyList()

        init {
            rebuildRows()
        }

        private val contentHeight: Int get() = rows.size * TREE_ROW_HEIGHT
        private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

        fun rebuildRows() {
            val query = searchText.trim().lowercase()
            val nodes = tabs.getOrNull(activeTab)?.nodes ?: emptyList()
            rows = if (query.isEmpty()) buildTreeRows(nodes) else buildSearchRows(nodes, query)
            treeScroll = treeScroll.coerceIn(0.0, maxScroll)
        }

        private fun buildTreeRows(nodes: List<TagNode>): List<TreeRow> = buildList {
            fun walk(node: TagNode, depth: Int) {
                add(TreeRow(node, depth, node.displayName, node.children.isNotEmpty()))
                if (node.id in expanded) node.children.forEach { walk(it, depth + 1) }
            }
            nodes.forEach { walk(it, 0) }
        }

        /** Substring matches rendered flat, each labeled with its ancestor path for context. */
        private fun buildSearchRows(nodes: List<TagNode>, query: String): List<TreeRow> = buildList {
            fun walk(node: TagNode) {
                if (node.displayName.lowercase().contains(query)) {
                    val path = pathById[node.id].orEmpty()
                    val label = if (path.isEmpty()) node.displayName else "$path > ${node.displayName}"
                    add(TreeRow(node, 0, label, hasChildren = false))
                }
                node.children.forEach { walk(it) }
            }
            nodes.forEach { walk(it) }
        }

        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            if (!isMouseOver(mouseX, mouseY)) return false
            treeScroll = (treeScroll - verticalAmount * 24.0).coerceIn(0.0, maxScroll)
            return true
        }

        // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
        // primitive override delegates to the event-shaped method (compat shim).
        //? if <1.21.9 {
        /*override fun onClick(clickX: Double, clickY: Double) {
            onClick(MouseButtonEvent(clickX, clickY), false)
        }
        *///?}
        //? if >=1.21.9
        override
        fun onClick(click: MouseButtonEvent, doubled: Boolean) {
            val index = ((click.y() - y + treeScroll) / TREE_ROW_HEIGHT).toInt()
            val row = rows.getOrNull(index) ?: return
            val indentX = x + 4 + row.depth * INDENT
            if (row.hasChildren && click.x() < indentX + INDENT) {
                if (row.node.id in expanded) expanded.remove(row.node.id) else expanded.add(row.node.id)
                rebuildRows()
                return
            }
            if (mode == Mode.ASSIGN && !row.node.isManuallyAssignable) return
            toggleTag(row.node)
        }

        // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
        //? if >=26.1 {
        /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        *///?} else {
        override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        //?}
            context.fill(x, y, x + width, y + height, Theme.SURFACE_ALT)
            Theme.stroke(context, x, y, width, height, Theme.BORDER)
            if (rows.isEmpty()) {
                val message = when {
                    searchText.isNotBlank() -> "No tags match \"${searchText.trim()}\""
                    else -> tabs.getOrNull(activeTab)?.emptyHint ?: "No tags available"
                }
                Theme.emptyState(context, font, message, x, y, width, height)
                return
            }
            context.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1)
            rows.forEachIndexed { index, row ->
                val rowY = y + index * TREE_ROW_HEIGHT - treeScroll.toInt()
                if (rowY + TREE_ROW_HEIGHT < y || rowY > y + height) return@forEachIndexed
                renderNodeRow(context, row, rowY, mouseX, mouseY)
            }
            context.disableScissor()

            Theme.scrollbar(context, x + width - 4, y + 1, height - 2, contentHeight, height, treeScroll.toInt())
        }

        private fun renderNodeRow(context: GuiGraphics, row: TreeRow, rowY: Int, mouseX: Int, mouseY: Int) {
            val selectable = mode == Mode.FILTER || row.node.isManuallyAssignable
            val isSelected = row.node.id in selected
            val hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + TREE_ROW_HEIGHT
            if (isSelected) {
                context.fill(x + 1, rowY, x + width - 1, rowY + TREE_ROW_HEIGHT, Theme.withAlpha(Theme.ACCENT, 0x1C))
            } else if (hovered && selectable) {
                context.fill(x + 1, rowY, x + width - 1, rowY + TREE_ROW_HEIGHT, Theme.SURFACE_HOVER)
            }
            val indentX = x + 4 + row.depth * INDENT
            if (row.hasChildren) drawTriangle(context, indentX, rowY + 3, row.node.id in expanded)
            val boxX = indentX + INDENT
            drawCheckbox(context, boxX, rowY + (TREE_ROW_HEIGHT - BOX_SIZE) / 2, isSelected, selectable)
            var textX = boxX + BOX_SIZE + 4
            TagChips.parseColor(row.node.color)?.let { swatch ->
                context.fill(textX, rowY + 4, textX + 6, rowY + 10, swatch)
                textX += 9
            }
            val color = when {
                !selectable -> Theme.TEXT_FAINT
                isSelected -> Theme.TEXT_PRIMARY
                else -> Theme.TEXT_SECONDARY
            }
            context.drawString(
                font, font.plainSubstrByWidth(row.label, x + width - 6 - textX),
                textX, rowY + 3, color, false,
            )
        }

        private fun drawTriangle(context: GuiGraphics, tx: Int, ty: Int, open: Boolean) {
            val color = Theme.TEXT_MUTED
            if (open) {
                context.fill(tx, ty + 2, tx + 7, ty + 3, color)
                context.fill(tx + 1, ty + 3, tx + 6, ty + 4, color)
                context.fill(tx + 2, ty + 4, tx + 5, ty + 5, color)
                context.fill(tx + 3, ty + 5, tx + 4, ty + 6, color)
            } else {
                context.fill(tx + 2, ty, tx + 3, ty + 7, color)
                context.fill(tx + 3, ty + 1, tx + 4, ty + 6, color)
                context.fill(tx + 4, ty + 2, tx + 5, ty + 5, color)
                context.fill(tx + 5, ty + 3, tx + 6, ty + 4, color)
            }
        }

        private fun drawCheckbox(context: GuiGraphics, boxX: Int, boxY: Int, checkedState: Boolean, enabled: Boolean) {
            val border = when {
                !enabled -> Theme.BORDER_SUBTLE
                checkedState -> Theme.ACCENT
                else -> Theme.BORDER
            }
            context.fill(boxX, boxY, boxX + BOX_SIZE, boxY + BOX_SIZE, border)
            context.fill(boxX + 1, boxY + 1, boxX + BOX_SIZE - 1, boxY + BOX_SIZE - 1, Theme.SURFACE)
            if (checkedState) {
                val fill = if (enabled) Theme.ACCENT else Theme.ACCENT_DIM
                context.fill(boxX + 2, boxY + 2, boxX + BOX_SIZE - 2, boxY + BOX_SIZE - 2, fill)
            }
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }

    // ---- chips widget (selection across ALL tabs) ----

    private inner class ChipsWidget(x: Int, y: Int, w: Int, h: Int) :
        AbstractWidget(x, y, w, h, Component.literal("Selected tags")) {

        private fun chips(): List<TagChips.Chip> = selected.map { id ->
            val node = nodeById[id]
            TagChips.Chip(id, node?.displayName ?: id, node?.color)
        }

        // 1.21.9 changed input handlers to take event objects; on 1.21.8 the
        // primitive override delegates to the event-shaped method (compat shim).
        //? if <1.21.9 {
        /*override fun onClick(clickX: Double, clickY: Double) {
            onClick(MouseButtonEvent(clickX, clickY), false)
        }
        *///?}
        //? if >=1.21.9
        override
        fun onClick(click: MouseButtonEvent, doubled: Boolean) {
            val (placed, _) = TagChips.layout(chips(), x, y, width, height, font, withClose = true)
            val hit = placed.firstOrNull { p ->
                p.closeX >= 0 &&
                    click.x() >= p.closeX && click.x() < p.x + p.width &&
                    click.y() >= p.y && click.y() < p.y + TagChips.CHIP_HEIGHT
            } ?: return
            val id = hit.chip.id ?: return
            selected.remove(id)
            nodeById[id]?.let { removeFilterState(it) }
            onMutated()
        }

        // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
        //? if >=26.1 {
        /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        *///?} else {
        override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        //?}
            context.fill(x, y, x + width, y + height, Theme.SURFACE_ALT)
            Theme.stroke(context, x, y, width, height, Theme.BORDER)
            val list = chips()
            if (list.isEmpty()) {
                val message = if (mode == Mode.FILTER) "No tag filters" else "No tags selected"
                Theme.hint(context, font, message, x + Theme.XS, y + Theme.XS)
                return
            }
            val (placed, overflow) = TagChips.layout(list, x, y, width, height, font, withClose = true)
            TagChips.render(context, font, placed, overflow)
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }
}
