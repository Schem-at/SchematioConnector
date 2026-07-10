package io.schemat.connector.fabric.client.ui.widgets.tagselector

import imgui.ImGui
import imgui.flag.ImGuiStyleVar
import imgui.type.ImString
import io.schemat.connector.core.modapi.dto.TagFilterDef
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup.Mode
import io.schemat.connector.fabric.client.ui.widgets.Widgets

/**
 * Filter-value input rows for [TagSelectorPopup]: the inline per-tag filter panel
 * (ASSIGN literal inputs / FILTER constraint inputs, range fields and value cyclers).
 */

/**
 * One filter row inside the selected tag's inline panel: a secondary-toned label line
 * ("name (unit) *") followed by the mode-appropriate value control, then any live error.
 */
internal fun TagSelectorPopup.renderFilterRow(filter: TagFilterDef) {
    val label = buildString {
        append(filter.name.ifBlank { "filter ${filter.id}" })
        filter.unit?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
    }
    val required = mode == Mode.ASSIGN && filter.isRequired
    // Label line; required filters get an accent-coloured trailing star.
    ImGui.textColored(
        ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
        ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w, label,
    )
    if (required) {
        ImGui.sameLine(0f, 4f)
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y,
            ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w, "*",
        )
    }

    when (mode) {
        Mode.ASSIGN -> renderAssignInput(filter)
        Mode.FILTER -> renderFilterInput(filter)
    }
}

internal fun TagSelectorPopup.renderAssignInput(filter: TagFilterDef) {
    val buf = assignBuf(filter.id)
    when (filter.type) {
        "enum" -> renderCycler(filter, buf, listOf("") + filter.enumValues)
        "bool" -> renderCycler(filter, buf, listOf("", "true", "false"))
        else -> {
            ImGui.setNextItemWidth(200f)
            Widgets.textField("##fv-${filter.id}", buf, hint = assignHint(filter))
        }
    }
    // Live validation error (also reported by validationError(); shown inline here).
    val v = buf.get().trim()
    if (v.isNotEmpty()) {
        filter.validate(v)?.let { Widgets.statusText(it, Widgets.StatusKind.DANGER) }
    }
}

internal fun TagSelectorPopup.renderFilterInput(filter: TagFilterDef) {
    when (filter.type) {
        "int", "float" -> renderRangeFields(filter)
        "enum" -> renderCycler(filter, exactBuf(filter.id), listOf("") + filter.enumValues)
        "bool" -> renderCycler(filter, exactBuf(filter.id), listOf("", "true", "false"))
        else -> {
            val buf = exactBuf(filter.id)
            ImGui.setNextItemWidth(200f)
            Widgets.textField("##fx-${filter.id}", buf, hint = "any value")
        }
    }
}

/** A tidy "Min […]  Max […]" range pair with aligned mini-labels and a live error. */
internal fun TagSelectorPopup.renderRangeFields(filter: TagFilterDef) {
    val minBuf = rangeMinBuf(filter.id)
    val maxBuf = rangeMaxBuf(filter.id)
    val fieldW = 70f
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w, "Min",
    )
    ImGui.sameLine()
    ImGui.setNextItemWidth(fieldW)
    Widgets.textField("##min-${filter.id}", minBuf, hint = "any")
    ImGui.sameLine(0f, 12f)
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w, "Max",
    )
    ImGui.sameLine()
    ImGui.setNextItemWidth(fieldW)
    Widgets.textField("##max-${filter.id}", maxBuf, hint = "any")
    rangeError(filter, minBuf.get(), maxBuf.get())
        ?.let { Widgets.statusText(it, Widgets.StatusKind.DANGER) }
}

/**
 * A themed value cycler styled as the old [FlatButton.secondary] stepper: a labelled
 * value box flanked by small drawn `<`/`>` chevron buttons. Clicking the box (or the
 * chevrons) steps through [options]; an empty string reads "Any" (FILTER) / "Unset"
 * (ASSIGN). Stores the chosen option in [buf].
 */
internal fun TagSelectorPopup.renderCycler(filter: TagFilterDef, buf: ImString, options: List<String>) {
    val current = buf.get()
    val display = when {
        current.isBlank() -> if (mode == Mode.FILTER) "Any" else "Unset"
        else -> current
    }
    val idx = options.indexOf(current).let { if (it < 0) 0 else it }
    fun step(delta: Int) {
        val next = options[((idx + delta) % options.size + options.size) % options.size]
        buf.set(next)
    }

    val dl = ImGui.getWindowDrawList()
    val origin = ImGui.getCursorScreenPos()
    val h = ImGui.getFrameHeight()
    val chevW = h
    val boxW = 170f

    // Prev chevron.
    ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, 0f)
    if (ImGui.button("##cyc-prev-${filter.id}", chevW, h)) step(-1)
    val prevMin = origin
    drawChevron(dl, prevMin.x, prevMin.y, chevW, h, left = true)

    // Value box (also steps forward on click, like the old single-button cycler).
    ImGui.sameLine(0f, 4f)
    val valuePos = ImGui.getCursorScreenPos()
    if (ImGui.button("##cyc-val-${filter.id}", boxW, h)) step(1)
    val tw = ImGui.calcTextSize(display).x
    val lh = ImGui.getTextLineHeight()
    dl.addText(
        valuePos.x + (boxW - tw) / 2f,
        valuePos.y + (h - lh) / 2f,
        u32(ImGuiColors.TEXT_PRIMARY),
        display,
    )

    // Next chevron.
    ImGui.sameLine(0f, 4f)
    val nextPos = ImGui.getCursorScreenPos()
    if (ImGui.button("##cyc-next-${filter.id}", chevW, h)) step(1)
    drawChevron(dl, nextPos.x, nextPos.y, chevW, h, left = false)
    ImGui.popStyleVar()
}

internal fun TagSelectorPopup.assignHint(filter: TagFilterDef): String = when (filter.type) {
    "int" -> "integer"
    "float" -> "number"
    else -> "value"
}
