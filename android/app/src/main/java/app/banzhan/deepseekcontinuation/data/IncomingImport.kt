package app.banzhan.deepseekcontinuation.data

import android.content.Intent
import android.net.Uri
import android.os.Build

enum class IncomingImportStatus {
    AWAITING_CONFIRMATION,
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
)

internal data class IncomingImportRequest(
    val uri: String? = null,
    val mimeType: String? = null,
    val error: String? = null,
)

/** Reads only the document URI. Text, nested intents and other sender extras are ignored. */
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
        IncomingImportRequest(uriText, intent.type, IncomingImportPolicy.validate(intent.action, intent.type, uriText))
    } catch (_: Exception) {
        IncomingImportRequest(error = "无法读取分享文件，请从文件管理器重新分享 ZIP 或 JSON。")
    }
}
