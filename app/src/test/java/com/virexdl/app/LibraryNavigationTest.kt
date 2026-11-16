package com.virexdl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryNavigationTest {
    @get:Rule val ui = createComposeRule()
    @Test fun likedCollectionAndQueueActionsAreAccessible() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app, PlaybackService::class.java), null)
        val vm = MusicViewModel(app)
        val song = Song("navigation", "A favorite track", "Artist", "", 180, listOf(Stream("320kbps", "https://example.com/track.mp4")))
        app.store.toggleLike(song)
        try {
            ui.setContent { VirexRoot(vm) }
            ui.onNodeWithText("Library", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Liked songs · 1").performClick()
            ui.onNodeWithText("Liked songs").assertIsDisplayed()
            ui.onNodeWithText(song.title).performTouchInput { longClick() }
            ui.onNodeWithText("Play next").assertIsDisplayed()
            ui.onNodeWithText("Add to queue").assertIsDisplayed()
        } finally { vm.viewModelScope.cancel() }
    }
}
