package io.schemat.connector.core.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DispatchTableTest {

    @Test
    fun `everything is local when no plugin`() {
        for (a in ClientAction.entries) {
            assertEquals(DispatchMode.LOCAL, DispatchTable.resolve(a, pluginPresent = false), a.name)
        }
    }

    @Test
    fun `browse stays local when plugin present`() {
        assertEquals(DispatchMode.LOCAL, DispatchTable.resolve(ClientAction.BROWSE, pluginPresent = true))
    }

    @Test
    fun `server-backed actions forward when plugin present`() {
        for (a in listOf(ClientAction.DOWNLOAD, ClientAction.UPLOAD, ClientAction.QUICKSHARE, ClientAction.QUICKSHARE_GET)) {
            assertEquals(DispatchMode.SERVER_COMMAND, DispatchTable.resolve(a, pluginPresent = true), a.name)
        }
    }
}
