package io.schemat.schematioConnector.commands

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.UIMode
import io.schemat.schematioConnector.utils.UIModeResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Allows players to configure their UI preferences.
 *
 * Usage:
 * - /schematio settings - Show current settings (dialog if available, chat otherwise)
 * - /schematio settings ui <chat|dialog> - Set preferred UI mode
 * - /schematio settings reset - Reset to server default
 *
 * @property plugin The main plugin instance
 */
class SettingsSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "settings"
    override val permission = "schematio.list" // Basic permission
    override val description = "Configure your preferences"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        val resolver = plugin.uiModeResolver

        if (args.isEmpty()) {
            // Show current settings using user's preferred UI mode
            val effectiveMode = resolver.resolve(player)
            when (effectiveMode) {
                UIMode.DIALOG -> showSettingsDialog(player)
                UIMode.CHAT -> showSettingsChat(player)
            }
            return true
        }

        when (args[0].lowercase()) {
            "ui" -> {
                if (args.size < 2) {
                    audience.sendMessage(Component.text("Usage: /schematio settings ui <chat|dialog>").color(NamedTextColor.RED))
                    return true
                }

                val mode = UIMode.fromString(args[1])
                if (mode == null) {
                    audience.sendMessage(Component.text("Invalid UI mode. Use 'chat' or 'dialog'.").color(NamedTextColor.RED))
                    return true
                }

                // Check permission
                if (!resolver.hasPermission(player, mode)) {
                    audience.sendMessage(Component.text("You don't have permission to use the $mode UI mode.").color(NamedTextColor.RED))
                    return true
                }

                plugin.userPreferences.setUIMode(player, mode)
                audience.sendMessage(
                    Component.text("UI preference set to ").color(NamedTextColor.GREEN)
                        .append(Component.text(mode.name.lowercase()).color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                )
            }

            "reset" -> {
                plugin.userPreferences.clearUIMode(player)
                val defaultMode = resolver.getConfigDefault()
                audience.sendMessage(
                    Component.text("UI preference reset to server default (").color(NamedTextColor.GREEN)
                        .append(Component.text(defaultMode.name.lowercase()).color(NamedTextColor.AQUA))
                        .append(Component.text(")").color(NamedTextColor.GREEN))
                )
            }

            else -> {
                audience.sendMessage(Component.text("Usage: /schematio settings [ui <chat|dialog>|reset]").color(NamedTextColor.RED))
            }
        }

        return true
    }

    private fun showSettingsChat(player: Player) {
        val audience = player.audience()
        val resolver = plugin.uiModeResolver
        val userPrefs = plugin.userPreferences

        audience.sendMessage(Component.empty())
        audience.sendMessage(
            Component.text("=== ").color(NamedTextColor.DARK_GRAY)
                .append(Component.text("SchematioConnector Settings").color(NamedTextColor.GOLD))
                .append(Component.text(" ===").color(NamedTextColor.DARK_GRAY))
        )

        // Current UI mode
        val currentUserPref = userPrefs.getUIMode(player)
        val serverDefault = resolver.getConfigDefault()
        val effectiveMode = resolver.resolve(player)

        audience.sendMessage(Component.empty())
        audience.sendMessage(Component.text("UI Mode:").color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD))

        if (currentUserPref != null) {
            audience.sendMessage(
                Component.text("  Your preference: ").color(NamedTextColor.GRAY)
                    .append(Component.text(currentUserPref.name.lowercase()).color(NamedTextColor.AQUA))
            )
        } else {
            audience.sendMessage(
                Component.text("  Your preference: ").color(NamedTextColor.GRAY)
                    .append(Component.text("not set (using server default)").color(NamedTextColor.DARK_GRAY))
            )
        }

        audience.sendMessage(
            Component.text("  Server default: ").color(NamedTextColor.GRAY)
                .append(Component.text(serverDefault.name.lowercase()).color(NamedTextColor.WHITE))
        )

        audience.sendMessage(
            Component.text("  Effective mode: ").color(NamedTextColor.GRAY)
                .append(Component.text(effectiveMode.name.lowercase()).color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
        )

        // Available modes
        val availableModes = resolver.getAvailableModes(player)
        audience.sendMessage(Component.empty())
        audience.sendMessage(
            Component.text("  Available modes: ").color(NamedTextColor.GRAY)
                .append(Component.text(availableModes.joinToString(", ") { it.name.lowercase() }).color(NamedTextColor.WHITE))
        )

        // Change options
        audience.sendMessage(Component.empty())
        audience.sendMessage(Component.text("Change UI mode:").color(NamedTextColor.WHITE))

        for (mode in UIMode.entries) {
            if (resolver.hasPermission(player, mode)) {
                val isActive = effectiveMode == mode
                val color = if (isActive) NamedTextColor.GREEN else NamedTextColor.GRAY
                val prefix = if (isActive) "\u2714 " else "  "

                audience.sendMessage(
                    Component.text("  $prefix").color(color)
                        .append(
                            Component.text("[${mode.name.lowercase()}]")
                                .color(if (isActive) NamedTextColor.GREEN else NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.runCommand("/schematio settings ui ${mode.name.lowercase()}"))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to set as preferred")))
                        )
                        .append(Component.text(if (isActive) " (current)" else "").color(NamedTextColor.DARK_GRAY))
                )
            }
        }

        if (currentUserPref != null) {
            audience.sendMessage(
                Component.text("  ")
                    .append(
                        Component.text("[reset to default]")
                            .color(NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.runCommand("/schematio settings reset"))
                            .hoverEvent(HoverEvent.showText(Component.text("Reset to server default")))
                    )
            )
        }

        audience.sendMessage(Component.empty())
    }

    private fun showSettingsDialog(player: Player) {
        val resolver = plugin.uiModeResolver
        val userPrefs = plugin.userPreferences

        val currentUserPref = userPrefs.getUIMode(player)
        val serverDefault = resolver.getConfigDefault()
        val effectiveMode = resolver.resolve(player)

        val title = Component.text("Settings")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val bodyElements = mutableListOf<DialogBody>()
        bodyElements.add(DialogBody.plainMessage(
            Component.text("Configure your SchematioConnector preferences").color(NamedTextColor.GRAY)
        ))

        val statusText = if (currentUserPref != null) {
            "Currently using: ${effectiveMode.name.lowercase()} (your preference)"
        } else {
            "Currently using: ${effectiveMode.name.lowercase()} (server default)"
        }
        bodyElements.add(DialogBody.plainMessage(
            Component.text(statusText).color(NamedTextColor.WHITE)
        ))

        // Inputs
        val inputs = mutableListOf<DialogInput>()

        val modeOptions = mutableListOf<SingleOptionDialogInput.OptionEntry>()
        for (mode in UIMode.entries) {
            if (resolver.hasPermission(player, mode)) {
                val isSelected = effectiveMode == mode
                modeOptions.add(SingleOptionDialogInput.OptionEntry.create(
                    mode.name.lowercase(),
                    Component.text(mode.name.lowercase().replaceFirstChar { it.uppercase() }),
                    isSelected
                ))
            }
        }

        if (modeOptions.isNotEmpty()) {
            inputs.add(
                DialogInput.singleOption("ui_mode", Component.text("UI Mode").color(NamedTextColor.WHITE), modeOptions)
                    .width(200)
                    .build()
            )
        }

        // Action buttons
        val actionButtons = mutableListOf<ActionButton>()

        // Save button uses command template to apply selected mode
        actionButtons.add(
            ActionButton.builder(Component.text("Save").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .width(100)
                .action(DialogAction.commandTemplate("/schematio settings ui \$(ui_mode)"))
                .build()
        )

        if (currentUserPref != null) {
            actionButtons.add(
                ActionButton.builder(Component.text("Reset").color(NamedTextColor.YELLOW))
                    .width(80)
                    .action(DialogAction.staticAction(ClickEvent.runCommand("/schematio settings reset")))
                    .build()
            )
        }

        actionButtons.add(
            ActionButton.builder(Component.text("Cancel").color(NamedTextColor.GRAY))
                .width(80)
                // No action - clicking closes the dialog (default behavior)
                .build()
        )

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("SchematioConnector Settings"))
            .body(bodyElements)
            .inputs(inputs)
            .canCloseWithEscape(true)
            .build()

        try {
            val dialog = Dialog.create { builder ->
                builder.empty()
                    .base(dialogBase)
                    .type(DialogType.multiAction(actionButtons, null, 1))
            }
            player.showDialog(dialog)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to show settings dialog: ${e.message}")
            showSettingsChat(player)
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()

        return when (args.size) {
            1 -> {
                val partial = args[0].lowercase()
                listOf("ui", "reset").filter { it.startsWith(partial) }
            }
            2 -> {
                if (args[0].equals("ui", ignoreCase = true)) {
                    val partial = args[1].lowercase()
                    listOf("chat", "dialog").filter { it.startsWith(partial) }
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }
}
