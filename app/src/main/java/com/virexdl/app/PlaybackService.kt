package com.virexdl.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

fun Song.mediaItem(store: LibraryStore, radio: Boolean = false, label: String = "Virex Radio", radioSession: String? = null): MediaItem {
    val offline = store.state.value.downloads.find { it.song.id == id }
    val uri = offline?.let { Uri.fromFile(File(it.path)) } ?: Uri.parse(url(store.state.value.settings.hq) ?: error("No playable stream for $title"))
    return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle("Virex Music")
            .setArtworkUri(offline?.artwork?.takeIf(String::isNotBlank)?.let { Uri.fromFile(File(it)) } ?: image.takeIf(String::isNotBlank)?.let(Uri::parse))
            .setExtras(Bundle().apply { putString("song", json().toString()); putBoolean("radio", radio); putString("label", label); putString("radioSession", radioSession); putString("queueEntry", java.util.UUID.randomUUID().toString()) }).build()
    ).build()
}
fun MediaItem.song(): Song? = runCatching { Song.parse(JSONObject(mediaMetadata.extras?.getString("song") ?: return null)) }.getOrNull()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshots = kotlinx.coroutines.channels.Channel<PlaybackSnapshot?>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var listenedMs = 0L
    private var counted = false
    private var recentTouched = false
    private var restoring = false
    private var radioJob: Job? = null
    private var radioSession: String? = null
    private var fetchingSeed: String? = null
    private val seenRadioIds = mutableSetOf<String>()
    private val app get() = application as VirexApp
    private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { listenedMs = 0; counted = false; recentTouched = false; extendRadio() }
        override fun onEvents(p: Player, events: Player.Events) {
            if (!restoring) {
                if (p.isPlaying && !recentTouched) p.currentMediaItem?.song()?.let { app.store.touchRecent(it); recentTouched = true }
                snapshots.trySend(snapshot())
            }
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { extendRadio() }
        override fun onAudioSessionIdChanged(audioSessionId: Int) { app.audioSession.value = audioSessionId }
    }
    private fun newPlayer(focus: Boolean): ExoPlayer = ExoPlayer.Builder(this).build().apply {
        setAudioAttributes(attributes, focus)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
    }
    override fun onCreate() {
        super.onCreate()
        player = newPlayer(true)
        player.addListener(playerListener)
        restoring = true
        app.sessions.load()?.playable(app.store.state.value)?.let { saved ->
            player.setMediaItems(saved.songs.map { it.mediaItem(app.store, saved.radio, saved.label, saved.radioSession) }, saved.index, saved.positionMs)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeat
        }
        restoring = false
        scope.launch(Dispatchers.IO) { for (saved in snapshots) app.sessions.save(saved) }
        scope.launch {
            var lastTick = android.os.SystemClock.elapsedRealtime()
            var ticks = 0
            while (isActive) {
                delay(1000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (player.isPlaying) {
                    listenedMs += (now - lastTick).coerceIn(0, 1500)
                    if (!counted && listenedMs >= listenThreshold(player.duration.takeIf { it > 0 } ?: (player.currentMediaItem?.song()?.duration ?: 60) * 1000)) {
                        player.currentMediaItem?.song()?.let { app.store.countListen(it) }; counted = true
                    }
                }
                lastTick = now
                if (++ticks % 5 == 0) snapshots.trySend(snapshot())
            }
        }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return if (controller.packageName == packageName || controller.isTrusted) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.reject()
                }
            }).build()
        scope.launch { app.network.online.collect { if (it) extendRadio() } }
        scope.launch {
            while (isActive) {
                delay(150)
                val remaining = player.duration - player.currentPosition
                if (app.store.state.value.settings.crossfade && player.isPlaying && player.duration > 6000 &&
                    remaining in 1..3500 && player.hasNextMediaItem() &&
                    player.repeatMode != Player.REPEAT_MODE_ONE && !player.shuffleModeEnabled) blendNext()
            }
        }
    }
    private suspend fun blendNext() {
        val outgoing = player
        val index = outgoing.currentMediaItemIndex
        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex < 0) return
        val count = outgoing.mediaItemCount
        val incoming = newPlayer(false)
        var promoted = false
        fun valid(): Boolean = player === outgoing && outgoing.isPlaying && outgoing.currentMediaItemIndex == index &&
            outgoing.mediaItemCount == count && outgoing.duration - outgoing.currentPosition in 1..4000 &&
            app.store.state.value.settings.crossfade && outgoing.repeatMode != Player.REPEAT_MODE_ONE && !outgoing.shuffleModeEnabled
        try {
            incoming.volume = 0f
            incoming.repeatMode = outgoing.repeatMode
            incoming.setMediaItems((0 until count).map(outgoing::getMediaItemAt), nextIndex, 0)
            incoming.prepare()
            while (incoming.playbackState != Player.STATE_READY && valid() && incoming.playerError == null) delay(30)
            if (!valid() || incoming.playerError != null || incoming.playbackState != Player.STATE_READY) return
            val length = (outgoing.duration - outgoing.currentPosition - 200).coerceIn(1, 3000)
            incoming.play()
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) {
                if (!valid() || incoming.playerError != null) return
                val fraction = ((android.os.SystemClock.elapsedRealtime() - start).toFloat() / length).coerceIn(0f, 1f)
                outgoing.volume = 1f - fraction; incoming.volume = fraction
                if (fraction >= 1f) break
                delay(30)
            }
            outgoing.removeListener(playerListener)
            outgoing.stop()
            incoming.setAudioAttributes(attributes, true)
            incoming.addListener(playerListener)
            player = incoming
            listenedMs = incoming.currentPosition.coerceIn(0, 3000); counted = false; recentTouched = true
            incoming.currentMediaItem?.song()?.let { app.store.touchRecent(it) }
            snapshots.trySend(snapshot())
            session.setPlayer(incoming)
            app.audioSession.value = incoming.audioSessionId
            promoted = true
            outgoing.release()
            extendRadio()
        } finally {
            if (!promoted) { incoming.release(); if (player === outgoing) outgoing.volume = 1f }
        }
    }
    private fun extendRadio() {
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.extras?.getBoolean("radio") != true) {
            radioJob?.cancel(); radioSession = null; fetchingSeed = null; seenRadioIds.clear(); app.radioStatus.value = ""; return
        }
        val token = item.mediaMetadata.extras?.getString("radioSession") ?: item.mediaId
        if (token != radioSession) { radioJob?.cancel(); radioSession = token; seenRadioIds.clear(); fetchingSeed = null }
        if (player.mediaItemCount - player.currentMediaItemIndex > 4) return
        if (!app.network.online.value) { app.radioStatus.value = "You're offline. Radio resumes when you're connected."; return }
        if (radioJob?.isActive == true && fetchingSeed == item.mediaId) return
        radioJob?.cancel(); fetchingSeed = item.mediaId
        app.radioStatus.value = "Finding your next songs…"
        radioJob = scope.launch {
            try {
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                seenRadioIds.addAll(existing)
                val next = RadioEngine(app.api).next(item.song() ?: return@launch, seenRadioIds.toSet(), app.store.state.value.settings.hq)
                if (radioSession != token || player.currentMediaItem?.mediaMetadata?.extras?.getString("radioSession") != token) return@launch
                seenRadioIds.addAll(next.map(Song::id))
                player.addMediaItems(next.map { it.mediaItem(app.store, radio = true, radioSession = token) })
                app.radioStatus.value = if (next.isNotEmpty()) "Virex Radio · more songs added automatically" else "No more matches right now. Start radio from another song."
                if (player.playbackState == Player.STATE_ENDED && player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { app.radioStatus.value = "Radio couldn't connect. It will retry when your connection returns." }
        }
    }
    private fun snapshot(): PlaybackSnapshot? {
        if (player.mediaItemCount == 0) return null
        val songs = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).song() }
        if (songs.isEmpty()) return null
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        return PlaybackSnapshot(songs, player.currentMediaItemIndex.coerceIn(0, songs.lastIndex), player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode,
            extras?.getBoolean("radio") == true, extras?.getString("label") ?: "Your queue", extras?.getString("radioSession"))
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf() }
    override fun onDestroy() { val saved = snapshot(); scope.cancel(); app.sessions.save(saved); app.audioSession.value = 0; session.release(); player.release(); super.onDestroy() }
}
