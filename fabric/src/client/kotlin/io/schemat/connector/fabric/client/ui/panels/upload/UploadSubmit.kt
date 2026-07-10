package io.schemat.connector.fabric.client.ui.panels.upload

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.UploadRequest
import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.services.ChatNotice
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.ui.panels.BrowsePanel
import io.schemat.connector.fabric.client.ui.panels.MySchematicsPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.Step
import io.schemat.connector.fabric.client.ui.widgets.ExportSources
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import java.nio.file.Files
import java.nio.file.Path

internal fun UploadWizardPanel.startUpload() {
    if (uploadBusy.get() || exporting) return
    val source = selectedSource ?: return
    statusMessage = null

    when (source.kind) {
        SourceKind.LOCAL_FILE ->
            performUpload(source) { Files.readAllBytes(Path.of(source.id)) }

        SourceKind.WORLDEDIT_CLIPBOARD -> {
            exporting = true
            Bridges.worldEdit.clipboardToBytes { bytes, error ->
                exporting = false
                if (bytes == null) {
                    services.onMainThread {
                        statusMessage = error ?: "Failed to read the WorldEdit clipboard"
                        statusKind = Widgets.StatusKind.DANGER
                    }
                } else {
                    performUpload(source) { bytes }
                }
            }
        }

        SourceKind.PLACEMENT, SourceKind.AREA_SELECTION -> {
            exporting = true
            Bridges.litematica.exportToBytes(source) { bytes, error ->
                exporting = false
                if (bytes == null) {
                    services.onMainThread {
                        statusMessage = error ?: "Failed to export the schematic"
                        statusKind = Widgets.StatusKind.DANGER
                    }
                } else {
                    performUpload(source) { bytes }
                }
            }
        }
    }
}

internal fun UploadWizardPanel.performUpload(source: ExportSource, bytesProvider: suspend () -> ByteArray) {
    val authorId = services.authManager.session?.playerUuid
    if (authorId == null) {
        statusMessage = "Not signed in to schemat.io"
        statusKind = Widgets.StatusKind.DANGER
        return
    }
    val name = nameBuf.get().trim()
    // Rich-text description serialized to sanitizer-safe website HTML.
    val description = descEditor.toHtml()
    val format = ExportSources.formatFor(source)
    val fileName = name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "schematic" } + "." + format
    val tagIds = selectedTagIds.toList()
    val tagFilters = selectedTagFilters
    val coAuthorIds = coAuthorPicker.uuids()
        .filter { it.lowercase().replace("-", "") != authorId.lowercase().replace("-", "") }
    val communityId = selectedCommunity?.id?.takeIf { it.isNotBlank() }

    services.call(
        busy = uploadBusy,
        block = {
            val bytes = bytesProvider()
            val request = UploadRequest(
                name = name,
                description = description,
                authorId = authorId,
                schematicBytes = bytes,
                schematicFileName = fileName,
                previewImagePng = capturedPreviewPng ?: placeholderPng(name),
                format = format,
                isPublic = isPublic,
                tagIds = tagIds,
                tagFilters = tagFilters,
                coAuthorIds = coAuthorIds,
                communityId = communityId,
            )
            uploadWithPermissionSelfHeal(request)
        },
    ) { result ->
        when (result) {
            is ApiResult.Success -> {
                val detail = result.value
                BrowsePanel.invalidate()
                MySchematicsPanel.invalidate()
                PanelManager.close(id)
                val webUrl = webLink(detail)
                ChatNotice.success(
                    "Uploaded \"${detail.name}\" successfully",
                    webUrl,
                    "Open in browser",
                )
            }
            is ApiResult.Failure -> {
                val error = result.error
                when {
                    error is ApiError.Validation -> {
                        step = Step.DETAILS
                        val message = if (error.fieldErrors.isEmpty()) error.message
                        else error.fieldErrors.entries.joinToString("; ") { (field, messages) ->
                            "$field: ${messages.firstOrNull() ?: "invalid"}"
                        }
                        statusMessage = message
                        statusKind = Widgets.StatusKind.DANGER
                    }
                    error is ApiError.Conflict -> {
                        val existingUrl = error.existingUrl
                        if (existingUrl != null) {
                            statusMessage = "This schematic already exists on schemat.io — link posted in chat"
                            statusKind = Widgets.StatusKind.DANGER
                            ChatNotice.success(
                                "This schematic is already uploaded",
                                existingUrl,
                                "Open existing",
                            )
                        } else {
                            statusMessage = "This schematic already exists on schemat.io"
                            statusKind = Widgets.StatusKind.DANGER
                        }
                    }
                    error is ApiError.Forbidden && isMissingUploadPermission(error) -> {
                        statusMessage = "Upload was rejected. Your account may not have upload permission, or the server needs updating."
                        statusKind = Widgets.StatusKind.DANGER
                    }
                    else -> {
                        statusMessage = error.toUserMessage()
                        statusKind = Widgets.StatusKind.DANGER
                    }
                }
            }
        }
    }
}

internal fun UploadWizardPanel.isMissingUploadPermission(error: ApiError.Forbidden): Boolean =
    error.message.contains("permission", ignoreCase = true) ||
        error.message.contains("upload_schematic", ignoreCase = true)
