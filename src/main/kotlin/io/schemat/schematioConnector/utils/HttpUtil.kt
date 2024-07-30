package io.schemat.schematioConnector.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.logging.Logger

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.bukkit.map.MapPalette
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO

class HttpUtil(private val apiKey: String, private val apiEndpoint: String, private val logger: Logger) {

    private val gson = Gson()

    suspend fun checkConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$apiEndpoint/check")
                logger.info("Checking connection to $apiEndpoint")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.doOutput = true

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = gson.fromJson(response, Map::class.java)
                    jsonResponse["success"] as Boolean
                } else {
                    logger.warning("Failed to connect to the API, response code: $responseCode")
                    logger.warning(connection.errorStream.bufferedReader().use { it.readText() })
                    false
                }
            } catch (e: Exception) {
                logger.severe("Exception occurred while checking connection: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun sendMultiPartRequest(endpoint: String, multipart: HttpEntity): HttpEntity? {
        return withContext(Dispatchers.IO) {
            try {
                val httpClient = HttpClients.createDefault()
                val uploadFile = HttpPost("$apiEndpoint$endpoint")
                uploadFile.addHeader("Authorization", "Bearer $apiKey")
                uploadFile.entity = multipart
                val response = httpClient.execute(uploadFile)
                response.entity
            } catch (e: IOException) {
                logger.severe("Exception occurred while sending multipart request: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun sendGetRequestFullResponse(endpoint: String, progressCallback: (Float) -> Unit): HttpResponse? {
        return withContext(Dispatchers.IO) {
            val httpClient = HttpClients.createDefault()
            val fullUrl = "$apiEndpoint$endpoint"
            val httpGet = HttpGet(fullUrl)
            httpGet.addHeader("Authorization", "Bearer $apiKey")

            try {
                logger.info("Sending GET request to: $fullUrl")
                val response = httpClient.execute(httpGet)
                val entity = response.entity
                if (entity != null) {
                    val totalBytes = entity.contentLength
                    var bytesTransferred: Long = 0

                    val content = ByteArrayOutputStream()
                    entity.content.use { inputStream ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            content.write(buffer, 0, bytesRead)
                            bytesTransferred += bytesRead
                            val progress = bytesTransferred.toFloat() / totalBytes
                            progressCallback(progress)
                        }
                    }

                    // Replace the original entity with a new one that uses our buffered content
                    EntityUtils.consume(entity)
                    response.entity = ByteArrayEntity(content.toByteArray())
                }
                response
            } catch (e: IOException) {
                logger.severe("Exception occurred while sending GET request (full response): ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun sendGetRequest(endpoint: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val httpClient = HttpClients.createDefault()
                val fullUrl = "$apiEndpoint$endpoint"
                val httpGet = HttpGet(fullUrl)
                httpGet.addHeader("Authorization", "Bearer $apiKey")

                val response = httpClient.execute(httpGet)
                val entity = response.entity
                if (entity != null) {
                    EntityUtils.toString(entity)
                } else {
                    null
                }
            } catch (e: IOException) {
                logger.severe("Exception occurred while sending GET request: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private fun validateJwtToken(token: String): Boolean {
        return try {
            val decodedJWT = JWT.decode(token)
            val payload = decodedJWT.claims
            val type = payload["type"]?.asString()
            val permissions = payload["permissions"]?.asList(String::class.java)

            type == "system" && permissions?.contains("can_manage_password") == true
        } catch (exception: JWTVerificationException) {
            logger.warning("Invalid JWT token: ${exception.message}")
            false
        }
    }


    suspend fun setPassword(playerUuid: String, password: String): Pair<Int, List<String>> {
        if (!validateJwtToken(apiKey)) {
            logger.warning("JWT token validation failed")
            return Pair(403, listOf("You don't have permission to change passwords."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val httpClient = HttpClients.createDefault()
                val fullUrl = URI("$apiEndpoint/password-set").toString()

                val postRequest = HttpPost(fullUrl)
                postRequest.addHeader("Content-Type", "application/json")
                postRequest.addHeader("Accept", "application/json")
                postRequest.addHeader("Authorization", "Bearer $apiKey")

                val json = """
                    {
                        "player_uuid": "$playerUuid",
                        "password": "$password"
                    }
                """.trimIndent()
                postRequest.entity = StringEntity(json)

                val response = httpClient.execute(postRequest)
                val statusCode = response.statusLine.statusCode
                val responseBody = EntityUtils.toString(response.entity)
                logger.info("Set password response: $responseBody")
                when (statusCode) {
                    200 -> Pair(200, listOf("Password successfully changed!"))
                    422 -> {
                        val errors = parseErrors(responseBody)
                        Pair(422, errors)
                    }
                    else -> Pair(statusCode, listOf("An unexpected error occurred. Please try again later."))
                }
            } catch (e: Exception) {
                logger.severe("Exception occurred while setting password: ${e.message}")
                e.printStackTrace()
                Pair(500, listOf("An internal error occurred. Please try again later."))
            }
        }
    }

    private fun parseErrors(responseBody: String): List<String> {
        val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
        val errors = jsonObject.getAsJsonObject("errors")
        val errorMessages = mutableListOf<String>()

        errors?.entrySet()?.forEach { (field, messages) ->
            messages.asJsonArray.forEach { message ->
                errorMessages.add("$field: ${message.asString}")
            }
        }

        return errorMessages
    }

    fun fetchImageAsByteArray(imageUrl: String): ByteArray? {
        return try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val image = ImageIO.read(connection.inputStream)
                imageToMapByteArray(image)
            } else {
                logger.warning("Failed to fetch image, response code: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            logger.severe("Exception occurred while fetching image: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun imageToMapByteArray(image: BufferedImage): ByteArray {
        val scaledImage = scaleImage(image)
        val byteArray = ByteArray(128 * 128)

        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val rgb = scaledImage.getRGB(x, y)
                val color = Color(rgb, true)
                val mapColor = MapPalette.matchColor(color)
                byteArray[y * 128 + x] = mapColor.toByte()
            }
        }

        return byteArray
    }

    private fun scaleImage(image: BufferedImage): BufferedImage {
        val scaledImage = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        val g = scaledImage.createGraphics()
        g.drawImage(image, 0, 0, 128, 128, null)
        g.dispose()
        return scaledImage
    }

}