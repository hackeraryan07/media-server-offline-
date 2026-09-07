package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity

class ServerService : Service() {

    companion object {
        const val CHANNEL_ID = "VideoServerChannel"
        const val NOTIFY_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        ServerManager.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startServer()
            startForeground(NOTIFY_ID, buildNotification(ServerManager.serverAddress))
        } else if (action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    private fun startServer() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            ServerManager.multicastLock = wifiManager.createMulticastLock("video_streamer_lock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.e("ServerService", "Failed to acquire multicast lock", e)
        }

        ServerManager.errorMessage = null
        ServerManager.localVideoServer?.start { success, address ->
            ServerManager.isServerRunning = success
            ServerManager.serverAddress = address
            if (success) {
                val serverIp = address?.substringBefore(":") ?: "127.0.0.1"
                ServerManager.nsdPublisher?.registerService(8999, serverIp)
                updateNotification(address)
            } else {
                ServerManager.errorMessage = address
                updateNotification("Failed to start")
            }
        }
    }

    private fun stopServer() {
        ServerManager.localVideoServer?.stop()
        ServerManager.nsdPublisher?.unregisterService()
        ServerManager.isServerRunning = false
        ServerManager.serverAddress = null

        try {
            ServerManager.multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e("ServerService", "Exception releasing lock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Stream Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(address: String?): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local Video Server Running")
            .setContentText(if (address != null) "Address: $address" else "Starting...")
            //.setSmallIcon(R.mipmap.ic_launcher) // Wait, we don't know the package R class path, use android.R.drawable.stat_sys_data_bluetooth for now or something default
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(address: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFY_ID, buildNotification(address))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
