package io.schemat.connector.core.routing

import io.schemat.connector.core.routing.Destination.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DestinationProfilesTest {

    @Test
    fun `round trip default order`() {
        assertEquals(
            Destination.DEFAULT_ORDER,
            DestinationProfiles.decode(DestinationProfiles.encode(Destination.DEFAULT_ORDER)),
        )
    }

    @Test
    fun `decode tolerates unknown and missing entries`() {
        assertEquals(
            listOf(LOCAL_LITEMATICA, SERVER_WORLDEDIT, LOCAL_WORLDEDIT, LOCAL_FILE),
            DestinationProfiles.decode("LOCAL_LITEMATICA,BOGUS,LOCAL_LITEMATICA"),
        )
    }

    @Test
    fun `decode blank is null`() {
        assertNull(DestinationProfiles.decode(null))
        assertNull(DestinationProfiles.decode(""))
        assertNull(DestinationProfiles.decode("   "))
    }

    @Test
    fun `server key normalizes`() {
        assertEquals("server:play.example.com:25565", DestinationProfiles.serverKey(" Play.Example.com:25565 "))
    }
}
