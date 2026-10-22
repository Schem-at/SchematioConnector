package io.schemat.connector.core.routing

import io.schemat.connector.core.ipc.IpcPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ServerDownloadCommandTest {

    @Test
    fun `paper schematic`() {
        assertEquals(
            "schematio download 44srfK",
            ServerDownloadCommand.build(IpcPlatform.PAPER_PLUGIN, DownloadKind.SCHEMATIC, "44srfK", null),
        )
    }

    @Test
    fun `legacy null platform is paper quick share`() {
        assertEquals(
            "schematio quickshareget qs_abc123 pw",
            ServerDownloadCommand.build(null, DownloadKind.QUICK_SHARE, "qs_abc123", "pw"),
        )
    }

    @Test
    fun `fabric quick share gets qs prefix`() {
        assertEquals(
            "schematio download qs_abc12345",
            ServerDownloadCommand.build(IpcPlatform.FABRIC_SERVER, DownloadKind.QUICK_SHARE, "abc12345", null),
        )
    }

    @Test
    fun `fabric keeps existing prefix`() {
        assertEquals(
            "schematio download qs_abc12345",
            ServerDownloadCommand.build(IpcPlatform.FABRIC_SERVER, DownloadKind.QUICK_SHARE, "qs_abc12345", null),
        )
    }

    @Test
    fun `fabric quick share keeps password`() {
        assertEquals(
            "schematio download qs_abc123 pw",
            ServerDownloadCommand.build(IpcPlatform.FABRIC_SERVER, DownloadKind.QUICK_SHARE, "qs_abc123", "pw"),
        )
    }

    @Test
    fun `paper quick share without password`() {
        assertEquals(
            "schematio quickshareget qs_abc123",
            ServerDownloadCommand.build(IpcPlatform.PAPER_PLUGIN, DownloadKind.QUICK_SHARE, "qs_abc123", null),
        )
    }

    @Test
    fun `build rejects invalid fabric quick share`() {
        assertFailsWith<IllegalArgumentException> {
            ServerDownloadCommand.build(IpcPlatform.FABRIC_SERVER, DownloadKind.QUICK_SHARE, "bad!", null)
        }
    }
}
