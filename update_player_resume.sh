cat tv/src/main/java/com/example/tv/PlayerActivity.kt | awk '
/btnStartOver.requestFocus\(\)/ {
    print "        val prefs = getSharedPreferences(\"app_settings\", Context.MODE_PRIVATE)"
    print "        val resumeDefault = prefs.getString(\"resume_default\", \"start_over\") ?: \"start_over\""
    print ""
    print "        if (resumeDefault == \"continue\") {"
    print "            btnContinue.requestFocus()"
    print "        } else {"
    print "            btnStartOver.requestFocus()"
    print "        }"
    print ""
    print "        resumeTimer = object : android.os.CountDownTimer(10000, 1000) {"
    print "            override fun onTick(millisUntilFinished: Long) {"
    print "                val secondsLeft = (millisUntilFinished / 1000) + 1"
    print "                if (resumeDefault == \"continue\") {"
    print "                    btnContinue.text = \"Continue ($secondsLeft)\""
    print "                    btnStartOver.text = \"Start Over\""
    print "                } else {"
    print "                    btnStartOver.text = \"Start Over ($secondsLeft)\""
    print "                    btnContinue.text = \"Continue\""
    print "                }"
    print "            }"
    print ""
    print "            override fun onFinish() {"
    print "                if (resumeDialog?.isShowing == true) {"
    print "                    if (resumeDefault == \"continue\") {"
    print "                        handleResumeChoice(\"continue\", video.watchedPosition)"
    print "                    } else {"
    print "                        handleResumeChoice(\"start_over\", 0L)"
    print "                    }"
    print "                    resumeDialog?.dismiss()"
    print "                }"
    print "            }"
    print "        }.start()"
    print "    }"
    
    # Skip the existing resumeTimer block
    getline; getline; getline; getline; getline; getline; getline; getline; getline; getline; getline; getline; getline; getline;
    next
}
{ print $0 }
' > tmp_player.kt
mv tmp_player.kt tv/src/main/java/com/example/tv/PlayerActivity.kt
