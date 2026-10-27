package com.example.blinktemp

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** Small wrapper around SharedPreferences for tokens, settings and the last reading. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("blink_temp", Context.MODE_PRIVATE)

    /** Blink rejects non-UUID hardware ids, and it must stay stable for refresh tokens to keep working. */
    val hardwareId: String
        get() = sp.getString("hardware_id", null)
            ?: UUID.randomUUID().toString().uppercase().also { id -> sp.edit { putString("hardware_id", id) } }

    var accessToken: String?
        get() = sp.getString("access_token", null)
        set(v) = sp.edit { putString("access_token", v) }

    var refreshToken: String?
        get() = sp.getString("refresh_token", null)
        set(v) = sp.edit { putString("refresh_token", v) }

    var expiresAt: Long
        get() = sp.getLong("expires_at", 0)
        set(v) = sp.edit { putLong("expires_at", v) }

    var tier: String?
        get() = sp.getString("tier", null)
        set(v) = sp.edit { putString("tier", v) }

    var accountId: Long
        get() = sp.getLong("account_id", 0)
        set(v) = sp.edit { putLong("account_id", v) }

    var useCelsius: Boolean
        get() = sp.getBoolean("celsius", true)
        set(v) = sp.edit { putBoolean("celsius", v) }

    /** Camera ids the user unchecked (not counted in the average). */
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("excluded", v) }

    /** Last Snapshot as JSON. */
    var cache: String?
        get() = sp.getString("cache", null)
        set(v) = sp.edit { putString("cache", v) }

    /** Ignore cameras Blink reports as offline (their temperature is a stale last value). */
    var skipOffline: Boolean
        get() = sp.getBoolean("skip_offline", true)
        set(v) = sp.edit { putBoolean("skip_offline", v) }

    // ---- scheduled image refresh

    /** Camera ids that get a new thumbnail on schedule. */
    var snapCameras: Set<String>
        get() = sp.getStringSet("snap_cameras", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("snap_cameras", v) }

    var snapIntervalMin: Int
        get() = sp.getInt("snap_interval", 60)
        set(v) = sp.edit { putInt("snap_interval", v) }

    /** Only refresh images between snapFrom and snapTo (minutes after midnight; may wrap midnight). */
    var snapWindowEnabled: Boolean
        get() = sp.getBoolean("snap_window", false)
        set(v) = sp.edit { putBoolean("snap_window", v) }

    var snapFrom: Int
        get() = sp.getInt("snap_from", 7 * 60)
        set(v) = sp.edit { putInt("snap_from", v) }

    var snapTo: Int
        get() = sp.getInt("snap_to", 22 * 60)
        set(v) = sp.edit { putInt("snap_to", v) }

    var lastSnap: String?
        get() = sp.getString("last_snap", null)
        set(v) = sp.edit { putString("last_snap", v) }

    // ---- regular temperature refresh (wake cameras)

    var tempCameras: Set<String>
        get() = sp.getStringSet("temp_cameras", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("temp_cameras", v) }

    var tempIntervalMin: Int
        get() = sp.getInt("temp_interval", 60)
        set(v) = sp.edit { putInt("temp_interval", v) }

    /** Wake with a snapshot instead of the light status request. */
    var tempWakeWithPhoto: Boolean
        get() = sp.getBoolean("temp_wake_photo", false)
        set(v) = sp.edit { putBoolean("temp_wake_photo", v) }

    var lastTempRefresh: String?
        get() = sp.getString("last_temp_refresh", null)
        set(v) = sp.edit { putString("last_temp_refresh", v) }

    /** SpikeFilter state: camera id -> last accepted reading (JSON). */
    var lastGood: String?
        get() = sp.getString("last_good", null)
        set(v) = sp.edit { putString("last_good", v) }

    var historyEnabled: Boolean
        get() = sp.getBoolean("history_enabled", true)
        set(v) = sp.edit { putBoolean("history_enabled", v) }

    // ---- freeze alert

    var alertEnabled: Boolean
        get() = sp.getBoolean("alert_enabled", true)
        set(v) = sp.edit { putBoolean("alert_enabled", v) }

    var alertThresholdC: Int
        get() = sp.getInt("alert_threshold_c", 0)
        set(v) = sp.edit { putInt("alert_threshold_c", v) }

    /** Camera ids the user unticked for freeze alerts (new cameras are included by default). */
    var alertExcluded: Set<String>
        get() = sp.getStringSet("alert_excluded", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("alert_excluded", v) }

    /** Camera ids currently below the threshold and already notified. */
    var alerted: Set<String>
        get() = sp.getStringSet("alerted", emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet("alerted", v) }

    val isSignedIn: Boolean get() = refreshToken != null && tier != null && accountId != 0L

    fun signOut() = sp.edit {
        listOf("access_token", "refresh_token", "expires_at", "tier", "account_id", "cache", "last_snap")
            .forEach { remove(it) }
    }
}
