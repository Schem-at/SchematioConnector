package io.schemat.connector.fabric.client.ui

import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads schematic preview images and registers them as GPU textures, keyed by a
 * caller-supplied cache key (typically the schematic id or short_id).
 *
 * Transport is injected via [fetchBytes] (a suspend `url -> bytes?` function) so the
 * manager stays decoupled from any particular HTTP stack; the shared instance lives on
 * [io.schemat.connector.fabric.client.services.ClientServices.previewImages].
 */
class PreviewImageManager(
    private val scope: CoroutineScope,
    private val fetchBytes: suspend (String) -> ByteArray?,
) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-preview")
        /** Cap on resident preview textures; least-recently-rendered entries are evicted. */
        private const val MAX_TEXTURES = 128
    }

    /** A registered preview texture plus its pixel dimensions (for aspect-correct drawing). */
    data class Entry(val id: Identifier, val width: Int, val height: Int)

    /**
     * Access-ordered LRU: [getTexture] hits refresh recency, and inserting beyond
     * [MAX_TEXTURES] destroys + unregisters the eldest texture. Only ever mutated on
     * the client thread (lookups happen during rendering, inserts inside
     * [Minecraft.execute]).
     */
    private val textureCache = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            if (size <= MAX_TEXTURES) return false
            val id = eldest.value.id
            Minecraft.getInstance().execute {
                Minecraft.getInstance().textureManager.release(id)
            }
            return true
        }
    }

    // Mutated from both the client thread and IO coroutines, so they must be concurrent
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The registered texture for [key], or null while it is missing/loading/failed.
     * A miss with a usable [imageUrl] kicks off a background download; callers simply
     * keep polling each frame until a texture appears.
     */
    fun getTexture(key: String, imageUrl: String?): Identifier? = getEntry(key, imageUrl)?.id

    /** Pixel dimensions of the registered texture for [key], or null while unavailable. */
    fun getTextureSize(key: String): Pair<Int, Int>? = textureCache[key]?.let { it.width to it.height }

    /**
     * The registered texture entry (id + dimensions) for [key], or null while it is
     * missing/loading/failed; a miss with a usable [imageUrl] kicks off the download.
     */
    fun getEntry(key: String, imageUrl: String?): Entry? {
        if (imageUrl.isNullOrBlank()) return null

        textureCache[key]?.let { return it }

        if (key in failed || key in loading) return null

        loading.add(key)
        scope.launch {
            try {
                val bytes = fetchBytes(imageUrl)
                if (bytes == null) {
                    LOGGER.warn("Failed to fetch preview for $key")
                    failed.add(key)
                    loading.remove(key)
                    return@launch
                }

                Minecraft.getInstance().execute {
                    try {
                        val nativeImage = NativeImage.read(bytes)
                        // Minecraft Identifier paths require [a-z0-9/._-], so sanitize the key
                        val safePath = key.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
                        val texture = DynamicTexture({ "schematioconnector/preview/$safePath" }, nativeImage)
                        val id = Identifier.fromNamespaceAndPath("schematioconnector", "preview/$safePath")
                        Minecraft.getInstance().textureManager.register(id, texture)
                        textureCache[key] = Entry(id, nativeImage.width, nativeImage.height)
                        LOGGER.info("Loaded preview for $key (${nativeImage.width}x${nativeImage.height})")
                    } catch (e: Exception) {
                        LOGGER.warn("Failed to decode preview image for $key: ${e.message}")
                        failed.add(key)
                    } finally {
                        loading.remove(key)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOGGER.warn("Failed to load preview for $key: ${e.message}")
                failed.add(key)
                loading.remove(key)
            }
        }

        return null
    }

    fun isLoading(key: String): Boolean = key in loading

    fun cleanup() {
        val textureManager = Minecraft.getInstance().textureManager
        textureCache.values.forEach { textureManager.release(it.id) }
        textureCache.clear()
        loading.clear()
        failed.clear()
    }
}
