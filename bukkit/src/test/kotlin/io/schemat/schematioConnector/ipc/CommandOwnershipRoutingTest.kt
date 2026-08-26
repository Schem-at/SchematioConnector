package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.OpenUiSurface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandOwnershipRoutingTest {

    private val wants = Capabilities.WANTS_COMMAND_OWNERSHIP

    @Test
    fun `hand off only when attested AND client advertised ownership`() {
        // Full attested x capability matrix (spec: routing decision table).
        assertTrue(CommandOwnershipRouting.shouldHandOff(attested = true, clientFlags = wants))
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = true, clientFlags = 0))
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = false, clientFlags = wants))
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = false, clientFlags = 0))
        // Other flag bits alone never qualify (vanilla / other-capability clients).
        assertFalse(CommandOwnershipRouting.shouldHandOff(attested = true, clientFlags = Capabilities.DOWNLOAD_CMD))
    }

    @Test
    fun `menu-style invocations map to their surfaces`() {
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(emptyList()), // no-args help menu
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(listOf("list")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(listOf("search")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.UPLOAD),
            CommandOwnershipRouting.handoffFor(listOf("upload")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.SHARES),
            CommandOwnershipRouting.handoffFor(listOf("quickshare")),
        )
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.SETTINGS),
            CommandOwnershipRouting.handoffFor(listOf("settings")),
        )
        // Case-insensitive, like the SchematioCommand router itself.
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.BROWSE),
            CommandOwnershipRouting.handoffFor(listOf("LIST")),
        )
    }

    @Test
    fun `download with an id maps to SCHEMATIC_DETAIL with contextId`() {
        assertEquals(
            CommandOwnershipRouting.Handoff(OpenUiSurface.SCHEMATIC_DETAIL, "11111111-2222-3333-4444-555555555555"),
            CommandOwnershipRouting.handoffFor(listOf("download", "11111111-2222-3333-4444-555555555555")),
        )
    }

    @Test
    fun `argful and action invocations stay on the chat flow`() {
        assertNull(CommandOwnershipRouting.handoffFor(listOf("list", "castle")))       // search context
        assertNull(CommandOwnershipRouting.handoffFor(listOf("list", "2")))            // pagination context
        assertNull(CommandOwnershipRouting.handoffFor(listOf("upload", "my-build")))   // explicit named upload
        assertNull(CommandOwnershipRouting.handoffFor(listOf("quickshare", "3600")))   // explicit share action
        assertNull(CommandOwnershipRouting.handoffFor(listOf("settings", "ui", "chat")))
        assertNull(CommandOwnershipRouting.handoffFor(listOf("download")))             // no id -> usage message
        assertNull(CommandOwnershipRouting.handoffFor(listOf("download", "id", "x")))  // over-arity
        assertNull(CommandOwnershipRouting.handoffFor(listOf("download", "a".repeat(65)))) // over-long id
        assertNull(CommandOwnershipRouting.handoffFor(listOf("info")))                 // status output, not a menu
        assertNull(CommandOwnershipRouting.handoffFor(listOf("reload")))               // admin action
        assertNull(CommandOwnershipRouting.handoffFor(listOf("quickshareget", "qs_abc")))
        assertNull(CommandOwnershipRouting.handoffFor(listOf("diff")))                 // vcs, in-world
        assertNull(CommandOwnershipRouting.handoffFor(listOf("nonsense")))
    }
}
