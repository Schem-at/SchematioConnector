package io.schemat.schematioConnector.vcs.render

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.wrappers.WrappedBlockData
import com.comphenix.protocol.wrappers.WrappedChatComponent
import com.comphenix.protocol.wrappers.WrappedDataValue
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import org.bukkit.block.data.BlockData
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin ProtocolLib layer for client-bound-only display entities (companion of
 * [io.schemat.schematioConnector.ProtocolLibHandler], which owns the auth listeners):
 * spawn/metadata/destroy packets sent to ONE viewing player. The entities never enter
 * the world, so no other player can ever see them.
 *
 * Metadata indices are the 1.20.2+ Display layout (unchanged through 1.21.x/26.x):
 * 0 flags, 11 translation, 12 scale, 15 billboard, 22 glow color override,
 * 23 block state (BlockDisplay) / text (TextDisplay), 25 background (TextDisplay).
 *
 * Only the gate build covers this class (packet layer is deliberately thin); the
 * budget/session logic it renders is unit-tested in core.
 */
class DisplayPacketSender(private val player: Player) {

    companion object {
        /**
         * Reserved negative entity-id range (spec §4): server entity ids are positive
         * counters, so negatives never collide with real entities on the client.
         */
        private val NEXT_ENTITY_ID = AtomicInteger(-1_100_000_000)

        fun nextEntityId(): Int = NEXT_ENTITY_ID.getAndDecrement()

        private const val FLAGS_INDEX = 0
        private const val TRANSLATION_INDEX = 11
        private const val SCALE_INDEX = 12
        private const val BILLBOARD_INDEX = 15
        private const val GLOW_COLOR_INDEX = 22
        private const val CONTENT_INDEX = 23 // block state or text, depending on entity
        private const val TEXT_BACKGROUND_INDEX = 25

        private const val FLAG_GLOWING: Byte = 0x40
        private const val BILLBOARD_CENTER: Byte = 3
    }

    private val protocolManager = ProtocolLibrary.getProtocolManager()

    private fun byteSerializer() = WrappedDataWatcher.Registry.get(java.lang.Byte::class.java)
    private fun intSerializer() = WrappedDataWatcher.Registry.get(java.lang.Integer::class.java)
    private fun vecSerializer() = WrappedDataWatcher.Registry.get(Vector3f::class.java)

    private fun sendSpawn(entityId: Int, type: EntityType, x: Double, y: Double, z: Double) {
        val packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY)
        packet.integers.write(0, entityId)
        packet.getUUIDs().write(0, UUID.randomUUID())
        packet.getEntityTypeModifier().write(0, type)
        packet.doubles.write(0, x).write(1, y).write(2, z)
        protocolManager.sendServerPacket(player, packet)
    }

    private fun sendMetadata(entityId: Int, values: List<WrappedDataValue>) {
        val packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA)
        packet.integers.write(0, entityId)
        packet.getDataValueCollectionModifier().write(0, values)
        protocolManager.sendServerPacket(player, packet)
    }

    /**
     * Spawns a BlockDisplay showing [blockData], glowing in [glowColorRgb], scaled by
     * [scale] and offset by [translation] (slight shrink+recenter distinguishes ghosts
     * from real blocks). Returns the entity id.
     */
    fun spawnBlockDisplay(
        x: Double,
        y: Double,
        z: Double,
        blockData: BlockData,
        glowColorRgb: Int?,
        scale: Vector3f = Vector3f(1f, 1f, 1f),
        translation: Vector3f = Vector3f(0f, 0f, 0f),
    ): Int {
        val entityId = nextEntityId()
        sendSpawn(entityId, EntityType.BLOCK_DISPLAY, x, y, z)
        val values = buildList {
            add(WrappedDataValue(TRANSLATION_INDEX, vecSerializer(), translation))
            add(WrappedDataValue(SCALE_INDEX, vecSerializer(), scale))
            add(WrappedDataValue(CONTENT_INDEX, WrappedDataWatcher.Registry.getBlockDataSerializer(false), WrappedBlockData.createData(blockData).handle))
            if (glowColorRgb != null) {
                add(WrappedDataValue(FLAGS_INDEX, byteSerializer(), FLAG_GLOWING))
                add(WrappedDataValue(GLOW_COLOR_INDEX, intSerializer(), glowColorRgb))
            }
        }
        sendMetadata(entityId, values)
        return entityId
    }

    /** Spawns a center-billboarded TextDisplay label. Returns the entity id. */
    fun spawnTextDisplay(x: Double, y: Double, z: Double, text: String, backgroundArgb: Int? = null): Int {
        val entityId = nextEntityId()
        sendSpawn(entityId, EntityType.TEXT_DISPLAY, x, y, z)
        val values = buildList {
            add(WrappedDataValue(BILLBOARD_INDEX, byteSerializer(), BILLBOARD_CENTER))
            add(WrappedDataValue(CONTENT_INDEX, WrappedDataWatcher.Registry.getChatComponentSerializer(false), WrappedChatComponent.fromText(text).handle))
            if (backgroundArgb != null) {
                add(WrappedDataValue(TEXT_BACKGROUND_INDEX, intSerializer(), backgroundArgb))
            }
        }
        sendMetadata(entityId, values)
        return entityId
    }

    /** Destroys previously spawned entities for this player. */
    fun destroy(entityIds: List<Int>) {
        if (entityIds.isEmpty()) return
        val packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY)
        packet.getIntLists().write(0, entityIds)
        protocolManager.sendServerPacket(player, packet)
    }
}
