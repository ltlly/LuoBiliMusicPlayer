package com.bilimusicplayer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bilimusicplayer.network.bilibili.favorite.FavoriteMedia
import com.bilimusicplayer.ui.components.PlaybackSeekBar
import com.bilimusicplayer.ui.screens.MediaItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rowPlaysFromCoverTitleAndWhitespaceWhileSelectionStillSelects() {
        var plays = 0
        var selections = 0
        var selection by mutableStateOf(false)
        val media = FavoriteMedia(1, 2, "整行点播测试", "", "BV_test", duration = 180, intro = "", ctime = 0, pubtime = 0)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(600.dp)) { MediaItem(media, isSelectionMode = selection, onClick = { selections++ }, onPlayClick = { plays++ }) }
            }
        }
        compose.onNodeWithText("整行点播测试").performClick()
        compose.onNodeWithText("整行点播测试").performTouchInput { click(Offset(30f, centerY)) }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.runOnIdle { assertEquals(3, plays); selection = true }
        compose.onNodeWithText("整行点播测试").performClick()
        compose.runOnIdle { assertEquals(3, plays); assertEquals(1, selections) }
    }
    @Test fun pausedProgressCanDragAndTapAndKeepsTheRequestedPosition() {
        var position by mutableStateOf(1000L)
        var seeks = 0
        compose.setContent {
            MaterialTheme { Box(Modifier.width(500.dp)) {
                PlaybackSeekBar(position, 200_000, false, "probe", { position = it; seeks++ })
            } }
        }
        compose.onNodeWithTag("player_seek").performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.65f, centerY), 600)
        }
        compose.runOnIdle { assertEquals(1, seeks); assertTrue("拖动后必须跳转", position in 120_000..145_000) }
        compose.onNodeWithTag("player_seek").performTouchInput { click(Offset(width * 0.3f, centerY)) }
        compose.runOnIdle { assertEquals(2, seeks); assertTrue(position in 50_000..70_000) }
    }
}
