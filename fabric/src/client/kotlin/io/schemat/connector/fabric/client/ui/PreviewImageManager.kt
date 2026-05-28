package io.schemat.connector.fabric.client.ui

import io.schemat.connector.core.http.HttpUtil
import kotlinx.coroutines.*
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

class PreviewImageManager(private val scope: CoroutineScope) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger("schematioconnector-preview")
    }

    private val textureCache = mutableMapOf<String, Identifier>()
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()

    fun getTexture(shortId: String, imageUrl: String?, httpUtil: HttpUtil?): Identifier? {
        if (imageUrl.isNullOrBlank() || httpUtil == null) return null

        textureCache[shortId]?.let { return it }

        if (shortId in failed || shortId in loading) return null

        loading.add(shortId)
        scope.launch {
            try {
                val bytes = httpUtil.fetchImageBytes(imageUrl)
                if (bytes == null) {
                    LOGGER.warn("Failed to fetch preview for $shortId")
                    failed.add(shortId)
                    loading.remove(shortId)
                    return@launch
                }

                MinecraftClient.getInstance().execute {
                    try {
                        val nativeImage = NativeImage.read(bytes)
                        // Minecraft Identifier paths require [a-z0-9/._-], so sanitize the shortId
                        val safePath = shortId.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
                        val texture = NativeImageBackedTexture({ "schematioconnector/preview/$safePath" }, nativeImage)
                        val id = Identifier.of("schematioconnector", "preview/$safePath")
                        MinecraftClient.getInstance().textureManager.registerTexture(id, texture)
                        textureCache[shortId] = id
                        LOGGER.info("Loaded preview for $shortId (${nativeImage.width}x${nativeImage.height})")
                    } catch (e: Exception) {
                        LOGGER.warn("Failed to decode preview image for $shortId: ${e.message}")
                        failed.add(shortId)
                    } finally {
                        loading.remove(shortId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOGGER.warn("Failed to load preview for $shortId: ${e.message}")
                failed.add(shortId)
                loading.remove(shortId)
            }
        }

        return null
    }

    fun isLoading(shortId: String): Boolean = shortId in loading

    fun cleanup() {
        val textureManager = MinecraftClient.getInstance().textureManager
        textureCache.values.forEach { textureManager.destroyTexture(it) }
        textureCache.clear()
        loading.clear()
        failed.clear()
    }
}
