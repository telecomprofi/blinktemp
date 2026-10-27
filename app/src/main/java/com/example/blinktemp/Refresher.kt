package com.example.blinktemp

import android.content.Context
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
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Refresher {
    private const val PERIODIC = "blink-temp-periodic"
    private const val ONE_SHOT = "blink-temp-now"
    private const val INTERVAL_MINUTES = 30L // Android minimum is 15; be gentle with Blink's servers

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Fetch temperatures, cache them, and redraw the widget. Never throws. */
    suspend fun refresh(ctx: Context): Snapshot = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        val previous = Snapshot.fromJson(prefs.cache)
        val snap = try {
            val now = System.currentTimeMillis()
            val cams = SpikeFilter.apply(ctx, BlinkApi(ctx).readCameras(), now)
            Alerts.check(ctx, cams)
            History.record(ctx, cams, now)
            Snapshot(cams, now, null)
        } catch (e: Exception) {
            val msg = if (e is AuthException) e.message else "Offline / Blink error (${e.message})"
            Snapshot(previous?.cameras.orEmpty(), previous?.updatedAt ?: 0, msg)
        }
        prefs.cache = snap.toJson()
        TempWidget.updateAll(ctx)
        snap
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(online).build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Prefs(applicationContext).isSignedIn) return Result.success()
        Refresher.refresh(applicationContext)
        return Result.success()
    }
}
