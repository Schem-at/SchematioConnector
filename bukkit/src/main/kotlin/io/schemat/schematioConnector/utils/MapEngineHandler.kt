package io.schemat.schematioConnector.utils

import de.pianoman911.mapengine.api.MapEngineApi
import de.pianoman911.mapengine.api.clientside.IMapDisplay
import de.pianoman911.mapengine.api.util.Converter
import de.pianoman911.mapengine.api.util.FullSpacedColorBuffer
import de.pianoman911.mapengine.api.util.ImageUtils
import io.schemat.schematioConnector.SchematioConnector
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.BlockVector
import java.awt.image.BufferedImage
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Handler for MapEngine integration.
 * Provides schematic preview functionality using MapEngine's async rendering.
 */
class MapEngineHandler(private val plugin: SchematioConnector) {
    
    private val mapEngine: MapEngineApi = Bukkit.getServicesManager().load(MapEngineApi::class.java)
        ?: throw IllegalStateException("MapEngine API not available")
    
    // Track active displays per player so we can clean them up
    private val activeDisplays = ConcurrentHashMap<UUID, IMapDisplay>()
    
    // Image cache for loaded preview images
    private val imageCache = ConcurrentHashMap<String, BufferedImage>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Fetch an image from a URL (public for use by other components)
     */
    fun fetchImage(imageUrl: String): BufferedImage? {
        return try {
            // Check cache first
            imageCache[imageUrl]?.let { return it }
            
            val url = URI(imageUrl).toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "SchematioConnector/1.0")
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val image = ImageIO.read(connection.inputStream)
                if (image != null) {
                    imageCache[imageUrl] = image
                }
                image
            } else {
                plugin.logger.warning("Failed to fetch image: HTTP ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            plugin.logger.warning("Exception fetching image '$imageUrl': ${e.message}")
            null
        }
    }
    
    /**
     * Show a preview with an already-loaded image at a specific location.
     * Must be called from the main thread.
     */
    fun showPreview(
        player: Player,
        image: BufferedImage,
        center: Location,
        widthMaps: Int = 1,
        heightMaps: Int = 1
    ) {
        closePreview(player)
        
        val blockX = center.blockX
        val blockY = center.blockY
        val blockZ = center.blockZ
        
        // Map faces TOWARD the player
        val facing = getOppositeFacing(getPlayerFacing(player))
        
        // Calculate corners for multi-map display
        val (cornerA, cornerB) = calculateCorners(blockX, blockY, blockZ, widthMaps, heightMaps, facing)
        
        try {
            val display = mapEngine.displayProvider().createBasic(cornerA, cornerB, facing)
            display.spawn(player)
            
            activeDisplays[player.uniqueId] = display
            
            // Pixel dimensions
            val pixelWidth = widthMaps * 128
            val pixelHeight = heightMaps * 128
            
            // Create color buffer
            val buffer = FullSpacedColorBuffer(pixelWidth, pixelHeight)
            
            // Resize and get RGB
            val resized = ImageUtils.resize(image, pixelWidth, pixelHeight)
            val rgb = ImageUtils.rgb(resized)
            buffer.pixels(rgb, 0, 0, pixelWidth, pixelHeight)
            
            // Create drawing space
            @Suppress("DEPRECATION") // MapEngine API deprecation, no replacement documented
            val drawingSpace = mapEngine.pipeline().drawingSpace(buffer, display)
            
            // Configure
            drawingSpace.ctx().receivers().add(player)
            drawingSpace.ctx().converter(Converter.FLOYD_STEINBERG)
            
            // Flush
            display.pipeline().flush(drawingSpace)
            
            plugin.logger.info("Map preview (${widthMaps}x${heightMaps}) shown for ${player.name}")
        } catch (e: Exception) {
            plugin.logger.warning("Failed to show map preview: ${e.message}")
        }
    }
    
    /**
     * Show a schematic preview to a player using a URL.
     * The preview is displayed on a 1x1 map in front of the player.
     */
    fun showPreviewFromUrl(player: Player, imageUrl: String, onComplete: (() -> Unit)? = null) {
        // First close any existing preview
        closePreview(player)
        
        plugin.logger.info("showPreviewFromUrl called for ${player.name} with URL: $imageUrl")
        
        scope.launch {
            try {
                // Load or fetch image
                val image = fetchImage(imageUrl)
                if (image == null) {
                    plugin.logger.warning("Failed to fetch image from: $imageUrl")
                    return@launch
                }
                
                plugin.logger.info("Image loaded: ${image.width}x${image.height}")
                
                // Calculate position in front of player
                val location = player.eyeLocation.clone()
                val direction = location.direction.normalize()
                location.add(direction.multiply(3.0))
                
                // Create and spawn display on main thread
                plugin.server.scheduler.runTask(plugin, Runnable {
                    showPreview(player, image, location)
                    onComplete?.invoke()
                })
                
            } catch (e: Exception) {
                plugin.logger.warning("Failed to show preview: ${e.message}")
            }
        }
    }
    
    /**
     * Show a larger preview (for detailed view) using multiple maps
     */
    fun showLargePreview(player: Player, imageUrl: String, width: Int = 2, height: Int = 2, onComplete: (() -> Unit)? = null) {
        closePreview(player)
        
        plugin.logger.info("showLargePreview called for ${player.name} - ${width}x${height} maps")
        
        scope.launch {
            try {
                val image = fetchImage(imageUrl)
                if (image == null) {
                    plugin.logger.warning("Failed to fetch image from: $imageUrl")
                    return@launch
                }
                
                plugin.logger.info("Image loaded: ${image.width}x${image.height}")
                
                // Position for larger display
                val location = player.eyeLocation.clone()
                val direction = location.direction.normalize()
                location.add(direction.multiply(5.0))
                
                plugin.server.scheduler.runTask(plugin, Runnable {
                    showPreview(player, image, location, width, height)
                    onComplete?.invoke()
                })
                
            } catch (e: Exception) {
                plugin.logger.warning("Failed to show large preview: ${e.message}")
            }
        }
    }
    
    private fun getOppositeFacing(face: BlockFace): BlockFace {
        return when (face) {
            BlockFace.NORTH -> BlockFace.SOUTH
            BlockFace.SOUTH -> BlockFace.NORTH
            BlockFace.EAST -> BlockFace.WEST
            BlockFace.WEST -> BlockFace.EAST
            else -> BlockFace.NORTH
        }
    }
    
    /**
     * Close and despawn any active preview for a player
     */
    fun closePreview(player: Player) {
        activeDisplays.remove(player.uniqueId)?.let { display ->
            try {
                display.despawn(player)
                plugin.logger.info("Preview closed for ${player.name}")
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
    
    /**
     * Pre-cache images for faster loading later
     */
    fun preloadImage(imageUrl: String) {
        if (imageCache.containsKey(imageUrl)) return
        
        scope.launch {
            try {
                fetchImage(imageUrl)
            } catch (e: Exception) {
                // Silent fail for preloading
            }
        }
    }
    
    private fun getPlayerFacing(player: Player): BlockFace {
        val yaw = player.location.yaw
        return when {
            yaw in -45f..45f -> BlockFace.SOUTH
            yaw in 45f..135f -> BlockFace.WEST
            yaw in -135f..-45f -> BlockFace.EAST
            else -> BlockFace.NORTH
        }
    }
    
    private fun calculateCorners(x: Int, y: Int, z: Int, width: Int, height: Int, facing: BlockFace): Pair<BlockVector, BlockVector> {
        // For maps facing a direction, we need to place them on the perpendicular plane
        return when (facing) {
            BlockFace.NORTH -> Pair(
                BlockVector(x, y, z),
                BlockVector(x + width - 1, y + height - 1, z)
            )
            BlockFace.SOUTH -> Pair(
                BlockVector(x, y, z),
                BlockVector(x - width + 1, y + height - 1, z)
            )
            BlockFace.EAST -> Pair(
                BlockVector(x, y, z),
                BlockVector(x, y + height - 1, z + width - 1)
            )
            BlockFace.WEST -> Pair(
                BlockVector(x, y, z),
                BlockVector(x, y + height - 1, z - width + 1)
            )
            else -> Pair(BlockVector(x, y, z), BlockVector(x, y, z))
        }
    }
    
    /**
     * Clean up all resources
     */
    fun shutdown() {
        scope.cancel()
        activeDisplays.forEach { (uuid, display) ->
            try {
                // Find player by UUID and despawn
                Bukkit.getPlayer(uuid)?.let { player ->
                    display.despawn(player)
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        activeDisplays.clear()
        imageCache.clear()
    }
}
