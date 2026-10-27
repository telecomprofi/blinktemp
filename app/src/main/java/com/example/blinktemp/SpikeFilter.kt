package com.example.blinktemp

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Drops implausible sensor readings (e.g. an outdoor camera suddenly reporting -67.8 °C):
 *  - anything outside [MIN_C, MAX_C] is a sensor error, always;
 *  - a change of more than MAX_C_PER_HOUR per hour since the camera's last accepted reading.
 *
 * A rejected reading is replaced by the camera's last good value if that is at most
 * HOLD_MS old (keeps the average and graph smooth); otherwise the camera simply has no
 * reading this time. Rejected readings never trigger freeze alerts.
 */
object SpikeFilter {
    private const val MAX_C_PER_HOUR = 10.0
    private const val MIN_C = -50.0
    private const val MAX_C = 70.0
    private const val HOUR_MS = 3_600_000.0
    const val HOLD_MS = 2 * 3_600_000L

    data class Good(val f: Double, val t: Long)

    fun plausible(f: Double, t: Long, last: Good?): Boolean {
        val c = Temps.fToC(f)
        if (c < MIN_C || c > MAX_C) return false
        if (last == null) return true
        val hours = max(1.0, (t - last.t) / HOUR_MS) // allowed change grows if the camera was quiet
        return abs(c - Temps.fToC(last.f)) <= MAX_C_PER_HOUR * hours
    }

    /** Last good value to show instead of a rejected reading, or null if too old / none. */
    fun hold(last: Good?, t: Long): Double? = last?.takeIf { t - it.t <= HOLD_MS }?.f

    /** Filter a fresh read from Blink, remembering each camera's last accepted value. */
    fun apply(ctx: Context, cams: List<Camera>, now: Long): List<Camera> {
        val prefs = Prefs(ctx)
        val good = load(prefs.lastGood).toMutableMap()
        val out = cams.map { cam ->
            val f = cam.tempF
            if (f == null || !cam.online) return@map cam // offline values are ignored elsewhere anyway
            val last = good[cam.id]
            if (plausible(f, now, last)) {
                good[cam.id] = Good(f, now)
                cam
            } else {
                cam.copy(tempF = hold(last, now), spikeF = f)
            }
        }
        prefs.lastGood = save(good)
        return out
    }

    private fun load(s: String?): Map<String, Good> = try {
        val j = JSONObject(s ?: "{}")
        j.keys().asSequence().associateWith { k ->
            j.getJSONObject(k).let { Good(it.getDouble("f"), it.getLong("t")) }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun save(m: Map<String, Good>): String = JSONObject().apply {
        m.forEach { (k, g) -> put(k, JSONObject().put("f", g.f).put("t", g.t)) }
    }.toString()
}
