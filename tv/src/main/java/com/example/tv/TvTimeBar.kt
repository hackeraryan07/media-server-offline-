package com.example.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View

class TvTimeBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4DFFFFFF") }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D3E3FD") } 
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    var duration: Long = 0
        set(value) {
            field = value
            invalidate()
        }
    var position: Long = 0
        set(value) {
            field = value
            invalidate()
        }

    private var isFocusedState = false
    private var animationProgress = 0f 
    private var animator: ValueAnimator? = null

    private val normalTrackHeight = 4.dpToPx()
    private val focusedTrackHeight = 16.dpToPx()
    private val normalThumbSize = 4.dpToPx()
    private val focusedThumbSize = 24.dpToPx()

    interface OnScrubListener {
        fun onScrubStart()
        fun onScrubMove(position: Long)
        fun onScrubStop(position: Long)
    }

    var listener: OnScrubListener? = null
    private var isScrubbing = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { _, hasFocus ->
            isFocusedState = hasFocus
            animateTo(if (hasFocus) 1f else 0f)
            if (!hasFocus && isScrubbing) {
                isScrubbing = false
                listener?.onScrubStop(position)
            }
        }
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animationProgress, target).apply {
            duration = 200
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentTrackHeight = normalTrackHeight + (focusedTrackHeight - normalTrackHeight) * animationProgress
        val centerY = height / 2f
        val startX = paddingLeft.toFloat() + focusedThumbSize / 2f
        val endX = width - paddingRight.toFloat() - focusedThumbSize / 2f
        val trackWidth = endX - startX
        
        if (trackWidth <= 0) return

        val trackRect = RectF(startX, centerY - currentTrackHeight / 2f, endX, centerY + currentTrackHeight / 2f)
        val cornerRadius = currentTrackHeight / 2f
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        val progressRatio = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
        val progressEndX = startX + trackWidth * progressRatio.coerceIn(0f, 1f)
        
        val progressRect = RectF(startX, centerY - currentTrackHeight / 2f, progressEndX, centerY + currentTrackHeight / 2f)
        canvas.drawRoundRect(progressRect, cornerRadius, cornerRadius, progressPaint)

        val currentThumbSize = normalThumbSize + (focusedThumbSize - normalThumbSize) * animationProgress
        val halfSize = currentThumbSize / 2f
        val thumbCornerRadius = (currentThumbSize / 2f) * animationProgress 
        val thumbRect = RectF(progressEndX - halfSize, centerY - halfSize, progressEndX + halfSize, centerY + halfSize)
        canvas.drawRoundRect(thumbRect, thumbCornerRadius, thumbCornerRadius, thumbPaint)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isFocusedState && duration > 0) {
            val step = duration / 100 // 1% per step
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isScrubbing) {
                        isScrubbing = true
                        listener?.onScrubStart()
                    }
                    position = (position - step).coerceAtLeast(0)
                    listener?.onScrubMove(position)
                    invalidate()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isScrubbing) {
                        isScrubbing = true
                        listener?.onScrubStart()
                    }
                    position = (position + step).coerceAtMost(duration)
                    listener?.onScrubMove(position)
                    invalidate()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isScrubbing && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            isScrubbing = false
            listener?.onScrubStop(position)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun Int.dpToPx(): Float = this * resources.displayMetrics.density
}
