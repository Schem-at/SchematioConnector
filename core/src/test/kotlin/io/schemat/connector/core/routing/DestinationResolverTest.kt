package io.schemat.connector.core.routing

import io.schemat.connector.core.routing.Destination.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DestinationResolverTest {

    private val order = Destination.DEFAULT_ORDER

    @Test
    fun `first available wins without fallback note`() {
        assertEquals(Resolution.Chosen(SERVER_WORLDEDIT, null), DestinationResolver.resolve(order, null, emptyMap()))
    }

    @Test
    fun `falls back and remembers the preferred one`() {
        val r = DestinationResolver.resolve(order, null, mapOf(SERVER_WORLDEDIT to "x"))
        assertEquals(Resolution.Chosen(LOCAL_WORLDEDIT, SERVER_WORLDEDIT), r)
    }

    @Test
    fun `forced tool never falls back`() {
        val r = DestinationResolver.resolve(
            order,
            DestinationFilter(tool = Tool.WORLDEDIT),
            mapOf(SERVER_WORLDEDIT to "A", LOCAL_WORLDEDIT to "B"),
        )
        assertTrue(r is Resolution.Unavailable)
        assertTrue("A" in (r as Resolution.Unavailable).reason)
    }

    @Test
    fun `forced tool picks the first matching side`() {
        val r = DestinationResolver.resolve(order, DestinationFilter(tool = Tool.WORLDEDIT), emptyMap())
        assertEquals(Resolution.Chosen(SERVER_WORLDEDIT, null), r)
    }

    @Test
    fun `side and tool combine`() {
        val r = DestinationResolver.resolve(order, DestinationFilter(Side.LOCAL, Tool.WORLDEDIT), emptyMap())
        assertEquals(Resolution.Chosen(LOCAL_WORLDEDIT, null), r)
    }

    @Test
    fun `file is the last resort`() {
        val unavailable = mapOf(SERVER_WORLDEDIT to "a", LOCAL_WORLDEDIT to "b", LOCAL_LITEMATICA to "c")
        assertEquals(Resolution.Chosen(LOCAL_FILE, SERVER_WORLDEDIT), DestinationResolver.resolve(order, null, unavailable))
    }

    @Test
    fun `respects custom order`() {
        val custom = listOf(LOCAL_LITEMATICA, SERVER_WORLDEDIT, LOCAL_WORLDEDIT, LOCAL_FILE)
        assertEquals(Resolution.Chosen(LOCAL_LITEMATICA, null), DestinationResolver.resolve(custom, null, emptyMap()))
    }

    @Test
    fun `no matching candidates is unavailable`() {
        val r = DestinationResolver.resolve(listOf(LOCAL_FILE), DestinationFilter(side = Side.SERVER), emptyMap())
        assertEquals(Resolution.Unavailable("No destination matches those flags"), r)
    }
}
