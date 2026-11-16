package com.virexdl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.WorkInfo
import kotlinx.coroutines.cancel
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
class PlaylistDownloadTest {
    @get:Rule val ui = createComposeRule()
    @Test fun playlistButtonQueuesUnsavedTracksOnceAndShowsCompletion() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app, PlaybackService::class.java), null)
        app.network.online.value = false
        val saved = Song("saved", "Saved track", "Artist", "", 180, emptyList())
        val fresh = saved.copy(id = "fresh", title = "Fresh track")
        app.store.edit { it.copy(downloads = listOf(Download(saved, "/saved", "", 100)), mixes = listOf(Mix("test-mix", "Road trip", listOf(saved, fresh)))) }
        val vm = MusicViewModel(app)
        try {
            ui.setContent { VirexRoot(vm) }
            ui.onNodeWithText("Library", useUnmergedTree = true).performClick()
            ui.onNodeWithText("Road trip").performScrollTo().performClick()
            ui.onNodeWithText("1 of 2 saved offline").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Download all").performClick()
            ui.runOnIdle {
                val jobs = vm.workManager.getWorkInfosByTag("audio").get()
                assertEquals(1, jobs.size)
                assertTrue("song:fresh" in jobs.single().tags)
                vm.downloadPlaylist(Mix("repeat", "Repeated", listOf(saved, fresh, fresh)))
                assertEquals(1, vm.workManager.getWorkInfosByTag("audio").get().size)
            }
            app.store.edit { it.copy(downloads = it.downloads + Download(fresh, "/fresh", "", 100)) }
            ui.onNodeWithText("Saved offline").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        } finally { vm.viewModelScope.cancel() }
    }
}
