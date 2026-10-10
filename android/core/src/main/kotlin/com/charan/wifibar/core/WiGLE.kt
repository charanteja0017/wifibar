package com.charan.wifibar.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/** API name and token from https://wigle.net/account, sent with HTTP Basic auth. */
data class WiGLECredentials(val name: String, val token: String) {
    internal val authorizationHeader: String
        get() = "Basic " + Base64.getEncoder().encodeToString("$name:$token".toByteArray())
}

/** One record from WiGLE's `GET /api/v2/network/search` (`WiFiNetwork` in its API spec). */
data class WiGLENetwork(
    val trilat: Double?,
    val trilong: Double?,
    val ssid: String?,
    val netid: String?,
    val lastupdt: String?,
    val housenumber: String?,
    val road: String?,
    val city: String?,
    val region: String?,
    val country: String?,
) {
    val hasLocation: Boolean get() = trilat != null && trilong != null

    /** "12 Main St, Springfield, IL, US" from whatever parts WiGLE has. */
    val address: String?
        get() {
            val street = listOfNotNull(housenumber, road).filter { it.isNotEmpty() }.joinToString(" ")
            val parts = listOf(street, city, region, country).filter { !it.isNullOrEmpty() }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

    /** The date part of `lastupdt`, e.g. "2024-05-12". */
    val lastUpdated: String? get() = lastupdt?.take(10)
}

sealed class WiGLEError(message: String) : Exception(message) {
    data object Unauthorized : WiGLEError("WiGLE didn’t accept the API name and token. Check them at wigle.net/account.")
    data object RateLimited : WiGLEError("You’ve reached WiGLE’s query limit for today. Try again tomorrow.")
    class Failed(message: String) : WiGLEError(message) {
        override fun equals(other: Any?) = other is Failed && other.message == message
        override fun hashCode() = message.hashCode()
    }
}

class WiGLEClient(private val credentials: WiGLECredentials) {
    /** The located record for exactly this BSSID, or null when nobody has mapped it. Blocking: call off the main thread. */
    fun lookUp(bssid: String): WiGLENetwork? {
        val connection = URI(searchUrl(bssid)).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", credentials.authorizationHeader)
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return bestMatch(decode(body, status), bssid)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun searchUrl(bssid: String): String =
            "https://api.wigle.net/api/v2/network/search?netid=" + URLEncoder.encode(Bssid.normalize(bssid), "UTF-8")

        @Throws(WiGLEError::class)
        fun decode(body: String, status: Int): List<WiGLENetwork> {
            when (status) {
                401, 403 -> throw WiGLEError.Unauthorized
                429 -> throw WiGLEError.RateLimited
                in 200..299 -> Unit
                else -> throw WiGLEError.Failed("WiGLE returned HTTP $status.")
            }
            val root = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (_: Exception) {
                throw WiGLEError.Failed("WiGLE sent a response this app couldn’t read.")
            }
            if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) {
                val message = root.string("message") ?: "WiGLE couldn’t complete the search."
                if (message.contains("too many", ignoreCase = true)) throw WiGLEError.RateLimited
                throw WiGLEError.Failed(message)
            }
            val results = root["results"]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
            return results.map { element ->
                val o = element.jsonObject
                WiGLENetwork(
                    trilat = o.double("trilat"), trilong = o.double("trilong"),
                    ssid = o.string("ssid"), netid = o.string("netid"), lastupdt = o.string("lastupdt"),
                    housenumber = o.string("housenumber"), road = o.string("road"), city = o.string("city"),
                    region = o.string("region"), country = o.string("country"),
                )
            }
        }

        /** Only an exact BSSID match counts; a neighbour's record would put the pin in the wrong place. */
        fun bestMatch(results: List<WiGLENetwork>, bssid: String): WiGLENetwork? {
            val wanted = Bssid.normalize(bssid)
            return results.firstOrNull { it.hasLocation && it.netid?.let(Bssid::normalize) == wanted }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
        @Suppress("unused") private fun JsonElement.isNull() = this is JsonNull
    }
}
