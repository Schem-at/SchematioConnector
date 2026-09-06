package io.schemat.connector.fabric.client.services

import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Demand-driven thumbnails: at most two downloads/decodes and 32 queued requests. */
class PreviewImageManager(
    private val scope: CoroutineScope,
    private val fetchBytes: suspend (String) -> ByteArray?,
) {
    data class Entry(val id: Identifier, val width: Int, val height: Int)
    private data class Key(val name: String, val url: String)
    private class Request(val key: Key, var lastSeen: Long, val generation: Long) { var job: Job? = null }
    private val textures = LinkedHashMap<Key, Entry>(32, 0.75f, true)
    private val queued = LinkedHashMap<Key, Request>()
    private val active = HashMap<Key, Request>()
    private val ready = ArrayDeque<Pair<Request, NativeImage?>>()
    private val failed = LinkedHashMap<Key, Long>()
    private var generation = 0L
    data class Stats(val queued: Int, val inFlight: Int, val resident: Int)
    fun stats(): Stats = Stats(queued.size, active.size, textures.size)
    private val client get() = Minecraft.getInstance()

    fun getTexture(key: String, imageUrl: String?): Identifier? = getEntry(key, imageUrl)?.id
    fun getTextureSize(key: String): Pair<Int, Int>? = textures.entries.lastOrNull { it.key.name == key }?.value?.let { it.width to it.height }
    fun getEntry(key: String, imageUrl: String?): Entry? {
        if (imageUrl.isNullOrBlank()) return null
        val id = Key(key, imageUrl)
        textures[id]?.let { return it }
        val now = System.nanoTime()
        (active[id] ?: queued[id])?.let { it.lastSeen = now; return null }
        failed[id]?.let { if (now - it < RETRY_NS) return null else failed.remove(id) }
        if (queued.size < MAX_QUEUED) queued[id] = Request(id, now, generation)
        return null
    }

    /** Client tick. GPU uploads have a two-millisecond cooperative budget and a two-image cap. */
    fun tick() {
        val started = System.nanoTime()
        var uploaded = 0
        while (ready.isNotEmpty() && uploaded < 2 && System.nanoTime() - started < 2_000_000L) {
            val (request, image) = ready.removeFirst()
            if (active[request.key] === request) active.remove(request.key)
            if (image == null) continue
            if (request.generation != generation || System.nanoTime() - request.lastSeen > VISIBLE_NS) {
                image.close(); continue
            }
            var texture: DynamicTexture? = null
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest((request.key.name + "\n" + request.key.url).toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val id = Identifier.fromNamespaceAndPath("schematioconnector", "preview/$digest")
                val entry = Entry(id, image.width, image.height)
                texture = DynamicTexture({ "Schematio preview" }, image)
                client.textureManager.register(id, texture)
                textures[request.key] = entry
                while (textures.size > 128) {
                    val oldest = textures.entries.iterator().next()
                    client.textureManager.release(oldest.value.id)
                    textures.remove(oldest.key)
                }
                uploaded++
            } catch (e: Exception) {
                if (texture != null) texture.close() else image.close()
                fail(request)
                LOGGER.warn("Could not register preview", e)
            }
        }
        val now = System.nanoTime()
        queued.entries.removeIf { now - it.value.lastSeen > VISIBLE_NS }
        while (active.size < 2 && queued.isNotEmpty()) {
            // Prefer the latest visible demand after a page or search change.
            val request = queued.values.maxBy { it.lastSeen }
            queued.remove(request.key)
            active[request.key] = request
            request.job = scope.launch {
                var image: NativeImage? = null
                try {
                    val bytes = fetchBytes(request.key.url) ?: error("Preview download failed")
                    require(bytes.size <= 5 * 1024 * 1024) { "Preview exceeds 5 MiB" }
                    checkDimensions(bytes)
                    ensureActive()
                    image = NativeImage.read(bytes)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { LOGGER.debug("Preview unavailable for {}: {}", request.key.name, e.message) }
                finally {
                    val decoded = image
                    client.execute {
                        if (request.generation != generation || active[request.key] !== request) decoded?.close()
                        else {
                            if (decoded == null) fail(request)
                            ready.addLast(request to decoded)
                        }
                    }
                }
            }
        }
    }

    private fun fail(request: Request) {
        failed[request.key] = System.nanoTime()
        while (failed.size > 128) failed.remove(failed.keys.first())
    }

    fun isLoading(key: String): Boolean = queued.keys.any { it.name == key } || active.keys.any { it.name == key }

    /** Late worker results must close their image rather than repopulate a cleared cache. */
    fun cleanup() {
        generation++
        active.values.forEach { it.job?.cancel() }
        active.clear(); queued.clear(); failed.clear()
        ready.forEach { it.second?.close() }; ready.clear()
        textures.values.forEach { client.textureManager.release(it.id) }; textures.clear()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-preview")
        private const val MAX_QUEUED = 32
        private const val VISIBLE_NS = 1_000_000_000L
        private const val RETRY_NS = 30_000_000_000L
        /** Inspect dimensions before allocating the native RGBA image. IO thread only. */
        fun checkDimensions(bytes: ByteArray) {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                require(readers.hasNext()) { "Unsupported preview image" }
                val reader = readers.next()
                try {
                    reader.input = input
                    val w = reader.getWidth(0); val h = reader.getHeight(0)
                    require(w > 0 && h > 0 && w.toLong() * h <= 4_194_304L) { "Preview exceeds four million pixels" }
                } finally { reader.dispose() }
            }
        }
    }
}
