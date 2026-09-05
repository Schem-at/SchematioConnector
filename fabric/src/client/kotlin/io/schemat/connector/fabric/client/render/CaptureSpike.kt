package io.schemat.connector.fabric.client.render

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import org.slf4j.LoggerFactory
import java.nio.file.Files

/** Settings' capture test exercises the same renderer as the thumbnail composer. */
object CaptureSpike {
    private val LOGGER = LoggerFactory.getLogger("schematioconnector-client")

    /** Render thread only. Writes a grass block to <gameDir>/schemat-capture-test.png. */
    fun run() {
        val client = Minecraft.getInstance()
        val outPath = FabricLoader.getInstance().gameDir.resolve("schemat-capture-test.png")
        val builder = SchematicSnapshot.Builder(BlockPos.ZERO, BlockPos.ZERO)
        builder.setBlockState(BlockPos.ZERO, Blocks.GRASS_BLOCK.defaultBlockState())
        val source = SchematicRenderSource(SnapshotBlockRenderView(builder.build()), BlockPos.ZERO, BlockPos.ZERO)
        val target = OffscreenTarget()
        try {
            OffscreenSchematicRenderer.render(source, CameraPose(), target, BackgroundMode.STUDIO)
            target.readPng { bytes ->
                try {
                    checkNotNull(bytes) { "PNG readback failed" }
                    Files.write(outPath, bytes)
                    LOGGER.info("SCHEMAT-CAPTURE: test saved {} bytes to {}", bytes.size, outPath)
                } catch (e: Exception) {
                    LOGGER.error("SCHEMAT-CAPTURE: test failed", e)
                } finally {
                    client.execute { target.close() }
                }
            }
        } catch (e: Throwable) {
            target.close()
            LOGGER.error("SCHEMAT-CAPTURE: test failed", e)
        }
    }
}
