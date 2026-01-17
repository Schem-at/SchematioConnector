package io.schemat.schematioConnector.ui.page

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.commands.audience
import io.schemat.schematioConnector.ui.FloatingUI
import io.schemat.schematioConnector.ui.layout.CrossAxisAlignment
import io.schemat.schematioConnector.ui.layout.Layout
import io.schemat.schematioConnector.ui.layout.MainAxisAlignment
import io.schemat.schematioConnector.ui.layout.Padding
import io.schemat.schematioConnector.utils.parseJsonSafe
import io.schemat.schematioConnector.utils.safeGetObject
import io.schemat.schematioConnector.utils.safeGetString
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.Base64

/**
 * Page content for configuring and creating a quick share.
 *
 * Options:
 * - Name (optional)
 * - Password protection (toggle + input)
 * - Expiration time (1h, 24h, 7d)
 * - Download limit (unlimited, 1, 10)
 */
class QuickShareContent(
    private val plugin: SchematioConnector,
    private val player: Player,
    private val schematicBytes: ByteArray
) : PageContent("quick_share", "Quick Share") {

    private val gson = Gson()
    private val QUICKSHARE_ENDPOINT = "/plugin/quick-shares"

    companion object {
        const val PADDING = 0.08f
        const val GAP = 0.05f
        const val HEADER_HEIGHT = 0.25f
        const val ROW_HEIGHT = 0.18f
        const val BUTTON_HEIGHT = 0.22f

        /**
         * Create a quick share directly without UI (for --direct flag)
         */
        fun createQuickShareDirect(plugin: SchematioConnector, player: Player, schematicBytes: ByteArray) {
            val audience = player.audience()
            audience.sendMessage(Component.text("Creating quick share...").color(NamedTextColor.YELLOW))

            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                try {
                    val httpUtil = plugin.httpUtil ?: run {
                        plugin.server.scheduler.runTask(plugin, Runnable {
                            audience.sendMessage(Component.text("API not connected.").color(NamedTextColor.RED))
                        })
                        return@Runnable
                    }

                    val base64Data = Base64.getEncoder().encodeToString(schematicBytes)
                    val requestBody = JsonObject().apply {
                        addProperty("schematic_data", base64Data)
                        addProperty("format", "schem")
                        addProperty("expires_in", 86400) // 24 hours default
                        addProperty("player_uuid", player.uniqueId.toString())
                    }

                    val (statusCode, responseBody) = runBlocking {
                        httpUtil.sendPostRequest("/plugin/quick-shares", requestBody.toString())
                    }

                    plugin.server.scheduler.runTask(plugin, Runnable {
                        handleShareResponse(player, statusCode, responseBody)
                    })
                } catch (e: Exception) {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        audience.sendMessage(Component.text("Error creating share: ${e.message}").color(NamedTextColor.RED))
                    })
                }
            })
        }

        private fun handleShareResponse(player: Player, statusCode: Int, responseBody: String?) {
            val audience = player.audience()

            if (statusCode == 201 && responseBody != null) {
                try {
                    val json = parseJsonSafe(responseBody)
                    val quickShare = json.safeGetObject("quick_share")
                    val webUrl = quickShare.safeGetString("web_url")
                    val accessCode = quickShare.safeGetString("access_code")

                    if (webUrl != null) {
                        audience.sendMessage(Component.text("Quick share created!").color(NamedTextColor.GREEN))

                        val linkComponent = Component.text(webUrl)
                            .color(NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl(webUrl))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to open share link")))

                        audience.sendMessage(
                            Component.text("Link: ").color(NamedTextColor.GRAY)
                                .append(linkComponent)
                        )

                        // Also show copy hint
                        audience.sendMessage(
                            Component.text("[Click to copy]")
                                .color(NamedTextColor.YELLOW)
                                .clickEvent(ClickEvent.copyToClipboard(webUrl))
                                .hoverEvent(HoverEvent.showText(Component.text("Copy link to clipboard")))
                        )
                    } else {
                        audience.sendMessage(Component.text("Share created but no URL returned.").color(NamedTextColor.YELLOW))
                    }
                } catch (e: Exception) {
                    audience.sendMessage(Component.text("Error parsing response: ${e.message}").color(NamedTextColor.RED))
                }
            } else {
                val errorMsg = when (statusCode) {
                    400 -> "Invalid schematic data"
                    403 -> "Permission denied - token may not have create_quick_share permission"
                    413 -> "Schematic too large (max 10MB)"
                    -1 -> "Connection failed"
                    else -> "Error (code: $statusCode)"
                }
                audience.sendMessage(Component.text(errorMsg).color(NamedTextColor.RED))
                if (responseBody != null) {
                    plugin.logger.warning("Quick share error response: $responseBody")
                }
            }
        }

        private val plugin get() = SchematioConnector.instance
    }

    // Configuration state
    private var shareName: String = ""
    private var hasPassword: Boolean = false
    private var password: String = ""
    private var selectedExpirationIndex: Int = 1 // Default: 24h
    private var selectedLimitIndex: Int = 0 // Default: Unlimited

    // Expiration options (label, seconds)
    private val expirationOptions = listOf(
        "1 hour" to 3600,
        "24 hours" to 86400,
        "7 days" to 604800
    )

    // Limit options (label, type, max_uses)
    private val limitOptions = listOf(
        Triple("Unlimited", "unlimited", null),
        Triple("1 download", "total", 1),
        Triple("10 downloads", "total", 10)
    )

    // State
    private var isSubmitting = false
    private var errorMessage: String? = null

    override fun buildLayout(width: Float, height: Float): Layout {
        return Layout(width = width, height = height).apply {
            column(
                "root",
                padding = Padding.all(PADDING),
                gap = GAP,
                crossAxisAlignment = CrossAxisAlignment.Stretch
            ) {
                // Header
                leaf("header", height = HEADER_HEIGHT)

                // Name row (info only - chat input would be complex)
                leaf("name_row", height = ROW_HEIGHT)

                // Password toggle row
                row("password_row", height = ROW_HEIGHT, gap = GAP, crossAxisAlignment = CrossAxisAlignment.Center) {
                    leaf("password_label", flexGrow = 1f, height = ROW_HEIGHT)
                    leaf("password_toggle", width = 0.4f, height = ROW_HEIGHT * 0.9f)
                }

                // Expiration row
                row("expiration_row", height = ROW_HEIGHT, gap = GAP * 0.5f, crossAxisAlignment = CrossAxisAlignment.Center) {
                    leaf("expiration_label", width = 0.6f, height = ROW_HEIGHT)
                    for (i in expirationOptions.indices) {
                        leaf("exp_$i", width = 0.55f, height = ROW_HEIGHT * 0.9f)
                    }
                }

                // Limit row
                row("limit_row", height = ROW_HEIGHT, gap = GAP * 0.5f, crossAxisAlignment = CrossAxisAlignment.Center) {
                    leaf("limit_label", width = 0.6f, height = ROW_HEIGHT)
                    for (i in limitOptions.indices) {
                        leaf("limit_$i", width = 0.55f, height = ROW_HEIGHT * 0.9f)
                    }
                }

                // Error message (if any)
                if (errorMessage != null) {
                    leaf("error", height = ROW_HEIGHT)
                }

                // Spacer
                spacer(flexGrow = 1f)

                // Action buttons
                row("actions", height = BUTTON_HEIGHT, gap = GAP, mainAxisAlignment = MainAxisAlignment.Center) {
                    leaf("cancel_btn", width = 0.8f, height = BUTTON_HEIGHT)
                    leaf("create_btn", width = 1.2f, height = BUTTON_HEIGHT)
                }
            }
            compute()
        }
    }

    override fun render(ui: FloatingUI, renderer: LayoutRenderer, bounds: PageBounds) {
        // Header
        renderer.renderPanel("header", Material.BLUE_CONCRETE, 0.01)?.let { elements.add(it) }
        renderer.renderAlignedLabel("header", "§f§lQuick Share", 0.25f, -0.02)?.let { elements.add(it) }

        // Name row (informational)
        renderer.renderPanel("name_row", Material.GRAY_CONCRETE, 0.01)?.let { elements.add(it) }
        val sizeKb = schematicBytes.size / 1024
        renderer.renderAlignedLabel("name_row", "§7Clipboard: §f${sizeKb}KB schematic", 0.16f, -0.02)?.let { elements.add(it) }

        // Password row
        renderer.renderAlignedLabel("password_label", "§7Password protect:", 0.16f, -0.02)?.let { elements.add(it) }
        renderToggleButton(ui, renderer, "password_toggle", hasPassword) {
            hasPassword = !hasPassword
            if (hasPassword) {
                // Open chat input for password
                promptForPassword()
            }
            rebuild()
        }

        // Expiration row
        renderer.renderAlignedLabel("expiration_label", "§7Expires:", 0.16f, -0.02)?.let { elements.add(it) }
        for (i in expirationOptions.indices) {
            val (label, _) = expirationOptions[i]
            val isSelected = i == selectedExpirationIndex
            renderOptionButton(ui, renderer, "exp_$i", label, isSelected) {
                selectedExpirationIndex = i
                rebuild()
            }
        }

        // Limit row
        renderer.renderAlignedLabel("limit_label", "§7Limit:", 0.16f, -0.02)?.let { elements.add(it) }
        for (i in limitOptions.indices) {
            val (label, _, _) = limitOptions[i]
            val isSelected = i == selectedLimitIndex
            renderOptionButton(ui, renderer, "limit_$i", label, isSelected) {
                selectedLimitIndex = i
                rebuild()
            }
        }

        // Error message
        if (errorMessage != null) {
            renderer.renderPanel("error", Material.RED_CONCRETE, 0.01)?.let { elements.add(it) }
            renderer.renderAlignedLabel("error", "§c$errorMessage", 0.14f, -0.02)?.let { elements.add(it) }
        }

        // Cancel button
        renderActionButton(ui, renderer, "cancel_btn", "§fCancel", Material.RED_CONCRETE, Material.ORANGE_CONCRETE) {
            page?.manager?.destroy()
        }

        // Create button
        val createLabel = if (isSubmitting) "§7Creating..." else "§a§lCreate Share"
        val createMaterial = if (isSubmitting) Material.GRAY_CONCRETE else Material.GREEN_CONCRETE
        val createHover = if (isSubmitting) Material.GRAY_CONCRETE else Material.LIME_CONCRETE
        renderActionButton(ui, renderer, "create_btn", createLabel, createMaterial, createHover) {
            if (!isSubmitting) {
                createQuickShare()
            }
        }
    }

    private fun renderToggleButton(
        ui: FloatingUI,
        renderer: LayoutRenderer,
        elementId: String,
        isOn: Boolean,
        onClick: () -> Unit
    ) {
        val pos = renderer.getElementPosition(elementId) ?: return

        val material = if (isOn) Material.LIME_CONCRETE else Material.GRAY_CONCRETE
        val hoverMaterial = if (isOn) Material.GREEN_CONCRETE else Material.LIGHT_GRAY_CONCRETE
        val label = if (isOn) "§aON" else "§7OFF"

        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = material,
            hoverMaterial = hoverMaterial
        ) {
            onClick()
        }
        elements.add(panel)

        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = label,
            scale = 0.14f
        ))
    }

    private fun renderOptionButton(
        ui: FloatingUI,
        renderer: LayoutRenderer,
        elementId: String,
        label: String,
        isSelected: Boolean,
        onClick: () -> Unit
    ) {
        val pos = renderer.getElementPosition(elementId) ?: return

        val material = if (isSelected) Material.LIGHT_BLUE_CONCRETE else Material.GRAY_CONCRETE
        val hoverMaterial = if (isSelected) Material.CYAN_CONCRETE else Material.LIGHT_GRAY_CONCRETE
        val textColor = if (isSelected) "§f" else "§7"

        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = material,
            hoverMaterial = hoverMaterial
        ) {
            onClick()
        }
        elements.add(panel)

        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = "$textColor$label",
            scale = 0.12f
        ))
    }

    private fun renderActionButton(
        ui: FloatingUI,
        renderer: LayoutRenderer,
        elementId: String,
        label: String,
        material: Material,
        hoverMaterial: Material,
        onClick: () -> Unit
    ) {
        val pos = renderer.getElementPosition(elementId) ?: return

        val panel = ui.addInteractivePanel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = 0.01,
            width = pos.width,
            height = pos.height,
            material = material,
            hoverMaterial = hoverMaterial
        ) {
            onClick()
        }
        elements.add(panel)

        elements.add(ui.addAlignedLabel(
            offsetRight = pos.uiX,
            offsetUp = pos.uiY,
            offsetForward = -0.02,
            text = label,
            scale = 0.18f
        ))
    }

    private fun promptForPassword() {
        player.sendMessage("§6Type a password for your share in chat (or 'cancel' to disable password):")
        page?.manager?.destroy()

        val listener = object : org.bukkit.event.Listener {
            @Suppress("DEPRECATION")
            @org.bukkit.event.EventHandler
            fun onChat(event: org.bukkit.event.player.AsyncPlayerChatEvent) {
                if (event.player.uniqueId != player.uniqueId) return

                event.isCancelled = true
                val message = event.message

                org.bukkit.event.HandlerList.unregisterAll(this)

                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (message.equals("cancel", ignoreCase = true)) {
                        player.sendMessage("§7Password cancelled.")
                        hasPassword = false
                        password = ""
                    } else {
                        password = message
                        player.sendMessage("§aPassword set.")
                    }
                    // Reopen UI
                    reopenUI()
                })
            }
        }

        plugin.server.pluginManager.registerEvents(listener, plugin)

        // Auto-unregister after 30 seconds
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            org.bukkit.event.HandlerList.unregisterAll(listener)
        }, 600L)
    }

    private fun reopenUI() {
        FloatingUI.closeForPlayer(player)

        val eyeLocation = player.eyeLocation
        val direction = eyeLocation.direction.normalize()
        val center = eyeLocation.clone().add(direction.multiply(3.0))

        val ui = FloatingUI.create(plugin, player, center)
        val bounds = PageBounds(x = -1.5f, y = 1.0f, width = 3.0f, height = 2.0f)
        val manager = PageManager(plugin, ui, player, bounds)

        // Create a new content with the same state
        val content = QuickShareContent(plugin, player, schematicBytes).apply {
            this.hasPassword = this@QuickShareContent.hasPassword
            this.password = this@QuickShareContent.password
            this.selectedExpirationIndex = this@QuickShareContent.selectedExpirationIndex
            this.selectedLimitIndex = this@QuickShareContent.selectedLimitIndex
        }
        manager.showPage(content)
    }

    private fun createQuickShare() {
        isSubmitting = true
        errorMessage = null
        rebuild()

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val httpUtil = plugin.httpUtil ?: run {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        errorMessage = "API not connected"
                        isSubmitting = false
                        rebuild()
                    })
                    return@Runnable
                }

                // Build request body
                val base64Data = Base64.getEncoder().encodeToString(schematicBytes)
                val (_, expiresIn) = expirationOptions[selectedExpirationIndex]
                val (_, limitType, maxUses) = limitOptions[selectedLimitIndex]

                val requestBody = JsonObject().apply {
                    addProperty("schematic_data", base64Data)
                    addProperty("format", "schem")
                    addProperty("expires_in", expiresIn)
                    addProperty("limit_type", limitType)
                    if (maxUses != null) {
                        addProperty("max_uses", maxUses)
                    }
                    if (hasPassword && password.isNotBlank()) {
                        addProperty("password", password)
                    }
                    addProperty("player_uuid", player.uniqueId.toString())
                }

                val (statusCode, responseBody) = runBlocking {
                    httpUtil.sendPostRequest(QUICKSHARE_ENDPOINT, requestBody.toString())
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    isSubmitting = false

                    if (statusCode == 201 && responseBody != null) {
                        // Success - close UI and show link
                        page?.manager?.destroy()
                        handleShareResponse(player, statusCode, responseBody)
                    } else {
                        // Error - show in UI
                        errorMessage = when (statusCode) {
                            400 -> "Invalid data"
                            403 -> "Permission denied"
                            413 -> "Too large"
                            -1 -> "Connection failed"
                            else -> "Error ($statusCode)"
                        }
                        rebuild()
                    }
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    errorMessage = "Error: ${e.message?.take(20)}"
                    isSubmitting = false
                    rebuild()
                })
            }
        })
    }
}
