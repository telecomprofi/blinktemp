package com.example.blinktemp

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import android.text.format.DateFormat
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.Date

/** Settings screen: sign in/out, units, which cameras to average, scheduled image refresh. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var avgView: TextView
    private lateinit var statusView: TextView
    private lateinit var listView: LinearLayout
    private lateinit var signBtn: Button
    private lateinit var refreshBtn: Button
    private lateinit var snapNowBtn: Button
    private lateinit var lastSnapView: TextView
    private lateinit var fromBtn: Button
    private lateinit var toBtn: Button

    private lateinit var tempNowBtn: Button
    private lateinit var lastTempView: TextView
    private lateinit var minMaxView: TextView
    private lateinit var chart: TempChartView
    private lateinit var chartSummary: TextView
    private lateinit var rangeGroup: RadioGroup
    private lateinit var alertSwitch: SwitchCompat
    private lateinit var thresholdSpinner: Spinner

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Alerts.reset(this)
            else Toast.makeText(this, R.string.alert_permission_denied, Toast.LENGTH_LONG).show()
        }

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        prefs = Prefs(this)
        avgView = findViewById(R.id.avg)
        statusView = findViewById(R.id.status)
        listView = findViewById(R.id.camera_list)
        signBtn = findViewById(R.id.btn_sign)
        refreshBtn = findViewById(R.id.btn_refresh)
        snapNowBtn = findViewById(R.id.btn_snap_now)
        lastSnapView = findViewById(R.id.last_snap)
        fromBtn = findViewById(R.id.btn_from)
        toBtn = findViewById(R.id.btn_to)

        bindSwitch(R.id.unit_switch, prefs.useCelsius) {
            prefs.useCelsius = it
            tempSettingsChanged(full = true)
            if (::thresholdSpinner.isInitialized) fillThresholds()
        }
        bindSwitch(R.id.skip_offline_switch, prefs.skipOffline) { prefs.skipOffline = it; tempSettingsChanged(full = true) }

        signBtn.setOnClickListener {
            if (prefs.isSignedIn) {
                prefs.signOut()
                Refresher.cancel(this)
                Snapshots.cancel(this)
                TempRefresh.cancel(this)
                TempWidget.updateAll(this)
                render()
            } else {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
        }

        refreshBtn.setOnClickListener {
            statusView.setText(R.string.refreshing)
            lifecycleScope.launch {
                Refresher.refresh(applicationContext)
                render()
            }
        }

        setupHistory()
        setupTempRefresh()
        setupSnapshotSettings()
        setupAlertSettings()
    }

    override fun onResume() {
        super.onResume()
        render()
        // First launch after an update: cache is in the old format, so load fresh data.
        if (prefs.isSignedIn && Snapshot.fromJson(prefs.cache) == null) refreshBtn.performClick()
    }

    // ------------------------------------------------------------ scheduled image refresh

    private fun setupSnapshotSettings() {
        val spinner = findViewById<Spinner>(R.id.interval_spinner)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Snapshots.INTERVALS.map(::intervalLabel)
        )
        spinner.setSelection(Snapshots.INTERVALS.indexOf(prefs.snapIntervalMin).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val minutes = Snapshots.INTERVALS[pos]
                if (minutes != prefs.snapIntervalMin) {
                    prefs.snapIntervalMin = minutes
                    Snapshots.schedule(this@MainActivity)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // The window is checked each time the job runs, so no reschedule is needed here.
        bindSwitch(R.id.window_switch, prefs.snapWindowEnabled) { prefs.snapWindowEnabled = it; updateWindowViews() }
        fromBtn.setOnClickListener { pickTime(prefs.snapFrom) { prefs.snapFrom = it; updateWindowViews() } }
        toBtn.setOnClickListener { pickTime(prefs.snapTo) { prefs.snapTo = it; updateWindowViews() } }

        snapNowBtn.setOnClickListener {
            Snapshots.runNow(this)
            lastSnapView.setText(R.string.snap_requested)
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(Snapshots.ONE_SHOT)
            .observe(this) { infos -> if (infos.any { it.state.isFinished }) render() }
    }

    private fun updateWindowViews() {
        fromBtn.text = hhmm(prefs.snapFrom)
        toBtn.text = hhmm(prefs.snapTo)
        fromBtn.isEnabled = prefs.snapWindowEnabled
        toBtn.isEnabled = prefs.snapWindowEnabled
    }

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this, { _, h, m -> onPicked(h * 60 + m) },
            current / 60, current % 60, DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun intervalLabel(m: Int) = when {
        m < 60 -> getString(R.string.every_minutes, m)
        m == 60 -> getString(R.string.every_hour)
        m < 1440 -> getString(R.string.every_hours, m / 60)
        else -> getString(R.string.once_a_day)
    }

    private fun hhmm(min: Int) = String.format("%02d:%02d", min / 60, min % 60)

    // ------------------------------------------------------------ history chart

    private fun setupHistory() {
        minMaxView = findViewById(R.id.minmax_24h)
        chart = findViewById(R.id.chart)
        chartSummary = findViewById(R.id.chart_summary)
        rangeGroup = findViewById(R.id.range_group)
        rangeGroup.setOnCheckedChangeListener { _, _ -> renderChart() }
        bindSwitch(R.id.history_switch, prefs.historyEnabled) { prefs.historyEnabled = it; renderChart() }
        findViewById<Button>(R.id.btn_clear_history).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton(R.string.history_clear) { _, _ -> History.clear(this); renderChart() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun renderChart() {
        val range = if (rangeGroup.checkedRadioButtonId == R.id.range_week) TempChartView.WEEK else TempChartView.DAY
        val since = System.currentTimeMillis() - range
        val pts = History.averages(History.load(this), prefs).filter { it.first >= since }
        val limitF = if (prefs.alertEnabled) Temps.cToF(prefs.alertThresholdC) else null
        chart.setData(pts, range, prefs.useCelsius, limitF)

        val fmtTime = DateFormat.getTimeFormat(this)
        minMaxView.text = History.minMax(this, prefs)?.let { m ->
            getString(
                R.string.minmax_24h,
                Temps.format(m.minF, prefs.useCelsius), fmtTime.format(Date(m.minT)),
                Temps.format(m.maxF, prefs.useCelsius), fmtTime.format(Date(m.maxT)),
            )
        } ?: getString(R.string.minmax_24h_none)

        val c = prefs.useCelsius
        chartSummary.text = when {
            !prefs.historyEnabled && pts.isEmpty() -> getString(R.string.history_off)
            pts.isEmpty() -> ""
            else -> {
                val v = pts.map { it.second }
                getString(R.string.chart_summary, Temps.format(v.min(), c), Temps.format(v.max(), c), Temps.format(v.average(), c))
            }
        }
    }

    // ------------------------------------------------------------ regular temperature refresh

    private fun setupTempRefresh() {
        tempNowBtn = findViewById(R.id.btn_temp_now)
        lastTempView = findViewById(R.id.last_temp_refresh)

        val spinner = findViewById<Spinner>(R.id.temp_interval_spinner)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, TempRefresh.INTERVALS.map(::intervalLabel)
        )
        spinner.setSelection(TempRefresh.INTERVALS.indexOf(prefs.tempIntervalMin).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val minutes = TempRefresh.INTERVALS[pos]
                if (minutes != prefs.tempIntervalMin) {
                    prefs.tempIntervalMin = minutes
                    TempRefresh.schedule(this@MainActivity)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        bindSwitch(R.id.temp_photo_switch, prefs.tempWakeWithPhoto) { prefs.tempWakeWithPhoto = it }

        tempNowBtn.setOnClickListener {
            TempRefresh.runNow(this)
            lastTempView.setText(R.string.temp_requested)
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(TempRefresh.ONE_SHOT)
            .observe(this) { infos -> if (infos.any { it.state.isFinished }) render() }
    }

    // ------------------------------------------------------------ freeze alert

    private fun setupAlertSettings() {
        alertSwitch = findViewById(R.id.alert_switch)
        thresholdSpinner = findViewById(R.id.alert_threshold_spinner)

        alertSwitch.isChecked = prefs.alertEnabled
        alertSwitch.setOnCheckedChangeListener { _, on ->
            prefs.alertEnabled = on
            thresholdSpinner.isEnabled = on
            render()
            if (on) requestNotificationsIfNeeded()
            Alerts.reset(this)
        }
        thresholdSpinner.isEnabled = prefs.alertEnabled

        fillThresholds()
        thresholdSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val c = Alerts.THRESHOLDS[pos]
                if (c != prefs.alertThresholdC) {
                    prefs.alertThresholdC = c
                    Alerts.reset(this@MainActivity)
                    renderChart()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btn_alert_test).setOnClickListener {
            if (Alerts.hasPermission(this)) Alerts.sendTest(this) else requestNotificationsIfNeeded()
        }

        if (prefs.alertEnabled) requestNotificationsIfNeeded()
    }

    /** Thresholds are stored in °C; labels follow the chosen unit. */
    private fun fillThresholds() {
        thresholdSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Alerts.THRESHOLDS.map { c ->
                if (prefs.useCelsius) "$c °C" else String.format("%.0f °F", Temps.cToF(c))
            }
        )
        thresholdSpinner.setSelection(Alerts.THRESHOLDS.indexOf(prefs.alertThresholdC).coerceAtLeast(0))
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.hasPermission(this)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------ rendering

    private fun render() {
        val signedIn = prefs.isSignedIn
        signBtn.setText(if (signedIn) R.string.sign_out else R.string.sign_in)
        refreshBtn.isEnabled = signedIn
        snapNowBtn.isEnabled = signedIn && prefs.snapCameras.isNotEmpty()

        val snap = Snapshot.fromJson(prefs.cache)
        renderAverage(snap)
        statusView.text = when {
            !signedIn -> getString(R.string.not_signed_in)
            snap == null -> getString(R.string.tap_refresh_hint)
            snap.error != null -> "⚠ ${snap.error}"
            else -> getString(R.string.updated_at, DateFormat.getTimeFormat(this).format(Date(snap.updatedAt)))
        }
        lastSnapView.text = prefs.lastSnap ?: getString(R.string.snap_never)
        lastTempView.text = prefs.lastTempRefresh ?: getString(R.string.temp_never)
        tempNowBtn.isEnabled = signedIn && prefs.tempCameras.isNotEmpty()
        renderChart()
        updateWindowViews()

        listView.removeAllViews()
        snap?.cameras?.groupBy { it.home }?.forEach { (home, cams) ->
            listView.addView(TextView(this).apply {
                text = home
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 32, 0, 4)
            })
            cams.forEach { listView.addView(cameraRow(it)) }
        }
    }

    private fun cameraRow(cam: Camera): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 12, 0, 4)

        addView(TextView(context).apply { text = cam.name; textSize = 16f })

        val details = buildList {
            cam.tempF?.let { add(Temps.format(it, prefs.useCelsius)) }
            add(getString(if (cam.online) R.string.online else R.string.offline))
            if (cam.batteryLow) add(getString(R.string.battery_low))
            cam.spikeF?.let { add(getString(R.string.spike_ignored, Temps.format(it, prefs.useCelsius))) }
            if (!cam.hasSensor) add(getString(R.string.no_temp_sensor))
            if (!cam.online && cam.tempF != null && prefs.skipOffline) add(getString(R.string.ignored_offline))
        }
        addView(TextView(context).apply { text = details.joinToString(" · "); alpha = 0.7f })

        val third = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        fun row(vararg boxes: CheckBox) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            boxes.forEach { addView(it, third()) }
        }
        fun box(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) =
            CheckBox(context).apply {
                setText(label)
                isChecked = checked
                isEnabled = enabled
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
        val hasTemp = cam.hasSensor

        val average = box(R.string.use_in_average, hasTemp && cam.id !in prefs.excluded, hasTemp) { on ->
            prefs.excluded = if (on) prefs.excluded - cam.id else prefs.excluded + cam.id
            tempSettingsChanged(full = false)
        }
        val alert = box(R.string.freeze_alert_short, hasTemp && cam.id !in prefs.alertExcluded, hasTemp && prefs.alertEnabled) { on ->
            prefs.alertExcluded = if (on) prefs.alertExcluded - cam.id else prefs.alertExcluded + cam.id
            Alerts.reset(this@MainActivity)
        }
        val snapshot = box(R.string.image_refresh, cam.id in prefs.snapCameras, true) { on ->
            prefs.snapCameras = if (on) prefs.snapCameras + cam.id else prefs.snapCameras - cam.id
            Snapshots.schedule(this@MainActivity)
            snapNowBtn.isEnabled = prefs.isSignedIn && prefs.snapCameras.isNotEmpty()
        }
        val tempRefresh = box(R.string.temp_refresh_short, hasTemp && cam.id in prefs.tempCameras, hasTemp) { on ->
            prefs.tempCameras = if (on) prefs.tempCameras + cam.id else prefs.tempCameras - cam.id
            TempRefresh.schedule(this@MainActivity)
            tempNowBtn.isEnabled = prefs.isSignedIn && prefs.tempCameras.isNotEmpty()
        }
        addView(row(average, alert))
        addView(row(snapshot, tempRefresh))
    }

    private fun renderAverage(snap: Snapshot?) {
        val avg = Temps.average(Temps.usable(snap?.cameras.orEmpty(), prefs))
        avgView.text = avg?.let { Temps.format(it, prefs.useCelsius) } ?: "--"
    }

    private fun tempSettingsChanged(full: Boolean) {
        TempWidget.updateAll(this)
        if (full) render() else {
            renderAverage(Snapshot.fromJson(prefs.cache))
            renderChart() // the chart average follows the same camera selection
        }
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<SwitchCompat>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }
}
