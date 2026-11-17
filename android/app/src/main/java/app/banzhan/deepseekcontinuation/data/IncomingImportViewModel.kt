package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.banzhan.deepseekcontinuation.core.DeepSeekShareLink
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class IncomingImportReceipt(
    val id: String,
    val uri: String? = null,
    val mimeType: String? = null,
    val fileName: String,
    val status: String = IncomingImportStatus.AWAITING_CONFIRMATION.name,
    val error: String? = null,
    val shareUrl: String? = null,
)

/** The queue and running import belong to the Activity's retained ViewModel, not a composable. */
class IncomingImportViewModel internal constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val readShare: suspend (DeepSeekShareLink) -> DeepSeekShareDocument,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) :
        this(application, savedState, { DeepSeekShareReader().read(it) })

    private val json = Json { ignoreUnknownKeys = true }
    private var receipts = restoreReceipts()
    private var initialIntentHandled = savedState.get<Boolean>(INITIAL_HANDLED) ?: false
    private var running: Job? = null
    private var shareDocument: Pair<String, DeepSeekShareDocument>? = null

    var pending by mutableStateOf(receipts.firstOrNull()?.asPending())
        private set

    fun receiveInitial(intent: Intent, restored: Boolean): String? {
        if (initialIntentHandled || restored) {
            initialIntentHandled = true
            savedState[INITIAL_HANDLED] = true
            return null
        }
        initialIntentHandled = true
        savedState[INITIAL_HANDLED] = true
        return receive(intent)
    }

    /** Every genuine onNewIntent is a new user action, even when it shares the same URI. */
    fun receive(intent: Intent): String? {
        val request = parseIncomingImport(intent) ?: return null
        return enqueue(request)
    }

    /** Pasted text is normalized before it enters saved state. */
    fun receiveShare(text: String): String? {
        val request = parseIncomingShare(text)
        if (request.error != null) return request.error
        return enqueue(request)
    }

    private fun enqueue(request: IncomingImportRequest): String? {
        if (receipts.size >= MAX_QUEUED) return "待导入内容较多，请先处理当前内容，再重新分享。"
        val receipt = IncomingImportReceipt(
            id = UUID.randomUUID().toString(),
            uri = request.uri.takeIf { request.error == null },
            mimeType = request.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).takeIf { request.error == null },
            fileName = if (request.shareUrl == null) "聊天文件" else "DeepSeek 分享的聊天",
            status = if (request.error == null) IncomingImportStatus.AWAITING_CONFIRMATION.name else IncomingImportStatus.FAILED.name,
            error = request.error,
            shareUrl = request.shareUrl.takeIf { request.error == null },
        )
        receipts = receipts + receipt
        persist()
        if (pending == null) pending = receipt.asPending()
        return null
    }

    fun confirm(id: String, controller: AppController) {
        val receipt = receipts.firstOrNull()?.takeIf { it.id == id } ?: return
        val retryShare = receipt.status == IncomingImportStatus.FAILED.name && receipt.shareUrl != null
        if (!retryShare && receipt.status !in setOf(IncomingImportStatus.AWAITING_CONFIRMATION.name, IncomingImportStatus.PREVIEW.name)) return
        val state = controller.state.value
        if (state.busy || state.tasks.any { it.status in setOf("queued", "running") }) return
        if (receipt.shareUrl != null) {
            if (receipt.status == IncomingImportStatus.PREVIEW.name) saveShare(receipt, controller)
            else fetchShare(receipt)
            return
        }
        update(receipt.copy(status = IncomingImportStatus.IMPORTING.name, error = null))
        running = viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    readIncomingImport(getApplication(), requireNotNull(receipt.uri), receipt.mimeType)
                }
                ensureActive()
                if (!isCurrent(id)) return@launch
                update(receipt.copy(status = IncomingImportStatus.IMPORTING.name, fileName = document.fileName))
                val conversationIds = controller.importFile(document.bytes, document.fileName)
                val completed = receipt.copy(fileName = document.fileName, status = IncomingImportStatus.COMPLETED.name)
                update(completed, conversationIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is IncomingImportReadException -> error.message
                    is IllegalArgumentException -> "文件内容无效或不受支持，请分享 DeepSeek 导出的 ZIP 或 JSON。"
                    else -> "无法导入分享文件，请重新分享 DeepSeek 导出的 ZIP 或 JSON。"
                }
                update(receipt.copy(status = IncomingImportStatus.FAILED.name, error = message))
            }
        }
    }

    private fun fetchShare(receipt: IncomingImportReceipt) {
        // Explicit retries always fetch a fresh snapshot; a failed save must not reuse an old preview.
        shareDocument = null
        update(receipt.copy(status = IncomingImportStatus.FETCHING.name, error = null))
        running = viewModelScope.launch {
            try {
                val document = readShare(DeepSeekShareLink.parse(requireNotNull(receipt.shareUrl)))
                ensureActive()
                if (!isCurrent(receipt.id)) return@launch
                shareDocument = receipt.id to document
                update(receipt.copy(status = IncomingImportStatus.PREVIEW.name, error = null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update(receipt.copy(status = IncomingImportStatus.FAILED.name,
                    error = when (error) {
                        is DeepSeekShareReadException, is IllegalArgumentException -> error.message
                        else -> "无法读取分享链接，请检查网络或确认分享仍然有效，也可以改用 ZIP 导入。"
                    }))
            }
        }
    }

    private fun saveShare(receipt: IncomingImportReceipt, controller: AppController) {
        val document = shareDocument?.takeIf { it.first == receipt.id }?.second
        if (document == null) {
            update(receipt.copy(status = IncomingImportStatus.AWAITING_CONFIRMATION.name))
            return
        }
        update(receipt.copy(status = IncomingImportStatus.IMPORTING.name, error = null))
        running = viewModelScope.launch {
            try {
                val ids = controller.importShare(document, DeepSeekShareLink.parse(requireNotNull(receipt.shareUrl)))
                shareDocument = null
                update(receipt.copy(status = IncomingImportStatus.COMPLETED.name), ids)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update(receipt.copy(status = IncomingImportStatus.FAILED.name,
                    error = "这次未能保存分享内容，请重试；也可以改用 ZIP 导入。"))
            }
        }
    }

    fun consume(id: String) {
        val first = receipts.firstOrNull() ?: return
        if (first.id != id || first.status == IncomingImportStatus.IMPORTING.name) return
        running?.cancel()
        running = null
        shareDocument = null
        receipts = receipts.drop(1)
        persist()
        pending = receipts.firstOrNull()?.asPending()
    }

    private fun update(receipt: IncomingImportReceipt, conversationIds: List<String> = emptyList()) {
        if (!isCurrent(receipt.id)) return
        receipts = listOf(receipt) + receipts.drop(1)
        persist()
        pending = receipt.asPending().copy(conversationIds = conversationIds)
    }

    private fun isCurrent(id: String) = receipts.firstOrNull()?.id == id

    private fun persist() {
        // Only bounded URI/link metadata is saved. Bodies, snapshots and result IDs stay out of Bundles.
        savedState[RECEIPTS] = json.encodeToString(receipts)
    }

    private fun restoreReceipts(): List<IncomingImportReceipt> {
        val serialized = savedState.get<String>(RECEIPTS) ?: return emptyList()
        return try {
            json.decodeFromString<List<IncomingImportReceipt>>(serialized).take(MAX_QUEUED).mapNotNull { receipt ->
                when (receipt.status) {
                    IncomingImportStatus.COMPLETED.name -> null
                    IncomingImportStatus.FETCHING.name, IncomingImportStatus.PREVIEW.name -> receipt.copy(
                        status = IncomingImportStatus.AWAITING_CONFIRMATION.name, error = null,
                    )
                    IncomingImportStatus.IMPORTING.name -> receipt.copy(
                        status = IncomingImportStatus.FAILED.name,
                        error = "上次导入被中断。已保存的会话可在首页查看，如有缺失，请重新分享。",
                    )
                    else -> receipt
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun IncomingImportReceipt.asPending() = PendingIncomingImport(
        id = id,
        fileName = fileName,
        status = IncomingImportStatus.entries.firstOrNull { it.name == status } ?: IncomingImportStatus.FAILED,
        error = error,
        shareUrl = shareUrl,
        preview = shareDocument?.takeIf { it.first == id }?.second?.result,
    )

    private companion object {
        const val MAX_QUEUED = 16
        const val INITIAL_HANDLED = "incoming_import_initial_handled"
        const val RECEIPTS = "incoming_import_receipts"
    }
}
