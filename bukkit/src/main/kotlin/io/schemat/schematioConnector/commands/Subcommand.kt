package io.schemat.schematioConnector.commands

import org.bukkit.entity.Player

/**
 * Base interface for all subcommands of the /schematio command.
 *
 * Subcommands are registered with the [SchematioCommand] router, which handles
 * permission checking and dispatching based on the first argument.
 *
 * ## Permission Model
 *
 * Each subcommand specifies a base [permission] that controls whether the player
 * can see and execute the command. Some commands also have a tier permission
 * (e.g., `schematio.tier.chat`, `schematio.tier.floating`) that controls which
 * UI variant the player can use.
 *
 * ## Command Tiers
 *
 * Commands that support multiple UI tiers use a naming convention:
 * - Base name (e.g., `list`) - Chat tier (default, simplest)
 * - `-inv` suffix (e.g., `list-inv`) - Inventory GUI tier
 * - `-gui` suffix (e.g., `list-gui`) - Floating 3D GUI tier
 *
 * ## Implementation Guidelines
 *
 * 1. Always check for API availability (`plugin.httpUtil == null`) before making API calls
 * 2. Use Adventure Components for message formatting
 * 3. Return `true` if the command was handled (even if it failed with an error message)
 * 4. Return `false` only if the command syntax was wrong (triggers usage message)
 *
 * @see SchematioCommand The command router that dispatches to subcommands
 */
interface Subcommand {

    /**
     * The name of the subcommand used for routing.
     *
     * This is the first argument after `/schematio`. For example, if `name` is "upload",
     * the command would be invoked as `/schematio upload <args>`.
     *
     * For tiered commands, use suffixes:
     * - No suffix for chat tier (e.g., "list")
     * - "-inv" for inventory tier (e.g., "list-inv")
     * - "-gui" for floating GUI tier (e.g., "list-gui")
     */
    val name: String

    /**
     * The base permission required to execute this subcommand.
     *
     * This permission is checked by [SchematioCommand] before the subcommand
     * is even shown in help or tab completion. Common permissions:
     * - `schematio.use` - Basic access (info, settings)
     * - `schematio.list` - List and browse schematics
     * - `schematio.upload` - Upload schematics
     * - `schematio.download` - Download schematics
     * - `schematio.quickshare` - Create and retrieve quick share links
     * - `schematio.admin` - Administrative commands (reload, settoken, setpassword)
     *
     * Note: Tiered commands may also check a tier-specific permission
     * (e.g., `schematio.tier.floating`) within their [execute] method.
     */
    val permission: String

    /**
     * A brief description of what the subcommand does.
     *
     * This is displayed in help messages and should be concise (under 60 characters).
     * Focus on the action and any key differentiator for tiered variants.
     *
     * Examples:
     * - "Browse schematics in chat"
     * - "Browse schematics in an inventory GUI"
     * - "Upload your clipboard to schemat.io"
     */
    val description: String

    /**
     * Executes the subcommand logic.
     *
     * This method is called after permission checks pass. Implementations should:
     * 1. Validate any additional permissions (e.g., tier permissions)
     * 2. Check for required dependencies (API connection, WorldEdit, etc.)
     * 3. Parse and validate arguments
     * 4. Execute the command logic
     * 5. Send appropriate feedback messages to the player
     *
     * @param sender The player executing the command. Console execution is not supported.
     * @param args The arguments passed after the subcommand name.
     *             For `/schematio upload myfile`, args would be `["myfile"]`.
     * @return `true` if the command was handled (success or handled error),
     *         `false` if the syntax was incorrect (triggers usage message)
     */
    fun execute(sender: Player, args: Array<out String>): Boolean

    /**
     * Provides tab completion suggestions for this subcommand.
     *
     * Called when the player presses Tab while typing arguments for this subcommand.
     * The [args] array contains what the player has typed so far.
     *
     * @param sender The player requesting tab completion
     * @param args The current arguments being typed
     * @return A list of completion suggestions, or empty list for no suggestions
     */
    fun tabComplete(sender: Player, args: Array<out String>): List<String> = emptyList()
}