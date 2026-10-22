package io.schemat.connector.core.routing

/** Where a downloaded schematic is loaded: the remote server, or the player's own machine. */
enum class Side { SERVER, LOCAL }

/** What receives the schematic. */
enum class Tool { WORLDEDIT, LITEMATICA, FILE }

/** A place a schematic download can go, in default preference order. */
enum class Destination(val side: Side, val tool: Tool, val label: String) {
    SERVER_WORLDEDIT(Side.SERVER, Tool.WORLDEDIT, "WorldEdit (server)"),
    LOCAL_WORLDEDIT(Side.LOCAL, Tool.WORLDEDIT, "WorldEdit (local)"),
    LOCAL_LITEMATICA(Side.LOCAL, Tool.LITEMATICA, "Litematica"),
    LOCAL_FILE(Side.LOCAL, Tool.FILE, "Save to disk"),
    ;

    companion object {
        val DEFAULT_ORDER: List<Destination> = entries
    }
}
