package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.type.ImBoolean
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.render.CaptureSpike
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.fabricmc.loader.api.FabricLoader
//? if >=1.21.11 {
import net.minecraft.util.Util
//?} else {
/*import net.minecraft.Util
*///?}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone, dockable "Settings" window — connection status + maintenance actions.
 *
 * Was the Settings tab of the former multi-tab `BrowserPanel`; replaces its
 * `openToSettings()` entry point. Toggled from the toolbar / keybinds via [PanelManager].
 */
object SettingsPanel : Panel {

    override val id = "settings"

    private val services: ClientServices get() = SchematioClientMod.instance.services

    private val reauthBusy = AtomicBoolean(false)
    /** Transient status message shown after an action. */
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.INFO

    override fun render() {
        val open = ImBoolean(true)
        ImGui.setNextWindowSize(520f, 440f, imgui.flag.ImGuiCond.FirstUseEver)
        val expanded = ImGui.begin("Settings###settings", open)
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
        val authManager = services.authManager

        // ---- CONNECTION section ----
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            "Connection"
        )
        ImGui.separator()

        // Endpoint row
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Endpoint"
        )
        ImGui.sameLine()
        ImGui.text(authManager.apiEndpoint)

        // Status row
        val player = services.me?.player
        when {
            player != null -> {
                ImGui.textColored(
                    ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
                    "Status "
                )
                ImGui.sameLine()
                Widgets.statusText("Signed in as ${player.name} (${player.id})", Widgets.StatusKind.SUCCESS)
            }
            authManager.isAuthenticated -> {
                ImGui.textColored(
                    ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
                    "Status "
                )
                ImGui.sameLine()
                Widgets.statusText(
                    "Signed in (uuid: ${authManager.session?.playerUuid ?: "unknown"})",
                    Widgets.StatusKind.SUCCESS
                )
            }
            else -> {
                ImGui.textColored(
                    ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
                    "Status "
                )
                ImGui.sameLine()
                Widgets.statusText("Not signed in", Widgets.StatusKind.DANGER)
            }
        }

        // Offline warning
        if (services.isOffline()) {
            Widgets.statusText(
                "Offline — can't reach the backend (cached data shown where available)",
                Widgets.StatusKind.WARNING
            )
        }

        ImGui.spacing()

        // ---- ACTIONS section ----
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            "Actions"
        )
        ImGui.separator()

        // Re-authenticate button
        val reauthLabel = if (reauthBusy.get()) "Authenticating..." else "Re-authenticate"
        if (reauthBusy.get()) ImGui.beginDisabled()
        if (Widgets.button(reauthLabel)) {
            reauthenticate()
        }
        if (reauthBusy.get()) ImGui.endDisabled()

        // Clear cache button
        if (Widgets.button("Clear cache")) {
            services.cached.clear()
            statusText = "Cache cleared — listings will refresh on next load"
            statusKind = Widgets.StatusKind.INFO
        }

        // Open config folder button
        if (Widgets.button("Open config folder")) {
            val dir = FabricLoader.getInstance().configDir.resolve(SchematioConnectorMod.MOD_ID)
            dir.toFile().mkdirs()
            Util.getPlatform().openFile(dir.toFile())
        }

        // Dev-only capture spike button (hidden in production)
        if (FabricLoader.getInstance().isDevelopmentEnvironment) {
            if (Widgets.button("Debug: capture test")) {
                CaptureSpike.run()
                statusText = "Capture spike triggered — check the log (SCHEMAT-SPIKE) and schemat-capture-test.png in the game dir"
                statusKind = Widgets.StatusKind.INFO
            }
        }

        // Status / feedback banner
        val sText = statusText
        if (sText != null) {
            ImGui.spacing()
            Widgets.statusText(sText, statusKind)
        }
    }

    private fun reauthenticate() {
        statusText = null
        statusKind = Widgets.StatusKind.INFO
        services.call(
            busy = reauthBusy,
            block = {
                // Force: discard the cached token and re-handshake, so a token issued
                // before a backend permission change is replaced (ensureAuthenticated
                // would just reuse the non-expired cached token).
                if (services.authManager.forceReauthenticate()) {
                    services.refreshMe(force = true)
                    ApiResult.Success(Unit)
                } else {
                    ApiResult.Failure(ApiError.Unauthorized("Authentication failed — see the log for details"))
                }
            },
        ) { result ->
            when (result) {
                is ApiResult.Success -> {
                    statusText = "Authenticated successfully"
                    statusKind = Widgets.StatusKind.SUCCESS
                }
                is ApiResult.Failure -> {
                    statusText = result.error.toUserMessage()
                    statusKind = Widgets.StatusKind.DANGER
                }
            }
        }
    }
}
