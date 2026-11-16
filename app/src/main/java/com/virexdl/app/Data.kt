package com.virexdl.app

import android.app.Application
import android.content.Context
import android.text.Html
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class VirexApp : Application() {
    val store by lazy { LibraryStore(this) }
    val sessions by lazy { PlaybackSnapshotStore(this) }
    var api = MusicApi()
    val audioSession = MutableStateFlow(0)
    val network by lazy { ConnectivityWatcher(this) }
    val radioStatus = MutableStateFlow("")
}

fun String.clean(): String = Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()
fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

data class Stream(val quality: String, val url: String)
data class Song(val id: String, val title: String, val artist: String, val image: String,
    val duration: Long, val streams: List<Stream>) {
    fun url(hq: Boolean): String? = streams.firstOrNull { it.quality == if (hq) "320kbps" else "160kbps" }?.url
        ?: streams.lastOrNull()?.url
    fun json(): JSONObject = JSONObject().put("id", id).put("name", title)
        .put("artists", JSONObject().put("primary", JSONArray().put(JSONObject().put("name", artist))))
        .put("image", image).put("duration", duration)
        .put("downloadUrl", JSONArray().apply { streams.forEach { put(JSONObject().put("quality", it.quality).put("url", it.url)) } })
    companion object {
        fun parse(j: JSONObject): Song {
            val images = j.optJSONArray("image")
            val image = if (images != null) images.optJSONObject(images.length() - 1)?.optString("url").orEmpty() else j.optString("image")
            return Song(j.getString("id"), j.optString("name", "Untitled").clean(),
                j.optJSONObject("artists")?.optJSONArray("primary")?.objects()?.joinToString(", ") { it.optString("name").clean() }.orEmpty().ifBlank { "Unknown artist" },
                image, j.optLong("duration"), j.optJSONArray("downloadUrl")?.objects()?.map { Stream(it.optString("quality"), it.optString("url")) }.orEmpty().filter { it.url.startsWith("https://") })
        }
    }
}
data class Mix(val id: String = UUID.randomUUID().toString(), val name: String, val songs: List<Song> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("name", name).put("songs", JSONArray().apply { songs.forEach { put(it.json()) } })
}
data class Download(val song: Song, val path: String, val artwork: String, val bytes: Long)
data class Settings(val accent: Long = 0xFFB8FF3D, val hq: Boolean = true, val visualizer: Boolean = false, val crossfade: Boolean = false)
data class ListeningRecord(val song: Song, val lastPlayed: Long, val plays: Int)
data class Library(val mixes: List<Mix> = emptyList(), val downloads: List<Download> = emptyList(),
    val history: List<String> = emptyList(), val settings: Settings = Settings(), val liked: List<Song> = emptyList(), val listening: List<ListeningRecord> = emptyList())

class LibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("virex", Context.MODE_PRIVATE)
    val state = MutableStateFlow(load())
    private fun load(): Library = runCatching {
        val j = JSONObject(prefs.getString("library", "{}")!!)
        Library(parseMixes(j.optJSONArray("playlists")), j.optJSONArray("downloads")?.objects()?.mapNotNull {
            val f = File(context.filesDir, "offline/${it.getString("file")}")
            if (!f.isFile) null else Download(Song.parse(it.getJSONObject("song")), f.absolutePath, it.optString("artwork"), f.length())
        }.orEmpty(), j.optJSONArray("history")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), parseSettings(j), j.optJSONArray("liked")?.objects()?.map(Song::parse).orEmpty(),
            j.optJSONArray("listening")?.objects()?.map { ListeningRecord(Song.parse(it.getJSONObject("song")), it.optLong("last"), it.optInt("plays")) }.orEmpty())
    }.getOrDefault(Library())
    private fun parseSettings(j: JSONObject): Settings {
        val oldTheme = j.optString("theme", "#B8FF3D")
        val theme = mapOf("#C6F36A" to "#B8FF3D", "#69B5FF" to "#55B9FF", "#FF8EB8" to "#FF65BD", "#B39DFF" to "#B18AFF", "#FFC36B" to "#FFBF45", "#66DDCF" to "#42F5D7")[oldTheme.uppercase()] ?: oldTheme
        return Settings(runCatching { android.graphics.Color.parseColor(theme).toLong() and 0xFFFFFFFF }.getOrDefault(0xFFB8FF3D), j.optJSONObject("config")?.optBoolean("hq", true) ?: true, j.optJSONObject("config")?.optBoolean("visualizer", false) ?: false, j.optJSONObject("config")?.optBoolean("crossfade", false) ?: false)
    }
    private fun parseMixes(a: JSONArray?): List<Mix> = a?.objects()?.map { Mix(it.optString("id").ifBlank { UUID.randomUUID().toString() }, it.getString("name"), it.optJSONArray("songs")?.objects()?.map(Song::parse).orEmpty().distinctBy(Song::id)) }.orEmpty()
    @Synchronized fun edit(transform: (Library) -> Library) {
        val next = transform(state.value)
        val j = backupJson(next).put("liked", JSONArray().apply { next.liked.forEach { put(it.json()) } }).put("listening", JSONArray().apply { next.listening.forEach { put(JSONObject().put("song", it.song.json()).put("last", it.lastPlayed).put("plays", it.plays)) } }).put("history", JSONArray(next.history)).put("downloads", JSONArray().apply {
            next.downloads.forEach { put(JSONObject().put("song", it.song.json()).put("file", File(it.path).name).put("artwork", it.artwork)) }
        })
        check(prefs.edit().putString("library", j.toString()).commit()) { "Could not save library" }
        state.value = next
    }
    fun backupJson(l: Library = state.value): JSONObject = JSONObject().put("playlists", JSONArray().apply { l.mixes.forEach { put(it.json()) } })
        .put("theme", "#%06X".format(l.settings.accent and 0xFFFFFF)).put("config", JSONObject().put("hq", l.settings.hq).put("visualizer", l.settings.visualizer).put("crossfade", l.settings.crossfade)).put("version", 1)
    fun restore(raw: String) {
        require(raw.length <= 10_000_000) { "Backup is too large" }
        val j = JSONObject(raw)
        require(j.has("playlists")) { "Not a Virex backup" }
        val mixes = parseMixes(j.getJSONArray("playlists")) // Validate everything before replacing anything.
        val settings = parseSettings(j)
        edit { it.copy(mixes = mixes, settings = settings) }
    }
    fun toggleLike(song: Song) = edit { l -> l.copy(liked = if (l.liked.any { it.id == song.id }) l.liked.filterNot { it.id == song.id } else listOf(song) + l.liked) }
    fun touchRecent(song: Song, now: Long = System.currentTimeMillis()) = edit { l ->
        val old = l.listening.firstOrNull { it.song.id == song.id }
        l.copy(listening = (listOf(ListeningRecord(song, now, old?.plays ?: 0)) + l.listening.filterNot { it.song.id == song.id }).take(1000))
    }
    fun countListen(song: Song) = edit { l ->
        val old = l.listening.firstOrNull { it.song.id == song.id }
        val record = ListeningRecord(song, old?.lastPlayed ?: System.currentTimeMillis(), (old?.plays ?: 0) + 1)
        l.copy(listening = (listOf(record) + l.listening.filterNot { it.song.id == song.id }).sortedByDescending { it.lastPlayed }.take(1000))
    }
    fun remember(query: String) = edit { it.copy(history = (listOf(query) + it.history.filterNot { q -> q.equals(query, true) }).take(8)) }
    fun create(name: String, songs: List<Song> = emptyList()) = edit { it.copy(mixes = it.mixes + Mix(name = name.ifBlank { "My mix" }, songs = songs.distinctBy(Song::id))) }
    fun add(id: String, song: Song) = edit { l -> l.copy(mixes = l.mixes.map { if (it.id == id) it.copy(songs = (it.songs + song).distinctBy(Song::id)) else it }) }
    fun removeDownload(id: String) = edit { l ->
        l.downloads.filter { it.song.id == id }.forEach { File(it.path).delete(); if (it.artwork.isNotBlank()) File(it.artwork).delete() }
        l.copy(downloads = l.downloads.filterNot { it.song.id == id })
    }
}

class MusicApi(val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(), val base: String = "https://jiosaavn-api.n4noobff.workers.dev/api", val lyricsBase: String = "https://lrclib.net/api") {
    suspend fun json(url: String): String = suspendCancellableCoroutine { continuation ->
        require(url.startsWith("https://"))
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "VirexDL-Android/1.0").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    check(it.isSuccessful) { "Server returned ${it.code}" }
                    it.body?.string() ?: error("Empty response")
                } }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }
    suspend fun search(query: String, limit: Int = 20): List<Song> = JSONObject(json("$base/search/songs?query=${query.encoded()}&limit=$limit"))
        .getJSONObject("data").getJSONArray("results").objects().map(Song::parse).distinctBy(Song::id)
    suspend fun recommendations(id: String): List<Song> = JSONObject(json("$base/songs/${id.encoded()}/suggestions?limit=20"))
        .getJSONArray("data").objects().map(Song::parse)
    suspend fun lyrics(song: Song): Lyrics {
        val title = song.title.replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()
        val artists = song.artist.split(",").map(String::trim).filter(String::isNotBlank).take(3)
        suspend fun attempt(url: String): String? = try { kotlinx.coroutines.withTimeoutOrNull(8000) { json(url) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        var plainFallback: Lyrics? = null
        for (artist in artists) {
            val q = "track_name=${title.encoded()}&artist_name=${artist.encoded()}"
            val exact = attempt("$lyricsBase/get?$q&duration=${song.duration}")?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (exact != null) {
                val parsed = parseLyricsResult(exact)
                if (parsed.instrumental || parsed.lines.any { it.millis >= 0 }) return parsed
                if (parsed.lines.isNotEmpty()) plainFallback = parsed
            }
        }
        // Broad title search handles producer credits and multi-artist catalogue entries.
        val results = attempt("$lyricsBase/search?track_name=${title.encoded()}")?.let { runCatching { JSONArray(it).objects() }.getOrNull() }.orEmpty()
        val ranked = results.filter { kotlin.math.abs(it.optLong("duration") - song.duration) < 20 || song.duration == 0L }
            .sortedBy { item -> kotlin.math.abs(item.optLong("duration") - song.duration) + if (artists.any { item.optString("artistName").contains(it, true) }) 0 else 100 }
        for (item in ranked) {
            val parsed = parseLyricsResult(item)
            if (parsed.lines.any { it.millis >= 0 }) return parsed
            if (parsed.lines.isNotEmpty() && plainFallback == null) plainFallback = parsed
        }
        return plainFallback ?: Lyrics()
    }
    private fun parseLyricsResult(result: JSONObject): Lyrics {
        if (result.optBoolean("instrumental")) return Lyrics(instrumental = true)
        val synced = result.optString("syncedLyrics").takeUnless { it == "null" || it.isBlank() }
        if (synced != null) return Lyrics(parseLrc(synced))
        return Lyrics(result.optString("plainLyrics").takeUnless { it == "null" }.orEmpty().lines().filter(String::isNotBlank).map { Lyric(-1, it) })
    }
}
data class Lyric(val millis: Long, val text: String)
data class Lyrics(val lines: List<Lyric> = emptyList(), val instrumental: Boolean = false)
fun parseLrc(raw: String): List<Lyric> = raw.lines().flatMap { line ->
    val tags = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]").findAll(line).toList()
    val text = line.substringAfterLast(']').trim()
    if (text.isBlank()) emptyList() else tags.map { m ->
        val ms = m.groupValues[3].padEnd(3, '0').take(3).toLong()
        Lyric((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + ms, text)
    }
}.sortedBy(Lyric::millis)

val categories = linkedMapOf(
    "Trending" to listOf("Arijit Singh", "Diljit Dosanjh", "Karan Aujla", "The Weeknd", "Taylor Swift", "Anirudh Ravichander", "Billie Eilish"),
    "Punjabi" to listOf("Karan Aujla", "Diljit Dosanjh", "Sidhu Moose Wala", "AP Dhillon", "Shubh", "Amrinder Gill", "Jordan Sandhu"),
    "Hip Hop" to listOf("Kendrick Lamar", "Drake", "Travis Scott", "Divine", "KRSNA", "Eminem", "MC Stan", "Seedhe Maut"),
    "Bollywood" to listOf("Arijit Singh", "Pritam", "Atif Aslam", "Shreya Ghoshal", "KK", "Vishal Mishra", "Mohit Chauhan"),
    "Global Hits" to listOf("The Weeknd", "Taylor Swift", "Dua Lipa", "Bruno Mars", "Ed Sheeran", "Ariana Grande", "Post Malone"),
    "Lofi" to listOf("Lofi Sleep", "Indian Lofi", "Chillhop", "Aesthetic Beats", "Night Drives Lofi"),
    "EDM" to listOf("Martin Garrix", "Alan Walker", "Avicii", "David Guetta", "Marshmello", "DJ Snake", "Chainsmokers")
)
