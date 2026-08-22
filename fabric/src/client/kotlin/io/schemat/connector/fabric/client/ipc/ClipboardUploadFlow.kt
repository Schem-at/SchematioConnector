package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.services.ChatNotice
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Upload clipboard" flow (spec §Client): UPLOAD_CLIPBOARD -> DRAFT_CREATED ->
 * re-fetch the draft with the USER's OWN auth -> ownership check -> wizard in
 * complete-draft mode. The server-supplied draft id is never trusted: a draft not
 * owned by the current user produces an error and nothing else (spec invariant 4).
 */
object ClipboardUploadFlow {

    private val services: ClientServices get() = SchematioClientMod.instance.services
    private val busy = AtomicBoolean(false)

    fun isBusy(): Boolean = busy.get()

    /**
     * Clears the busy latch. Called on JOIN/DISCONNECT so a mid-flight upload
     * (player disconnects before onStatus/onDraft fires) doesn't permanently
     * disable the "Upload Clipboard" button for the rest of the client session.
     * Idempotent / safe to call when no upload is in flight.
     */
    fun reset() {
        busy.set(false)
    }

    fun start() {
        if (!busy.compareAndSet(false, true)) return
        val requestId = ServerIpc.sendUploadClipboard(
            onStatus = { state, detail ->
                if (state.isTerminal) {
                    busy.set(false)
                    ChatNotice.error(detail.ifBlank { "Clipboard upload failed (${state.name})" })
                }
            },
            onDraft = { draftId -> fetchAndOpen(draftId) },
        )
        if (requestId == null) {
            busy.set(false)
            ChatNotice.error("No verified Schematio server connection")
        } else {
            ChatNotice.info("Uploading your server-side clipboard as a draft…")
        }
    }

    private fun fetchAndOpen(draftId: String) {
        services.call(
            block = { services.cached.schematic(draftId) },
        ) { result ->
            busy.set(false)
            when (result) {
                is ApiResult.Success -> {
                    val detail = result.value
                    val me = services.authManager.session?.playerUuid
                    if (!isDraftOwnedBy(detail.authors.map { it.uuid }, me)) {
                        ChatNotice.error("The server returned a draft that isn't yours — ignoring it")
                    } else {
                        UploadWizardPanel.openCompleteDraft(detail)
                    }
                }
                is ApiResult.Failure ->
                    ChatNotice.error("Could not fetch the created draft: ${result.error.toUserMessage()}")
            }
        }
    }
}
