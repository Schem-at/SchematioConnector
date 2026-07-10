package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.type.ImBoolean
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.QuickShareInfo
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.widgets.ConfirmModal
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.minecraft.client.Minecraft
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone, dockable "Quick Shares" window — the LIST of existing quick shares.
 *
 * Was the Quick Shares tab of the former multi-tab `BrowserPanel`. Distinct from
 * [QuickShareCreatePanel], which CREATES a quick share. Toggled from the toolbar / keybinds
 * via [PanelManager].
 */
object SharesPanel : Panel {

    override val id = "quick-shares"

    private val services: ClientServices get() = SchematioClientMod.instance.services

    private var shares: List<QuickShareInfo> = emptyList()
    private var hasLoaded = false
    private val loadBusy = AtomicBoolean(false)
    private val actionBusy = AtomicBoolean(false)
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.INFO

    /**
     * Drop the state so it re-fetches on next render.
     * Called by [QuickShareCreatePanel] after a successful create.
     */
    fun invalidate() {
        hasLoaded = false
        statusText = null
    }

    override fun render() {
        val open = ImBoolean(true)
        ImGui.setNextWindowSize(720f, 480f, imgui.flag.ImGuiCond.FirstUseEver)
        val expanded = ImGui.begin("Quick Shares###quick-shares", open)
        try {
            if (!open.get()) {
                PanelManager.close(id)
                return
            }
            if (!expanded) return
            renderContent()
        } finally {
            ImGui.end()
        }
    }

    private fun renderContent() {
        // Trigger initial load on first render
        if (!hasLoaded) load()

        // ---- Controls row ----
        val actionsDisabled = loadBusy.get() || actionBusy.get() || services.isOffline()
        if (actionsDisabled) ImGui.beginDisabled()
        if (Widgets.button("New Share", accent = true)) {
            QuickShareCreatePanel.show(null)
        }
        if (actionsDisabled) ImGui.endDisabled()

        ImGui.sameLine()
        if (loadBusy.get()) ImGui.beginDisabled()
        if (Widgets.button("Refresh##qs_refresh")) {
            hasLoaded = false
            statusText = null
            load()
        }
        if (loadBusy.get()) ImGui.endDisabled()

        ImGui.separator()

        // ---- Status / error banner ----
        val sText = statusText
        if (sText != null) {
            Widgets.statusText(sText, statusKind)
            ImGui.sameLine()
            if (statusKind == Widgets.StatusKind.DANGER) {
                if (Widgets.button("Retry##qs_retry")) {
                    statusText = null
                    hasLoaded = false
                    load()
                }
            }
        }

        // ---- Empty / loading states ----
        if (shares.isEmpty()) {
            if (loadBusy.get()) {
                ImGui.textDisabled("Loading...")
            } else if (hasLoaded && statusText == null) {
                ImGui.textDisabled("No quick shares — create one with New Share")
            }
            return
        }

        // Loading indicator while refreshing or doing an action with data already shown
        if ((loadBusy.get() || actionBusy.get()) && shares.isNotEmpty()) {
            ImGui.textDisabled("Working...")
        }

        // ---- Shares table ----
        // Columns: Name | Status | Code | Expiry | Uses | Actions
        ImGuiTheme.withStandardTable("##qs_list", 6) {
            ImGui.tableSetupScrollFreeze(0, 1)
            ImGui.tableSetupColumn("Name")
            ImGui.tableSetupColumn("Status")
            ImGui.tableSetupColumn("Code")
            ImGui.tableSetupColumn("Expiry")
            ImGui.tableSetupColumn("Uses")
            ImGui.tableSetupColumn("Actions")
            ImGui.tableHeadersRow()

            for (share in shares.toList()) {
                ImGui.tableNextRow()

                // Name column
                ImGui.tableSetColumnIndex(0)
                val displayName = share.name?.takeIf { it.isNotBlank() } ?: share.accessCode
                ImGui.textColored(
                    ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
                    ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w,
                    displayName
                )

                // Status column
                ImGui.tableSetColumnIndex(1)
                if (share.isActive) {
                    Widgets.statusText("active", Widgets.StatusKind.SUCCESS)
                } else {
                    Widgets.statusText("inactive", Widgets.StatusKind.WARNING)
                }

                // Code column
                ImGui.tableSetColumnIndex(2)
                ImGui.textDisabled(share.accessCode)

                // Expiry column
                ImGui.tableSetColumnIndex(3)
                ImGui.textDisabled(relativeExpiry(share.expiresAt))

                // Uses column
                ImGui.tableSetColumnIndex(4)
                val uses = share.maxUses?.let { "${share.currentUses}/$it" } ?: "${share.currentUses}"
                ImGui.textDisabled(uses)

                // Actions column
                ImGui.tableSetColumnIndex(5)
                val rowActionsDisabled = actionBusy.get() || loadBusy.get()
                if (rowActionsDisabled) ImGui.beginDisabled()

                if (Widgets.button("Copy##qs_copy_${share.accessCode}")) {
                    copyLink(share)
                }
                ImGui.sameLine()
                if (Widgets.button("Revoke##qs_revoke_${share.accessCode}")) {
                    val shareName = share.name?.takeIf { it.isNotBlank() } ?: share.accessCode
                    ConfirmModal.show(
                        title = "Revoke quick share",
                        message = "Revoke \"$shareName\"? The link stops working immediately.",
                        confirmLabel = "Revoke",
                        danger = true,
                    ) { revoke(share) }
                }

                if (rowActionsDisabled) ImGui.endDisabled()
            }
        }
    }

    private fun load() {
        hasLoaded = true
        statusText = null
        services.call(
            busy = loadBusy,
            block = { services.cached.quickShares() },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    shares = result.value
                    statusText = null
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    private fun revoke(share: QuickShareInfo) {
        if (services.isOffline()) {
            statusText = "Offline — cannot revoke while offline"
            statusKind = Widgets.StatusKind.DANGER
            return
        }
        services.call(
            busy = actionBusy,
            block = { services.cached.revokeQuickShare(share.accessCode) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    statusText = "Quick share revoked"
                    statusKind = Widgets.StatusKind.INFO
                    hasLoaded = false
                    load()
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    private fun copyLink(share: QuickShareInfo) {
        val url = shareUrl(share)
        Minecraft.getInstance().keyboardHandler.setClipboard(url)
        // Don't overwrite an active error banner with a copy-confirmation.
        if (statusKind != Widgets.StatusKind.DANGER) {
            statusText = "Link for \"${share.name ?: share.accessCode}\" copied to clipboard"
            statusKind = Widgets.StatusKind.INFO
        }
    }

    private fun shareUrl(share: QuickShareInfo): String {
        share.webUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val base = services.authManager.apiEndpoint.substringBefore("/api/").trimEnd('/')
        return "$base/share/${share.accessCode}"
    }

    /** "expires in 3d 4h" / "expires in 25m" / "expired" / "never expires" from an ISO-8601 timestamp. */
    private fun relativeExpiry(iso: String?): String {
        if (iso.isNullOrBlank()) return "never expires"
        val instant = parseInstant(iso) ?: return "expires ${iso.take(16)}"
        val remaining = Duration.between(Instant.now(), instant)
        if (remaining.isNegative || remaining.isZero) return "expired"
        val days = remaining.toDays()
        val hours = remaining.toHours() % 24
        val minutes = remaining.toMinutes() % 60
        return "expires in " + when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes.coerceAtLeast(1)}m"
        }
    }

    private fun parseInstant(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (e: Exception) {
        try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (e2: Exception) {
            null
        }
    }
}
