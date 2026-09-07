sed -i 's/topBar?.let/findViewById<android.view.ViewGroup>(R.id.topBar)?.let/g' tv/src/main/java/com/example/tv/PlayerActivity.kt
sed -i 's/middleRightBar?.let/findViewById<android.view.ViewGroup>(R.id.middleRightBar)?.let/g' tv/src/main/java/com/example/tv/PlayerActivity.kt
sed -i 's/controlsPillContainer?.let/(btnPlayPause.parent as? android.view.ViewGroup)?.let/g' tv/src/main/java/com/example/tv/PlayerActivity.kt
