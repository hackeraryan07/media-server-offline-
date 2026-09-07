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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
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

class TvControlService : Service() {
    companion object {
        const val CHANNEL_ID = "TvControlChannel"
        const val NOTIFY_ID = 3
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TV_IP = "EXTRA_TV_IP"
    }

    private var tvIp: String? = null
    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var syncJob: Job? = null

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "TvControlSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { sendCommand("play") }
                override fun onPause() { sendCommand("pause") }
                override fun onSkipToNext() { sendCommand("next") }
                override fun onSkipToPrevious() { sendCommand("prev") }
                override fun onSeekTo(pos: Long) { sendCommand("seek&position=$pos") }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            tvIp = intent.getStringExtra(EXTRA_TV_IP)
            if (tvIp == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(NOTIFY_ID, buildNotification("Connecting...", false))
            startPolling()
        } else if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_NOT_STICKY
    }

    private fun sendCommand(action: String) {
        val ip = tvIp ?: return
        Thread {
            try {
                val url = "http://$ip:9000/command?action=$action"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("TvControlService", "Command failed: $action", e)
            }
        }.start()
    }

    private fun startPolling() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            while (isActive) {
                pollState()
                delay(1000)
            }
        }
    }

    private suspend fun pollState() {
        val ip = tvIp ?: return
        try {
            val request = Request.Builder().url("http://$ip:9000/state").build()
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val isPlaying = json.optBoolean("isPlaying", false)
                            val title = json.optString("title", "Video")
                            val position = json.optLong("position", 0L)
                            val duration = json.optLong("duration", 0L)

                            withContext(Dispatchers.Main) {
                                updateMediaSession(isPlaying, title, position, duration)
                                updateNotification(title, isPlaying)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TvControlService", "Error polling state", e)
        }
    }

    private fun updateMediaSession(isPlaying: Boolean, title: String, position: Long, duration: Long) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                position,
                if (isPlaying) 1f else 0f
            )
        mediaSession.setPlaybackState(stateBuilder.build())

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TV Remote Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls the TV playback"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, isPlaying: Boolean): Notification {
        val intent = Intent(this, RemoteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("tvIp", tvIp)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            ).build()
        }

        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous, "Previous",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        ).build()

        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next, "Next",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TV Remote")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification(title: String, isPlaying: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFY_ID, buildNotification(title, isPlaying))
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        mediaSession.isActive = false
        mediaSession.release()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
