package io.schemat.schematioConnector.vcs

import io.schemat.connector.core.vcs.DiffSession
import io.schemat.connector.core.vcs.SessionMode
import io.schemat.schematioConnector.commands.audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Chat-mode UI fragments for diff sessions, shared by DiffSubcommand (session verbs)
 * and ConflictResolver (which opens RESOLVE sessions outside the diff command).
 */
object DiffControls {

    fun clickable(label: String, command: String, color: NamedTextColor, hover: String): Component =
        Component.text(label)
            .color(color)
            .decorate(TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(hover)))

    fun totalsLabel(session: DiffSession): String {
        val added = session.regions.sumOf { it.addedCount }
        val removed = session.regions.sumOf { it.removedCount }
        val changed = session.regions.sumOf { it.changedCount }
        return "+$added −$removed ~$changed"
    }

    fun sendFocusInfo(player: Player, session: DiffSession) {
        val region = session.focusedRegion ?: return
        val choice = session.choices[region.id]?.let { " · ${it.name}" } ?: ""
        player.audience().sendMessage(
            Component.text("Region ${region.id + 1}/${session.regions.size} · ${region.countLabel()}$choice").color(NamedTextColor.AQUA),
        )
    }

    fun sendChatControls(player: Player, session: DiffSession) {
        val audience = player.audience()
        var line = Component.text("")
            .append(clickable("[◀ Prev]", "/schematio diff prev", NamedTextColor.AQUA, "Focus previous region"))
            .append(Component.text(" "))
            .append(clickable("[Next ▶]", "/schematio diff next", NamedTextColor.AQUA, "Focus next region"))
        line = if (session.mode == SessionMode.RESOLVE) {
            line
                .append(Component.text(" "))
                .append(clickable("[Mine]", "/schematio diff mine", NamedTextColor.GREEN, "Keep your edit for this region"))
                .append(Component.text(" "))
                .append(clickable("[Theirs]", "/schematio diff theirs", NamedTextColor.AQUA, "Keep the new head for this region"))
                .append(Component.text(" "))
                .append(clickable("[Done]", "/schematio diff done", NamedTextColor.GOLD, "Compose and commit"))
                .append(Component.text(" "))
                .append(clickable("[Abort]", "/schematio diff abort", NamedTextColor.RED, "Abandon the resolution"))
        } else {
            line
                .append(Component.text(" "))
                .append(clickable("[Close]", "/schematio diff close", NamedTextColor.RED, "Close the diff viewer"))
        }
        audience.sendMessage(line)
        audience.sendMessage(
            Component.text("Also: /schematio diff goto <n> · layers <kind> <on|off>").color(NamedTextColor.DARK_GRAY),
        )
    }
}
