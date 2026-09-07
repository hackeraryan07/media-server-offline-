package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.MainActivity
import com.example.RemoteActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import androidx.media3.common.Player

class AudioSyncService : Service() {

    companion object {
        const val CHANNEL_ID = "AudioSyncChannel"
        const val NOTIFY_ID = 2
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TV_IP = "EXTRA_TV_IP"
    }

    private var exoPlayer: ExoPlayer? = null
    private val client = OkHttpClient()
    private var syncJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var tvIp: String? = null

    // State
    private var isPlaying = false
    private var clockOffset: Long? = null
    private var audioShiftMs = 0L
    private var videoUrl = ""
    private var isContinuousSyncEnabled = true
    private var isRemoteAudioEnabled = true
    private var currentTitle = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        exoPlayer = ExoPlayer.Builder(applicationContext).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            tvIp = intent.getStringExtra(EXTRA_TV_IP)
            if (tvIp == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(NOTIFY_ID, buildNotification("Syncing Audio"))
            startSyncLoop()
        } else if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            // Polling job
            launch {
                while (isActive) {
                    pollState()
                    delay(500)
                }
            }
            // Audio update loop
            while (isActive) {
                if (!isRemoteAudioEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                updateAudioPlayback()
                delay(50)
            }
        }
    }

    private suspend fun pollState() {
        if (tvIp == null) return
        try {
            val request = Request.Builder().url("http://$tvIp:9000/state").build()
            val requestStartTime = android.os.SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val requestEndTime = android.os.SystemClock.elapsedRealtime()
                    val rtt = requestEndTime - requestStartTime
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            isPlaying = json.optBoolean("isPlaying", false)
                            val t = json.optString("title", "")
                            if (t.isNotBlank()) currentTitle = t
                            audioShiftMs = json.optLong("audioShiftMs", 0L)
                            isContinuousSyncEnabled = json.optBoolean("isContinuousSyncEnabled", true)
                            isRemoteAudioEnabled = json.optBoolean("isRemoteAudioEnabled", false)
                            val newVideoUrl = json.optString("videoUrl", "")
                            if (newVideoUrl != videoUrl) {
                                videoUrl = newVideoUrl
                                withContext(Dispatchers.Main) {
                                    if (videoUrl.isNotEmpty()) {
                                        exoPlayer?.setMediaItem(MediaItem.fromUri(videoUrl))
                                        exoPlayer?.prepare()
                                    } else {
                                        exoPlayer?.clearMediaItems()
                                    }
                                }
                            }
                            
                            val isSeeking = false // We assume no seeking here since we just want to follow the TV. Or if TV seeks, we get new position.
                            // The TV state returns the current position.
                            val newPosition = json.optLong("position", 0L)
                            
                            val currentOffset = newPosition - (requestStartTime + rtt / 2)
                            val prevOffset = clockOffset
                            if (prevOffset == null || kotlin.math.abs(currentOffset - prevOffset) > 1000) {
                                clockOffset = currentOffset
                            } else {
                                clockOffset = (prevOffset * 0.8 + currentOffset * 0.2).toLong()
                            }
                            
                            updateNotification("Syncing: $currentTitle")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioSyncService", "Error polling state", e)
        }
    }

    private fun updateAudioPlayback() {
        val player = exoPlayer ?: return
        if (videoUrl.isEmpty()) {
            player.pause()
            return
        }

        if (isPlaying) {
            if (!player.isPlaying) player.play()
            
            if (isContinuousSyncEnabled) {
                val targetPos = android.os.SystemClock.elapsedRealtime() + (clockOffset ?: 0L) + audioShiftMs
                
                val diff = targetPos - player.currentPosition
                
                if (kotlin.math.abs(diff) > 1000) {
                    player.seekTo(targetPos)
                    if (player.playbackParameters.speed != 1.0f) {
                        player.setPlaybackSpeed(1.0f)
                    }
                } else if (kotlin.math.abs(diff) > 30) {
                    val correction = (diff / 300.0f).coerceIn(-0.1f, 0.1f)
                    val newSpeed = 1.0f + correction
                    if (kotlin.math.abs(player.playbackParameters.speed - newSpeed) > 0.02f) {
                        player.setPlaybackSpeed(newSpeed)
                    }
                } else if (kotlin.math.abs(diff) < 15) {
                    if (player.playbackParameters.speed != 1.0f) {
                        player.setPlaybackSpeed(1.0f)
                    }
                }
            } else {
                if (player.playbackParameters.speed != 1.0f) {
                    player.setPlaybackSpeed(1.0f)
                }
            }
        } else {
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps audio syncing active in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Audio Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFY_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
