import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable

@Composable
fun Test() {
    val state = rememberPagerState { 3 }
}
