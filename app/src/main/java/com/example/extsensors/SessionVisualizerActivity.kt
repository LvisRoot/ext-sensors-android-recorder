package com.example.extsensors

import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionVisualizerActivity : ComponentActivity() {
    private lateinit var folderLabel: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var listScreen: View
    private lateinit var playerScreen: View
    private lateinit var dateSortButton: Button
    private lateinit var nameSortButton: Button
    private lateinit var textureView: TextureView
    private lateinit var graphView: GraphOverlayView
    private lateinit var loadingOverlay: View
    private lateinit var legendFlow: FlowLayout
    private lateinit var playPauseButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView

    private var folderUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var pendingVideoUri: Uri? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null
    private var videoDurationMs = 0
    private var isUserSeeking = false
    private var loadedSeries: Map<String, List<Pair<Float, Float>>> = emptyMap()
    private var loadedColors: Map<String, Int> = emptyMap()
    private var loadedUnits: Map<String, String> = emptyMap()
    private var allSummaries: List<Summary> = emptyList()
    private var sortByDate = true
    private var dateAscending = false
    private var nameAscending = true

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            progressHandler.postDelayed(this, 200)
        }
    }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            folderUri = uri
            folderLabel.text = "Folder: ${describeFolder(uri)}"
            loadSessions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderUri = intent.getStringExtra(EXTRA_FOLDER_URI)?.let { Uri.parse(it) }
        buildUi()
        loadSessions()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        listScreen = buildListScreen()
        playerScreen = buildPlayerScreen()
        playerScreen.visibility = View.GONE
        root.addView(listScreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(playerScreen, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun buildListScreen(): View {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101416.toInt())
            setPadding(24, 24, 24, 24)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(panelText("Recorded sessions", 21, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "Folder"
            setOnClickListener { folderLauncher.launch(null) }
        })
        header.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        })
        screen.addView(header)
        folderLabel = panelText("Folder: ${describeFolder(folderUri)}", 13)
        screen.addView(folderLabel)

        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dateSortButton = Button(this).apply {
            setOnClickListener {
                if (sortByDate) dateAscending = !dateAscending else sortByDate = true
                applySort()
            }
        }
        nameSortButton = Button(this).apply {
            setOnClickListener {
                if (!sortByDate) nameAscending = !nameAscending else sortByDate = false
                applySort()
            }
        }
        sortRow.addView(dateSortButton, LinearLayout.LayoutParams(0, -2, 1f))
        sortRow.addView(nameSortButton, LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(sortRow)
        updateSortButtonLabels()

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(ScrollView(this).apply {
            addView(listContainer)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return screen
    }

    private fun buildPlayerScreen(): View {
        val screen = FrameLayout(this)
        textureView = TextureView(this)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                pendingSurfaceTexture = surface
                pendingVideoUri?.let { startPlayback(it) }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        screen.addView(textureView, FrameLayout.LayoutParams(-1, -1))

        // Graph occupies only the bottom half of the video area; side margins keep its drawn
        // width aligned with the seek bar below, which uses the same horizontal padding.
        val overlayContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bottomSpacer = View(this)
        graphView = GraphOverlayView(this).apply {
            setBackgroundColor(Color.argb(DEFAULT_GRAPH_ALPHA, 0, 0, 0))
        }
        overlayContainer.addView(graphView, LinearLayout.LayoutParams(-1, 0, 1f))
        overlayContainer.addView(bottomSpacer, LinearLayout.LayoutParams(-1, 0, 1f))
        screen.addView(overlayContainer, FrameLayout.LayoutParams(-1, -1).apply {
            marginStart = SIDE_PADDING
            marginEnd = SIDE_PADDING
        })

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(panelText("Loading graphs...", 18, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            visibility = View.GONE
        }
        screen.addView(loadingOverlay, FrameLayout.LayoutParams(-1, -1))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SIDE_PADDING, 8, SIDE_PADDING, 16)
            setBackgroundColor(0xCC101416.toInt())
        }
        controls.addView(ScrollView(this).apply {
            addView(legendFlowContainer().also { legendFlow = it })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        val opacityRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        opacityRow.addView(panelText("Graph opacity", 12))
        opacityRow.addView(SeekBar(this).apply {
            max = 100
            progress = (DEFAULT_GRAPH_ALPHA * 100 / 255)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    graphView.setBackgroundColor(Color.argb((progress * 255 / 100), 0, 0, 0))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(opacityRow)

        val transportRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener { closePlayer() }
        }
        playPauseButton = Button(this).apply {
            text = "Play"
            setOnClickListener { togglePlayback() }
        }
        timeLabel = TextView(this).apply { setTextColor(Color.WHITE); setPadding(16, 0, 16, 0) }
        transportRow.addView(backButton)
        transportRow.addView(playPauseButton)
        transportRow.addView(timeLabel)
        controls.addView(transportRow)
        seekBar = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) mediaPlayer?.seekTo(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { isUserSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar) { isUserSeeking = false }
            })
        }
        controls.addView(seekBar)
        screen.addView(controls, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
        return screen
    }

    private fun legendFlowContainer(): FlowLayout = FlowLayout(this)

    private fun describeFolder(uri: Uri?): String = uri?.lastPathSegment ?: "App storage (default)"

    private fun rootDocFile(): DocumentFile {
        val uri = folderUri
        if (uri != null) DocumentFile.fromTreeUri(this, uri)?.let { return it }
        return DocumentFile.fromFile(File(getExternalFilesDir(null), "sessions").apply { mkdirs() })
    }

    private fun loadSessions() {
        listContainer.removeAllViews()
        listContainer.addView(panelText("Loading...", 14))
        lifecycleScope.launch(Dispatchers.IO) {
            val root = rootDocFile()
            val summaries = (root.listFiles().filter { it.isDirectory }).mapNotNull { buildSummary(it) }
            withContext(Dispatchers.Main) {
                allSummaries = summaries
                applySort()
            }
        }
    }

    private fun applySort() {
        val sorted = if (sortByDate) {
            if (dateAscending) allSummaries.sortedBy { it.timestampMs } else allSummaries.sortedByDescending { it.timestampMs }
        } else {
            if (nameAscending) allSummaries.sortedBy { it.rawName } else allSummaries.sortedByDescending { it.rawName }
        }
        listContainer.removeAllViews()
        if (sorted.isEmpty()) listContainer.addView(panelText("No sessions found in this folder", 14))
        sorted.forEach { addSessionRow(it) }
        updateSortButtonLabels()
    }

    private fun updateSortButtonLabels() {
        dateSortButton.text = "Date ${if (dateAscending) "\u25B2" else "\u25BC"}${if (sortByDate) " \u2022" else ""}"
        nameSortButton.text = "Name ${if (nameAscending) "\u25B2" else "\u25BC"}${if (!sortByDate) " \u2022" else ""}"
    }

    private data class Summary(
        val dir: DocumentFile,
        val rawName: String,
        val timestampMs: Long,
        val dateText: String,
        val durationText: String,
        val quality: String,
        val hasLineScale: Boolean,
        val hasImu: Boolean
    )

    private fun buildSummary(dir: DocumentFile): Summary? {
        val paramsFile = dir.listFiles().firstOrNull { it.name == "params.json" } ?: return null
        val json = readText(paramsFile.uri) ?: return null
        val root = try { JSONObject(json) } catch (_: Exception) { return null }
        val quality = root.optJSONObject("camera")?.optString("quality") ?: "unknown"
        val sensors = root.optJSONObject("sensors")
        val hasLineScale = sensors?.has("LineScale") == true
        val hasImu = sensors?.has("IMU") == true
        val videoFile = dir.listFiles().firstOrNull { it.name == "video.mp4" }
        val durationMs = videoFile?.let { readDurationMs(it.uri) } ?: 0L
        val name = dir.name ?: "session"
        val timestampMs = SESSION_TIMESTAMP.find(name)?.groupValues?.get(1)?.toLongOrNull()
            ?: root.optLong("video_start_unix_ms", 0L)
        val dateText = if (timestampMs > 0) DATE_FORMAT.format(Date(timestampMs)) else "Unknown date"
        return Summary(dir, name, timestampMs, dateText, formatDuration(durationMs), quality, hasLineScale, hasImu)
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun readText(uri: Uri): String? =
        try { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } catch (_: Exception) { null }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    }

    private fun addSessionRow(summary: Summary) {
        val label = buildString {
            append(summary.dateText)
            append("  (").append(summary.rawName).append(")")
            append("\nDuration: ").append(summary.durationText)
            append("  Quality: ").append(summary.quality)
            if (summary.hasLineScale) append("  \u2022 LineScale")
            if (summary.hasImu) append("  \u2022 IMU")
        }
        val row = panelText(label, 14).apply {
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF1B2226.toInt())
            setOnClickListener { openSession(summary.dir) }
        }
        val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 }
        listContainer.addView(row, params)
    }

    private fun openSession(dir: DocumentFile) {
        releasePlayer()
        legendFlow.removeAllViews()
        graphView.clearVisible()
        graphView.setSeries(emptyMap(), emptyMap(), emptyMap(), 1f)
        listScreen.visibility = View.GONE
        playerScreen.visibility = View.VISIBLE
        loadingOverlay.visibility = View.VISIBLE
        loadVideoAndSignals(dir)
    }

    private fun closePlayer() {
        releasePlayer()
        playerScreen.visibility = View.GONE
        listScreen.visibility = View.VISIBLE
    }

    private fun loadVideoAndSignals(dir: DocumentFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            val paramsFile = dir.listFiles().firstOrNull { it.name == "params.json" }
            val startMs = paramsFile?.let { readText(it.uri) }?.let {
                try { JSONObject(it).optLong("video_start_unix_ms", 0L) } catch (_: Exception) { 0L }
            } ?: 0L
            val series = mutableMapOf<String, MutableList<Pair<Float, Float>>>()
            val units = mutableMapOf<String, String>()
            dir.listFiles().filter { it.name?.endsWith(".csv") == true }.forEach { csvFile ->
                contentResolver.openInputStream(csvFile.uri)?.bufferedReader()?.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val columns = line.split(",")
                        if (columns.size >= 5) {
                            val timestamp = columns[0].toLongOrNull()
                            val signal = columns[2].trim('"')
                            val value = columns[3].toFloatOrNull()
                            val unit = columns[4].trim('"')
                            if (timestamp != null && value != null) {
                                series.getOrPut(signal) { mutableListOf() }.add((timestamp - startMs) / 1000f to value)
                                units[signal] = unit
                            }
                        }
                    }
                }
            }
            val colors = mutableMapOf<String, Int>()
            series.keys.sorted().forEachIndexed { index, key -> colors[key] = PALETTE[index % PALETTE.size] }
            val videoFile = dir.listFiles().firstOrNull { it.name == "video.mp4" }
            withContext(Dispatchers.Main) {
                loadedSeries = series
                loadedColors = colors
                loadedUnits = units
                buildLegend(series.keys.sorted(), units, colors)
                loadingOverlay.visibility = View.GONE
                pendingVideoUri = videoFile?.uri
                if (textureView.isAvailable) pendingVideoUri?.let { startPlayback(it) }
            }
        }
    }

    private fun buildLegend(signals: List<String>, units: Map<String, String>, colors: Map<String, Int>) {
        legendFlow.removeAllViews()
        signals.forEach { signal ->
            val color = colors[signal] ?: Color.CYAN
            // All signals start disabled; the operator picks which traces to overlay.
            val label = panelText("$signal (${units[signal] ?: "?"})", 13).apply {
                setTextColor(0xFFB0B0B0.toInt())
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }
            val chip = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 8, 12, 8)
                addView(dot, LinearLayout.LayoutParams(20, 20).apply { marginEnd = 8 })
                addView(label)
                setOnClickListener {
                    val visible = graphView.toggle(signal)
                    label.setTextColor(if (visible) color else 0xFFB0B0B0.toInt())
                }
            }
            legendFlow.addView(chip)
        }
    }

    private fun startPlayback(uri: Uri) {
        releasePlayer()
        val surfaceTexture = pendingSurfaceTexture ?: return
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(this@SessionVisualizerActivity, uri)
                setSurface(Surface(surfaceTexture))
                setOnPreparedListener { player ->
                    videoDurationMs = player.duration
                    seekBar.max = videoDurationMs
                    graphView.setSeries(loadedSeries, loadedColors, loadedUnits, videoDurationMs / 1000f)
                    player.start()
                    playPauseButton.text = "Pause"
                    progressHandler.post(progressRunnable)
                }
                prepareAsync()
            } catch (_: Exception) {
            }
        }
    }

    private fun togglePlayback() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playPauseButton.text = "Play"
        } else {
            player.start()
            playPauseButton.text = "Pause"
        }
    }

    private fun updateProgressUi() {
        val player = mediaPlayer ?: return
        val position = try { player.currentPosition } catch (_: Exception) { return }
        if (!isUserSeeking) seekBar.progress = position
        timeLabel.text = "${formatDuration(position.toLong())} / ${formatDuration(videoDurationMs.toLong())}"
        if (videoDurationMs > 0) graphView.setProgressFraction(position.toFloat() / videoDurationMs)
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun panelText(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val SIDE_PADDING = 32
        const val DEFAULT_GRAPH_ALPHA = 110
        val SESSION_TIMESTAMP = Regex("""session_(\d+)$""")
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val PALETTE = listOf(
            0xFF00A896.toInt(), 0xFFE94F37.toInt(), 0xFFF6AE2D.toInt(), 0xFF7B2CBF.toInt(),
            0xFF2D9CDB.toInt(), 0xFFBB6BD9.toInt(), 0xFF56CBF9.toInt(), 0xFFFF66C4.toInt(),
            0xFFB4E33D.toInt(), 0xFFFF9F1C.toInt(), 0xFF6A994E.toInt(), 0xFFC9184A.toInt()
        )
    }
}
