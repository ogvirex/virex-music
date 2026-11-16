package com.virexdl.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

fun offlineKey(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as VirexApp
        val song = runCatching { Song.parse(JSONObject(inputData.getString("song")!!)) }.getOrElse { return@withContext Result.failure() }
        if (app.store.state.value.downloads.any { it.song.id == song.id }) return@withContext Result.success()
        val dir = File(applicationContext.filesDir, "offline").apply { mkdirs() }
        val key = offlineKey(song.id)
        val target = File(dir, "$key.audio")
        val part = File(dir, "$key.part")
        val cover = File(dir, "$key.jpg")
        try {
            val url = song.url(app.store.state.value.settings.hq) ?: error("No download URL")
            app.api.client.newBuilder().callTimeout(8, TimeUnit.MINUTES).build().newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("No audio received")
                check(body.contentType()?.type != "text") { "Server returned text instead of audio" }
                val length = body.contentLength()
                var count = 0L
                var lastProgress = -1
                body.byteStream().use { source -> part.outputStream().use { dest ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw java.io.IOException("Download cancelled")
                        val n = source.read(buffer)
                        if (n < 0) break
                        count += n
                        check(count <= 250L * 1024 * 1024) { "Audio exceeds 250 MB limit" }
                        dest.write(buffer, 0, n)
                        val progress = if (length > 0) (count * 100 / length).toInt() else 0
                        if (progress != lastProgress) { lastProgress = progress; setProgress(workDataOf("percent" to progress)) }
                    }
                    dest.fd.sync()
                } }
                check(count > 0 && (length < 0 || count == length)) { "Incomplete download" }
            }
            check(part.renameTo(target)) { "Could not finish download" }
            runCatching {
                if (song.image.startsWith("https://")) app.api.client.newCall(Request.Builder().url(song.image).build()).execute().use { res ->
                    if (res.isSuccessful) res.body?.byteStream()?.use { input ->
                        cover.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var count = 0
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                check(count <= 5 * 1024 * 1024)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }.onFailure { cover.delete() }
            app.store.edit { it.copy(downloads = listOf(Download(song, target.absolutePath, if (cover.isFile) cover.absolutePath else "", target.length())) + it.downloads.filterNot { d -> d.song.id == song.id }) }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { part.delete(); throw e } catch (e: Exception) {
            part.delete(); target.delete(); cover.delete()
            Result.failure(workDataOf("error" to (e.message ?: "Download failed")))
        }
    }
}
