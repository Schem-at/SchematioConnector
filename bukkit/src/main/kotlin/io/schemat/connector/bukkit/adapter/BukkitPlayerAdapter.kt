package io.schemat.connector.bukkit.adapter

import io.schemat.connector.core.api.ClickAction
import io.schemat.connector.core.api.MessageColor
import io.schemat.connector.core.api.PlayerAdapter
import io.schemat.connector.core.api.RichMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Bukkit/Paper implementation of PlayerAdapter.
 * Wraps a Bukkit Player and provides platform-agnostic access.
 */
class BukkitPlayerAdapter(val player: Player) : PlayerAdapter {

    override val uuid: UUID = player.uniqueId

    override val name: String = player.name

    override fun sendMessage(message: String) {
        player.sendMessage(message)
    }

    override fun sendRichMessage(message: RichMessage) {
        val component = buildComponent(message)
        player.sendMessage(component)
    }

    override fun hasPermission(permission: String): Boolean {
        return player.hasPermission(permission)
    }

    override fun isOnline(): Boolean {
        return player.isOnline
    }

    override fun isOp(): Boolean {
        return player.isOp
    }

    /**
     * Recursively builds an Adventure Component from a RichMessage.
     */
    private fun buildComponent(message: RichMessage): Component {
        var component = Component.text(message.text)

        // Apply color
        message.color?.let { color ->
            component = component.color(toAdventureColor(color))
        }

        // Apply formatting
        if (message.bold) {
            component = component.decoration(TextDecoration.BOLD, true)
        }
        if (message.italic) {
            component = component.decoration(TextDecoration.ITALIC, true)
        }
        if (message.underlined) {
            component = component.decoration(TextDecoration.UNDERLINED, true)
        }
        if (message.strikethrough) {
            component = component.decoration(TextDecoration.STRIKETHROUGH, true)
        }

        // Apply click action
        message.clickAction?.let { action ->
            component = component.clickEvent(toClickEvent(action))
        }

        // Apply hover text
        message.hoverText?.let { hoverText ->
            component = component.hoverEvent(HoverEvent.showText(Component.text(hoverText)))
        }

        // Append children
        for (child in message.children) {
            component = component.append(buildComponent(child))
        }

        return component
    }

    /**
     * Converts core MessageColor to Adventure NamedTextColor.
     */
    private fun toAdventureColor(color: MessageColor): NamedTextColor {
        return when (color) {
            MessageColor.BLACK -> NamedTextColor.BLACK
            MessageColor.DARK_BLUE -> NamedTextColor.DARK_BLUE
            MessageColor.DARK_GREEN -> NamedTextColor.DARK_GREEN
            MessageColor.DARK_AQUA -> NamedTextColor.DARK_AQUA
            MessageColor.DARK_RED -> NamedTextColor.DARK_RED
            MessageColor.DARK_PURPLE -> NamedTextColor.DARK_PURPLE
            MessageColor.GOLD -> NamedTextColor.GOLD
            MessageColor.GRAY -> NamedTextColor.GRAY
            MessageColor.DARK_GRAY -> NamedTextColor.DARK_GRAY
            MessageColor.BLUE -> NamedTextColor.BLUE
            MessageColor.GREEN -> NamedTextColor.GREEN
            MessageColor.AQUA -> NamedTextColor.AQUA
            MessageColor.RED -> NamedTextColor.RED
            MessageColor.LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE
            MessageColor.YELLOW -> NamedTextColor.YELLOW
            MessageColor.WHITE -> NamedTextColor.WHITE
        }
    }

    /**
     * Converts core ClickAction to Adventure ClickEvent.
     */
    private fun toClickEvent(action: ClickAction): ClickEvent {
        return when (action) {
            is ClickAction.RunCommand -> ClickEvent.runCommand(action.command)
            is ClickAction.SuggestCommand -> ClickEvent.suggestCommand(action.command)
            is ClickAction.OpenUrl -> ClickEvent.openUrl(action.url)
            is ClickAction.CopyToClipboard -> ClickEvent.copyToClipboard(action.text)
        }
    }
}
