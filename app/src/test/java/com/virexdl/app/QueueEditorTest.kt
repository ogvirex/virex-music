package com.virexdl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class QueueEditorTest {
    @get:Rule val ui = createComposeRule()
    private fun song(id: String) = Song(id, "Track $id", "Artist", "", 200, emptyList())
    @Test fun upcomingTracksCanBeDraggedAndRemoved() {
        var songs by mutableStateOf(listOf(song("a"), song("b"), song("c"), song("d")))
        var moveCount = 0
        var keys by mutableStateOf(listOf("a", "b", "c", "d"))
        ui.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) {
            QueueEditor(songs, keys, 0, {}, { from, to ->
                moveCount++
                songs = songs.toMutableList().apply { add(to, removeAt(from)) }
                keys = keys.toMutableList().apply { add(to, removeAt(from)) }
            }, { index -> songs = songs.filterIndexed { i, _ -> i != index }; keys = keys.filterIndexed { i, _ -> i != index } }, Modifier.fillMaxSize())
        } } }
        val handle = ui.onNodeWithTag("queue-handle-b", useUnmergedTree = true)
        handle.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(700)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        ui.waitForIdle()
        handle.performTouchInput { moveBy(Offset(0f, 75f)); moveBy(Offset(0f, 1f)) }
        ui.runOnIdle { assertEquals(listOf("a", "b", "c", "d"), songs.map(Song::id)); assertEquals(0, moveCount) }
        handle.performTouchInput { moveBy(Offset(0f, 1f)); up() }
        ui.runOnIdle { assertEquals(listOf("a", "c", "b", "d"), songs.map(Song::id)); assertEquals(1, moveCount) }
        ui.onNodeWithContentDescription("Remove Track b from queue").performClick()
        ui.runOnIdle { assertEquals(listOf("a", "c", "d"), songs.map(Song::id)) }
        ui.onNodeWithText("Track a").assertIsDisplayed()
    }
}
