package io.schemat.connector.fabric.client.ui.tabs

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.dto.QuickShareInfo
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.HomeScreen
import io.schemat.connector.fabric.client.ui.QuickShareCreateScreen
import io.schemat.connector.fabric.client.ui.foundation.ConfirmDialogScreen
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.OFFLINE_TOOLTIP
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
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Quick Shares tab: the caller's shares (name, active badge, access code, relative
 * expiry, use counts) with per-row Copy / Revoke actions, plus a New Share button that
 * opens [QuickShareCreateScreen].
 */
class QuickSharesTab : HomeScreen.TabContent {

    companion object {
        private const val ROW_HEIGHT = 30
        private const val ACTION_WIDTH = 48
        private const val ACTION_HEIGHT = 14
    }

    private val services get() = SchematioClientMod.instance.services

    // ---- state (survives re-init) ----
    private var shares: List<QuickShareInfo> = emptyList()
    private var hasLoaded = false
    private val loadBusy = AtomicBoolean(false)
    private val actionBusy = AtomicBoolean(false)

    private val banner = NoticeBanner()
    private var screen: HomeScreen? = null
    private var listArea = intArrayOf(0, 0, 0, 0)

    override fun init(screen: HomeScreen, width: Int, height: Int) {
        this.screen = screen
        val pad = HomeScreen.PADDING
        val controlsY = screen.contentTop

        screen.register(
            FlatButton.primary(pad, controlsY, 90, Component.literal("New share")) {
                Minecraft.getInstance().setScreen(QuickShareCreateScreen(screen))
            }.disabledWhenOffline(services)
        )
        screen.register(
            FlatButton.secondary(pad + 90 + Theme.SM, controlsY, 70, Component.literal("Refresh")) {
                hasLoaded = false
                load()
            }
        )

        var listTop = controlsY + Theme.BTN_H + Theme.MD
        screen.register(banner.layout(pad, listTop, width - pad * 2))
        listTop += NoticeBanner.HEIGHT + Theme.MD

        val listHeight = (height - listTop - pad).coerceAtLeast(40)
        listArea = intArrayOf(pad, listTop, width - pad * 2, listHeight)
        screen.register(ShareListWidget(pad, listTop, width - pad * 2, listHeight))

        if (!hasLoaded) load()
    }

    /** External hook: drop state so the next init refetches (e.g. after creating a share). */
    fun invalidate() {
        hasLoaded = false
    }

    private fun load() {
        hasLoaded = true
        banner.clear()
        services.call(
            busy = loadBusy,
            block = { services.cached.quickShares() },
        ) { result ->
            when (result) {
                is ApiResult.Success -> shares = result.value
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage()) {
                    banner.clear()
                    load()
                }
            }
        }
    }

    private fun shareUrl(share: QuickShareInfo): String {
        share.webUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val base = services.authManager.apiEndpoint.substringBefore("/api/").trimEnd('/')
        return "$base/share/${share.accessCode}"
    }

    private fun copyLink(share: QuickShareInfo) {
        Minecraft.getInstance().keyboardHandler.setClipboard(shareUrl(share))
        banner.show(NoticeBanner.Kind.INFO, "Link for \"${share.name ?: share.accessCode}\" copied to clipboard")
    }

    private fun confirmRevoke(share: QuickShareInfo) {
        val host = screen ?: return
        if (services.isOffline()) {
            banner.show(NoticeBanner.Kind.ERROR, OFFLINE_TOOLTIP)
            return
        }
        Minecraft.getInstance().setScreen(
            ConfirmDialogScreen(
                host,
                "Revoke quick share",
                "Revoke \"${share.name ?: share.accessCode}\"? The link will stop working immediately.",
                confirmLabel = "Revoke",
                danger = true,
            ) { revoke(share) }
        )
    }

    private fun revoke(share: QuickShareInfo) {
        services.call(
            busy = actionBusy,
            block = { services.cached.revokeQuickShare(share.accessCode) },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    banner.show(NoticeBanner.Kind.INFO, "Quick share revoked")
                    hasLoaded = false
                    load()
                }
                is ApiResult.Failure -> banner.show(NoticeBanner.Kind.ERROR, result.error.toUserMessage())
            }
        }
    }

    /** "in 3d 4h" / "in 25m" / "expired" / "never" from an ISO-8601 timestamp. */
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

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val font = Minecraft.getInstance().font
        banner.render(context, font)

        val centerX = listArea[0] + listArea[2] / 2
        if (shares.isEmpty()) {
            if (loadBusy.get()) {
                LoadingSpinner.render(context, font, centerX, listArea[1] + listArea[3] / 2 - 4)
            } else if (hasLoaded && !banner.isVisible) {
                Theme.emptyState(
                    context, font, "No quick shares - create one with New share",
                    listArea[0], listArea[1], listArea[2], listArea[3],
                )
            }
        } else if (loadBusy.get() || actionBusy.get()) {
            LoadingSpinner.render(context, font, centerX, listArea[1] + listArea[3] - 12, "Working")
        }
    }

    /** Scrollable share rows with right-aligned Copy / Revoke click zones. */
    private inner class ShareListWidget(x: Int, y: Int, width: Int, height: Int) :
        AbstractWidget(x, y, width, height, Component.literal("Quick shares")) {

        private var scrollOffset = 0.0
        private val contentHeight: Int get() = shares.size * ROW_HEIGHT
        private val maxScroll: Double get() = (contentHeight - height).coerceAtLeast(0).toDouble()

        private val revokeX: Int get() = x + width - ACTION_WIDTH - Theme.MD
        private val copyX: Int get() = revokeX - ACTION_WIDTH - Theme.SM

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
            val share = shares.getOrNull(index) ?: return
            val clickX = click.x().toInt()
            when {
                clickX >= revokeX -> confirmRevoke(share)
                clickX >= copyX -> copyLink(share)
            }
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
            shares.forEachIndexed { index, share ->
                val rowY = y + index * ROW_HEIGHT - scrollOffset.toInt()
                if (rowY + ROW_HEIGHT < y || rowY > y + height) return@forEachIndexed
                val hovered = isMouseOver(mouseX.toDouble(), mouseY.toDouble()) &&
                    mouseY in rowY until (rowY + ROW_HEIGHT)
                if (hovered) context.fill(x, rowY, x + width, rowY + ROW_HEIGHT, Theme.SURFACE_HOVER)
                if (index > 0) Theme.divider(context, x + Theme.MD, rowY, width - Theme.MD * 2)

                val title = share.name?.takeIf { it.isNotBlank() } ?: share.accessCode
                val nameColor = if (share.isActive) Theme.TEXT_PRIMARY else Theme.TEXT_MUTED
                val titleMax = copyX - x - Theme.MD * 2 - 50 // leave room for the status badge
                val trimmedTitle = font.plainSubstrByWidth(title, titleMax)
                Theme.value(context, font, trimmedTitle, x + Theme.MD, rowY + Theme.SM, nameColor)

                val badgeX = x + Theme.MD + font.width(trimmedTitle) + Theme.SM
                if (share.isActive) {
                    Theme.badge(context, font, "active", Theme.SUCCESS, badgeX, rowY + Theme.XS)
                } else {
                    Theme.badge(context, font, "inactive", Theme.TEXT_FAINT, badgeX, rowY + Theme.XS)
                }

                val uses = share.maxUses?.let { "${share.currentUses}/$it uses" } ?: "${share.currentUses} uses"
                val detail = "${share.accessCode}  ·  ${relativeExpiry(share.expiresAt)}  ·  $uses"
                Theme.muted(
                    context, font,
                    font.plainSubstrByWidth(detail, copyX - x - Theme.MD * 2),
                    x + Theme.MD, rowY + Theme.SM + 11,
                )

                drawAction(context, "Copy", copyX, rowY, mouseX, mouseY, Theme.INFO)
                drawAction(context, "Revoke", revokeX, rowY, mouseX, mouseY, Theme.DANGER)
            }
            context.disableScissor()

            Theme.scrollbar(context, x + width - 3, y, height, contentHeight, height, scrollOffset.toInt())
        }

        /** Small ghost-style action pill inside a row. */
        private fun drawAction(context: GuiGraphics, label: String, actionX: Int, rowY: Int, mouseX: Int, mouseY: Int, tint: Int) {
            val font = Minecraft.getInstance().font
            val actionY = rowY + (ROW_HEIGHT - ACTION_HEIGHT) / 2
            val hovered = mouseX in actionX until (actionX + ACTION_WIDTH) &&
                mouseY in rowY until (rowY + ROW_HEIGHT) && isMouseOver(mouseX.toDouble(), mouseY.toDouble())
            if (hovered) {
                context.fill(actionX, actionY, actionX + ACTION_WIDTH, actionY + ACTION_HEIGHT, Theme.withAlpha(tint, 0x22))
            }
            Theme.stroke(context, actionX, actionY, ACTION_WIDTH, ACTION_HEIGHT, Theme.withAlpha(tint, if (hovered) 0xAA else 0x55))
            val fg = if (hovered) Theme.lighten(tint, 0.3f) else tint
            context.drawString(
                font, label,
                actionX + (ACTION_WIDTH - font.width(label)) / 2,
                actionY + (ACTION_HEIGHT - font.lineHeight + 1) / 2,
                fg, false,
            )
        }

        override fun updateWidgetNarration(builder: NarrationElementOutput) {
            defaultButtonNarrationText(builder)
        }
    }
}
