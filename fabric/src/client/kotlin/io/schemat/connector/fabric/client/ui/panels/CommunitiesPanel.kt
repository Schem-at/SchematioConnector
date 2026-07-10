package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.type.ImBoolean
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.InvitationInfo
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone, dockable "Communities" window — pending invitations + the caller's communities.
 *
 * Was the Communities tab of the former multi-tab `BrowserPanel`. Rows drill into
 * [CommunityDetailPanel] (unchanged). Toggled from the toolbar / keybinds via [PanelManager].
 */
object CommunitiesPanel : Panel {

    override val id = "communities"

    private val services: ClientServices get() = SchematioClientMod.instance.services

    private var invitations: List<InvitationInfo> = emptyList()
    private var communities: List<CommunitySummary> = emptyList()
    private var hasLoaded = false
    private var isStale = false
    private val loadBusy = AtomicBoolean(false)
    private val actionBusy = AtomicBoolean(false)
    /** Non-null when the load or an action failed; cleared on next load attempt. */
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.DANGER

    /**
     * Drop the state so it re-fetches on next render.
     * Called by [CommunityDetailPanel] after leave so the list refreshes immediately.
     */
    fun invalidate() {
        hasLoaded = false
        isStale = false
        statusText = null
    }

    override fun render() {
        val open = ImBoolean(true)
        ImGui.setNextWindowSize(560f, 520f, imgui.flag.ImGuiCond.FirstUseEver)
        val expanded = ImGui.begin("Communities###communities", open)
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

        // ---- Pending invitations section ----
        val visibleInvites = invitations.take(3)
        if (visibleInvites.isNotEmpty()) {
            ImGui.textColored(
                ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
                "Pending Invitations"
            )
            ImGui.separator()
            ImGui.spacing()

            val actionDisabled = actionBusy.get() || loadBusy.get() || services.isOffline()
            for (invitation in visibleInvites) {
                val invitedBy = invitation.invitedBy?.takeIf { it.isNotBlank() }
                ImGui.textColored(
                    ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
                    ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w,
                    invitation.communityName
                )
                if (invitedBy != null) {
                    ImGui.sameLine()
                    ImGui.textDisabled("— invited by $invitedBy")
                }
                ImGui.sameLine()
                if (actionDisabled) ImGui.beginDisabled()
                if (Widgets.button("Accept##inv_${invitation.id}", accent = true)) {
                    accept(invitation)
                }
                ImGui.sameLine()
                if (Widgets.button("Decline##inv_${invitation.id}")) {
                    decline(invitation)
                }
                if (actionDisabled) ImGui.endDisabled()
            }
            if (invitations.size > 3) {
                ImGui.textDisabled("+${invitations.size - 3} more pending invitation(s) — see the website")
            }
            ImGui.spacing()
            ImGui.spacing()
        }

        // ---- Communities list section ----
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            "Your Communities"
        )
        ImGui.separator()
        ImGui.spacing()

        // Stale / status banner
        if (isStale) {
            Widgets.statusText("Offline — showing cached results", Widgets.StatusKind.WARNING)
        }
        val cStatusText = statusText
        if (cStatusText != null) {
            Widgets.statusText(cStatusText, statusKind)
            ImGui.sameLine()
            if (Widgets.button("Retry##comm_retry")) {
                statusText = null
                hasLoaded = false
                load()
            }
            ImGui.spacing()
        }

        when {
            loadBusy.get() && communities.isEmpty() -> {
                ImGui.textDisabled("Loading...")
            }
            hasLoaded && communities.isEmpty() && statusText == null -> {
                ImGui.textDisabled("You're not in any communities yet — join one on the website")
            }
            communities.isNotEmpty() -> {
                renderCommunityCards()
            }
        }
    }

    /**
     * Renders the community list as styled rows — name + member count + roles, each
     * row clickable to open [CommunityDetailPanel].
     */
    private fun renderCommunityCards() {
        val availW = ImGui.getContentRegionAvail().x
        val availH = ImGui.getContentRegionAvail().y
        val scrollH = (availH - 4f).coerceAtLeast(80f)

        if (!ImGui.beginChild("##comm_scroll", availW, scrollH, false)) {
            ImGui.endChild()
            return
        }

        val drawList = ImGui.getWindowDrawList()
        val rowH = 44f
        val rowGap = 4f

        for (community in communities.toList()) {
            val cx = ImGui.getCursorScreenPos().x
            val cy = ImGui.getCursorScreenPos().y

            val clicked = ImGui.invisibleButton("##comm_row_${community.id}", availW - 4f, rowH)
            val hovered = ImGui.isItemHovered()

            val bgColor = if (hovered) {
                ImGui.colorConvertFloat4ToU32(
                    ImGuiColors.SURFACE_HOVER.x, ImGuiColors.SURFACE_HOVER.y,
                    ImGuiColors.SURFACE_HOVER.z, ImGuiColors.SURFACE_HOVER.w
                )
            } else {
                ImGui.colorConvertFloat4ToU32(
                    ImGuiColors.SURFACE.x, ImGuiColors.SURFACE.y,
                    ImGuiColors.SURFACE.z, ImGuiColors.SURFACE.w
                )
            }
            drawList.addRectFilled(cx, cy, cx + availW - 4f, cy + rowH, bgColor, 3f)

            val borderColor = if (hovered) {
                ImGui.colorConvertFloat4ToU32(
                    ImGuiColors.BORDER_ACCENT.x, ImGuiColors.BORDER_ACCENT.y,
                    ImGuiColors.BORDER_ACCENT.z, ImGuiColors.BORDER_ACCENT.w
                )
            } else {
                ImGui.colorConvertFloat4ToU32(
                    ImGuiColors.BORDER_SUBTLE.x, ImGuiColors.BORDER_SUBTLE.y,
                    ImGuiColors.BORDER_SUBTLE.z, ImGuiColors.BORDER_SUBTLE.w
                )
            }
            drawList.addRect(cx, cy, cx + availW - 4f, cy + rowH, borderColor, 3f)

            val lineH = ImGui.getTextLineHeight()
            val textTop = cy + (rowH - lineH * 2f - 3f) / 2f

            val nameColor = ImGui.colorConvertFloat4ToU32(
                ImGuiColors.TEXT_PRIMARY.x, ImGuiColors.TEXT_PRIMARY.y,
                ImGuiColors.TEXT_PRIMARY.z, ImGuiColors.TEXT_PRIMARY.w
            )
            drawList.addText(cx + 10f, textTop, nameColor, community.name)

            val memberStr = community.memberCount?.let { "$it member${if (it == 1) "" else "s"}" }
                ?: if (community.isPublic) "public" else "private"
            val rolesStr = community.roles.take(3).joinToString(", ") { it.name }.ifEmpty { "" }
            val subtitleStr = if (rolesStr.isNotEmpty()) "$memberStr · $rolesStr" else memberStr
            val mutedColor = ImGui.colorConvertFloat4ToU32(
                ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
                ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w
            )
            drawList.addText(cx + 10f, textTop + lineH + 3f, mutedColor, subtitleStr)

            if (clicked) {
                CommunityDetailPanel.show(community)
            }

            ImGui.dummy(0f, rowGap)
        }

        if (loadBusy.get() && communities.isNotEmpty()) {
            ImGui.textDisabled("Refreshing...")
        }

        ImGui.endChild()
    }

    private fun load() {
        hasLoaded = true
        statusText = null
        services.call(
            busy = loadBusy,
            block = {
                val invitationsResult = services.cached.invitations()
                val communitiesResult = services.cached.communities()
                when {
                    communitiesResult is ApiResult.Failure -> communitiesResult
                    invitationsResult is ApiResult.Failure -> invitationsResult
                    else -> {
                        val inv = (invitationsResult as ApiResult.Success).value
                        val com = (communitiesResult as ApiResult.Success).value
                        ApiResult.Success(Triple(inv.value, com.value, inv.isStale || com.isStale))
                    }
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    invitations = result.value.first
                    communities = result.value.second
                    isStale = result.value.third
                    statusText = null
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }

    private fun accept(invitation: InvitationInfo) {
        services.call(
            busy = actionBusy,
            block = {
                val result = services.cached.acceptInvitation(invitation.id)
                if (result is ApiResult.Success) services.refreshMe()
                result
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    statusText = "Joined ${invitation.communityName}"
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

    private fun decline(invitation: InvitationInfo) {
        services.call(
            busy = actionBusy,
            block = {
                val result = services.cached.declineInvitation(invitation.id)
                if (result is ApiResult.Success) services.refreshMe()
                result
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    statusText = "Invitation declined"
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
}
