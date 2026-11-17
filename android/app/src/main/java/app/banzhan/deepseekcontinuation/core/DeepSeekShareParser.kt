package app.banzhan.deepseekcontinuation.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Normalizes only the public endpoint envelope; the established importer owns message/branch semantics. */
object DeepSeekShareParser {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val INVALID = "分享内容格式暂不支持，请改用 DeepSeek 导出的 ZIP 或 JSON 文件。"
    private val json = Json { ignoreUnknownKeys = true }
    private val extraFragments = setOf("THINK", "TEMPLATE_RESPONSE", "TIP", "CONTENT_RISK", "PARTIAL_FILE_READ",
        "TOOL_SEARCH", "TOOL_OPEN", "TOOL_FIND", "READ_LINK", "TOOL_IMAGE_SEARCH")

    fun parse(bytes: ByteArray, shareId: String): ImportResult {
        require(Regex("[A-Za-z0-9_-]{6,128}").matches(shareId)) { "分享链接无效，请重新复制链接。" }
        require(bytes.isNotEmpty()) { "分享内容为空，请检查链接或改用 ZIP 文件导入。" }
        require(bytes.size <= MAX_BYTES) { "分享内容超过 8 MiB，请改用 ZIP 文件导入。" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw IllegalArgumentException(INVALID) }
        validateNesting(text)
        val root = try { json.parseToJsonElement(text) as? JsonObject }
            catch (_: Exception) { null } ?: throw IllegalArgumentException(INVALID)
        checkSuccess(root, "code")
        val data = root["data"] as? JsonObject ?: root
        checkSuccess(data, "biz_code")
        val source = data["biz_data"] as? JsonObject ?: data
        require(listOf("has_more", "hasMore", "truncated", "is_truncated", "need_load_more").none {
            (source[it] as? JsonPrimitive)?.booleanOrNull == true
        }) { "分享内容尚未完整加载，请改用 ZIP 文件导入。" }
        val messages = source["messages"] as? JsonArray ?: throw IllegalArgumentException(INVALID)
        require(messages.isNotEmpty()) { "这个分享没有可导入的消息，请检查分享范围。" }
        require(messages.size <= 100_000) { "分享消息过多，请改用 ZIP 文件导入。" }
        var hasExtraFragments = false
        messages.forEach { item ->
            val message = item as? JsonObject ?: throw IllegalArgumentException(INVALID)
            val fragments = message["fragments"]
            if (fragments != null && fragments !is JsonNull) {
                require(fragments is JsonArray) { INVALID }
                fragments.forEach { value ->
                    val fragment = value as? JsonObject ?: throw IllegalArgumentException(INVALID)
                    val type = (fragment["type"] as? JsonPrimitive)?.contentOrNull?.uppercase(Locale.ROOT)
                    when (type) {
                        "REQUEST", "RESPONSE" -> require((fragment["content"] as? JsonPrimitive)?.isString == true) { INVALID }
                        "FILE" -> require(fragment["files"] is JsonArray) { INVALID }
                        in extraFragments -> hasExtraFragments = true
                        else -> throw IllegalArgumentException(INVALID)
                    }
                }
            } else {
                require(message.containsKey("content")) { INVALID }
                validateContent(message.getValue("content"))
            }
        }
        val fallbackTitle = messages.asSequence().mapNotNull { it as? JsonObject }.mapNotNull { message ->
            (message["fragments"] as? JsonArray)?.asSequence()?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull.equals("REQUEST", true) }
                ?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: (message["content"] as? JsonPrimitive)?.contentOrNull
        }.firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "DeepSeek 分享的聊天"
        val normalized = JsonObject(source.toMutableMap().apply {
            // A share is a snapshot, never evidence that it is the whole original session.
            put("id", JsonPrimitive("deepseek-public-share:$shareId"))
            if ((get("title") as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) put("title", JsonPrimitive(fallbackTitle))
        })
        val result = try { DeepSeekImporter.parse(normalized.toString().toByteArray(Charsets.UTF_8), "DeepSeek分享.json") }
            catch (_: Exception) { throw IllegalArgumentException(INVALID) }
        val warnings = linkedSetOf("仅导入分享范围内的公开消息，可能不包含完整会话或其他分支；完整记录可用 ZIP 导入。")
        if (hasExtraFragments) warnings += "推理过程、搜索结果及系统提示不纳入续聊正文，原始分享数据会保留在本机。"
        warnings += result.warnings
        return result.copy(warnings = warnings.toList())
    }

    private fun checkSuccess(source: JsonObject, field: String) {
        val value = source[field]?.takeUnless { it is JsonNull } ?: return
        require((value as? JsonPrimitive)?.intOrNull == 0) { "分享不存在、已撤销或暂时无法访问，请检查链接。" }
    }

    private fun validateContent(value: JsonElement) {
        when (value) {
            is JsonNull -> Unit
            is JsonPrimitive -> require(value.isString) { INVALID }
            is JsonArray -> value.forEach(::validateContent)
            is JsonObject -> {
                val type = (value["content_type"] ?: value["type"])?.let { (it as? JsonPrimitive)?.contentOrNull }?.lowercase(Locale.ROOT)
                require(type == null || type in setOf("text", "input_text", "output_text", "request", "response", "multimodal_text",
                    "thinking", "think", "reasoning", "reasoning_content", "analysis", "image", "image_url", "input_image", "file", "audio", "video")) { INVALID }
                if (type == null || type in setOf("text", "input_text", "output_text", "request", "response", "multimodal_text")) {
                    validateContent(value["parts"] ?: value["text"] ?: value["content"] ?: throw IllegalArgumentException(INVALID))
                }
            }
        }
    }

    private fun validateNesting(text: String) {
        var quoted = false
        var escaped = false
        var depth = 0
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) { INVALID } }
                '}', ']' -> { depth--; require(depth >= 0) { INVALID } }
            }
        }
        require(depth == 0 && !quoted) { INVALID }
    }
}
