package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.LocalVideoServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Mobile Stream Server", appName)
  }

  @Test
  fun `ai decides creative playlist name and avoids prompt echo`() {
    val videos = listOf(
      LocalVideoServer.SharedVideo("1", "Taarak Mehta Ka Ooltah Chashmah - Ep 100", "", 100L, isLocal = true, folder = "Local"),
      LocalVideoServer.SharedVideo("2", "Taarak Mehta Ka Ooltah Chashmah - Ep 101", "", 100L, isLocal = true, folder = "Local")
    )

    // When AI decides a creative title
    val creativeName = AiHelper.cleanAndValidatePlaylistName("Gokuldham Chronicles", "all taarak mehta episodes in ascending order", videos)
    assertEquals("Gokuldham Chronicles", creativeName)

    // When AI echoes the prompt
    val fallbackName = AiHelper.cleanAndValidatePlaylistName("all taarak mehta episodes in ascending order", "all taarak mehta episodes in ascending order", videos)
    assertNotEquals("all taarak mehta episodes in ascending order", fallbackName)
    assertTrue(fallbackName.contains("Taarak Mehta", ignoreCase = true))

    // When AI returns generic or blank name
    val genericName = AiHelper.cleanAndValidatePlaylistName("", "comedy shows", emptyList())
    assertEquals("Comedy Shows Collection", genericName)
  }
}
