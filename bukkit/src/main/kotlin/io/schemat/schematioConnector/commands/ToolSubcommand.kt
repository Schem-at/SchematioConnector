package io.schemat.schematioConnector.commands

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.tools.RunRequest
import io.schemat.connector.core.tools.RunStatus
import io.schemat.connector.core.tools.ToolArgs
import io.schemat.connector.core.tools.ToolCatalogueCache
import io.schemat.connector.core.tools.ToolDefinition
import io.schemat.connector.core.tools.ToolErrors
import io.schemat.connector.core.tools.ToolInputValue
import io.schemat.connector.core.tools.ToolResult
import io.schemat.connector.core.tools.ToolRunOutcome
import io.schemat.connector.core.tools.ToolRunPoller
import io.schemat.connector.core.tools.ToolRunText
import io.schemat.connector.core.tools.ToolsApi
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a schemat.io remote tool (on the player's WorldEdit clipboard when it takes a schematic) and puts the result
 * back into their clipboard.
 *
 * Usage:
 * - `/schematio tool` or `/schematio tool list`: tools this player may run
 * - `/schematio tool <name> [key=value ...]`: run a tool (see [ToolArgs] for values)
 * - `/schematio tool cancel`: cancel the player's running tool
 *
 * The plugin's community token vouches for the player: every request carries their uuid
 * as the actor. schemat.io allows the run when the token has `run_tools` and the player is
 * an active member of the community whose role grants `run-remote-tools`.
 */
class ToolSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "tool"
    override val permission = "schematio.tool"
    override val description = "Run a schemat.io tool; the result goes into your clipboard"

    private val catalogue = ToolCatalogueCache()

    /** One running tool per player; the flag asks its poller to cancel. */
    private val active = ConcurrentHashMap<UUID, AtomicBoolean>()

    private companion object {
        /** The contract's input limit, in decoded bytes per file. */
        const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    }

    override fun execute(sender: Player, args: Array<out String>): Boolean {
        val audience = sender.audience()
        val api = plugin.toolsApi
        if (api == null) {
            audience.sendMessage(Component.text("Cannot run tools - not connected to schemat.io").color(NamedTextColor.RED))
            audience.sendMessage(Component.text("Configure a community token in config.yml and run /schematio reload").color(NamedTextColor.GRAY))
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            null, "list" -> {
                listTools(sender, api)
                return true
            }
            "cancel" -> {
                val flag = active[sender.uniqueId]
                if (flag == null) {
                    audience.sendMessage(Component.text("You have no tool running.").color(NamedTextColor.GRAY))
                } else {
                    flag.set(true)
                    audience.sendMessage(Component.text("Cancelling...").color(NamedTextColor.YELLOW))
                }
                return true
            }
        }
        if (active.containsKey(sender.uniqueId)) {
            audience.sendMessage(Component.text("A tool is already running. Use /schematio tool cancel to stop it.").color(NamedTextColor.RED))
            return true
        }
        if (plugin.rateLimiter.tryAcquire(sender.uniqueId) == null) {
            val wait = plugin.rateLimiter.getWaitTimeSeconds(sender.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${wait}s before making another request.").color(NamedTextColor.RED))
            return true
        }

        val toolId = args[0]
        val toolArgs = args.drop(1)
        val cancel = AtomicBoolean(false)
        active[sender.uniqueId] = cancel
        async {
            val actor = sender.uniqueId.toString()
            val found = catalogue.lookup(actor, toolId) { api.catalogue(actor) }
            sync {
                val def = (found as? ApiResult.Success)?.value
                when {
                    found is ApiResult.Failure -> {
                        audience.sendMessage(Component.text(ToolErrors.userMessage(found.error)).color(NamedTextColor.RED))
                        active.remove(sender.uniqueId)
                    }
                    def == null -> {
                        audience.sendMessage(Component.text("Unknown tool '$toolId'. See /schematio tool list").color(NamedTextColor.RED))
                        active.remove(sender.uniqueId)
                    }
                    else -> prepareAndRun(sender, api, def, toolArgs, cancel)
                }
            }
        }
        return true
    }

    /** Main thread: parse the arguments, read the clipboard if needed, then run asynchronously. */
    private fun prepareAndRun(player: Player, api: ToolsApi, def: ToolDefinition, args: List<String>, cancel: AtomicBoolean) {
        val audience = player.audience()
        fun fail(message: String) {
            audience.sendMessage(Component.text(message).color(NamedTextColor.RED))
            active.remove(player.uniqueId)
        }
        val inputs = when (val parsed = ToolArgs.parse(def, args)) {
            is ToolArgs.Result.Error -> return fail(parsed.message)
            is ToolArgs.Result.Ok -> parsed.inputs
        }.toMutableMap()

        if (inputs.values.any { it == ToolInputValue.ClipboardPlaceholder }) {
            val clipboard = WorldEditUtil.getClipboard(player)
                ?: return fail("Your WorldEdit clipboard is empty. Copy something first (//copy).")
            val bytes = WorldEditUtil.clipboardToByteArray(clipboard)
                ?: return fail("Could not read your WorldEdit clipboard.")
            if (bytes.size > MAX_INPUT_BYTES) return fail("Your clipboard is too large for remote tools (16 MB max).")
            for ((key, value) in inputs.entries.toList()) {
                if (value == ToolInputValue.ClipboardPlaceholder) inputs[key] = ToolInputValue.Upload(bytes, "clipboard.schem")
            }
        }

        audience.sendMessage(Component.text("Running ${def.name} on schemat.io...").color(NamedTextColor.YELLOW))
        val actor = player.uniqueId.toString()
        async {
            try {
                val started = api.startRun(def.id, RunRequest(inputs), actor)
                val run = when (started) {
                    is ApiResult.Failure -> return@async sync { audience.sendMessage(Component.text(ToolErrors.userMessage(started.error)).color(NamedTextColor.RED)) }
                    is ApiResult.Success -> started.value
                }
                plugin.logger.info("Tool run ${run.id} (${def.id}) started for ${player.name}")
                var lastLine = ""
                val outcome = ToolRunPoller(api).await(run.id, def.timeoutSeconds, actor, isCancelled = { cancel.get() }) { update ->
                    val line = ToolRunText.progressLine(update)
                    if (line != lastLine) {
                        lastLine = line
                        sync { if (player.isOnline) audience.sendActionBar(Component.text(line).color(NamedTextColor.AQUA)) }
                    }
                }
                deliver(player, api, def, outcome, actor)
            } finally {
                active.remove(player.uniqueId)
            }
        }
    }

    /** Async: turn the outcome into chat feedback, loading a schematic result into the clipboard. */
    private suspend fun deliver(player: Player, api: ToolsApi, def: ToolDefinition, outcome: ToolRunOutcome, actor: String) {
        val audience = player.audience()
        fun say(text: String, color: NamedTextColor) = sync { audience.sendMessage(Component.text(text).color(color)) }
        when (outcome) {
            is ToolRunOutcome.Failed -> say(ToolErrors.userMessage(outcome.error), NamedTextColor.RED)
            is ToolRunOutcome.StillRunning ->
                say("${def.name} is still running on schemat.io. Stopped waiting; check your runs on schemat.io.", NamedTextColor.YELLOW)
            is ToolRunOutcome.Finished -> {
                val run = outcome.run
                if (run.status != RunStatus.DONE) {
                    return say(ToolRunText.progressLine(run), if (run.status == RunStatus.CANCELLED) NamedTextColor.GRAY else NamedTextColor.RED)
                }
                val output = when (val result = ToolResult.pick(run)) {
                    is ToolResult.QuickShare -> result.output
                    is ToolResult.Download -> result.output
                    is ToolResult.SaveOnly -> return say("${def.name} finished, but its result (${result.output.kind}) can only be viewed on schemat.io.", NamedTextColor.YELLOW)
                    ToolResult.None -> return say("${def.name} finished without a result.", NamedTextColor.YELLOW)
                }
                when (val bytes = api.downloadOutput(run, output, actor)) {
                    is ApiResult.Failure -> say(ToolErrors.userMessage(bytes.error), NamedTextColor.RED)
                    is ApiResult.Success -> sync {
                        if (!player.isOnline) return@sync
                        val clipboard = WorldEditUtil.byteArrayToClipboard(bytes.value)
                        if (clipboard == null) {
                            audience.sendMessage(Component.text("${def.name} returned a schematic WorldEdit could not read.").color(NamedTextColor.RED))
                        } else {
                            WorldEditUtil.setClipboard(player, clipboard)
                            audience.sendMessage(Component.text("${def.name} finished. The result is in your clipboard.").color(NamedTextColor.GREEN))
                            audience.sendMessage(Component.text("Use //paste to place it.").color(NamedTextColor.GRAY))
                        }
                    }
                }
            }
        }
    }

    private fun listTools(player: Player, api: ToolsApi) {
        val audience = player.audience()
        val actor = player.uniqueId.toString()
        async {
            val result = api.catalogue(actor)
            sync {
                when (result) {
                    is ApiResult.Failure -> audience.sendMessage(Component.text(ToolErrors.userMessage(result.error)).color(NamedTextColor.RED))
                    is ApiResult.Success -> {
                        catalogue.put(actor, result.value)
                        if (result.value.isEmpty()) {
                            audience.sendMessage(Component.text("No tools are available to you.").color(NamedTextColor.GRAY))
                            audience.sendMessage(Component.text("Your community role needs the \"run remote tools\" permission on schemat.io.").color(NamedTextColor.GRAY))
                            return@sync
                        }
                        audience.sendMessage(Component.text("Tools you can run (${result.value.size}):").color(NamedTextColor.GOLD))
                        for (tool in result.value) {
                            // The typeable name, unless another tool shares it; then the id.
                            val typed = tool.commandName.takeIf { catalogue.find(actor, it) == tool } ?: tool.id
                            val line = Component.text("  $typed").color(NamedTextColor.AQUA)
                                .append(Component.text(" - ${tool.name}").color(NamedTextColor.WHITE))
                                .append(Component.text(tool.summary?.let { ": $it" } ?: "").color(NamedTextColor.GRAY))
                                .clickEvent(ClickEvent.suggestCommand("/schematio tool $typed "))
                            audience.sendMessage(line)
                        }
                        audience.sendMessage(Component.text("Click a tool, or: /schematio tool <name> [key=value ...]").color(NamedTextColor.GRAY))
                    }
                }
            }
        }
    }

    override fun tabComplete(sender: Player, args: Array<out String>): List<String> {
        val actor = sender.uniqueId.toString()
        val tools = catalogue.get(actor)
        if (tools == null) {
            // Warm the cache for the next Tab press; never block the main thread.
            plugin.toolsApi?.let { api -> async { api.catalogue(actor).valueOrNull()?.let { catalogue.put(actor, it) } } }
        }
        if (args.size <= 1) {
            val prefix = args.firstOrNull().orEmpty()
            return (listOf("list", "cancel") + catalogue.commandNames(actor)).filter { it.startsWith(prefix, ignoreCase = true) }
        }
        val def = catalogue.find(actor, args[0]) ?: return emptyList()
        return ToolArgs.complete(def, args.drop(1))
    }

    private fun async(block: suspend () -> Unit) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                runBlocking { block() }
            } catch (e: Exception) {
                plugin.logger.warning("Tool command failed: ${e.message}")
            }
        })
    }

    private fun sync(block: () -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { block() })
    }
}
