package com.example.tv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivity : AppCompatActivity() {

    private lateinit var videoLayout: VLCVideoLayout
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var overlay: View
    private lateinit var titleText: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var timeBar: TvTimeBar
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtTotalTime: TextView
    private lateinit var loadingSpinner: ProgressBar

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
    private var pendingSeekPosition: Long = 0L

    private val antiScreenSaverHandler = Handler(Looper.getMainLooper())
    private val antiScreenSaverRunnable = object : Runnable {
        override fun run() {
            try {
                val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val isPreventEnabled = prefs.getBoolean("prevent_screensaver", false)
                if (isPreventEnabled && mediaPlayer?.isPlaying == true) {
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
    private var isContinuousSyncEnabled = true // default keep sync on

    private fun saveAudioShift(shift: Long) {
        audioShiftMs = shift
        getSharedPreferences("PlayerPrefs", Context.MODE_PRIVATE).edit().putLong("audioShiftMs", shift).apply()
        try {
            mediaPlayer?.setAudioDelay(shift * 1000L)
        } catch (e: Exception) {}
    }

    private var speedDialog: android.app.AlertDialog? = null
    private var audioShiftDialog: android.app.AlertDialog? = null

    private fun updateLockState() {
        val btnLock = findViewById<ImageButton>(R.id.btnLock)
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
        val btn = findViewById<ImageButton>(R.id.btnAudioTrack)
        if (isRemoteAudioEnabled) {
            btn.setColorFilter(android.graphics.Color.YELLOW, android.graphics.PorterDuff.Mode.SRC_IN)
        } else {
            btn.clearColorFilter()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                val currentPos = player.time
                val duration = player.length
                if (player.isPlaying) {
                    if (duration > 0 && currentPos >= 0) {
                        currentVideo?.let { video ->
                            video.watchedPosition = currentPos
                            video.totalDuration = duration
                            sendProgressUpdate(video.id, currentPos, duration)
                        }
                    }
                }

                // Update timebar
                if (duration > 0) {
                    timeBar.duration = duration
                    timeBar.position = currentPos
                    txtCurrentTime.text = formatTime(currentPos)
                    txtTotalTime.text = formatTime(duration)
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
            window.setFormat(android.graphics.PixelFormat.RGBA_8888)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_player)

            videoLayout = findViewById(R.id.internalVideoView)
            videoLayout.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    if (child is android.view.SurfaceView) {
                        child.holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
                    }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
            for (i in 0 until videoLayout.childCount) {
                val child = videoLayout.getChildAt(i)
                if (child is android.view.SurfaceView) {
                    child.holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
                }
            }
            overlay = findViewById(R.id.playerControlsOverlay)
            titleText = findViewById(R.id.playerTitleText)
            titleText.isSelected = true
            btnPlayPause = findViewById(R.id.playerPlayPauseBtn)
            timeBar = findViewById(R.id.playerSeekBar)
            txtCurrentTime = findViewById(R.id.playerCurrentTime)
            txtTotalTime = findViewById(R.id.playerTotalTime)
            loadingSpinner = findViewById(R.id.playerLoadingSpinner)

            timeBar.listener = object : TvTimeBar.OnScrubListener {
                override fun onScrubStart() {
                    mediaPlayer?.pause()
                    scheduleMetadataHide()
                }
                override fun onScrubMove(position: Long) {
                    txtCurrentTime.text = formatTime(position)
                    mediaPlayer?.time = position
                    scheduleMetadataHide()
                }
                override fun onScrubStop(position: Long) {
                    mediaPlayer?.time = position
                    mediaPlayer?.play()
                    scheduleMetadataHide()
                }
            }

            findViewById<View>(R.id.btnNext).setOnClickListener {
                playNext()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnPrevious).setOnClickListener {
                playPrevious()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnForward10).setOnClickListener {
                mediaPlayer?.let { player ->
                    val newPos = player.time + 10000L
                    val dur = player.length
                    player.time = if (dur > 0) newPos.coerceAtMost(dur) else newPos
                }
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnReplay10).setOnClickListener {
                mediaPlayer?.let { player ->
                    player.time = (player.time - 10000L).coerceAtLeast(0L)
                }
                scheduleMetadataHide()
            }
            btnPlayPause.setOnClickListener {
                mediaPlayer?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.playerBackBtn).setOnClickListener { finish() }

            val btnMute = findViewById<ImageButton>(R.id.btnMute)
            btnMute.setOnClickListener {
                mediaPlayer?.let { player ->
                    if (player.volume > 0) {
                        player.volume = 0
                        btnMute.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.accent_color), android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        player.volume = 100
                        btnMute.setColorFilter(android.graphics.Color.parseColor("#ffffff"), android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                }
                scheduleMetadataHide()
            }

            findViewById<View>(R.id.btnSpeed).setOnClickListener {
                isWaitingForSpeedChoice = true
                val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
                val speedValues = arrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                val currentRate = mediaPlayer?.rate ?: 1.0f
                var selectedIndex = speedValues.indexOfFirst { kotlin.math.abs(it - currentRate) < 0.05f }
                if (selectedIndex == -1) selectedIndex = 2

                speedDialog = android.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Playback Speed")
                    .setSingleChoiceItems(speeds, selectedIndex) { _, which ->
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
            val resizeNames = listOf("Fit", "16:9", "4:3", "Original", "Zoom")
            findViewById<View>(R.id.btnResize).setOnClickListener {
                resizeIndex = (resizeIndex + 1) % resizeNames.size
                when (resizeNames[resizeIndex]) {
                    "Fit" -> {
                        mediaPlayer?.aspectRatio = null
                        mediaPlayer?.scale = 0f
                    }
                    "16:9" -> {
                        mediaPlayer?.aspectRatio = "16:9"
                    }
                    "4:3" -> {
                        mediaPlayer?.aspectRatio = "4:3"
                    }
                    "Original" -> {
                        mediaPlayer?.aspectRatio = null
                        mediaPlayer?.scale = 1.0f
                    }
                    "Zoom" -> {
                        mediaPlayer?.aspectRatio = null
                        mediaPlayer?.scale = 1.3f
                    }
                }
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
                showSubtitleDialog()
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
                showAudioShiftDialog()
                scheduleMetadataHide()
                true
            }
            updateAudioTrackButtonState()
            findViewById<View>(R.id.btnPlaylist).setOnClickListener {
                Toast.makeText(this, "Playlist opened (${playlist?.size ?: 1} items)", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnCast).setOnClickListener {
                Toast.makeText(this, "Cast devices scanning...", Toast.LENGTH_SHORT).show()
                scheduleMetadataHide()
            }
            findViewById<View>(R.id.btnScreenshot).setOnClickListener {
                Toast.makeText(this, "Screenshot captured", Toast.LENGTH_SHORT).show()
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
                val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val curQualityMode = appPrefs.getString("video_quality_mode", "peak") ?: "peak"
                val qualityLabel = when (curQualityMode) {
                    "balanced" -> "Balanced (32-Bit)"
                    "powersave" -> "Compatibility"
                    else -> "Peak (RV32 32-Bit / HW Direct)"
                }
                val options = arrayOf(
                    "Select Audio Track",
                    "Select Subtitles",
                    "Audio Shift",
                    "Playback Speed",
                    "Video Quality: $qualityLabel",
                    "Open in External Player"
                )
                android.app.AlertDialog.Builder(this)
                    .setTitle("Settings")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> showAudioTrackDialog()
                            1 -> showSubtitleDialog()
                            2 -> showAudioShiftDialog()
                            3 -> findViewById<View>(R.id.btnSpeed).callOnClick()
                            4 -> showVideoQualityDialog()
                            5 -> {
                                currentVideo?.let { video ->
                                    mediaPlayer?.pause()
                                    TvPlayerLauncher.launchExternalPlayer(this, video, playlist?.let { ArrayList(it) }, currentIndex)
                                }
                            }
                        }
                    }
                    .show()
                scheduleMetadataHide()
            }

            currentVideo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra("video", TvVideo::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra("video") as? TvVideo
            }
            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            playlist = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra("playlist", ArrayList::class.java) as? ArrayList<TvVideo>
            } else {
                intent.getSerializableExtra("playlist") as? ArrayList<TvVideo>
            }
            currentIndex = intent.getIntExtra("currentIndex", 0)

            if (currentVideo == null && intent.data != null) {
                val uri = intent.data!!
                val title = uri.lastPathSegment ?: "Media"
                currentVideo = TvVideo(
                    id = uri.toString(),
                    title = title,
                    url = uri.toString(),
                    duration = "00:00",
                    isLocal = true
                )
            }

            if (currentVideo == null) {
                Toast.makeText(this, "No media provided", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            initializeVlcPlayer()
            setupFocusMemory()
        } catch (t: Throwable) {
            val errString = "Internal player error: ${t.javaClass.simpleName}: ${t.message}"
            android.util.Log.e("PlayerActivity", errString, t)
            Toast.makeText(this, errString, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun getVideoById(id: String): TvVideo? {
        return playlist?.find { it.id == id } ?: if (currentVideo?.id == id) currentVideo else null
    }

    private fun hasNext(): Boolean {
        val list = playlist
        return !list.isNullOrEmpty() && currentIndex + 1 < list.size
    }

    private fun hasPrevious(): Boolean {
        val list = playlist
        return !list.isNullOrEmpty() && currentIndex - 1 >= 0
    }

    private fun playNext() {
        val list = playlist
        if (!list.isNullOrEmpty() && currentIndex + 1 < list.size) {
            currentIndex++
            isManualSkip = true
            playVideoItem(list[currentIndex])
        }
    }

    private fun playPrevious() {
        val list = playlist
        if (!list.isNullOrEmpty() && currentIndex - 1 >= 0) {
            currentIndex--
            isManualSkip = true
            playVideoItem(list[currentIndex])
        }
    }

    private fun createLibVlcInstance(): LibVLC {
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val qualityMode = appPrefs.getString("video_quality_mode", "peak") ?: "peak"

        // Tier 1: Peak Quality with safe LibVLC Android core options
        val qualityOptions = ArrayList<String>().apply {
            add("--audio-time-stretch")
            add("--network-caching=3000")
            add("--file-caching=2000")
            add("--live-caching=2000")
            add("--rtsp-tcp")
            if (qualityMode == "peak") {
                // -1 = Never skip loop filter deblocking, keeping full macroblock sharpness
                add("--avcodec-skiploopfilter")
                add("-1")
                // 0 = Never skip frames
                add("--avcodec-skip-frame")
                add("0")
                // 0 = Full precision IDCT
                add("--avcodec-skip-idct")
                add("0")
            } else if (qualityMode == "balanced") {
                add("--avcodec-skiploopfilter")
                add("0")
            }
        }

        try {
            return LibVLC(this, qualityOptions)
        } catch (t: Throwable) {
            android.util.Log.w("PlayerActivity", "LibVLC init failed with quality options: ${t.message}", t)
        }

        // Tier 2: Safe basic options fallback
        try {
            val basicOptions = arrayListOf("--network-caching=3000", "--rtsp-tcp")
            return LibVLC(this, basicOptions)
        } catch (t: Throwable) {
            android.util.Log.w("PlayerActivity", "LibVLC init failed with basic options: ${t.message}", t)
        }

        // Tier 3: Zero-arguments default constructor
        return LibVLC(this)
    }

    private fun initializeVlcPlayer(initialResumePosition: Long = 0L) {
        if (isFinishing || isDestroyed) return

        try {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer?.stop()
                    if (mediaPlayer?.vlcVout?.areViewsAttached() == true) {
                        mediaPlayer?.detachViews()
                    }
                    mediaPlayer?.release()
                } catch (e: Exception) {}
                mediaPlayer = null
            }
            if (libVLC != null) {
                try {
                    libVLC?.release()
                } catch (e: Exception) {}
                libVLC = null
            }
        } catch (e: Exception) {}

        libVLC = createLibVlcInstance()
        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer?.aspectRatio = null
        mediaPlayer?.scale = 0f
        
        // Attach views safely if not already attached (use single surface to prevent overlay conflicts)
        try {
            if (mediaPlayer?.vlcVout?.areViewsAttached() != true) {
                mediaPlayer?.attachViews(videoLayout, null, false, false)
            }
        } catch (t: Throwable) {
            android.util.Log.e("PlayerActivity", "Error attaching views", t)
        }

        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (event.buffering < 100f) {
                            loadingSpinner.visibility = View.VISIBLE
                        } else {
                            loadingSpinner.visibility = View.GONE
                        }
                    }
                }
                MediaPlayer.Event.Playing -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        loadingSpinner.visibility = View.GONE
                        btnPlayPause.setImageResource(R.drawable.ic_pause_flat)
                        if (pendingSeekPosition > 0L) {
                            mediaPlayer?.time = pendingSeekPosition
                            pendingSeekPosition = 0L
                        }
                        scheduleMetadataHide()
                    }
                }
                MediaPlayer.Event.Paused -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        btnPlayPause.setImageResource(R.drawable.ic_play_flat)
                        hideHandler.removeCallbacks(hideRunnable)
                        showMetadataTemp()
                    }
                }
                MediaPlayer.Event.Stopped -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        btnPlayPause.setImageResource(R.drawable.ic_play_flat)
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        handleMediaEnded()
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        loadingSpinner.visibility = View.GONE
                        Toast.makeText(this@PlayerActivity, "Error playing video with VLC", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        setupRemoteController()

        // Start playing the current video
        val videoToPlay = if (!playlist.isNullOrEmpty() && currentIndex in playlist!!.indices) {
            playlist!![currentIndex]
        } else {
            currentVideo
        }
        if (videoToPlay != null) {
            playVideoItem(videoToPlay, resumePosition = initialResumePosition)
        }

        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.postDelayed(progressRunnable, 1000)

        antiScreenSaverHandler.removeCallbacks(antiScreenSaverRunnable)
        antiScreenSaverHandler.postDelayed(antiScreenSaverRunnable, 30000)
    }

    private fun playVideoItem(video: TvVideo, resumePosition: Long = 0L) {
        try {
            currentVideo = video
            videoUrlString = video.url
            titleText.text = video.title

            loadingSpinner.visibility = View.VISIBLE

            if (video.url.isBlank()) {
                loadingSpinner.visibility = View.GONE
                Toast.makeText(this, "Empty video URL", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val media = if (video.url.startsWith("content://")) {
                val uri = Uri.parse(video.url)
                var fdMedia: Media? = null
                try {
                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        fdMedia = Media(libVLC, pfd.fileDescriptor)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PlayerActivity", "Could not open FileDescriptor for content URI: $uri", e)
                }
                fdMedia ?: Media(libVLC, uri)
            } else if (video.url.startsWith("/") || (!video.url.contains("://") && !video.url.startsWith("http"))) {
                Media(libVLC, video.url)
            } else {
                Media(libVLC, Uri.parse(video.url))
            }

            val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val qualityMode = appPrefs.getString("video_quality_mode", "peak") ?: "peak"

            try {
                // Force full hardware acceleration (force = true overrides device model blacklists)
                media.setHWDecoderEnabled(true, true)
            } catch (e: Exception) {
                android.util.Log.w("PlayerActivity", "Could not set HWDecoderEnabled", e)
            }

            if (qualityMode == "peak") {
                media.apply {
                    addOption(":codec=mediacodec_ndk,mediacodec_jni,all")
                    addOption(":mediacodec-dr=1")
                    addOption(":no-mediacodec-dr=0")
                    addOption(":mediacodec-audio=1")
                    addOption(":avcodec-hw=any")
                    addOption(":avcodec-skiploopfilter=-1")
                    addOption(":avcodec-skip-frame=0")
                    addOption(":network-caching=3000")
                    addOption(":file-caching=2000")
                    addOption(":live-caching=2000")
                }
            } else if (qualityMode == "balanced") {
                media.apply {
                    addOption(":codec=mediacodec_ndk,mediacodec_jni,all")
                    addOption(":mediacodec-dr=1")
                    addOption(":network-caching=3000")
                    addOption(":file-caching=2000")
                }
            }

            mediaPlayer?.media = media
            media.release()

            val watchedPos = video.watchedPosition
            val totDur = video.totalDuration
            val isCompleted = totDur > 0L && watchedPos >= totDur - 5000L

            if (resumePosition > 0L) {
                pendingSeekPosition = resumePosition
                mediaPlayer?.play()
            } else if (watchedPos > 1000 && !isCompleted) {
                showResumeDialog(video)
            } else {
                if (isCompleted) {
                    pendingSeekPosition = 0L
                }
                mediaPlayer?.play()
            }

            val timeout = if (isManualSkip) 2000L else 5000L
            showMetadataTemp(timeoutMs = timeout)
            isManualSkip = false
        } catch (t: Throwable) {
            loadingSpinner.visibility = View.GONE
            android.util.Log.e("PlayerActivity", "Failed to play video item: ${video.title}", t)
            Toast.makeText(this, "Internal player error: ${t.localizedMessage ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleMediaEnded() {
        val finalDuration = mediaPlayer?.length?.takeIf { it > 0 } ?: currentVideo?.totalDuration ?: 0L
        currentVideo?.let { video ->
            sendProgressUpdate(video.id, finalDuration, finalDuration)
        }
        if (hasNext()) {
            playNext()
        } else {
            finish()
        }
    }

    private fun setupRemoteController() {
        TvRemoteServer.playerController = object : TvRemoteServer.PlayerController {
            override fun play() { Handler(Looper.getMainLooper()).post { mediaPlayer?.play() } }
            override fun pause() { Handler(Looper.getMainLooper()).post { mediaPlayer?.pause() } }
            override fun next() { Handler(Looper.getMainLooper()).post { if (hasNext()) playNext() } }
            override fun prev() { Handler(Looper.getMainLooper()).post { if (hasPrevious()) playPrevious() } }
            override fun playVideo(id: String) {
                Handler(Looper.getMainLooper()).post {
                    var index = playlist?.indexOfFirst { it.id == id } ?: -1
                    if (index == -1) {
                        playlist = java.util.ArrayList(TvDataStore.playlist)
                        index = playlist?.indexOfFirst { it.id == id } ?: -1
                    }
                    if (index != -1 && !playlist.isNullOrEmpty()) {
                        currentIndex = index
                        playVideoItem(playlist!![index])
                    }
                }
            }
            override fun seekTo(positionMs: Long) {
                Handler(Looper.getMainLooper()).post { mediaPlayer?.time = positionMs }
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
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    playing = mediaPlayer?.isPlaying ?: false
                    position = mediaPlayer?.time ?: 0L
                    duration = mediaPlayer?.length ?: 0L
                    needsResume = isWaitingForResume
                    resumePos = currentVideo?.watchedPosition ?: 0L
                    locked = isLocked
                    muted = (mediaPlayer?.volume ?: 100) == 0
                    needsSpeed = isWaitingForSpeedChoice
                    currentSpeed = mediaPlayer?.rate ?: 1.0f
                    needsAudioShift = isWaitingForAudioShiftChoice
                    currentAudioShift = audioShiftMs
                } else {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post {
                        try {
                            playing = mediaPlayer?.isPlaying ?: false
                            position = mediaPlayer?.time ?: 0L
                            duration = mediaPlayer?.length ?: 0L
                            needsResume = isWaitingForResume
                            resumePos = currentVideo?.watchedPosition ?: 0L
                            locked = isLocked
                            muted = (mediaPlayer?.volume ?: 100) == 0
                            needsSpeed = isWaitingForSpeedChoice
                            currentSpeed = mediaPlayer?.rate ?: 1.0f
                            needsAudioShift = isWaitingForAudioShiftChoice
                            currentAudioShift = audioShiftMs
                        } catch (e: Exception) {}
                        latch.countDown()
                    }
                    try { latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) {}
                }
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
                state.put("videoUrl", currentVideo?.url ?: videoUrlString ?: "")
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
                        if (action == "audio_track" || action == "remote_audio") {
                            isRemoteAudioEnabled = !isRemoteAudioEnabled
                            val msg = if (isRemoteAudioEnabled) "Remote Audio Enabled" else "Remote Audio Disabled"
                            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_SHORT).show()
                            updateAudioTrackButtonState()
                            scheduleMetadataHide()
                            return@post
                        }
                        val view = when (action) {
                            "mute" -> findViewById<View>(R.id.btnMute)
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
            pendingSeekPosition = position
            mediaPlayer?.play()
        } else {
            pendingSeekPosition = 0L
            mediaPlayer?.play()
        }
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

        val builder = android.app.AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener {
                isWaitingForAudioShiftChoice = false
                audioShiftDialog = null
            }
        audioShiftDialog = builder.create()
        audioShiftDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        audioShiftDialog?.show()
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
            mediaPlayer?.rate = speed
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
        val titleText = dialogView.findViewById<TextView>(R.id.dialog_title)
        val messageText = dialogView.findViewById<TextView>(R.id.dialog_message)
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
        mediaPlayer?.let { player ->
            val currentPos = player.time
            val duration = player.length
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
                if (hasNext()) {
                    isManualSkip = true
                    playNext()
                }
                showMetadataTemp(timeoutMs = 2000L)
                return true
            } else if (!isLocked && event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                if (hasPrevious()) {
                    isManualSkip = true
                    playPrevious()
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
                mediaPlayer?.let {
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
        lastFocusedTopBarView = findViewById(R.id.btnCast)
        lastFocusedMiddleRightView = findViewById(R.id.btnAudioTrack)
        lastFocusedControlsPillView = btnPlayPause
        lastFocusedUpperView = lastFocusedMiddleRightView

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

    override fun onStart() {
        super.onStart()
        try {
            if (mediaPlayer?.vlcVout?.areViewsAttached() != true) {
                mediaPlayer?.attachViews(videoLayout, null, false, false)
            }
        } catch (t: Throwable) {
            android.util.Log.e("PlayerActivity", "Error attaching views in onStart", t)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            mediaPlayer?.stop()
            if (mediaPlayer?.vlcVout?.areViewsAttached() == true) {
                mediaPlayer?.detachViews()
            }
        } catch (t: Throwable) {
            android.util.Log.e("PlayerActivity", "Error in onStop", t)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            mediaPlayer?.pause()
            saveFinalProgress()
        } catch (t: Throwable) {
            android.util.Log.e("PlayerActivity", "Error in onPause", t)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            currentVideo?.let { video ->
                if (video.watchedPosition <= 1000 || (mediaPlayer?.time ?: 0L) > 0L) {
                    mediaPlayer?.play()
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("PlayerActivity", "Error in onResume", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TvRemoteServer.playerController = null
        hideHandler.removeCallbacks(hideRunnable)
        progressHandler.removeCallbacks(progressRunnable)
        antiScreenSaverHandler.removeCallbacks(antiScreenSaverRunnable)
        try {
            saveFinalProgress()
            mediaPlayer?.stop()
            if (mediaPlayer?.vlcVout?.areViewsAttached() == true) {
                mediaPlayer?.detachViews()
            }
            mediaPlayer?.release()
            mediaPlayer = null
            libVLC?.release()
            libVLC = null
        } catch (t: Throwable) {
            android.util.Log.e("PlayerActivity", "Error releasing player in onDestroy", t)
        }
    }

    private fun showAudioTrackDialog() {
        mediaPlayer?.let { player ->
            val tracks = player.audioTracks
            if (tracks.isNullOrEmpty()) {
                Toast.makeText(this, "No audio tracks available", Toast.LENGTH_SHORT).show()
                return
            }

            val currentTrackId = player.audioTrack
            val trackNames = tracks.map { track ->
                val activeStr = if (track.id == currentTrackId) " [Active]" else ""
                "${track.name}$activeStr"
            }.toTypedArray()

            android.app.AlertDialog.Builder(this)
                .setTitle("Select Audio Track")
                .setItems(trackNames) { _, which ->
                    val selectedTrack = tracks[which]
                    player.audioTrack = selectedTrack.id
                    Toast.makeText(this, "Selected: ${selectedTrack.name}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun showSubtitleDialog() {
        mediaPlayer?.let { player ->
            val tracks = player.spuTracks
            val currentTrackId = player.spuTrack
            val trackIds = mutableListOf<Int>()
            val trackNames = mutableListOf<String>()

            trackNames.add("Off${if (currentTrackId == -1) " [Active]" else ""}")
            trackIds.add(-1)

            if (!tracks.isNullOrEmpty()) {
                for (track in tracks) {
                    if (track.id != -1) {
                        trackIds.add(track.id)
                        val activeStr = if (track.id == currentTrackId) " [Active]" else ""
                        trackNames.add("${track.name}$activeStr")
                    }
                }
            }

            android.app.AlertDialog.Builder(this)
                .setTitle("Select Subtitles")
                .setItems(trackNames.toTypedArray()) { _, which ->
                    val selectedId = trackIds[which]
                    player.spuTrack = selectedId
                    Toast.makeText(this, "Selected Subtitles: ${trackNames[which]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun showVideoQualityDialog() {
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val currentQuality = appPrefs.getString("video_quality_mode", "peak") ?: "peak"
        val qualityOptions = arrayOf(
            "Peak Quality (RV32 32-Bit Color + Full HW Direct Rendering)",
            "Balanced Quality (RV32 32-Bit + Standard HW)",
            "Compatibility Mode"
        )
        val qualityValues = arrayOf("peak", "balanced", "powersave")
        val currentIndex = when (currentQuality) {
            "balanced" -> 1
            "powersave" -> 2
            else -> 0
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Video Rendering Quality")
            .setSingleChoiceItems(qualityOptions, currentIndex) { dialog, which ->
                dialog.dismiss()
                val selectedValue = qualityValues[which]
                if (selectedValue != currentQuality) {
                    appPrefs.edit().putString("video_quality_mode", selectedValue).apply()
                    Toast.makeText(this, "Quality set to ${qualityOptions[which].substringBefore(" (")}. Reloading player...", Toast.LENGTH_SHORT).show()
                    val curTime = mediaPlayer?.time ?: 0L
                    initializeVlcPlayer(curTime)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
