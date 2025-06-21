package io.schemat.schematioConnector.commands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * A common interface for all subcommands of /schematio.
 */
interface Subcommand {
    /**
     * The name of the subcommand (e.g., "upload", "download").
     */
    val name: String

    /**
     * The permission required to execute this subcommand.
     */
    val permission: String

    /**
     * A brief description of what the subcommand does, for help messages.
     */
    val description: String

    /**
     * Executes the subcommand logic.
     * @param sender The player executing the command.
     * @param args The arguments passed to the subcommand.
     * @return true if the command was handled, false otherwise.
     */
    fun execute(sender: Player, args: Array<out String>): Boolean

    /**
     * Provides tab completions for the subcommand.
     * @param sender The player requesting tab completion.
     * @param args The current arguments.
     * @return A list of possible completions.
     */
    fun tabComplete(sender: Player, args: Array<out String>): List<String> = emptyList() // Default implementation
}