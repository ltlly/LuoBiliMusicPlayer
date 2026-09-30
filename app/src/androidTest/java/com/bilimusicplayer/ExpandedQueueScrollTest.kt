package com.bilimusicplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the unfolded device with a saved real queue; no synthetic songs or data clearing. */
@RunWith(AndroidJUnit4::class)
class ExpandedQueueScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun largeQueueSurvivesFastInlineAndSheetScrolling() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.onActivity { BiliMusicApplication.musicPlayerController.pause() }
        compose.waitUntil(5000) { !BiliMusicApplication.musicPlayerController.playbackState.value.isPlaying }
        compose.onNodeWithTag("mini_player").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("play_queue_inline").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("play_queue_inline").assertIsDisplayed()
        repeat(20) {
            compose.onNodeWithTag("play_queue_inline").performTouchInput { swipeUp(durationMillis = 45) }
        }
        repeat(20) {
            compose.onNodeWithTag("play_queue_inline").performTouchInput { swipeDown(durationMillis = 45) }
        }
        compose.onNodeWithContentDescription("播放队列").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("play_queue_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(20) {
            compose.onNodeWithTag("play_queue_sheet").performTouchInput { swipeUp(durationMillis = 45) }
        }
        compose.onNodeWithTag("play_queue_sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithTag("play_queue_inline").assertIsDisplayed()
    }
}
