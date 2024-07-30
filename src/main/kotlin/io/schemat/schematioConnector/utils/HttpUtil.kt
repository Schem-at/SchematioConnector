package io.schemat.schematioConnector.utils

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.logging.Logger

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


}