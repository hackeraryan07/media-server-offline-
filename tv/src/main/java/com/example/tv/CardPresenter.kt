package com.example.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide

class CardPresenter : Presenter() {
    private val CARD_WIDTH = 320
    private val CARD_HEIGHT = 180

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = object : ImageCardView(parent.context) {
            override fun setSelected(selected: Boolean) {
                updateCardBackgroundColor(this, selected)
                val titleTextView = findViewById<android.widget.TextView>(androidx.leanback.R.id.title_text)
                titleTextView?.let {
                    it.minLines = 2
                    if (selected) {
                        it.setSingleLine(true)
                        it.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                        it.marqueeRepeatLimit = -1
                        it.isSelected = true
                    } else {
                        it.setSingleLine(false)
                        it.maxLines = 2
                        it.ellipsize = android.text.TextUtils.TruncateAt.END
                        it.isSelected = false
                    }
                }
                super.setSelected(selected)
            }
        }
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        updateCardBackgroundColor(cardView, false)
        return ViewHolder(cardView)
    }

    private fun updateCardBackgroundColor(view: ImageCardView, selected: Boolean) {
        val bgCol = if (selected) Color.parseColor("#4F46E5") else Color.parseColor("#1F2937")
        view.setBackgroundColor(bgCol)
        view.setInfoAreaBackgroundColor(bgCol)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val video = item as TvVideo
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = video.title
        cardView.contentText = "Format: MP4 Video"

        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)

        if (!video.thumbnailUrl.isNullOrEmpty()) {
            Glide.with(cardView.context)
                .load(video.thumbnailUrl)
                .centerCrop()
                .into(cardView.mainImageView)
        } else {
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1E3A8A"), Color.parseColor("#3B82F6"))
            )
            gradient.cornerRadius = 8f
            cardView.mainImage = gradient
        }

        // Programmatically overlay a horizontal progress bar at the bottom of the thumbnail image
        var progressBar = cardView.findViewWithTag<android.widget.ProgressBar>("card_progress_bar")
        if (progressBar == null) {
            progressBar = android.widget.ProgressBar(cardView.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "card_progress_bar"
                max = 100
                minimumHeight = 0

                // Create completely rectangular shapes with no corner radius
                val backgroundShape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#44FFFFFF"))
                    cornerRadius = 0f
                }
                val progressShape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#E11D48")) // YouTube Red
                    cornerRadius = 0f
                }
                val progressClip = android.graphics.drawable.ClipDrawable(
                    progressShape,
                    android.view.Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL
                )
                val layerDrawable = android.graphics.drawable.LayerDrawable(arrayOf(backgroundShape, progressClip)).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
                
                progressDrawable = layerDrawable
                
                val params = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    4 // height in pixels (halved from 8)
                ).apply {
                    gravity = android.view.Gravity.TOP
                    topMargin = CARD_HEIGHT - 4
                }
                layoutParams = params
            }
            cardView.addView(progressBar)
        }

        val hasProgress = video.totalDuration > 0 && video.id != "err"
        if (hasProgress) {
            val pct = (video.watchedPosition * 100 / video.totalDuration).toInt()
            if (pct > 0) {
                progressBar.alpha = 1f
                progressBar.progress = pct.coerceIn(0, 100)
            } else {
                progressBar.alpha = 0f
            }
        } else {
            progressBar.alpha = 0f
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        Glide.with(cardView.context).clear(cardView.mainImageView)
        cardView.mainImage = null
    }
}
