sed -i 's/currentFocusView.parent == middleRightBar/currentFocusView.parent == findViewById<android.view.ViewGroup>(R.id.middleRightBar)/g' tv/src/main/java/com/example/tv/PlayerActivity.kt
sed -i 's/currentFocusView.parent == topBar/currentFocusView.parent == findViewById<android.view.ViewGroup>(R.id.topBar)/g' tv/src/main/java/com/example/tv/PlayerActivity.kt
sed -i 's/topBar?.let { group ->/findViewById<android.view.ViewGroup>(R.id.topBar)?.let { group ->/g' tv/src/main/java/com/example/tv/PlayerActivity.kt
sed -i 's/middleRightBar?.let { group ->/findViewById<android.view.ViewGroup>(R.id.middleRightBar)?.let { group ->/g' tv/src/main/java/com/example/tv/PlayerActivity.kt
