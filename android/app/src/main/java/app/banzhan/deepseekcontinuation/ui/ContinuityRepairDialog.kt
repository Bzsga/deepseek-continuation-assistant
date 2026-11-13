@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ContinuityRepair
import app.banzhan.deepseekcontinuation.core.ContinuityRepairIssue
import app.banzhan.deepseekcontinuation.core.ExportResult
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional local repair; pasted replies and drafts are not persisted or sent to an API. */
@Composable
internal fun ContinuityRepairDialog(
    archive: Archive,
    conversations: List<ImportedConversation>,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var issue by remember(archive.id) { mutableStateOf(ContinuityRepairIssue.MISSING_BACKGROUND) }
    var clarification by remember(archive.id) { mutableStateOf("") }
    var observedReply by remember(archive.id) { mutableStateOf("") }
    var showExtra by remember(archive.id) { mutableStateOf(false) }
    var result by remember(archive, conversations) { mutableStateOf<ExportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val currentArchive by rememberUpdatedState(archive)
    val currentConversations by rememberUpdatedState(conversations)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val validInput = clarification.length <= ContinuityRepair.MAX_CLARIFICATION_CHARACTERS &&
        observedReply.length <= ContinuityRepair.MAX_REPLY_CHARACTERS

    fun changed() {
        result = null
        error = null
        copied = false
    }

    fun leaveInput() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    fun dismiss() {
        leaveInput()
        onDismiss()
    }

    Dialog(
        onDismissRequest = ::dismiss,
        // Receive window/IME insets in Compose instead of letting the dialog pan over its actions.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                .padding(12.dp).testTag("repair-dialog-safe-area"),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                // Insets are applied once above. Short content wraps; longer content scrolls
                // within the available height, keeping both title and actions visible.
                Modifier.widthIn(max = 680.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.85f).testTag("repair-dialog-surface"),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("没接上，调整一下", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    LazyColumn(Modifier.weight(1f, fill = false).testTag("repair-dialog-body"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("选一种情况，从已有资料准备一小段修正内容，带回正在聊的新会话。",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("在本机完成，不消耗整理次数。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ContinuityRepairIssue.entries.forEach { choice ->
                                    FilterChip(
                                        selected = issue == choice,
                                        enabled = !preparing,
                                        onClick = { issue = choice; changed() },
                                        label = { Text(choice.title) },
                                    )
                                }
                            }
                        }
                        item {
                            TextButton(onClick = {
                                if (showExtra) leaveInput()
                                showExtra = !showExtra
                            }, enabled = !preparing) {
                                Text(if (showExtra) "收起补充" else "补充哪里没接上（可选）")
                            }
                        }
                        if (showExtra) {
                            item {
                                OutlinedTextField(
                                    value = clarification,
                                    onValueChange = { clarification = it; changed() },
                                    enabled = !preparing,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("你现在想补充或纠正的内容") },
                                    placeholder = { Text("例如：这件事已经决定了，我想继续聊下一步。") },
                                    minLines = 2, maxLines = 5,
                                    isError = clarification.length > ContinuityRepair.MAX_CLARIFICATION_CHARACTERS,
                                    supportingText = { Text("${clarification.length} / ${ContinuityRepair.MAX_CLARIFICATION_CHARACTERS}") },
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = observedReply,
                                    onValueChange = { observedReply = it; changed() },
                                    enabled = !preparing,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("贴回新会话的回复（可选）") },
                                    minLines = 2, maxLines = 5,
                                    isError = observedReply.length > ContinuityRepair.MAX_REPLY_CHARACTERS,
                                    supportingText = { Text("${observedReply.length} / ${ContinuityRepair.MAX_REPLY_CHARACTERS} · 关闭后不保留") },
                                )
                            }
                        }
                        if (preparing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                        result?.let { prepared ->
                            item {
                                Text("修正内容预览", style = MaterialTheme.typography.titleMedium)
                                if (prepared.omittedCount > 0) Text("仅带少量相关材料，原档案保持完整。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item {
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    SelectionContainer {
                                        Text(prepared.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (copied) item {
                            Text("已复制。回到刚才的新会话粘贴并发送，继续当前对话。",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    FlowRow(Modifier.testTag("repair-dialog-actions"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = ::dismiss) { Text("关闭") }
                        if (result == null) Button(
                            enabled = !preparing && validInput,
                            onClick = {
                                leaveInput()
                                preparing = true
                                error = null
                                val capturedArchive = archive
                                val capturedConversations = conversations
                                val capturedIssue = issue
                                val capturedClarification = clarification
                                val capturedReply = observedReply
                                scope.launch {
                                    try {
                                        val prepared = withContext(Dispatchers.Default) {
                                            ContinuityRepair.create(capturedArchive, capturedConversations, capturedIssue,
                                                capturedClarification, capturedReply)
                                        }
                                        if (currentArchive == capturedArchive && currentConversations == capturedConversations)
                                            result = prepared
                                        else error = "档案刚有更新，请按当前内容重新生成。"
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "暂时无法准备修正内容，请稍后再试。"
                                    } finally {
                                        preparing = false
                                    }
                                }
                            },
                        ) { Text(if (preparing) "准备中…" else "生成修正内容") }
                        else Button(onClick = { result?.let { onCopy(it.text); copied = true } }) { Text("复制修正内容") }
                    }
                }
            }
        }
    }
}
