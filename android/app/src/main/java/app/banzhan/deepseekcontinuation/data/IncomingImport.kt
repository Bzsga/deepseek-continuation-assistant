package app.banzhan.deepseekcontinuation.data

import android.content.Intent
import android.net.Uri
import android.os.Build
import app.banzhan.deepseekcontinuation.core.DeepSeekShareLink
import app.banzhan.deepseekcontinuation.core.ImportResult

enum class IncomingImportStatus {
    AWAITING_CONFIRMATION,
    FETCHING,
    PREVIEW,
    IMPORTING,
    COMPLETED,
    FAILED,
}

data class PendingIncomingImport(
    val id: String,
    val fileName: String,
    val status: IncomingImportStatus = IncomingImportStatus.AWAITING_CONFIRMATION,
    val conversationIds: List<String> = emptyList(),
    val error: String? = null,
    val shareUrl: String? = null,
    val preview: ImportResult? = null,
)

internal data class IncomingImportRequest(
    val uri: String? = null,
    val mimeType: String? = null,
    val error: String? = null,
    val shareUrl: String? = null,
)

/** Documents and public DeepSeek links have separate validation paths; arbitrary text is never fetched. */
internal fun parseIncomingImport(intent: Intent): IncomingImportRequest? {
    if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_VIEW)) return null
    return try {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            else -> {
                if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                    }
                } else {
                    intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                }
            }
        }
        val uriText = uri?.toString()
        if (intent.action == Intent.ACTION_VIEW && uri?.scheme.equals("https", ignoreCase = true)) {
            parseIncomingShare(uriText.orEmpty())
        } else if (intent.action == Intent.ACTION_SEND && uri == null && !intent.hasExtra(Intent.EXTRA_STREAM) &&
            intent.type?.substringBefore(';')?.trim().equals("text/plain", ignoreCase = true)) {
            val text = if (intent.hasExtra(Intent.EXTRA_TEXT)) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                else intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
            parseIncomingShare(text?.toString().orEmpty())
        } else {
            IncomingImportRequest(uriText, intent.type, IncomingImportPolicy.validate(intent.action, intent.type, uriText))
        }
    } catch (_: Exception) {
        IncomingImportRequest(error = "无法读取分享文件，请从文件管理器重新分享 ZIP 或 JSON。")
    }
}

internal fun parseIncomingShare(text: String): IncomingImportRequest = try {
    IncomingImportRequest(shareUrl = DeepSeekShareLink.parse(text).url)
} catch (error: IllegalArgumentException) {
    // Parser errors are fixed product messages, never sender-controlled text or a URL.
    IncomingImportRequest(error = error.message ?: "请粘贴 DeepSeek 的公开分享链接。")
}
