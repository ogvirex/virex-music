package com.virexdl.app

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RadioAndLyricsTest {
    private fun song(id: String, artist: String = "Artist") = Song(id, "Song $id", artist, "", 200, listOf(Stream("320kbps", "https://example.com/$id.mp4")))
    @Test fun newProviderSearchResponseParsesForStreamingAndDownloads() = runBlocking {
        MockWebServer().use { server ->
            val fixture = javaClass.getResource("/worker-search.json")!!.readText()
            server.enqueue(MockResponse().setBody(fixture))
            val songs = localApi(server).search("lover", 2)
            assertEquals(2, songs.size)
            assertEquals("Lover", songs.first().title)
            assertTrue(songs.first().artist.isNotBlank())
            assertTrue(songs.first().duration > 0)
            assertTrue(songs.first().image.startsWith("https://"))
            assertTrue(songs.first().streams.any { it.quality == "320kbps" && it.url.startsWith("https://") })
        }
    }
    @Test fun radioFallsBackWhenSuggestionsFail() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            val found = JSONArray().put(song("seed").json()).put(song("next").json()).put(song("next").json())
            server.enqueue(MockResponse().setBody(JSONObject().put("data", JSONObject().put("results", found)).toString()))
            val api = localApi(server)
            val result = RadioEngine(api).next(song("seed"), setOf("seed"), true)
            assertEquals(listOf("next"), result.map(Song::id))
        }
    }
    @Test fun radioDeduplicatesAndKeepsAnArtistOnlyMixPlayable() {
        val songs = (1..10).map { song("$it") }
        val result = playableRadioSongs(songs + songs, setOf("1", "2"), true)
        assertEquals(8, result.size)
        assertEquals(8, result.map(Song::id).toSet().size)
    }
    @Test fun lyricsTryOtherCreditedArtists() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"syncedLyrics":"[00:01.00]Matched singer","duration":200}"""))
            val lyrics = localApi(server).lyrics(song("seed", "Producer, Singer"))
            assertEquals("Matched singer", lyrics.lines.single().text)
        }
    }
    private fun localApi(server: MockWebServer): MusicApi {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        return MusicApi(client, server.url("/api").toString().trimEnd('/'), server.url("/lyrics").toString().trimEnd('/'))
    }
}
