package com.jarvis.assistant.data

import android.content.Context
import android.util.Log
import com.jarvis.core.util.JarvisLog
import com.jarvis.core.util.LogRedactor
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat + a small rotating private log file (256 KB x 2), with every line passed through [LogRedactor] first. The file never leaves the device unless the
 * user chooses "Share diagnostics" (which shows exactly what will be shared). No crash-reporting service is used or configured: [CrashHandler] only writes
 * a local record.
 */
class AppLog(context: Context, private val debug: Boolean = false) : JarvisLog {
    private val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
    private val file = File(dir, "jarvis.log")
    private val old = File(dir, "jarvis.log.1")
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    override fun d(tag: String, msg: String) { if (debug) write('D', tag, msg, null) }
    override fun i(tag: String, msg: String) = write('I', tag, msg, null)
    override fun w(tag: String, msg: String, t: Throwable?) = write('W', tag, msg, t)
    override fun e(tag: String, msg: String, t: Throwable?) = write('E', tag, msg, t)

    private fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        val clean = LogRedactor.redact(msg)
        val line = if (t == null) clean else clean + " [" + t.javaClass.simpleName + "]"   // exception MESSAGES can contain user text; the class name cannot
        when (level) {
            'D' -> Log.d(tag, line); 'I' -> Log.i(tag, line); 'W' -> Log.w(tag, line); else -> Log.e(tag, line)
        }
        try {
            synchronized(lock) {
                if (file.length() > MAX_BYTES) { old.delete(); file.renameTo(old) }
                file.appendText("${fmt.format(Date())} $level/$tag: $line\n")
            }
        } catch (_: Exception) { /* logging must never crash the app */ }
    }

    /** The last [maxBytes] of the log (older file first), for the diagnostics screen. */
    fun readTail(maxBytes: Int = 64 * 1024): String = synchronized(lock) {
        try {
            val all = (if (old.exists()) old.readText() else "") + (if (file.exists()) file.readText() else "")
            if (all.length > maxBytes) all.takeLast(maxBytes) else all
        } catch (e: Exception) { "" }
    }

    fun clear() = synchronized(lock) { file.delete(); old.delete() }

    /** Uncaught exceptions inside coroutines launched with this handler are logged instead of crashing the process. */
    val coroutineHandler = CoroutineExceptionHandler { _, e -> this.e("Coroutine", "uncaught ${e.javaClass.simpleName}", e) }

    private companion object { const val MAX_BYTES = 256 * 1024L }
}

/**
 * Writes `crash/last_crash.txt` (time, exception class, stack frames — never exception messages, which can contain user text) and then hands over to the previous
 * handler, so Android still shows its normal "app stopped" behaviour. On the next start the UI offers to show it ([pending] / [dismiss]).
 */
object CrashHandler {
    fun install(context: Context, log: AppLog) {
        val dir = File(context.applicationContext.filesDir, "crash").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { p ->
                    p.println("JARVIS crash record")
                    p.println("time: ${Date()}")
                    p.println("thread: ${thread.name}")
                    var c: Throwable? = t
                    var depth = 0
                    while (c != null && depth < 4) {
                        p.println((if (depth == 0) "exception: " else "caused by: ") + c.javaClass.name)
                        c.stackTrace.take(40).forEach { p.println("    at $it") }
                        c = c.cause; depth++
                    }
                }
                File(dir, "last_crash.txt").writeText(LogRedactor.redact(sw.toString(), 12_000))
                log.e("Crash", "uncaught ${t.javaClass.simpleName} on ${thread.name} — record saved", t)
            } catch (_: Throwable) { /* never mask the original crash */ }
            previous?.uncaughtException(thread, t)
        }
    }

    private fun record(context: Context) = File(File(context.applicationContext.filesDir, "crash"), "last_crash.txt")

    /** The record of an unreported crash, or null. */
    fun pending(context: Context): String? = try { record(context).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }

    fun dismiss(context: Context) { try { record(context).delete() } catch (_: Exception) { } }
}
