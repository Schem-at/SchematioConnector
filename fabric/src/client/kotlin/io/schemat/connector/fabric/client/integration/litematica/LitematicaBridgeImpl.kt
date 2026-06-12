package io.schemat.connector.fabric.client.integration.litematica

import fi.dy.masa.litematica.data.DataManager
import fi.dy.masa.litematica.data.SchematicHolder
import fi.dy.masa.litematica.schematic.LitematicaSchematic
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement
import fi.dy.masa.litematica.scheduler.TaskScheduler
import fi.dy.masa.litematica.scheduler.tasks.TaskSaveSchematic
import fi.dy.masa.litematica.selection.AreaSelection
import fi.dy.masa.litematica.selection.SelectionManager
import fi.dy.masa.litematica.selection.SelectionMode
import fi.dy.masa.malilib.interfaces.ICompletionListener
import io.schemat.connector.fabric.client.integration.ExportSource
import io.schemat.connector.fabric.client.integration.LitematicaBridge
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.render.SchematicRenderSource
import io.schemat.connector.fabric.client.render.SchematicSnapshot
import io.schemat.connector.fabric.client.render.SnapshotBlockRenderView
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.stream.Collectors
import kotlin.math.abs

/**
 * Real [LitematicaBridge] backed by Litematica's own managers.
 *
 * Compiled directly against Litematica 0.26.x (`maven.modrinth:litematica:7LGBHMu9`,
 * loom-remapped). This class must ONLY be classloaded when Litematica is present -
 * see [LitematicaBridgeLoader.tryCreate].
 *
 * API surface verified with `javap` against the remapped jar
 * (`.gradle/loom-cache/remapped_mods/.../litematica-76f15208-7LGBHMu9.jar`):
 * - `DataManager.getSchematicsBaseDirectory(): Path` (static)
 * - `DataManager.getAreaSelectionsBaseDirectory(): Path` (static)
 * - `DataManager.getSchematicPlacementManager(): SchematicPlacementManager` (static)
 * - `DataManager.getSelectionManager(): SelectionManager` (static)
 * - `SchematicHolder.getInstance().getOrLoad(Path): LitematicaSchematic?` - NOTE: takes
 *   `java.nio.file.Path`, not `File` (the old reflection code looked for a `File` overload
 *   that no longer exists)
 * - `SchematicPlacement.createFor(LitematicaSchematic, BlockPos, String, boolean, boolean)` (static)
 * - `SchematicPlacementManager.addSchematicPlacement(SchematicPlacement, boolean)`,
 *   `.setSelectedSchematicPlacement(SchematicPlacement)`, `.getAllSchematicsPlacements(): List`
 * - `SchematicPlacement.getName(): String`, `.getSchematic(): LitematicaSchematic`,
 *   `.getSchematicFile(): Path?`
 * - `LitematicaSchematic.writeToFile(Path dir, String fileName, boolean override): boolean`
 * - `LitematicaSchematic.createEmptySchematic(AreaSelection, String author): LitematicaSchematic?`
 * - `LitematicaSchematic.SchematicSaveInfo(boolean visibleOnly, boolean ignoreEntities)`
 *   (2-arg ctor delegates to the 4-arg one with includeSupportBlocks=false, fromSchematicWorld=false
 *   - confirmed in bytecode)
 * - `TaskSaveSchematic(Path dir, String fileName, LitematicaSchematic, AreaSelection,
 *   SchematicSaveInfo, boolean overrideFile)` + inherited
 *   `setCompletionListener(ICompletionListener)` (from `TaskBase`)
 * - `TaskScheduler.getServerInstanceIfExistsOrClient().scheduleTask(ITask, int)` - Litematica's
 *   own `GuiSchematicSave$ButtonListener` schedules with interval 10 (confirmed in bytecode),
 *   which we mirror
 * - `SelectionManager.getSelectionMode()/getCurrentSelection()/getSimpleSelection()` and static
 *   `SelectionManager.tryLoadSelectionFromFile(Path): AreaSelection?`
 * - `AreaSelection.getName(): String`, `.getAllSubRegionBoxes(): List<Box>`
 * - `LitematicaSchematic.getAreaPositions(): Map<String, BlockPos>` (relative sub-region
 *   positions), `.getSubRegionPosition(String): BlockPos?`, `.getAreaSize(String): BlockPos?`
 *   (SIGNED sizes - litematic format), `.getSubRegionContainer(String):
 *   LitematicaBlockStateContainer?`, `.getBlockEntityMapForRegion(String):
 *   Map<BlockPos, NbtCompound>` (container-local keys)
 * - `LitematicaBlockStateContainer.getSize(): Vec3i`, `.get(int, int, int): BlockState`
 * - `selection.Box.getPos1()/getPos2(): BlockPos` (nullable, unordered corners)
 * - MC side (snapshot capture): `BlockEntity.loadStatic(BlockPos, BlockState,
 *   NbtCompound, RegistryWrapper.WrapperLookup)`, `World.getRegistryManager():
 *   DynamicRegistryManager` (implements WrapperLookup), `LightingProvider.DEFAULT`
 *
 * Litematica's managers are not thread-safe: every manager call happens on the client
 * thread (`Minecraft.execute`); file IO runs on a background executor.
 */
class LitematicaBridgeImpl : LitematicaBridge {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-litematica-bridge")
        private const val SUBFOLDER = "schemat.io"
        private const val LITEMATIC_EXTENSION = ".litematic"

        private const val PLACEMENT_ID_PREFIX = "placement:"
        private const val SELECTION_CURRENT_ID = "selection:current"
        private const val SELECTION_FILE_ID_PREFIX = "selection:file:"

        private fun sanitizeFileName(name: String): String =
            name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "schematic" }
    }

    init {
        // Smoke-test the API surface eagerly so signature drift surfaces as a Throwable
        // inside LitematicaBridgeLoader.tryCreate() instead of mid-session.
        DataManager.getSchematicsBaseDirectory()
        DataManager.getSchematicPlacementManager()
        DataManager.getSelectionManager()
    }

    override val isAvailable: Boolean = true

    // ------------------------------------------------------------------ filesystem

    override fun schematicsDirectory(): Path? = try {
        val dir = DataManager.getSchematicsBaseDirectory().resolve(SUBFOLDER)
        Files.createDirectories(dir)
        dir
    } catch (t: Throwable) {
        LOGGER.warn("Could not resolve Litematica schematics directory: ${t.message}", t)
        null
    }

    // ------------------------------------------------------------------ load

    override fun loadSchematic(file: File, name: String, onResult: (Boolean, String?) -> Unit) {
        val client = Minecraft.getInstance()
        CompletableFuture.supplyAsync {
            // File IO off-thread: make sure the file sits under the schematics dir
            // (callers normally write it there already; copy defensively otherwise).
            val dir = schematicsDirectory()
                ?: throw IOException("Litematica schematics directory is unavailable")
            val source = file.toPath().toAbsolutePath()
            if (!Files.exists(source)) throw IOException("File not found: $source")
            if (source.startsWith(dir.toAbsolutePath())) {
                source
            } else {
                val target = dir.resolve(sanitizeFileName(name) + LITEMATIC_EXTENSION)
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                target.toAbsolutePath()
            }
        }.whenComplete { path, ioError ->
            if (ioError != null) {
                LOGGER.warn("Failed to stage schematic for Litematica: ${ioError.message}", ioError)
                client.execute { onResult(false, "Could not save the schematic file: ${ioError.cause?.message ?: ioError.message}") }
                return@whenComplete
            }
            client.execute { loadOnClientThread(path, name, onResult) }
        }
    }

    /** Mirrors Litematica's own load GUI: load schematic -> createFor placement at player -> add + select. */
    private fun loadOnClientThread(path: Path, name: String, onResult: (Boolean, String?) -> Unit) {
        try {
            val client = Minecraft.getInstance()
            val player = client.player
            if (player == null) {
                onResult(false, "Saved to ${path.parent}. Join a world to create a placement.")
                return
            }

            val holder = SchematicHolder.getInstance()
            // Evict any stale cached copy of this file so re-downloads pick up fresh bytes.
            holder.allSchematics
                .filter { it.file?.toAbsolutePath() == path }
                .forEach { holder.removeSchematic(it) }

            val schematic = holder.getOrLoad(path)
            if (schematic == null) {
                onResult(false, fallbackLoadMessage(path, "Litematica could not parse the file"))
                return
            }

            val placement = SchematicPlacement.createFor(schematic, player.blockPosition(), name, true, true)
            val manager = DataManager.getSchematicPlacementManager()
            manager.addSchematicPlacement(placement, true)
            manager.setSelectedSchematicPlacement(placement)
            onResult(true, null)
        } catch (t: Throwable) {
            LOGGER.error("Litematica placement creation failed: ${t.message}", t)
            onResult(false, fallbackLoadMessage(path, t.message ?: "unexpected error"))
        }
    }

    private fun fallbackLoadMessage(path: Path, reason: String): String =
        "Schematic saved to ${path.parent}, but auto-placement failed ($reason). " +
            "Open Litematica's Load Schematics menu to place it."

    // ------------------------------------------------------------------ export: sources

    override fun listExportSources(): List<ExportSource> {
        val sources = mutableListOf<ExportSource>()
        try {
            DataManager.getSchematicPlacementManager().allSchematicsPlacements
                .forEachIndexed { index, placement ->
                    sources += ExportSource(
                        id = "$PLACEMENT_ID_PREFIX$index",
                        label = placement.name,
                        kind = SourceKind.PLACEMENT
                    )
                }
        } catch (t: Throwable) {
            LOGGER.warn("Failed to list Litematica placements: ${t.message}", t)
        }
        try {
            currentAreaSelection()?.let { selection ->
                if (selection.allSubRegionBoxes.isNotEmpty()) {
                    sources += ExportSource(
                        id = SELECTION_CURRENT_ID,
                        label = "${selection.name} (current selection)",
                        kind = SourceKind.AREA_SELECTION
                    )
                }
            }
            sources += listSavedSelectionFiles()
        } catch (t: Throwable) {
            LOGGER.warn("Failed to list Litematica area selections: ${t.message}", t)
        }
        return sources
    }

    /** Client thread. Prefer the selected placement, else the current area selection. */
    override fun currentSelectionSource(): ExportSource? = try {
        val manager = DataManager.getSchematicPlacementManager()
        val selected = manager.selectedSchematicPlacement
        if (selected != null) {
            // Mirror listExportSources(): the id is the index into the manager's list
            // (findPlacement falls back to the name when indices shift).
            val index = manager.allSchematicsPlacements.indexOf(selected)
            ExportSource(
                id = "$PLACEMENT_ID_PREFIX${index.coerceAtLeast(0)}",
                label = selected.name,
                kind = SourceKind.PLACEMENT
            )
        } else {
            currentAreaSelection()?.takeIf { it.allSubRegionBoxes.isNotEmpty() }?.let { selection ->
                ExportSource(
                    id = SELECTION_CURRENT_ID,
                    label = "${selection.name} (current selection)",
                    kind = SourceKind.AREA_SELECTION
                )
            }
        }
    } catch (t: Throwable) {
        LOGGER.warn("Failed to resolve the current Litematica selection: ${t.message}", t)
        null
    }

    private fun currentAreaSelection(): AreaSelection? {
        val manager = DataManager.getSelectionManager()
        return if (manager.selectionMode == SelectionMode.SIMPLE) {
            manager.simpleSelection
        } else {
            manager.currentSelection
        }
    }

    /** Saved selections are JSON files under Litematica's area-selections dir (per-world subfolders). */
    private fun listSavedSelectionFiles(): List<ExportSource> {
        val base = DataManager.getAreaSelectionsBaseDirectory()
        if (!Files.isDirectory(base)) return emptyList()
        val files = Files.walk(base, 4).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .collect(Collectors.toList())
        }
        return files.mapNotNull { path ->
            val selection = runCatching { SelectionManager.tryLoadSelectionFromFile(path) }.getOrNull()
                ?: return@mapNotNull null
            val relative = runCatching { base.relativize(path).toString() }.getOrDefault(path.fileName.toString())
            ExportSource(
                id = "$SELECTION_FILE_ID_PREFIX${path.toAbsolutePath()}",
                label = "${selection.name} ($relative)",
                kind = SourceKind.AREA_SELECTION
            )
        }
    }

    // ------------------------------------------------------------------ export: bytes

    override fun exportToBytes(source: ExportSource, onResult: (ByteArray?, String?) -> Unit) {
        val client = Minecraft.getInstance()
        when (source.kind) {
            SourceKind.PLACEMENT -> client.execute { exportPlacement(source, onResult) }
            SourceKind.AREA_SELECTION -> client.execute { exportAreaSelection(source, onResult) }
            else -> client.execute { onResult(null, "Unsupported export source: ${source.kind}") }
        }
    }

    /** Client thread. Resolve a PLACEMENT export source back to the live placement. */
    private fun findPlacement(source: ExportSource): SchematicPlacement? {
        val placements = DataManager.getSchematicPlacementManager().allSchematicsPlacements
        val index = source.id.removePrefix(PLACEMENT_ID_PREFIX).toIntOrNull()
        return index?.let { placements.getOrNull(it) }?.takeIf { it.name == source.label }
            ?: placements.firstOrNull { it.name == source.label }
    }

    /** Client thread. Resolve an AREA_SELECTION export source back to an AreaSelection. */
    private fun resolveAreaSelection(source: ExportSource): AreaSelection? = when {
        source.id == SELECTION_CURRENT_ID -> currentAreaSelection()
        source.id.startsWith(SELECTION_FILE_ID_PREFIX) ->
            SelectionManager.tryLoadSelectionFromFile(
                Path.of(source.id.removePrefix(SELECTION_FILE_ID_PREFIX))
            )
        else -> null
    }

    /** Client thread. File-backed placements read straight from disk; in-memory ones go via writeToFile. */
    private fun exportPlacement(source: ExportSource, onResult: (ByteArray?, String?) -> Unit) {
        try {
            val placement = findPlacement(source)
            if (placement == null) {
                onResult(null, "Placement \"${source.label}\" is no longer loaded")
                return
            }

            val backingFile = placement.schematicFile
            if (backingFile != null && Files.isRegularFile(backingFile)) {
                readBytesAsync(backingFile, deleteAfter = null, onResult)
                return
            }

            // In-memory schematic: serialize to a temp file (same idiom as Litematica's
            // GuiSchematicSave in-memory path, which calls writeToFile on the GUI thread).
            val tempDir = Files.createTempDirectory("schematio-export")
            val fileName = sanitizeFileName(placement.name) + LITEMATIC_EXTENSION
            val ok = placement.schematic.writeToFile(tempDir, fileName, true)
            val tempFile = tempDir.resolve(fileName)
            if (!ok || !Files.isRegularFile(tempFile)) {
                onResult(null, "Litematica failed to serialize placement \"${source.label}\"")
                return
            }
            readBytesAsync(tempFile, deleteAfter = tempDir, onResult)
        } catch (t: Throwable) {
            LOGGER.error("Placement export failed: ${t.message}", t)
            onResult(null, "Export failed: ${t.message ?: "unexpected error"}")
        }
    }

    /**
     * Client thread. Mirrors Litematica's "Save Area as Schematic" GUI path:
     * createEmptySchematic + TaskSaveSchematic scheduled on the task scheduler
     * (interval 10, like GuiSchematicSave), completion listener reads the bytes.
     */
    private fun exportAreaSelection(source: ExportSource, onResult: (ByteArray?, String?) -> Unit) {
        try {
            val client = Minecraft.getInstance()
            if (client.level == null) {
                onResult(null, "Join a world before exporting an area selection")
                return
            }

            val area: AreaSelection? = resolveAreaSelection(source)
            if (area == null || area.allSubRegionBoxes.isEmpty()) {
                onResult(null, "Area selection \"${source.label}\" has no regions (or no longer exists)")
                return
            }

            val author = client.player?.name?.string ?: "schemat.io"
            val schematic = LitematicaSchematic.createEmptySchematic(area, author)
            if (schematic == null) {
                onResult(null, "Area selection \"${source.label}\" has no valid regions")
                return
            }

            val tempDir = Files.createTempDirectory("schematio-export")
            val fileName = sanitizeFileName(area.name) + LITEMATIC_EXTENSION
            val saveInfo = LitematicaSchematic.SchematicSaveInfo(false, false)
            val task = TaskSaveSchematic(tempDir, fileName, schematic, area.copy(), saveInfo, true)
            task.setCompletionListener(object : ICompletionListener {
                override fun onTaskCompleted() {
                    Minecraft.getInstance().execute {
                        val tempFile = tempDir.resolve(fileName)
                        if (Files.isRegularFile(tempFile)) {
                            readBytesAsync(tempFile, deleteAfter = tempDir, onResult)
                        } else {
                            onResult(null, "Litematica did not produce a schematic file for \"${source.label}\"")
                        }
                    }
                }

                override fun onTaskAborted() {
                    Minecraft.getInstance().execute {
                        onResult(null, "Litematica aborted saving area \"${source.label}\"")
                    }
                }
            })
            TaskScheduler.getServerInstanceIfExistsOrClient().scheduleTask(task, 10)
        } catch (t: Throwable) {
            LOGGER.error("Area selection export failed: ${t.message}", t)
            onResult(null, "Export failed: ${t.message ?: "unexpected error"}")
        }
    }

    // ------------------------------------------------------------------ render source (thumbnails)

    /**
     * Build a [SchematicRenderSource] for the thumbnail composer by capturing a
     * FROZEN [SchematicSnapshot] - never a live Litematica world. All Litematica /
     * world reads happen on the client thread; [onResult] is delivered there too.
     * Once captured, the snapshot is plain immutable data, safe to render from any
     * frame later regardless of what Litematica loads/unloads.
     *
     * - PLACEMENT / LOCAL_FILE: read the `LitematicaSchematic` OBJECT directly
     *   (already loaded for a placement; `SchematicHolder.getOrLoad(Path)` for a
     *   file - loading the object does NOT require a world placement) and copy its
     *   sub-region block states + block-entity NBT into the snapshot. NO
     *   `SchematicPlacement` is ever added: no in-world ghost, and no
     *   render-disabled placement whose chunks Litematica would unload mid-preview
     *   (the old "build disappears" bug).
     * - AREA_SELECTION: copy the real client world over the selection's box union
     *   into the snapshot (frozen - stable even if the player moves or edits).
     *
     * Capture is bounded by [SchematicRenderSource.clampedRenderRegion] (the same
     * centered VOLUME_CAP clamp the renderer iterates), so huge builds snapshot at
     * most ~VOLUME_CAP³ cells.
     */
    override fun loadRenderSource(source: ExportSource, onResult: (SchematicRenderSource?, String?) -> Unit) {
        val client = Minecraft.getInstance()
        client.execute {
            try {
                when (source.kind) {
                    SourceKind.PLACEMENT -> {
                        val placement = findPlacement(source)
                        if (placement == null) {
                            onResult(null, "Placement \"${source.label}\" is no longer loaded")
                        } else {
                            val (renderSource, error) = renderSourceFromSchematic(placement.schematic, source.label)
                            onResult(renderSource, error)
                        }
                    }
                    SourceKind.AREA_SELECTION -> renderSourceFromAreaSelection(source, onResult)
                    SourceKind.LOCAL_FILE -> renderSourceFromLocalFile(source, onResult)
                    else -> onResult(null, "Previews are not supported for ${source.kind}")
                }
            } catch (t: Throwable) {
                LOGGER.error("Building render source failed: ${t.message}", t)
                onResult(null, "Preview failed: ${t.message ?: "unexpected error"}")
            }
        }
    }

    /** A schematic sub-region normalized to an ordered min corner + positive sizes. */
    private class SchematicRegion(
        val name: String,
        val minCorner: BlockPos,
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
    )

    /**
     * Normalize each sub-region's (relative position, SIGNED size) pair - the
     * litematic format stores sizes signed - to an ordered min corner with positive
     * sizes. Container coordinates and block-entity map keys are both 0-based from
     * that min corner (mirrors Litematica's own `SchematicPlacingUtils` math).
     */
    private fun schematicRegions(schematic: LitematicaSchematic): List<SchematicRegion> =
        schematic.areaPositions.keys.mapNotNull { name ->
            val pos = schematic.getSubRegionPosition(name) ?: return@mapNotNull null
            val size = schematic.getAreaSize(name) ?: return@mapNotNull null
            if (size.x == 0 || size.y == 0 || size.z == 0) return@mapNotNull null
            fun relEnd(p: Int, s: Int): Int = p + (if (s > 0) s - 1 else s + 1)
            val endX = relEnd(pos.x, size.x)
            val endY = relEnd(pos.y, size.y)
            val endZ = relEnd(pos.z, size.z)
            SchematicRegion(
                name = name,
                minCorner = BlockPos(minOf(pos.x, endX), minOf(pos.y, endY), minOf(pos.z, endZ)),
                sizeX = abs(size.x),
                sizeY = abs(size.y),
                sizeZ = abs(size.z),
            )
        }

    /**
     * Client thread. Snapshot a [LitematicaSchematic]'s sub-regions (states via
     * `getSubRegionContainer(name).get(x, y, z)`, block entities via
     * `getBlockEntityMapForRegion(name)` NBT) into a frozen [SchematicSnapshot] in
     * schematic-relative coordinates, then wrap it in a [SnapshotBlockRenderView].
     * Placement transforms (rotation/mirror) are NOT applied - the preview shows
     * the schematic as authored.
     */
    private fun renderSourceFromSchematic(
        schematic: LitematicaSchematic,
        label: String,
    ): Pair<SchematicRenderSource?, String?> {
        val regions = schematicRegions(schematic)
        if (regions.isEmpty()) return null to "\"$label\" has no regions to preview"

        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (region in regions) {
            minX = minOf(minX, region.minCorner.x); maxX = maxOf(maxX, region.minCorner.x + region.sizeX - 1)
            minY = minOf(minY, region.minCorner.y); maxY = maxOf(maxY, region.minCorner.y + region.sizeY - 1)
            minZ = minOf(minZ, region.minCorner.z); maxZ = maxOf(maxZ, region.minCorner.z + region.sizeZ - 1)
        }
        val minPos = BlockPos(minX, minY, minZ)
        val maxPos = BlockPos(maxX, maxY, maxZ)
        val (captureMin, captureMax) = SchematicRenderSource.clampedRenderRegion(minPos, maxPos)

        val builder = SchematicSnapshot.Builder(minPos, maxPos)
        for (region in regions) {
            val container = schematic.getSubRegionContainer(region.name) ?: continue
            val containerSize = container.size
            // Intersect the region with the capture box, in region-local coordinates.
            val x0 = maxOf(captureMin.x - region.minCorner.x, 0)
            val y0 = maxOf(captureMin.y - region.minCorner.y, 0)
            val z0 = maxOf(captureMin.z - region.minCorner.z, 0)
            val x1 = minOf(captureMax.x - region.minCorner.x, region.sizeX - 1, containerSize.x - 1)
            val y1 = minOf(captureMax.y - region.minCorner.y, region.sizeY - 1, containerSize.y - 1)
            val z1 = minOf(captureMax.z - region.minCorner.z, region.sizeZ - 1, containerSize.z - 1)
            if (x0 > x1 || y0 > y1 || z0 > z1) continue
            for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) {
                val state = container.get(x, y, z) ?: continue
                if (!state.isAir) {
                    builder.setBlockState(
                        BlockPos(region.minCorner.x + x, region.minCorner.y + y, region.minCorner.z + z),
                        state,
                    )
                }
            }
            val blockEntityMap = runCatching { schematic.getBlockEntityMapForRegion(region.name) }.getOrNull()
                ?: continue
            for ((localPos, nbt) in blockEntityMap) {
                val pos = BlockPos(
                    region.minCorner.x + localPos.x,
                    region.minCorner.y + localPos.y,
                    region.minCorner.z + localPos.z,
                )
                if (pos.x in captureMin.x..captureMax.x &&
                    pos.y in captureMin.y..captureMax.y &&
                    pos.z in captureMin.z..captureMax.z
                ) {
                    builder.setBlockEntityNbt(pos, nbt)
                }
            }
        }
        val view = SnapshotBlockRenderView(builder.build())
        return SchematicRenderSource(view, minPos, maxPos) to null
    }

    /**
     * Client thread. Area selections reference real blocks: copy the client world
     * over the selection's box union (states + live block entities) into a frozen
     * snapshot, so the preview stays stable even if the player moves or edits.
     */
    private fun renderSourceFromAreaSelection(source: ExportSource, onResult: (SchematicRenderSource?, String?) -> Unit) {
        val world = Minecraft.getInstance().level
        if (world == null) {
            onResult(null, "Join a world before previewing an area selection")
            return
        }
        val area = resolveAreaSelection(source)
        if (area == null || area.allSubRegionBoxes.isEmpty()) {
            onResult(null, "Area selection \"${source.label}\" has no regions (or no longer exists)")
            return
        }
        val bounds = unionOfBoxes(area.allSubRegionBoxes)
        if (bounds == null) {
            onResult(null, "Area selection \"${source.label}\" has no complete regions")
            return
        }
        val (captureMin, captureMax) = SchematicRenderSource.clampedRenderRegion(bounds.first, bounds.second)
        val builder = SchematicSnapshot.Builder(bounds.first, bounds.second)
        for (pos in BlockPos.betweenClosed(captureMin, captureMax)) {
            val state = world.getBlockState(pos)
            if (state.isAir) continue
            builder.setBlockState(pos, state)
            if (state.hasBlockEntity()) {
                world.getBlockEntity(pos)?.let { builder.setBlockEntity(pos, it) }
            }
        }
        val view = SnapshotBlockRenderView(builder.build())
        onResult(SchematicRenderSource(view, bounds.first, bounds.second), null)
    }

    /**
     * Client thread. Load the schematic OBJECT (no placement, no world needed) and
     * snapshot it like a placement's schematic.
     */
    private fun renderSourceFromLocalFile(source: ExportSource, onResult: (SchematicRenderSource?, String?) -> Unit) {
        val path = Path.of(source.id).toAbsolutePath()
        if (!Files.isRegularFile(path)) {
            onResult(null, "File not found: $path")
            return
        }
        val schematic = SchematicHolder.getInstance().getOrLoad(path)
        if (schematic == null) {
            onResult(null, "Litematica could not load \"${source.label}\" for preview")
            return
        }
        val (renderSource, error) = renderSourceFromSchematic(schematic, source.label)
        onResult(renderSource, error)
    }

    /** Component-wise min/max over Litematica selection boxes (pos1/pos2 are nullable, unordered). */
    private fun unionOfBoxes(boxes: Collection<fi.dy.masa.litematica.selection.Box>): Pair<BlockPos, BlockPos>? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        var any = false
        for (box in boxes) {
            val p1 = box.pos1 ?: continue
            val p2 = box.pos2 ?: continue
            any = true
            minX = minOf(minX, p1.x, p2.x); maxX = maxOf(maxX, p1.x, p2.x)
            minY = minOf(minY, p1.y, p2.y); maxY = maxOf(maxY, p1.y, p2.y)
            minZ = minOf(minZ, p1.z, p2.z); maxZ = maxOf(maxZ, p1.z, p2.z)
        }
        if (!any) return null
        return BlockPos(minX, minY, minZ) to BlockPos(maxX, maxY, maxZ)
    }

    /** Reads [file] on a background thread, optionally deleting [deleteAfter] (temp dir), result on render thread. */
    private fun readBytesAsync(file: Path, deleteAfter: Path?, onResult: (ByteArray?, String?) -> Unit) {
        val client = Minecraft.getInstance()
        CompletableFuture.supplyAsync {
            val bytes = Files.readAllBytes(file)
            if (deleteAfter != null) {
                runCatching {
                    Files.deleteIfExists(file)
                    Files.deleteIfExists(deleteAfter)
                }
            }
            bytes
        }.whenComplete { bytes, error ->
            client.execute {
                if (error != null) {
                    LOGGER.error("Failed to read exported schematic: ${error.message}", error)
                    onResult(null, "Could not read the exported file: ${error.cause?.message ?: error.message}")
                } else {
                    onResult(bytes, null)
                }
            }
        }
    }
}
