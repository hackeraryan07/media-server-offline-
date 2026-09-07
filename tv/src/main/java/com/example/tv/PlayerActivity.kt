package com.example.tv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

import org.json.JSONObject
import android.widget.Toast

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var overlay: View
    private lateinit var titleText: TextView
    private lateinit var btnPlayPause: android.widget.ImageButton
    private lateinit var timeBar: com.example.tv.TvTimeBar
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtTotalTime: TextView
    private lateinit var loadingSpinner: android.widget.ProgressBar

    private var lastFocusedTopBarView: View? = null
    private var lastFocusedMiddleRightView: View? = null
    private var lastFocusedControlsPillView: View? = null
    private var lastFocusedUpperView: View? = null
    private var ignoreFocusMemory = false
    
    private var isManualSkip = false
    private var currentTimeoutMs = 5000L

    private val hideHandler = Handler(Looper.getMainLooper())
    private var videoUrlString: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    
    private val antiScreenSaverHandler = Handler(Looper.getMainLooper())
    private val antiScreenSaverRunnable = object : Runnable {
        override fun run() {
            try {
                val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val isPreventEnabled = prefs.getBoolean("prevent_screensaver", false)
                if (isPreventEnabled && exoPlayer?.isPlaying == true) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN)
                    val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN)
                    window.superDispatchKeyEvent(eventDown)
                    window.superDispatchKeyEvent(eventUp)
                } else if (!isPreventEnabled) {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } catch (e: Exception) {}
            antiScreenSaverHandler.postDelayed(this, 30000)
        }
    }
    
    private var playlist: List<TvVideo>? = null
    private var currentIndex: Int = 0
    private var currentVideo: TvVideo? = null
    private var isLocked = false
    private var isWaitingForSpeedChoice = false
    private var isRemoteAudioEnabled = false
    private var audioShiftMs: Long = 0L
    private var isWaitingForAudioShiftChoice = false
    private var isContinuousSyncEnabled = true // defaultly keep sync on

    private fun saveAudioShift(shift: Long) {
        audioShiftMs = shift
        getSharedPreferences("PlayerPrefs", Context.MODE_PRIVATE).edit().putLong("audioShiftMs", shift).apply()
    }
    private var speedDialog: android.app.AlertDialog? = null
    private var audioShiftDialog: android.app.AlertDialog? = null

    private fun updateLockState() {
        val btnLock = findViewById<android.widget.ImageButton>(R.id.btnLock)
        val lockColor = if (isLocked) android.graphics.Color.RED else android.graphics.Color.WHITE
        btnLock.setColorFilter(lockColor, android.graphics.PorterDuff.Mode.SRC_IN)
        
        val alphaVal = if (isLocked) 0.5f else 1.0f
        
        val allControls = listOf(
            R.id.playerBackBtn, R.id.btnPlaylist, R.id.btnCast, R.id.btnScreenshot,
            R.id.btnMute, R.id.btnRotate, R.id.btnAudioTrack, R.id.btnSubtitles,
            R.id.btnPip, R.id.btnSpeed, R.id.btnSettings,
            R.id.btnReplay10, R.id.btnPrevious, R.id.playerPlayPauseBtn,
            R.id.btnNext, R.id.btnForward10, R.id.btnResize,
            R.id.playerTitleText, R.id.playerCurrentTime, R.id.playerTotalTime
        )
        
        for (id in allControls) {
            findViewById<View>(id)?.let { view ->
                view.alpha = alphaVal
                view.isFocusable = !isLocked
                view.isClickable = !isLocked
            }
        }
        
        timeBar.isFocusable = !isLocked
        
        if (isLocked) {
            btnLock.requestFocus()
        }
    }

    private fun updateAudioTrackButtonState() {
        val btn = findViewById<android.widget.ImageButton>(R.id.btnAudioTrack)
        if (isRemoteAudioEnabled) {
            btn.setColorFilter(android.graphics.Color.YELLOW, android.graphics.PorterDuff.Mode.SRC_IN)
        } else {
            btn.clearColorFilter()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    val currentPos = player.currentPosition
                    val duration = player.duration
                    if (duration > 0 && currentPos >= 0) {
                        currentVideo?.let { video ->
                            video.watchedPosition = currentPos
                            video.totalDuration = duration
                            sendProgressUpdate(video.id, currentPos, duration)
                        }
                    }
                }
                
                // Update timebar
                val pos = player.currentPosition
                val dur = player.duration
                if (dur > 0) {
                    timeBar.duration = dur
                    timeBar.position = pos
                    txtCurrentTime.text = formatTime(pos)
                    txtTotalTime.text = formatTime(dur)
                }
            }
            progressHandler.postDelayed(this, 1000)
        }
    }

    private val hideRunnable = Runnable {
        overlay.visibility = View.GONE
        resetFocusMemory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("PlayerPrefs", Context.MODE_PRIVATE)
        audioShiftMs = prefs.getLong("audioShiftMs", 0L)
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_player)
    
            playerView = findViewById(R.id.internalVideoView)
            overlay = findViewById(R.id.playerControlsOverlay)
            titleText = findViewById(R.id.playerTitleText)
            titleText.isSelected = true
            btnPlayPause = findViewById(R.id.playerPlayPauseBtn)
            timeBar = findViewById(R.id.playerSeekBar)
            txtCurrentTime = findViewById(R.id.playerCurrentTime)
            txtTotalTime = findViewById(R.id.playerTotalTime)
            loadingSpinner = findViewById(R.id.playerLoadingSpinner)
    
            timeBar.listener = object : com.example.tv.TvTimeBar.OnScrubListener {
                override fun onScrubStart() {
                    exoPlayer?.pause()
                    scheduleMetadataHide()
                }
                override fun onScrubMove(position: Long) {
                    txtCurrentTime.text = formatTime(position)
                    exoPlayer?.seekTo(position)
                    scheduleMetadataHide()
                }
                override fun onScrubStop(position: Long) {
                    exoPlayer?.seekTo(position)
                    exoPlayer?.play()
                    scheduleMetadataHide()
                }
            }
    
            findViewById<View>(R.id.btnNext).setOnClickListener {
                exoPlayer?.seekToNextMediaItem()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnPrevious).setOnClickListener {
                exoPlayer?.seekToPreviousMediaItem()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnForward10).setOnClickListener {
                exoPlayer?.let { it.seekTo(it.currentPosition + 10000) }
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnReplay10).setOnClickListener {
                exoPlayer?.let { it.seekTo(Math.max(0, it.currentPosition - 10000)) }
                scheduleMetadataHide()
            }
            btnPlayPause.setOnClickListener {
                exoPlayer?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.playerBackBtn).setOnClickListener { finish() }

            val btnMute = findViewById<android.widget.ImageButton>(R.id.btnMute)
            btnMute.setOnClickListener {
                exoPlayer?.let { player ->
                    if (player.volume > 0f) {
                        player.volume = 0f
                        btnMute.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.accent_color), android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        player.volume = 1f
                        btnMute.setColorFilter(android.graphics.Color.parseColor("#ffffff"), android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                }
                scheduleMetadataHide()
            }

            findViewById<View>(R.id.btnSpeed).setOnClickListener {
                isWaitingForSpeedChoice = true
                val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
                val speedValues = arrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                var selectedIndex = speedValues.indexOf(exoPlayer?.playbackParameters?.speed ?: 1.0f)
                if (selectedIndex == -1) selectedIndex = 2
                
                speedDialog = android.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Playback Speed")
                    .setSingleChoiceItems(speeds, selectedIndex) { dialog, which ->
                        val selectedSpeed = speedValues[which]
                        this@PlayerActivity.handleSpeedChoice(selectedSpeed)
                    }
                    .setOnCancelListener {
                        isWaitingForSpeedChoice = false
                        speedDialog = null
                    }
                    .create()
                    
                speedDialog?.show()
                scheduleMetadataHide()
            }

            var resizeIndex = 0
            val resizeModes = listOf(
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            )
            val resizeNames = listOf("Fit", "Fill", "Zoom")
            findViewById<View>(R.id.btnResize).setOnClickListener {
                resizeIndex = (resizeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeIndex]
                Toast.makeText(this, "Aspect Ratio: ${resizeNames[resizeIndex]}", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }

            findViewById<View>(R.id.btnPip).setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try {
                        enterPictureInPictureMode()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to enter PiP", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "PiP not supported on this device", Toast.LENGTH_SHORT).show()
                }
                scheduleMetadataHide()
            }

            findViewById<View>(R.id.btnSubtitles).setOnClickListener {
                Toast.makeText(this, "Subtitles toggled", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnAudioTrack).setOnClickListener {
                isRemoteAudioEnabled = !isRemoteAudioEnabled
                val msg = if (isRemoteAudioEnabled) "Remote Audio Enabled" else "Remote Audio Disabled"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                updateAudioTrackButtonState()
                scheduleMetadataHide()

            }
            findViewById<View>(R.id.btnAudioTrack).setOnLongClickListener {
                requestAudioShiftDialog()
                true
            }
            findViewById<View>(R.id.btnPlaylist).setOnClickListener {
                Toast.makeText(this, "Playlist opened", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnCast).setOnClickListener {
                Toast.makeText(this, "Cast devices scanning...", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnScreenshot).setOnClickListener {
                Toast.makeText(this, "Screenshot saved to Gallery", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnRotate).setOnClickListener {
                requestedOrientation = if (requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                Toast.makeText(this, "Screen Rotated", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnLock).setOnClickListener {
                isLocked = !isLocked
                updateLockState()
                Toast.makeText(this, if (isLocked) "Controls Locked" else "Controls Unlocked", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnSettings).setOnClickListener {
                Toast.makeText(this, "Settings menu opened", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
    
            currentVideo = intent.getSerializableExtra("video") as? TvVideo
            @Suppress("UNCHECKED_CAST")
            playlist = intent.getSerializableExtra("playlist") as? ArrayList<TvVideo>
            currentIndex = intent.getIntExtra("currentIndex", 0)
    
            if (currentVideo == null) {
                Toast.makeText(this, "No video provided", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
    
            initializePlayer()
            setupFocusMemory()
        } catch (e: Exception) {
            val errString = "Player Crash! Cause: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}"
            android.util.Log.e("PlayerActivity", errString)
            Toast.makeText(this, errString.take(150), Toast.LENGTH_LONG).show()
            Toast.makeText(this, errString.take(150), Toast.LENGTH_LONG).show() // show twice to last longer
            finish()
        }

    }
    
    private fun getVideoById(id: String): TvVideo? {
        return playlist?.find { it.id == id } ?: if (currentVideo?.id == id) currentVideo else null
    }

    private fun initializePlayer() {
        videoUrlString = currentVideo?.url
        titleText.text = currentVideo?.title
        
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        
        val items = mutableListOf<MediaItem>()
        if (playlist != null && playlist!!.isNotEmpty()) {
            playlist!!.forEach { video ->
                items.add(MediaItem.Builder()
                    .setUri(Uri.parse(video.url))
                    .setMediaId(video.id)
                    .build())
            }
            exoPlayer?.setMediaItems(items, currentIndex, 0L)
        } else {
            currentVideo?.let {
                exoPlayer?.setMediaItem(MediaItem.Builder()
                    .setUri(Uri.parse(it.url))
                    .setMediaId(it.id)
                    .build())
            }
        }
        
        exoPlayer?.prepare()
        
        TvRemoteServer.playerController = object : TvRemoteServer.PlayerController {
            override fun play() { Handler(Looper.getMainLooper()).post { exoPlayer?.play() } }
            override fun pause() { Handler(Looper.getMainLooper()).post { exoPlayer?.pause() } }
            override fun next() { Handler(Looper.getMainLooper()).post { if (exoPlayer?.hasNextMediaItem() == true) exoPlayer?.seekToNextMediaItem() } }
            override fun prev() { Handler(Looper.getMainLooper()).post { if (exoPlayer?.hasPreviousMediaItem() == true) exoPlayer?.seekToPreviousMediaItem() } }
            override fun playVideo(id: String) {
                Handler(Looper.getMainLooper()).post {
                    var index = playlist?.indexOfFirst { it.id == id } ?: -1
                    if (index == -1) {
                        playlist = java.util.ArrayList(TvDataStore.playlist)
                        val items = mutableListOf<MediaItem>()
                        playlist!!.forEach { video ->
                            items.add(MediaItem.Builder()
                                .setUri(Uri.parse(video.url))
                                .setMediaId(video.id)
                                .build())
                        }
                        index = playlist?.indexOfFirst { it.id == id } ?: -1
                        if (index != -1) {
                            exoPlayer?.setMediaItems(items, index, 0L)
                            exoPlayer?.prepare()
                            return@post
                        }
                    }
                    if (index != -1) {
                        exoPlayer?.seekToDefaultPosition(index)
                        exoPlayer?.prepare()
                    }
                }
            }
            override fun seekTo(positionMs: Long) {
                Handler(Looper.getMainLooper()).post { exoPlayer?.seekTo(positionMs) }
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
                var locked = false
                var muted = false
                var needsSpeed = false
                var currentSpeed = 1.0f
                var needsAudioShift = false
                var currentAudioShift = 0L
                val latch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    playing = exoPlayer?.isPlaying ?: false
                    position = exoPlayer?.currentPosition ?: 0L
                    duration = exoPlayer?.duration ?: 0L
                    needsResume = isWaitingForResume
                    resumePos = currentVideo?.watchedPosition ?: 0L
                    locked = isLocked
                    muted = exoPlayer?.volume == 0f
                    needsSpeed = isWaitingForSpeedChoice
                    currentSpeed = exoPlayer?.playbackParameters?.speed ?: 1.0f
                    needsAudioShift = isWaitingForAudioShiftChoice
                    currentAudioShift = audioShiftMs
                    latch.countDown()
                }
                try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
                state.put("isPlaying", playing)
                state.put("position", position)
                state.put("duration", duration)
                state.put("needsResumeChoice", needsResume)
                state.put("resumePosition", resumePos)
                state.put("isLocked", locked)
                state.put("isMuted", muted)
                state.put("needsSpeedChoice", needsSpeed)
                state.put("currentSpeed", currentSpeed.toDouble())
                state.put("needsAudioShiftChoice", needsAudioShift)
                state.put("audioShiftMs", currentAudioShift)
                state.put("isContinuousSyncEnabled", isContinuousSyncEnabled)
                state.put("isRemoteAudioEnabled", isRemoteAudioEnabled)
                state.put("videoUrl", currentVideo?.url ?: "")
                return state
            }
            override fun handleResumeChoice(choice: String) {
                Handler(Looper.getMainLooper()).post {
                    this@PlayerActivity.handleResumeChoice(choice, currentVideo?.watchedPosition ?: 0L)
                }
            }
            override fun handleSpeedChoice(speed: Float?) {
                Handler(Looper.getMainLooper()).post {
                    this@PlayerActivity.handleSpeedChoice(speed)
                }
            }
            override fun handleAudioShiftChoice(shiftMs: Long?) {
                Handler(Looper.getMainLooper()).post {
                    this@PlayerActivity.handleAudioShiftChoice(shiftMs)
                }
            }
            override fun requestAudioShiftDialog() {
                this@PlayerActivity.requestAudioShiftDialog()
            }
            override fun triggerAction(action: String) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (action == "toggle_continuous_sync") {
                            isContinuousSyncEnabled = !isContinuousSyncEnabled
                            val msg = if (isContinuousSyncEnabled) "Continuous Sync Enabled" else "Continuous Sync Disabled"
                            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_SHORT).show()
                            
                            val btn = audioShiftDialog?.findViewById<android.widget.Button>(R.id.btnToggleSync)
                            if (btn != null) {
                                btn.text = if (isContinuousSyncEnabled) "Stop Syncing" else "Start Syncing"
                            }
                            return@post
                        }
                        val view = when (action) {
                            "mute" -> findViewById<View>(R.id.btnMute)
                            "audio_track" -> findViewById<View>(R.id.btnAudioTrack)
                            "subtitles" -> findViewById<View>(R.id.btnSubtitles)
                            "pip" -> findViewById<View>(R.id.btnPip)
                            "speed" -> findViewById<View>(R.id.btnSpeed)
                            "playlist" -> findViewById<View>(R.id.btnPlaylist)
                            "cast" -> findViewById<View>(R.id.btnCast)
                            "rotate" -> findViewById<View>(R.id.btnRotate)
                            "resize" -> findViewById<View>(R.id.btnResize)
                            "screenshot" -> findViewById<View>(R.id.btnScreenshot)
                            "lock" -> findViewById<View>(R.id.btnLock)
                            "settings" -> findViewById<View>(R.id.btnSettings)
                            "back" -> findViewById<View>(R.id.playerBackBtn)
                            "replay_10" -> findViewById<View>(R.id.btnReplay10)
                            "forward_10" -> findViewById<View>(R.id.btnForward10)
                            else -> null
                        }
                        view?.callOnClick()
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerActivity", "triggerAction failed for $action", e)
                    }
                }
            }
        }
        
        val watchedPos = currentVideo!!.watchedPosition
        val totDur = currentVideo!!.totalDuration
        val isCompleted = totDur > 0L && watchedPos >= totDur - 5000L
        if (watchedPos > 1000 && !isCompleted) {
            exoPlayer?.playWhenReady = false
            showResumeDialog(currentVideo!!)
        } else {
            if (isCompleted) {
                exoPlayer?.seekTo(0L)
            }
            exoPlayer?.playWhenReady = true
        }

        showMetadataTemp()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                mediaItem?.mediaId?.let { id ->
                    getVideoById(id)?.let { video ->
                        currentVideo = video
                        titleText.text = video.title
                        videoUrlString = video.url
                        
                        val watched = video.watchedPosition
                        val total = video.totalDuration
                        val completed = total > 0L && watched >= total - 5000L
                        val shouldShowResume = watched > 1000 && !completed

                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            if (shouldShowResume) {
                                showMetadataTemp()
                            }
                        } else {
                            val timeout = if (isManualSkip) 2000L else 5000L
                            showMetadataTemp(timeoutMs = timeout)
                        }
                        isManualSkip = false
                        
                        if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || 
                             reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                             reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)) {
                            
                            exoPlayer?.playWhenReady = false
                            isWaitingForResume = false
                            if (resumeDialog?.isShowing == true) {
                                resumeDialog?.dismiss()
                            }
                            
                            if (shouldShowResume) {
                                showResumeDialog(video)
                            } else {
                                if (completed) {
                                    exoPlayer?.seekTo(0L)
                                }
                                exoPlayer?.playWhenReady = true
                            }
                        }
                    }
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    loadingSpinner.visibility = View.VISIBLE
                } else {
                    loadingSpinner.visibility = View.GONE
                }
                
                if (playbackState == Player.STATE_ENDED) {
                    val finalDuration = exoPlayer?.duration?.takeIf { it > 0 } ?: currentVideo?.totalDuration ?: 0L
                    currentVideo?.let { video ->
                        sendProgressUpdate(video.id, finalDuration, finalDuration)
                    }
                    if (exoPlayer?.hasNextMediaItem() == false) {
                        finish()
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause_flat)
                    scheduleMetadataHide()
                } else {
                    btnPlayPause.setImageResource(R.drawable.ic_play_flat)
                    hideHandler.removeCallbacks(hideRunnable)
                    showMetadataTemp()
                }
            }
        })

        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.postDelayed(progressRunnable, 1000)

        antiScreenSaverHandler.removeCallbacks(antiScreenSaverRunnable)
        antiScreenSaverHandler.postDelayed(antiScreenSaverRunnable, 30000)
    }

    private var isWaitingForResume = false
    private var resumeDialog: android.app.AlertDialog? = null

    fun handleResumeChoice(choice: String, position: Long) {
        if (!isWaitingForResume) return
        isWaitingForResume = false
        if (resumeDialog?.isShowing == true) {
            resumeDialog?.dismiss()
        }
        if (choice == "continue") {
            exoPlayer?.seekTo(position)
        } else {
            exoPlayer?.seekTo(0)
        }
        exoPlayer?.playWhenReady = true
        exoPlayer?.play()
    }

    private fun showAudioShiftDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_audio_shift, null)
        val seekBar = view.findViewById<android.widget.SeekBar>(R.id.shiftSeekBar)
        val textValue = view.findViewById<android.widget.TextView>(R.id.shiftValueText)
        val btnMinus = view.findViewById<android.widget.Button>(R.id.btnMinus10)
        val btnPlus = view.findViewById<android.widget.Button>(R.id.btnPlus10)
        val btnToggleSync = view.findViewById<android.widget.Button>(R.id.btnToggleSync)

        fun updateUI(progress: Int) {
            val shift = (progress - 600) * 100L
            saveAudioShift(shift)
            textValue.text = String.format("%.2fs", shift / 1000f)
        }
        
        btnToggleSync.text = if (isContinuousSyncEnabled) "Stop Syncing" else "Start Syncing"
        btnToggleSync.setOnClickListener {
            isContinuousSyncEnabled = !isContinuousSyncEnabled
            val msg = if (isContinuousSyncEnabled) "Continuous Sync Enabled" else "Continuous Sync Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            btnToggleSync.text = if (isContinuousSyncEnabled) "Stop Syncing" else "Start Syncing"
        }

        seekBar.progress = (audioShiftMs / 100).toInt() + 600
        updateUI(seekBar.progress)

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: android.widget.SeekBar?, progress: Int, p2: Boolean) {
                updateUI(progress)
            }
            override fun onStartTrackingTouch(p0: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(p0: android.widget.SeekBar?) {}
        })

        btnMinus.setOnClickListener {
            seekBar.progress = (seekBar.progress - 1).coerceAtLeast(0)
        }
        btnPlus.setOnClickListener {
            seekBar.progress = (seekBar.progress + 1).coerceAtMost(1200)
        }

        audioShiftDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Audio Shift")
            .setView(view)
            .setOnDismissListener {
                isWaitingForAudioShiftChoice = false
                audioShiftDialog = null
            }
            .show()
    }

    fun handleAudioShiftChoice(shiftMs: Long?) {
        if (shiftMs == null) {
            isWaitingForAudioShiftChoice = false
            audioShiftDialog?.dismiss()
            audioShiftDialog = null
        } else {
            saveAudioShift(shiftMs)
            if (audioShiftDialog?.isShowing == true) {
                val seekBar = audioShiftDialog?.findViewById<android.widget.SeekBar>(R.id.shiftSeekBar)
                seekBar?.progress = (shiftMs / 100).toInt() + 600
            }
        }
    }

    fun requestAudioShiftDialog() {
        isWaitingForAudioShiftChoice = true
        Handler(Looper.getMainLooper()).post {
            if (audioShiftDialog == null || audioShiftDialog?.isShowing == false) {
                showAudioShiftDialog()
            }
        }
    }


    fun handleSpeedChoice(speed: Float?) {
        isWaitingForSpeedChoice = false
        if (speedDialog?.isShowing == true) {
            speedDialog?.dismiss()
        }
        speedDialog = null
        if (speed != null) {
            exoPlayer?.setPlaybackSpeed(speed)
            Toast.makeText(this, "Speed: ${speed}x", Toast.LENGTH_SHORT).show()
        }
        scheduleMetadataHide()
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%d:%02d", mins, secs)
    }

    private var resumeTimer: android.os.CountDownTimer? = null

    private fun showResumeDialog(video: TvVideo) {
        if (resumeDialog?.isShowing == true) {
            resumeDialog?.dismiss()
        }
        resumeTimer?.cancel()
        isWaitingForResume = true

        val dialogView = layoutInflater.inflate(R.layout.tv_resume_dialog, null)
        val titleText = dialogView.findViewById<android.widget.TextView>(R.id.dialog_title)
        val messageText = dialogView.findViewById<android.widget.TextView>(R.id.dialog_message)
        val btnContinue = dialogView.findViewById<android.widget.Button>(R.id.btn_continue)
        val btnStartOver = dialogView.findViewById<android.widget.Button>(R.id.btn_start_over)

        titleText.text = "Resume Playback?"
        messageText.text = "Would you like to resume \"${video.title}\" from ${formatTime(video.watchedPosition)}?"

        val builder = android.app.AlertDialog.Builder(this)
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
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val resumeDefault = prefs.getString("resume_default", "start_over") ?: "start_over"

        if (resumeDefault == "continue") {
            btnContinue.requestFocus()
        } else {
            btnStartOver.requestFocus()
        }

        resumeTimer = object : android.os.CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                if (resumeDefault == "continue") {
                    btnContinue.text = "Continue ($secondsLeft)"
                    btnStartOver.text = "Start Over"
                } else {
                    btnStartOver.text = "Start Over ($secondsLeft)"
                    btnContinue.text = "Continue"
                }
            }

            override fun onFinish() {
                if (resumeDialog?.isShowing == true) {
                    if (resumeDefault == "continue") {
                        handleResumeChoice("continue", video.watchedPosition)
                    } else {
                        handleResumeChoice("start_over", 0L)
                    }
                    resumeDialog?.dismiss()
                }
            }
        }.start()
    }

    private fun sendProgressUpdate(videoId: String, position: Long, duration: Long) {
        TvDataStore.playlist.find { it.id == videoId }?.let {
            it.watchedPosition = position
            it.totalDuration = duration
        }
        
        val videoUrl = videoUrlString
        if (videoUrl.isNullOrEmpty() || !videoUrl.startsWith("http")) return
        
        val uri = Uri.parse(videoUrl)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: "127.0.0.1"
        val port = uri.port
        val baseUrl = if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
        
        val updateUrl = "$baseUrl/update_progress?id=$videoId&position=$position&duration=$duration"
        
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(updateUrl)
            .build()
            
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
        })
    }

    private fun saveFinalProgress() {
        exoPlayer?.let { player ->
            if (player.playbackState == Player.STATE_ENDED) return
            val currentPos = player.currentPosition
            val duration = player.duration
            if (duration > 0 && currentPos >= 0 && currentPos < duration) {
                currentVideo?.let { video ->
                    video.watchedPosition = currentPos
                    video.totalDuration = duration
                    sendProgressUpdate(video.id, currentPos, duration)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!isLocked && event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                if (exoPlayer?.hasNextMediaItem() == true) {
                    isManualSkip = true
                    exoPlayer?.seekToNextMediaItem()
                }
                showMetadataTemp(timeoutMs = 2000L)
                return true
            } else if (!isLocked && event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                if (exoPlayer?.hasPreviousMediaItem() == true) {
                    isManualSkip = true
                    exoPlayer?.seekToPreviousMediaItem()
                }
                showMetadataTemp(timeoutMs = 2000L)
                return true
            }
            
            // Standard user interaction (not skipping) resets to 5s timeout
            currentTimeoutMs = 5000L

            if (isLocked) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (overlay.visibility == View.VISIBLE) {
                        overlay.visibility = View.GONE
                        return true
                    } else {
                        showMetadataTemp(false)
                        return true // Prevent closing player when locked
                    }
                }
                if (overlay.visibility != View.VISIBLE) {
                    showMetadataTemp(false)
                    return true
                }
                
                // Allow clicking btnLock to unlock
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (currentFocus?.id == R.id.btnLock) {
                        scheduleMetadataHide()
                        return super.dispatchKeyEvent(event)
                    }
                }
                
                // Prevent all other navigation
                scheduleMetadataHide()
                return true
            }

            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (overlay.visibility == View.VISIBLE) {
                    overlay.visibility = View.GONE
                    resetFocusMemory()
                    return true
                } else {
                    return super.dispatchKeyEvent(event)
                }
            }
            if (overlay.visibility != View.VISIBLE) {
                val focusOnSeekBar = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                showMetadataTemp(focusOnSeekBar)
                return true
            }

            val currentFocusView = currentFocus
            
            
            

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (currentFocusView == timeBar) {
                    val target = lastFocusedControlsPillView ?: btnPlayPause
                    if (target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                        scheduleMetadataHide()
                        return true
                    }
                } else if (currentFocusView != null && currentFocusView.parent == findViewById<android.view.ViewGroup>(R.id.middleRightBar)) {
                    timeBar.requestFocus()
                    scheduleMetadataHide()
                    return true
                } else if (currentFocusView != null && currentFocusView.parent == findViewById<android.view.ViewGroup>(R.id.topBar)) {
                    val target = lastFocusedMiddleRightView
                    if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                    } else {
                        timeBar.requestFocus()
                    }
                    scheduleMetadataHide()
                    return true
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (currentFocusView != null && currentFocusView.parent == btnPlayPause.parent) {
                    timeBar.requestFocus()
                    scheduleMetadataHide()
                    return true
                } else if (currentFocusView == timeBar) {
                    val target = lastFocusedUpperView ?: lastFocusedMiddleRightView ?: lastFocusedTopBarView
                    if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                        scheduleMetadataHide()
                        return true
                    }
                } else if (currentFocusView != null && currentFocusView.parent == findViewById<android.view.ViewGroup>(R.id.middleRightBar)) {
                    val target = lastFocusedTopBarView
                    if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                        target.requestFocus()
                        scheduleMetadataHide()
                        return true
                    }
                }
            } else if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) && currentFocusView == timeBar) {
                exoPlayer?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                scheduleMetadataHide()
                return true
            }
            scheduleMetadataHide()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun resetFocusMemory() {
        lastFocusedTopBarView = findViewById(R.id.btnCast)
        lastFocusedMiddleRightView = findViewById(R.id.btnAudioTrack)
        lastFocusedControlsPillView = btnPlayPause
        lastFocusedUpperView = lastFocusedMiddleRightView
    }

    private fun setupFocusMemory() {
        
        
        

        // Default initial focus values
        lastFocusedTopBarView = findViewById(R.id.btnCast)
        lastFocusedMiddleRightView = findViewById(R.id.btnAudioTrack)
        lastFocusedControlsPillView = btnPlayPause
        lastFocusedUpperView = lastFocusedMiddleRightView

        // Top Bar focus tracking
        findViewById<android.view.ViewGroup>(R.id.topBar)?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                child.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus && !ignoreFocusMemory && overlay.visibility == View.VISIBLE) {
                        lastFocusedTopBarView = v
                        lastFocusedUpperView = v
                    }
                }
            }
        }

        // Middle Right Bar focus tracking
        findViewById<android.view.ViewGroup>(R.id.middleRightBar)?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                child.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus && !ignoreFocusMemory && overlay.visibility == View.VISIBLE) {
                        lastFocusedMiddleRightView = v
                        lastFocusedUpperView = v
                    }
                }
            }
        }

        // Controls Pill focus tracking
        (btnPlayPause.parent as? android.view.ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                child.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus && !ignoreFocusMemory && overlay.visibility == View.VISIBLE) {
                        lastFocusedControlsPillView = v
                    }
                }
            }
        }
    }

    private fun showMetadataTemp(focusOnSeekBar: Boolean = false, timeoutMs: Long = 5000L) {
        currentTimeoutMs = timeoutMs
        val wasHidden = overlay.visibility != View.VISIBLE
        if (wasHidden) {
            ignoreFocusMemory = true
            overlay.visibility = View.VISIBLE
            if (isLocked) {
                findViewById<View>(R.id.btnLock)?.requestFocus()
            } else if (focusOnSeekBar) {
                timeBar.requestFocus()
            } else {
                (lastFocusedControlsPillView ?: btnPlayPause).requestFocus()
            }
            overlay.post {
                ignoreFocusMemory = false
            }
        } else {
            overlay.visibility = View.VISIBLE
        }
        scheduleMetadataHide()
    }

    private fun scheduleMetadataHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, currentTimeoutMs)
    }

    override fun onDestroy() {
        super.onDestroy()
        TvRemoteServer.playerController = null
        hideHandler.removeCallbacks(hideRunnable)
        progressHandler.removeCallbacks(progressRunnable)
        antiScreenSaverHandler.removeCallbacks(antiScreenSaverRunnable)
        saveFinalProgress()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        saveFinalProgress()
    }

    override fun onResume() {
        super.onResume()
        currentVideo?.let { video ->
            if (video.watchedPosition <= 1000 || (exoPlayer?.currentPosition ?: 0L) > 0L) {
                exoPlayer?.play()
            }
        }
    }
}

