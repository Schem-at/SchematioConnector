package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.HttpTransport
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class HttpTransportTest {

    @Test
    fun `builds url with encoded query params`() {
        val url = HttpTransport.buildUrl(
            "https://schemat.io/api/v1",
            ApiRequest(HttpMethod.GET, "/mod/communities/my-slug/schematics", query = mapOf("search" to "a b", "per_page" to "20")),
        )
        assertEquals("https://schemat.io/api/v1/mod/communities/my-slug/schematics?search=a+b&per_page=20", url)
    }

    @Test
    fun `base endpoint trailing slash is normalized`() {
        val url = HttpTransport.buildUrl("https://schemat.io/api/v1/", ApiRequest(HttpMethod.GET, "/mod/me"))
        assertEquals("https://schemat.io/api/v1/mod/me", url)
    }
}
