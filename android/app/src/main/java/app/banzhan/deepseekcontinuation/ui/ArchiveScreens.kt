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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    onReorganize: () -> Unit = {},
    onOpenRecord: (String) -> Unit = {},
    onPoliticalExport: () -> Unit = {},
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
                if (archive.generationMode == "summary" &&
                    (entries.any { it.text.isNotBlank() } || archive.background.isNotBlank())) {
                    OutlinedButton(onClick = onPoliticalExport) { Text("生成温和版") }
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
            ArchiveReorganizationActions(
                archive, state.archives, state.tasks, state.busy, onReorganize, onOpenRecord,
            )
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
        ConflictGroupsDialog(
            conflicts = list,
            onDismiss = { conflicts = null },
            onResolve = { group, useProposed ->
                controller.resolveConflict(archive.id, group.first(), useProposed, observedGroup = group)
            },
            onReload = { conflicts = controller.conflicts(archive.id) },
        )
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
internal fun ConflictGroupsDialog(
    conflicts: List<EntryConflict>,
    onDismiss: () -> Unit,
    onResolve: suspend (List<EntryConflict>, Boolean) -> Unit,
    onReload: suspend () -> Unit,
) {
    val groups = remember(conflicts) { ReorganizationLogic.groupConflicts(conflicts) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                onReload()
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "未能刷新，请重试。"
            } finally {
                saving = false
            }
        }
    }

    fun resolve(group: List<EntryConflict>, useProposed: Boolean) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                onResolve(group, useProposed)
                onReload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "未能处理，请刷新后重试。"
            } finally {
                saving = false
            }
        }
    }

    AppDialog(
        "核对更新冲突",
        { if (!saving) onDismiss() },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (saving) Text("正在处理…", style = MaterialTheme.typography.bodySmall)
                ActionRow {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
                    TextButton(onClick = ::reload, enabled = !saving) { Text("刷新") }
                }
            }
        },
    ) {
        if (groups.isEmpty()) {
            Text("目前没有待处理的冲突。手动编辑、固定和删除状态会保留。")
        } else {
            Text("关联的条目按整组处理，未选择的组会继续保留。", style = MaterialTheme.typography.bodySmall)
        }
        groups.forEachIndexed { index, group ->
            val existing = group.map { it.existing }.distinct()
            val proposed = group.map { it.proposed }.distinct()
            val existingCount = existing.distinctBy { it.id }.size
            val proposedCount = proposed.distinctBy { it.id }.size
            Text("第 ${index + 1} 组", fontWeight = FontWeight.SemiBold)
            if (existing.size > existingCount || proposed.size > proposedCount) {
                Text("同一条目有不同版本，已全部展示；处理前请刷新核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("当前内容 · $existingCount 条", style = MaterialTheme.typography.labelLarge, color = Blue)
            existing.forEach { entry -> ConflictEntryPreview(entry) }
            Text("新整理的内容 · $proposedCount 条", style = MaterialTheme.typography.labelLarge, color = Blue)
            proposed.forEach { entry -> ConflictEntryPreview(entry) }
            if (existing.any { it.deleted }) {
                Text("这一组含已删除内容；采用新版会用上方的新内容替换这一组。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionRow {
                OutlinedButton(onClick = { resolve(group, false) }, enabled = !saving && error == null) {
                    Text("保留当前")
                }
                Button(onClick = { resolve(group, true) }, enabled = !saving && error == null) {
                    Text("采用以上 $proposedCount 条，替换 $existingCount 条")
                }
            }
            if (index < groups.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ConflictEntryPreview(entry: ArchiveEntry) {
    var previewLimit by remember(entry) { mutableIntStateOf(8000) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionRow {
                Text(entry.category.label(), style = MaterialTheme.typography.labelMedium)
                if (entry.deleted) Text("已删除", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium)
                if (entry.edited) Text("已编辑", style = MaterialTheme.typography.labelMedium)
                if (entry.pinned) Text("已固定", style = MaterialTheme.typography.labelMedium)
            }
            SelectionContainer { Text(entry.text.take(previewLimit), style = MaterialTheme.typography.bodyMedium) }
            if (entry.text.length > previewLimit) {
                Text("当前预览前 $previewLimit 字符，处理时会使用完整条目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { previewLimit = (previewLimit + 8000).coerceAtMost(entry.text.length) }) {
                    Text("继续查看")
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
