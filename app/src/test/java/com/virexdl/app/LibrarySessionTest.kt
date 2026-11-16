package com.virexdl.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.widget.TextView
import android.widget.ImageButton
import android.widget.FrameLayout

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibrarySessionTest {
    private fun song(id: String) = Song(id, "Track $id", "Virex Studio", "", 180, listOf(Stream("320kbps", "https://example.com/$id.mp4")))
    @Test fun likesPersistAndUnlikeRemovesOnlyThatSong() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        val store = LibraryStore(app)
        store.toggleLike(song("a")); store.toggleLike(song("b"))
        assertEquals(listOf("b", "a"), LibraryStore(app).state.value.liked.map(Song::id))
        store.toggleLike(song("a"))
        assertEquals(listOf("b"), LibraryStore(app).state.value.liked.map(Song::id))
    }
    @Test fun recentOrderingAndPlayCountsSurviveReload() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        val store = LibraryStore(app)
        store.touchRecent(song("a"), 1); store.countListen(song("a")); store.countListen(song("a"))
        store.touchRecent(song("b"), 2); store.countListen(song("b"))
        store.touchRecent(song("b"), 3) // Opening/resuming does not itself increase play counts.
        val loaded = LibraryStore(app).state.value
        assertEquals(listOf("b", "a"), collectionSongs(loaded, "@recent")!!.second.map(Song::id))
        assertEquals(listOf("a", "b"), collectionSongs(loaded, "@most")!!.second.map(Song::id))
        assertEquals(1, loaded.listening.first { it.song.id == "b" }.plays)
        assertEquals(30000L, listenThreshold(180000))
        assertEquals(10000L, listenThreshold(20000))
    }
    @Test fun sessionRetainsQueuePositionAndModes() {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        val saved = PlaybackSnapshot(listOf(song("a"), song("b")), 1, 73456, true, 2, true, "Virex Radio", "session-test")
        PlaybackSnapshotStore(app).save(saved)
        val loaded = PlaybackSnapshotStore(app).load()!!
        assertEquals(listOf("a", "b"), loaded.songs.map(Song::id))
        assertEquals(1, loaded.index); assertEquals(73456L, loaded.positionMs)
        assertTrue(loaded.shuffle); assertEquals(2, loaded.repeat); assertEquals("session-test", loaded.radioSession)
    }
    @Test fun unavailableSavedSongDoesNotSeekIntoTheWrongReplacement() {
        val missing = song("missing").copy(streams = emptyList())
        val saved = PlaybackSnapshot(listOf(missing, song("b")), 0, 70000, false, 0, false, "Queue", null)
        val restored = saved.playable(Library())!!
        assertEquals("b", restored.songs[restored.index].id)
        assertEquals(0L, restored.positionMs)
    }

}
