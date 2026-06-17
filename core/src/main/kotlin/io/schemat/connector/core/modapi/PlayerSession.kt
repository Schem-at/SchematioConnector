package io.schemat.connector.core.modapi

import com.auth0.jwt.JWT
import java.time.Instant

/**
 * A decoded (NOT signature-verified - the server does that) player JWT.
 *
 * Decoding uses java-jwt's [JWT.decode] directly, consistent with the existing inline
 * decoding in `HttpUtil` (there is no shared JWT-parser class in core to reuse).
 */
class PlayerSession private constructor(
    val token: String,
    val playerUuid: String?,
    private val expiresAt: Instant?,
) {
    /**
     * True when the token is expired or will expire within [marginSeconds].
     * Tokens without an `exp` claim never report expired.
     */
    fun isExpired(marginSeconds: Long = 30, now: Instant = Instant.now()): Boolean =
        expiresAt != null && now.plusSeconds(marginSeconds).isAfter(expiresAt)

    companion object {
        /** Mojang-style undashed uuid: exactly 32 hex chars. */
        private val UNDASHED_UUID = Regex("^[0-9a-fA-F]{32}$")

        /** Same 8-4-4-4-12 insertion idiom as the server's `ResolvesModContext::normalizeUuid`. */
        private val UUID_GROUPS = Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$")

        /**
         * Mojang-issued tokens carry the player uuid undashed in `sub`; the backend now
         * tolerates that, but the client should send canonical dashed-lowercase uuids in
         * `player_uuid` fields. Non-32-hex subjects (already dashed, opaque ids) pass through.
         */
        private fun normalizeUuid(sub: String?): String? = when {
            sub == null -> null
            UNDASHED_UUID.matches(sub) -> UUID_GROUPS.replace(sub.lowercase(), "$1-$2-$3-$4-$5")
            else -> sub
        }

        /** Decode a JWT into a session; returns null for malformed tokens. */
        fun fromToken(token: String): PlayerSession? = try {
            val decoded = JWT.decode(token)
            PlayerSession(token, normalizeUuid(decoded.subject), decoded.expiresAtAsInstant)
        } catch (e: Throwable) {
            // Throwable, not Exception: JWT.decode() can raise linkage Errors
            // (e.g. NoClassDefFoundError when a transitive dep like Jackson is
            // missing from the shipped jar). Those must degrade to a failed
            // decode here, not escape and kill the auth coroutine.
            null
        }
    }
}
