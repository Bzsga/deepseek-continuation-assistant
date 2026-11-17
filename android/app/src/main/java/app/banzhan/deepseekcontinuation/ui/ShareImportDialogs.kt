package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.core.DeepSeekShareLink
import app.banzhan.deepseekcontinuation.core.ImportResult

@Composable
internal fun ShareLinkInputDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入分享链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在 DeepSeek 中分享想保留的消息，再把链接粘贴到这里。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 8192) { text = it; error = null } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DeepSeek 分享链接") },
                    placeholder = { Text("https://chat.deepseek.com/share/…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2,
                    maxLines = 4,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                )
                Text("分享链接可被持有链接的人查看。App 会直接从 DeepSeek 读取，预览确认后保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                try {
                    val link = DeepSeekShareLink.parse(text)
                    onRead(link.url)
                } catch (invalid: IllegalArgumentException) {
                    error = invalid.message ?: "请粘贴有效的 DeepSeek 分享链接。"
                }
            }) { Text("读取聊天") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ShareImportPreviewDialog(
    result: ImportResult,
    blocked: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val messageCount = result.conversations.flatMap { it.branches }.flatMap { it.messages }.distinctBy { it.id }.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入 · $messageCount 条消息") },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("这里只包含链接中分享的内容，未必是完整对话。确认后保存在本机，不消耗整理次数。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (result.warnings.isNotEmpty()) item {
                    Text(result.warnings.distinct().joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result.conversations.forEach { conversation ->
                    item { Text(conversation.title, fontWeight = FontWeight.SemiBold) }
                    conversation.branches.forEachIndexed { index, branch ->
                        if (conversation.branches.size > 1) item {
                            Text("聊天版本 ${index + 1}" + if (branch.isCurrent) " · 当前" else "",
                                style = MaterialTheme.typography.labelLarge)
                        }
                        items(branch.messages) { message ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(roleLabel(message.role), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodyMedium) }
                                if (message.attachments.isNotEmpty()) Text(message.attachments.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (blocked) item { Text("当前操作完成后可保存。", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !blocked) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun IncomingImportFailureDialog(
    error: String?,
    isShareLink: Boolean,
    blocked: Boolean,
    onRetry: () -> Unit,
    onChooseFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这次没有导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error ?: "请重新分享聊天 ZIP，或在下载目录选择文件。")
                if (isShareLink) TextButton(onClick = onChooseFile) { Text("改用文件导入") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (isShareLink) onRetry else onChooseFile,
                enabled = !isShareLink || !blocked,
            ) { Text(if (isShareLink) "重试链接" else "重新选择文件") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
