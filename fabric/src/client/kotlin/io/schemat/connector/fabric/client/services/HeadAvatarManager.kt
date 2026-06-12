package io.schemat.connector.fabric.client.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads Minecraft head avatars and registers them as GPU textures, keyed by
 * player uuid. Mirrors [io.schemat.connector.fabric.client.ui.PreviewImageManager]'s
 * registration/threading: decode + register on the render thread, access-ordered LRU
 * cap with evicted textures destroyed on the render thread.
 *
 * Source preference per player: the schemat.io-provided `head_url` when present
 * (fetched via [fetchTrusted], i.e. the allowlisted image fetcher), otherwise
 * `https://mc-heads.net/avatar/{uuid}/{size}` via a plain [java.net.http.HttpClient]
 * on Dispatchers.IO - mc-heads is a different host than the schemat.io transport.
 */
class HeadAvatarManager(
    private val scope: CoroutineScope,
    /** Allowlisted fetcher for schemat.io-hosted head URLs (ClientServices.fetchImageBytes). */
    private val fetchTrusted: suspend (String) -> ByteArray?,
) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-heads")
        /** Resident head-texture cap; the least-recently-used entries are evicted. */
        private const val MAX_TEXTURES = 64
        /** Requested avatar resolution (drawn at 10-16 GUI px). */
        private const val AVATAR_SIZE = 64
        private const val MAX_BYTES = 512 * 1024
    }

    /** Plain JDK client for mc-heads.net (public, regular TLS - no dev trust-all needed). */
    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private data class Entry(val id: Identifier, val width: Int, val height: Int)

    /** Access-ordered LRU; only mutated on the client thread (like PreviewImageManager). */
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

    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun cacheKey(uuid: String): String = uuid.lowercase().replace("-", "")

    /**
     * The registered head texture for [uuid], or null while loading/failed (a miss
     * kicks off the fetch - callers poll each frame, like preview images). Prefers
     * [headUrl] when given; falls back to mc-heads by uuid.
     */
    fun getHead(uuid: String, headUrl: String? = null): Identifier? {
        if (uuid.isBlank()) return null
        val key = cacheKey(uuid)
        textureCache[key]?.let { return it.id }
        if (key in failed || key in loading) return null

        loading.add(key)
        scope.launch {
            try {
                val bytes = fetchAvatarBytes(uuid, headUrl)
                if (bytes == null) {
                    failed.add(key)
                    loading.remove(key)
                    return@launch
                }
                Minecraft.getInstance().execute {
                    try {
                        val image = NativeImage.read(bytes)
                        val id = Identifier.fromNamespaceAndPath("schematioconnector", "head/$key")
                        val texture = DynamicTexture({ id.toString() }, image)
                        Minecraft.getInstance().textureManager.register(id, texture)
                        textureCache[key] = Entry(id, image.width, image.height)
                    } catch (e: Exception) {
                        LOGGER.warn("Failed to decode head avatar for {}: {}", uuid, e.message)
                        failed.add(key)
                    } finally {
                        loading.remove(key)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOGGER.warn("Failed to load head avatar for {}: {}", uuid, e.message)
                failed.add(key)
                loading.remove(key)
            }
        }
        return null
    }

    /** Pixel dimensions of the registered head texture, or null while unavailable. */
    fun getHeadSize(uuid: String): Pair<Int, Int>? =
        textureCache[cacheKey(uuid)]?.let { it.width to it.height }

    /** head_url first (allowlisted fetcher), then mc-heads by uuid as fallback. */
    private suspend fun fetchAvatarBytes(uuid: String, headUrl: String?): ByteArray? {
        if (!headUrl.isNullOrBlank()) {
            fetchTrusted(headUrl)?.let { return it }
        }
        return fetchMcHeads(uuid)
    }

    private suspend fun fetchMcHeads(uuid: String): ByteArray? {
        // mc-heads accepts dashed or undashed uuids - send what we have.
        val url = "https://mc-heads.net/avatar/$uuid/$AVATAR_SIZE"
        return try {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "image/*")
                .GET()
                .build()
            val response = withContext(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            }
            when {
                response.statusCode() != 200 -> {
                    LOGGER.warn("mc-heads avatar fetch failed for {} (status={})", uuid, response.statusCode())
                    null
                }
                response.body().size > MAX_BYTES -> null
                else -> response.body()
            }
        } catch (e: Exception) {
            LOGGER.warn("mc-heads avatar fetch failed for {}: {}", uuid, e.message)
            null
        }
    }

    /** Destroy all registered head textures (render thread). */
    fun cleanup() {
        val textureManager = Minecraft.getInstance().textureManager
        textureCache.values.forEach { textureManager.release(it.id) }
        textureCache.clear()
        loading.clear()
        failed.clear()
    }
}
