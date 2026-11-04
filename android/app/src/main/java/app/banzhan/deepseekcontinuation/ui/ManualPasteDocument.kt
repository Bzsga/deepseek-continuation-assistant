package app.banzhan.deepseekcontinuation.ui

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val MANUAL_PASTE_TITLE_PREFIX = "手动粘贴 · "
internal const val MANUAL_PASTE_NOTICE = "来自手动粘贴，仅包含本次提供的内容。请核对消息顺序、角色与完整性。"

@Serializable
internal data class ManualPastedText(
    val rowId: Long,
    val role: String = "unknown",
    val content: String = "",
    val selected: Boolean = false,
)

/** Generates a local import document. UI row IDs and selection never become source identity. */
internal fun manualPasteDocument(title: String, messages: List<ManualPastedText>): ByteArray {
    require(title.isNotBlank()) { "请填写会话标题" }
    val normalizedMessages =
        messages
            .filter { it.content.isNotBlank() }
            .map { message ->
                require(message.role in setOf("user", "assistant", "unknown")) { "请选择有效的消息角色" }
                buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                }
            }
    require(normalizedMessages.isNotEmpty()) { "请至少填写一条非空内容" }
    val messagesJson = JsonArray(normalizedMessages)
    val contentHash =
        MessageDigest.getInstance("SHA-256")
            .digest(messagesJson.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val document = buildJsonObject {
        put("id", "manual-paste-v1-$contentHash")
        put("title", MANUAL_PASTE_TITLE_PREFIX + title.trim())
        put("import_source", "manual-paste-v1")
        put("complete_export", false)
        put("source_note", MANUAL_PASTE_NOTICE)
        put("messages", messagesJson)
    }
    return document.toString().toByteArray(Charsets.UTF_8)
}
