@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.*
import java.util.UUID

@Composable
internal fun ArchivesScreen(
    archives: List<Archive>,
    onArchive: (String) -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("续聊记录", "整理好的内容会自动保存在这里，随时可以接着聊。") }
        if (archives.isEmpty()) {
            item { NoticeCard("还没有保存的内容。导入一段聊天，就可以开始准备续聊。") }
            item { Button(onClick = onImport) { Text("导入聊天") } }
        }
        items(archives.sortedByDescending { it.updatedAt }, key = { it.id }) {
            ArchiveSummary(it) { onArchive(it.id) }
        }
    }
}

@Composable
internal fun ArchiveScreen(
    archive: Archive,
    state: AppState,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
    onExport: (Archive) -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    var editEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var editBackground by remember { mutableStateOf(false) }
    var deleteArchive by remember { mutableStateOf(false) }
    var sourceEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var history by remember { mutableStateOf<List<Archive>?>(null) }
    var pendingRestore by remember { mutableStateOf<Archive?>(null) }
    var conflicts by remember { mutableStateOf<List<EntryConflict>?>(null) }
    var chooseConversation by remember { mutableStateOf(false) }
    val entries = archive.entries.filterNot { it.deleted }.sortedByDescending { it.pinned }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionTitle(
                archive.title,
                "更新于 ${formatTime(archive.updatedAt)} · ${entries.size} 条记忆",
            )
        }
        item {
            ActionRow {
                Button(
                    onClick = { onExport(archive) },
                    enabled = entries.isNotEmpty() || archive.background.isNotBlank(),
                ) {
                    Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出续聊")
                }
                OutlinedButton(onClick = { chooseConversation = true }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Text("关联对话")
                }
            }
            ActionRow {
                TextButton(
                    onClick = {
                        editEntry =
                            ArchiveEntry(
                                UUID.randomUUID().toString(),
                                EntryCategory.BACKGROUND,
                                "",
                                kind = EntryKind.USER_FACT,
                                edited = true,
                            )
                    }
                ) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("补充条目")
                }
                TextButton(onClick = { run { history = controller.revisions(archive.id) } }) {
                    Icon(Icons.Outlined.History, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("历史版本")
                }
                TextButton(onClick = { run { conflicts = controller.conflicts(archive.id) } }) {
                    Text("核对冲突")
                }
            }
        }
        item {
            ContinuitySetupCard(archive, state.conversations) { settings ->
                controller.saveContinuitySettings(archive.id, settings)
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row {
                        Text("补充背景", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { editBackground = true }, Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.Edit, "编辑背景", Modifier.size(18.dp))
                        }
                    }
                    Text(
                        archive.background.ifBlank { "可以补充称呼、前情或你希望延续的方向。" },
                        color =
                            if (archive.background.isBlank())
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (entries.isEmpty()) item { NoticeCard("档案尚无条目，可以手动补充，或关联对话进行整理。") }
        items(entries, key = { it.id }) { entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionRow {
                        Text(
                            entry.category.label(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Blue,
                        )
                        Text(
                            "· ${entry.kind.label()}${if (entry.edited) " · 已编辑" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (entry.pinned)
                            Icon(Icons.Outlined.PushPin, "已固定", Modifier.size(16.dp), tint = Blue)
                    }
                    SelectionContainer {
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    ActionRow {
                        TextButton(onClick = { editEntry = entry }) { Text("编辑") }
                        TextButton(
                            onClick = {
                                run {
                                    controller.saveEntry(
                                        archive.id,
                                        entry.copy(pinned = !entry.pinned),
                                    )
                                }
                            }
                        ) {
                            Text(if (entry.pinned) "取消固定" else "固定")
                        }
                        TextButton(onClick = { sourceEntry = entry }) {
                            Text("来源 ${entry.sourceIds.size}")
                        }
                        TextButton(
                            onClick = {
                                run {
                                    controller.saveEntry(
                                        archive.id,
                                        entry.copy(deleted = true, edited = true),
                                    )
                                }
                            }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("关联的对话")
            Spacer(Modifier.height(8.dp))
            state.conversations
                .filter { it.id in archive.conversationIds }
                .forEach { conversation ->
                    TextButton(onClick = { onSelectConversation(conversation.id) }) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(conversation.title)
                    }
                }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { deleteArchive = true }) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除档案", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    editEntry?.let { entry ->
        EntryEditor(entry, { editEntry = null }) { updated ->
            run {
                controller.saveEntry(archive.id, updated)
                editEntry = null
            }
        }
    }
    if (editBackground) {
        var text by remember(archive.id) { mutableStateOf(archive.background) }
        AppDialog(
            "补充背景",
            { editBackground = false },
            actions = {
                ActionRow {
                    TextButton(onClick = { editBackground = false }) { Text("取消") }
                    Button(
                        onClick = {
                            run {
                                controller.saveBackground(archive.id, text)
                                editBackground = false
                            }
                        }
                    ) {
                        Text("保存")
                    }
                }
            },
        ) {
            OutlinedTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 12,
                label = { Text("前情与补充信息") },
            )
        }
    }
    if (deleteArchive) {
        AlertDialog(
            onDismissRequest = { deleteArchive = false },
            title = { Text("删除这份档案？") },
            text = { Text("将删除「${archive.title}」及其档案记录。原始导入对话仍可用于重新创建。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            controller.deleteArchive(archive.id)
                            deleteArchive = false
                            onDeleted()
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteArchive = false }) { Text("取消") } },
        )
    }
    sourceEntry?.let { entry ->
        val allMessages =
            state.conversations
                .flatMap { it.branches }
                .flatMap { it.messages }
                .associateBy { it.id }
        AppDialog(
            "条目来源",
            { sourceEntry = null },
            actions = { TextButton(onClick = { sourceEntry = null }) { Text("关闭") } },
        ) {
            if (entry.sourceIds.isEmpty()) Text("这是一条手动补充的信息，没有引用原始消息。")
            entry.sourceIds.forEach { id ->
                val message = allMessages[id]
                if (message == null)
                    Text("无法找到这条消息的原文。", style = MaterialTheme.typography.bodySmall)
                else {
                    Text(
                        roleLabel(message.role) + " · " + message.timestamp,
                        fontWeight = FontWeight.SemiBold,
                        color = Blue,
                    )
                    SelectionContainer { Text(message.content) }
                    HorizontalDivider()
                }
            }
        }
    }
    history?.let { versions ->
        AppDialog(
            "历史版本",
            { history = null },
            actions = { TextButton(onClick = { history = null }) { Text("关闭") } },
        ) {
            if (versions.isEmpty()) Text("暂时没有可恢复的历史版本。")
            versions.forEach { version ->
                Text(formatTime(version.updatedAt), fontWeight = FontWeight.SemiBold)
                Text(
                    "${version.entries.count { !it.deleted }} 条记忆 · ${version.background.take(60)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        pendingRestore = version
                        history = null
                    }
                ) {
                    Text("恢复此版本")
                }
                HorizontalDivider()
            }
        }
    }
    pendingRestore?.let { version ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复历史版本？") },
            text = { Text("将恢复到 ${formatTime(version.updatedAt)} 的内容。当前内容也会保留为历史版本。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            controller.restore(archive.id, version)
                            pendingRestore = null
                        }
                    }
                ) {
                    Text("恢复")
                }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }
    conflicts?.let { list ->
        AppDialog(
            "核对更新冲突",
            { conflicts = null },
            actions = { TextButton(onClick = { conflicts = null }) { Text("关闭") } },
        ) {
            if (list.isEmpty()) Text("目前没有待处理的冲突。手动编辑和固定内容会受到保留保护。")
            list.forEach { conflict ->
                Text("当前条目", style = MaterialTheme.typography.labelLarge, color = Blue)
                Text(conflict.existing.text)
                Text("新整理的内容", style = MaterialTheme.typography.labelLarge, color = Blue)
                Text(conflict.proposed.text)
                ActionRow {
                    OutlinedButton(
                        onClick = {
                            run {
                                controller.resolveConflict(archive.id, conflict, false)
                                conflicts = controller.conflicts(archive.id)
                            }
                        }
                    ) {
                        Text("保留当前")
                    }
                    Button(
                        onClick = {
                            run {
                                controller.resolveConflict(archive.id, conflict, true)
                                conflicts = controller.conflicts(archive.id)
                            }
                        }
                    ) {
                        Text("采用新条目")
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (chooseConversation) {
        AppDialog(
            "关联对话",
            { chooseConversation = false },
            actions = { TextButton(onClick = { chooseConversation = false }) { Text("关闭") } },
        ) {
            if (state.conversations.isEmpty()) Text("请先从首页导入聊天记录。")
            state.conversations.forEach { conversation ->
                TextButton(
                    onClick = {
                        chooseConversation = false
                        onSelectConversation(conversation.id)
                    }
                ) {
                    Text(conversation.title)
                }
            }
        }
    }
}

@Composable
private fun EntryEditor(
    entry: ArchiveEntry,
    onDismiss: () -> Unit,
    onSave: (ArchiveEntry) -> Unit,
) {
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var category by remember(entry.id) { mutableStateOf(entry.category) }
    var kind by remember(entry.id) { mutableStateOf(entry.kind) }
    var pinned by remember(entry.id) { mutableStateOf(entry.pinned) }
    AppDialog(
        "编辑条目",
        onDismiss,
        actions = {
            ActionRow {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = {
                        onSave(
                            entry.copy(
                                text = text.trim(),
                                category = category,
                                kind = kind,
                                pinned = pinned,
                                edited = true,
                            )
                        )
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Text("保存")
                }
            }
        },
    ) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 10,
            label = { Text("记忆内容") },
        )
        Text("分类", style = MaterialTheme.typography.labelLarge)
        ActionRow {
            EntryCategory.entries.forEach {
                FilterChip(
                    selected = category == it,
                    onClick = { category = it },
                    label = { Text(it.label()) },
                )
            }
        }
        Text("信息性质", style = MaterialTheme.typography.labelLarge)
        ActionRow {
            EntryKind.entries.forEach {
                FilterChip(
                    selected = kind == it,
                    onClick = { kind = it },
                    label = { Text(it.label()) },
                )
            }
        }
        Row {
            Checkbox(pinned, { pinned = it })
            Text("固定这条记忆", Modifier.padding(top = 14.dp))
        }
    }
}
