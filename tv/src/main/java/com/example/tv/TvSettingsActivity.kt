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
        val radioGroupResume = findViewById<RadioGroup>(R.id.radio_group_resume_default)
        val radioStartOver = findViewById<RadioButton>(R.id.radio_start_over)
        val radioContinue = findViewById<RadioButton>(R.id.radio_continue)
        
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isPreventEnabled = prefs.getBoolean("prevent_screensaver", false)
        val resumeDefault = prefs.getString("resume_default", "start_over")
        
        switchPrevent.isChecked = isPreventEnabled
        switchPrevent.requestFocus()
        
        switchPrevent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("prevent_screensaver", isChecked).apply()
        }

        if (resumeDefault == "continue") {
            radioContinue.isChecked = true
        } else {
            radioStartOver.isChecked = true
        }

        radioGroupResume.setOnCheckedChangeListener { _, checkedId ->
            val value = if (checkedId == R.id.radio_continue) "continue" else "start_over"
            prefs.edit().putString("resume_default", value).apply()
        }
    }
}
