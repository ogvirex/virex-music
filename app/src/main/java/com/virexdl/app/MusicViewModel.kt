package com.virexdl.app

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VirexApp
    val store = app.store
    val library = store.state.asStateFlow()
    val audioSession = app.audioSession.asStateFlow()
    val online = app.network.online.asStateFlow()
    val radioStatus = app.radioStatus.asStateFlow()
    val lyricsError = MutableStateFlow<String?>(null)
    val home = MutableStateFlow<List<Song>>(emptyList())
    val search = MutableStateFlow<List<Song>>(emptyList())
    val category = MutableStateFlow("Trending")
    val homeLoading = MutableStateFlow(false)
    val searchLoading = MutableStateFlow(false)
    val homeError = MutableStateFlow<String?>(null)
    val searchError = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val current = MutableStateFlow<Song?>(null)
    val queue = MutableStateFlow<List<Song>>(emptyList())
    val queueKeys = MutableStateFlow<List<String>>(emptyList())
    val queueIndex = MutableStateFlow(0)
    val playing = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val connected = MutableStateFlow(false)
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val shuffled = MutableStateFlow(false)
    val repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val contextLabel = MutableStateFlow("Virex Radio")
    val lyrics = MutableStateFlow(Lyrics())
    val lyricsLoading = MutableStateFlow(false)
    val importProgress = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val workManager = WorkManager.getInstance(app)
    val work = workManager.getWorkInfosByTagFlow("audio").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var controller: MediaController? = null
    private var homeJob: Job? = null
    private var searchJob: Job? = null
    private var lyricsJob: Job? = null
    private var importJob: Job? = null
    private val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync() }
        override fun onPlayerError(error: PlaybackException) { message("Playback failed. Check your connection or try another track.") }
    }
    init {
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c; c.addListener(listener); connected.value = true; sync()
            }.onFailure { message("Could not connect to audio service") }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch { while (isActive) { controller?.let { position.value = it.currentPosition.coerceAtLeast(0); duration.value = it.duration.takeIf { ms -> ms > 0 } ?: ((current.value?.duration ?: 0) * 1000) }; delay(250) } }
        var wasOnline = online.value
        viewModelScope.launch {
            online.collect { connected ->
                if (!connected) { homeJob?.cancel(); homeLoading.value = false }
                if (connected && !wasOnline) { refreshHome(); if (lyrics.value.lines.isEmpty()) loadLyrics(current.value) }
                wasOnline = connected
            }
        }
        refreshHome()
    }
    private fun sync() {
        val c = controller ?: return
        val song = c.currentMediaItem?.song()
        if (current.value?.id != song?.id) { current.value = song; loadLyrics(song) }
        queue.value = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).song() }
        queueKeys.value = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaMetadata.extras?.getString("queueEntry") ?: "${c.getMediaItemAt(it).mediaId}:$it" }
        queueIndex.value = c.currentMediaItemIndex.coerceAtLeast(0)
        playing.value = c.isPlaying; buffering.value = c.playbackState == Player.STATE_BUFFERING
        shuffled.value = c.shuffleModeEnabled; repeat.value = c.repeatMode
        contextLabel.value = c.currentMediaItem?.mediaMetadata?.extras?.getString("label") ?: "Virex Radio"
    }
    fun message(text: String) { messages.tryEmit(text) }
    fun refreshHome(selected: String = category.value) {
        category.value = selected
        homeJob?.cancel()
        if (!online.value) { homeLoading.value = false; return }
        homeJob = viewModelScope.launch {
            homeLoading.value = true; homeError.value = null
            try {
                val results = supervisorScope { categories.getValue(selected).shuffled().take(3).map { topic -> async { runCatching { app.api.search(topic, 15) }.getOrDefault(emptyList()) } }.awaitAll().flatten() }
                check(results.isNotEmpty()) { "Could not load your mix. Check your connection and retry." }
                val counts = mutableMapOf<String, Int>()
                home.value = results.distinctBy(Song::id).shuffled().filter { val count = counts[it.artist] ?: 0; if (count < 2) { counts[it.artist] = count + 1; true } else false }.take(15)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { homeError.value = e.message }
            finally { homeLoading.value = false }
        }
    }
    fun searchQuery(value: String, submitted: Boolean = false) {
        query.value = value
        searchJob?.cancel(); searchLoading.value = false; searchError.value = null
        if (value.trim().length < 2) { search.value = emptyList(); return }
        if (submitted) store.remember(value.trim())
        searchJob = viewModelScope.launch {
            if (!submitted) delay(380)
            searchLoading.value = true
            try { search.value = app.api.search(value.trim()) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { searchError.value = "Search is unavailable. Check your connection and retry." }
            finally { searchLoading.value = false }
        }
    }
    fun play(songs: List<Song>, index: Int = 0, radio: Boolean = false, label: String = "Virex Radio") {
        val c = controller ?: return message("Audio service is connecting…")
        if (songs.isEmpty()) return
        runCatching {
            val selected = songs[index]
            val playable = songs.filter { s -> s.url(library.value.settings.hq) != null || library.value.downloads.any { it.song.id == s.id } }
            val start = playable.indexOfFirst { it.id == selected.id }
            check(start >= 0) { "This song has no audio stream" }
            c.shuffleModeEnabled = false; val sessionId = java.util.UUID.randomUUID().toString()
            c.setMediaItems(playable.map { it.mediaItem(store, radio, label, sessionId) }, start, 0); c.prepare(); c.play()
            contextLabel.value = label
        }.onFailure { message(it.message ?: "Cannot play track") }
    }
    fun radio(song: Song) { if (query.value.isNotBlank()) store.remember(query.value.trim()); play(listOf(song), radio = true) }
    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playerError != null || it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previous() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
    fun seek(ms: Long) { controller?.seekTo(ms.coerceIn(0, duration.value)) }
    fun jump(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.play() } }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun queueSong(song: Song, next: Boolean) {
        val c = controller ?: return message("Audio service is connecting…")
        if (c.mediaItemCount == 0) { play(listOf(song), label = "Your queue"); return }
        runCatching {
            val extra = c.currentMediaItem?.mediaMetadata?.extras
            val item = song.mediaItem(store, extra?.getBoolean("radio") == true, extra?.getString("label") ?: "Your queue", extra?.getString("radioSession"))
            val stoppedShuffle = next && c.shuffleModeEnabled
            if (stoppedShuffle) c.shuffleModeEnabled = false
            c.addMediaItem(if (next) c.currentMediaItemIndex + 1 else c.mediaItemCount, item)
            message(if (next) "Playing next: ${song.title}${if (stoppedShuffle) " · shuffle off" else ""}" else "Added to queue")
        }.onFailure { message("This song can't be queued right now") }
    }
    fun removeQueued(index: Int) {
        controller?.let { if (index > it.currentMediaItemIndex && index < it.mediaItemCount) it.removeMediaItem(index) }
    }
    fun moveQueued(from: Int, to: Int) {
        controller?.let { if (from > it.currentMediaItemIndex && to > it.currentMediaItemIndex && from < it.mediaItemCount && to < it.mediaItemCount) { if (it.shuffleModeEnabled) { it.shuffleModeEnabled = false; message("Shuffle off · using your queue order") }; it.moveMediaItem(from, to) } }
    }
    private fun downloadRequest(song: Song) = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf("song" to song.json().toString()))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .addTag("audio").addTag("title:${song.title.take(100)}").addTag("song:${song.id}").build()

    fun download(song: Song) {
        if (library.value.downloads.any { it.song.id == song.id }) return message("Already in your offline library")
        runCatching {
            workManager.enqueueUniqueWork("download:${song.id}", ExistingWorkPolicy.KEEP, downloadRequest(song))
            message("Download queued: ${song.title}")
        }.onFailure { message("Could not queue download") }
    }

    fun downloadPlaylist(mix: Mix) {
        val saved = library.value.downloads.map { it.song.id }.toSet()
        val active = work.value.filterNot { it.state.isFinished }.flatMap { it.tags }.toSet()
        val remaining = mix.songs.distinctBy(Song::id).filter { it.id !in saved && "song:${it.id}" !in active }
        if (remaining.isEmpty()) return message(if (mix.songs.all { it.id in saved }) "Playlist saved offline" else "Playlist downloads already queued")
        var queued = 0
        remaining.forEach { song ->
            runCatching { workManager.enqueueUniqueWork("download:${song.id}", ExistingWorkPolicy.KEEP, downloadRequest(song)) }.onSuccess { queued++ }
        }
        message(if (queued == remaining.size) "Queued $queued tracks · follow progress in Offline" else "Queued $queued of ${remaining.size} tracks. Tap Download all to retry the rest.")
    }
    fun retryLyrics() { loadLyrics(current.value) }
    private fun loadLyrics(song: Song?) {
        lyricsJob?.cancel(); lyrics.value = Lyrics(); lyricsLoading.value = false; lyricsError.value = null
        if (song == null) return
        val cache = java.io.File(app.filesDir, "lyrics/${offlineKey(song.id)}.json")
        runCatching {
            if (cache.isFile) {
                val j = org.json.JSONObject(cache.readText())
                lyrics.value = Lyrics(j.getJSONArray("lines").objects().map { Lyric(it.getLong("time"), it.getString("text")) }, j.optBoolean("instrumental"))
            }
        }
        if (lyrics.value.lines.isNotEmpty() || lyrics.value.instrumental) return
        if (!online.value) { lyricsError.value = "You're offline. Connect to find lyrics for this song."; return }
        lyricsJob = viewModelScope.launch {
            lyricsLoading.value = true
            try {
                val found = app.api.lyrics(song)
                lyrics.value = found
                if (found.lines.isNotEmpty() || found.instrumental) withContext(Dispatchers.IO) {
                    cache.parentFile?.mkdirs()
                    val j = org.json.JSONObject().put("instrumental", found.instrumental).put("lines", org.json.JSONArray().apply { found.lines.forEach { put(org.json.JSONObject().put("time", it.millis).put("text", it.text)) } })
                    val temp = java.io.File(cache.parentFile, cache.name + ".part")
                    temp.writeText(j.toString()); temp.renameTo(cache)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { lyricsError.value = "Couldn't load lyrics. Try again." }
            finally { lyricsLoading.value = false }
        }
    }
    fun importLines(name: String, text: String) {
        if (importJob?.isActive == true) return
        val lines = text.lines().map { it.replace(Regex("^\\d+[.\\-\\s]+"), "").replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex("\\b(feat|ft)\\b\\.?.*$", RegexOption.IGNORE_CASE), "").trim() }.filter { it.length > 1 }.distinct()
        if (lines.isEmpty()) return message("Paste one song per line")
        importJob = viewModelScope.launch {
            val found = mutableListOf<Song>()
            importProgress.value = "Starting import…"
            try {
                for ((batch, chunk) in lines.chunked(5).withIndex()) {
                    val results = supervisorScope { chunk.map { async { runCatching { app.api.search(it, 1).firstOrNull() }.getOrNull() } }.awaitAll().filterNotNull() }
                    found.addAll(results)
                    importProgress.value = "${minOf((batch + 1) * 5, lines.size)} / ${lines.size} checked · ${found.distinctBy(Song::id).size} matched"
                    delay(120)
                }
                if (found.isNotEmpty()) { store.create(name.ifBlank { "Imported playlist" }, found); message("Imported ${found.distinctBy(Song::id).size} songs") } else message("No matching songs found")
            } finally { importProgress.value = null }
        }
    }
    fun cancelImport() { importJob?.cancel(); importProgress.value = null }
    fun exportBackup(uri: Uri) = viewModelScope.launch {
        val raw = store.backupJson().toString(2)
        runCatching { withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(raw) } } }
            .onSuccess { message("Backup saved") }.onFailure { message("Could not save backup") }
    }
    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) {
            val raw = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
                val out = StringBuilder(); val buffer = CharArray(8192)
                while (true) { val n = reader.read(buffer); if (n < 0) break; require(out.length + n <= 10_000_000); out.append(buffer, 0, n) }; out.toString()
            }
            store.restore(raw)
        } }.onSuccess { message("Playlists and settings restored") }.onFailure { message("Invalid or unreadable Virex backup") }
    }
    fun exportAudio(download: Download, uri: Uri) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")!!.use { out -> java.io.File(download.path).inputStream().use { it.copyTo(out) } } } }
            .onSuccess { message("Audio file saved") }.onFailure { message("Could not export audio") }
    }
    override fun onCleared() { controller?.removeListener(listener); MediaController.releaseFuture(future); super.onCleared() }
}
