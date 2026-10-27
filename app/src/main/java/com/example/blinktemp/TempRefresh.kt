package com.example.blinktemp

import android.content.Context
import android.text.format.DateFormat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Regular temperature refresh: wakes the selected cameras so they report a fresh reading,
 * then re-reads temperatures (which saves a history point and checks freeze alerts).
 *
 * Battery cameras only send a new temperature when they wake up; without this the
 * 30-minute refresh just re-reads the last value Blink has stored.
 */
object TempRefresh {
    private const val PERIODIC = "blink-temp-wake-periodic"
    const val ONE_SHOT = "blink-temp-wake-now"

    val INTERVALS = listOf(30, 60, 120, 180, 360)

    private const val GAP_BETWEEN_CAMERAS_MS = 5_000L
    private const val WAIT_BEFORE_READING_MS = 30_000L

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.isSignedIn || prefs.tempCameras.isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val minutes = prefs.tempIntervalMin.toLong()
        val req = PeriodicWorkRequestBuilder<TempRefreshWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<TempRefreshWorker>().setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)

    internal suspend fun run(ctx: Context): Unit = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        val stamp = DateFormat.getTimeFormat(ctx).format(Date())
        val api = BlinkApi(ctx)
        val cams = try {
            api.readCameras()
        } catch (e: Exception) {
            prefs.lastTempRefresh = "$stamp – failed: ${e.message}"
            return@withContext
        }

        val targets = cams.filter { it.id in prefs.tempCameras && it.tempF != null }
        val results = targets.mapIndexed { i, cam ->
            if (i > 0) delay(GAP_BETWEEN_CAMERAS_MS)
            if (!cam.online) return@mapIndexed "${cam.name}: offline"
            try {
                if (!prefs.tempWakeWithPhoto && api.requestStatus(cam)) {
                    "${cam.name}: ✓"
                } else {
                    api.snap(cam) // also used when the light request isn't supported for this camera
                    "${cam.name}: ✓ (photo)"
                }
            } catch (e: Exception) {
                "${cam.name}: ${e.message}"
            }
        }
        prefs.lastTempRefresh = "$stamp – " + results.joinToString(", ").ifEmpty { "no cameras selected" }

        if (results.any { "✓" in it }) delay(WAIT_BEFORE_READING_MS)
        Refresher.refresh(ctx) // fresh reading -> widget, history point, freeze check
    }
}

class TempRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Prefs(applicationContext).isSignedIn) return Result.success()
        TempRefresh.run(applicationContext)
        return Result.success()
    }
}
