package com.jarvis.assistant.platform

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.jarvis.core.ports.StoredFile

/**
 * Opening and sharing files JARVIS saved. Only URIs that JARVIS itself produced are accepted ([JarvisFileProvider.safeUriFor]); other apps get a read-only,
 * one-off permission for exactly that file (FLAG_GRANT_READ_URI_PERMISSION), never a file path.
 */
object FileActions {
    enum class Result { OK, NO_FILE, NO_APP }

    fun open(a: Activity, stored: String, mime: String): Result {
        val uri = JarvisFileProvider.safeUriFor(a, stored) ?: return Result.NO_FILE
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("file", uri)
        return try { a.startActivity(Intent.createChooser(i, null)); Result.OK } catch (e: Exception) { Result.NO_APP }
    }

    fun share(a: Activity, f: StoredFile): Result = share(a, f.uri, f.mime, f.name)

    fun share(a: Activity, stored: String, mime: String, name: String): Result {
        val uri = JarvisFileProvider.safeUriFor(a, stored) ?: return Result.NO_FILE
        val i = Intent(Intent.ACTION_SEND).setType(mime.ifBlank { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(name, uri)
        return try { a.startActivity(Intent.createChooser(i, null)); Result.OK } catch (e: Exception) { Result.NO_APP }
    }

    /** Copies a saved file to a place the user picked with the system file dialog (ACTION_CREATE_DOCUMENT). Blocking: call from a background thread. */
    fun copyTo(a: Activity, stored: String, dest: Uri): Boolean {
        val src = JarvisFileProvider.safeUriFor(a, stored) ?: return false
        return try {
            a.contentResolver.openInputStream(src)?.use { input -> a.contentResolver.openOutputStream(dest, "w")?.use { out -> input.copyTo(out); out.flush(); true } } ?: false
        } catch (e: Exception) { false }
    }

    fun shareText(a: Activity, text: String): Boolean = try {
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)); true
    } catch (e: Exception) { false }

    /** Opens a web link in the browser. Only http and https are accepted: anything else (intent:, file:, javascript:...) is refused. */
    fun openLink(a: Activity, url: String): Result {
        val u = try { Uri.parse(url.trim()) } catch (e: Exception) { return Result.NO_FILE }
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.NO_FILE
        return try { a.startActivity(Intent(Intent.ACTION_VIEW, u)); Result.OK } catch (e: Exception) { Result.NO_APP }
    }
}
