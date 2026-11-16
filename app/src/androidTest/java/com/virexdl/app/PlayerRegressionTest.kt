package com.virexdl.app

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.work.ListenableWorker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class PlayerRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun offlineDownloadAndSmallPlayerControls() {
        val app = ui.activity.application as VirexApp
        val originalApi = app.api
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        val audio = wav()
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio)))
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        app.api = MusicApi(client)
        val song = Song("regression-offline", "Signal test", "Virex Studio", "", 30, listOf(Stream("320kbps", server.url("/audio.wav").toString())))
        app.store.removeDownload(song.id)
        try {
            // Exercise the actual worker, HTTPS request, file flush/rename and library persistence.
            val worker = TestListenableWorkerBuilder<DownloadWorker>(app).setInputData(workDataOf("song" to song.json().toString())).build()
            val result = runBlocking { worker.doWork() }
            assertTrue("Worker must return success", result is ListenableWorker.Result.Success)
            val saved = app.store.state.value.downloads.first { it.song.id == song.id }
            assertArrayEquals(audio, File(saved.path).readBytes())
            assertTrue(song.mediaItem(app.store).localConfiguration!!.uri.scheme == "file")
            server.shutdown() // Server is unavailable before playback: the private offline file must be used.
            val vm = ViewModelProvider(ui.activity)[MusicViewModel::class.java]
            ui.waitUntil(15000) { vm.connected.value }
            ui.runOnIdle { vm.play(listOf(song), label = "Offline library") }
            ui.waitUntil(15000) { vm.current.value?.id == song.id && vm.playing.value }
            ui.onNodeWithText("Signal test").performClick()
            ui.onNodeWithTag("full-player").assertIsDisplayed()
            ui.onNodeWithTag("save-offline").assertIsDisplayed()
            ui.onNodeWithText("Saved to offline vault").assertIsDisplayed()
            ui.onNodeWithTag("player-play").assertIsDisplayed()
            File(app.filesDir, "player-qa.png").outputStream().use { ui.onNodeWithTag("full-player").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            (0..2).forEach { ui.onNodeWithTag("player-tab-$it").assertIsDisplayed() }
            ui.onNodeWithTag("player-tab-2").performClick()
            ui.onNodeWithText("Signal test").assertIsDisplayed()
            ui.onNodeWithTag("player-tab-0").performClick()
            ui.onNodeWithContentDescription("Close player").performClick()
            ui.onNodeWithText("Offline", useUnmergedTree = true).performClick()
            ui.onAllNodesWithText("Signal test").onFirst().assertIsDisplayed()
            assertTrue("Offline metadata must survive store recreation", LibraryStore(app).state.value.downloads.any { it.song.id == song.id })
        } finally {
            app.api = originalApi
            runCatching { server.shutdown() }
        }
    }
    private fun wav(): ByteArray {
        val size = 22050 * 2 * 30
        val b = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        b.putInt(22050).putInt(44100).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        repeat(size / 2) { i -> b.putShort((kotlin.math.sin(i * 2.0 * Math.PI * 220 / 22050) * 500).toInt().toShort()) }
        return b.array()
    }
}
