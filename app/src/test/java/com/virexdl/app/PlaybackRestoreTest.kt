package com.virexdl.app

import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.Player
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackRestoreTest {
    @Test fun serviceRestoresTheSessionPausedAtItsSavedPosition() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        fun song(id: String) = Song(id, "Track $id", "Artist", "", 180, listOf(Stream("320kbps", "https://example.com/$id.mp4")))
        app.sessions.save(PlaybackSnapshot(listOf(song("one"), song("two")), 1, 57000, true, Player.REPEAT_MODE_ONE, false, "Your queue", null))
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val field = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
            val player = field.get(controller.get()) as Player
            assertEquals(2, player.mediaItemCount)
            assertEquals("two", player.currentMediaItem!!.mediaId)
            assertEquals(57000L, player.currentPosition)
            assertTrue(player.shuffleModeEnabled)
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
            assertFalse(player.playWhenReady)
            assertFalse(player.isPlaying)
        } finally { controller.destroy() }
    }
    @Test fun playNextMoveAndRemoveChangeTheActualPlayerQueue() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app, PlaybackService::class.java), null)
        fun song(id: String) = Song(id, "Track $id", "Artist", "", 180, listOf(Stream("320kbps", "https://example.com/$id.mp4")))
        app.sessions.save(PlaybackSnapshot(listOf(song("a"), song("b"), song("c")), 0, 23000, true, 0, false, "Queue", null))
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val sessionField = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }
        val session = sessionField.get(service.get()) as androidx.media3.session.MediaSession
        val future = androidx.media3.session.MediaController.Builder(app, session.token).buildAsync()
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeat(20) { looper.idleFor(java.time.Duration.ofMillis(50)) }
        val remote = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val vm = MusicViewModel(app)
        MusicViewModel::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(vm, remote)
        val field = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
        val player = field.get(service.get()) as Player
        try {
            vm.queueSong(song("d"), true); looper.idle()
            assertEquals(listOf("a", "d", "b", "c"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertFalse(player.shuffleModeEnabled)
            vm.moveQueued(3, 1); looper.idle()
            assertEquals(listOf("a", "c", "d", "b"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            vm.removeQueued(2); looper.idle()
            assertEquals(listOf("a", "c", "b"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals(23000L, player.currentPosition)
        } finally {
            vm.viewModelScope.cancel()
            androidx.media3.session.MediaController.releaseFuture(future)
            service.destroy()
        }
    }

}
