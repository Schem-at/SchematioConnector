package io.schemat.schematioConnector.vcs.render

import io.schemat.connector.core.vcs.BlockChange
import io.schemat.connector.core.vcs.DiffKind
import io.schemat.connector.core.vcs.DiffRegion
import io.schemat.connector.core.vcs.DiffRenderer
import io.schemat.connector.core.vcs.DiffSession
import io.schemat.connector.core.vcs.DisplayBudget
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.joml.Vector3f
import java.util.logging.Logger

/**
 * B1 vanilla rendering backend (spec §4): per-player packet-level BlockDisplay /
 * TextDisplay entities via [DisplayPacketSender] - no client mod needed, no other
 * player sees anything.
 *
 * Vocabulary:
 * - ADDED   = the new block as a ghost (scale 0.98, teal glow)
 * - REMOVED = the old block (red glow; display entities have no per-block alpha, so
 *   the shrunken ghost + red glow carries the "gone" reading)
 * - CHANGED = the new block (orange glow)
 * - per-region bounding box from thin BlockDisplay slabs + a TextDisplay label
 *   (`Region 3/12 · +45 −12 ~7`)
 *
 * Budget/LOD decisions come from the unit-tested [DisplayBudget]; this class only
 * translates its plan into packets.
 */
class VanillaDisplayRenderer(
    private val player: Player,
    private val logger: Logger,
) : DiffRenderer {

    companion object {
        private const val GLOW_ADDED = 0x1ABC9C // teal
        private const val GLOW_REMOVED = 0xE74C3C // red
        private const val GLOW_CHANGED = 0xE67E22 // orange
        private const val GLOW_BOX = 0x808080 // unfocused region boxes

        private const val GHOST_SCALE = 0.98f
        private const val GHOST_OFFSET = 0.01f
        private const val BOX_THICKNESS = 0.05f
        private const val BOX_EDGE_BLOCK = "minecraft:gray_stained_glass"
    }

    private val sender = DisplayPacketSender(player)
    private val spawnedIds = mutableListOf<Int>()
    private var session: DiffSession? = null

    override fun show(session: DiffSession) {
        this.session = session
        render()
    }

    override fun focusRegion(index: Int) {
        // The session's cursor is the single source of truth (moved by the command
        // layer before this call); focusing is just a re-render.
        render()
    }

    override fun setLayerVisible(kind: DiffKind, visible: Boolean) {
        // Toggle state lives on the session; re-render with the new visibility.
        render()
    }

    override fun clear() {
        if (spawnedIds.isNotEmpty()) {
            try {
                sender.destroy(spawnedIds.toList())
            } catch (e: Exception) {
                logger.warning("Failed to destroy diff display entities for ${player.name}: ${e.message}")
            }
            spawnedIds.clear()
        }
    }

    private fun render() {
        val session = this.session ?: return
        clear()
        if (session.disposed) return

        val visibleKinds = DiffKind.entries.filterTo(mutableSetOf()) { session.isLayerVisible(it) }
        val plan = DisplayBudget.plan(session.regions, session.cursor, visibleKinds)
        val regionsById = session.regions.associateBy { it.id }

        try {
            plan.focused?.let { focus ->
                regionsById[focus.regionId]?.let { region ->
                    focus.cells.forEach { spawnCell(session, it) }
                    spawnLabel(session, region, focused = true, truncated = focus.truncated)
                }
            }
            plan.boxRegionIds.forEach { id ->
                regionsById[id]?.let { region ->
                    spawnBox(session, region)
                    spawnLabel(session, region, focused = false, truncated = false)
                }
            }
        } catch (e: Exception) {
            // A packet-construction failure must never leave a half-rendered overlay.
            logger.warning("Diff render failed for ${player.name}: ${e.message}")
            clear()
        }
    }

    private fun worldX(session: DiffSession, x: Int): Double = (session.anchor.x + x).toDouble()
    private fun worldY(session: DiffSession, y: Int): Double = (session.anchor.y + y).toDouble()
    private fun worldZ(session: DiffSession, z: Int): Double = (session.anchor.z + z).toDouble()

    private fun spawnCell(session: DiffSession, cell: BlockChange) {
        val (blockString, glow) = when (cell.kind) {
            DiffKind.ADDED -> cell.newBlock to GLOW_ADDED
            DiffKind.REMOVED -> cell.oldBlock to GLOW_REMOVED
            DiffKind.CHANGED -> cell.newBlock to GLOW_CHANGED
        }
        val blockData = parseBlockData(blockString) ?: return
        spawnedIds += sender.spawnBlockDisplay(
            x = worldX(session, cell.pos.x),
            y = worldY(session, cell.pos.y),
            z = worldZ(session, cell.pos.z),
            blockData = blockData,
            glowColorRgb = glow,
            scale = Vector3f(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE),
            translation = Vector3f(GHOST_OFFSET, GHOST_OFFSET, GHOST_OFFSET),
        )
    }

    /** 12 thin slabs along the edges of the region's bounding box. */
    private fun spawnBox(session: DiffSession, region: DiffRegion) {
        val edgeBlock = parseBlockData(BOX_EDGE_BLOCK) ?: return
        val minX = worldX(session, region.min.x)
        val minY = worldY(session, region.min.y)
        val minZ = worldZ(session, region.min.z)
        val sizeX = (region.max.x - region.min.x + 1).toFloat()
        val sizeY = (region.max.y - region.min.y + 1).toFloat()
        val sizeZ = (region.max.z - region.min.z + 1).toFloat()
        val maxX = minX + sizeX
        val maxY = minY + sizeY
        val maxZ = minZ + sizeZ

        fun edge(x: Double, y: Double, z: Double, scale: Vector3f) {
            spawnedIds += sender.spawnBlockDisplay(x, y, z, edgeBlock, GLOW_BOX, scale)
        }

        val alongX = Vector3f(sizeX, BOX_THICKNESS, BOX_THICKNESS)
        val alongY = Vector3f(BOX_THICKNESS, sizeY, BOX_THICKNESS)
        val alongZ = Vector3f(BOX_THICKNESS, BOX_THICKNESS, sizeZ)

        // 4 edges along X
        edge(minX, minY, minZ, alongX); edge(minX, maxY, minZ, alongX)
        edge(minX, minY, maxZ, alongX); edge(minX, maxY, maxZ, alongX)
        // 4 edges along Y
        edge(minX, minY, minZ, alongY); edge(maxX, minY, minZ, alongY)
        edge(minX, minY, maxZ, alongY); edge(maxX, minY, maxZ, alongY)
        // 4 edges along Z
        edge(minX, minY, minZ, alongZ); edge(maxX, minY, minZ, alongZ)
        edge(minX, maxY, minZ, alongZ); edge(maxX, maxY, minZ, alongZ)
    }

    private fun spawnLabel(session: DiffSession, region: DiffRegion, focused: Boolean, truncated: Boolean) {
        val total = session.regions.size
        val text = buildString {
            append("Region ${region.id + 1}/$total · ${region.countLabel()}")
            if (focused && truncated) append("\n⚠ region too large - showing densest slice")
        }
        spawnedIds += sender.spawnTextDisplay(
            x = worldX(session, (region.min.x + region.max.x) / 2),
            y = worldY(session, region.max.y) + 1.5,
            z = worldZ(session, (region.min.z + region.max.z) / 2),
            text = text,
            backgroundArgb = if (focused) 0xB0000000.toInt() else 0x60000000,
        )
    }

    private fun parseBlockData(blockString: String?): BlockData? {
        if (blockString.isNullOrBlank()) return null
        return try {
            Bukkit.createBlockData(blockString)
        } catch (e: Exception) {
            // Unknown/modded block state on this server version - skip the cell.
            null
        }
    }
}
