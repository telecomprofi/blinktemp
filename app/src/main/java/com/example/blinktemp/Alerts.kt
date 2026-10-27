package com.example.blinktemp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Freeze alert, per camera: notifies once when a camera drops below the threshold, and clears the
 * notification when it recovers to threshold + 1 °C (hysteresis, so it doesn't flap around 0).
 */
object Alerts {
    private const val CHANNEL = "freeze_alerts"
    private const val HYSTERESIS_C = 1.0

    /** Threshold choices in °C. */
    val THRESHOLDS = (-10..10).toList()

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Called after every successful temperature refresh. */
    fun check(ctx: Context, cams: List<Camera>) {
        val prefs = Prefs(ctx)
        if (!prefs.alertEnabled) return
        val limitC = prefs.alertThresholdC.toDouble()
        // Every online camera with a sensor that isn't opted out. Offline cameras are always
        // skipped: Blink keeps showing their last (stale) temperature.
        val readings: Map<String, Pair<String, Double>> = cams
            .filter { it.tempF != null && it.online && it.id !in prefs.alertExcluded }
            .associate { c -> c.id to ("${c.name} (${c.home})" to c.tempF!!) }

        val alerted = prefs.alerted.toMutableSet()
        readings.forEach { (key, reading) ->
            val (label, f) = reading
            val c = Temps.fToC(f)
            if (c < limitC && key !in alerted) {
                notify(ctx, key, label, f)
                alerted += key
            } else if (c >= limitC + HYSTERESIS_C && key in alerted) {
                NotificationManagerCompat.from(ctx).cancel(key.hashCode())
                alerted -= key
            }
        }
        prefs.alerted = alerted.filterTo(mutableSetOf()) { it in readings.keys }
    }

    /** Settings changed: forget past alerts, clear notifications, and re-evaluate the cached reading. */
    fun reset(ctx: Context) {
        val prefs = Prefs(ctx)
        prefs.alerted.forEach { NotificationManagerCompat.from(ctx).cancel(it.hashCode()) }
        prefs.alerted = emptySet()
        Snapshot.fromJson(prefs.cache)?.let { check(ctx, it.cameras) }
    }

    fun sendTest(ctx: Context) =
        notify(ctx, "test", ctx.getString(R.string.alert_test_label), Temps.cToF(Prefs(ctx).alertThresholdC - 1.5))

    private fun notify(ctx: Context, key: String, label: String, tempF: Double) {
        if (!hasPermission(ctx)) return
        ensureChannel(ctx)
        val prefs = Prefs(ctx)
        val limit = Temps.format(Temps.cToF(prefs.alertThresholdC.toDouble()), prefs.useCelsius)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_freeze)
            .setContentTitle(ctx.getString(R.string.alert_title, limit))
            .setContentText("$label: ${Temps.format(tempF, prefs.useCelsius)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
        } catch (e: SecurityException) {
            // permission revoked between check and notify
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
