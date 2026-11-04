package app.banzhan.deepseekcontinuation.ui

import android.app.Application
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ManualPasteSnapshot(val title: String, val messages: List<ManualPastedText>)

/** Atomic private storage keeps large drafts out of Activity Bundles and survives process death. */
internal class ManualPasteDraft(application: Application) : AndroidViewModel(application) {
    var title by mutableStateOf("")
        private set

    var messages by mutableStateOf(listOf(ManualPastedText(0)))
        private set

    var loaded by mutableStateOf(false)
        private set

    var resetting by mutableStateOf(false)
        private set

    var persistenceError by mutableStateOf<String?>(null)
        private set

    private val file = AtomicFile(File(application.filesDir, "manual-paste-draft.json"))
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val fileMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var saveJob: Job? = null
    private var nextId = 1L

    init {
        viewModelScope.launch {
            try {
                val snapshot =
                    withContext(Dispatchers.IO) {
                        fileMutex.withLock {
                            try {
                                file.openRead().bufferedReader(Charsets.UTF_8).use {
                                    json.decodeFromString<ManualPasteSnapshot>(it.readText())
                                }
                            } catch (_: java.io.FileNotFoundException) {
                                null
                            }
                        }
                    }
                if (snapshot != null) {
                    title = snapshot.title
                    messages = snapshot.messages.ifEmpty { listOf(ManualPastedText(0)) }
                    nextId = (messages.maxOfOrNull { it.rowId } ?: 0) + 1
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                persistenceError = "无法恢复粘贴草稿。${error.message.orEmpty()}"
            } finally {
                loaded = true
                ready.complete(Unit)
            }
        }
    }

    fun updateTitle(value: String) {
        if (!loaded || resetting) return
        title = value
        scheduleSave()
    }

    fun addMessage() {
        if (!loaded || resetting) return
        messages = messages + ManualPastedText(nextId++)
        scheduleSave()
    }

    fun updateMessage(updated: ManualPastedText) {
        if (!loaded || resetting) return
        messages = messages.map { if (it.rowId == updated.rowId) updated else it }
        scheduleSave()
    }

    fun deleteSelected() {
        if (!loaded || resetting) return
        messages = messages.filterNot { it.selected }.ifEmpty { listOf(ManualPastedText(nextId++)) }
        scheduleSave()
    }

    fun flush() {
        if (
            loaded && !resetting && (title.isNotEmpty() || messages.any { it.content.isNotEmpty() })
        )
            scheduleSave(0)
    }

    /**
     * The optional action runs with autosave stopped, so clearing app data cannot resurrect a
     * draft.
     */
    suspend fun reset(beforeReset: suspend () -> Unit = {}) {
        ready.await()
        resetting = true
        try {
            saveJob?.cancelAndJoin()
            beforeReset()
            withContext(Dispatchers.IO) { fileMutex.withLock { file.delete() } }
            title = ""
            messages = listOf(ManualPastedText(0))
            nextId = 1L
            persistenceError = null
        } finally {
            resetting = false
        }
    }

    private fun scheduleSave(delayMillis: Long = 250) {
        val snapshot = ManualPasteSnapshot(title, messages.toList())
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                try {
                    delay(delayMillis)
                    withContext(Dispatchers.IO) {
                        fileMutex.withLock {
                            currentCoroutineContext().ensureActive()
                            val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
                            val stream = file.startWrite()
                            try {
                                stream.write(bytes)
                                file.finishWrite(stream)
                            } catch (error: Exception) {
                                file.failWrite(stream)
                                throw error
                            }
                        }
                    }
                    persistenceError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    persistenceError = "草稿尚未保存，请保留当前页面。${error.message.orEmpty()}"
                }
            }
    }
}
