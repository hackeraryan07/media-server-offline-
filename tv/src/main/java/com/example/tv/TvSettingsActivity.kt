package com.example.tv

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch

class TvSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val switchPrevent = findViewById<Switch>(R.id.switch_prevent_screensaver)
        val radioGroupPlayer = findViewById<RadioGroup>(R.id.radio_group_player_default)
        val radioPlayerInternal = findViewById<RadioButton>(R.id.radio_player_internal)
        val radioPlayerExternal = findViewById<RadioButton>(R.id.radio_player_external)
        val radioPlayerAsk = findViewById<RadioButton>(R.id.radio_player_ask)

        val radioGroupResume = findViewById<RadioGroup>(R.id.radio_group_resume_default)
        val radioStartOver = findViewById<RadioButton>(R.id.radio_start_over)
        val radioContinue = findViewById<RadioButton>(R.id.radio_continue)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isPreventEnabled = prefs.getBoolean("prevent_screensaver", false)
        val defaultPlayer = prefs.getString("default_player", "internal") ?: "internal"
        val resumeDefault = prefs.getString("resume_default", "start_over") ?: "start_over"

        switchPrevent.isChecked = isPreventEnabled
        switchPrevent.requestFocus()

        switchPrevent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("prevent_screensaver", isChecked).apply()
        }

        // Configure player selection
        when (defaultPlayer) {
            "external" -> radioPlayerExternal.isChecked = true
            "ask" -> radioPlayerAsk.isChecked = true
            else -> radioPlayerInternal.isChecked = true
        }

        radioGroupPlayer.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radio_player_external -> "external"
                R.id.radio_player_ask -> "ask"
                else -> "internal"
            }
            prefs.edit().putString("default_player", value).apply()
        }

        // Configure auto-resume action
        if (resumeDefault == "continue") {
            radioContinue.isChecked = true
        } else {
            radioStartOver.isChecked = true
        }

        radioGroupResume.setOnCheckedChangeListener { _, checkedId ->
            val value = if (checkedId == R.id.radio_continue) "continue" else "start_over"
            prefs.edit().putString("resume_default", value).apply()
        }

        // Configure video rendering quality
        val radioGroupQuality = findViewById<RadioGroup>(R.id.radio_group_video_quality)
        val radioQualityPeak = findViewById<RadioButton>(R.id.radio_quality_peak)
        val radioQualityBalanced = findViewById<RadioButton>(R.id.radio_quality_balanced)
        val radioQualityPowersave = findViewById<RadioButton>(R.id.radio_quality_powersave)
        val videoQuality = prefs.getString("video_quality_mode", "peak") ?: "peak"

        when (videoQuality) {
            "balanced" -> radioQualityBalanced.isChecked = true
            "powersave" -> radioQualityPowersave.isChecked = true
            else -> radioQualityPeak.isChecked = true
        }

        radioGroupQuality.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radio_quality_balanced -> "balanced"
                R.id.radio_quality_powersave -> "powersave"
                else -> "peak"
            }
            prefs.edit().putString("video_quality_mode", value).apply()
        }
    }
}
