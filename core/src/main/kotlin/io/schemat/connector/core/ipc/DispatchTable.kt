package io.schemat.connector.core.ipc

enum class DispatchMode { LOCAL, SERVER_COMMAND, PACKET }

enum class ClientAction { BROWSE, UPLOAD, DOWNLOAD, QUICKSHARE, QUICKSHARE_GET }

/**
 * Decides how a client `/schematio` action is handled. POC policy only; later this will
 * also consult client config and negotiated server capabilities (e.g. WANTS_COMMAND_OWNERSHIP).
 */
object DispatchTable {
    fun resolve(action: ClientAction, pluginPresent: Boolean): DispatchMode {
        if (!pluginPresent) return DispatchMode.LOCAL
        return when (action) {
            ClientAction.BROWSE -> DispatchMode.LOCAL
            ClientAction.DOWNLOAD,
            ClientAction.UPLOAD,
            ClientAction.QUICKSHARE,
            ClientAction.QUICKSHARE_GET -> DispatchMode.SERVER_COMMAND
        }
    }
}
