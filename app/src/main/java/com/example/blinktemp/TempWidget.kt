package com.example.blinktemp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.util.SizeF
import android.widget.RemoteViews
import java.util.Date

/**
 * Regular widget (default 2×2). On Android 12+ it is responsive: shrink it to 1×1 and it
 * switches to the compact layout automatically.
 */
open class TempWidget : AppWidgetProvider() {

    /** True for the dedicated 1×1 widget. */
    protected open val compactOnly = false

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context, compactOnly))
        if (Prefs(context).isSignedIn) Refresher.schedule(context)
    }

    override fun onEnabled(context: Context) {
        if (Prefs(context).isSignedIn) Refresher.refreshNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAll(context, refreshing = true)
            Refresher.refreshNow(context)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.blinktemp.REFRESH"

        /** Redraw every placed widget of both kinds. */
        fun updateAll(ctx: Context, refreshing: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            listOf(TempWidget::class.java to false, TempWidgetSmall::class.java to true).forEach { (cls, compact) ->
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx, compact, refreshing))
            }
        }

        private fun views(ctx: Context, compactOnly: Boolean, refreshing: Boolean = false): RemoteViews {
            val s = State.load(ctx, refreshing)
            return when {
                compactOnly -> compact(ctx, s)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> RemoteViews(
                    mapOf(SizeF(40f, 40f) to compact(ctx, s), SizeF(100f, 80f) to full(ctx, s))
                )
                else -> full(ctx, s)
            }
        }

        /** Everything both layouts need, computed once. */
        private class State(
            val signedIn: Boolean,
            val celsius: Boolean,
            val cams: List<Camera>,
            val avg: Double?,
            val refreshing: Boolean,
            val error: String?,
            val updatedAt: Long,
            val minMax: History.MinMax?,
        ) {
            companion object {
                fun load(ctx: Context, refreshing: Boolean): State {
                    val prefs = Prefs(ctx)
                    val snap = Snapshot.fromJson(prefs.cache)
                    val cams = Temps.usable(snap?.cameras.orEmpty(), prefs)
                    return State(
                        prefs.isSignedIn, prefs.useCelsius, cams, Temps.average(cams),
                        refreshing, snap?.error, snap?.updatedAt ?: 0,
                        if (prefs.isSignedIn) History.minMax(ctx, prefs) else null,
                    )
                }
            }
        }

        private fun openAppIntent(ctx: Context) = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        private fun refreshIntent(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, TempWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun time(ctx: Context, millis: Long) = DateFormat.getTimeFormat(ctx).format(Date(millis))

        /** 2×2 and larger: average, per-home breakdown, status. Number = refresh, rest = open app. */
        private fun full(ctx: Context, s: State): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_temp)
            v.setOnClickPendingIntent(R.id.widget_root, openAppIntent(ctx))

            if (!s.signedIn) {
                v.setTextViewText(R.id.widget_temp, "--")
                v.setTextViewText(R.id.widget_label, ctx.getString(R.string.tap_to_sign_in))
                v.setTextViewText(R.id.widget_status, "")
                v.setTextViewText(R.id.widget_minmax, "")
                return v
            }
            v.setOnClickPendingIntent(R.id.widget_temp, refreshIntent(ctx))
            v.setTextViewText(
                R.id.widget_minmax,
                s.minMax?.let {
                    ctx.getString(R.string.widget_minmax, Temps.formatShort(it.minF, s.celsius), Temps.formatShort(it.maxF, s.celsius))
                } ?: ""
            )
            v.setTextViewText(R.id.widget_temp, s.avg?.let { Temps.format(it, s.celsius) } ?: "--")

            val byHome = s.cams.groupBy { it.home }
            v.setTextViewText(
                R.id.widget_label,
                if (byHome.size > 1)
                    byHome.entries.joinToString("  ·  ") { (home, list) -> "$home ${Temps.format(Temps.average(list)!!, s.celsius)}" }
                else
                    ctx.resources.getQuantityString(R.plurals.avg_of_cameras, s.cams.size, s.cams.size)
            )
            v.setTextViewText(
                R.id.widget_status,
                when {
                    s.refreshing -> ctx.getString(R.string.refreshing)
                    s.error != null -> "⚠ ${s.error}"
                    s.updatedAt > 0 -> ctx.getString(R.string.updated_at, time(ctx, s.updatedAt))
                    else -> ctx.getString(R.string.tap_to_refresh)
                }
            )
            return v
        }

        /** 1×1: just the number and a tiny status line. Tap = refresh (or sign in). */
        private fun compact(ctx: Context, s: State): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_temp_small)
            if (!s.signedIn) {
                v.setOnClickPendingIntent(R.id.widget_root, openAppIntent(ctx))
                v.setTextViewText(R.id.widget_temp, "--")
                v.setTextViewText(R.id.widget_status, ctx.getString(R.string.sign_in_short))
                return v
            }
            v.setOnClickPendingIntent(R.id.widget_root, refreshIntent(ctx))
            v.setTextViewText(R.id.widget_temp, s.avg?.let { Temps.formatShort(it, s.celsius) } ?: "--")
            v.setTextViewText(
                R.id.widget_status,
                when {
                    s.refreshing -> "…"
                    s.error != null -> "⚠"
                    s.updatedAt > 0 -> time(ctx, s.updatedAt)
                    else -> ""
                }
            )
            return v
        }
    }
}

/** Separate 1×1 entry in the widget picker. */
class TempWidgetSmall : TempWidget() {
    override val compactOnly = true
}
