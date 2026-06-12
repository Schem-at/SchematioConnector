package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.transport.ApiResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApiErrorTest {

    private fun response(status: Int, body: String?, headers: Map<String, String> = emptyMap()) =
        ApiResponse(status, body?.toByteArray(), headers)

    @Nested
    inner class FromResponse {
        @Test
        fun `401 maps to Unauthorized`() {
            val error = ApiError.fromResponse(response(401, """{"error":"Token expired"}"""))
            assertTrue(error is ApiError.Unauthorized)
        }

        @Test
        fun `403 maps to Forbidden with message`() {
            val error = ApiError.fromResponse(response(403, """{"message":"You are not a member of this community"}"""))
            assertTrue(error is ApiError.Forbidden)
            assertEquals("You are not a member of this community", (error as ApiError.Forbidden).message)
        }

        @Test
        fun `404 maps to NotFound`() {
            assertTrue(ApiError.fromResponse(response(404, """{"message":"Community not found"}""")) is ApiError.NotFound)
        }

        @Test
        fun `422 maps to Validation with field messages`() {
            val error = ApiError.fromResponse(
                response(422, """{"message":"The name field is required.","errors":{"name":["The name field is required."]}}""")
            )
            assertTrue(error is ApiError.Validation)
            assertEquals(listOf("The name field is required."), (error as ApiError.Validation).fieldErrors["name"])
        }

        @Test
        fun `409 maps to Conflict with existing url`() {
            val error = ApiError.fromResponse(
                response(
                    409,
                    """{"message":"A schematic with identical content already exists","existing":{"short_id":"ab12","slug":"test","url":"https://schemat.io/schematics/ab12"}}"""
                )
            )
            assertTrue(error is ApiError.Conflict)
            assertEquals("A schematic with identical content already exists", (error as ApiError.Conflict).message)
            assertEquals("https://schemat.io/schematics/ab12", error.existingUrl)
        }

        @Test
        fun `409 never uses the slug as a url, missing url is null`() {
            // A bare slug is not a URL (and slug links 404 on the website) - never fall back to it.
            val withSlug = ApiError.fromResponse(
                response(409, """{"error":"A schematic with identical content already exists","existing":{"slug":"test-2"}}""")
            )
            assertEquals(null, (withSlug as ApiError.Conflict).existingUrl)

            val bare = ApiError.fromResponse(response(409, """{"error":"A schematic with identical content already exists"}"""))
            assertTrue(bare is ApiError.Conflict)
            assertEquals(null, (bare as ApiError.Conflict).existingUrl)
        }

        @Test
        fun `429 maps to RateLimited with retry-after`() {
            val error = ApiError.fromResponse(response(429, "{}", mapOf("Retry-After" to "37")))
            assertTrue(error is ApiError.RateLimited)
            assertEquals(37, (error as ApiError.RateLimited).retryAfterSeconds)
        }

        @Test
        fun `500 maps to Server`() {
            assertTrue(ApiError.fromResponse(response(500, "oops")) is ApiError.Server)
        }

        @Test
        fun `message falls back through message then error keys then default`() {
            assertEquals("boom", (ApiError.fromResponse(response(403, """{"error":"boom"}""")) as ApiError.Forbidden).message)
            assertTrue((ApiError.fromResponse(response(403, "not-json")) as ApiError.Forbidden).message.isNotBlank())
        }
    }

    @Nested
    inner class ResultHelpers {
        @Test
        fun `map transforms success and passes failure through`() {
            val ok: ApiResult<Int> = ApiResult.Success(2)
            val fail: ApiResult<Int> = ApiResult.Failure(ApiError.Offline)
            assertEquals(ApiResult.Success(4), ok.map { it * 2 })
            assertEquals(fail, fail.map { it * 2 })
        }
    }
}
