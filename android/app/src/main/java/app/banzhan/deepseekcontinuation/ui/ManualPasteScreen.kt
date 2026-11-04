@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ManualPasteScreen(
    draft: ManualPasteDraft,
    busy: Boolean,
    run: (suspend () -> Unit) -> Unit,
    onImport: (String, List<ManualPastedText>) -> Unit,
) {
    val populatedCount = draft.messages.count { it.content.isNotBlank() }
    val selectedCount = draft.messages.count { it.selected }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var discardConfirmation by remember { mutableStateOf(false) }
    val editingEnabled = !busy && draft.loaded && !draft.resetting
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { NoticeCard(MANUAL_PASTE_NOTICE) }
            draft.persistenceError?.let { error -> item { NoticeCard(error) } }
            item {
                Text(
                    "在下方输入框长按并选择粘贴。每条内容请选好角色；整段混合对话可保留为「角色不确定」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = draft::updateTitle,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("会话标题") },
                    placeholder = { Text("例如：本周旅行计划") },
                    singleLine = true,
                    enabled = editingEnabled,
                )
            }
            itemsIndexed(draft.messages, key = { _, message -> message.rowId }) { index, message ->
                Card(
                    colors =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "第 ${index + 1} 条",
                                Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("选择删除", style = MaterialTheme.typography.labelSmall)
                            Checkbox(
                                checked = message.selected,
                                onCheckedChange = {
                                    draft.updateMessage(message.copy(selected = it))
                                },
                                enabled = editingEnabled,
                            )
                        }
                        ActionRow {
                            listOf("user", "assistant", "unknown").forEach { role ->
                                FilterChip(
                                    selected = message.role == role,
                                    onClick = { draft.updateMessage(message.copy(role = role)) },
                                    label = { Text(roleLabel(role)) },
                                    enabled = editingEnabled,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = message.content,
                            onValueChange = { draft.updateMessage(message.copy(content = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("手动粘贴或输入正文") },
                            minLines = 4,
                            maxLines = 10,
                            enabled = editingEnabled,
                        )
                        Text(
                            "${message.content.length} 字符",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                ActionRow {
                    OutlinedButton(onClick = draft::addMessage, enabled = editingEnabled) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加一条")
                    }
                    TextButton(
                        onClick = { deleteConfirmation = true },
                        enabled = editingEnabled && selectedCount > 0,
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("删除选定条（$selectedCount）")
                    }
                    TextButton(onClick = { discardConfirmation = true }, enabled = editingEnabled) {
                        Text("放弃草稿")
                    }
                }
            }
            item {
                Text(
                    "草稿自动保存在本机，方便切回 DeepSeek 继续复制。这里只导入文本，后续 AI 整理需另行确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "将导入 $populatedCount 条非空内容，按当前顺序保留。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onImport(draft.title, draft.messages.toList()) },
                    enabled = editingEnabled && draft.title.isNotBlank() && populatedCount > 0,
                ) {
                    Text("导入本次内容")
                }
            }
        }
    }
    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除选定的内容？") },
            text = { Text("将移除本次草稿中选定的 $selectedCount 条内容。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft.deleteSelected()
                        deleteConfirmation = false
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("取消") } },
        )
    }
    if (discardConfirmation) {
        AlertDialog(
            onDismissRequest = { discardConfirmation = false },
            title = { Text("放弃本次草稿？") },
            text = { Text("将删除本机保存的粘贴草稿。已导入的对话不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            draft.reset()
                            discardConfirmation = false
                        }
                    }
                ) {
                    Text("放弃草稿")
                }
            },
            dismissButton = { TextButton(onClick = { discardConfirmation = false }) { Text("取消") } },
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun GetRecordsDialog(
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onOpenOfficialWebsite: () -> Unit,
    onImportFile: () -> Unit,
) = DownloadShareTutorialDialog(onDismiss, onOpenOfficialWebsite, onImportFile)
