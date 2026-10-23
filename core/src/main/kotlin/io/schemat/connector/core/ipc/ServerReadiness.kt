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

    private val MISSING_ID = Regex("\"errors\"\\s*:\\s*\\{[^}]*\"id\"\\s*:")

    /**
     * True when a download failed because the schematic is unknown or hidden. The backend
     * answers an unknown id with a 422 on the `id` field rather than a 404.
     */
    fun isMissingSchematic(status: Int, errorBody: String?): Boolean =
        status == 404 || (status == 422 && errorBody != null && MISSING_ID.containsMatchIn(errorBody))

    /** Player-facing 404 copy shared by both server implementations. */
    fun notFoundMessage(communityName: String?): String {
        val community = if (communityName.isNullOrBlank()) "this server's" else "the $communityName"
        return "Not found (404). Check the ID. If the schematic is private, its owner must " +
            "join $community community on schemat.io for this server to load it."
    }
}
