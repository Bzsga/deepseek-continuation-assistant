package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
)

/** The queue and running import belong to the Activity's retained ViewModel, not a composable. */
class IncomingImportViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val json = Json { ignoreUnknownKeys = true }
    private var receipts = restoreReceipts()
    private var initialIntentHandled = savedState.get<Boolean>(INITIAL_HANDLED) ?: false

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
        if (receipts.size >= MAX_QUEUED) return "待导入文件较多，请先处理当前文件，再重新分享。"
        val receipt = IncomingImportReceipt(
            id = UUID.randomUUID().toString(),
            uri = request.uri.takeIf { request.error == null },
            mimeType = request.mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).takeIf { request.error == null },
            fileName = "聊天文件",
            status = if (request.error == null) IncomingImportStatus.AWAITING_CONFIRMATION.name else IncomingImportStatus.FAILED.name,
            error = request.error,
        )
        receipts = receipts + receipt
        persist()
        if (pending == null) pending = receipt.asPending()
        return null
    }

    fun confirm(id: String, controller: AppController) {
        val receipt = receipts.firstOrNull()?.takeIf { it.id == id } ?: return
        if (receipt.status != IncomingImportStatus.AWAITING_CONFIRMATION.name) return
        val state = controller.state.value
        if (state.busy || state.tasks.any { it.status in setOf("queued", "running") }) return
        update(receipt.copy(status = IncomingImportStatus.IMPORTING.name, error = null))
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    readIncomingImport(getApplication(), requireNotNull(receipt.uri), receipt.mimeType)
                }
                update(receipts.first().copy(fileName = document.fileName))
                val conversationIds = controller.importFile(document.bytes, document.fileName)
                val completed = receipts.first().copy(status = IncomingImportStatus.COMPLETED.name)
                update(completed, conversationIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is IncomingImportReadException -> error.message
                    is IllegalArgumentException -> "文件内容无效或不受支持，请分享 DeepSeek 导出的 ZIP 或 JSON。"
                    else -> "无法导入分享文件，请重新分享 DeepSeek 导出的 ZIP 或 JSON。"
                }
                update(receipts.first().copy(status = IncomingImportStatus.FAILED.name, error = message))
            }
        }
    }

    fun consume(id: String) {
        val first = receipts.firstOrNull() ?: return
        if (first.id != id || first.status == IncomingImportStatus.IMPORTING.name) return
        receipts = receipts.drop(1)
        persist()
        pending = receipts.firstOrNull()?.asPending()
    }

    private fun update(receipt: IncomingImportReceipt, conversationIds: List<String> = emptyList()) {
        receipts = listOf(receipt) + receipts.drop(1)
        persist()
        pending = receipt.asPending().copy(conversationIds = conversationIds)
    }

    private fun persist() {
        // Only bounded URI metadata is saved. Conversation bodies, bytes and result IDs stay out of Bundles.
        savedState[RECEIPTS] = json.encodeToString(receipts)
    }

    private fun restoreReceipts(): List<IncomingImportReceipt> {
        val serialized = savedState.get<String>(RECEIPTS) ?: return emptyList()
        return try {
            json.decodeFromString<List<IncomingImportReceipt>>(serialized).take(MAX_QUEUED).mapNotNull { receipt ->
                when (receipt.status) {
                    IncomingImportStatus.COMPLETED.name -> null
                    IncomingImportStatus.IMPORTING.name -> receipt.copy(
                        status = IncomingImportStatus.FAILED.name,
                        error = "上次导入被中断。已保存的会话可在首页查看，如有缺失，请重新分享文件。",
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
    )

    private companion object {
        const val MAX_QUEUED = 16
        const val INITIAL_HANDLED = "incoming_import_initial_handled"
        const val RECEIPTS = "incoming_import_receipts"
    }
}
