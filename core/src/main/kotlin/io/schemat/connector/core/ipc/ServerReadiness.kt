package io.schemat.connector.core.ipc

/** Server-side readiness to load schematics into a player's WorldEdit clipboard. */
object ServerReadiness {

    /** True only when WorldEdit is present, a token is configured, downloads are enabled and the player may download. */
    fun serverWorldEdit(
        worldEdit: Boolean,
        tokenConfigured: Boolean,
        downloadEnabled: Boolean,
        hasPermission: Boolean,
    ): Boolean = worldEdit && tokenConfigured && downloadEnabled && hasPermission

    /** Player-facing 404 copy shared by both server implementations. */
    fun notFoundMessage(communityName: String?): String {
        val community = if (communityName.isNullOrBlank()) "this server's" else "the $communityName"
        return "Not found (404). Check the ID. If the schematic is private, its owner must " +
            "join $community community on schemat.io for this server to load it."
    }
}
