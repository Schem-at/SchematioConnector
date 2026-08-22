package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.attest.AttestOutcome
import io.schemat.connector.core.attest.AttestationVerifier
import io.schemat.connector.core.attest.bytesToHexLower
import io.schemat.connector.core.ipc.Attest
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.HttpTransport
import io.schemat.connector.core.modapi.transport.TransportException
import io.schemat.connector.fabric.client.SchematioClientMod
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * Verifies relayed ATTEST messages off-thread against keys from OUR configured backend and
 * upgrades [ServerSession] to VERIFIED on success. Every failure path logs and leaves the
 * session at UNVERIFIED — never surfaces an error to the player (spec §Flow).
 */
object AttestFlow {

    private val LOGGER = LoggerFactory.getLogger("SchematioAttest")

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "schematio-attest").apply { isDaemon = true }
    }

    @Volatile private var keyCache: BackendKeyCache? = null

    /** Strips a trailing /api/v<N> from an API endpoint, yielding the backend origin. */
    fun originOf(apiEndpoint: String): String =
        apiEndpoint.trimEnd('/').replace(Regex("/api/v\\d+$"), "")

    private fun cache(): BackendKeyCache {
        keyCache?.let { return it }
        val auth = SchematioClientMod.instance.authManager
        val transport = HttpTransport(
            apiEndpoint = originOf(auth.apiEndpoint),
            logger = Logger.getLogger("schematio-attest"),
            trustAllCertificates = auth.trustAllCertificates,
        )
        val created = BackendKeyCache {
            try {
                runBlocking {
                    transport.execute(ApiRequest(HttpMethod.GET, "/.well-known/schematio-keys.json"), null)
                }.takeIf { it.isSuccess }?.bodyAsString()
            } catch (e: TransportException) {
                LOGGER.warn("Failed to fetch backend key document: ${e.message}")
                null
            }
        }
        keyCache = created
        return created
    }

    /**
     * Handles an ATTEST from the server. Captures the session's nonce/community expectations
     * on the caller's thread, verifies on the attest thread (network fetch for keys), and
     * flips trust on success. Late/duplicate ATTESTs after a reconnect fail NONCE_MISMATCH.
     */
    fun onAttest(attest: Attest) {
        val expectedNonce = ServerSession.nonce
        val expectedNonceHex = bytesToHexLower(expectedNonce)
        val expectedCommunityId = ServerSession.communityId
        executor.execute {
            val cache = cache()
            var keys = cache.keysByKid()
            if (!keys.containsKey(attest.keyId)) {
                cache.invalidate() // rotation: one forced refetch on unknown kid
                keys = cache.keysByKid()
            }
            when (val outcome = AttestationVerifier.verify(
                payloadJson = attest.payloadJson,
                signature = attest.signature,
                keyId = attest.keyId,
                keysByKid = keys,
                expectedNonceHex = expectedNonceHex,
                expectedCommunityId = expectedCommunityId,
            )) {
                is AttestOutcome.Verified -> {
                    if (ServerSession.markVerified(expectedNonce)) {
                        LOGGER.info("Server attestation VERIFIED (community ${outcome.communityId})")
                    } else {
                        LOGGER.warn("Dropping stale ATTEST verification: connection changed since request (nonce mismatch)")
                    }
                }
                is AttestOutcome.Rejected -> {
                    LOGGER.warn("Server attestation rejected (${outcome.reason}); staying UNVERIFIED")
                }
            }
        }
    }
}
