package io.schemat.schematioConnector.commands

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.vcs.Anchor
import io.schemat.connector.core.vcs.Choice
import io.schemat.connector.core.vcs.DiffKind
import io.schemat.connector.core.vcs.DiffSession
import io.schemat.connector.core.vcs.SessionMode
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.UIMode
import io.schemat.schematioConnector.vcs.DiffControls
import io.schemat.schematioConnector.vcs.DiffEngine
import io.schemat.schematioConnector.vcs.NucleationRuntime
import io.schemat.schematioConnector.vcs.ResolveHandler
import io.schemat.schematioConnector.vcs.render.VanillaDisplayRenderer
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * In-game diff viewer entry point + session controls (spec §5):
 *
 * - `/schematio diff <id> <verA> <verB>` - open a VIEW session anchored where the
 *   player stands
 * - `next` / `prev` / `goto <n>` - focus region
 * - `layers <added|removed|changed> <on|off>` - toggle change-kind layers
 * - `close` (VIEW) · `mine` / `theirs` / `done` / `abort` (RESOLVE)
 *
 * Controls are dual-mode: clickable chat components or a native dialog, chosen by
 * [io.schemat.schematioConnector.utils.UIModeResolver]. Resolve actions additionally
 * require [PERMISSION_COMMIT].
 */
class DiffSubcommand(private val plugin: SchematioConnector) : Subcommand {

    companion object {
        const val PERMISSION_COMMIT = "schematio.version.commit"
        private val CONTROL_VERBS = setOf("next", "prev", "goto", "layers", "close", "mine", "theirs", "done", "abort", "controls")
    }

    override val name = "diff"
    override val permission = "schematio.diff"
    override val description = "View schematic version diffs in-world"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()

        if (args.any { it == "--help" || it == "-h" }) {
            showUsage(player)
            return true
        }

        val resolver = plugin.uiModeResolver
        if (!resolver.hasAnyUIPermission(player)) {
            audience.sendMessage(Component.text("You don't have permission to use any UI mode.").color(NamedTextColor.RED))
            return true
        }
        val (uiMode, cleanedArgs) = resolver.resolveWithArgs(player, args)

        if (cleanedArgs.isEmpty()) {
            showUsage(player)
            return true
        }

        val first = cleanedArgs[0].lowercase()
        if (first in CONTROL_VERBS) {
            handleControl(player, first, cleanedArgs.drop(1), uiMode)
            return true
        }

        if (cleanedArgs.size < 3) {
            audience.sendMessage(Component.text("Usage: /schematio diff <id> <verA> <verB>").color(NamedTextColor.RED))
            return false
        }
        openViewSession(player, cleanedArgs[0], cleanedArgs[1], cleanedArgs[2], uiMode)
        return true
    }

    // ===========================================
    // OPENING A VIEW SESSION
    // ===========================================

    private fun openViewSession(player: Player, schematicId: String, verA: String, verB: String, uiMode: UIMode) {
        val audience = player.audience()

        if (!checkDiffPrerequisites(player)) return

        val api = plugin.versionApi
        if (api == null) {
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return
        }
        if (plugin.rateLimiter.tryAcquire(player.uniqueId) == null) {
            val waitTime = plugin.rateLimiter.getWaitTimeSeconds(player.uniqueId)
            audience.sendMessage(Component.text("Rate limited. Please wait ${waitTime}s before making another request.").color(NamedTextColor.RED))
            return
        }

        audience.sendMessage(Component.text("Computing diff $verA → $verB...").color(NamedTextColor.YELLOW))
        val anchor = Anchor(
            x = player.location.blockX,
            y = player.location.blockY,
            z = player.location.blockZ,
            world = player.world.name,
        )

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val baseBytes = when (val result = runBlocking { api.downloadVersion(schematicId, verA) }) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return@Runnable failSync(player, "version $verA", result.error)
            }
            val otherBytes = when (val result = runBlocking { api.downloadVersion(schematicId, verB) }) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return@Runnable failSync(player, "version $verB", result.error)
            }
            val regions = try {
                DiffEngine.computeRegions(baseBytes, otherBytes)
            } catch (e: Exception) {
                plugin.logger.warning("Diff computation failed for $schematicId $verA..$verB: ${e.message}")
                return@Runnable runSync {
                    player.audience().sendMessage(Component.text("Failed to diff those versions (unparseable schematic?).").color(NamedTextColor.RED))
                }
            }
            runSync {
                if (!player.isOnline) return@runSync
                val session = DiffSession(
                    schematicId = schematicId,
                    labels = verA to verB,
                    anchor = anchor,
                    regions = regions,
                    mode = SessionMode.VIEW,
                )
                plugin.diffSessions.open(player, session, VanillaDisplayRenderer(player, plugin.logger))
                player.audience().sendMessage(
                    Component.text("Diff $verA → $verB: ${regions.size} region(s), ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text(totalsLabel(session)).color(NamedTextColor.AQUA)),
                )
                if (regions.isEmpty()) {
                    player.audience().sendMessage(Component.text("The versions are identical.").color(NamedTextColor.GRAY))
                    plugin.diffSessions.close(player)
                } else {
                    sendFocusInfo(player, session)
                    showControls(player, session, uiMode)
                }
            }
        })
    }

    /** ProtocolLib + Nucleation guards shared by view and resolve entry points. */
    private fun checkDiffPrerequisites(player: Player): Boolean {
        val audience = player.audience()
        if (!plugin.hasProtocolLib) {
            audience.sendMessage(Component.text("The diff viewer needs ProtocolLib installed on this server.").color(NamedTextColor.RED))
            return false
        }
        if (!NucleationRuntime.available) {
            audience.sendMessage(Component.text(NucleationRuntime.UNAVAILABLE_MESSAGE).color(NamedTextColor.RED))
            return false
        }
        return true
    }

    private fun runSync(block: () -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { block() })
    }

    private fun failSync(player: Player, what: String, error: ApiError) = runSync {
        val message = when (error) {
            is ApiError.Offline -> "Could not connect to schemat.io API"
            is ApiError.NotFound -> "Could not find $what (is the schematic versioned?)"
            is ApiError.Forbidden -> "Access denied to $what"
            is ApiError.RateLimited -> "Rate limited by the API. Try again later."
            else -> "Failed to download $what"
        }
        player.audience().sendMessage(Component.text(message).color(NamedTextColor.RED))
    }

    // ===========================================
    // SESSION CONTROLS
    // ===========================================

    private fun handleControl(player: Player, verb: String, rest: List<String>, uiMode: UIMode) {
        val audience = player.audience()
        val active = plugin.diffSessions.current(player)
        if (active == null) {
            audience.sendMessage(Component.text("No active diff session. Start one with /schematio diff <id> <verA> <verB>").color(NamedTextColor.RED))
            return
        }
        val session = active.session

        when (verb) {
            "next" -> {
                session.next()
                active.renderer.focusRegion(session.cursor)
                sendFocusInfo(player, session)
            }
            "prev" -> {
                session.prev()
                active.renderer.focusRegion(session.cursor)
                sendFocusInfo(player, session)
            }
            "goto" -> {
                val index = rest.firstOrNull()?.toIntOrNull()
                if (index == null || !session.goto(index - 1)) {
                    audience.sendMessage(Component.text("Usage: /schematio diff goto <1-${session.regions.size}>").color(NamedTextColor.RED))
                    return
                }
                active.renderer.focusRegion(session.cursor)
                sendFocusInfo(player, session)
            }
            "layers" -> {
                val kind = when (rest.getOrNull(0)?.lowercase()) {
                    "added" -> DiffKind.ADDED
                    "removed" -> DiffKind.REMOVED
                    "changed" -> DiffKind.CHANGED
                    else -> null
                }
                val visible = when (rest.getOrNull(1)?.lowercase()) {
                    "on" -> true
                    "off" -> false
                    else -> null
                }
                if (kind == null || visible == null) {
                    audience.sendMessage(Component.text("Usage: /schematio diff layers <added|removed|changed> <on|off>").color(NamedTextColor.RED))
                    return
                }
                session.setLayerVisible(kind, visible)
                active.renderer.setLayerVisible(kind, visible)
                audience.sendMessage(
                    Component.text("Layer ${kind.name.lowercase()} ${if (visible) "shown" else "hidden"}.").color(NamedTextColor.GRAY),
                )
            }
            "close" -> {
                if (session.mode == SessionMode.RESOLVE) {
                    audience.sendMessage(Component.text("This is a conflict-resolution session - use done or abort.").color(NamedTextColor.RED))
                    return
                }
                plugin.diffSessions.close(player)
                audience.sendMessage(Component.text("Diff session closed.").color(NamedTextColor.GRAY))
            }
            "mine", "theirs" -> {
                if (!requireResolve(player, session)) return
                session.choose(if (verb == "mine") Choice.MINE else Choice.THEIRS)
                val label = if (verb == "mine") "MINE (your edit)" else "THEIRS (new head)"
                audience.sendMessage(
                    Component.text("Region ${session.cursor + 1}: ").color(NamedTextColor.GRAY)
                        .append(Component.text(label).color(if (verb == "mine") NamedTextColor.GREEN else NamedTextColor.AQUA)),
                )
                if (session.allDecided) {
                    audience.sendMessage(
                        Component.text("All regions decided - run ").color(NamedTextColor.GREEN)
                            .append(clickable("[Done]", "/schematio diff done", NamedTextColor.GOLD, "Compose and commit")),
                    )
                } else {
                    // jump to the next undecided region so mine/mine/mine walks the conflict
                    session.undecidedRegionIds.firstOrNull()?.let { nextId ->
                        session.goto(nextId)
                        active.renderer.focusRegion(session.cursor)
                        sendFocusInfo(player, session)
                    }
                }
            }
            "done" -> {
                if (!requireResolve(player, session)) return
                if (!session.allDecided) {
                    val undecided = session.undecidedRegionIds.joinToString(", ") { (it + 1).toString() }
                    audience.sendMessage(Component.text("Still undecided: region(s) $undecided. Choose mine or theirs for each.").color(NamedTextColor.RED))
                    return
                }
                val handler = active.attachment as? ResolveHandler
                if (handler == null) {
                    audience.sendMessage(Component.text("Nothing to finish - this session has no pending commit.").color(NamedTextColor.RED))
                    return
                }
                handler.onDone(player, session)
            }
            "abort" -> {
                if (session.mode != SessionMode.RESOLVE) {
                    audience.sendMessage(Component.text("Nothing to abort - use close for view sessions.").color(NamedTextColor.RED))
                    return
                }
                (active.attachment as? ResolveHandler)?.onAbort(player, session)
                plugin.diffSessions.close(player)
                audience.sendMessage(Component.text("Conflict resolution aborted. Your commit was not applied.").color(NamedTextColor.YELLOW))
            }
            "controls" -> showControls(player, session, uiMode)
        }
    }

    private fun requireResolve(player: Player, session: DiffSession): Boolean {
        val audience = player.audience()
        if (session.mode != SessionMode.RESOLVE) {
            audience.sendMessage(Component.text("This action is only available while resolving a conflict.").color(NamedTextColor.RED))
            return false
        }
        if (!player.hasPermission(PERMISSION_COMMIT)) {
            audience.sendMessage(Component.text("You don't have permission to commit ($PERMISSION_COMMIT).").color(NamedTextColor.RED))
            return false
        }
        return true
    }

    // ===========================================
    // INFO + CONTROL SURFACES (chat / dialog)
    // ===========================================

    private fun totalsLabel(session: DiffSession): String = DiffControls.totalsLabel(session)

    private fun sendFocusInfo(player: Player, session: DiffSession) = DiffControls.sendFocusInfo(player, session)

    private fun clickable(label: String, command: String, color: NamedTextColor, hover: String): Component =
        DiffControls.clickable(label, command, color, hover)

    private fun showControls(player: Player, session: DiffSession, uiMode: UIMode) {
        when (uiMode) {
            UIMode.CHAT -> DiffControls.sendChatControls(player, session)
            UIMode.DIALOG -> showControlsDialog(player, session)
        }
    }

    private fun showControlsDialog(player: Player, session: DiffSession) {
        val title = Component.text(if (session.mode == SessionMode.RESOLVE) "Resolve Conflict" else "Diff Viewer")
            .color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD)

        val body = listOf(
            DialogBody.plainMessage(
                Component.text("${session.labels.first} → ${session.labels.second} · ${session.regions.size} region(s) · ${totalsLabel(session)}")
                    .color(NamedTextColor.WHITE),
            ),
        )

        fun button(label: String, color: NamedTextColor, command: String) =
            ActionButton.builder(Component.text(label).color(color))
                .width(120)
                .action(DialogAction.staticAction(ClickEvent.runCommand(command)))
                .build()

        val buttons = buildList {
            add(button("◀ Prev", NamedTextColor.AQUA, "/schematio diff prev --dialog"))
            add(button("Next ▶", NamedTextColor.AQUA, "/schematio diff next --dialog"))
            if (session.mode == SessionMode.RESOLVE) {
                add(button("Mine", NamedTextColor.GREEN, "/schematio diff mine --dialog"))
                add(button("Theirs", NamedTextColor.AQUA, "/schematio diff theirs --dialog"))
                add(button("Done", NamedTextColor.GOLD, "/schematio diff done --dialog"))
                add(button("Abort", NamedTextColor.RED, "/schematio diff abort --dialog"))
            } else {
                add(button("Close", NamedTextColor.RED, "/schematio diff close --dialog"))
            }
        }

        val dialogBase = DialogBase.builder(title)
            .externalTitle(Component.text("Diff Viewer"))
            .body(body)
            .canCloseWithEscape(true)
            .build()

        try {
            val dialog = Dialog.create { builder ->
                builder.empty()
                    .base(dialogBase)
                    .type(DialogType.multiAction(buttons, null, 2))
            }
            player.showDialog(dialog)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to show diff controls dialog: ${e.message}")
            DiffControls.sendChatControls(player, session)
        }
    }

    private fun showUsage(player: Player) {
        val audience = player.audience()
        audience.sendMessage(Component.text("Diff Viewer Usage:").color(NamedTextColor.GOLD))
        audience.sendMessage(Component.text("  /schematio diff <id> <verA> <verB>").color(NamedTextColor.YELLOW)
            .append(Component.text(" Open an in-world diff of two versions").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  next · prev · goto <n>").color(NamedTextColor.AQUA)
            .append(Component.text(" Focus regions").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  layers <added|removed|changed> <on|off>").color(NamedTextColor.AQUA)
            .append(Component.text(" Toggle layers").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  close").color(NamedTextColor.AQUA)
            .append(Component.text(" End a view session").color(NamedTextColor.GRAY)))
        audience.sendMessage(Component.text("  mine · theirs · done · abort").color(NamedTextColor.AQUA)
            .append(Component.text(" Resolve a commit conflict").color(NamedTextColor.GRAY)))
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> {
        val partial = args.lastOrNull()?.lowercase() ?: return emptyList()
        return when (args.size) {
            1 -> (CONTROL_VERBS - "controls").sorted().filter { it.startsWith(partial) }
            2 -> when (args[0].lowercase()) {
                "layers" -> listOf("added", "removed", "changed").filter { it.startsWith(partial) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "layers" -> listOf("on", "off").filter { it.startsWith(partial) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
