package io.schemat.connector.core.routing

/** Restricts resolution to a side and/or tool (from command flags). */
data class DestinationFilter(val side: Side? = null, val tool: Tool? = null)

sealed interface Resolution {
    /** [fellBackFrom] is the preferred destination that was unavailable, only set when no filter was given. */
    data class Chosen(val destination: Destination, val fellBackFrom: Destination?) : Resolution

    data class Unavailable(val reason: String) : Resolution
}

object DestinationResolver {

    /**
     * Picks the first available destination in [order] that satisfies [filter].
     * [unavailable] maps each unavailable destination to its player-facing reason.
     */
    fun resolve(
        order: List<Destination>,
        filter: DestinationFilter?,
        unavailable: Map<Destination, String>,
    ): Resolution {
        val candidates = order.filter { d ->
            (filter?.side == null || d.side == filter.side) && (filter?.tool == null || d.tool == filter.tool)
        }
        if (candidates.isEmpty()) return Resolution.Unavailable("No destination matches those flags")

        val chosen = candidates.firstOrNull { it !in unavailable }
            ?: return Resolution.Unavailable(candidates.joinToString("; ") { unavailable.getValue(it) })

        val preferred = candidates.first()
        val fellBackFrom = if (filter == null && chosen != preferred) preferred else null
        return Resolution.Chosen(chosen, fellBackFrom)
    }
}
