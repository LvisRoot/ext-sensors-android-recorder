package com.example.extsensors

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var activeOverlay: TextView
    private lateinit var status: TextView
    private lateinit var storageLabel: TextView
    private lateinit var sessionPrefixLabel: TextView
    private lateinit var sensorsButton: Button
    private lateinit var folderButton: Button
    private lateinit var qualityButton: Button
    private lateinit var modeButton: Button
    private lateinit var recordButton: Button
    private lateinit var recordingDot: View
    private lateinit var lockOverlay: TextView
    private lateinit var sensorPanel: View
    private lateinit var recorder: SessionRecorder
    private var recordingService: RecordingService? = null
    private var selectedQuality: Quality = Quality.LOWEST
    private var keepLivePreview = false
    private var recording = false
    private var scanner: BleSensorScanner? = null
    private var sessionPrefix = ""
    private val connections = mutableMapOf<SensorKind, BleSensorConnection>()
    private val foundDevices = mutableMapOf<SensorKind, BluetoothDevice>()
    private val selectedSignals = linkedMapOf<SensorKey, SignalView>()
    private val lastUnits = mutableMapOf<SensorKey, String>()
    private val slots = SensorKind.values().associateWith { SensorSlot() }
    private val targetRates = mutableMapOf(SensorKind.LINESCALE to 10.0, SensorKind.IMU to 10.0)
    private val measuredRates = mutableMapOf<SensorKind, Double>()
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            recordingDot.visibility = if (recordingDot.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            blinkHandler.postDelayed(this, 500)
        }
    }

    // The camera + recording live in a foreground Service so Android's background camera-access
    // restriction (which stalls a backgrounded app's camera after a few seconds) doesn't apply.
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            recordingService = (service as RecordingService.LocalBinder).service
            recordingService?.startCamera(previewView.surfaceProvider) { runOnUiThread { updateOverlay() } }
        }
        override fun onServiceDisconnected(name: ComponentName?) { recordingService = null }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) bindRecordingService()
        if (permissions[Manifest.permission.BLUETOOTH_SCAN] == true) scanSensors()
    }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) applyStorageFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = SessionRecorder(this)
        buildUi()
        restoreStorageFolder()
        permissionLauncher.launch(requiredPermissions())
    }

    private fun bindRecordingService() {
        bindService(Intent(this, RecordingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun restoreStorageFolder() {
        val saved = preferences().getString(PREF_STORAGE_URI, null) ?: return
        val uri = Uri.parse(saved)
        val stillGranted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (stillGranted) applyStorageFolder(uri, persist = false)
    }

    private fun applyStorageFolder(uri: Uri, persist: Boolean = true) {
        if (persist) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            preferences().edit().putString(PREF_STORAGE_URI, uri.toString()).apply()
        }
        recorder.storageTreeUri = uri
        storageLabel.text = "Saving to: ${recorder.storageDescription()}"
    }

    private fun preferences() = getSharedPreferences("ext_sensors", MODE_PRIVATE)

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        activeOverlay = text("No active sensors", 16).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA101416.toInt())
            setPadding(20, 14, 20, 14)
        }
        root.addView(activeOverlay, frameParams(Gravity.TOP or Gravity.START))
        lockOverlay = text("Recording in progress, you can lock the phone", 18).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC101416.toInt())
            setPadding(32, 20, 32, 20)
            visibility = View.GONE
        }
        root.addView(lockOverlay, frameParams(Gravity.CENTER))
        storageLabel = text("Saving to: ${recorder.storageDescription()}", 13).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA101416.toInt())
            setPadding(20, 10, 20, 10)
        }
        root.addView(storageLabel, frameParams(Gravity.TOP or Gravity.END))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
            setBackgroundColor(0xCC101416.toInt())
        }
        sessionPrefixLabel = text("Session name prefix (tap to change)", 14).apply {
            setTextColor(Color.WHITE)
            setPadding(4, 8, 4, 8)
            setOnClickListener { showPrefixDialog() }
        }
        controls.addView(sessionPrefixLabel, LinearLayout.LayoutParams(-1, -2))
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sensorsButton = Button(this).apply {
            text = "Sensors"
            setOnClickListener { sensorPanel.visibility = View.VISIBLE; scanSensors() }
        }
        folderButton = Button(this).apply {
            text = "Folder"
            setOnClickListener { folderLauncher.launch(null) }
        }
        qualityButton = Button(this).apply {
            text = "\uD83D\uDCD0 ${qualityLabel(selectedQuality)}"
            setOnClickListener { showQualityDialog() }
        }
        modeButton = Button(this).apply {
            text = recordingModeLabel()
            setOnClickListener {
                keepLivePreview = !keepLivePreview
                text = recordingModeLabel()
            }
        }
        recordButton = Button(this).apply {
            text = "Start recording"
            isEnabled = false
            setOnClickListener { toggleRecording() }
        }
        recordingDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }
            visibility = View.INVISIBLE
        }
        topRow.addView(sensorsButton, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(folderButton, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(modeButton, LinearLayout.LayoutParams(0, -2, 1f))
        bottomRow.addView(qualityButton, LinearLayout.LayoutParams(0, -2, 1f))
        bottomRow.addView(recordButton, LinearLayout.LayoutParams(0, -2, 1f))
        bottomRow.addView(recordingDot, LinearLayout.LayoutParams(24, 24).apply { marginStart = 12 })
        controls.addView(topRow)
        controls.addView(bottomRow)
        controls.addView(Button(this).apply {
            text = "\uD83D\uDCCA Session visualizer"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SessionVisualizerActivity::class.java).apply {
                    putExtra(SessionVisualizerActivity.EXTRA_FOLDER_URI, recorder.storageTreeUri?.toString())
                })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        root.addView(controls, frameParams(Gravity.BOTTOM))

        sensorPanel = buildSensorPanel()
        sensorPanel.visibility = View.GONE
        root.addView(sensorPanel, frameParams(Gravity.CENTER))
        setContentView(root)

        // Target SDK 35 draws edge-to-edge by default; pad overlays so system bars don't cover them.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            activeOverlay.setPadding(20, bars.top + 14, 20, 14)
            storageLabel.setPadding(20, bars.top + 10, 20, 10)
            controls.setPadding(16, 8, 16, bars.bottom + 16)
            insets
        }
    }

    private fun showPrefixDialog() {
        val input = EditText(this).apply {
            setText(sessionPrefix)
            hint = "Session name prefix"
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Session name prefix")
            .setView(input)
            .setPositiveButton("Done") { _, _ -> applyPrefix(input.text.toString()) }
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyPrefix(input.text.toString())
                dialog.dismiss()
                true
            } else false
        }
        dialog.show()
    }

    private fun applyPrefix(value: String) {
        sessionPrefix = value.trim()
        sessionPrefixLabel.text = if (sessionPrefix.isEmpty()) "Session name prefix (tap to change)" else "Prefix: $sessionPrefix (tap to change)"
    }

    private fun buildSensorPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(0xE6141A1E.toInt())
            }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(panelText("Sensor configuration", 21, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { sensorPanel.visibility = View.GONE }
        })
        panel.addView(header)
        status = panelText("Open Sensors to scan", 14)
        panel.addView(status)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SensorKind.values().forEach { kind ->
            columns.addView(buildSensorColumn(kind), LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(columns)
        return ScrollView(this).apply { addView(panel) }
    }

    private fun buildSensorColumn(kind: SensorKind): View {
        val slot = slots.getValue(kind)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        column.addView(panelText(kind.title, 18, bold = true))
        slot.deviceLabel = panelText("Not found", 13)
        column.addView(slot.deviceLabel)
        slot.rateLabel = panelText("-- Hz", 13)
        column.addView(slot.rateLabel)
        column.addView(panelText("Recording rate", 12))
        val rateOptions = kind.supportedRatesHz.map { "${formatHz(it)} Hz" }
        slot.rateSpinner = Spinner(this).apply {
            setBackgroundColor(Color.WHITE)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rateOptions)
            setSelection(kind.supportedRatesHz.indexOf(targetRates.getValue(kind)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val hz = kind.supportedRatesHz[position]
                    targetRates[kind] = hz
                    connections[kind]?.setSampleRate(hz)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        column.addView(slot.rateSpinner)
        slot.connectButton = Button(this).apply {
            text = "Connect"
            isEnabled = false
            setOnClickListener { connectSensor(kind) }
        }
        column.addView(slot.connectButton)
        slot.signalContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(slot.signalContainer)
        return column
    }

    private fun connectSensor(kind: SensorKind) {
        val slot = slots.getValue(kind)
        val existing = connections[kind]
        if (existing != null) {
            existing.close()
            connections.remove(kind)
            measuredRates.remove(kind)
            slot.connectButton.text = "Connect"
            slot.rateLabel.text = "-- Hz"
            slot.signalContainer.removeAllViews()
            selectedSignals.keys.filter { it.kind == kind }.forEach { selectedSignals.remove(it) }
            updateOverlay()
            return
        }
        val device = foundDevices[kind] ?: return
        slot.connectButton.text = "Disconnect"
        slot.signalContainer.removeAllViews()
        kind.signals.forEach { addSignalButton(kind, it) }
        val connection = BleSensorConnection(this, kind.toConnectionKind(), device,
            initialRateHz = targetRates.getValue(kind),
            onValue = { signal, value, unit ->
                runOnUiThread {
                    val key = SensorKey(kind, signal)
                    lastUnits[key] = unit
                    val selected = selectedSignals[key]
                    selected?.value?.text = "${"%.3f".format(value)} $unit"
                    if (recorder.isRecording() && selected != null)
                        recorder.writeSensorRow(kind.title, signal, value, unit)
                }
            },
            onRate = { hz -> runOnUiThread { slot.rateLabel.text = "${formatHz(hz)} Hz"; measuredRates[kind] = hz } },
            onState = { message -> runOnUiThread { status.text = message } })
        connections[kind] = connection
        connection.connect()
        updateOverlay()
    }

    private fun addSignalButton(kind: SensorKind, signal: SignalDefinition) {
        val key = SensorKey(kind, signal.name)
        val value = panelText(if (signal.unit.isEmpty()) "--" else "-- ${signal.unit}", 14)
        val button = Button(this).apply {
            text = signal.name
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                if (selectedSignals.remove(key) == null) {
                    selectedSignals[key] = SignalView(signal, value)
                    setBackgroundColor(0xFF00A896.toInt())
                } else setBackgroundColor(Color.WHITE)
                updateOverlay()
            }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value, LinearLayout.LayoutParams(0, -2, 1f))
        slots.getValue(kind).signalContainer.addView(row)
    }

    private fun scanSensors() {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        scanner?.stop()
        scanner = BleSensorScanner(this) { device ->
            val kind = classify(device) ?: return@BleSensorScanner
            if (foundDevices.putIfAbsent(kind, device) == null) runOnUiThread {
                val slot = slots.getValue(kind)
                slot.deviceLabel.text = "Found: ${safeName(device)}"
                slot.connectButton.isEnabled = true
                status.text = "Found ${foundDevices.size} supported sensor(s)"
            }
        }
        scanner?.start()
        status.text = "Scanning for supported sensors..."
    }

    private fun classify(device: BluetoothDevice): SensorKind? {
        val name = safeName(device).lowercase()
        return when {
            name.contains("line") || name.contains("scale") -> SensorKind.LINESCALE
            name.contains("wt901") || name.contains("wit") || name.contains("imu") -> SensorKind.IMU
            else -> null
        }
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name ?: "Unnamed"
    } catch (_: SecurityException) { "Unnamed" }

    private fun updateOverlay() {
        activeOverlay.text = if (selectedSignals.isEmpty()) "No active sensors" else
            selectedSignals.keys.joinToString("\n") { "${it.kind.title}: ${it.signal}" }
        recordButton.isEnabled = recordingService != null && selectedSignals.isNotEmpty()
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun showQualityDialog() {
        val labels = QUALITIES.map { it.second }.toTypedArray()
        val currentIndex = QUALITIES.indexOfFirst { it.first == selectedQuality }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("\uD83D\uDCD0 Camera quality")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                selectedQuality = QUALITIES[which].first
                qualityButton.text = "\uD83D\uDCD0 ${QUALITIES[which].second}"
                recordingService?.setQuality(selectedQuality, previewView.surfaceProvider)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun qualityLabel(quality: Quality): String = QUALITIES.first { it.first == quality }.second

    private fun recordingModeLabel() = if (keepLivePreview) "\uD83D\uDC41 Live preview" else "\uD83D\uDD12 Record while locked"

    private fun toggleRecording() {
        val service = recordingService ?: return
        if (recording) {
            service.stopRecording()
            recorder.finish()
            recording = false
            recordButton.text = "Start recording"
            blinkHandler.removeCallbacks(blinkRunnable)
            recordingDot.visibility = View.INVISIBLE
            lockOverlay.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            sensorsButton.isEnabled = true
            folderButton.isEnabled = true
            qualityButton.isEnabled = true
            modeButton.isEnabled = true
            return
        }
        val listener = { event: VideoRecordEvent ->
            if (event is VideoRecordEvent.Finalize && event.hasError()) status.text = "Video error: ${event.error}"
            Unit
        }
        val target = recorder.begin(sessionPrefix, buildCameraParamsJson(), buildSensorParamsJson())
        // Promote the service to foreground before it opens the recording surface, matching the
        // camera/microphone foreground-service types required to keep recording through screen lock.
        ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java))
        if (!service.startRecording(target, keepLivePreview, listener)) {
            recorder.finish()
            return
        }
        recording = true
        recordButton.text = "Stop recording"
        sensorsButton.isEnabled = false
        folderButton.isEnabled = false
        qualityButton.isEnabled = false
        modeButton.isEnabled = false
        blinkHandler.post(blinkRunnable)
        if (keepLivePreview) {
            // Live preview mode keeps the on-screen feed, so the screen must not sleep/lock on its own.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            // Preview is dropped for lock-proof recording; tell the operator it's safe to lock up.
            lockOverlay.visibility = View.VISIBLE
        }
    }

    private fun buildCameraParamsJson(): String {
        val info = recordingService?.cameraInfo()
        val resolution = info?.let { QualitySelector.getResolution(it, selectedQuality) }
        val orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        return "{" +
            "\"lens_facing\":\"back\"," +
            "\"quality\":${jsonString(qualityLabel(selectedQuality))}," +
            "\"resolution_width\":${resolution?.width ?: -1}," +
            "\"resolution_height\":${resolution?.height ?: -1}," +
            "\"audio_enabled\":true," +
            "\"fps_used\":${RecordingService.TARGET_FPS}," +
            "\"orientation_at_start\":\"$orientation\"" +
        "}"
    }

    private fun buildSensorParamsJson(): String {
        val kinds = selectedSignals.keys.map { it.kind }.distinct()
        if (kinds.isEmpty()) return "{}"
        return kinds.joinToString(",", "{", "}") { kind ->
            val device = foundDevices[kind]
            val signals = selectedSignals.keys.filter { it.kind == kind }.joinToString(",", "[", "]") { key ->
                val unit = lastUnits[key] ?: kind.signals.first { it.name == key.signal }.unit
                "{\"name\":${jsonString(key.signal)},\"unit\":${jsonString(unit)}}"
            }
            "${jsonString(kind.title)}:{" +
                "\"protocol\":${jsonString(kind.protocolName)}," +
                "\"mac_address\":${jsonString(device?.address ?: "unknown")}," +
                "\"requested_rate_hz\":${targetRates[kind] ?: -1}," +
                "\"measured_rate_hz_at_start\":${measuredRates[kind] ?: -1}," +
                "\"signals\":$signals" +
            "}"
        }
    }

    private fun jsonString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun formatHz(hz: Double): String = "%.1f".format(hz).trimEnd('0').trimEnd('.')

    private fun frameParams(gravity: Int) = FrameLayout.LayoutParams(-1, -2).apply { this.gravity = gravity }
    private fun text(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(0xFF101416.toInt())
    }
    private fun panelText(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    override fun onDestroy() {
        scanner?.stop()
        connections.values.forEach { it.close() }
        if (recorder.isRecording()) recorder.finish()
        blinkHandler.removeCallbacks(blinkRunnable)
        if (recordingService != null) unbindService(serviceConnection)
        super.onDestroy()
    }

    private enum class SensorKind(
        val title: String,
        val protocolName: String,
        val supportedRatesHz: List<Double>,
        val signals: List<SignalDefinition>
    ) {
        // LineScale's report rate is a physical S/F switch on the device; there is no documented
        // remote command to change it, so this only records the operator's intended rate.
        LINESCALE(
            "LineScale", "LineScale BLE", listOf(10.0, 40.0),
            listOf(SignalDefinition("force", ""), SignalDefinition("reference", ""))
        ),
        IMU(
            "IMU", "WT901BLE", BleSensorConnection.RATES_HZ.toList(),
            listOf("accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z", "mag_x", "mag_y", "mag_z", "angle_x", "angle_y", "angle_z").map {
                SignalDefinition(it, if (it.startsWith("accel")) "g" else if (it.startsWith("gyro")) "deg/s" else if (it.startsWith("mag")) "mG" else "deg")
            }
        )
    }

    private fun SensorKind.toConnectionKind() = when (this) {
        SensorKind.LINESCALE -> BleSensorConnection.Kind.LINESCALE
        SensorKind.IMU -> BleSensorConnection.Kind.IMU
    }

    private data class SignalDefinition(val name: String, val unit: String)
    private data class SensorKey(val kind: SensorKind, val signal: String)
    private data class SignalView(val definition: SignalDefinition, val value: TextView)
    private class SensorSlot {
        lateinit var deviceLabel: TextView
        lateinit var rateLabel: TextView
        lateinit var rateSpinner: Spinner
        lateinit var connectButton: Button
        lateinit var signalContainer: LinearLayout
    }

    private companion object {
        const val PREF_STORAGE_URI = "storage_uri"
        val QUALITIES = listOf(
            Quality.UHD to "UHD (4K)",
            Quality.FHD to "FHD (1080p)",
            Quality.HD to "HD (720p)",
            Quality.SD to "SD (480p)",
            Quality.HIGHEST to "Highest (auto)",
            Quality.LOWEST to "Lowest (auto)"
        )
    }
}
