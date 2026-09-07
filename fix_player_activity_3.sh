cat tv/src/main/java/com/example/tv/PlayerActivity.kt | awk '
/lastFocusedTopBarView = findViewById\(R\.id\.btnCast\)/ {
    inInit = 1
    print $0
    next
}
/private fun showMetadataTemp/ {
    inInit = 0
}
{
    if (inInit == 1) {
        if ($0 ~ /findViewById<android\.view\.ViewGroup>\(R\.id\.topBar\)\?\.let/) {
            print "        findViewById<android.view.ViewGroup>(R.id.topBar)?.let { group ->"
        } else if ($0 ~ /findViewById<android\.view\.ViewGroup>\(R\.id\.middleRightBar\)\?\.let/) {
            print "        findViewById<android.view.ViewGroup>(R.id.middleRightBar)?.let { group ->"
        } else if ($0 ~ /\(btnPlayPause\.parent as\? android\.view\.ViewGroup\)\?\.let/) {
            print "        (btnPlayPause.parent as? android.view.ViewGroup)?.let { group ->"
        } else {
            print $0
        }
    } else {
        print $0
    }
}' > tmp.kt
mv tmp.kt tv/src/main/java/com/example/tv/PlayerActivity.kt
