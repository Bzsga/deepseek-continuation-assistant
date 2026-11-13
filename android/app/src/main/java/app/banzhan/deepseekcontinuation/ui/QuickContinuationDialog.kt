@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun QuickContinuationDialog(
    archive: Archive,
    conversations: List<ImportedConversation>,
    onDismiss: () -> Unit,
    onCopyAndOpen: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    var result by remember(archive, conversations) { mutableStateOf<Result<ExportResult>?>(null) }
    var previewLimit by remember(archive.id) { mutableIntStateOf(4000) }
    LaunchedEffect(archive, conversations) {
        result = withContext(Dispatchers.Default) {
            runCatching { QuickContinuation.create(archive, conversations) }
        }
    }
    val text = result?.getOrNull()?.text
    val canCopy = text != null && text.toByteArray(Charsets.UTF_8).size <= 60 * 1024
    AppDialog("先快速接续", onDismiss, actions = {
        ActionRow {
            TextButton(onClick = onDismiss) { Text("返回") }
            if (text != null) {
                OutlinedButton(onClick = { onSave(text) }) { Text("保存文件") }
                if (canCopy) Button(onClick = { onCopyAndOpen(text) }) { Text("复制并打开 DeepSeek") }
            }
        }
    }) {
        Text("本机准备，无需等待 AI。使用最近原话和已有档案，完整整理仍可稍后进行。",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        when {
            result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            text == null -> Text(result?.exceptionOrNull()?.message ?: "当前没有可用内容。",
                color = MaterialTheme.colorScheme.error)
            else -> {
                if (!canCopy) Text("内容较长，请保存文件后在 DeepSeek 添加。")
                if ((result?.getOrNull()?.omittedCount ?: 0) > 0)
                    Text("这是精简的快速版，完整资料仍保留在本机。", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(text.take(previewLimit), style = MaterialTheme.typography.bodyMedium) }
                if (text.length > previewLimit) {
                    Text("预览前 $previewLimit 字符，复制和保存使用同一份完整内容。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { previewLimit = (previewLimit + 4000).coerceAtMost(text.length) }) {
                        Text("继续查看")
                    }
                }
            }
        }
    }
}
