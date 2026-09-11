package com.example.extsensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * Runs the camera + video recording as a foreground service so Android doesn't apply its
 * background camera-access restriction (which otherwise stalls/evicts the session a few
 * seconds after the app leaves the foreground, e.g. on screen lock).
 */
class RecordingService : LifecycleService() {
    private val binder = LocalBinder()
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var recording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var quality: Quality = Quality.HIGHEST
    private var previewDroppedForRecording = false

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    override fun onBind(intent: android.content.Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun cameraInfo(): CameraInfo? = camera?.cameraInfo

    fun startCamera(surfaceProvider: Preview.SurfaceProvider, onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindUseCases(surfaceProvider)
            onReady()
        }, ContextCompat.getMainExecutor(this))
    }

    fun setQuality(newQuality: Quality, surfaceProvider: Preview.SurfaceProvider) {
        quality = newQuality
        bindUseCases(surfaceProvider)
    }

    private fun bindUseCases(surfaceProvider: Preview.SurfaceProvider) {
        val provider = cameraProvider ?: return
        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS)
        )
        val newPreview = previewBuilder.build().also { it.surfaceProvider = surfaceProvider }
        preview = newPreview
        val cameraRecorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
        videoCapture = VideoCapture.withOutput(cameraRecorder)
        provider.unbindAll()
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, newPreview, videoCapture)
    }

    fun startRecording(target: SessionOutput, keepLivePreview: Boolean, onEvent: (VideoRecordEvent) -> Unit): Boolean {
        val output = videoCapture ?: return false
        startForegroundNotification()
        // The on-screen preview surface goes invalid while the display is off, and since it shares
        // the capture session with the encoder, that has been observed to freeze recorded frames too
        // (audio keeps going independently, which is what exposed this). Drop it unless the caller
        // explicitly wants a live preview and is keeping the screen awake to avoid that scenario.
        previewDroppedForRecording = !keepLivePreview
        if (previewDroppedForRecording) preview?.let { cameraProvider?.unbind(it) }
        val executor = ContextCompat.getMainExecutor(this)
        recording = when (target) {
            is SessionOutput.PlainFile -> output.output
                .prepareRecording(this, FileOutputOptions.Builder(target.videoFile).build())
                .withAudioEnabled().start(executor, onEvent)
            is SessionOutput.Document -> output.output
                .prepareRecording(this, FileDescriptorOutputOptions.Builder(target.videoDescriptor).build())
                .withAudioEnabled().start(executor, onEvent)
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ExtSensors:Recording").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
        return true
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Restore the live preview now that dropping it no longer risks the recording session.
        if (previewDroppedForRecording) preview?.let { cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, it) }
        previewDroppedForRecording = false
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording video and sensors")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        recording?.stop()
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
        const val TARGET_FPS = 30
    }
}
