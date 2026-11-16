package com.virexdl.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class RadioEngine(private val api: MusicApi) {
    suspend fun next(seed: Song, excluded: Set<String>, highQuality: Boolean): List<Song> {
        var suggestions = emptyList<Song>()
        repeat(2) { attempt ->
            if (suggestions.isEmpty()) {
                try { suggestions = withTimeoutOrNull(10000) { api.recommendations(seed.id) }.orEmpty() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
                if (suggestions.isEmpty() && attempt == 0) delay(400)
            }
        }
        var chosen = playableRadioSongs(suggestions, excluded, highQuality)
        if (chosen.size < 4) {
            val fallback = mutableListOf<Song>()
            for (query in seed.artist.split(",").map(String::trim).filter(String::isNotBlank).take(2)) {
                try { fallback += withTimeoutOrNull(8000) { api.search(query, 20) }.orEmpty() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            chosen = playableRadioSongs(chosen + fallback, excluded, highQuality)
        }
        return chosen.take(12)
    }
}
fun playableRadioSongs(songs: List<Song>, excluded: Set<String>, hq: Boolean): List<Song> {
    val counts = mutableMapOf<String, Int>()
    val unique = songs.distinctBy(Song::id).filter { it.id !in excluded && it.url(hq) != null }
    val varied = unique.filter { val count = counts[it.artist] ?: 0; if (count < 3) { counts[it.artist] = count + 1; true } else false }
    return (varied + unique).distinctBy(Song::id).take(12)
}
