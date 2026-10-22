package io.schemat.connector.fabric.worldedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Stands in for WorldEdit 7.3's FabricAdapter: a static `adaptPlayer(ServerPlayer)`. */
object LegacyFabricAdapter {
    @JvmStatic
    fun adaptPlayer(player: Any): Any = "legacy:$player"
}

/** Stands in for WorldEdit 7.4's FabricAdapter: `get().fromNativePlayer(ServerPlayer)`. */
class ModernFabricAdapter {
    fun fromNativePlayer(player: Any): Any = "modern:$player"

    companion object {
        @JvmStatic
        fun get(): ModernFabricAdapter = ModernFabricAdapter()
    }
}

class UnknownFabricAdapter

class WorldEditPlayerAdapterTest {

    @Test
    fun `adapts through the static adaptPlayer of WorldEdit 7_3`() {
        val adapt = WorldEditPlayerAdapter.resolve(LegacyFabricAdapter::class.java)
        assertEquals("legacy:Purplers", adapt?.invoke("Purplers"))
    }

    @Test
    fun `adapts through the shared adapter instance of WorldEdit 7_4`() {
        val adapt = WorldEditPlayerAdapter.resolve(ModernFabricAdapter::class.java)
        assertEquals("modern:Purplers", adapt?.invoke("Purplers"))
    }

    @Test
    fun `reports an adapter with neither API as unsupported`() {
        assertNull(WorldEditPlayerAdapter.resolve(UnknownFabricAdapter::class.java))
    }
}
