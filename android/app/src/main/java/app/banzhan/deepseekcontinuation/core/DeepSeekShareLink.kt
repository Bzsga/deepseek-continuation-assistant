package app.banzhan.deepseekcontinuation.core

import java.net.URI

/** A public share identity, deliberately separate from an exported chat-session identity. */
data class DeepSeekShareLink(val id: String, val url: String) {
    companion object {
        private val links = Regex("https?://[^\\s<>\"'“”‘’（）【】]+", RegexOption.IGNORE_CASE)
        private val path = Regex("/share/([A-Za-z0-9_-]{6,128})/?")
        private const val INVALID = "请粘贴一个 DeepSeek 分享链接（https://chat.deepseek.com/share/…）。"

        /** Accepts the normal share-sheet text, including a title surrounding the URL. */
        fun parse(text: String): DeepSeekShareLink {
            require(text.length <= 8192) { "分享文字过长，请只粘贴 DeepSeek 分享链接。" }
            val candidates = links.findAll(text).map { match ->
                match.value.trimEnd('.', ',', '!', '?', ';', ':', '，', '。', '！', '？', '；', '：', '、', ')', ']', '}', '》', '〉')
            }.toList()
            require(candidates.isNotEmpty()) { INVALID }
            val parsed = candidates.map { candidate ->
                val uri = try { URI(candidate) } catch (_: Exception) { throw IllegalArgumentException(INVALID) }
                require(uri.scheme.equals("https", true) && uri.host.equals("chat.deepseek.com", true) &&
                    uri.rawUserInfo == null && uri.port in setOf(-1, 443)) { INVALID }
                val id = path.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
                    ?: throw IllegalArgumentException(INVALID)
                DeepSeekShareLink(id, "https://chat.deepseek.com/share/$id")
            }.distinct()
            require(parsed.size == 1) { "一次只能导入一个分享链接，请分开导入。" }
            return parsed.single()
        }
    }
}
