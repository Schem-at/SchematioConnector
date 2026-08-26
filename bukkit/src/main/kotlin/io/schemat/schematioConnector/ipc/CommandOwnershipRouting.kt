package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.OpenUi
import io.schemat.connector.core.ipc.OpenUiSurface

/**
 * Pure decision logic for the command-ownership handoff (spec D): whether a player's
 * session qualifies, and which /schematio invocation maps to which OPEN_UI surface.
 * Kept Bukkit-free so it runs under plain JUnit.
 */
object CommandOwnershipRouting {

    /** An OPEN_UI to send instead of a chat menu. [contextId] only for SCHEMATIC_DETAIL. */
    data class Handoff(val surface: OpenUiSurface, val contextId: String = "")

    /**
     * Server-side ownership gate (attested x capability matrix): the connection must be
     * attested (ATTEST relayed this session) AND the client's HELLO_CLIENT flags must
     * advertise WANTS_COMMAND_OWNERSHIP. Vanilla clients send no flags -> never hand off,
     * so their command behavior is untouched by construction.
     */
    fun shouldHandOff(attested: Boolean, clientFlags: Int): Boolean =
        attested && Capabilities.has(clientFlags, Capabilities.WANTS_COMMAND_OWNERSHIP)

    /**
     * OPEN_UI surface for a /schematio invocation, or null to run the classic chat flow.
     * Only menu-style invocations hand off:
     * - ``            (no args, help menu)  -> BROWSE (the client UI hub)
     * - `list`/`search` (bare)              -> BROWSE (argful forms carry query/page
     *                                          context OPEN_UI cannot convey -> chat)
     * - `upload`      (bare)                -> UPLOAD (argful `upload <name>` is an
     *                                          explicit server-clipboard action -> chat)
     * - `quickshare`  (bare)                -> SHARES
     * - `settings`    (bare)                -> SETTINGS
     * - `download <id>`                     -> SCHEMATIC_DETAIL with contextId (this
     *                                          repo's "here <id>"-style detail command)
     */
    fun handoffFor(args: List<String>): Handoff? {
        if (args.isEmpty()) return Handoff(OpenUiSurface.BROWSE)
        val sub = args[0].lowercase()
        val bare = args.size == 1
        return when {
            bare && (sub == "list" || sub == "search") -> Handoff(OpenUiSurface.BROWSE)
            bare && sub == "upload" -> Handoff(OpenUiSurface.UPLOAD)
            bare && sub == "quickshare" -> Handoff(OpenUiSurface.SHARES)
            bare && sub == "settings" -> Handoff(OpenUiSurface.SETTINGS)
            sub == "download" && args.size == 2 &&
                args[1].isNotEmpty() && args[1].length <= OpenUi.MAX_CONTEXT_CHARS ->
                Handoff(OpenUiSurface.SCHEMATIC_DETAIL, args[1])
            else -> null
        }
    }
}
