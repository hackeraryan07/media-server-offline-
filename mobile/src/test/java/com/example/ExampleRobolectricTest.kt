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
      LocalVideoServer.SharedVideo("1", "Science Documentary - Ep 100", "", 100L, isLocal = true, folder = "Local"),
      LocalVideoServer.SharedVideo("2", "Science Documentary - Ep 101", "", 100L, isLocal = true, folder = "Local")
    )

    // When AI decides a creative title
    val creativeName = AiHelper.cleanAndValidatePlaylistName("Science Journey", "all science episodes in ascending order", videos)
    assertEquals("Science Journey", creativeName)

    // When AI echoes the prompt
    val fallbackName = AiHelper.cleanAndValidatePlaylistName("all science episodes in ascending order", "all science episodes in ascending order", videos)
    assertNotEquals("all science episodes in ascending order", fallbackName)
    assertTrue(fallbackName.contains("Science", ignoreCase = true))

    // When AI returns generic or blank name
    val genericName = AiHelper.cleanAndValidatePlaylistName("", "comedy shows", emptyList())
    assertEquals("Comedy Shows Collection", genericName)
  }

  @Test
  fun `extractCountFromPrompt accurately extracts user specified counts`() {
    assertEquals(200, AiHelper.extractCountFromPrompt("all 200 science episodes in ascending order"))
    assertEquals(215, AiHelper.extractCountFromPrompt("arrange 215 episodes chronologically"))
    assertEquals(150, AiHelper.extractCountFromPrompt("total of 150 videos in order"))
    assertEquals(250, AiHelper.extractCountFromPrompt("there are 250 eps of documentary"))
    assertEquals(null, AiHelper.extractCountFromPrompt("arrange comedy episodes"))
  }

  @Test
  fun `extractEpisodeNumber and naturalOrderComparator sort 200+ episodes accurately`() {
    assertEquals(1.0, AiHelper.extractEpisodeNumber("Show - Episode 1.mp4")!!, 0.001)
    assertEquals(10.0, AiHelper.extractEpisodeNumber("Show - Ep 10 [1080p].mkv")!!, 0.001)
    assertEquals(105.0, AiHelper.extractEpisodeNumber("Show S01E105.mp4")!!, 0.001)
    assertEquals(200.0, AiHelper.extractEpisodeNumber("Show - 200.mkv")!!, 0.001)

    // Test that natural ordering puts Ep 2 before Ep 10 and Ep 100 before Ep 200
    val rawList = listOf(
      "Show - Ep 100.mp4",
      "Show - Ep 2.mp4",
      "Show - Ep 10.mp4",
      "Show - Ep 1.mp4",
      "Show - Ep 200.mp4",
      "Show - Ep 20.mp4"
    )

    val sortedList = rawList.sortedWith(AiHelper.naturalOrderComparator)
    val expected = listOf(
      "Show - Ep 1.mp4",
      "Show - Ep 2.mp4",
      "Show - Ep 10.mp4",
      "Show - Ep 20.mp4",
      "Show - Ep 100.mp4",
      "Show - Ep 200.mp4"
    )
    assertEquals(expected, sortedList)
  }
}
