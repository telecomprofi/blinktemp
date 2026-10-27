package com.example.blinktemp

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One Blink device. [type] is the homescreen group: "cameras", "doorbells" or "owls" (Minis).
 * [tempF] is null for devices without a temperature sensor. Blink reports °F.
 */
data class Camera(
    val id: String,
    val rawId: Long,
    val networkId: Long,
    val type: String,
    val name: String,
    val home: String,
    val tempF: Double?,
    val online: Boolean,
    val batteryLow: Boolean,
    /** Raw reading rejected by [SpikeFilter] (tempF then holds the last good value, or null). */
    val spikeF: Double? = null,
) {
    val hasSensor: Boolean get() = tempF != null || spikeF != null
}

data class Snapshot(val cameras: List<Camera>, val updatedAt: Long, val error: String?) {

    fun toJson(): String = JSONObject().apply {
        put("v", 2)
        put("updatedAt", updatedAt)
        error?.let { put("error", it) }
        put("cameras", JSONArray().apply {
            cameras.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("rawId", c.rawId); put("networkId", c.networkId)
                    put("type", c.type); put("name", c.name); put("home", c.home)
                    c.tempF?.let { put("tempF", it) }
                    put("online", c.online); put("batteryLow", c.batteryLow)
                    c.spikeF?.let { put("spikeF", it) }
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(s: String?): Snapshot? = try {
            val j = JSONObject(s!!)
            require(j.optInt("v") == 2) // older cache format -> ignore, next refresh rebuilds it
            val arr = j.getJSONArray("cameras")
            Snapshot(
                cameras = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    Camera(
                        id = c.getString("id"), rawId = c.getLong("rawId"), networkId = c.getLong("networkId"),
                        type = c.getString("type"), name = c.getString("name"), home = c.getString("home"),
                        tempF = if (c.has("tempF")) c.getDouble("tempF") else null,
                        online = c.optBoolean("online", true), batteryLow = c.optBoolean("batteryLow"),
                        spikeF = if (c.has("spikeF")) c.getDouble("spikeF") else null,
                    )
                },
                updatedAt = j.optLong("updatedAt"),
                error = if (j.has("error")) j.getString("error") else null,
            )
        } catch (e: Exception) {
            null
        }
    }
}

object Temps {
    /** Cameras that count toward the average with the current settings. */
    fun usable(cams: List<Camera>, prefs: Prefs): List<Camera> = cams.filter {
        it.tempF != null && it.id !in prefs.excluded && (it.online || !prefs.skipOffline)
    }

    fun average(cams: List<Camera>): Double? =
        cams.mapNotNull { it.tempF }.takeIf { it.isNotEmpty() }?.average()

    fun fToC(f: Double) = (f - 32.0) * 5.0 / 9.0
    fun cToF(c: Double) = c * 9.0 / 5.0 + 32.0
    fun cToF(c: Int) = cToF(c.toDouble())

    fun format(f: Double, celsius: Boolean): String =
        if (celsius) String.format("%.1f°C", (f - 32.0) * 5.0 / 9.0) else String.format("%.1f°F", f)

    /** For the 1×1 widget: no unit letter, °F without decimals. */
    fun formatShort(f: Double, celsius: Boolean): String =
        if (celsius) String.format("%.1f°", (f - 32.0) * 5.0 / 9.0) else String.format("%.0f°", f)
}

/** PKCE helpers for the OAuth authorization-code flow. */
object Pkce {
    fun verifier(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.b64()
    fun challenge(verifier: String): String =
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).b64()

    private fun ByteArray.b64() =
        Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
