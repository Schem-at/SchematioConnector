package io.schemat.connector.fabric.client.ui.panels

import imgui.ImGui
import imgui.flag.ImGuiDir
import imgui.type.ImBoolean
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.routing.Destination
import io.schemat.connector.core.routing.DestinationProfiles
import io.schemat.connector.fabric.SchematioConnectorMod
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.ipc.OpenUiPrefs
import io.schemat.connector.fabric.client.ui.framework.Panel
import io.schemat.connector.fabric.client.ui.framework.PanelManager
import io.schemat.connector.fabric.client.render.CaptureSpike
import io.schemat.connector.fabric.client.routing.ClientDestinations
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.theme.ImGuiColors
import io.schemat.connector.fabric.client.ui.theme.ImGuiTheme
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
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
    /** "Server may open UI" toggle (spec D, default ON); loaded from config on first render. */
    private val allowServerOpenUi = ImBoolean(OpenUiPrefs.DEFAULT)
    private var prefsLoaded = false
    /** Transient status message shown after an action. */
    private var statusText: String? = null
    private var statusKind: Widgets.StatusKind = Widgets.StatusKind.INFO

    /** Last destination-order save failed (ClientAuthManager logged why); cleared by the next success. */
    private var destinationSaveFailed = false

    override fun render() {
        val open = ImBoolean(true)
        ImGui.setNextWindowSize(520f, 440f, imgui.flag.ImGuiCond.FirstUseEver)
        val expanded = ImGui.begin("Settings###settings", open)
        if (expanded) ImGuiTheme.windowTitleAccent()
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

        // ---- PREFERENCES section ----
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            "Preferences"
        )
        ImGui.separator()

        if (!prefsLoaded) {
            allowServerOpenUi.set(authManager.getConfigFlag(OpenUiPrefs.KEY, OpenUiPrefs.DEFAULT))
            prefsLoaded = true
        }
        if (ImGui.checkbox("Allow this server to open Schematio windows", allowServerOpenUi)) {
            authManager.setConfigFlag(OpenUiPrefs.KEY, allowServerOpenUi.get())
            statusText = if (allowServerOpenUi.get()) {
                "Attested servers may now open Schematio windows for /schematio commands"
            } else {
                "Servers can no longer open Schematio windows — /schematio returns to chat menus after rejoin"
            }
            statusKind = Widgets.StatusKind.INFO
        }
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "When on, /schematio menus on attested Schematio servers open here instead of chat"
        )

        ImGui.spacing()

        renderDownloadDestinations()

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
            //? if >=26.3 {
            /*com.mojang.blaze3d.Blaze3D.openPath(dir)
            *///?} else {
            Util.getPlatform().openFile(dir.toFile())
            //?}
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

    // ---- Download destination ----

    private fun renderDownloadDestinations() {
        ImGui.textColored(
            ImGuiColors.ACCENT.x, ImGuiColors.ACCENT.y, ImGuiColors.ACCENT.z, ImGuiColors.ACCENT.w,
            "Download destination"
        )
        ImGui.separator()
        if (destinationSaveFailed) {
            ImGui.textColored(
                ImGuiColors.DANGER.x, ImGuiColors.DANGER.y, ImGuiColors.DANGER.z, ImGuiColors.DANGER.w,
                "Could not save: see the log"
            )
        }

        val key = ClientDestinations.profileKey()
        val unavailable = ClientDestinations.unavailable()
        if (key == DestinationProfiles.DEFAULT_KEY) {
            // Not in a world: there is no per-world profile to edit, only the shared default.
            mutedText("Downloads try these in order, using the first one that is available.")
            renderOrderControls(DestinationProfiles.DEFAULT_KEY, unavailable)
            return
        }

        val world = if (key == DestinationProfiles.SINGLEPLAYER_KEY) {
            "Singleplayer"
        } else {
            "This server (${Minecraft.getInstance().currentServer?.ip ?: "unknown address"})"
        }
        ImGui.text(world)
        if (!ClientDestinations.hasOwnProfile(key)) mutedText("Using your default order")
        renderOrderControls(key, unavailable)

        ImGui.spacing()
        if (ImGui.collapsingHeader("Default order")) {
            mutedText("Used for worlds without their own order")
            renderOrderControls(DestinationProfiles.DEFAULT_KEY, unavailable)
        }
    }

    /** One numbered row per destination with move up/down buttons, plus a reset button, for profile [key]. */
    private fun renderOrderControls(key: String, unavailable: Map<Destination, String>) {
        ImGui.pushID(key)
        val order = ClientDestinations.order(key)
        order.forEachIndexed { index, dest ->
            ImGui.pushID(index)
            ImGui.text("${index + 1}.  ${dest.label}")
            ImGui.sameLine()
            if (index == 0) ImGui.beginDisabled()
            if (ImGui.arrowButton("##up", ImGuiDir.Up)) saveOrder(key, swapped(order, index, index - 1))
            if (index == 0) ImGui.endDisabled()
            ImGui.sameLine()
            if (index == order.lastIndex) ImGui.beginDisabled()
            if (ImGui.arrowButton("##down", ImGuiDir.Down)) saveOrder(key, swapped(order, index, index + 1))
            if (index == order.lastIndex) ImGui.endDisabled()
            unavailable[dest]?.let {
                ImGui.sameLine()
                mutedText(it)
            }
            ImGui.popID()
        }
        if (Widgets.button("Reset to default")) saveOrder(key, null)
        ImGui.popID()
    }

    private fun saveOrder(key: String, order: List<Destination>?) {
        destinationSaveFailed = !ClientDestinations.save(key, order)
    }

    private fun swapped(order: List<Destination>, i: Int, j: Int): List<Destination> =
        order.toMutableList().also { java.util.Collections.swap(it, i, j) }

    private fun mutedText(text: String) = ImGui.textColored(
        ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y, ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
        text
    )

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
