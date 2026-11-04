package app.banzhan.deepseekcontinuation.data

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Validates share metadata. A sanitized name never grants filesystem access. */
internal object IncomingImportPolicy {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_URI_CHARACTERS = 4096
    private const val MAX_NAME_CHARACTERS = 160
    private val actions = setOf("android.intent.action.SEND", "android.intent.action.VIEW")
    private val mimeTypes = setOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "text/json")
    private val hiddenCharacterTypes = setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt())

    fun validate(action: String?, mimeType: String?, uri: String?): String? {
        if (action !in actions) return "请通过系统分享或打开文件导入聊天记录。"
        if (normalizedMime(mimeType) !in mimeTypes) return "仅支持 ZIP 或 JSON 聊天记录，请选择导出文件后重试。"
        if (uri.isNullOrBlank()) return "没有收到可读取的文件，请重新分享聊天记录。"
        if (uri.length > MAX_URI_CHARACTERS) return "分享的文件地址过长，请重新选择文件后分享。"
        val parsed = try { URI(uri) } catch (error: URISyntaxException) {
            return "分享的文件地址无效，请重新分享聊天记录。"
        }
        if (!parsed.scheme.equals("content", ignoreCase = true)) {
            return "请通过系统文件服务分享 ZIP 或 JSON 文件。"
        }
        val authority = parsed.authority
        if (authority.isNullOrBlank() || parsed.rawUserInfo != null || '@' in authority ||
            authority.any { Character.isISOControl(it) }) {
            return "分享的文件地址无效，请重新分享聊天记录。"
        }
        return null
    }

    fun safeFileName(value: String?, mimeType: String?): String {
        val fallback = if (normalizedMime(mimeType) in setOf("application/json", "text/json")) {
            "分享的聊天记录.json"
        } else "分享的聊天记录.zip"
        val segment = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            var offset = 0
            while (offset < segment.length) {
                val codePoint = segment.codePointAt(offset)
                if (Character.getType(codePoint) !in hiddenCharacterTypes) appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
            }
        }.trim().replace(Regex("^[A-Za-z]:"), "").trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return fallback
        if (cleaned.length <= MAX_NAME_CHARACTERS) return cleaned
        val suffix = when {
            cleaned.endsWith(".json", ignoreCase = true) -> cleaned.takeLast(5)
            cleaned.endsWith(".zip", ignoreCase = true) -> cleaned.takeLast(4)
            else -> ""
        }
        var end = MAX_NAME_CHARACTERS - suffix.length
        if (cleaned[end - 1].isHighSurrogate() && cleaned[end].isLowSurrogate()) end--
        return cleaned.substring(0, end) + suffix
    }

    private fun normalizedMime(value: String?): String = value.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
}
