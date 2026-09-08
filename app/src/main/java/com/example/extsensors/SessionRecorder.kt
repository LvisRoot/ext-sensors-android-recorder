package com.example.extsensors

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap

/** Where recorded video/CSV/JSON should be written: a user-picked SAF folder, or the app's private external storage. */
sealed class SessionOutput {
    data class PlainFile(val videoFile: File) : SessionOutput()
    data class Document(val videoDescriptor: ParcelFileDescriptor) : SessionOutput()
}

class SessionRecorder(private val context: Context) {
    var storageTreeUri: Uri? = null
    private var sessionDirFile: File? = null
    private var sessionDirDoc: DocumentFile? = null
    private var videoDescriptor: ParcelFileDescriptor? = null
    private val writers = ConcurrentHashMap<String, Writer>()

    fun begin(prefix: String, cameraParams: String, sensorParams: String): SessionOutput {
        check(sessionDirFile == null && sessionDirDoc == null) { "A session is already active" }
        val start = System.currentTimeMillis()
        val safePrefix = prefix.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val name = if (safePrefix.isEmpty()) "session_$start" else "${safePrefix}_session_$start"
        val paramsJson = "{\n  \"video_start_unix_ms\": $start,\n  \"video_start_elapsed_ns\": ${SystemClock.elapsedRealtimeNanos()},\n  \"camera\": $cameraParams,\n  \"sensors\": $sensorParams\n}\n"
        val treeUri = storageTreeUri
        if (treeUri == null) {
            val dir = File(context.getExternalFilesDir(null), "sessions/$name")
            check(dir.mkdirs()) { "Could not create session directory" }
            sessionDirFile = dir
            File(dir, "params.json").writeText(paramsJson)
            return SessionOutput.PlainFile(File(dir, "video.mp4"))
        }
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Chosen folder is no longer accessible")
        val dir = root.createDirectory(name) ?: error("Could not create session directory")
        sessionDirDoc = dir
        val paramsFile = dir.createFile("application/json", "params.json") ?: error("Could not create params.json")
        context.contentResolver.openOutputStream(paramsFile.uri)?.use { it.write(paramsJson.toByteArray()) }
        val videoFile = dir.createFile("video/mp4", "video.mp4") ?: error("Could not create video.mp4")
        val descriptor = context.contentResolver.openFileDescriptor(videoFile.uri, "rw") ?: error("Could not open video.mp4")
        videoDescriptor = descriptor
        return SessionOutput.Document(descriptor)
    }

    @Synchronized
    fun writeSensorRow(sensorId: String, signal: String, value: Double, unit: String) {
        val safeId = sensorId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val writer = writers.getOrPut(safeId) { openCsvWriter(safeId) } ?: return
        writer.write("${System.currentTimeMillis()},${csv(sensorId)},${csv(signal)},$value,${csv(unit)}\n")
        writer.flush()
    }

    private fun openCsvWriter(safeId: String): Writer {
        val header = "timestamp_unix_ms,sensor_id,signal,value,unit\n"
        val docDir = sessionDirDoc
        val writer: Writer = if (docDir != null) {
            val file = docDir.createFile("text/csv", "$safeId.csv") ?: error("Could not create $safeId.csv")
            OutputStreamWriter(context.contentResolver.openOutputStream(file.uri))
        } else {
            val dir = sessionDirFile ?: error("No active session")
            BufferedWriter(FileWriter(File(dir, "$safeId.csv"), true))
        }
        writer.write(header)
        return writer
    }

    @Synchronized
    fun finish() {
        writers.values.forEach { it.close() }
        writers.clear()
        videoDescriptor?.close()
        videoDescriptor = null
        sessionDirFile = null
        sessionDirDoc = null
    }

    fun isRecording(): Boolean = sessionDirFile != null || sessionDirDoc != null

    fun storageDescription(): String {
        val uri = storageTreeUri ?: return context.getExternalFilesDir(null)?.absolutePath + "/sessions"
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2) "/storage/${if (parts[0] == "primary") "emulated/0" else parts[0]}/${parts[1]}" else uri.toString()
        } catch (_: Exception) { uri.toString() }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
