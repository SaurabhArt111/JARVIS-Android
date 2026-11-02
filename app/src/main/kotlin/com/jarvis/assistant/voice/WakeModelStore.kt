package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.core.util.JarvisLog
import com.jarvis.core.wake.WakeModelSpec
import com.jarvis.core.wake.WakeModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where the (user-downloaded) wake-word models live: app-private storage, never shared. */
class WakeModelStore(context: Context) {
    val dir: File = File(context.applicationContext.filesDir, "wake")

    fun file(spec: WakeModelSpec) = File(dir, spec.fileName)

    fun presentNames(): Set<String> = WakeModels.ALL.filter { file(it).isFile && file(it).length() > 0 }.map { it.fileName }.toSet()

    /** All three files exist AND match their pinned SHA-256 (about 4 MB hashed, a few ms). */
    fun verified(): Boolean = WakeModels.ALL.all { spec ->
        val f = file(spec)
        f.isFile && try { f.inputStream().use { WakeModels.matches(spec, it) } } catch (_: Exception) { false }
    }

    fun deleteAll() {
        WakeModels.ALL.forEach { file(it).delete(); File(dir, it.fileName + ".part").delete() }
    }

    fun totalBytes(): Long = WakeModels.ALL.sumOf { file(it).length() }
}

sealed class ModelProgress {
    data class Downloading(val fileName: String, val index: Int, val count: Int, val bytes: Long, val total: Long) : ModelProgress()
    object Verifying : ModelProgress()
    object Done : ModelProgress()
    data class Failed(val message: String) : ModelProgress()
}

/**
 * Downloads the three wake-word models. Only ever runs because the user tapped "Download" (it is a cold Flow: nothing happens until collected, and cancelling
 * the collection cancels the transfer). HTTPS only, GitHub hosts only (every redirect hop is checked), each file hashed and compared with the pinned SHA-256
 * before it replaces anything; a failed or mismatching file is deleted.
 */
class WakeModelDownloader(private val store: WakeModelStore, private val log: JarvisLog = JarvisLog.None) {
    fun download(): Flow<ModelProgress> = flow {
        store.dir.mkdirs()
        val todo = WakeModels.missing(store.presentNames()).ifEmpty { WakeModels.ALL.filter { !store.verified() } }
        if (todo.isEmpty()) { emit(ModelProgress.Done); return@flow }
        for ((i, spec) in todo.withIndex()) {
            val part = File(store.dir, spec.fileName + ".part")
            try {
                fetch(spec, part) { bytes, total -> emit(ModelProgress.Downloading(spec.fileName, i + 1, todo.size, bytes, total)) }
                emit(ModelProgress.Verifying)
                val ok = part.inputStream().use { WakeModels.matches(spec, it) }
                if (!ok) {
                    part.delete()
                    log.w("WakeModels", "checksum mismatch for ${spec.fileName}; file discarded")
                    emit(ModelProgress.Failed("${spec.fileName} did not match the expected checksum, so I discarded it. Try again later; if it keeps failing the download source may have changed."))
                    return@flow
                }
                val target = store.file(spec)
                target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    emit(ModelProgress.Failed("Couldn't save ${spec.fileName} (storage full?)."))
                    return@flow
                }
            } catch (e: CancellationException) {
                part.delete(); throw e
            } catch (e: Exception) {
                part.delete()
                log.w("WakeModels", "download of ${spec.fileName} failed (${e.javaClass.simpleName})")
                emit(ModelProgress.Failed(friendly(e)))
                return@flow
            }
        }
        emit(if (store.verified()) ModelProgress.Done else ModelProgress.Failed("The files were saved but did not verify. Remove them and try again."))
    }.flowOn(Dispatchers.IO)

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Couldn't reach GitHub. Check your internet connection and try again."
        is DownloadRefused -> e.message ?: "The download was refused."
        else -> "The download failed (${e.javaClass.simpleName})."
    }

    private class DownloadRefused(message: String) : Exception(message)

    private suspend fun fetch(spec: WakeModelSpec, to: File, onProgress: suspend (Long, Long) -> Unit) {
        var url = spec.url
        var hops = 0
        while (true) {
            if (!WakeModels.isAllowedDownloadUrl(url)) throw DownloadRefused("The download was redirected somewhere I don't trust, so I stopped.")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "JARVIS-Android")
                val code = conn.responseCode
                if (code in 300..399) {
                    if (++hops > 5) throw DownloadRefused("Too many redirects.")
                    val loc = conn.getHeaderField("Location") ?: throw DownloadRefused("Redirect without a location.")
                    url = URL(URL(url), loc).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw DownloadRefused("The server answered HTTP $code.")
                val total = conn.contentLengthLong
                if (total > MAX_BYTES) throw DownloadRefused("The file is larger than expected, so I stopped.")
                var done = 0L
                var lastReport = 0L
                conn.inputStream.use { input ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > MAX_BYTES) throw DownloadRefused("The file is larger than expected, so I stopped.")
                            if (done - lastReport >= 128 * 1024) { lastReport = done; onProgress(done, total) }
                        }
                    }
                }
                onProgress(done, total)
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object { private const val MAX_BYTES = 20L * 1024 * 1024 }
}
