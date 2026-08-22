package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetString
import java.util.Base64

/**
 * Ed25519 public keys from the CLIENT'S OWN configured backend's
 * /.well-known/schematio-keys.json — never from the server-claimed backendHost, which is
 * what makes spoofing collapse into "signature does not verify" (spec §Attestation payload).
 *
 * Fetches lazily once per session; [invalidate] forces one refetch (unknown kid = rotation).
 * A failed fetch keeps previously-known keys.
 */
class BackendKeyCache(private val fetchDocument: () -> String?) {

    @Volatile private var keys: Map<String, ByteArray> = emptyMap()
    @Volatile private var fetched = false

    fun keysByKid(): Map<String, ByteArray> {
        if (!fetched) {
            val doc = fetchDocument()
            fetched = true
            if (doc != null) keys = parse(doc)
        }
        return keys
    }

    fun invalidate() {
        fetched = false
    }

    companion object {
        /** kid -> raw 32-byte Ed25519 key; unknown algs / bad base64 / bad lengths skipped. */
        fun parse(json: String): Map<String, ByteArray> {
            val obj = parseJsonSafe(json) ?: return emptyMap()
            val arr = obj.safeGetArray("keys")
            val out = LinkedHashMap<String, ByteArray>()
            for (element in arr) {
                if (!element.isJsonObject) continue
                val entry = element.asJsonObject
                val kid = entry.safeGetString("kid") ?: continue
                if (entry.safeGetString("alg") != "Ed25519") continue
                val keyB64 = entry.safeGetString("key") ?: continue
                val raw = try {
                    Base64.getDecoder().decode(keyB64)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (raw.size == 32) out[kid] = raw
            }
            return out
        }
    }
}
