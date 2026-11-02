package com.jarvis.assistant.ui.library

import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.ui.kit.ViewModel
import com.jarvis.core.ports.ContentCategory
import com.jarvis.core.ports.StoredFile
import com.jarvis.core.settings.Keys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Files JARVIS saved (conversations, notes, generated text, images, audio, exports): list, filter, search, delete, clean up. Nothing here deletes without the screen asking the user first. */
class LibraryViewModel(private val app: AppContainer) : ViewModel({ tag, e -> app.appLog.e(tag, "unexpected ${e.javaClass.simpleName}", e) }) {
    data class State(
        val category: ContentCategory? = null,
        val query: String = "",
        val files: List<StoredFile> = emptyList(),
        val loading: Boolean = true,
        val failed: Boolean = false,
        val totalCount: Int = 0,
        val totalBytes: Long = 0,
        val privateStorage: Boolean = false,
    )

    private val _state = MutableStateFlow(State(privateStorage = isPrivate()))
    val state: StateFlow<State> get() = _state
    private var job: Job? = null

    private fun isPrivate() = app.settings.get(Keys.STORAGE_LOCATION, "documents").equals("private", ignoreCase = true)
    private fun update(f: State.() -> State) { _state.value = _state.value.f() }

    init { refresh() }

    fun refresh(delayMs: Long = 0) {
        job?.cancel()
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            update { copy(loading = true, failed = false, privateStorage = isPrivate()) }
            try {
                val s = _state.value
                val shown = app.documents.list(s.category, s.query.ifBlank { null })
                val all = if (s.category == null && s.query.isBlank()) shown else app.documents.list(null, null)
                update { copy(files = shown, loading = false, totalCount = all.size, totalBytes = all.sumOf { it.sizeBytes }) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                app.appLog.w("Library", "could not list files (${e.javaClass.simpleName})")
                update { copy(loading = false, failed = true) }
            }
        }
    }

    fun setCategory(c: ContentCategory?) { if (_state.value.category != c) { update { copy(category = c) }; refresh() } }
    fun setQuery(q: String) { if (_state.value.query != q.trim()) { update { copy(query = q.trim()) }; refresh(250) } }

    /** Permanent. [onDone] gets true only if the file is really gone. */
    fun delete(f: StoredFile, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = try { app.documents.deletePermanently(f) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            refresh()
            onDone(ok)
        }
    }

    fun previewCleanup(days: Int, category: ContentCategory?, onResult: (List<StoredFile>) -> Unit) {
        scope.launch { onResult(try { app.documents.previewOlderThan(days, category) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }) }
    }

    /** Permanent. [onDone] gets (deleted, attempted). */
    fun cleanup(files: List<StoredFile>, onDone: (deleted: Int, attempted: Int) -> Unit) {
        scope.launch {
            var n = 0
            for (f in files) if (try { app.documents.deletePermanently(f) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }) n++
            refresh()
            onDone(n, files.size)
        }
    }

    suspend fun readText(f: StoredFile): String? = try { app.documents.readText(f, 20_000) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
