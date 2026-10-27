package com.example.blinktemp

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 7-day temperature history in a small JSON-lines file (one line per refresh, ~100 KB max).
 * Each entry stores every online camera's own reading, so the average is computed at display
 * time using the current "Average" checkboxes.
 */
object History {
    private const val FILE = "history.jsonl"
    const val KEEP_MS = 7L * 24 * 3600 * 1000

    /** [temps]: camera id -> °F (online cameras only). */
    data class Entry(val t: Long, val temps: Map<String, Double>)

    @Synchronized
    fun record(ctx: Context, cams: List<Camera>, t: Long) {
        if (!Prefs(ctx).historyEnabled) return
        val temps = cams.filter { it.online && it.tempF != null }.associate { it.id to it.tempF!! }
        if (temps.isEmpty()) return

        val file = File(ctx.filesDir, FILE)
        file.appendText(JSONObject().put("t", t).put("r", JSONObject(temps)).toString() + "\n")

        // Prune entries older than a week (rewrite only when something actually expired).
        val all = parse(file)
        val kept = all.filter { it.t >= t - KEEP_MS }
        if (kept.size < all.size) {
            file.writeText(kept.joinToString("") { e -> JSONObject().put("t", e.t).put("r", JSONObject(e.temps)).toString() + "\n" })
        }
    }

    @Synchronized
    fun load(ctx: Context): List<Entry> {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        return parse(File(ctx.filesDir, FILE)).filter { it.t >= cutoff }
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }

    /**
     * (time, average °F) using the cameras currently ticked for the average. Each camera's
     * series goes through the same spike filter, so spikes saved before the filter existed
     * disappear from the graph as well.
     */
    fun averages(entries: List<Entry>, prefs: Prefs): List<Pair<Long, Double>> {
        val last = mutableMapOf<String, SpikeFilter.Good>()
        return entries.mapNotNull { e ->
            val vals = e.temps.mapNotNull { (id, f) ->
                if (id in prefs.excluded) return@mapNotNull null
                val l = last[id]
                if (SpikeFilter.plausible(f, e.t, l)) {
                    last[id] = SpikeFilter.Good(f, e.t)
                    f
                } else {
                    SpikeFilter.hold(l, e.t)
                }
            }
            if (vals.isEmpty()) null else e.t to vals.average()
        }
    }

    data class MinMax(val minT: Long, val minF: Double, val maxT: Long, val maxF: Double)

    /** Lowest and highest average (with times) over the last [rangeMs], or null without data. */
    fun minMax(ctx: Context, prefs: Prefs, rangeMs: Long = 24L * 3600 * 1000): MinMax? {
        val since = System.currentTimeMillis() - rangeMs
        val pts = averages(load(ctx), prefs).filter { it.first >= since }
        if (pts.isEmpty()) return null
        val lo = pts.minBy { it.second }
        val hi = pts.maxBy { it.second }
        return MinMax(lo.first, lo.second, hi.first, hi.second)
    }

    private fun parse(file: File): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                val j = JSONObject(line)
                val r = j.getJSONObject("r")
                Entry(j.getLong("t"), r.keys().asSequence().associateWith { r.getDouble(it) })
            } catch (e: Exception) {
                null // skip a damaged line
            }
        }.sortedBy { it.t }
    }
}
