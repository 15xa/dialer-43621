package com.simplemobiletools.dialer.services

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CallRecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private var fileUri: Uri? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val autoRecord = intent?.getBooleanExtra("AUTO_RECORD_ENABLED", true) ?: true

        if (!hasRecordingPermission()) {
            Log.e("CallRecorder", "Missing RECORD_AUDIO permission")
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("CallRecorder", "POST_NOTIFICATIONS not granted on Android 13+")
        }

        when (intent?.action) {
            "STOP_RECORDING" -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (autoRecord) {
                    startForegroundNotification()
                    startRecording()
                }
            }
        }

        return START_STICKY
    }

    private fun startRecording() {
        if (recorder != null) {
            Log.w("CallRecorder", "Recording already in progress")
            return
        }
        val resolver = contentResolver
        val fileName = "CallRecord_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.m4a"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/CallRecordings")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val audioCollection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fileUri = resolver.insert(audioCollection, values)

        if (fileUri == null) {
            Log.e("CallRecorder", "Failed to create URI for recording")
            stopSelf()
            return
        }

        try {
            val pfd = resolver.openFileDescriptor(fileUri!!, "w")
            if (pfd == null) {
                Log.e("CallRecorder", "File descriptor is null")
                stopSelf()
                return
            }

            recorder = MediaRecorder(applicationContext).apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(fileUri!!, values, null, null)

        } catch (e: IOException) {
            Log.e("CallRecorder", "Recording failed: ${e.message}", e)
            stopSelf()
        } catch (e: IllegalStateException) {
            Log.e("CallRecorder", "Mic in use or MediaRecorder failed: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopRecording() {
        recorder?.apply {
            try {
                stop()
            } catch (e: Exception) {
                Log.e("CallRecorder", "Stop failed", e)
            }
            release()
        }
        recorder = null
        stopForeground(true)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasRecordingPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundNotification() {
        val channelId = "call_recording_channel"
        val channelName = "Call Recording"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Call Recording")
            .setContentText("Recording call in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    companion object {
        fun startRecording(context: Context) {


            val intent = Intent(context, CallRecordingService::class.java).apply {
                putExtra("AUTO_RECORD_ENABLED", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = "STOP_RECORDING"
            }
            context.startService(intent)
        }
    }
}
