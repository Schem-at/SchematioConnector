package io.schemat.connector.fabric.client.ui.panels.upload

import imgui.ImGui
import imgui.flag.ImGuiSelectableFlags
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.Step
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.ui.widgets.Widgets

internal fun UploadWizardPanel.renderSourceStep() {
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
        "Choose what to upload:",
    )
    ImGui.sameLine()
    if (Widgets.button("Refresh")) sources = ExportSources.collect()
    ImGui.spacing()

    if (sources.isEmpty()) {
        Widgets.statusText(
            "No sources found. Make a Litematica placement or selection, copy a WorldEdit " +
                "selection, or put .litematic/.schem files in ${ExportSources.localFilesDirectory()}.",
            Widgets.StatusKind.WARNING,
        )
    } else {
        ImGui.beginChild("##source-list", 0f, -50f, true)
        sourceSection(
            "Litematica",
            sources.filter { it.kind == SourceKind.PLACEMENT || it.kind == SourceKind.AREA_SELECTION },
        )
        sourceSection("WorldEdit", sources.filter { it.kind == SourceKind.WORLDEDIT_CLIPBOARD })
        sourceSection("Local files", sources.filter { it.kind == SourceKind.LOCAL_FILE })
        ImGui.endChild()
    }

    renderNavButtons(
        backStep = null,
        nextLabel = "Next >",
        nextEnabled = selectedSource != null,
        onNext = {
            // Refresh sources on "Next" in case disk / selections changed.
            sources = ExportSources.collect()
            step = Step.DETAILS
            statusMessage = null
            if (!communitiesLoaded && !loadBusy.get()) loadCommunitiesAndTags()
        },
    )
}

/** A category section in the source picker: heading + its rows. Hidden when empty. */
internal fun UploadWizardPanel.sourceSection(title: String, items: List<ExportSource>) {
    if (items.isEmpty()) return
    sectionHeading("$title (${items.size})")
    for (source in items) sourceRow(source)
    ImGui.spacing()
}

/**
 * One source entry: a full-width selectable (row highlight + click), with the source
 * name drawn on the left and a muted "kind · format" badge right-aligned, painted over
 * the selectable via the draw list so the row reads cleanly.
 */
internal fun UploadWizardPanel.sourceRow(source: ExportSource) {
    val selected = selectedSource?.id == source.id
    val originX = ImGui.getCursorScreenPosX()
    val originY = ImGui.getCursorScreenPosY()
    val rowW = ImGui.getContentRegionAvailX()

    if (ImGui.selectable("##src-${source.id}", selected, ImGuiSelectableFlags.None, 0f, SOURCE_ROW_H)) {
        selectedSource = source
    }

    val nameU32 = ImGui.colorConvertFloat4ToU32(
        ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
        ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w,
    )
    val badgeU32 = ImGui.colorConvertFloat4ToU32(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
    )
    val badge = "${kindTag(source.kind)} · ${ExportSources.formatFor(source)}"
    val badgeW = ImGui.calcTextSizeX(badge)
    val textY = originY + (SOURCE_ROW_H - ImGui.getTextLineHeight()) * 0.5f
    val dl = ImGui.getWindowDrawList()
    dl.addText(originX + 6f, textY, nameU32, source.label)
    dl.addText(originX + rowW - badgeW - 6f, textY, badgeU32, badge)
}

internal fun UploadWizardPanel.kindTag(kind: SourceKind): String = when (kind) {
    SourceKind.PLACEMENT -> "placement"
    SourceKind.AREA_SELECTION -> "selection"
    SourceKind.WORLDEDIT_CLIPBOARD -> "clipboard"
    SourceKind.LOCAL_FILE -> "file"
}
