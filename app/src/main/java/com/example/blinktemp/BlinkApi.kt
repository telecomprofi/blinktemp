package com.example.blinktemp

import android.content.Context
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthException(msg: String) : Exception(msg)
class HttpException(val code: Int) : IOException("HTTP $code")

/**
 * Minimal client for Blink's *unofficial* API (same endpoints blinkpy / Home Assistant use).
 * All calls are blocking – call from Dispatchers.IO.
 */
class BlinkApi(context: Context) {
    private val prefs = Prefs(context)

    /** Step after the WebView login: trade the authorization code for tokens, then look up account/tier. */
    fun exchangeCode(code: String, verifier: String) {
        val body = FormBody.Builder()
            .add("app_brand", "blink")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
            .add("hardware_id", prefs.hardwareId)
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", SCOPE)
            .build()
        saveTokens(postToken(body))
        val tier = getJson("$BASE_URL/api/v1/users/tier_info")
        prefs.tier = tier.getString("tier")
        prefs.accountId = tier.getLong("account_id")
    }

    /** One homescreen call returns every device in every home (network) on the account. */
    fun readCameras(): List<Camera> {
        val hs = getJson("${restBase()}/api/v3/accounts/${prefs.accountId}/homescreen")

        val homes = mutableMapOf<Long, String>()
        hs.optJSONArray("networks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                homes[n.optLong("id")] = n.optString("name", "Home")
            }
        }

        val result = mutableListOf<Camera>()
        for (group in listOf("cameras", "doorbells", "owls")) {
            val arr = hs.optJSONArray(group) ?: continue
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val temp = c.optJSONObject("signals")?.optDouble("temp", Double.NaN)
                    ?.takeUnless { it.isNaN() || group == "owls" } // Minis have no sensor
                val rawId = c.optLong("id")
                result += Camera(
                    id = "$group:$rawId",
                    rawId = rawId,
                    networkId = c.optLong("network_id"),
                    type = group,
                    name = c.optString("name", "Camera"),
                    home = homes[c.optLong("network_id")] ?: "Home",
                    tempF = temp,
                    online = c.optString("status") != "offline",
                    batteryLow = c.optString("battery").equals("low", true) ||
                        c.optString("battery_state").equals("low", true),
                )
            }
        }
        return result.sortedWith(compareBy({ it.home }, { it.name }))
    }

    /** Ask a camera to take a new thumbnail (wakes battery cameras, which also updates their temperature). */
    fun snap(cam: Camera) {
        val acct = prefs.accountId
        val path = when (cam.type) {
            "owls" -> "/api/v1/accounts/$acct/networks/${cam.networkId}/owls/${cam.rawId}/thumbnail"
            "doorbells" -> "/api/v1/accounts/$acct/networks/${cam.networkId}/doorbells/${cam.rawId}/thumbnail"
            else -> "/network/${cam.networkId}/camera/${cam.rawId}/thumbnail"
        }
        call(restBase() + path, isPost = true)
    }

    /**
     * Light wake-up: asks the camera to report its status (no photo). This is an older endpoint
     * that not every camera/account supports; returns false when it isn't, so the caller can
     * fall back to [snap].
     */
    fun requestStatus(cam: Camera): Boolean {
        if (cam.type != "cameras") return false
        return try {
            call("${restBase()}/network/${cam.networkId}/camera/${cam.rawId}/status", isPost = true)
            true
        } catch (e: HttpException) {
            if (e.code in 400..499) false else throw e
        }
    }

    private fun restBase(): String {
        val tier = prefs.tier ?: throw AuthException("Not signed in")
        return "https://rest-$tier.immedia-semi.com"
    }

    // ---------------------------------------------------------------- internals

    private fun getJson(url: String): JSONObject = call(url, isPost = false)

    private fun call(url: String, isPost: Boolean, retried: Boolean = false): JSONObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${validToken()}")
            .header("User-Agent", REST_UA)
            .apply { if (isPost) post(ByteArray(0).toRequestBody(JSON)) else get() }
            .build()
        HTTP.newCall(req).execute().use { r ->
            if (r.code == 401 && !retried) {
                prefs.expiresAt = 0 // force a token refresh and try once more
                return call(url, isPost, retried = true)
            }
            if (r.code == 401) throw AuthException("Session expired – sign in again")
            if (r.code == 409) throw IOException("camera busy")
            if (!r.isSuccessful) throw HttpException(r.code)
            val text = r.body?.string().orEmpty()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun validToken(): String = synchronized(LOCK) {
        val token = prefs.accessToken
        if (token != null && System.currentTimeMillis() < prefs.expiresAt - 60_000) return token
        val refresh = prefs.refreshToken ?: throw AuthException("Not signed in")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .add("hardware_id", prefs.hardwareId)
            .build()
        saveTokens(postToken(body, isRefresh = true))
        prefs.accessToken!!
    }

    private fun postToken(body: RequestBody, isRefresh: Boolean = false): JSONObject {
        val req = Request.Builder().url("$OAUTH_BASE/oauth/token")
            .header("User-Agent", TOKEN_UA)
            .header("Accept", "*/*")
            .post(body).build()
        HTTP.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (isRefresh && r.code in 400..499) throw AuthException("Sign in again (refresh rejected, HTTP ${r.code})")
            if (!r.isSuccessful) throw IOException("Token request failed: HTTP ${r.code}")
            return JSONObject(text)
        }
    }

    private fun saveTokens(j: JSONObject) {
        prefs.accessToken = j.getString("access_token")
        j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.refreshToken = it } // tokens rotate
        prefs.expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
    }

    companion object {
        private val LOCK = Any()
        private val JSON = "application/json".toMediaType()
        private val HTTP = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

        // Values mirror blinkpy (github.com/fronzbot/blinkpy, helpers/constants.py).
        // If Blink changes its login again, these are the things to update.
        const val OAUTH_BASE = "https://api.oauth.blink.com"
        const val BASE_URL = "https://rest-prod.immedia-semi.com"
        const val CLIENT_ID = "ios"
        const val SCOPE = "client"
        const val REDIRECT_URI = "immedia-blink://applinks.blink.com/signin/callback"
        const val REDIRECT_SCHEME = "immedia-blink"
        const val WEB_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1"
        const val TOKEN_UA = "Blink/2511191620 CFNetwork/3860.200.71 Darwin/25.1.0"
        const val REST_UA = "27.0ANDROID_28373244"

        fun authorizeUrl(hardwareId: String, challenge: String): String =
            "$OAUTH_BASE/oauth/v2/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("app_brand", "blink")
                .addQueryParameter("app_version", "50.1")
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("device_brand", "Apple")
                .addQueryParameter("device_model", "iPhone16,1")
                .addQueryParameter("device_os_version", "26.1")
                .addQueryParameter("hardware_id", hardwareId)
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", SCOPE)
                .build().toString()
    }
}
