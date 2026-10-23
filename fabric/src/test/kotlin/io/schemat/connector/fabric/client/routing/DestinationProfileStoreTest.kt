package io.schemat.connector.fabric.client.routing

import io.schemat.connector.core.routing.Destination
import io.schemat.connector.core.routing.DestinationProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DestinationProfileStoreTest {

    private val disk = mutableMapOf<String, String>()
    private val reads = mutableListOf<String>()
    private val writes = mutableListOf<Pair<String, String?>>()
    private var diskWritable = true

    private fun store() = DestinationProfileStore(
        read = { name -> reads += name; disk[name] },
        write = { name, value ->
            writes += name to value
            if (diskWritable) {
                if (value == null) disk.remove(name) else disk[name] = value
            }
            diskWritable
        },
    )

    private val custom = listOf(
        Destination.LOCAL_FILE, Destination.LOCAL_LITEMATICA, Destination.SERVER_WORLDEDIT, Destination.LOCAL_WORLDEDIT,
    )

    @Test
    fun `reads each key from disk once, however often it is rendered`() {
        disk["download_destinations.server:a"] = DestinationProfiles.encode(custom)
        val s = store()
        repeat(100) {
            assertEquals(custom, s.order("server:a"))
            assertTrue(s.hasOwnProfile("server:a"))
        }
        assertEquals(listOf("download_destinations.server:a"), reads)
    }

    @Test
    fun `absent profiles are cached too and fall back to the default then the built-in order`() {
        val s = store()
        repeat(50) { assertEquals(Destination.DEFAULT_ORDER, s.order("server:b")) }
        assertFalse(s.hasOwnProfile("server:b"))
        assertEquals(
            listOf("download_destinations.server:b", "download_destinations.${DestinationProfiles.DEFAULT_KEY}"),
            reads,
        )
    }

    @Test
    fun `save writes through and updates the cache without re-reading`() {
        val s = store()
        assertEquals(Destination.DEFAULT_ORDER, s.order("server:c"))
        reads.clear()

        assertTrue(s.save("server:c", custom))
        assertEquals(listOf<Pair<String, String?>>("download_destinations.server:c" to DestinationProfiles.encode(custom)), writes)
        assertEquals(custom, s.order("server:c"))
        assertTrue(s.hasOwnProfile("server:c"))

        assertTrue(s.save("server:c", null)) // reset
        assertFalse(s.hasOwnProfile("server:c"))
        assertEquals(Destination.DEFAULT_ORDER, s.order("server:c"))
        assertEquals(emptyList<String>(), reads)
    }

    @Test
    fun `saving the default updates worlds that fall back to it`() {
        val s = store()
        assertEquals(Destination.DEFAULT_ORDER, s.order("server:d"))
        s.save(DestinationProfiles.DEFAULT_KEY, custom)
        assertEquals(custom, s.order("server:d"))
    }

    @Test
    fun `a failed save reports false and keeps showing what is on disk`() {
        val s = store()
        assertEquals(Destination.DEFAULT_ORDER, s.order("server:f"))
        diskWritable = false

        assertFalse(s.save("server:f", custom))

        assertFalse(s.hasOwnProfile("server:f"))
        assertEquals(Destination.DEFAULT_ORDER, s.order("server:f"))
    }

    @Test
    fun `clear forces a fresh read`() {
        val s = store()
        s.order("server:e")
        disk["download_destinations.server:e"] = DestinationProfiles.encode(custom)
        s.clear()
        assertEquals(custom, s.order("server:e"))
    }
}
