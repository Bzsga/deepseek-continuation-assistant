@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ContinuitySetupCard(
    archive: Archive,
    conversations: List<ImportedConversation>,
    onSave: suspend (ContinuitySettings) -> Unit,
) {
    var editing by rememberSaveable(archive.id) { mutableStateOf(false) }
    ActionRow {
        Text(
            when {
                !archive.continuity.enabled -> "沿用已保存内容"
                archive.generationMode == "original" -> "已保留所选原文"
                archive.continuity.selectedFragmentIds == null -> "续聊原话自动选择"
                else -> "续聊使用已保存设置"
            },
            Modifier.align(Alignment.CenterVertically),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { editing = true }) { Text("调整（可选）") }
    }
    if (editing)
        ContinuityEditor(archive, conversations, { editing = false }, onSave)
}

@Composable
internal fun ContinuityEditor(
    archive: Archive,
    conversations: List<ImportedConversation>,
    onDismiss: () -> Unit,
    onSave: suspend (ContinuitySettings) -> Unit,
) {
    var enabled by rememberSaveable(archive.id) { mutableStateOf(archive.continuity.enabled) }
    var includeRecent by rememberSaveable(archive.id) { mutableStateOf(archive.continuity.includeRecent) }
    var selected by rememberSaveable(archive.id) {
        mutableStateOf(archive.continuity.selectedFragmentIds?.let { ArrayList(it) })
    }
    var interactionNotes by rememberSaveable(archive.id) { mutableStateOf(archive.continuity.interactionNotes) }
    var nextMessage by rememberSaveable(archive.id) { mutableStateOf(archive.continuity.nextMessage) }
    val planningArchive = remember(archive, selected == null) {
        if (selected == null) archive.copy(continuity = archive.continuity.copy(selectedFragmentIds = null))
        else archive
    }
    var plan by remember(planningArchive, conversations) { mutableStateOf<Result<ContinuityPlan>?>(null) }
    var preview by remember { mutableStateOf<ContinuityFragment?>(null) }
    var showRecent by rememberSaveable(archive.id) { mutableStateOf(false) }
    var showFragments by rememberSaveable(archive.id) { mutableStateOf(false) }
    var showNotes by rememberSaveable(archive.id) { mutableStateOf(false) }
    var showSourceDetails by rememberSaveable(archive.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(planningArchive, conversations) {
        plan = withContext(Dispatchers.Default) {
            runCatching {
                ContinuityLogic.buildPlan(
                    planningArchive,
                    conversations,
                )
            }
        }
    }
    val candidates = plan?.getOrNull()
    val recommendedIds = candidates?.recommendedFragments?.map { it.id }?.toSet().orEmpty()
    val selectedIds = selected?.filter { it in recommendedIds }?.toSet()
        ?: candidates?.selectedFragmentIds?.toSet().orEmpty()
    AppDialog(
        "接着上次聊",
        { if (!saving) onDismiss() },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                saveError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                ActionRow {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                    Button(
                        enabled = !saving && interactionNotes.length <= 4000 && nextMessage.length <= 8000 &&
                            (selected == null || candidates != null),
                        onClick = {
                            saving = true
                            saveError = null
                            scope.launch {
                                try {
                                    onSave(
                                        archive.continuity.copy(
                                            enabled = enabled,
                                            includeRecent = includeRecent,
                                            selectedFragmentIds = selected?.filter { it in recommendedIds },
                                            interactionNotes = interactionNotes.trim(),
                                            nextMessage = nextMessage.trim(),
                                        )
                                    )
                                    onDismiss()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    saveError = error.message ?: "未能保存，请重试。"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "保存") }
                }
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("加入这些续聊线索", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(enabled, { enabled = it },
                modifier = Modifier.semantics { contentDescription = "加入这些续聊线索" }, enabled = !saving)
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("最近对话", fontWeight = FontWeight.SemiBold)
                Text("自动带上最近聊到的几轮原话", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(includeRecent, { includeRecent = it },
                modifier = Modifier.semantics { contentDescription = "最近对话" }, enabled = enabled && !saving)
        }
        if (plan == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        candidates?.let { available ->
            if (available.recentFragments.isEmpty()) {
                Text("没有可确认的最近原文，仍会保留现有整理内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = { showRecent = !showRecent }) {
                    Text(if (showRecent) "收起最近原话" else "查看最近原话")
                }
                if (showRecent) {
                    available.recentFragments.forEach { fragment ->
                        ContinuityFragmentCard(fragment, onPreview = { preview = fragment })
                    }
                }
            }
            HorizontalDivider()
            Text(
                when {
                    selected == null -> "关键片段：自动选择"
                    selectedIds.isEmpty() -> "关键片段：不附加"
                    else -> "关键片段：已选 ${selectedIds.size} 段"
                },
                fontWeight = FontWeight.SemiBold,
            )
            ActionRow {
                TextButton(onClick = { showFragments = !showFragments }) {
                    Text(if (showFragments) "收起片段选择" else "手动选片段（可选）")
                }
                if (selected != null) {
                    TextButton(onClick = { selected = null }, enabled = enabled && !saving) {
                        Text("恢复自动选择")
                    }
                }
            }
            if (showFragments) {
                if (available.recommendedFragments.isEmpty()) {
                    Text("没有可额外附上的原文片段。", style = MaterialTheme.typography.bodySmall)
                } else {
                    available.recommendedFragments.forEach { fragment ->
                        ContinuityFragmentCard(
                            fragment,
                            selected = fragment.id in selectedIds,
                            enabled = enabled && !saving,
                            onSelected = { checked ->
                                selected = ArrayList(if (checked) selectedIds + fragment.id else selectedIds - fragment.id)
                            },
                            onPreview = { preview = fragment },
                        )
                    }
                }
            }
            if (available.notices.isNotEmpty()) {
                TextButton(onClick = { showSourceDetails = !showSourceDetails }) {
                    Text(if (showSourceDetails) "收起原话说明" else "查看原话说明")
                }
                if (showSourceDetails) {
                    available.notices.distinct().forEach { notice ->
                        Text(notice, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        plan?.exceptionOrNull()?.let {
            Text("原文暂时无法载入。现有整理内容仍可使用。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()
        ActionRow {
            Text(
                when {
                    interactionNotes.isNotBlank() && nextMessage.isNotBlank() -> "已保留交流方式和下一句话"
                    interactionNotes.isNotBlank() -> "已保留交流方式"
                    nextMessage.isNotBlank() -> "已保留下一句话"
                    else -> "个人补充"
                },
                Modifier.align(Alignment.CenterVertically),
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { showNotes = !showNotes }) {
                Text(if (showNotes) "收起补充" else "补充（可选）")
            }
        }
        if (!showNotes && (interactionNotes.length > 4000 || nextMessage.length > 8000)) {
            Text("补充内容过长，请展开后精简。", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (showNotes) {
            OutlinedTextField(
                interactionNotes,
                { interactionNotes = it },
                Modifier.fillMaxWidth(),
                enabled = enabled && !saving,
                label = { Text("希望延续的交流方式") },
                placeholder = { Text("例如：先直接回应我的疑问；有分歧时说明理由。") },
                minLines = 3,
                maxLines = 6,
                isError = interactionNotes.length > 4000,
                supportingText = { Text(if (interactionNotes.length > 4000) "最多 4000 字，请精简后保存" else "可选 · 写下你自己的偏好") },
            )
            OutlinedTextField(
                nextMessage,
                { nextMessage = it },
                Modifier.fillMaxWidth(),
                enabled = enabled && !saving,
                label = { Text("接下来想说的话") },
                placeholder = { Text("例如：关于刚才那个选择，我还有一点没想清楚……") },
                minLines = 3,
                maxLines = 8,
                isError = nextMessage.length > 8000,
                supportingText = { Text(if (nextMessage.length > 8000) "最多 8000 字，请精简后保存" else "可选 · 放在续聊内容最后") },
            )
        }
    }
    preview?.let { fragment ->
        var previewLimit by remember(fragment.id) { mutableIntStateOf(8000) }
        val totalCharacters = remember(fragment) { fragment.messages.sumOf { it.content.length } }
        val visibleMessages = remember(fragment, previewLimit) {
            buildList {
                var remaining = previewLimit
                for (message in fragment.messages) {
                    if (remaining <= 0) break
                    val visibleText = message.content.take(remaining)
                    add(message to visibleText)
                    remaining -= visibleText.length
                }
            }
        }
        AppDialog(
            "查看完整原话",
            { preview = null },
            actions = { TextButton(onClick = { preview = null }) { Text("返回设置") } },
        ) {
            Text(fragment.title, fontWeight = FontWeight.SemiBold)
            visibleMessages.forEachIndexed { index, (message, visibleText) ->
                if (index > 0) HorizontalDivider()
                Text(
                    roleLabel(message.role) + message.timestamp.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    color = Blue,
                    style = MaterialTheme.typography.labelLarge,
                )
                SelectionContainer { Text(visibleText, style = MaterialTheme.typography.bodyMedium) }
            }
            if (totalCharacters > previewLimit) {
                Text(
                    "当前仅预览前 $previewLimit 字符，片段共 $totalCharacters 字符。继续查看可读完整原话，预览长度不影响导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { previewLimit = (previewLimit + 8000).coerceAtMost(totalCharacters) }) {
                    Text("继续查看")
                }
            }
        }
    }
}

@Composable
private fun ContinuityFragmentCard(
    fragment: ContinuityFragment,
    selected: Boolean? = null,
    enabled: Boolean = true,
    onSelected: (Boolean) -> Unit = {},
    onPreview: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected != null) {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = selected, enabled = enabled,
                        role = Role.Checkbox, onValueChange = onSelected),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(selected, onCheckedChange = null, enabled = enabled)
                    Text(fragment.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else Text(fragment.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            fragment.messages.take(2).forEach { message ->
                Text("${roleLabel(message.role)}：${message.content.take(240)}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onPreview) { Text("查看 ${fragment.messages.size} 条原话") }
        }
    }
}
