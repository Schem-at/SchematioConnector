package io.schemat.schematioConnector.vcs

import io.schemat.connector.core.vcs.DiffSession
import org.bukkit.entity.Player

/**
 * Contract between the diff controls (`/schematio diff done|abort`) and the flow that
 * opened a RESOLVE session. The conflict-resolution flow attaches its implementation
 * to [DiffSessionManager.Active.attachment]; `done` fires [onDone] only once every
 * region has a choice.
 */
interface ResolveHandler {

    /** All regions decided - compose and re-commit. The session is still open. */
    fun onDone(player: Player, session: DiffSession)

    /** Player abandoned the resolution. Called before the session is closed. */
    fun onAbort(player: Player, session: DiffSession)
}
