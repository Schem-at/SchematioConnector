package io.schemat.connector.fabric.client.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.LiteralCommandNode
import java.lang.reflect.Proxy
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchematioClientCommandsTest {

    private val dispatcher = CommandDispatcher<FabricClientCommandSource>().also { SchematioClientCommands.register(it) }

    private val source = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(FabricClientCommandSource::class.java),
    ) { _, _, _ -> null } as FabricClientCommandSource

    /** The node names a full parse of [input] walks through, or null when the client cannot parse it. */
    private fun parsedPath(input: String): List<String>? {
        val parse = dispatcher.parse(input, source)
        if (parse.exceptions.isNotEmpty() || parse.reader.canRead() || parse.context.command == null) return null
        return parse.context.nodes.map { it.node.name }
    }

    @Test
    fun `plugin subcommands the mod does not know reach the server instead of failing on the client`() {
        // Fabric only falls back to the server for unknown root commands, so an unknown
        // /schematio subcommand used to stop at "Incorrect argument for command".
        assertEquals(listOf("schematio", "server"), parsedPath("schematio tool list"))
        assertEquals(listOf("schematio", "server"), parsedPath("schematio tool skin-statue username=jeb_"))
        assertEquals(listOf("schematio", "server"), parsedPath("schematio info"))
        assertEquals("schematio tool list", SchematioClientCommands.serverCommand("tool list"))
    }

    @Test
    fun `the mod's own subcommands still run on the client`() {
        assertEquals(listOf("schematio", "upload"), parsedPath("schematio upload"))
        assertEquals(listOf("schematio", "help"), parsedPath("schematio help"))
        assertEquals(listOf("schematio", "download", "args"), parsedPath("schematio download 44srfK --lm"))
        assertEquals(listOf("schematio"), parsedPath("schematio"))
        val root = dispatcher.root.getChild("schematio")
        assertTrue(root is LiteralCommandNode<*>)
        assertFalse(root.children.any { it.name == "tool" }, "tool belongs to the server plugin")
    }
}
