package com.jarvis.assistant.platform

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.StatFs
import com.jarvis.assistant.R
import com.jarvis.assistant.data.FileIndex
import com.jarvis.core.ports.ContentCategory
import com.jarvis.core.ports.DocumentStore
import com.jarvis.core.ports.StorageLocation
import com.jarvis.core.ports.StoredFile
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.storage.FileTypes
import com.jarvis.core.storage.SafeFileName
import com.jarvis.core.util.JarvisClock
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** A save that failed in a way the user can understand (message is user-facing). */
class StorageException(message: String) : IOException(message)

/**
 * Everything JARVIS creates for the user lives in **Documents/JARVIS/<Category>** (Conversations, Notes, Generated, Images, Audio, Exports, Other).
 *
 *  - Public storage uses MediaStore (API 29+ scoped storage): no storage permission is needed for files the app creates itself. Each file is inserted with
 *    IS_PENDING=1, written, flushed and only then published; its size is read back and a short write is rolled back, so a half-written file is never listed.
 *  - If public storage fails for any reason (or the user chose "private" in Settings) the file goes to the app's private folder (`files/documents/<Category>`),
 *    and the StoredFile says so ([StorageLocation.APP_PRIVATE]); callers tell the user where it went.
 *  - Names are safe (core.storage.SafeFileName) and unique; nothing is ever overwritten. Extensions/MIME must be on the allow-list for the category and binary content must
 *    carry the right magic bytes (core.storage.FileTypes).
 *  - The library is an index table (see FileIndex): it is the list the UI shows; [list] drops index rows whose file has vanished (deleted in a file manager) - it never deletes files.
 *  - Deleting happens only through [deletePermanently] / [cleanupOlderThan], both reachable only from an explicit, confirmed UI action.
 *
 * Limits (scoped storage): after an uninstall/reinstall the old files are still in Documents/JARVIS for the user, but the new install cannot enumerate or modify
 * files it did not create, so they aren't in the library.
 */
class DocumentStoreAndroid(
    context: Context,
    private val settings: Settings,
    private val index: FileIndex,
    private val clock: JarvisClock,
    private val log: JarvisLog = JarvisLog.None,
) : DocumentStore {
    private val ctx = context.applicationContext
    private val resolver = ctx.contentResolver
    private val saveLock = Mutex()
    private val privateRoot get() = File(ctx.filesDir, "documents")

    companion object {
        const val ROOT_FOLDER = "Documents/JARVIS"
        const val MAX_BYTES = 50L * 1024 * 1024
    }

    private fun wantsPublic() = !settings.get(Keys.STORAGE_LOCATION, "documents").equals("private", ignoreCase = true)

    // ------------------------------------------------------------------------------------------------ save
    override suspend fun saveText(category: ContentCategory, title: String, content: String, extension: String, source: String?): StoredFile =
        saveBytes(category, title, content.toByteArray(Charsets.UTF_8), FileTypes.mimeFor(extension) ?: "text/plain", extension, source)

    override suspend fun saveBytes(category: ContentCategory, title: String, bytes: ByteArray, mime: String, extension: String, source: String?): StoredFile =
        saveLock.withLock {
            withContext(Dispatchers.IO) {
                val ext = SafeFileName.extension(extension)
                val type = FileTypes.byExtension(ext) ?: throw StorageException(ctx.getString(R.string.storage_unsupported_type, category.folder))
                if (!FileTypes.isAllowed(category, ext)) throw StorageException(ctx.getString(R.string.storage_unsupported_type, category.folder))
                if (!FileTypes.looksLike(ext, bytes)) throw StorageException("The data doesn't look like a .$ext file, so it wasn't saved.")
                if (bytes.size > MAX_BYTES) throw StorageException("That file is too large to save (over ${MAX_BYTES / 1024 / 1024} MB).")
                val name = uniqueName(category, title, ext)
                val id = UUID.randomUUID().toString().replace("-", "").take(12)
                val now = clock.instant()

                var stored: StoredFile? = null
                if (wantsPublic()) {
                    stored = try {
                        savePublic(id, category, name, type.mime, bytes, source, now)
                    } catch (e: Exception) {
                        log.w("Storage", "public Documents save failed (${e.javaClass.simpleName}) — using private storage")
                        null
                    }
                }
                if (stored == null) stored = savePrivate(id, category, name, type.mime, bytes, source, now)
                try {
                    index.add(stored)
                } catch (e: Exception) {
                    log.e("Storage", "could not index the saved file (${e.javaClass.simpleName}) — rolling back")
                    runCatching { deleteBacking(stored) }
                    throw StorageException("I couldn't record the file in the library, so I removed it again.")
                }
                stored
            }
        }

    private suspend fun uniqueName(category: ContentCategory, title: String, ext: String): String {
        val tried = HashSet<String>()
        while (true) {
            val candidate = SafeFileName.unique(title, ext, tried)
            if (!index.nameTaken(category, candidate) && !File(File(privateRoot, category.folder), candidate).exists()) return candidate
            tried.add(candidate)
        }
    }

    private fun savePublic(id: String, category: ContentCategory, name: String, mime: String, bytes: ByteArray, source: String?, now: java.time.Instant): StoredFile {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$ROOT_FOLDER/${category.folder}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore returned no URI")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw IOException("could not open the output stream")).use { out -> out.write(bytes); out.flush() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            var actualName = name
            var size = -1L
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { actualName = c.getString(0) ?: name; size = c.getLong(1) }
            }
            if (size != bytes.size.toLong()) throw IOException("short write ($size of ${bytes.size} bytes)")
            return StoredFile(id, actualName, category, uri.toString(), mime, size, now, StorageLocation.PUBLIC_DOCUMENTS, source)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun savePrivate(id: String, category: ContentCategory, name: String, mime: String, bytes: ByteArray, source: String?, now: java.time.Instant): StoredFile {
        val dir = File(privateRoot, category.folder)
        if (!dir.isDirectory && !dir.mkdirs()) throw StorageException("I couldn't create JARVIS's storage folder.")
        val free = try { StatFs(dir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (free < bytes.size + 1_000_000L) throw StorageException(ctx.getString(R.string.storage_full))
        val target = File(dir, name)
        val temp = File(dir, "$name.part")
        try {
            temp.outputStream().use { it.write(bytes); it.flush(); it.fd.sync() }
            if (temp.length() != bytes.size.toLong()) throw IOException("short write")
            if (!temp.renameTo(target)) throw IOException("could not move the file into place")
        } catch (e: IOException) {
            temp.delete(); target.delete()
            throw StorageException(if (e.message?.contains("ENOSPC") == true) ctx.getString(R.string.storage_full) else "I couldn't write the file (${e.message?.take(60) ?: "I/O error"}).")
        }
        return StoredFile(id, name, category, target.absolutePath, mime, target.length(), now, StorageLocation.APP_PRIVATE, source)
    }

    // ------------------------------------------------------------------------------------------------ list / read
    private fun exists(f: StoredFile): Boolean = try {
        if (f.location == StorageLocation.PUBLIC_DOCUMENTS) resolver.query(Uri.parse(f.uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        else File(f.uri).isFile
    } catch (e: SecurityException) { false } catch (e: Exception) { true }   // unknown problem: keep the entry rather than lose track of a file

    override suspend fun list(category: ContentCategory?, query: String?): List<StoredFile> = withContext(Dispatchers.IO) {
        val rows = index.list(category, query)
        val alive = ArrayList<StoredFile>(rows.size)
        for (f in rows) if (exists(f)) alive.add(f) else { index.remove(f.id); log.i("Storage", "a library entry's file was gone — entry removed") }
        alive
    }

    override suspend fun readText(file: StoredFile, maxChars: Int): String = withContext(Dispatchers.IO) {
        if (!file.mime.startsWith("text/") && file.mime != "application/json") throw StorageException("That isn't a text file.")
        val limit = maxChars.coerceIn(1, 1_000_000)
        val stream = if (file.location == StorageLocation.PUBLIC_DOCUMENTS) resolver.openInputStream(Uri.parse(file.uri)) else File(file.uri).inputStream()
        val buf = (stream ?: throw StorageException("I couldn't open that file.")).use { it.readNBytes(limit * 4) }
        val s = String(buf, Charsets.UTF_8)
        if (s.length > limit) s.take(limit) else s
    }

    // ------------------------------------------------------------------------------------------------ delete (explicit user action only)
    private fun deleteBacking(f: StoredFile): Boolean =
        if (f.location == StorageLocation.PUBLIC_DOCUMENTS) { try { resolver.delete(Uri.parse(f.uri), null, null) >= 0 } catch (_: Exception) { false } && !exists(f) }
        else { val file = File(f.uri); (file.delete() || !file.exists()) }

    /** Removes the file AND its library entry. Returns true only if the file is really gone. Reachable only from a confirmed UI action. */
    override suspend fun deletePermanently(file: StoredFile): Boolean = withContext(Dispatchers.IO) {
        val gone = deleteBacking(file)
        if (gone) index.remove(file.id)
        else log.w("Storage", "could not delete a file — its library entry was kept")
        gone
    }

    /** "Clear old files": deletes files older than [days] (optionally only in [category]). Returns how many were really deleted. Explicit UI action only. */
    suspend fun cleanupOlderThan(days: Int, category: ContentCategory? = null): Int = withContext(Dispatchers.IO) {
        if (days < 1) return@withContext 0
        val cutoff = clock.nowMs() - days * 86_400_000L
        var n = 0
        for (f in index.olderThan(cutoff, category)) if (deletePermanently(f)) n++
        n
    }

    /** What [cleanupOlderThan] would delete, without deleting anything: the Library screen shows this before asking for confirmation. */
    suspend fun previewOlderThan(days: Int, category: ContentCategory? = null): List<StoredFile> = withContext(Dispatchers.IO) {
        if (days < 1) emptyList() else index.olderThan(clock.nowMs() - days * 86_400_000L, category)
    }

    /** Total size of everything in the library (for the Library screen header). */
    suspend fun totalBytes(): Long = list(null, null).sumOf { it.sizeBytes }
}
