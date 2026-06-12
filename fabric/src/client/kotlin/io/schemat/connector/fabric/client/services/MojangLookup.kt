package io.schemat.connector.fabric.client.services

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal Mojang profile lookup (player name → uuid) for the co-author editor.
 *
 * Deliberately NOT routed through the schemat.io transport: this talks to
 * `api.mojang.com` with a plain [HttpClient] on [Dispatchers.IO].
 */
object MojangLookup {

    /** Resolved Mojang profile; [uuid] is dashed lowercase. */
    data class Profile(val uuid: String, val name: String)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * `GET https://api.mojang.com/users/profiles/minecraft/<name>`:
     * 200 → profile, 404/204 (unknown player) → null, anything else → [IOException].
     */
    suspend fun resolve(name: String): Profile? = withContext(Dispatchers.IO) {
        val cleaned = name.trim()
        require(cleaned.matches(Regex("[a-zA-Z0-9_]{1,16}"))) { "Invalid player name" }
        val request = HttpRequest.newBuilder(
            URI.create("https://api.mojang.com/users/profiles/minecraft/$cleaned")
        ).timeout(Duration.ofSeconds(10)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            200 -> parseProfile(response.body())
            204, 404 -> null
            else -> throw IOException("Mojang lookup failed (HTTP ${response.statusCode()})")
        }
    }

    private fun parseProfile(body: String): Profile? {
        val json = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = json.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val name = json.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        return Profile(dashUuid(id), name)
    }

    /** Insert dashes into a 32-char undashed uuid (Mojang returns undashed ids). */
    fun dashUuid(raw: String): String {
        val s = raw.lowercase().replace("-", "")
        if (s.length != 32) return raw
        return "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"
    }
}
