package io.schemat.connector.core.attest

import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class AttestationClientTest {

    private val sig64 = Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
    private val okBody =
        """{"payload":"{\"communityId\":\"c\"}","signature_base64":"$sig64","key_id":"k1"}"""

    private class FakeTransport(private val respond: (ApiRequest) -> ApiResponse) : ApiTransport {
        val requests = mutableListOf<Pair<ApiRequest, String?>>()
        override suspend fun execute(request: ApiRequest, bearerToken: String?): ApiResponse {
            requests += request to bearerToken
            return respond(request)
        }
    }

    private fun ok(body: String) = ApiResponse(200, body.toByteArray(Charsets.UTF_8))

    @Test
    fun `posts nonce and platform with the community token and parses the response`() = runTest {
        val transport = FakeTransport { ok(okBody) }
        val client = AttestationClient(transport, { "jwt-token" })

        val attestation = client.requestAttestation("00ff", IpcPlatform.PAPER_PLUGIN)!!

        assertEquals("""{"communityId":"c"}""", attestation.payloadJson)
        assertEquals(64, attestation.signature.size)
        assertEquals("k1", attestation.keyId)

        val (request, token) = transport.requests.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/plugin/attest", request.path)
        assertEquals("jwt-token", token)
        assertEquals("""{"nonce_hex":"00ff","platform":"PAPER_PLUGIN"}""", request.jsonBody)
    }

    @Test
    fun `caches by nonce (one backend call per connection)`() = runTest {
        val transport = FakeTransport { ok(okBody) }
        val client = AttestationClient(transport, { "jwt" })

        val first = client.requestAttestation("aa", IpcPlatform.PAPER_PLUGIN)
        val second = client.requestAttestation("aa", IpcPlatform.PAPER_PLUGIN)

        assertTrue(first === second)
        assertEquals(1, transport.requests.size)

        client.requestAttestation("bb", IpcPlatform.PAPER_PLUGIN)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `returns null without a token, on http error, transport failure, or bad body`() = runTest {
        assertNull(AttestationClient(FakeTransport { ok(okBody) }, { null })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        assertNull(AttestationClient(FakeTransport { ApiResponse(429, null) }, { "jwt" })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        assertNull(AttestationClient(FakeTransport { throw TransportException("boom") }, { "jwt" })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        assertNull(AttestationClient(FakeTransport { ok("""{"payload":"x"}""") }, { "jwt" })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        // Signature must be exactly 64 bytes.
        val shortSig = Base64.getEncoder().encodeToString(ByteArray(10))
        assertNull(
            AttestationClient(
                FakeTransport { ok("""{"payload":"x","signature_base64":"$shortSig","key_id":"k"}""") },
                { "jwt" },
            ).requestAttestation("00", IpcPlatform.PAPER_PLUGIN),
        )
    }
}
