package com.jarvis.assistant.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.jarvis.core.util.JarvisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app's SQLite database. Never silently loses data: a corrupt file is RENAMED (`jarvis.db.corrupt-<time>`) and a fresh database is created, so the
 * user (or a support session) can still recover it. All access is serialised through [run] on the IO dispatcher.
 */
class Db(private val context: Context, private val log: JarvisLog = JarvisLog.None, private val name: String = Sql.DB_NAME) {

    private val lock = Any()

    /** Called instead of Android's default handler, which would DELETE a corrupt database. */
    private val onCorruption = DatabaseErrorHandler { db ->
        val path = db.path
        log.e("Db", "database is corrupt — keeping a copy and starting fresh")
        quarantine(path)
    }

    private class Helper(context: Context, name: String, handler: DatabaseErrorHandler) : SQLiteOpenHelper(context, name, null, Sql.VERSION, handler) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            for (stmt in Sql.CREATE) db.execSQL(stmt)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            for (v in oldVersion until newVersion) for (stmt in Sql.MIGRATIONS.getOrNull(v).orEmpty()) db.execSQL(stmt)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // A newer app version wrote this file. Opening it with older code could damage it: refuse, the caller quarantines it.
            throw SQLiteException("database version $oldVersion is newer than this app ($newVersion)")
        }
    }

    private var helper: Helper? = null
    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase {
        db?.takeIf { it.isOpen }?.let { return it }
        val h = helper ?: Helper(context, name, onCorruption).also { helper = it }
        return try {
            h.writableDatabase.also { db = it }
        } catch (e: SQLiteException) {
            log.e("Db", "could not open the database (${e.javaClass.simpleName}) — moving it aside")
            h.close(); helper = null
            quarantine(context.getDatabasePath(name).path)
            val fresh = Helper(context, name, onCorruption).also { helper = it }
            fresh.writableDatabase.also { db = it }
        }
    }

    private fun quarantine(path: String) {
        try {
            val f = File(path)
            if (f.exists()) f.renameTo(File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}"))
            for (suffix in listOf("-wal", "-shm", "-journal")) File(path + suffix).delete()
        } catch (e: Exception) {
            log.e("Db", "could not move the corrupt database (${e.javaClass.simpleName})")
        }
    }

    /** Runs [block] with the open database on the IO dispatcher. One caller at a time. */
    suspend fun <T> run(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) { synchronized(lock) { block(open()) } }

    /** [run] inside a transaction. */
    suspend fun <T> transaction(block: (SQLiteDatabase) -> T): T = run { d ->
        d.beginTransaction()
        try { block(d).also { d.setTransactionSuccessful() } } finally { d.endTransaction() }
    }

    /** Blocking variant for code that cannot suspend (receivers using goAsync, notification actions). Never call on the main thread for large work. */
    fun <T> runBlockingIo(block: (SQLiteDatabase) -> T): T = synchronized(lock) { block(open()) }

    fun close() = synchronized(lock) { db?.close(); db = null; helper?.close(); helper = null }
}
