package com.example.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object TvPlayerLauncher {

    const val PLAYER_INTERNAL = "internal"
    const val PLAYER_EXTERNAL = "external"
    const val PLAYER_ASK = "ask"

    fun launch(activity: Activity, video: TvVideo, playlist: ArrayList<TvVideo>? = null, currentIndex: Int = 0) {
        val prefs = activity.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val defaultPlayer = prefs.getString("default_player", PLAYER_INTERNAL) ?: PLAYER_INTERNAL

        when (defaultPlayer) {
            PLAYER_EXTERNAL -> launchExternalPlayer(activity, video, playlist, currentIndex)
            PLAYER_ASK -> showPlayerChoiceDialog(activity, video, playlist, currentIndex)
            else -> launchInternalPlayer(activity, video, playlist, currentIndex)
        }
    }

    fun launchInternalPlayer(context: Context, video: TvVideo, playlist: ArrayList<TvVideo>? = null, currentIndex: Int = 0) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("video", video)
            if (playlist != null) {
                putExtra("playlist", playlist)
                putExtra("currentIndex", currentIndex)
            }
        }
        context.startActivity(intent)
    }

    fun launchExternalPlayer(context: Context, video: TvVideo, playlist: ArrayList<TvVideo>? = null, currentIndex: Int = 0) {
        try {
            val uri = Uri.parse(video.url)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("title", video.title)
                putExtra(Intent.EXTRA_TITLE, video.title)
            }
            val chooser = Intent.createChooser(intent, "Play with external player").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "No external video player available. Launching internal player.", Toast.LENGTH_SHORT).show()
            launchInternalPlayer(context, video, playlist, currentIndex)
        }
    }

    private fun showPlayerChoiceDialog(activity: Activity, video: TvVideo, playlist: ArrayList<TvVideo>? = null, currentIndex: Int = 0) {
        val options = arrayOf(
            "Internal Player (LibVLC)",
            "External Player",
            "Always use Internal Player",
            "Always use External Player"
        )

        AlertDialog.Builder(activity)
            .setTitle("Play with which player?")
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                val prefs = activity.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                when (which) {
                    0 -> {
                        launchInternalPlayer(activity, video, playlist, currentIndex)
                    }
                    1 -> {
                        launchExternalPlayer(activity, video, playlist, currentIndex)
                    }
                    2 -> {
                        prefs.edit().putString("default_player", PLAYER_INTERNAL).apply()
                        Toast.makeText(activity, "Default player set to Internal", Toast.LENGTH_SHORT).show()
                        launchInternalPlayer(activity, video, playlist, currentIndex)
                    }
                    3 -> {
                        prefs.edit().putString("default_player", PLAYER_EXTERNAL).apply()
                        Toast.makeText(activity, "Default player set to External", Toast.LENGTH_SHORT).show()
                        launchExternalPlayer(activity, video, playlist, currentIndex)
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
