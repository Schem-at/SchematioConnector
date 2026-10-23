package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.utils.UIMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuickShareGetArgsTest {

    private fun parse(vararg args: String) = QuickShareGetArgs.parse(args)

    @Test
    fun `joins every word after the code into the password`() {
        assertEquals(QuickShareGetArgs(null, "qs_abc123", "correct horse battery"), parse("qs_abc123", "correct", "horse", "battery"))
    }

    @Test
    fun `strips trailing ui flags before joining the password`() {
        assertEquals(
            QuickShareGetArgs(UIMode.DIALOG, "qs_abc123", "two words"),
            parse("qs_abc123", "two", "words", "--dialog"),
        )
        assertEquals(QuickShareGetArgs(UIMode.CHAT, "qs_abc123", "pw"), parse("qs_abc123", "pw", "-c"))
    }

    @Test
    fun `accepts a leading ui flag`() {
        assertEquals(QuickShareGetArgs(UIMode.CHAT, "qs_abc123", "a b"), parse("--chat", "qs_abc123", "a", "b"))
    }

    @Test
    fun `keeps a flag-like word inside the password`() {
        assertEquals(QuickShareGetArgs(null, "qs_abc123", "my --chat pw"), parse("qs_abc123", "my", "--chat", "pw"))
    }

    @Test
    fun `keeps repeated spaces that Bukkit splits into empty args`() {
        // Bukkit splits on single spaces, so "a  b" arrives as ["a", "", "b"].
        assertEquals(QuickShareGetArgs(null, "qs_abc123", "a  b"), parse("qs_abc123", "a", "", "b"))
    }

    @Test
    fun `a blank password from an empty dialog field means no password`() {
        // The dialog template "/schematio quickshareget $(code) $(password) --dialog" with an empty field.
        assertEquals(QuickShareGetArgs(UIMode.DIALOG, "qs_abc123", null), parse("qs_abc123", "", "--dialog"))
        assertEquals(QuickShareGetArgs(null, "qs_abc123", null), parse("qs_abc123"))
    }

    @Test
    fun `no args yields no code`() {
        assertEquals(QuickShareGetArgs(UIMode.DIALOG, null, null), parse("--dialog"))
        assertEquals(QuickShareGetArgs(null, null, null), parse())
    }
}
