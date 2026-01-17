package io.schemat.schematioConnector.utils

import io.schemat.schematioConnector.SchematioConnector
import org.bukkit.Bukkit
import org.bukkit.map.MapPalette
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Caches map preview images with async loading and pre-computed color lookups.
 * 
 * Features:
 * - Async HTTP fetch (doesn't block main thread)
 * - Memory cache for hot images
 * - Disk cache for persistence
 * - Pre-computed RGB -> MapColor lookup table (much faster than MapPalette.matchColor)
 */
object MapImageCache {
    
    private val plugin get() = SchematioConnector.instance
    private val executor = Executors.newFixedThreadPool(2) // 2 threads for image loading
    
    // Memory cache: URL hash -> map byte array
    private val memoryCache = ConcurrentHashMap<String, ByteArray>()
    private const val MAX_MEMORY_CACHE_SIZE = 50
    
    // Pre-computed color lookup table (much faster than MapPalette.matchColor)
    private val colorLookup: ByteArray by lazy { buildColorLookupTable() }
    
    /**
     * Get map image bytes asynchronously.
     * Returns cached version if available, otherwise fetches and caches.
     */
    fun getMapImage(imageUrl: String): CompletableFuture<ByteArray?> {
        val urlHash = hashUrl(imageUrl)
        
        // Check memory cache first
        memoryCache[urlHash]?.let { cached ->
            return CompletableFuture.completedFuture(cached)
        }
        
        // Check disk cache
        val diskCached = loadFromDiskCache(urlHash)
        if (diskCached != null) {
            // Store in memory cache too
            addToMemoryCache(urlHash, diskCached)
            return CompletableFuture.completedFuture(diskCached)
        }
        
        // Fetch async
        return CompletableFuture.supplyAsync({
            try {
                val bytes = fetchAndConvertImage(imageUrl)
                if (bytes != null) {
                    addToMemoryCache(urlHash, bytes)
                    saveToDiskCache(urlHash, bytes)
                }
                bytes
            } catch (e: Exception) {
                plugin.logger.warning("Failed to fetch map image: ${e.message}")
                null
            }
        }, executor)
    }
    
    /**
     * Get map image and call callback on main thread when ready.
     */
    fun getMapImageAsync(imageUrl: String, onComplete: (ByteArray?) -> Unit) {
        getMapImage(imageUrl).thenAccept { bytes ->
            // Run callback on main thread
            Bukkit.getScheduler().runTask(plugin, Runnable {
                onComplete(bytes)
            })
        }
    }
    
    /**
     * Pre-load an image into cache (call this when listing schematics)
     */
    fun preload(imageUrl: String) {
        val urlHash = hashUrl(imageUrl)
        if (!memoryCache.containsKey(urlHash) && loadFromDiskCache(urlHash) == null) {
            getMapImage(imageUrl) // Fire and forget
        }
    }
    
    private fun fetchAndConvertImage(imageUrl: String): ByteArray? {
        val url = URI(imageUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        
        return if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val image = ImageIO.read(connection.inputStream)
            if (image != null) {
                imageToMapBytesFast(image)
            } else null
        } else {
            plugin.logger.warning("Failed to fetch image $imageUrl: ${connection.responseCode}")
            null
        }
    }
    
    /**
     * Fast image to map bytes conversion using pre-computed lookup table.
     * About 100x faster than using MapPalette.matchColor() per pixel.
     */
    private fun imageToMapBytesFast(image: BufferedImage): ByteArray {
        val scaled = scaleImage(image, 128, 128)
        val result = ByteArray(128 * 128)
        
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val rgb = scaled.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                
                // Use pre-computed lookup (quantized to 32 levels per channel)
                val lookupIndex = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                result[y * 128 + x] = colorLookup[lookupIndex]
            }
        }
        
        return result
    }
    
    /**
     * Build lookup table for RGB -> MapColor.
     * Quantizes RGB to 32 levels (5 bits) per channel = 32^3 = 32768 entries.
     * Built once at startup, then O(1) lookups.
     */
    @Suppress("DEPRECATION")
    private fun buildColorLookupTable(): ByteArray {
        val table = ByteArray(32 * 32 * 32)
        
        for (r5 in 0 until 32) {
            for (g5 in 0 until 32) {
                for (b5 in 0 until 32) {
                    // Convert back to full 8-bit color (center of the bucket)
                    val r = (r5 shl 3) or 4
                    val g = (g5 shl 3) or 4
                    val b = (b5 shl 3) or 4
                    
                    val index = (r5 shl 10) or (g5 shl 5) or b5
                    table[index] = MapPalette.matchColor(Color(r, g, b))
                }
            }
        }
        
        return table
    }
    
    private fun scaleImage(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return scaled
    }
    
    private fun hashUrl(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(url.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
    
    private fun getCacheDir(): File {
        val dir = File(plugin.dataFolder, "map_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    private fun loadFromDiskCache(hash: String): ByteArray? {
        val file = File(getCacheDir(), "$hash.bin")
        return if (file.exists() && file.length() == (128 * 128).toLong()) {
            try {
                file.readBytes()
            } catch (e: Exception) {
                null
            }
        } else null
    }
    
    private fun saveToDiskCache(hash: String, bytes: ByteArray) {
        try {
            val file = File(getCacheDir(), "$hash.bin")
            file.writeBytes(bytes)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to save map cache: ${e.message}")
        }
    }
    
    private fun addToMemoryCache(hash: String, bytes: ByteArray) {
        // Simple LRU-ish: if full, remove random entries
        if (memoryCache.size >= MAX_MEMORY_CACHE_SIZE) {
            val keysToRemove = memoryCache.keys.take(10)
            keysToRemove.forEach { memoryCache.remove(it) }
        }
        memoryCache[hash] = bytes
    }
    
    /**
     * Clear all caches
     */
    fun clearCache() {
        memoryCache.clear()
        getCacheDir().listFiles()?.forEach { it.delete() }
    }
    
    /**
     * Shutdown executor
     */
    fun shutdown() {
        executor.shutdown()
    }
}
