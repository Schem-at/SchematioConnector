package io.schemat.connector.fabric.client.ui.widgets.tagselector

import io.schemat.connector.core.modapi.FilterConstraint
import io.schemat.connector.core.modapi.dto.TagFilterDef
import io.schemat.connector.core.modapi.dto.TagNode
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup.Mode

/**
 * Tag-tree model helpers for [TagSelectorPopup]: node/filter caches, selection expansion,
 * result collection (ASSIGN values / FILTER constraints) and validation.
 */

/** Ancestor path ("A > B") for [id], for search-result context. */
internal fun TagSelectorPopup.nodePath(id: String): String {
    val names = ArrayDeque<String>()
    var parent = parentById[id]
    while (parent != null) {
        nodeById[parent]?.let { names.addFirst(it.name) }
        parent = parentById[parent]
    }
    return names.joinToString(" > ")
}

internal fun TagSelectorPopup.rebuildCachesIfNeeded() {
    if (sections === sectionsSnapshot) return
    sectionsSnapshot = sections
    nodeById.clear()
    parentById.clear()
    filterById.clear()
    val bySection = linkedMapOf<String, List<TagNode>>()
    sections.forEach { (label, nodes) ->
        val flat = mutableListOf<TagNode>()
        fun walk(n: TagNode, parent: String?) {
            nodeById.putIfAbsent(n.id, n)
            parent?.let { parentById.putIfAbsent(n.id, it) }
            n.filters.forEach { filterById.putIfAbsent(it.id, it) }
            flat += n
            n.children.forEach { walk(it, n.id) }
        }
        nodes.forEach { walk(it, null) }
        bySection[label] = flat
    }
    flatNodesBySection = bySection
    expandSelectionAncestors()
}

/** Expand every ancestor of a selected node so checked rows aren't hidden. */
internal fun TagSelectorPopup.expandSelectionAncestors() {
    selectedIds.forEach { id ->
        var parent = parentById[id]
        while (parent != null) {
            expanded.add(parent)
            parent = parentById[parent]
        }
    }
}

/** ASSIGN literal values for currently-selected tags' filters (blank dropped). */
internal fun TagSelectorPopup.collectAssignValues(): Map<Long, String> {
    if (mode != Mode.ASSIGN) return emptyMap()
    val out = linkedMapOf<Long, String>()
    activeFilters().forEach { filter ->
        val v = assignBuf(filter.id).get().trim()
        if (v.isNotEmpty()) out[filter.id] = v
    }
    return out
}

/** FILTER browse constraints for currently-selected tags' filters. */
internal fun TagSelectorPopup.collectConstraints(): List<FilterConstraint> {
    if (mode != Mode.FILTER) return emptyList()
    val out = mutableListOf<FilterConstraint>()
    activeFilters().forEach { filter ->
        when (filter.type) {
            "int", "float" -> {
                val min = rangeMinBuf(filter.id).get().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                val max = rangeMaxBuf(filter.id).get().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                if (min != null || max != null) out.add(FilterConstraint.Range(filter.id, min, max))
            }
            else -> exactBuf(filter.id).get().trim().takeIf { it.isNotBlank() }
                ?.let { out.add(FilterConstraint.Exact(filter.id, it)) }
        }
    }
    return out
}

/** Distinct filter defs across all currently-selected tags. */
internal fun TagSelectorPopup.activeFilters(): List<TagFilterDef> {
    val out = linkedMapOf<Long, TagFilterDef>()
    selectedIds.forEach { id ->
        nodeById[id]?.filters?.forEach { out.putIfAbsent(it.id, it) }
    }
    return out.values.toList()
}

/**
 * Blocking problem with the current state, or null when submittable: any invalid
 * filter value / malformed range, or (ASSIGN) a required filter left unset.
 */
internal fun TagSelectorPopup.validationError(): String? {
    val filters = activeFilters()
    filters.firstNotNullOfOrNull { filterError(it) }?.let { return it }
    if (mode == Mode.ASSIGN) {
        val missing = filters.filter { it.isRequired && assignBuf(it.id).get().isBlank() }
        if (missing.isNotEmpty()) {
            return "Required: " + missing.joinToString(", ") { it.name.ifBlank { "filter ${it.id}" } }
        }
    }
    return null
}

/** Per-filter validation error for the current buffers, or null. */
internal fun TagSelectorPopup.filterError(filter: TagFilterDef): String? = when (mode) {
    Mode.ASSIGN -> assignBuf(filter.id).get().trim().takeIf { it.isNotEmpty() }?.let { filter.validate(it) }
    Mode.FILTER -> when (filter.type) {
        "int", "float" -> rangeError(filter, rangeMinBuf(filter.id).get(), rangeMaxBuf(filter.id).get())
        else -> exactBuf(filter.id).get().trim().takeIf { it.isNotEmpty() }?.let { filter.validate(it) }
    }
}

/** Validate a min/max numeric range pair (parse + min<=max + per-bound validate). */
internal fun TagSelectorPopup.rangeError(filter: TagFilterDef, minText: String, maxText: String): String? {
    val minStr = minText.trim()
    val maxStr = maxText.trim()
    val min = if (minStr.isEmpty()) null else minStr.toDoubleOrNull()
        ?: return "${filter.name} min must be a number"
    val max = if (maxStr.isEmpty()) null else maxStr.toDoubleOrNull()
        ?: return "${filter.name} max must be a number"
    if (min != null && max != null && min > max) return "${filter.name} min must be <= max"
    min?.let { filter.validate(formatNumber(it))?.let { e -> return e } }
    max?.let { filter.validate(formatNumber(it))?.let { e -> return e } }
    return null
}

internal fun TagSelectorPopup.formatNumber(value: Double): String =
    if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()
