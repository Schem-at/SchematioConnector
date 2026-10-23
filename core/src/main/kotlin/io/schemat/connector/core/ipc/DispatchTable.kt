package io.schemat.connector.core.ipc

enum class DispatchMode { LOCAL, SERVER_COMMAND, PACKET }

enum class ClientAction { BROWSE, UPLOAD, DOWNLOAD, QUICKSHARE, QUICKSHARE_GET }

/**
 * Decides how a client `/schematio` action is handled. DOWNLOAD and QUICKSHARE_GET are always
 * LOCAL here: the client download router picks the destination. UPLOAD and QUICKSHARE are
 * forwarded as a server command only to a Paper plugin (a null platform is a pre-v2 hello,
 * which is Paper); Fabric servers keep them local.
 */
object DispatchTable {
    fun resolve(action: ClientAction, pluginPresent: Boolean, platform: IpcPlatform?): DispatchMode {
        if (!pluginPresent) return DispatchMode.LOCAL
        val paper = platform == null || platform == IpcPlatform.PAPER_PLUGIN
        return when (action) {
            ClientAction.BROWSE,
            ClientAction.DOWNLOAD,
            ClientAction.QUICKSHARE_GET -> DispatchMode.LOCAL
            ClientAction.UPLOAD,
            ClientAction.QUICKSHARE -> if (paper) DispatchMode.SERVER_COMMAND else DispatchMode.LOCAL
        }
    }
}
