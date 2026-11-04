@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.AppState

@Composable
internal fun HomeScreen(
    state: AppState,
    onImport: () -> Unit,
    onGetRecords: () -> Unit,
    onPaste: () -> Unit,
    onDemo: () -> Unit,
    onRecord: (String) -> Unit,
    onBrowse: () -> Unit,
    onTasks: () -> Unit,
    onQuotaRefresh: () -> Unit,
) {
    var otherWays by rememberSaveable { mutableStateOf(false) }
    val unfinished = state.tasks.filter { it.status in setOf("queued", "running", "paused") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.15f
                    Column(
                        Modifier.fillMaxWidth().padding(if (compact) 18.dp else 22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (compact) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) { HeroBadge() }
                                Whale(Modifier.width(72.dp).height(61.dp))
                            }
                            HeroHeading()
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    HeroBadge()
                                    HeroHeading()
                                }
                                Whale(Modifier.width(124.dp).height(106.dp))
                            }
                        }
                        Text(
                            "网页下载 ZIP，在浏览器下载记录中分享给本助手，再选聊天整理。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onGetRecords,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.MenuBook, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("查看下载与分享教程")
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onImport, enabled = !state.busy) {
                                Text("手动选择 ZIP")
                            }
                            Text(
                                "找不到分享入口时使用，不用解压。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { otherWays = !otherWays }) {
                Icon(if (otherWays) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Text("其他方式")
            }
            if (otherWays)
                ActionRow {
                    TextButton(onClick = onPaste, enabled = !state.busy) { Text("粘贴聊天内容") }
                    TextButton(onClick = onDemo, enabled = !state.busy) { Text("体验示例") }
                    TextButton(onClick = onImport, enabled = !state.busy) { Text("导入 JSON 文件") }
                }
        }
        if (state.settings.mode == "companion") item { FreeQuotaCard(state, onQuotaRefresh) }
        if (unfinished.isNotEmpty()) {
            item { SectionTitle("上次还没整理完") }
            items(unfinished.take(2), key = { "task-${it.id}" }) { task ->
                Card(
                    onClick = { onRecord(task.archiveId) },
                    colors =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(task.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${if (task.status == "paused") "已暂停" else "正在整理"} · 已完成 ${task.done}/${task.total} 段",
                            style = MaterialTheme.typography.bodySmall,
                            color = Blue,
                        )
                        Text("继续查看", style = MaterialTheme.typography.labelLarge, color = Blue)
                    }
                }
            }
            if (unfinished.size > 2) item { TextButton(onClick = onTasks) { Text("查看所有整理进度") } }
        }
        val recent =
            state.archives
                .sortedByDescending { it.updatedAt }
                .filter { archive -> unfinished.none { it.archiveId == archive.id } }
                .take(3)
        if (recent.isNotEmpty()) {
            item { SectionTitle("最近保存的续聊内容") }
            items(recent, key = { "record-${it.id}" }) { archive ->
                ArchiveSummary(archive) { onRecord(archive.id) }
            }
        }
        if (state.conversations.isNotEmpty())
            item { TextButton(onClick = onBrowse) { Text("继续整理已导入的聊天") } }
    }
}

@Composable
private fun HeroBadge() {
    Surface(color = Peach, shape = MaterialTheme.shapes.small) {
        Text(
            "让故事，接着说",
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun HeroHeading() {
    Text(
        "把重要的事\n带到下一段对话",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun ArchiveSummary(archive: Archive, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FolderOpen, null, tint = Blue)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    archive.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${when (archive.generationMode) { "original" -> "聊天原文"
 "summary" -> "智能整理内容"
 else -> "已保存内容" }} · ${formatTime(archive.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.ChevronRight, "打开档案")
        }
    }
}

@Composable
internal fun SelectionScreen(
    conversation: ImportedConversation,
    archives: List<Archive>,
    initialTargetId: String?,
    busy: Boolean,
    initialBranchId: String? = null,
    onSave: (String, String, Set<String>, String, String?, Boolean) -> Unit,
) {
    var branchId by
        rememberSaveable(conversation.id) {
            mutableStateOf(initialBranchId ?: defaultChatVersion(conversation).orEmpty())
        }
    val branch = conversation.branches.firstOrNull { it.id == branchId }
    val selectionKey = "advanced:${conversation.id}:$branchId"
    var customizedVersions by
        rememberSaveable(conversation.id) { mutableStateOf(arrayListOf<String>()) }
    val selectionMemory: MessageSelectionMemory = viewModel()
    val allIds = remember(branch) { branch?.messages?.map { it.id }?.toSet().orEmpty() }
    val range = selectionMemory.range(selectionKey, allIds, branchId in customizedVersions)
    val selected = range.ids
    fun select(ids: Set<String>) {
        if (branchId !in customizedVersions)
            customizedVersions = ArrayList(customizedVersions + branchId)
        range.select(ids)
    }
    var title by rememberSaveable(conversation.id) { mutableStateOf(conversation.title) }
    var background by rememberSaveable(conversation.id) { mutableStateOf("") }
    var targetId by
        rememberSaveable(conversation.id, initialTargetId) { mutableStateOf(initialTargetId) }
    var preview by remember { mutableStateOf<ChatMessage?>(null) }
    val selectedMessages = branch?.messages?.filter { it.id in selected }.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionTitle(
                conversation.title,
                "${conversation.createdAt.ifBlank { "起始时间未提供" }} — ${conversation.updatedAt.ifBlank { "结束时间未提供" }}",
            )
        }
        if (conversation.title.startsWith(MANUAL_PASTE_TITLE_PREFIX)) {
            item { NoticeCard(MANUAL_PASTE_NOTICE) }
        }
        if (range.needsReview) item { NoticeCard("上次自定义的消息范围未能恢复，请重新核对并选择消息。") }
        item {
            SectionTitle("选择聊天版本")
            Spacer(Modifier.height(8.dp))
            ActionRow {
                conversation.branches.forEach { item ->
                    FilterChip(
                        selected = item.id == branchId,
                        onClick = { branchId = item.id },
                        label = {
                            Text(
                                "聊天版本 ${conversation.branches.indexOf(item) + 1}" +
                                    if (item.isCurrent) " · 当前" else ""
                            )
                        },
                    )
                }
            }
        }
        item {
            SectionTitle("保存位置")
            ActionRow {
                FilterChip(
                    selected = targetId == null,
                    onClick = { targetId = null },
                    label = { Text("新建档案") },
                )
                archives.forEach { archive ->
                    FilterChip(
                        selected = targetId == archive.id,
                        onClick = { targetId = archive.id },
                        label = {
                            Text(archive.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
            if (targetId == null) {
                OutlinedTextField(
                    title,
                    { title = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("档案名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    background,
                    { background = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("补充背景（可选）") },
                    placeholder = { Text("如人物称呼、需要记住的约定……") },
                    minLines = 2,
                    maxLines = 5,
                )
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "已选 ${selectedMessages.size} / ${branch?.messages?.size ?: 0} 条消息",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "约 ${selectedMessages.sumOf { it.content.length }} 字符",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    selectedMessages.firstOrNull()?.let {
                        Text(
                            "首条 · ${roleLabel(it.role)} · ${it.content.take(100)}",
                            maxLines = 3,
                            style = MaterialTheme.typography.bodySmall,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selectedMessages.size > 1)
                        selectedMessages.lastOrNull()?.let {
                            Text(
                                "末条 · ${roleLabel(it.role)} · ${it.content.take(100)}",
                                maxLines = 3,
                                style = MaterialTheme.typography.bodySmall,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                }
            }
        }
        item {
            ActionRow {
                Button(
                    onClick = { onSave(title, branchId, selected, background, targetId, false) },
                    enabled =
                        !busy && selected.isNotEmpty() && (targetId != null || title.isNotBlank()),
                ) {
                    Text(if (targetId == null) "创建档案" else "加入档案")
                }
                OutlinedButton(
                    onClick = { onSave(title, branchId, selected, background, targetId, true) },
                    enabled =
                        !busy && selected.isNotEmpty() && (targetId != null || title.isNotBlank()),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("AI 整理")
                }
            }
            Text(
                "创建档案会保留选中原文；AI 整理会在确认服务与费用后发送所选内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("消息范围", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { select(branch?.messages?.map { it.id }?.toSet().orEmpty()) }
                ) {
                    Text("全选")
                }
                TextButton(onClick = { select(emptySet()) }) { Text("清空") }
            }
        }
        items(branch?.messages.orEmpty(), key = { it.id }) { message ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = message.id in selected,
                        onCheckedChange = { checked ->
                            select(if (checked) selected + message.id else selected - message.id)
                        },
                    )
                    Column(
                        Modifier.weight(1f)
                            .clickable { preview = message }
                            .padding(top = 12.dp, end = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            roleLabel(message.role) +
                                message.timestamp
                                    .takeIf { it.isNotBlank() }
                                    ?.let { " · $it" }
                                    .orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Blue,
                        )
                        Text(
                            message.content.ifBlank { "[仅附件]" },
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (message.attachments.isNotEmpty())
                            Text(
                                "${message.attachments.size} 个附件引用",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
            }
        }
    }
    preview?.let { message -> MessageDialog(message) { preview = null } }
}

@Composable
internal fun MessageDialog(message: ChatMessage, onDismiss: () -> Unit) {
    AppDialog(
        roleLabel(message.role),
        onDismiss,
        actions = { TextButton(onClick = onDismiss) { Text("关闭") } },
    ) {
        if (message.timestamp.isNotBlank())
            Text(message.timestamp, style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(message.content.ifBlank { "[无文字内容]" })
        }
        message.attachments.forEach { Text("附件 · $it", style = MaterialTheme.typography.bodySmall) }
    }
}
