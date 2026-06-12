package io.schemat.connector.fabric.client.ui.tabs

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.InvitationInfo
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.CommunityDetailScreen
import io.schemat.connector.fabric.client.ui.HomeScreen
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.disabledWhenOffline
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Communities tab: a pending-invitations section at the top (accept/decline inline)
 * and the caller's communities below as clickable rows (name, member count, the
 * caller's role badges) that open [CommunityDetailScreen].
 */
class CommunitiesTab : HomeScreen.TabContent {

    companion object {
        private const val INVITE_ROW_HEIGHT = 24
        private const val MAX_VISIBLE_INVITES = 3
        private const val ROW_HEIGHT = 30
    }

    private val services get() = SchematioClientMod.instance.services

    // ---- state (survives re-init) ----
    private var invitations: List<InvitationInfo> = emptyList()
    private var communities: List<CommunitySummary> = emptyList()
    private var hasLoaded = false
    private var isStale = false
    private val loadBusy = AtomicBoolean(false)
    private val actionBusy = AtomicBoolean(false)

    private val banner = NoticeBanner()
    private var screen: HomeScreen? = null
    private var listArea = intArrayOf(0, 0, 0, 0)
    private var inviteRows: List<Pair<InvitationInfo, Int>> = emptyList() // invitation → row y
    private var extraInvitesNote: String? = null
    private var invitesNoteY = 0

    override fun init(screen: HomeScreen, width: Int, height: Int) {
        this.screen = screen
        val pad = HomeScreen.PADDING
        var y = screen.contentTop

        screen.register(
            FlatButton.secondary(width - pad - 70, y, 70, Component.literal("Refresh")) {
                hasLoaded = false
                load()
            }
        )
        y += Theme.BTN_H + Theme.MD

        screen.register(banner.layout(pad, y, width - pad * 2))
        y += NoticeBanner.HEIGHT + Theme.MD

        // Pending invitations: one tinted banner row each (text drawn in render,
        // accept/decline buttons registered here).
        extraInvitesNote = null
        val visibleInvites = invitations.take(MAX_VISIBLE_INVITES)
        inviteRows = visibleInvites.map { invitation ->
            val rowY = y
            val buttonY = rowY + (INVITE_ROW_HEIGHT - Theme.BTN_H) / 2
            screen.register(
                FlatButton.success(width - pad - 130 - Theme.XS, buttonY, 62, Component.literal("Accept")) {
                    accept(invitation)
                }.disabledWhenOffline(services)
            )
            screen.register(
                FlatButton.ghost(width - pad - 64 - Theme.XS, buttonY, 60, Component.literal("Decline")) {
                    decline(invitation)
                }.disabledWhenOffline(services)
            )
            y += INVITE_ROW_HEIGHT + Theme.XS
            invitation to rowY
        }
        if (invitations.size > MAX_VISIBLE_INVITES) {
            extraInvitesNote = "+${invitations.size - MAX_VISIBLE_INVITES} more pending invitations"
            invitesNoteY = y
            y += 12
        }
        if (inviteRows.isNotEmpty()) y += Theme.MD

        val listHeight = (height - y - pad).coerceAtLeast(40)
        listArea = intArrayOf(pad, y, width - pad * 2, listHeight)
        screen.register(CommunityListWidget(pad, y, width - pad * 2, listHeight))

        if (!hasLoaded) load()
    }

    /** External hook: drop state so the next init refetches (e.g. after leaving a community). */
    fun invalidate() {
        hasLoaded = false
    }

    private fun load() {
        hasLoaded = true
        banner.clear()
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
                    if (isStale) {
                        banner.show(NoticeBanner.Kind.STALE, "Offline - showing cached results") {
                            hasLoaded = false
                            load()
                        }
                    }
                    // Invitation buttons depend on the data - rebuild widgets if we're still showing.
                    val host = screen
                    if (host != null && Minecraft.getInstance().screen === host) host.reinit()
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage()) {
                    banner.clear()
                    hasLoaded = false
                    load()
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
                    banner.show(NoticeBanner.Kind.INFO, "Joined ${invitation.communityName}")
                    hasLoaded = false
                    load()
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
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
                    banner.show(NoticeBanner.Kind.INFO, "Invitation declined")
                    hasLoaded = false
                    load()
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    private fun openDetail(community: CommunitySummary) {
        val host = screen ?: return
        Minecraft.getInstance().setScreen(CommunityDetailScreen(host, community.slug, community))
    }

    /** Parse a `#RRGGBB` (or `RRGGBB`) hex color into an opaque ARGB int. */
    private fun parseColor(hex: String?, fallback: Int): Int {
        val cleaned = hex?.removePrefix("#")?.takeIf { it.length == 6 } ?: return fallback
        return cleaned.toIntOrNull(16)?.let { 0xFF_000000.toInt() or it } ?: fallback
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val font = Minecraft.getInstance().font
        banner.render(context, font)

        val pad = HomeScreen.PADDING
        for ((invitation, rowY) in inviteRows) {
            // Tinted invitation banner behind the row (buttons sit on top).
            val rowW = listArea[2]
            context.fill(pad, rowY, pad + rowW, rowY + INVITE_ROW_HEIGHT, Theme.withAlpha(Theme.INFO, 0x18))
            Theme.stroke(context, pad, rowY, rowW, INVITE_ROW_HEIGHT, Theme.withAlpha(Theme.INFO, 0x44))
            context.fill(pad, rowY, pad + 2, rowY + INVITE_ROW_HEIGHT, Theme.INFO)

            val invitedBy = invitation.invitedBy?.takeIf { it.isNotBlank() }?.let { " - invited by $it" } ?: ""
            val text = "Invitation: ${invitation.communityName}$invitedBy"
            val maxWidth = rowW - 140
            Theme.label(
                context, font, font.plainSubstrByWidth(text, maxWidth),
                pad + Theme.MD, rowY + (INVITE_ROW_HEIGHT - font.lineHeight + 1) / 2, Theme.INFO,
            )
        }
        extraInvitesNote?.let {
            Theme.muted(context, font, it, pad + Theme.MD, invitesNoteY)
        }

        val centerX = listArea[0] + listArea[2] / 2
        if (communities.isEmpty()) {
            if (loadBusy.get() || actionBusy.get()) {
                LoadingSpinner.render(context, font, centerX, listArea[1] + listArea[3] / 2 - 4)
            } else if (hasLoaded && !banner.isVisible) {
                Theme.emptyState(
                    context, font, "You're not in any communities yet - join one on the website",
                    listArea[0], listArea[1], listArea[2], listArea[3],
                )
            }
        } else if (loadBusy.get() || actionBusy.get()) {
            LoadingSpinner.render(context, font, centerX, listArea[1] + listArea[3] - 12, "Working")
        }
    }

    /** Scrollable community rows: name + member count line, the caller's role chips. */
    private inner class CommunityListWidget(x: Int, y: Int, width: Int, height: Int) :
        AbstractWidget(x, y, width, height, Component.literal("Communities")) {

        private var scrollOffset = 0.0
        private val contentHeight: Int get() = communities.size * ROW_HEIGHT
        private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            if (!isMouseOver(mouseX, mouseY)) return false
            scrollOffset = (scrollOffset - verticalAmount * 24.0).coerceIn(0.0, maxScroll)
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
            val index = ((click.y() - y + scrollOffset) / ROW_HEIGHT).toInt()
            communities.getOrNull(index)?.let { openDetail(it) }
        }

        // 26.x renamed the widget render hook: renderWidget -> extractWidgetRenderState.
        //? if >=26.1 {
        /*override fun extractWidgetRenderState(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        *///?} else {
        override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        //?}
            val font = Minecraft.getInstance().font
            Theme.card(context, x, y, width, height)
            context.enableScissor(x, y, x + width, y + height)
            communities.forEachIndexed { index, community ->
                val rowY = y + index * ROW_HEIGHT - scrollOffset.toInt()
                if (rowY + ROW_HEIGHT < y || rowY > y + height) return@forEachIndexed
                val hovered = isMouseOver(mouseX.toDouble(), mouseY.toDouble()) &&
                    mouseY in rowY until (rowY + ROW_HEIGHT)
                if (hovered) context.fill(x, rowY, x + width, rowY + ROW_HEIGHT, Theme.SURFACE_HOVER)
                if (index > 0) Theme.divider(context, x + Theme.MD, rowY, width - Theme.MD * 2)

                Theme.value(
                    context, font,
                    font.plainSubstrByWidth(community.name, width / 2 - Theme.LG),
                    x + Theme.MD, rowY + Theme.SM,
                )
                val members = community.memberCount?.let { "$it member${if (it == 1) "" else "s"}" }
                    ?: (if (community.isPublic) "public" else "private")
                Theme.muted(context, font, members, x + Theme.MD, rowY + Theme.SM + 11)

                // Caller's role chips, in the right half of the row.
                var chipX = x + width / 2
                val chipY = rowY + (ROW_HEIGHT - Theme.CHIP_H) / 2
                for (role in community.roles) {
                    val chipWidth = Theme.XS + font.width(role.name) + Theme.XS
                    if (chipX + chipWidth > x + width - Theme.MD) break
                    Theme.chip(context, font, role.name, parseColor(role.color, Theme.ACCENT), chipX, chipY)
                    chipX += chipWidth + Theme.XS
                }
            }
            context.disableScissor()

            Theme.scrollbar(context, x + width - 3, y, height, contentHeight, height, scrollOffset.toInt())
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }
}
