@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparationChoice(
    val title: String,
    val branchId: String,
    val selectedIds: Set<String>,
    val background: String,
)

@Composable
private fun FlowSteps(current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("选聊天", "整理", "去续聊").forEachIndexed { index, label ->
            Text(
                "${index + 1} $label",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (current == index + 1) Blue else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (current == index + 1) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun ConversationPickerScreen(
    conversations: List<ImportedConversation>,
    title: String = "想接着聊哪一段？",
    detail: String = "默认选中这段聊天的全部消息。",
    onImport: (() -> Unit)? = null,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered =
        remember(conversations, query) {
            conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { FlowSteps(1) }
        item { SectionTitle(title, detail) }
        onImport?.let { import -> item {
            OutlinedButton(onClick = import) { Text("导入最近导出的记录") }
            Text("也可以直接选择下面已经导入的聊天。", style = MaterialTheme.typography.bodySmall)
        } }
        item {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索聊天标题") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
            )
        }
        if (filtered.isEmpty())
            item {
                NoticeCard(
                    if (conversations.isEmpty()) "还没有可选择的聊天，请先导入文件或粘贴内容。" else "没有找到这个标题，试试更短的关键词。"
                )
            }
        items(filtered, key = { it.id }) { conversation ->
            Card(
                onClick = { onSelect(conversation.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(conversation.title, fontWeight = FontWeight.SemiBold)
                        val end = conversation.updatedAt.ifBlank { conversation.createdAt }
                        val readableDate =
                            remember(end) {
                                runCatching {
                                        formatTime(java.time.Instant.parse(end).toEpochMilli())
                                    }
                                    .getOrDefault(end)
                            }
                        if (end.isNotBlank())
                            Text(
                                readableDate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    Icon(Icons.Outlined.ChevronRight, "选择这段聊天")
                }
            }
        }
    }
}

internal fun defaultChatVersion(conversation: ImportedConversation): String? {
    if (conversation.branches.size == 1) return conversation.branches.first().id
    conversation.currentBranchId?.let { id ->
        if (conversation.branches.any { it.id == id }) return id
    }
    return conversation.branches.filter { it.isCurrent }.singleOrNull()?.id
}

@Composable
internal fun PreparationScreen(
    conversation: ImportedConversation,
    state: AppState,
    busy: Boolean,
    error: String?,
    onStart: (PreparationChoice) -> Unit,
    onOriginal: (PreparationChoice) -> Unit,
    onService: () -> Unit,
    onQuotaRefresh: () -> Unit,
    estimateMessages: (List<ChatMessage>) -> OrganizationEstimate,
    onAdvanced: (String?) -> Unit,
    onSavedTask: (String) -> Unit,
    onQuick: ((PreparationChoice) -> Unit)? = null,
) {
    val settings = state.settings
    var branchId by
        rememberSaveable(conversation.id) { mutableStateOf(defaultChatVersion(conversation)) }
    val branch = conversation.branches.firstOrNull { it.id == branchId }
    val selectionKey = "prepare:${conversation.id}:$branchId"
    var customizedVersions by
        rememberSaveable(conversation.id) { mutableStateOf(arrayListOf<String>()) }
    val selectionMemory: MessageSelectionMemory = viewModel()
    val allIds = remember(branch) { branch?.messages?.map { it.id }?.toSet().orEmpty() }
    val range = selectionMemory.range(selectionKey, allIds, branchId in customizedVersions)
    val selected = range.ids
    fun select(ids: Set<String>) {
        if (branchId != null && branchId !in customizedVersions)
            customizedVersions = ArrayList(customizedVersions + branchId!!)
        range.select(ids)
    }
    var title by rememberSaveable(conversation.id) { mutableStateOf(conversation.title) }
    var background by rememberSaveable(conversation.id) { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(false) }
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var showMessages by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ChatMessage?>(null) }
    val messages =
        remember(branch, selected) { branch?.messages?.filter { it.id in selected }.orEmpty() }
    var estimate by remember { mutableStateOf<Result<OrganizationEstimate>?>(null) }
    LaunchedEffect(messages, settings, state.quota) {
        estimate = null
        estimate = withContext(Dispatchers.Default) { runCatching { estimateMessages(messages) } }
    }
    val choice = PreparationChoice(title, branchId.orEmpty(), selected, background)
    val canStart = !busy && branch != null && messages.isNotEmpty() && title.isNotBlank()
    val savedTask = resumablePreparationTask(conversation.id, state)
    val freeReady =
        settings.mode != "companion" ||
            (state.quota != null &&
                state.quota.organizationBlocker() == null &&
                !state.quotaLoading &&
                state.quotaError == null &&
                estimate?.isSuccess == true)
    val configured = if (settings.mode == "companion") true else settings.apiKey.isNotBlank()
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { FlowSteps(2) }
        item { SectionTitle("接着聊「${conversation.title}」", "把这段聊天整理成下一次对话能用的内容。") }
        if (conversation.title.startsWith(MANUAL_PASTE_TITLE_PREFIX))
            item { NoticeCard(MANUAL_PASTE_NOTICE) }
        if (range.needsReview) item { NoticeCard("上次自定义的消息范围未能恢复。请展开「调整范围与更多选项」，重新核对并选择消息。") }
        if (branch == null || showVersions) {
            item { SectionTitle("选一个聊天版本", "看看最后几句，选你想继续的那一版。") }
            items(conversation.branches, key = { it.id }) { version ->
                Card(
                    onClick = {
                        branchId = version.id
                        showVersions = false
                    },
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (version.id == branchId)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                        ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "聊天版本 ${conversation.branches.indexOf(version) + 1} · ${version.messages.size} 条消息",
                            fontWeight = FontWeight.SemiBold,
                        )
                        version.messages.takeLast(2).forEach {
                            Text(
                                "${roleLabel(it.role)} · ${it.content}",
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        if (branch != null) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "已选 ${messages.size} 条消息",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (messages.size == branch.messages.size) "使用这个聊天版本的全部消息。"
                            else "将使用你勾选的消息。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (conversation.branches.size > 1)
                            TextButton(onClick = { showVersions = !showVersions }) {
                                Text("查看其他聊天版本")
                            }
                    }
                }
            }
        }
        item {
            savedTask?.let { task ->
                Text("这段聊天已有整理进度：${task.done} / ${task.total} 段。", fontWeight = FontWeight.SemiBold)
                Button(onClick = { onSavedTask(task.archiveId) }, enabled = !busy) {
                    Text(if (task.status == "paused") "回到上次整理" else "查看整理进度")
                }
                Text("已完成部分会保留，无需从头开始。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }
            if (branch != null && messages.isEmpty()) {
                NoticeCard(if (range.needsReview) "上次选择的消息范围未能恢复，请重新选择。免费次数已经单独显示在下方。"
                    else "还没有选择要整理的消息。请先选择范围，再开始整理或带走原文。")
                ActionRow {
                    TextButton(onClick = { select(allIds) }, enabled = !busy && allIds.isNotEmpty()) {
                        Text("使用当前版本全部消息")
                    }
                    TextButton(onClick = { options = true; showMessages = true }, enabled = !busy) {
                        Text("重新选择范围")
                    }
                }
            }
            error?.let {
                OrganizationFailureReason(it)
                Spacer(Modifier.height(10.dp))
            }
            if (settings.mode == "companion") {
                Text(
                    "点击开始会将所选内容发送到我们的整理服务，免费使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (messages.isEmpty()) "选择消息后会显示所需请求次数。"
                    else estimate?.getOrNull()?.let { "预计分为 ${it.chunks} 段，约需 ${state.quota.requestCountText(it.chunks)} 次模型请求。" }
                        ?: if (state.quotaLoading || estimate == null) "正在确认分段请求次数。"
                        else "暂未能估算请求次数，请刷新额度后再试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FreeQuotaCard(state, onQuotaRefresh)
                val available =
                    listOfNotNull(state.quota?.userDailyRemaining, state.quota?.dailyRemaining)
                        .minOrNull()
                val requests = estimate?.getOrNull()?.chunks
                if (available != null && requests != null && state.quota.requestUpperBound(requests) > available) {
                    Text(
                        if (available == 0) "当前免费次数已用完。已完成的进度会保留，次数更新后可继续。"
                        else "按当前剩余次数，今天可能只能完成部分内容。进度会自动保存，次数更新后可继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Blue,
                    )
                }
            } else if (!configured)
                Text(
                    "使用自己的 API 时，需要先填写密钥。设置一次后即可继续使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else {
                val service = "${settings.model}（${settings.baseUrl}）"
                val cost =
                    estimate?.getOrNull()?.estimatedCost?.let {
                        "按填写单价估算约 ${"%.4f".format(it)}，实际以账单为准。"
                    } ?: "按服务方实际用量计费，当前未提供可用的费用估算。"
                Text(
                    "点击开始会将所选消息发送给$service。$cost",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onStart(choice) }, enabled = canStart && freeReady) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (busy) "正在准备…" else "开始智能整理")
            }
            if (error != null || !configured || state.quotaError != null)
                TextButton(onClick = onService, enabled = !busy) {
                    Text(if (configured) "检查服务设置" else "设置自己的 API")
                }
        }
        item {
            if (onQuick != null)
                OutlinedButton(onClick = { onQuick(choice) }, enabled = canStart) { Text("先快速接续") }
            else OutlinedButton(onClick = { onOriginal(choice) }, enabled = canStart) { Text("先带走原文") }
            Text(
                if (onQuick != null) "无需等待 AI，先用最近原话继续聊。完整整理可以稍后进行。"
                else "本机处理，无需 AI。保留原文长度，内容较长时会建议保存文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            TextButton(onClick = { options = !options }) {
                Icon(if (options) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Text("调整范围与更多选项")
            }
        }
        if (options) {
            if (onQuick != null) item {
                TextButton(onClick = { onOriginal(choice) }, enabled = canStart) { Text("带走所选完整原文") }
                Text("本机打包，不缩短所选内容。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(
                    title,
                    { title = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("保存名称") },
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    background,
                    { background = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("补充背景（可选）") },
                    minLines = 2,
                    maxLines = 5,
                )
            }
            item {
                Text(
                    "来源时间 · ${conversation.createdAt.ifBlank { "未提供" }} — ${conversation.updatedAt.ifBlank { "未提供" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { showMessages = !showMessages }) {
                    Text(if (showMessages) "收起消息范围" else "逐条选择消息")
                }
                TextButton(onClick = { onAdvanced(branchId) }) { Text("关联已有记录与其他管理选项") }
            }
            if (showMessages) {
                item {
                    ActionRow {
                        TextButton(
                            onClick = { select(branch?.messages?.map { it.id }?.toSet().orEmpty()) }
                        ) {
                            Text("全选")
                        }
                        TextButton(onClick = { select(emptySet()) }) { Text("清空选择") }
                    }
                }
                items(branch?.messages.orEmpty(), key = { it.id }) { message ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(8.dp)) {
                            Checkbox(
                                message.id in selected,
                                { checked ->
                                    select(
                                        if (checked) selected + message.id
                                        else selected - message.id
                                    )
                                },
                            )
                            Column(
                                Modifier.weight(1f).clickable { preview = message }.padding(8.dp)
                            ) {
                                Text(
                                    roleLabel(message.role),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Blue,
                                )
                                Text(
                                    message.content,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { MessageDialog(it) { preview = null } }
}

@Composable
internal fun OrganizationProgressScreen(
    task: OrganizationTask?,
    starting: Boolean,
    startupError: String?,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
    onPrepare: () -> Unit,
    onService: () -> Unit,
    onRebuild: () -> Unit,
    onSavedContent: () -> Unit,
    onOriginalArchive: (() -> Unit)? = null,
) {
    var confirmResume by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    val active = task?.status in setOf("queued", "running")
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { FlowSteps(2) }
        item {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Whale(Modifier.width(120.dp).height(100.dp))
                    Text(
                        when {
                            starting && task == null -> "正在准备整理"
                            active -> "正在整理这段聊天"
                            task?.status == "completed" -> "整理已结束"
                            task?.status == "cancelled" -> "整理已取消"
                            else -> "整理还没完成"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (task != null) {
                        LinearProgressIndicator(
                            progress = {
                                if (task.total == 0) 0f else task.done.toFloat() / task.total
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("已完成 ${task.done} / ${task.total} 段")
                    } else if (starting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        when {
                            starting && task == null -> "正在检查服务并准备消息。"
                            active -> "正在提炼要记住的信息，已完成部分会自动保存。"
                            task?.status == "completed" -> "正在打开结果。"
                            task?.status == "cancelled" -> "已停止后续整理，已完成内容会保留。"
                            task?.status == "paused" -> "整理暂时停下了。可以继续上次整理，或检查服务设置。"
                            else -> if (onOriginalArchive != null) "整理未能开始。可以检查服务设置，或返回原档案。"
                                else "整理未能开始。请检查服务设置，或返回准备页带走原文。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (task?.uncertain == true)
                        Text(
                            task.requestUncertaintyText(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    val reason = startupError ?: task?.message
                    if (!active && !reason.isNullOrBlank() &&
                        (startupError != null || task?.status !in setOf("completed", "cancelled"))) {
                        OrganizationFailureReason(reason)
                    }
                    if (active)
                        Text(
                            "可以离开这个页面，已完成的内容会自动保存在本机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
        item {
            ActionRow {
                if (active && task != null)
                    OutlinedButton(onClick = { run { controller.pauseTask(task.id) } }) {
                        Text("暂停")
                    }
                if (task?.status in setOf("paused", "cancelled") && task != null)
                    Button(
                        onClick = {
                            if (task.uncertain) confirmResume = true
                            else run { controller.resumeTask(task.id, false) }
                        }
                    ) {
                        Text("继续上次整理")
                    }
                if (task != null && task.status !in setOf("completed", "cancelled"))
                    TextButton(onClick = { confirmCancel = true }) { Text("取消整理") }
                onOriginalArchive?.let { onOriginal ->
                    TextButton(onClick = onOriginal) { Text("返回原档案") }
                }
            }
        }
        if (!active && !starting) {
            item {
                ActionRow {
                    TextButton(onClick = onService) { Text("检查服务设置") }
                    if ((task == null || task.status == "cancelled" || task.status == "completed") && onOriginalArchive == null)
                        TextButton(onClick = onPrepare) { Text("返回准备页") }
                    else if (task != null && task.status !in setOf("cancelled", "completed"))
                        TextButton(onClick = { confirmRebuild = true }) { Text("用当前设置重新开始") }
                }
            }
            if (task?.status == "paused")
                item {
                    Text(
                        "继续会沿用上次任务的服务。使用新的设置前，需要先取消旧任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            item { TextButton(onClick = onSavedContent) { Text("查看已保存的内容") } }
        }
    }
    if (confirmResume && task != null)
        AlertDialog(
            onDismissRequest = { confirmResume = false },
            title = { Text("确认继续整理") },
            text = { Text(task.requestUncertaintyText() + "\n\n${task.provider}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            controller.resumeTask(task.id, true)
                            confirmResume = false
                        }
                    }
                ) {
                    Text(if (task.serviceMode == "companion") "确认消耗次数并继续" else "确认费用风险并继续")
                }
            },
            dismissButton = { TextButton(onClick = { confirmResume = false }) { Text("暂不继续") } },
        )
    if (confirmCancel && task != null)
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("取消这次整理？") },
            text = { Text(task.cancelRequestText()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            controller.cancelTask(task.id)
                            confirmCancel = false
                        }
                    }
                ) {
                    Text("取消整理")
                }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("返回") } },
        )
    if (confirmRebuild)
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text("取消旧任务并重新准备？") },
            text = { Text(if (onOriginalArchive != null)
                "旧任务会停止，已完成内容会保留。将回到原档案，重新检查范围并确认当前服务与费用。"
                else "旧任务会停止，已完成内容会保留。返回准备页后，可以使用当前设置开启新整理。免费服务按请求次数计算，自己的 API 按服务方实际用量计费。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRebuild = false
                        onRebuild()
                    }
                ) {
                    Text("取消旧任务并返回")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text("保留旧任务") } },
        )
}

@Composable
internal fun ContinuationResultScreen(
    archive: Archive,
    task: OrganizationTask?,
    conversations: List<ImportedConversation>,
    onSaveContinuity: suspend (ContinuitySettings) -> Unit,
    onCopyAndOpen: (String) -> Unit,
    onSave: (String) -> Unit,
    onOpenDeepSeek: () -> Unit,
    onDetails: () -> Unit,
    onPrepare: () -> Unit,
    archives: List<Archive> = emptyList(),
    tasks: List<OrganizationTask> = emptyList(),
    busy: Boolean = false,
    onReorganize: () -> Unit = {},
    onOpenRecord: (String) -> Unit = {},
    onPoliticalExport: () -> Unit = {},
    onRepair: () -> Unit = {},
    onAddRecent: (() -> Unit)? = null,
) {
    var export by remember(archive, conversations) { mutableStateOf<Result<ExportResult>?>(null) }
    var large by remember(archive, conversations) { mutableStateOf(false) }
    LaunchedEffect(archive, conversations) {
        val prepared =
            withContext(Dispatchers.Default) {
                val result = runCatching { ArchiveLogic.continuationExport(archive, conversations) }
                result to
                    ((result.getOrNull()?.text?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 60 * 1024)
            }
        large = prepared.second
        export = prepared.first
    }
    var previewLimit by rememberSaveable(archive.id) { mutableIntStateOf(8000) }
    var showPreview by rememberSaveable(archive.id) { mutableStateOf(true) }
    var showTools by rememberSaveable(archive.id) { mutableStateOf(false) }
    var editingContinuity by rememberSaveable(archive.id) { mutableStateOf(false) }
    val text = export?.getOrNull()?.text
    val unfinished = task != null && task.status != "completed"
    val rebuiltCompleted = archive.rebuiltFromArchiveId != null && task?.status == "completed"
    val hasReorganizationNavigation = archive.rebuiltFromArchiveId != null ||
        archives.any { it.rebuiltFromArchiveId == archive.id }
    val pendingOrganization = tasks.firstOrNull {
        it.archiveId == archive.id && it.status in setOf("queued", "running", "paused")
    } ?: task?.takeIf { it.status in setOf("queued", "running", "paused") }

    val rebuilt = remember(archive.id, archives, tasks) { preferredRebuiltArchive(archive.id, archives, tasks) }
    val rebuiltTask = tasks.firstOrNull { it.archiveId == rebuilt?.id && it.status != "completed" }
    val canReorganize = pendingOrganization == null && rebuiltTask?.status !in setOf("queued", "running", "paused")
    LazyColumn(
        Modifier.fillMaxSize().testTag("result-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(18.dp).testTag("result-primary"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (unfinished) Icons.Outlined.History else Icons.Outlined.CheckCircleOutline,
                            null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            when {
                                export == null -> "正在准备续聊内容"
                                unfinished -> "已保存的部分内容"
                                rebuiltCompleted -> "重新整理已完成"
                                archive.generationMode == "original" -> "原文准备好了"
                                archive.generationMode == "summary" && text != null -> "整理好了，可以接着聊"
                                archive.generationMode == "summary" -> "这次没有提取到内容"
                                else -> "已保存的续聊内容"
                            }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(archive.title + if (archive.generationMode == "original") " · 原文未压缩 · 已存本机" else " · 已存本机",
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (unfinished) NoticeCard("整理尚未完成，下面是目前已保存的内容。")
                    if (rebuiltCompleted && text != null) Text("已保留个人设置和手动内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (export == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (text == null) {
                        NoticeCard(
                            if (rebuiltCompleted) "重新整理已完成，已保留你的修改与删除；需要时到详细内容核对新建议。"
                            else if (archive.generationMode == "summary") "没有提取到可续聊的内容，可以返回准备页改用「先带走原文」。"
                            else export?.exceptionOrNull()?.message ?: "还没有可导出的内容。"
                        )
                        if (rebuiltCompleted) Button(onClick = onDetails) { Text("查看详细内容与新建议") }
                        else Button(onClick = onPrepare) { Text("返回准备页") }
                        if (!rebuiltCompleted) TextButton(onClick = onDetails) { Text("查看与修改详细内容") }
                        ArchiveReorganizationActions(archive, archives, tasks, busy, onReorganize, onOpenRecord)
                    } else {
                        if (large) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onSave(text) }, modifier = Modifier.heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("保存文件")
                                }
                                OutlinedButton(onClick = onOpenDeepSeek, modifier = Modifier.heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
                                    Text("打开 DeepSeek")
                                }
                            }
                            Text("保存后，在新对话中添加文件。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Button(onClick = { onCopyAndOpen(text) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("复制并打开 DeepSeek")
                            }
                            Text("新建对话，粘贴后发送。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if ((export?.getOrNull()?.omittedCount ?: 0) > 0) {
                            Text("已优先携带互动与近期内容，${export?.getOrNull()?.omittedCount} 条自动内容仍保留在原档案。",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onDetails) { Text("查看完整档案") }
                        }
                        pendingOrganization?.let { pending ->
                            TextButton(onClick = { onOpenRecord(archive.id) }, enabled = !busy) {
                                Text(if (pending.status == "paused") "继续上次整理" else "查看整理进度")
                            }
                        }
                        if (hasReorganizationNavigation) ArchiveReorganizationActions(
                            archive, archives, tasks, busy, onReorganize, onOpenRecord, showStartAction = false,
                        )
                    }
                }
            }
        }
        if (text != null) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    onAddRecent?.let { add ->
                        TextButton(onClick = add, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                            Text("补入最近聊天")
                        }
                    }
                    TextButton(onClick = onRepair,
                        modifier = if (onAddRecent != null) Modifier.weight(1f).heightIn(min = 48.dp)
                            else Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("没接上？")
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("result-preview")) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("续聊内容", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text("${text.length} 字符", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { showTools = true }, modifier = Modifier.testTag("result-tool-trigger"),
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Outlined.Tune, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("工具")
                            }
                            IconButton(onClick = { showPreview = !showPreview }) {
                                Icon(if (showPreview) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    if (showPreview) "收起续聊内容" else "展开续聊内容")
                            }
                        }
                        if (showPreview) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SelectionContainer { Text(text.take(previewLimit), style = MaterialTheme.typography.bodyMedium) }
                            if (text.length > previewLimit) {
                                Text("预览前 $previewLimit 字符；复制和保存包含全部 ${text.length} 字符。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { previewLimit = (previewLimit + 8000).coerceAtMost(text.length) }) {
                                    Text("继续查看")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showTools && text != null) ResultToolsSheet(
        large = large, summary = archive.generationMode == "summary", canReorganize = canReorganize, busy = busy,
        onDismiss = { showTools = false },
        onSettings = { showTools = false; editingContinuity = true },
        onSave = { showTools = false; onSave(text) },
        onDetails = { showTools = false; onDetails() },
        onPoliticalExport = { showTools = false; onPoliticalExport() },
        onReorganize = { showTools = false; onReorganize() },
    )
    if (editingContinuity) ContinuityEditor(archive, conversations,
        { editingContinuity = false }, onSaveContinuity)
}
