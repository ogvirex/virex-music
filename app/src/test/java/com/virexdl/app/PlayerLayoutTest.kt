package com.virexdl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerLayoutTest {
    @get:Rule val ui = createComposeRule()
    @Test fun playerIsOpaqueAndActionsStayOnSmallScreen() = checkPlayer(1f)
    @Test fun largerTextKeepsControlsVisible() = checkPlayer(1.3f)
    @Test @Config(qualifiers = "w320dp-h568dp-mdpi") fun compactScreenKeepsOfflineSavePinned() = checkPlayer(1.3f)
    @Test @Config(qualifiers = "w640dp-h360dp-mdpi") fun landscapeKeepsSavePinnedWhileControlsScroll() = checkPlayer(1.3f, true)
    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi") fun tabletPlayerControlsRemainAccessible() = checkPlayer(1f)
    @Test fun extraLargeTextRetainsAccessibleControls() = checkPlayer(2f, true)
    private fun checkPlayer(fontScale: Float, scrollControls: Boolean = false) {
        org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app, PlaybackService::class.java), null)
        val vm = MusicViewModel(app)
        val song = Song("layout-fixture", "A sound of your own", "Virex Studio", "", 240, emptyList())
        app.store.edit { it.copy(settings = it.settings.copy(visualizer = true)) }
        vm.current.value = song
        vm.duration.value = 240000
        vm.position.value = 62000
        vm.playing.value = true
        vm.home.value = emptyList()
        vm.homeLoading.value = false
        vm.lyrics.value = Lyrics(listOf(Lyric(0, "A saved lyric line")))
        try {
            ui.setContent { VirexRoot(vm) }
            ui.onNodeWithText(song.title).performClick()
            ui.onNodeWithTag("full-player").assertIsDisplayed()
            ui.onNodeWithTag("save-offline").assertIsDisplayed()
            val saveBefore = ui.onNodeWithTag("save-offline").fetchSemanticsNode().boundsInRoot
            val originalTitle = ui.onNodeWithTag("player-title").fetchSemanticsNode().boundsInRoot.top
            assertTrue(ui.onNodeWithTag("player-tab-1").fetchSemanticsNode().boundsInRoot.bottom < originalTitle)
            if (scrollControls) ui.onNodeWithTag("player-play").performScrollTo()
            ui.onNodeWithTag("player-play").assertIsDisplayed()
            assertEquals(saveBefore, ui.onNodeWithTag("save-offline").fetchSemanticsNode().boundsInRoot)
            val root = ui.onNodeWithTag("full-player").fetchSemanticsNode().boundsInRoot
            val titleTop = ui.onNodeWithTag("player-title").fetchSemanticsNode().boundsInRoot.top
            if (!scrollControls) assertTrue("Lyrics must be above the artwork/title", ui.onNodeWithTag("player-tab-1").fetchSemanticsNode().boundsInRoot.bottom < titleTop)
            if (scrollControls) ui.onNodeWithContentDescription("Like song").performScrollTo()
            ui.onNodeWithContentDescription("Like song").performClick()
            if (scrollControls) ui.onNodeWithTag("player-play").performScrollTo()
            assertTrue(vm.library.value.liked.any { it.id == song.id })
            for (tag in listOf("save-offline", "player-play", "player-tab-0", "player-tab-1", "player-tab-2")) {
                val bounds = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("$tag must fit inside the player", bounds.top >= root.top && bounds.bottom <= root.bottom)
                assertTrue("$tag must retain a 48px touch height", bounds.height >= 48f)
            }
            val visualizer = ui.onNodeWithTag("audio-visualizer").fetchSemanticsNode().boundsInRoot
            assertTrue("Visualizer must remain compact", visualizer.height <= 24f)
            ui.mainClock.advanceTimeBy(500)
            ui.waitForIdle()
            ui.runOnIdle {
                val type = Class.forName("android.view.WindowManagerGlobal")
                val manager = type.getDeclaredMethod("getInstance").invoke(null)
                val field = type.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val views = field.get(manager) as List<android.view.View>
                val decor = views.last()
                val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(bitmap))
                decor.draw(android.graphics.Canvas(bitmap))
                File("build/player-layout-360x640-$fontScale.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                assertEquals("Player background must be opaque", 255, android.graphics.Color.alpha(bitmap.getPixel(1, 1)))
            }
            ui.onNodeWithTag("player-tab-1").performClick()
            ui.onNodeWithText("A saved lyric line").assertIsDisplayed()
            ui.onNodeWithTag("player-tab-2").performClick()
            ui.onNodeWithTag("player-tab-0").performClick()
            ui.onNodeWithTag("player-dismiss-handle").performTouchInput { swipeDown(startY = centerY, endY = centerY + 130f, durationMillis = 400) }
            ui.onNodeWithTag("full-player").assertDoesNotExist()
            assertTrue("Dismissing the player must not pause playback", vm.playing.value)
            ui.onNodeWithText("Listen now").assertIsDisplayed()
        } finally { vm.viewModelScope.cancel() }
    }
}
