package com.example.blinktemp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** Simple line chart of average temperature over the last 24 h or 7 days. No chart library needed. */
class TempChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    companion object {
        const val DAY = 24L * 3600 * 1000
        const val WEEK = 7 * DAY
        private const val GAP_MS = 3L * 3600 * 1000 // break the line if no reading for 3 h
    }

    private var points: List<Pair<Long, Double>> = emptyList() // (time, °F)
    private var rangeMs = DAY
    private var celsius = true
    private var thresholdF: Double? = null

    fun setData(points: List<Pair<Long, Double>>, rangeMs: Long, celsius: Boolean, thresholdF: Double?) {
        this.points = points
        this.rangeMs = rangeMs
        this.celsius = celsius
        this.thresholdF = thresholdF
        invalidate()
    }

    private val dp = resources.displayMetrics.density
    private val textColor = themeColor(android.R.attr.textColorSecondary)
    private val lineColor = 0xFF2E86DE.toInt()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor; style = Paint.Style.STROKE; strokeWidth = 2.5f * dp
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor; style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { color = textColor; alpha = 45; strokeWidth = 1f * dp }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE5533D.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f)
    }

    private fun themeColor(attr: Int): Int {
        val a = context.obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, Color.GRAY)
        a.recycle()
        return c
    }

    private fun conv(f: Double) = if (celsius) Temps.fToC(f) else f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 40 * dp
        val right = width - 8 * dp
        val top = 8 * dp
        val bottom = height - 22 * dp
        val now = System.currentTimeMillis()
        val start = now - rangeMs
        val pts = points.filter { it.first >= start }

        if (pts.isEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.chart_empty), width / 2f, height / 2f, labelPaint)
            return
        }

        // Y range: data ± 1°, include the freeze limit if it's nearby, at least 4° tall.
        val vals = pts.map { conv(it.second) }
        var lo = floor(vals.min() - 1)
        var hi = ceil(vals.max() + 1)
        val th = thresholdF?.let(::conv)
        if (th != null && th >= lo - 5 && th <= hi + 5) {
            lo = minOf(lo, floor(th - 1)); hi = maxOf(hi, ceil(th + 1))
        }
        if (hi - lo < 4) {
            val mid = (hi + lo) / 2; lo = floor(mid - 2); hi = ceil(mid + 2)
        }

        fun x(t: Long) = left + (t - start).toFloat() / rangeMs * (right - left)
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        // Horizontal grid + temperature labels
        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val v = lo + (hi - lo) * i / 4
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, gridPaint)
            canvas.drawText(String.format("%.0f°", v), left - 4 * dp, yy + labelPaint.textSize / 3, labelPaint)
        }

        // Vertical grid + time labels: every 6 h for a day, every midnight for a week
        labelPaint.textAlign = Paint.Align.CENTER
        val cal = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val timeFmt = DateFormat.getTimeFormat(context)
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        if (rangeMs <= DAY) {
            while (cal.get(Calendar.HOUR_OF_DAY) % 6 != 0 || cal.timeInMillis < start) cal.add(Calendar.HOUR_OF_DAY, 1)
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            while (cal.timeInMillis < start) cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        while (cal.timeInMillis <= now) {
            val xx = x(cal.timeInMillis)
            canvas.drawLine(xx, top, xx, bottom, gridPaint)
            val label = if (rangeMs <= DAY) timeFmt.format(Date(cal.timeInMillis)) else dayFmt.format(Date(cal.timeInMillis))
            canvas.drawText(label, xx, height - 6 * dp, labelPaint)
            if (rangeMs <= DAY) cal.add(Calendar.HOUR_OF_DAY, 6) else cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        // Freeze limit (dashed)
        if (th != null && th in lo..hi) {
            canvas.drawPath(Path().apply { moveTo(left, y(th)); lineTo(right, y(th)) }, thresholdPaint)
        }

        // Data line, broken where readings are missing; isolated points drawn as dots
        val path = Path()
        for (i in pts.indices) {
            val (t, f) = pts[i]
            val px = x(t); val py = y(conv(f))
            val gapBefore = i == 0 || t - pts[i - 1].first > GAP_MS
            val gapAfter = i == pts.lastIndex || pts[i + 1].first - t > GAP_MS
            if (gapBefore) path.moveTo(px, py) else path.lineTo(px, py)
            if (gapBefore && gapAfter) canvas.drawCircle(px, py, 2.5f * dp, dotPaint)
        }
        canvas.drawPath(path, linePaint)

        // Latest reading
        val (lt, lf) = pts.last()
        canvas.drawCircle(x(lt), y(conv(lf)), 4 * dp, dotPaint)
    }
}
