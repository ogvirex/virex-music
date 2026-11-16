package com.virexdl.app

import androidx.test.core.app.ApplicationProvider
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineSaveTest {
    @Test fun completedAudioPersistsAndPlaybackUsesLocalFile() = withServer { app, server ->
        val bytes = ByteArray(32_768) { (it % 251).toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody(Buffer().write(bytes)))
        val song = fixture(server, "download-success")
        val worker = TestListenableWorkerBuilder<DownloadWorker>(app).setInputData(workDataOf("song" to song.json().toString())).build()
        assertTrue(runBlocking { worker.doWork() } is ListenableWorker.Result.Success)
        val saved = app.store.state.value.downloads.single { it.song.id == song.id }
        assertArrayEquals(bytes, File(saved.path).readBytes())
        assertFalse(File(saved.path.replace(".audio", ".part")).exists())
        assertTrue(LibraryStore(app).state.value.downloads.any { it.song.id == song.id })
        server.shutdown()
        assertEquals("file", song.mediaItem(app.store).localConfiguration!!.uri.scheme)
    }
    @Test fun failedRequestDoesNotCreateAnOfflineEntry() = withServer { app, server ->
        server.enqueue(MockResponse().setResponseCode(503))
        val song = fixture(server, "download-failure")
        val worker = TestListenableWorkerBuilder<DownloadWorker>(app).setInputData(workDataOf("song" to song.json().toString())).build()
        assertTrue(runBlocking { worker.doWork() } is ListenableWorker.Result.Failure)
        assertFalse(app.store.state.value.downloads.any { it.song.id == song.id })
        assertFalse(File(app.filesDir, "offline/${offlineKey(song.id)}.part").exists())
    }
    private fun fixture(server: MockWebServer, id: String) = Song(id, "Offline fixture", "Virex Studio", "", 2, listOf(Stream("320kbps", server.url("/audio").toString())))
    private fun withServer(block: (VirexApp, MockWebServer) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<VirexApp>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val original = app.api
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        app.api = MusicApi(OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build())
        try { block(app, server) } finally { app.api = original; runCatching { server.shutdown() } }
    }
}
