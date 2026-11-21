package com.example.mygarmincaloriestracker

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Read-only Open Food Facts v2 lookup. Callers must obtain confirmation before saving. */
class OpenFoodFacts(private val appVersion: String = "1.0") {
    init {
        require(Regex("[A-Za-z0-9._+-]{1,40}").matches(appVersion)) { "Invalid app version" }
    }

    /** A valid GTIN-8/12/13/14 is required, including its check digit. */
    suspend fun lookup(barcode: String): OpenFoodFactsResult = withContext(Dispatchers.IO) {
        if (!isValidGtin(barcode)) return@withContext OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.INVALID_BARCODE)
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode?fields=product_name,brands,nutriments,serving_size,serving_quantity,code")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MyGarminCaloriesTracker/$appVersion (Android)")
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            currentCoroutineContext().ensureActive()
            when {
                status == 404 -> OpenFoodFactsResult.NotFound
                status !in 200..299 -> OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.HTTP, status)
                else -> {
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_BODY_BYTES) throw BodyTooLargeException()
                            output.write(buffer, 0, count)
                        }
                    }
                    parseOpenFoodFactsResponse(output.toString(Charsets.UTF_8.name()))
                }
            }
        } catch (e: BodyTooLargeException) {
            currentCoroutineContext().ensureActive()
            OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.RESPONSE_TOO_LARGE)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.NETWORK)
        } catch (e: SecurityException) {
            currentCoroutineContext().ensureActive()
            OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 256 * 1024
    }
}

private class BodyTooLargeException : IOException()

/** Uses Android's org.json only at the transport boundary. */
internal fun parseOpenFoodFactsResponse(body: String): OpenFoodFactsResult = try {
    val root = JSONObject(body)
    when (root.opt("status")?.toString()) {
        "0" -> OpenFoodFactsResult.NotFound
        "1" -> {
            val product = root.optJSONObject("product")
            if (product == null) {
                OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.INVALID_RESPONSE)
            } else {
                val nutrients = product.optJSONObject("nutriments")
                OpenFoodFactsResult.Success(productFromFields(OpenFoodFactsFields(
                    name = product.stringValue("product_name"),
                    brand = product.stringValue("brands"),
                    kcal = nutrients?.scalarValue("energy-kcal_100g"),
                    kilojoules = nutrients?.scalarValue("energy-kj_100g"),
                    servingQuantity = product.scalarValue("serving_quantity"),
                    servingSize = product.stringValue("serving_size")
                )))
            }
        }
        else -> OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.INVALID_RESPONSE)
    }
} catch (e: JSONException) {
    OpenFoodFactsResult.Error(OpenFoodFactsResult.Reason.INVALID_RESPONSE)
}

private fun JSONObject.stringValue(key: String): String? = opt(key) as? String
private fun JSONObject.scalarValue(key: String): String? = when (val value = opt(key)) {
    is String -> value
    is Number -> value.toString()
    else -> null
}
