package com.neo.plus.iosluancher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object RecorderState {
    var recording by mutableStateOf(false)
}

/** Invisible activity that shows Android's one-time "start recording?" consent, then starts the recorder service. */
class RecorderPermissionActivity : ComponentActivity() {
    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, ScreenRecordService::class.java)
                    .setAction(ScreenRecordService.ACTION_START)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                ContextCompat.startForegroundService(this, i)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            launcher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Screen recording is not available on this phone", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

/** Records the screen to Movies/NeoRecordings. Runs as a media-projection foreground service. */
class ScreenRecordService : Service() {
    companion object {
        const val ACTION_START = "neo.rec.start"
        const val ACTION_STOP = "neo.rec.stop"
        private const val CHANNEL = "neo_rec"
        private const val NOTIF_ID = 4711

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, ScreenRecordService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) { }
        }
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var pendingUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startRecording(intent)
        } else {
            finishRecording()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        finishRecording()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Screen Recording")
            .setContentText("Tap to stop")
            .setContentIntent(stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun screen(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        var w: Int
        var h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        }
        val scale = min(1f, 1080f / min(w, h))
        w = ((w * scale).toInt() / 2) * 2
        h = ((h * scale).toInt() / 2) * 2
        return Triple(w, h, resources.displayMetrics.densityDpi)
    }

    private fun openOutput(rec: MediaRecorder) {
        val name = "NEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NeoRecordings")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("Cannot create video file")
            pendingUri = uri
            val p = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("Cannot open video file")
            pfd = p
            rec.setOutputFile(p.fileDescriptor)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "NeoRecordings")
            dir.mkdirs()
            val f = File(dir, name)
            outFile = f
            rec.setOutputFile(f.absolutePath)
        }
    }

    private fun startRecording(intent: Intent) {
        if (RecorderState.recording) return
        goForeground()
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
        if (data == null) { stopSelf(); return }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { finishRecording(); stopSelf() }
            }, Handler(Looper.getMainLooper()))

            val (w, h, dpi) = screen()
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate((w * h * 4).coerceAtMost(14_000_000))
            openOutput(rec)
            rec.prepare()
            display = proj.createVirtualDisplay(
                "NeoRecorder", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null
            )
            rec.start()
            recorder = rec
            RecorderState.recording = true
        } catch (e: Throwable) {
            Toast.makeText(this, "Could not start screen recording", Toast.LENGTH_SHORT).show()
            finishRecording()
            stopSelf()
        }
    }

    private fun finishRecording() {
        val rec = recorder
        val proj = projection
        val disp = display
        recorder = null; projection = null; display = null
        if (rec == null && proj == null) { RecorderState.recording = false; return }

        var saved = false
        try { rec?.stop(); saved = rec != null } catch (e: Throwable) { }
        try { rec?.release() } catch (e: Throwable) { }
        try { disp?.release() } catch (e: Throwable) { }
        try { proj?.stop() } catch (e: Throwable) { }
        try { pfd?.close() } catch (e: Throwable) { }
        pfd = null

        pendingUri?.let { u ->
            try {
                if (saved) {
                    contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } else {
                    contentResolver.delete(u, null, null)
                }
            } catch (e: Throwable) { }
        }
        pendingUri = null
        outFile?.let { MediaScannerConnection.scanFile(this, arrayOf(it.absolutePath), null, null) }
        outFile = null

        RecorderState.recording = false
        if (saved) Toast.makeText(this, "Screen recording saved to Movies/NeoRecordings", Toast.LENGTH_LONG).show()
    }
}
