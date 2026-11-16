package com.virexdl.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A paused, restorable session. Opening the app never starts sound automatically. */
data class PlaybackSnapshot(val songs: List<Song>, val index: Int, val positionMs: Long, val shuffle: Boolean,
    val repeat: Int, val radio: Boolean, val label: String, val radioSession: String?) {
    fun json(): JSONObject = JSONObject().put("songs", JSONArray().apply { songs.forEach { put(it.json()) } })
        .put("index", index).put("position", positionMs).put("shuffle", shuffle).put("repeat", repeat)
        .put("radio", radio).put("label", label).put("radioSession", radioSession)
    fun playable(library: Library): PlaybackSnapshot? {
        val selected = songs.getOrNull(index)?.id
        val valid = songs.filter { s -> s.url(library.settings.hq) != null || library.downloads.any { it.song.id == s.id && java.io.File(it.path).isFile } }
        if (valid.isEmpty()) return null
        val found = valid.indexOfFirst { it.id == selected }
        return copy(songs = valid, index = if (found >= 0) found else index.coerceIn(0, valid.lastIndex), positionMs = if (found >= 0) positionMs.coerceAtLeast(0) else 0)
    }
    companion object {
        fun parse(j: JSONObject) = PlaybackSnapshot(j.getJSONArray("songs").objects().map(Song::parse), j.optInt("index"), j.optLong("position"), j.optBoolean("shuffle"), j.optInt("repeat"), j.optBoolean("radio"), j.optString("label", "Your queue"), j.optString("radioSession").takeUnless { it.isBlank() || it == "null" })
    }
}
class PlaybackSnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_session", Context.MODE_PRIVATE)
    @Synchronized fun save(snapshot: PlaybackSnapshot?) {
        if (snapshot == null) prefs.edit().remove("session").commit()
        else prefs.edit().putString("session", snapshot.json().toString()).commit()
    }
    fun load(): PlaybackSnapshot? = runCatching { prefs.getString("session", null)?.let { PlaybackSnapshot.parse(JSONObject(it)) } }.getOrNull()
}
fun listenThreshold(durationMs: Long): Long = minOf(30_000L, maxOf(5_000L, durationMs / 2))
