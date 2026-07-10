package io.schemat.connector.fabric.client.ui.panels.upload

import imgui.ImGui
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.Step
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.ui.widgets.Widgets

internal fun UploadWizardPanel.renderConfirmStep() {
    val source = selectedSource
    val busy = uploadBusy.get() || exporting

    ImGui.beginChild("##confirm-card", 0f, -50f, true)

    sectionHeading("Summary")
    summaryRow("Source", source?.let { ExportSources.label(it) } ?: "none")
    summaryRow("Name", nameBuf.get().trim().ifBlank { "(none)" })
    val descPlain = descEditor.plainText().replace('\n', ' ').trim()
    summaryRow(
        "Description",
        when {
            descPlain.isEmpty() -> "(none)"
            descPlain.length > 120 -> descPlain.take(120) + "..."
            else -> descPlain
        },
    )
    summaryRow("Visibility", if (isPublic) "Public" else "Private")
    summaryRow("Community", selectedCommunity?.name ?: "None")
    summaryRow("Tags", if (selectedTagIds.isEmpty()) "None" else "${selectedTagIds.size} tag(s)")
    summaryRow("Collaborators", coAuthorPicker.uuids().size.let { if (it == 0) "None" else "$it" })
    summaryRow("Format", ExportSources.formatFor(source))

    ImGui.spacing()
    ImGui.spacing()
    sectionHeading("Preview")
    if (capturedPreviewPng != null) {
        renderCapturedPreviewImage()
        Widgets.statusText("Captured - it will be uploaded.", Widgets.StatusKind.SUCCESS)
    } else {
        Widgets.statusText(
            "No preview captured - a placeholder will be used unless you compose one.",
            Widgets.StatusKind.INFO,
        )
    }
    ImGui.spacing()
    val previewLabel = if (capturedPreviewPng != null) "Re-compose preview" else "Generate preview"
    if (Widgets.button(previewLabel) && !busy) {
        generatePreview()
    }

    ImGui.endChild()

    renderStatus()

    if (busy) {
        Widgets.statusText("Uploading...", Widgets.StatusKind.INFO)
    }

    renderNavButtons(
        backStep = Step.DETAILS,
        nextLabel = "Upload",
        nextEnabled = !busy,
        nextAccent = true,
        onNext = { startUpload() },
    )
}

/** A section heading (secondary-colored label + separator) for the confirm card. */
internal fun UploadWizardPanel.sectionHeading(text: String) {
    ImGui.textColored(
        ImGuiColors.TEXT_SECONDARY.x, ImGuiColors.TEXT_SECONDARY.y,
        ImGuiColors.TEXT_SECONDARY.z, ImGuiColors.TEXT_SECONDARY.w, text,
    )
    ImGui.separator()
    ImGui.spacing()
}

/** An aligned "label  value" row: muted label in a fixed column, primary wrapped value. */
internal fun UploadWizardPanel.summaryRow(label: String, value: String) {
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w, label,
    )
    ImGui.sameLine(SUMMARY_LABEL_COL)
    ImGui.textWrapped(value)
}

/**
 * Web URL for the uploaded schematic — prefers server-provided `web_url`,
 * falls back to short_id path (mirrors vanilla [webLink]).
 */
internal fun UploadWizardPanel.webLink(detail: SchematicDetail): String {
    detail.webUrl?.takeIf { it.isNotBlank() }?.let { return it }
    val base = services.authManager.apiEndpoint.substringBefore("/api/").trimEnd('/')
    val key = detail.shortId ?: detail.id
    return "$base/schematics/$key"
}
