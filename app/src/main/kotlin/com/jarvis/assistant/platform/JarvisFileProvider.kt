package com.jarvis.assistant.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.jarvis.core.storage.FileTypes
import java.io.File
import java.io.FileNotFoundException

/**
 * A minimal read-only file provider (the AndroidX FileProvider is not used in this project). It serves ONLY files under JARVIS's own private storage
 * (<files>/documents, where DocumentStoreAndroid keeps files when shared Documents storage isn't available), so a file saved in the private fallback can be opened, shared or exported by another app
 * with a temporary grant. Path traversal is blocked by comparing canonical paths; nothing is writable; no listing.
 *
 * URI form:  content://<applicationId>.files/<root>/<relative path>   with <root> = "files".
 */
class JarvisFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File? {
        val ctx = context ?: return null
        val segs = uri.pathSegments
        if (segs.size < 2) return null
        val root = rootFor(ctx, segs[0]) ?: return null
        val f = File(root, segs.drop(1).joinToString("/"))
        return try {
            val canonRoot = root.canonicalPath + File.separator
            f.canonicalFile.takeIf { it.path.startsWith(canonRoot) && it.isFile }
        } catch (_: Exception) { null }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        val f = resolve(uri) ?: throw FileNotFoundException("not found")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection?.takeIf { it.isNotEmpty() } ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { c -> when (c) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }
        return MatrixCursor(cols).apply { addRow(row.toTypedArray()) }
    }

    override fun getType(uri: Uri): String? = resolve(uri)?.let { FileTypes.mimeFor(it.extension) ?: "application/octet-stream" }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.files"

        private fun rootFor(ctx: Context, name: String): File? = when (name) {
            "files" -> File(ctx.filesDir, "documents")
            else -> null
        }

        /** content:// URI for a private file path, or null when [file] is not inside one of the served roots. */
        fun uriForPrivateFile(ctx: Context, file: File): Uri? {
            val canon = try { file.canonicalFile } catch (_: Exception) { return null }
            for (name in listOf("files")) {
                val root = rootFor(ctx, name)?.let { try { it.canonicalFile } catch (_: Exception) { null } } ?: continue
                val prefix = root.path + File.separator
                if (canon.path.startsWith(prefix)) {
                    val b = Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(name)
                    canon.path.removePrefix(prefix).split(File.separatorChar).forEach { b.appendPath(it) }
                    return b.build()
                }
            }
            return null
        }

        /**
         * Accepts only what JARVIS itself saved: a MediaStore URI (public Documents), a URI of this provider, or a private file path. Anything else -> null.
         */
        fun safeUriFor(ctx: Context, stored: String): Uri? {
            if (stored.startsWith("content://")) {
                val u = Uri.parse(stored)
                return if (u.authority == MediaStore.AUTHORITY || u.authority == authority(ctx)) u else null
            }
            if (stored.startsWith("/")) return uriForPrivateFile(ctx, File(stored))
            return null
        }
    }
}
