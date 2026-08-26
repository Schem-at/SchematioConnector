package io.schemat.schematioConnector.commands

import io.schemat.connector.core.validation.InputValidator
import io.schemat.connector.core.validation.ValidationResult
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.vcs.ConflictResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * Commits the player's WorldEdit clipboard as a new version of a versioned schematic:
 * `/schematio commit <id> [message...]`.
 *
 * The heavy lifting (checkout-based `expected_head_version_id`, head fallback, the
 * 409 head_moved → RESOLVE session loop) lives in [ConflictResolver] (spec §6).
 */
class CommitSubcommand(private val plugin: SchematioConnector) : Subcommand {

    companion object {
        const val DEFAULT_MESSAGE = "Committed from in-game"
    }

    private val resolver = ConflictResolver(plugin)

    override val name = "commit"
    override val permission = DiffSubcommand.PERMISSION_COMMIT
    override val description = "Commit your clipboard as a new schematic version"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        if (args.any { it == "--help" || it == "-h" } || args.isEmpty()) {
            showUsage(player)
            return args.isNotEmpty()
        }

        // Strip UI-mode flags for consistency with the other unified commands (the
        // commit flow itself is chat-driven; the RESOLVE controls honor UI mode via
        // /schematio diff).
        val (_, cleanedArgs) = plugin.uiModeResolver.resolveWithArgs(player, args)
        if (cleanedArgs.isEmpty()) {
            showUsage(player)
            return false
        }

        val idResult = InputValidator.validateSchematicId(cleanedArgs[0])
        if (idResult is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(idResult.message).color(NamedTextColor.RED))
            return false
        }
        val schematicId = (idResult as ValidationResult.Valid).value
        val message = cleanedArgs.drop(1).joinToString(" ").ifBlank { DEFAULT_MESSAGE }

        if (plugin.rateLimiter.tryAcquire(player.uniqueId) == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return true
        }

        resolver.beginCommit(player, schematicId, message)
        return true
    }

    private fun showUsage(player: Player) {
        val audience = player.audience()
        audience.sendMessage(Component.text("Commit Usage:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  /schematio commit <id> [message...]").color(NamedTextColor.YELLOW))
        audience.sendMessage(Component.text("Commits your clipboard to the schematic's branch.").color(NamedTextColor.GRAY))
        audience.sendMessage(Component.text("If someone committed first you'll resolve the conflict in-world.").color(NamedTextColor.GRAY))
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("--help").filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
