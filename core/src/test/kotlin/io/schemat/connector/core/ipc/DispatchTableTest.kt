package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DispatchTableTest {

    private val forwardable = listOf(ClientAction.UPLOAD, ClientAction.QUICKSHARE)
    private val routed = listOf(ClientAction.DOWNLOAD, ClientAction.QUICKSHARE_GET)

    @Test
    fun `fabric server keeps upload local`() {
        for (a in ClientAction.entries) {
            assertEquals(
                DispatchMode.LOCAL,
                DispatchTable.resolve(a, pluginPresent = true, platform = IpcPlatform.FABRIC_SERVER),
                a.name,
            )
        }
    }

    @Test
    fun `paper forwards upload and quickshare`() {
        for (a in forwardable) {
            assertEquals(
                DispatchMode.SERVER_COMMAND,
                DispatchTable.resolve(a, pluginPresent = true, platform = IpcPlatform.PAPER_PLUGIN),
                a.name,
            )
        }
        assertEquals(
            DispatchMode.LOCAL,
            DispatchTable.resolve(ClientAction.BROWSE, pluginPresent = true, platform = IpcPlatform.PAPER_PLUGIN),
        )
    }

    @Test
    fun `legacy null platform forwards like paper`() {
        for (a in forwardable) {
            assertEquals(DispatchMode.SERVER_COMMAND, DispatchTable.resolve(a, pluginPresent = true, platform = null), a.name)
        }
    }

    @Test
    fun `download is never dispatched here`() {
        for (platform in IpcPlatform.entries + listOf<IpcPlatform?>(null)) {
            for (a in routed) {
                assertEquals(DispatchMode.LOCAL, DispatchTable.resolve(a, pluginPresent = true, platform = platform), "$a $platform")
            }
        }
    }

    @Test
    fun `no plugin is local`() {
        for (a in ClientAction.entries) {
            assertEquals(DispatchMode.LOCAL, DispatchTable.resolve(a, pluginPresent = false, platform = null), a.name)
        }
    }
}
