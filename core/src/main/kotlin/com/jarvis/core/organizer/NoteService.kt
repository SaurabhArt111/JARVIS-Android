package com.jarvis.core.organizer

import com.jarvis.core.ports.Note
import com.jarvis.core.ports.Services
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Notes (port of Backend/Notes.py). The [com.jarvis.core.ports.NoteStore] is the single source of truth for EVERY part of JARVIS,
 * including the chat AI, so nobody has to guess what is in the user's notes.
 *
 *  * Nothing is ever deleted here. Editing keeps the old wording in `history` as `"<changed ISO minute>|<old text>"`.
 *  * A note may be linked to a reminder ([Note.reminderId]); that is how "reschedule the note" knows which reminder to move.
 */
class NoteService(private val services: Services) {
    private val lock = Mutex()
    private val store get() = services.notes

    private fun stamp(): String = LocalDateTime.ofInstant(services.clock.instant(), services.clock.zone()).truncatedTo(ChronoUnit.MINUTES).toString()
    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    /** @throws IllegalArgumentException when [text] is blank (callers ask the user what the note should say). */
    suspend fun add(text: String, reminderId: String? = null): Note = lock.withLock {
        require(text.isNotBlank()) { "A note needs some text." }
        var id = newId()
        while (store.get(id) != null) id = newId()
        val now = services.clock.instant()
        store.insert(Note(id, text.trim(), now, now, reminderId))
    }

    suspend fun get(id: String): Note? = store.get(id)

    /** Oldest first. */
    suspend fun all(): List<Note> = store.all().sortedBy { it.createdAt }

    /** The newest [n] notes, newest first. */
    suspend fun latest(n: Int = 5): List<Note> = all().takeLast(n).reversed()

    suspend fun edit(id: String, text: String): Note? = lock.withLock {
        if (text.isBlank()) return null
        val n = store.get(id) ?: return null
        store.update(n.copy(text = text.trim(), updatedAt = services.clock.instant(), history = n.history + "${stamp()}|${n.text}"))
    }

    /** Adds [extra] after the existing text ("Buy milk. And eggs"). */
    suspend fun append(id: String, extra: String): Note? = lock.withLock {
        if (extra.isBlank()) return null
        val n = store.get(id) ?: return null
        val joined = (n.text.trimEnd('.', ' ') + ". " + extra.trim()).trim()
        store.update(n.copy(text = joined, updatedAt = services.clock.instant(), history = n.history + "${stamp()}|${n.text}"))
    }

    suspend fun linkReminder(noteId: String, reminderId: String): Note? = lock.withLock {
        val n = store.get(noteId) ?: return null
        store.update(n.copy(reminderId = reminderId))
    }

    /** Notes whose text shares words with [query], best match first (ties keep oldest-first order). */
    suspend fun find(query: String): List<Note> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        return all().map { (q intersect words(it.text)).size.toDouble() / q.size to it }
            .filter { it.first > 0 }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    companion object {
        private val STOP = setOf("the", "a", "an", "my", "of", "to", "about", "that", "this", "note", "notes", "for", "on", "at", "in", "and", "is", "it")
        private val WORD = Regex("[a-z0-9]+")
        fun words(s: String?): Set<String> = WORD.findAll((s ?: "").lowercase()).map { it.value }.filter { it !in STOP && it.length > 1 }.toSet()
    }
}
