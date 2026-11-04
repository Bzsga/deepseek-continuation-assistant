package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Display safe, actionable categories; the existing redacted diagnostics stay available on demand. */
internal fun organizationFailureSummary(message: String): String {
    val description = message.substringBefore("diagnostic=").trim()
    val codes = Regex("^\\s*(?:\\[[a-z_]+\\]\\s*)+").find(description)?.value.orEmpty()
    val plain = description.removePrefix(codes).trim()
    val httpStatus = Regex("\"http_status\"\\s*:\\s*([1-5][0-9]{2})")
        .find(message.substringAfter("diagnostic=", ""))?.groupValues?.get(1)?.toIntOrNull()
    return when {
        "本次未调用模型" in plain -> plain
        httpStatus in setOf(401, 403) -> "模型服务拒绝访问（HTTP $httpStatus）。请联系服务提供者核对配置与访问权限，已完成部分会保留。"
        httpStatus != null && httpStatus >= 500 -> "模型服务暂时不可用（HTTP $httpStatus），这段尚未整理完成。可稍后继续，已完成部分会保留。"
        "[output_limit]" in codes -> "模型回复达到输出上限，这段尚未整理完成。已完成部分会保留。"
        "[transport_timeout]" in codes || "[runner_timeout]" in codes || "总时限" in description ->
            "这次请求超时，这段尚未整理完成。可稍后继续，已完成部分会保留。"
        "[envelope_json]" in codes -> "模型服务返回了无法读取的响应，这段尚未整理完成。已完成部分会保留。"
        "[json]" in codes -> "模型回复没有形成完整的整理结果，这段未能保存。已完成部分会保留。"
        listOf("[schema]", "[unknown_source]", "[content_shape]").any { it in codes } ->
            "模型回复未通过格式或来源检查，这段未能保存。已完成部分会保留。"
        "[transport]" in codes || "[gateway_error]" in codes ->
            "暂未从模型服务取得可用结果。可稍后继续，已完成部分会保留。"
        else -> plain.takeIf {
            it.isNotBlank() && it.length <= 240 && !it.contains('{') && !it.contains("<html", ignoreCase = true)
        } ?: "这次整理未能完成，已完成部分会保留。可展开技术详情查看错误信息。"
    }
}

@Composable
internal fun OrganizationFailureReason(message: String) {
    var expanded by remember(message) { mutableStateOf(false) }
    val summary = remember(message) { organizationFailureSummary(message) }
    Text(summary, style = MaterialTheme.typography.bodyMedium)
    if (summary != message.trim()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起技术详情" else "查看技术详情")
        }
        if (expanded) SelectionContainer {
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
