package io.schemat.connector.fabric.client.ui.panels.upload

import imgui.ImGui
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.Step
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.ui.widgets.TagSelectorPopup
import io.schemat.connector.fabric.client.ui.widgets.Widgets

internal fun UploadWizardPanel.renderDetailsStep() {
    // Source affordance
    val srcLabel = "Source: " + (selectedSource?.let { ExportSources.label(it) } ?: "none selected")
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
        srcLabel,
    )
    ImGui.sameLine()
    if (Widgets.button("Change...")) {
        step = Step.SOURCE
        statusMessage = null
    }
    ImGui.separator()
    ImGui.spacing()

    // Kick off communities/tags load if not done yet
    if (!communitiesLoaded && !loadBusy.get()) loadCommunitiesAndTags()

    val avail = ImGui.getContentRegionAvailY()
    ImGui.beginChild("##details-form", 0f, avail - 50f, false)

    // Name
    ImGui.setNextItemWidth(-1f)
    Widgets.textField("##name", nameBuf, hint = "Schematic name (required)")
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
        "Name",
    )
    ImGui.spacing()

    // Description (rich text: markup source + live preview, submitted as HTML)
    descEditor.render(editorHeight = 80f, previewHeight = 80f)
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
        "Description (required)",
    )
    ImGui.spacing()

    // Visibility toggle
    if (ImGui.checkbox("Public", visibilityBuf)) {
        isPublic = visibilityBuf.get()
    }
    ImGui.spacing()

    // Community selector
    val communityNames = (listOf("None") + communities.map { it.name }).toTypedArray()
    ImGui.setNextItemWidth(220f)
    ImGui.combo("Community", communityIndexBuf, communityNames)
    // Keep index in range if community list shrinks
    if (communityIndexBuf.get() > communities.size) communityIndexBuf.set(0)
    ImGui.spacing()

    // Tags (via TagSelectorPopup in ASSIGN mode — collects literal tag-filter values).
    val tagSummary = if (selectedTagIds.isEmpty()) "None selected" else "${selectedTagIds.size} tag(s) selected"
    ImGui.text("Tags: $tagSummary")
    ImGui.sameLine()
    if (Widgets.button("Select Tags...")) {
        TagSelectorPopup.show(
            preselectedTagIds = selectedTagIds,
            mode = TagSelectorPopup.Mode.ASSIGN,
            preselectedFilterValues = selectedTagFilters,
        ) { newIds, filterValues, _ ->
            selectedTagIds = newIds
            selectedTagFilters = filterValues
        }
    }
    ImGui.spacing()

    // Collaborators: player-name lookup -> UUID chips (PlayerListPicker).
    ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
        ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
        "Collaborators (optional)",
    )
    coAuthorPicker.render()
    ImGui.spacing()

    // Captured preview thumbnail (composed on the Confirm step); show it here too
    // once it exists so the chosen image is visible while still editing details.
    if (capturedPreviewPng != null) {
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Preview",
        )
        renderCapturedPreviewImage()
        ImGui.spacing()
    }

    ImGui.endChild()

    // Status / error message
    renderStatus()

    renderNavButtons(
        backStep = Step.SOURCE,
        nextLabel = "Next >",
        nextEnabled = true,
        onNext = { validateDetailsAndAdvance() },
    )
}

internal fun UploadWizardPanel.validateDetailsAndAdvance() {
    val problems = mutableListOf<String>()
    if (nameBuf.get().isBlank()) problems.add("Name is required")
    if (descEditor.isEmpty()) problems.add("Description is required")
    if (services.authManager.session?.playerUuid == null) problems.add("Not signed in to schemat.io")
    if (problems.isNotEmpty()) {
        statusMessage = problems.joinToString("; ")
        statusKind = Widgets.StatusKind.DANGER
        return
    }
    statusMessage = null
    step = Step.CONFIRM
}

/**
 * Load global tag tree + player communities in one call (mirrors vanilla [loadCommunities]).
 */
internal fun UploadWizardPanel.loadCommunitiesAndTags() {
    services.call(
        busy = loadBusy,
        block = {
            val global = (services.cached.globalTags() as? ApiResult.Success)?.value?.value ?: emptyList()
            when (val me = services.cached.me()) {
                is ApiResult.Failure ->
                    if (global.isEmpty()) ApiResult.Failure(me.error)
                    else ApiResult.Success(global to null)
                is ApiResult.Success -> ApiResult.Success(global to me.value.value.communities)
            }
        },
    ) { result ->
        when (result) {
            is ApiResult.Success -> {
                val (global, loadedCommunities) = result.value
                globalTagNodes = global
                communitiesLoaded = true
                if (loadedCommunities != null) {
                    communities = loadedCommunities
                    // Keep communityIndex in range after list update
                    if (communityIndexBuf.get() > communities.size) communityIndexBuf.set(0)
                } else {
                    statusMessage = "Communities unavailable"
                    statusKind = Widgets.StatusKind.WARNING
                }
            }
            is ApiResult.Failure -> {
                communitiesLoaded = true
                statusMessage = "Communities unavailable: ${result.error.toUserMessage()}"
                statusKind = Widgets.StatusKind.WARNING
            }
        }
    }
}
