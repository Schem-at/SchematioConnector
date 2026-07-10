package io.schemat.schematioConnector.vcs

import io.schemat.connector.core.modapi.ApiError
import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.core.modapi.CommitResult
import io.schemat.connector.core.modapi.VersionInfo
import io.schemat.connector.bukkit.adapter.BukkitPlayerStorage
import io.schemat.connector.core.vcs.Anchor
import io.schemat.connector.core.vcs.DiffSession
import io.schemat.connector.core.vcs.SessionMode
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.commands.audience
import io.schemat.schematioConnector.utils.WorldEditUtil
import io.schemat.schematioConnector.vcs.render.VanillaDisplayRenderer
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * The commit-time conflict flow (spec §6):
 *
 * 1. [beginCommit]: player's clipboard → `.schem` bytes, commit with
 *    `expected_head_version_id` from checkout tracking (fallback: current branch head).
 * 2. On 409 head_moved: download the new HEAD, diff it against the player's edit
 *    locally (Nucleation), open a RESOLVE session.
 * 3. `done` (all regions chosen) → [DiffCompose] → re-commit expecting the new HEAD.
 *    A second head_moved restarts the flow against the newer HEAD.
 *
 * When Nucleation or ProtocolLib is unavailable the conflict degrades to a
 * "resolve on the web" message with a link - up/download keep working.
 */
class ConflictResolver(private val plugin: SchematioConnector) {

    fun beginCommit(player: Player, schematicId: String, message: String) {
        val audience = player.audience()

        val api = plugin.versionApi
        if (api == null) {
            audience.sendMessage(Component.text("API not connected. Run /schematio reload after configuring token.").color(NamedTextColor.RED))
            return
        }
        val clipboard = WorldEditUtil.getClipboard(player)
        if (clipboard == null) {
            audience.sendMessage(Component.text("Your clipboard is empty. Copy something with //copy first.").color(NamedTextColor.RED))
            return
        }
        val myBytes = WorldEditUtil.clipboardToByteArray(clipboard)
        if (myBytes == null) {
            audience.sendMessage(Component.text("Could not serialize your clipboard.").color(NamedTextColor.RED))
            return
        }

        val checkout = CheckoutStore(BukkitPlayerStorage(player, plugin)).get()
            ?.takeIf { it.schematicId == schematicId }

        audience.sendMessage(Component.text("Committing to $schematicId...").color(NamedTextColor.YELLOW))
        async {
            var branchId = checkout?.branchId
            var expectedHead = checkout?.versionId
            if (branchId == null || expectedHead == null) {
                // Fallback per plan: no (complete) checkout - fetch the current head first.
                when (val result = runBlocking { api.branches(schematicId) }) {
                    is ApiResult.Success -> {
                        val branch = result.value.firstOrNull { it.isDefault } ?: result.value.firstOrNull()
                        if (branch == null) {
                            sync { player.audience().sendMessage(Component.text("This schematic has no branches - is it versioned?").color(NamedTextColor.RED)) }
                            return@async
                        }
                        if (branchId == null) branchId = branch.id
                        if (expectedHead == null) expectedHead = branch.headVersionId
                    }
                    is ApiResult.Failure -> {
                        sync { player.audience().sendMessage(Component.text(describe(result.error, "fetch branches")).color(NamedTextColor.RED)) }
                        return@async
                    }
                }
            }
            attemptCommit(player, schematicId, branchId!!, myBytes, message, expectedHead)
        }
    }

    /** Runs on an async thread. */
    private fun attemptCommit(
        player: Player,
        schematicId: String,
        branchId: String,
        myBytes: ByteArray,
        message: String,
        expectedHead: String?,
    ) {
        val api = plugin.versionApi ?: return
        val result = runBlocking {
            api.commit(
                schematicId = schematicId,
                branchId = branchId,
                schematicBytes = myBytes,
                message = message,
                expectedHeadVersionId = expectedHead,
                playerUuid = player.uniqueId.toString(),
            )
        }
        when (result) {
            is CommitResult.Ok -> sync {
                if (player.isOnline) {
                    CheckoutStore(BukkitPlayerStorage(player, plugin))
                        .set(Checkout(schematicId, result.version.id, branchId))
                    player.audience().sendMessage(
                        Component.text("Committed ").color(NamedTextColor.GREEN)
                            .append(Component.text("\"$message\"").color(NamedTextColor.WHITE))
                            .append(Component.text(" as version ${result.version.id}.").color(NamedTextColor.GREEN)),
                    )
                }
            }
            is CommitResult.HeadMoved -> startResolveSession(player, schematicId, branchId, myBytes, message, result.newHead)
            is CommitResult.Error -> sync {
                player.audience().sendMessage(Component.text(describe(result.error, "commit")).color(NamedTextColor.RED))
            }
        }
    }

    /** Runs on an async thread. Opens the RESOLVE session (or degrades to a web link). */
    private fun startResolveSession(
        player: Player,
        schematicId: String,
        branchId: String,
        myBytes: ByteArray,
        message: String,
        newHead: VersionInfo,
    ) {
        val api = plugin.versionApi ?: return

        if (!plugin.hasProtocolLib || !NucleationRuntime.available) {
            sync {
                val url = "${plugin.baseUrl}/schematics/$schematicId"
                player.audience().sendMessage(
                    Component.text("Your commit conflicts with newer changes, and in-game resolution is not available on this server.").color(NamedTextColor.RED),
                )
                player.audience().sendMessage(
                    Component.text("Resolve it on the web: ").color(NamedTextColor.GRAY)
                        .append(Component.text(url).color(NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url))),
                )
            }
            return
        }

        val headBytes = when (val result = runBlocking { api.downloadVersion(schematicId, newHead.id) }) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                sync { player.audience().sendMessage(Component.text(describe(result.error, "download the new head")).color(NamedTextColor.RED)) }
                return
            }
        }
        // Base = new HEAD (THEIRS), other = the player's edit (MINE): ADDED/CHANGED
        // regions carry the player's blocks, so a MINE choice applies their cells.
        val regions = try {
            DiffEngine.computeRegions(headBytes, myBytes)
        } catch (e: Exception) {
            plugin.logger.warning("Conflict diff failed for $schematicId: ${e.message}")
            sync { player.audience().sendMessage(Component.text("Failed to diff against the new head - resolve on the web.").color(NamedTextColor.RED)) }
            return
        }
        if (regions.isEmpty()) {
            // The new head already contains exactly the player's edit.
            sync {
                if (player.isOnline) {
                    CheckoutStore(BukkitPlayerStorage(player, plugin)).set(Checkout(schematicId, newHead.id, branchId))
                    player.audience().sendMessage(
                        Component.text("The branch head moved, but it already matches your edit - nothing left to commit.").color(NamedTextColor.GREEN),
                    )
                }
            }
            return
        }

        sync {
            if (!player.isOnline) return@sync
            val session = DiffSession(
                schematicId = schematicId,
                labels = "head" to "your edit",
                anchor = Anchor(
                    x = player.location.blockX,
                    y = player.location.blockY,
                    z = player.location.blockZ,
                    world = player.world.name,
                ),
                regions = regions,
                mode = SessionMode.RESOLVE,
            )
            val handler = object : ResolveHandler {
                override fun onDone(player: Player, session: DiffSession) {
                    val choices = session.choices.toMap()
                    plugin.diffSessions.close(player)
                    player.audience().sendMessage(Component.text("Composing your resolution and re-committing...").color(NamedTextColor.YELLOW))
                    async {
                        val composed = try {
                            DiffCompose.compose(headBytes, myBytes, regions, choices)
                        } catch (e: Exception) {
                            plugin.logger.warning("Compose failed for $schematicId: ${e.message}")
                            sync { player.audience().sendMessage(Component.text("Failed to compose the resolved schematic.").color(NamedTextColor.RED)) }
                            return@async
                        }
                        // Expect the head we just resolved against; another head_moved
                        // 409 restarts this flow against the even-newer head.
                        attemptCommit(player, schematicId, branchId, composed, message, newHead.id)
                    }
                }

                override fun onAbort(player: Player, session: DiffSession) {
                    // Session teardown + messaging handled by the diff command.
                }
            }
            plugin.diffSessions.open(player, session, VanillaDisplayRenderer(player, plugin.logger), handler)

            val author = newHead.authorName ?: "someone else"
            player.audience().sendMessage(
                Component.text("Your commit conflicts with newer change(s) by $author").color(NamedTextColor.RED)
                    .append(newHead.message?.let { Component.text(" (\"$it\")").color(NamedTextColor.GRAY) } ?: Component.empty())
                    .append(Component.text(" - ${regions.size} conflicting region(s), ${DiffControls.totalsLabel(session)}.").color(NamedTextColor.RED)),
            )
            player.audience().sendMessage(
                Component.text("Walk the regions and pick your edit or theirs for each:").color(NamedTextColor.GRAY),
            )
            DiffControls.sendFocusInfo(player, session)
            DiffControls.sendChatControls(player, session)
        }
    }

    private fun describe(error: ApiError, what: String): String = when (error) {
        is ApiError.Offline -> "Could not connect to schemat.io API"
        is ApiError.NotFound -> "Not found while trying to $what - is the schematic versioned?"
        is ApiError.Forbidden -> "Access denied trying to $what"
        is ApiError.Unauthorized -> "The community token was rejected trying to $what"
        is ApiError.Validation -> "Rejected: ${error.message}"
        is ApiError.RateLimited -> "Rate limited by the API. Try again later."
        else -> "Failed to $what"
    }

    private fun async(block: () -> Unit) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { block() })
    }

    private fun sync(block: () -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { block() })
    }
}
