package io.schemat.schematioConnector.commands

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.InputValidator
import io.schemat.schematioConnector.utils.ValidationResult
import io.schemat.schematioConnector.utils.WorldEditUtil
import io.schemat.schematioConnector.utils.parseJsonSafe
import io.schemat.schematioConnector.utils.safeGetString
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * Downloads a schematic from a quick share link into the player's clipboard.
 *
 * Quick shares are temporary, shareable links to schematics. This command
 * retrieves the schematic data and loads it into the player's WorldEdit clipboard.
 *
 * Usage: /schematio quickshareget <accessCode|url> [password]
 *
 * Arguments:
 * - accessCode|url: Either a quick share code (e.g., "qs_abc123xy") or
 *   a full URL (e.g., "https://schemat.io/share/qs_abc123xy")
 * - password: Optional password if the share is password-protected
 *
 * Examples:
 * - /schematio quickshareget qs_abc123xy
 * - /schematio quickshareget https://schemat.io/share/qs_abc123xy
 * - /schematio quickshareget qs_abc123xy mypassword
 *
 * Requires:
 * - schematio.quickshare permission
 * - WorldEdit plugin for clipboard operations
 * - Active API connection
 *
 * @property plugin The main plugin instance
 */
class QuickShareGetSubcommand(private val plugin: SchematioConnector) : Subcommand {

    private val gson = Gson()
    private val QUICKSHARE_ENDPOINT = "/plugin/quick-shares"

    override val name = "quickshareget"
    override val permission = "schematio.quickshare"
    override val description = "Download a schematic from a quick share link"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        // Check for API availability
        if (plugin.httpUtil == null) {
            audience.sendMessage(Component.text("Cannot download quick share - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }

        // Check for WorldEdit
        if (!plugin.hasWorldEdit) {
            audience.sendMessage(Component.text("WorldEdit is required for this command.").color(NamedTextColor.RED))
            return true
        }

        // Check rate limit
        val remaining = plugin.rateLimiter.tryAcquire(player.uniqueId)
        if (remaining == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return true
        }

        // Parse arguments
        if (args.isEmpty()) {
            audience.sendMessage(Component.text("Usage: /schematio quickshareget <accessCode|url> [password]").color(NamedTextColor.RED))
            return true
        }

        // Validate and extract access code from URL or use directly
        val codeValidation = InputValidator.validateQuickShareCode(args[0])
        if (codeValidation is ValidationResult.Invalid) {
            audience.sendMessage(Component.text(codeValidation.message).color(NamedTextColor.RED))
            return true
        }
        val accessCode = codeValidation.getOrNull()!!

        val password = if (args.size > 1) args[1] else null

        audience.sendMessage(Component.text("Downloading quick share...").color(NamedTextColor.YELLOW))

        // Download async
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val httpUtil = plugin.httpUtil!!

                // Build request body
                val requestBody = JsonObject().apply {
                    addProperty("player_uuid", player.uniqueId.toString())
                    if (password != null) {
                        addProperty("password", password)
                    }
                }

                val (statusCode, bytes, errorBody) = runBlocking {
                    httpUtil.sendPostRequestForBinary(
                        "$QUICKSHARE_ENDPOINT/$accessCode/download",
                        requestBody.toString()
                    )
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    when (statusCode) {
                        200 -> {
                            if (bytes != null) {
                                // Convert bytes to clipboard
                                val clipboard = WorldEditUtil.byteArrayToClipboard(bytes)
                                if (clipboard != null) {
                                    WorldEditUtil.setClipboard(player, clipboard)
                                    audience.sendMessage(Component.text("Quick share downloaded to clipboard!").color(NamedTextColor.GREEN))
                                    audience.sendMessage(Component.text("Use //paste to place it.").color(NamedTextColor.GRAY))
                                } else {
                                    audience.sendMessage(Component.text("Failed to parse schematic data.").color(NamedTextColor.RED))
                                }
                            } else {
                                audience.sendMessage(Component.text("No data received.").color(NamedTextColor.RED))
                            }
                        }
                        401 -> {
                            audience.sendMessage(Component.text("This share requires a password.").color(NamedTextColor.RED))
                            audience.sendMessage(Component.text("Usage: /schematio quickshareget $accessCode <password>").color(NamedTextColor.GRAY))
                        }
                        403 -> {
                            val msg = parseErrorMessage(errorBody) ?: "Access denied"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        404 -> {
                            audience.sendMessage(Component.text("Quick share not found.").color(NamedTextColor.RED))
                        }
                        410 -> {
                            val msg = parseErrorMessage(errorBody) ?: "This share has expired or been revoked"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        429 -> {
                            val msg = parseErrorMessage(errorBody) ?: "Download limit reached or rate limited"
                            audience.sendMessage(Component.text(msg).color(NamedTextColor.RED))
                        }
                        -1 -> {
                            audience.sendMessage(Component.text("Connection failed.").color(NamedTextColor.RED))
                        }
                        else -> {
                            audience.sendMessage(Component.text("Error downloading share (code: $statusCode)").color(NamedTextColor.RED))
                        }
                    }
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    audience.sendMessage(Component.text("Error: ${e.message}").color(NamedTextColor.RED))
                })
            }
        })

        return true
    }

    /**
     * Parse error message from JSON response body
     */
    private fun parseErrorMessage(errorBody: String?): String? {
        val json = parseJsonSafe(errorBody) ?: return null
        return json.safeGetString("message") ?: json.safeGetString("error")
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("<access_code>")
            2 -> listOf("<password>")
            else -> emptyList()
        }
    }
}
