package io.schemat.schematioConnector.commands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

interface Subcommand {
    fun execute(player: Player, args: Array<out String>): Boolean
    fun tabComplete(player: Player, args: Array<out String>): List<String>
}