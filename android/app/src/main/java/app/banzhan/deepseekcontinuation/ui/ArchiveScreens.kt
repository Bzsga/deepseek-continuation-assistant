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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
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
    busy: Boolean = false,
    onDelete: suspend (Set<String>) -> Unit,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var pendingDeletion by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val currentIds = archives.map { it.id }.toSet()
    val selected = selectedIds.toSet().intersect(currentIds)
    val controlsEnabled = !busy && !deleting
    LaunchedEffect(currentIds) {
        selectedIds = ArrayList(selectedIds.filter { it in currentIds })
        if (currentIds.isEmpty() && !deleting) managing = false
    }
    fun finishManaging() {
        managing = false
        selectedIds = arrayListOf()
        deleteError = null
    }
    androidx.activity.compose.BackHandler(enabled = managing && !deleting) { finishManaging() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (managing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 项", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(
                        onClick = {
                            selectedIds = if (selected.size == currentIds.size) arrayListOf()
                                else ArrayList(currentIds)
                        },
                        enabled = controlsEnabled,
                    ) { Text(if (selected.size == currentIds.size) "取消全选" else "全选") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { finishManaging() }, enabled = controlsEnabled) { Text("完成") }
                    Button(
                        onClick = { pendingDeletion = ArrayList(selected) },
                        enabled = controlsEnabled && selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("删除（${selected.size}）")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        SectionTitle("续聊记录", "整理好的内容会自动保存在这里，随时可以接着聊。")
                    }
                    if (archives.isNotEmpty()) {
                        TextButton(onClick = { managing = true }, enabled = controlsEnabled) { Text("管理") }
                    }
                }
            }
            deleteError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("archives-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (archives.isEmpty()) {
                item { NoticeCard("还没有保存的内容。导入一段聊天，就可以开始准备续聊。") }
                item { Button(onClick = onImport) { Text("导入聊天") } }
            }
            items(archives.sortedByDescending { it.updatedAt }, key = { it.id }) { archive ->
                ArchiveSummary(
                    archive,
                    selected = if (managing) archive.id in selected else null,
                    enabled = !deleting,
                    onLongClick = {
                        if (controlsEnabled) {
                            managing = true
                            selectedIds = ArrayList(selected + archive.id)
                        }
                    },
                ) {
                    if (managing) {
                        if (controlsEnabled) {
                            selectedIds = ArrayList(if (archive.id in selected) selected - archive.id else selected + archive.id)
                        }
                    } else onArchive(archive.id)
                }
            }
        }
    }
    pendingDeletion?.let { ids ->
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDeletion = null },
            title = { Text("删除 ${ids.size} 条续聊记录？") },
            text = { Text("将删除所选续聊记录及其历史，并停止相关整理任务。原始导入和未选记录会保留。删除后无法撤销。") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("archive-delete-confirm"),
                    enabled = controlsEnabled,
                    onClick = {
                        if (!deleting) {
                            deleting = true
                            deleteError = null
                            scope.launch {
                                try {
                                    onDelete(ids.toSet())
                                    pendingDeletion = null
                                    finishManaging()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    pendingDeletion = null
                                    deleteError = error.message?.takeIf { it.isNotBlank() }
                                        ?: "删除失败，请稍后重试。"
                                } finally {
                                    deleting = false
                                }
                            }
                        }
                    },
                ) { Text(if (deleting) "正在删除…" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }, enabled = !deleting) { Text("取消") }
            },
        )
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
    onRepair: () -> Unit = {},
    onAddRecent: () -> Unit = {},
) {
    var editEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var editBackground by remember { mutableStateOf(false) }
    var deleteArchive by remember { mutableStateOf(false) }
    var sourceEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var history by remember { mutableStateOf<List<Archive>?>(null) }
    var pendingRestore by remember { mutableStateOf<Archive?>(null) }
    var conflicts by remember { mutableStateOf<List<EntryConflict>?>(null) }
    var showMoreActions by rememberSaveable(archive.id) { mutableStateOf(false) }
    val politicalState by controller.politicalExport.state.collectAsState()
    val continuingPoliticalTask = politicalState.task?.takeIf {
        it.archiveId == archive.id && (it.canResume || it.status in setOf("queued", "running"))
    }
    val hasReorganizationNavigation = archive.rebuiltFromArchiveId != null ||
        state.archives.any { it.rebuiltFromArchiveId == archive.id }
    val pendingOrganization = state.tasks.firstOrNull {
        it.archiveId == archive.id && it.status in setOf("queued", "running", "paused")
    }
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
            }
            ActionRow {
                TextButton(onClick = onRepair) { Text("没接上？调整一下") }
                TextButton(onClick = { showMoreActions = !showMoreActions }) {
                    Icon(if (showMoreActions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, Modifier.size(18.dp))
                    Text(if (showMoreActions) "收起更多操作" else "更多操作")
                }
            }
            continuingPoliticalTask?.let { pending ->
                SensitiveContentAction(onPoliticalExport, enabled = !state.busy,
                    label = if (pending.canResume) "去除敏感内容 · 继续检查" else "去除敏感内容 · 查看进度")
            }
            pendingOrganization?.let { pending ->
                TextButton(onClick = { onOpenRecord(archive.id) }, enabled = !state.busy) {
                    Text(if (pending.status == "paused") "继续上次整理" else "查看整理进度")
                }
            }
            if (hasReorganizationNavigation) {
                ArchiveReorganizationActions(
                    archive, state.archives, state.tasks, state.busy, onReorganize, onOpenRecord,
                )
            }
            if (showMoreActions) {
                ContinuitySetupCard(archive, state.conversations) { settings ->
                    controller.saveContinuitySettings(archive.id, settings)
                }
                ActionRow {
                    TextButton(onClick = { run { conflicts = controller.conflicts(archive.id) } }) {
                        Text("核对冲突")
                    }
                    if (archive.background.isBlank()) {
                        TextButton(onClick = { editBackground = true }) { Text("补充背景") }
                    }
                    TextButton(onClick = onAddRecent) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text("补入最近聊天")
                    }
                    TextButton(onClick = { run { history = controller.revisions(archive.id) } }) {
                        Icon(Icons.Outlined.History, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("历史版本")
                    }
                }
                if (continuingPoliticalTask == null && archive.generationMode == "summary" &&
                    (entries.any { it.text.isNotBlank() } || archive.background.isNotBlank())) {
                    SensitiveContentAction(onPoliticalExport, enabled = !state.busy)
                }
                if (!hasReorganizationNavigation && pendingOrganization == null) {
                    ArchiveReorganizationActions(
                        archive, state.archives, state.tasks, state.busy, onReorganize, onOpenRecord,
                    )
                }
            }
        }
        if (archive.background.isNotBlank()) item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row {
                        Text("补充背景", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { editBackground = true }) {
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
                    ArchiveEntryActions(
                        entry = entry,
                        onEdit = { editEntry = entry },
                        onPin = { run { controller.saveEntry(archive.id, entry.copy(pinned = !entry.pinned)) } },
                        onSource = { sourceEntry = entry },
                        onDelete = { run { controller.saveEntry(archive.id, entry.copy(deleted = true, edited = true)) } },
                    )
                }
            }
        }
        if (showMoreActions) item {
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
internal fun ArchiveEntryActions(
    entry: ArchiveEntry,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onSource: () -> Unit,
    onDelete: () -> Unit,
) {
    var more by remember(entry.id) { mutableStateOf(false) }
    ActionRow {
        TextButton(onClick = onEdit) { Text("编辑") }
        Box {
            TextButton(onClick = { more = true }) {
                Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("条目操作")
            }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(
                    text = { Text(if (entry.pinned) "取消固定" else "固定") },
                    onClick = { more = false; onPin() },
                )
                DropdownMenuItem(
                    text = { Text("来源 ${entry.sourceIds.size}") },
                    onClick = { more = false; onSource() },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = { more = false; onDelete() },
                )
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
