package io.schemat.connector.fabric.client.ui.tabs

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.render.CaptureSpike
import io.schemat.connector.fabric.client.ui.HomeScreen
import io.schemat.connector.fabric.client.ui.foundation.FlatButton
import io.schemat.connector.fabric.client.ui.foundation.LoadingSpinner
import io.schemat.connector.fabric.client.ui.foundation.NoticeBanner
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.Theme
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import io.schemat.connector.fabric.client.ui.compat.*
import net.minecraft.network.chat.Component
// Util moved from net.minecraft to net.minecraft.util in 1.21.11.
//? if >=1.21.11 {
import net.minecraft.util.Util
//?} else {
/*import net.minecraft.Util
*///?}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings tab: endpoint + auth status readout, re-authenticate, clear the read
 * cache, and open the mod's config folder.
 */
class SettingsTab : HomeScreen.TabContent {

    companion object {
        private const val BUTTON_WIDTH = 160
        private const val LINE_H = 12
        private const val LABEL_COLUMN = 70
    }

    private val services get() = SchematioClientMod.instance.services
    private val authManager get() = SchematioClientMod.instance.authManager

    private val reauthBusy = AtomicBoolean(false)
    private val banner = NoticeBanner()

    private var contentX = HomeScreen.PADDING
    private var infoTop = 0
    private var actionsLabelY = 0
    private lateinit var reauthButton: FlatButton

    override fun init(screen: HomeScreen, width: Int, height: Int) {
        contentX = HomeScreen.PADDING
        infoTop = screen.contentTop

        // CONNECTION section (drawn in render): section label + endpoint /
        // status / offline rows.
        var y = infoTop + 10 + LINE_H * 3 + Theme.LG

        actionsLabelY = y
        y += 10 + Theme.XS

        reauthButton = screen.register(
            FlatButton.secondary(contentX, y, BUTTON_WIDTH, Component.literal("Re-authenticate")) { reauthenticate() }
        )
        y += Theme.BTN_H + Theme.SM

        screen.register(
            FlatButton.ghost(contentX, y, BUTTON_WIDTH, Component.literal("Clear cache")) { clearCache() }
        )
        y += Theme.BTN_H + Theme.SM

        screen.register(
            FlatButton.ghost(contentX, y, BUTTON_WIDTH, Component.literal("Open config folder")) { openConfigFolder() }
        )
        y += Theme.BTN_H + Theme.SM

        // Dev-only capture spike: proves the offscreen render -> PNG pipeline.
        // Writes <gameDir>/schemat-capture-test.png; see SCHEMAT-SPIKE log lines.
        // Hidden outside a development environment so it never ships to users.
        if (FabricLoader.getInstance().isDevelopmentEnvironment) {
            screen.register(
                FlatButton.ghost(contentX, y, BUTTON_WIDTH, Component.literal("Debug: capture test")) { runCaptureSpike() }
            )
            y += Theme.BTN_H + Theme.SM
        }
        y += Theme.LG

        screen.register(banner.layout(contentX, y, width - HomeScreen.PADDING * 2))
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val font = Minecraft.getInstance().font
        var y = infoTop

        Theme.sectionLabel(context, font, "Connection", contentX, y)
        y += 10

        Theme.muted(context, font, "Endpoint", contentX, y)
        Theme.label(context, font, authManager.apiEndpoint, contentX + LABEL_COLUMN, y)
        y += LINE_H

        val player = services.me?.player
        val status: String
        val statusColor: Int
        when {
            player != null -> {
                status = "Signed in as ${player.name} (${player.id})"
                statusColor = Theme.SUCCESS
            }
            authManager.isAuthenticated -> {
                status = "Signed in (uuid: ${authManager.session?.playerUuid ?: "unknown"})"
                statusColor = Theme.SUCCESS
            }
            else -> {
                status = "Not signed in"
                statusColor = Theme.DANGER
            }
        }
        Theme.muted(context, font, "Status", contentX, y)
        Theme.label(context, font, status, contentX + LABEL_COLUMN, y, statusColor)
        y += LINE_H

        if (services.isOffline()) {
            Theme.label(
                context, font,
                "Offline - can't reach the backend (cached data shown where available)",
                contentX + LABEL_COLUMN, y, Theme.WARNING,
            )
        }

        Theme.sectionLabel(context, font, "Actions", contentX, actionsLabelY)

        reauthButton.active = !reauthBusy.get()
        if (reauthBusy.get()) {
            LoadingSpinner.render(
                context, font,
                reauthButton.x + reauthButton.width + 40, reauthButton.y + 6,
                "Authenticating"
            )
        }

        banner.render(context, font)
    }

    private fun reauthenticate() {
        banner.clear()
        services.call(
            busy = reauthBusy,
            block = {
                // Force: discard the cached token and re-handshake, so a token issued
                // before a backend permission change is replaced (ensureAuthenticated
                // would just reuse the non-expired cached token).
                if (authManager.forceReauthenticate()) {
                    services.refreshMe(force = true)
                    ApiResult.Success(Unit)
                } else {
                    ApiResult.Failure(ApiError.Unauthorized("Authentication failed - see the log for details"))
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> banner.show(NoticeBanner.Kind.INFO, "Authenticated successfully")
                is ApiResult.Failure -> banner.show(
                    NoticeBanner.Kind.ERROR,
                    result.error.toUserMessage(),
                    onRetry = ::reauthenticate,
                )
            }
        }
    }

    private fun clearCache() {
        services.cached.clear()
        banner.show(NoticeBanner.Kind.INFO, "Cache cleared - listings will refresh on next load")
    }

    private fun runCaptureSpike() {
        // Button callbacks run on the render thread, which the spike requires.
        CaptureSpike.run()
        banner.show(
            NoticeBanner.Kind.INFO,
            "Capture spike triggered - check the log (SCHEMAT-SPIKE) and schemat-capture-test.png in the game dir"
        )
    }

    private fun openConfigFolder() {
        val dir = FabricLoader.getInstance().configDir.resolve(SchematioConnectorMod.MOD_ID)
        dir.toFile().mkdirs()
        Util.getPlatform().openFile(dir.toFile())
    }
}
