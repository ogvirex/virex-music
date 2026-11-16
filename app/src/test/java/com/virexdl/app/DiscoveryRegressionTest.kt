package com.virexdl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DiscoveryRegressionTest {
    @get:Rule val ui = createComposeRule()
    private fun model(): MusicViewModel {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app, PlaybackService::class.java), null)
        app.network.online.value = false
        return MusicViewModel(app)
    }
    @Test fun offlineMessageOpensSavedLibrary() {
        val vm = model()
        try {
            ui.setContent { VirexRoot(vm) }
            ui.onNodeWithText("You're offline").assertIsDisplayed()
            ui.onNodeWithText("Signal lost").assertDoesNotExist()
            ui.onNodeWithText("Open offline library").performClick()
            ui.onNodeWithText("Offline vault").assertIsDisplayed()
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun settingsHaveNoBackupOrImplementationCopy() {
        val vm = model()
        try {
            ui.setContent { VirexRoot(vm) }
            ui.onNodeWithContentDescription("Settings").performClick()
            ui.onNodeWithText("High quality").assertIsDisplayed()
            listOf("Save backup", "Restore", "JioSaavn", "LRCLIB", "Kotlin", "API", "JSON", "Add home-screen player").forEach { ui.onNodeWithText(it, substring = true).assertDoesNotExist() }
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun brandingAndHomeShortcutsAreClean() {
        val vm = model()
        try {
            val app = ApplicationProvider.getApplicationContext<VirexApp>()
            assertEquals("Virex Music", app.applicationInfo.loadLabel(app.packageManager).toString())
            assertFalse(app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_RECEIVERS).receivers.orEmpty().any { it.name.endsWith("MusicWidget") })
            ui.setContent { VirexRoot(vm) }
            ui.onNodeWithText("Virex").assertIsDisplayed()
            listOf("Liked songs", "Recently played", "Most played").forEach { ui.onNodeWithText(it, substring = true).assertDoesNotExist() }
        } finally { vm.viewModelScope.cancel() }
    }
    @Test fun launcherForegroundFitsSafeZone() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        val icon = app.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        val bitmap = android.graphics.Bitmap.createBitmap(108, 108, android.graphics.Bitmap.Config.ARGB_8888)
        icon.foreground.setBounds(0, 0, 108, 108)
        icon.foreground.draw(android.graphics.Canvas(bitmap))
        val points = (0 until 108).flatMap { y -> (0 until 108).mapNotNull { x ->
            val c = bitmap.getPixel(x, y)
            if (android.graphics.Color.green(c) > 70 && android.graphics.Color.green(c) > android.graphics.Color.red(c) / 2) x to y else null
        } }
        assertTrue(points.isNotEmpty())
        assertTrue(points.minOf { it.first } >= 20 && points.maxOf { it.first } <= 88)
        assertTrue(points.minOf { it.second } >= 20 && points.maxOf { it.second } <= 88)
    }
}
