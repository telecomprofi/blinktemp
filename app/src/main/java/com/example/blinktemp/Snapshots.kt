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
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/** Scheduled "take a new thumbnail" for the cameras the user picked. */
object Snapshots {
    private const val PERIODIC = "blink-snap-periodic"
    const val ONE_SHOT = "blink-snap-now"
    private const val KEY_FORCE = "force"

    /** Choices offered in the UI (minutes). 15 is Android's minimum for periodic work. */
    val INTERVALS = listOf(15, 30, 60, 120, 180, 360, 720, 1440)

    private const val GAP_BETWEEN_CAMERAS_MS = 5_000L   // Blink throttles rapid commands
    private const val WAIT_BEFORE_READING_MS = 30_000L  // let cameras upload before re-reading temps

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** (Re)create the periodic job from current settings; cancels it if nothing is selected. */
    fun schedule(ctx: Context) {
        val prefs = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.isSignedIn || prefs.snapCameras.isEmpty()) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val minutes = prefs.snapIntervalMin.toLong()
        val req = PeriodicWorkRequestBuilder<SnapshotWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES) // don't fire immediately on every settings tweak
            .setConstraints(online)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
    }

    /** Run once right now, ignoring the active-hours window. */
    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SnapshotWorker>()
            .setInputData(workDataOf(KEY_FORCE to true))
            .setConstraints(online)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)

    fun inWindow(prefs: Prefs): Boolean {
        if (!prefs.snapWindowEnabled) return true
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val from = prefs.snapFrom
        val to = prefs.snapTo
        return if (from <= to) now in from until to else now >= from || now < to // window may cross midnight
    }

    internal suspend fun run(ctx: Context, force: Boolean): Unit = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        val stamp = DateFormat.getTimeFormat(ctx).format(Date())
        if (!force && !inWindow(prefs)) return@withContext

        val api = BlinkApi(ctx)
        val cams = try {
            api.readCameras()
        } catch (e: Exception) {
            prefs.lastSnap = "$stamp – failed: ${e.message}"
            return@withContext
        }

        val targets = cams.filter { it.id in prefs.snapCameras }
        val results = targets.mapIndexed { i, cam ->
            if (i > 0) delay(GAP_BETWEEN_CAMERAS_MS)
            when {
                !cam.online -> "${cam.name}: offline"
                else -> try {
                    api.snap(cam); "${cam.name}: ✓"
                } catch (e: Exception) {
                    "${cam.name}: ${e.message}"
                }
            }
        }
        prefs.lastSnap = "$stamp – " + (results.joinToString(", ").ifEmpty { "no cameras selected" })

        if (results.any { it.endsWith("✓") }) {
            delay(WAIT_BEFORE_READING_MS)
            Refresher.refresh(ctx) // woken cameras report a fresh temperature
        }
    }
}

class SnapshotWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Prefs(applicationContext).isSignedIn) return Result.success()
        Snapshots.run(applicationContext, force = inputData.getBoolean("force", false))
        return Result.success()
    }
}
