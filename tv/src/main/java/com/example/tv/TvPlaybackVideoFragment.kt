package com.example.tv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import org.json.JSONObject

class TvPlaybackVideoFragment : VideoSupportFragment() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var playerAdapter: LeanbackPlayerAdapter
    private lateinit var transportControlGlue: PlaybackTransportControlGlue<LeanbackPlayerAdapter>

    private var playlist: List<TvVideo>? = null
    private var currentIndex: Int = 0
    private var currentVideo: TvVideo? = null
    private var videoUrlString: String? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isWaitingForResume = false
    private var resumeDialog: android.app.AlertDialog? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (exoPlayer.isPlaying) {
                val currentPos = exoPlayer.currentPosition
                val duration = exoPlayer.duration
                if (duration > 0 && currentPos >= 0) {
                    currentVideo?.let { video ->
                        sendProgressUpdate(video.id, currentPos, duration)
                    }
                }
            }
            progressHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentVideo = activity?.intent?.getSerializableExtra("video") as? TvVideo
        playlist = activity?.intent?.getSerializableExtra("playlist") as? ArrayList<TvVideo>
        currentIndex = activity?.intent?.getIntExtra("currentIndex", 0) ?: 0

        if (currentVideo == null) {
            activity?.finish()
            return
        }

        initializePlayer()
    }

    private fun initializePlayer() {
        videoUrlString = currentVideo?.url

        exoPlayer = ExoPlayer.Builder(requireContext()).build()
        
        val items = mutableListOf<MediaItem>()
        if (!playlist.isNullOrEmpty()) {
            playlist!!.forEach { video ->
                items.add(MediaItem.Builder()
                    .setUri(Uri.parse(video.url))
                    .setMediaId(video.id)
                    .build())
            }
            exoPlayer.setMediaItems(items, currentIndex, 0L)
        } else {
            currentVideo?.let {
                exoPlayer.setMediaItem(MediaItem.Builder()
                    .setUri(Uri.parse(it.url))
                    .setMediaId(it.id)
                    .build())
            }
        }
        exoPlayer.prepare()

        playerAdapter = LeanbackPlayerAdapter(requireContext(), exoPlayer, 50)
        val glueHost = VideoSupportFragmentGlueHost(this@TvPlaybackVideoFragment)
        transportControlGlue = PlaybackTransportControlGlue(requireContext(), playerAdapter)
        transportControlGlue.host = glueHost
        transportControlGlue.title = currentVideo?.title
        transportControlGlue.subtitle = "Streaming from Mobile Wi-Fi Server"
        transportControlGlue.isSeekEnabled = true
        transportControlGlue.playWhenPrepared()

        setupRemoteController()
        
        val watchedPos = currentVideo!!.watchedPosition
        val totDur = currentVideo!!.totalDuration
        val isCompleted = totDur > 0L && watchedPos >= totDur - 5000L
        if (watchedPos > 1000 && !isCompleted) {
            exoPlayer.playWhenReady = false
            showResumeDialog(currentVideo!!)
        } else {
            if (isCompleted) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.playWhenReady = true
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                mediaItem?.mediaId?.let { id ->
                    getVideoById(id)?.let { video ->
                        currentVideo = video
                        transportControlGlue.title = video.title
                        videoUrlString = video.url
                        
                        if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || 
                             reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                             reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)) {
                            
                            exoPlayer.playWhenReady = false
                            isWaitingForResume = false
                            if (resumeDialog?.isShowing == true) {
                                resumeDialog?.dismiss()
                            }
                            
                            val watched = video.watchedPosition
                            val total = video.totalDuration
                            val completed = total > 0L && watched >= total - 5000L
                            if (watched > 1000 && !completed) {
                                showResumeDialog(video)
                            } else {
                                if (completed) {
                                    exoPlayer.seekTo(0L)
                                }
                                exoPlayer.playWhenReady = true
                            }
                        }
                    }
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val finalDuration = exoPlayer.duration.takeIf { it > 0 } ?: currentVideo?.totalDuration ?: 0L
                    currentVideo?.let { video ->
                        sendProgressUpdate(video.id, finalDuration, finalDuration)
                    }
                    if (!exoPlayer.hasNextMediaItem()) {
                        activity?.finish()
                    }
                }
            }
        })

        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.postDelayed(progressRunnable, 3000)
    }
    
    private fun getVideoById(id: String): TvVideo? {
        return playlist?.find { it.id == id } ?: if (currentVideo?.id == id) currentVideo else null
    }

    private fun setupRemoteController() {
        TvRemoteServer.playerController = object : TvRemoteServer.PlayerController {
            override fun play() { Handler(Looper.getMainLooper()).post { transportControlGlue.play() } }
            override fun pause() { Handler(Looper.getMainLooper()).post { transportControlGlue.pause() } }
            override fun next() { Handler(Looper.getMainLooper()).post { transportControlGlue.next() } }
            override fun prev() { Handler(Looper.getMainLooper()).post { transportControlGlue.previous() } }
            override fun playVideo(id: String) {
                Handler(Looper.getMainLooper()).post {
                    var index = playlist?.indexOfFirst { it.id == id } ?: -1
                    if (index == -1) {
                        playlist = ArrayList(TvDataStore.playlist)
                        val items = mutableListOf<MediaItem>()
                        playlist!!.forEach { video ->
                            items.add(MediaItem.Builder()
                                .setUri(Uri.parse(video.url))
                                .setMediaId(video.id)
                                .build())
                        }
                        index = playlist?.indexOfFirst { it.id == id } ?: -1
                        if (index != -1) {
                            exoPlayer.setMediaItems(items, index, 0L)
                            exoPlayer.prepare()
                            return@post
                        }
                    }
                    if (index != -1) {
                        exoPlayer.seekToDefaultPosition(index)
                        exoPlayer.prepare()
                    }
                }
            }
            override fun seekTo(positionMs: Long) {
                Handler(Looper.getMainLooper()).post { exoPlayer.seekTo(positionMs) }
            }
            override fun getState(): JSONObject {
                val state = JSONObject()
                state.put("videoId", currentVideo?.id ?: "")
                state.put("title", currentVideo?.title ?: "")
                var playing = false
                var position = 0L
                var duration = 0L
                var needsResume = false
                var resumePos = 0L
                val latch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    playing = exoPlayer.isPlaying
                    position = exoPlayer.currentPosition
                    duration = exoPlayer.duration
                    needsResume = isWaitingForResume
                    resumePos = currentVideo?.watchedPosition ?: 0L
                    latch.countDown()
                }
                try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
                state.put("isPlaying", playing)
                state.put("position", position)
                state.put("duration", duration)
                state.put("needsResumeChoice", needsResume)
                state.put("resumePosition", resumePos)
                state.put("needsAudioShiftChoice", false)
                state.put("audioShiftMs", 0L)
                state.put("isRemoteAudioEnabled", false)
                state.put("videoUrl", currentVideo?.url ?: "")
                return state
            }
            override fun handleResumeChoice(choice: String) {
                Handler(Looper.getMainLooper()).post {
                    this@TvPlaybackVideoFragment.handleResumeChoice(choice, currentVideo?.watchedPosition ?: 0L)
                }
            }
            override fun handleSpeedChoice(speed: Float?) {
                // Leanback doesn't easily support speed changes in this basic implementation, ignore for now
            }
            override fun handleAudioShiftChoice(shiftMs: Long?) {
                // Not supported
            }
            override fun requestAudioShiftDialog() {
                // Not supported
            }
            override fun triggerAction(action: String) {
                // Not supported/available in the classic Leanback Playback fragment
            }
        }
    }

    fun handleResumeChoice(choice: String, position: Long) {
        if (!isWaitingForResume) return
        isWaitingForResume = false
        if (resumeDialog?.isShowing == true) {
            resumeDialog?.dismiss()
        }
        if (choice == "continue") {
            exoPlayer.seekTo(position)
        } else {
            exoPlayer.seekTo(0)
        }
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%d:%02d", mins, secs)
    }

    private var resumeTimer: android.os.CountDownTimer? = null

    private fun showResumeDialog(video: TvVideo) {
        Handler(Looper.getMainLooper()).post {
            if (resumeDialog?.isShowing == true) {
                resumeDialog?.dismiss()
            }
            resumeTimer?.cancel()
            if (activity == null || activity?.isFinishing == true) return@post
            isWaitingForResume = true

            val dialogView = layoutInflater.inflate(R.layout.tv_resume_dialog, null)
            val titleText = dialogView.findViewById<android.widget.TextView>(R.id.dialog_title)
            val messageText = dialogView.findViewById<android.widget.TextView>(R.id.dialog_message)
            val btnContinue = dialogView.findViewById<android.widget.Button>(R.id.btn_continue)
            val btnStartOver = dialogView.findViewById<android.widget.Button>(R.id.btn_start_over)

            titleText.text = "Resume Playback?"
            messageText.text = "Would you like to resume \"${video.title}\" from ${formatTime(video.watchedPosition)}?"

            val builder = android.app.AlertDialog.Builder(requireActivity())
            builder.setView(dialogView)
            builder.setCancelable(false)
            resumeDialog = builder.create()
            
            resumeDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnContinue.setOnClickListener {
                resumeTimer?.cancel()
                handleResumeChoice("continue", video.watchedPosition)
                resumeDialog?.dismiss()
            }

            btnStartOver.setOnClickListener {
                resumeTimer?.cancel()
                handleResumeChoice("start_over", 0L)
                resumeDialog?.dismiss()
            }

            resumeDialog?.show()
            btnStartOver.requestFocus()

            resumeTimer = object : android.os.CountDownTimer(10000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsLeft = (millisUntilFinished / 1000) + 1
                    btnStartOver.text = "Start Over ($secondsLeft)"
                }

                override fun onFinish() {
                    if (resumeDialog?.isShowing == true) {
                        handleResumeChoice("start_over", 0L)
                        resumeDialog?.dismiss()
                    }
                }
            }.start()
        }
    }

    private fun sendProgressUpdate(videoId: String, position: Long, duration: Long) {
        val videoUrl = videoUrlString
        if (videoUrl.isNullOrEmpty() || !videoUrl.startsWith("http")) return
        
        val uri = Uri.parse(videoUrl)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: "127.0.0.1"
        val port = uri.port
        val baseUrl = if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
        
        val updateUrl = "$baseUrl/update_progress?id=$videoId&position=$position&duration=$duration"
        
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(updateUrl).build()
            
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
        })
    }

    private fun saveFinalProgress() {
        if (!::exoPlayer.isInitialized) return
        if (exoPlayer.playbackState == Player.STATE_ENDED) return
        val currentPos = exoPlayer.currentPosition
        val duration = exoPlayer.duration
        if (duration > 0 && currentPos >= 0 && currentPos < duration) {
            currentVideo?.let { video ->
                sendProgressUpdate(video.id, currentPos, duration)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TvRemoteServer.playerController = null
        progressHandler.removeCallbacks(progressRunnable)
        saveFinalProgress()
        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::exoPlayer.isInitialized) {
            exoPlayer.pause()
        }
        saveFinalProgress()
    }

    override fun onResume() {
        super.onResume()
        if (::exoPlayer.isInitialized) {
            currentVideo?.let { video ->
                if (video.watchedPosition <= 1000 || exoPlayer.currentPosition > 0L) {
                    exoPlayer.play()
                }
            }
        }
    }
}
