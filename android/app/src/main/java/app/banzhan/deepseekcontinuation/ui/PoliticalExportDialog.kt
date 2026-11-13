@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveLogic
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import app.banzhan.deepseekcontinuation.data.AppController
import app.banzhan.deepseekcontinuation.data.PoliticalExportPlan
import app.banzhan.deepseekcontinuation.data.automaticFallbackNotice
import app.banzhan.deepseekcontinuation.data.companionRequestCountText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An optional copy of the saved continuation; reviewing or exporting never edits the archive. */
@Composable
internal fun PoliticalExportDialog(
    archive: Archive,
    conversations: List<ImportedConversation>,
    controller: AppController,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    val manager = controller.politicalExport
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var plan by remember(archive.id) { mutableStateOf<PoliticalExportPlan?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var preparationAttempt by remember { mutableIntStateOf(0) }
    var confirmUncertainRetry by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val task = state.task
    var showHits by rememberSaveable(task?.id) { mutableStateOf(false) }
    var currentText by remember(archive, conversations) { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(archive, conversations) {
        currentText = withContext(Dispatchers.Default) {
            runCatching { ArchiveLogic.continuationExport(archive, conversations).text }
        }
    }
    val text = currentText?.getOrNull()
    val run: (suspend () -> Unit) -> Unit = { action ->
        if (!acting) scope.launch {
            acting = true
            localError = null
            manager.clearError()
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                localError = error.message ?: "操作未完成，请稍后再试。"
            } finally {
                acting = false
            }
        }
    }
    LaunchedEffect(archive.id, text, state.loaded, task?.id, preparationAttempt) {
        if (!state.loaded || task != null || text == null) return@LaunchedEffect
        preparing = true
        plan = null
        localError = null
        manager.clearError()
        try {
            plan = controller.preparePoliticalExport(archive.id, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            localError = error.message ?: "暂时无法准备检查，请确认整理服务可用。"
        } finally {
            preparing = false
        }
    }
    var variant by remember(task?.id, task?.status, task?.keptHitIds) { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(task?.id, task?.status, task?.keptHitIds) {
        variant = if (task?.status == "completed") withContext(Dispatchers.Default) {
            runCatching { task.variantText() }
        } else null
    }
    val variantText = variant?.getOrNull()
    var preview by remember(variantText) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(variantText) {
        preview = withContext(Dispatchers.Default) { politicalPreviewBlocks(variantText.orEmpty()) }
    }
    val completed = task?.status == "completed"
    val running = task?.status in setOf("queued", "running")
    val unresolved = task?.let { it.hasUnknownRequest || it.hasUnresolvedRemote } == true
    val changed = task != null && text != null && (task.archiveId != archive.id || task.originalText != text)
    val error = localError ?: state.error ?: currentText?.exceptionOrNull()?.message ?: variant?.exceptionOrNull()?.message
    val copyTooLarge = remember(variantText) { variantText != null && variantText.toByteArray(Charsets.UTF_8).size > 60 * 1024 }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("生成温和版", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("检查续聊文本中的政治敏感议题，默认省略命中片段，你可以保留误判内容。原档案不变。",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("仅降低被打断概率，可能误判或漏检。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!state.loaded || (task == null && (currentText == null || preparing))) {
                        item {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在准备检查范围与预计次数…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
                    if (task == null) {
                        plan?.let { prepared ->
                            item {
                                Text("${archive.title} · ${prepared.originalText.length} 字符", fontWeight = FontWeight.SemiBold)
                                Text(politicalEstimateText(prepared), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (plan == null && !preparing && state.loaded) item {
                            TextButton(onClick = { preparationAttempt++ }, enabled = !acting) { Text("重新检查服务") }
                        }
                    } else {
                        if (changed) item {
                            NoticeCard(if (task.archiveId != archive.id)
                                "这里保留着另一份档案的检查。可先完成或结束它，再检查当前档案。"
                            else "档案已更新。下面仍是检查开始时的内容，可保留这份结果，或按当前内容重新检查。")
                        }
                        if (!completed) item {
                            Text("已检查 ${task.done}/${task.total} 段", fontWeight = FontWeight.SemiBold)
                            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(task.message, style = MaterialTheme.typography.bodyMedium)
                            Text("关闭后进度仍会保留，可从「生成温和版」返回。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (task.hits.isNotEmpty()) Text("目前发现 ${task.hits.size} 处，全部检查完成后可复制或保存。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (completed) item {
                            val removed = task.hits.count { it.id !in task.keptHitIds }
                            Text(if (task.hits.isEmpty()) "未检出政治敏感片段"
                                else "发现 ${task.hits.size} 处 · 本次省略 $removed 处", fontWeight = FontWeight.SemiBold)
                            if (task.hits.isEmpty()) Text("本次输出与检查时的原版一致。", style = MaterialTheme.typography.bodyMedium)
                            if (copyTooLarge) Text("内容较长，请保存为文件。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (task.hits.isNotEmpty()) item {
                            TextButton(onClick = { showHits = !showHits }) {
                                Text(if (showHits) "收起命中原文" else "查看 ${task.hits.size} 处命中原文与原因")
                            }
                        }
                        if (showHits) itemsIndexed(task.hits, key = { _, hit -> hit.id }) { index, hit ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("片段 ${index + 1}", fontWeight = FontWeight.SemiBold)
                                    SelectionContainer { Text(hit.text, style = MaterialTheme.typography.bodyMedium) }
                                    Text(hit.reason, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (completed) Row(Modifier.fillMaxWidth()) {
                                        Checkbox(checked = hit.id in task.keptHitIds, enabled = !acting,
                                            onCheckedChange = { keep -> run {
                                                manager.setKeptHitIds(if (keep) task.keptHitIds + hit.id else task.keptHitIds - hit.id)
                                            } })
                                        Text("保留这段（误判时勾选）", Modifier.weight(1f).padding(top = 12.dp),
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        if (task.status in setOf("completed", "cancelled")) item {
                            TextButton(onClick = { confirmDiscard = true }, enabled = !acting && text != null) {
                                Text(if (changed) "按当前内容重新检查" else "重新检查")
                            }
                        }
                        if (task.status == "paused" && unresolved) item {
                            TextButton(onClick = { confirmDiscard = true }, enabled = !acting) { Text("放弃这次检查") }
                        }
                        if (completed) {
                            item { Text("温和版预览", fontWeight = FontWeight.SemiBold) }
                            if (variant == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if (variantText?.isBlank() == true) item { Text("命中片段全部省略后没有剩余正文。可勾选保留误判片段。") }
                            itemsIndexed(preview) { _, block ->
                                SelectionContainer { Text(block, style = MaterialTheme.typography.bodyMedium) }
                            }
                            if (preview.isNotEmpty()) item {
                                Text("已显示全部 ${variantText?.length ?: 0} 字符", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider()
                ActionRow {
                    when {
                        task == null -> Button(onClick = { plan?.let { prepared -> run { manager.start(prepared) } } },
                            enabled = !acting && !preparing && plan != null && plan?.originalText == text) { Text("开始检查") }
                        completed -> {
                            Button(onClick = { variantText?.let(onCopy) }, enabled = !acting && !copyTooLarge && !variantText.isNullOrBlank()) {
                                Text("复制温和版")
                            }
                            OutlinedButton(onClick = { variantText?.let(onSave) }, enabled = !acting && !variantText.isNullOrBlank()) {
                                Text("保存温和版")
                            }
                        }
                        running -> OutlinedButton(onClick = { run { manager.pause() } }, enabled = !acting) { Text("暂停检查") }
                        task.canResume -> Button(onClick = {
                            if (task.requiresUncertainRetry) confirmUncertainRetry = true
                            else run { manager.resume() }
                        }, enabled = !acting) { Text(if (task.hasUnknownRequest) "恢复检查" else "继续检查") }
                    }
                    if (task != null && !completed && task.status != "cancelled") {
                        TextButton(onClick = { run { manager.cancel() } }, enabled = !acting) { Text("结束检查") }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
    if (confirmUncertainRetry) AlertDialog(
        onDismissRequest = { confirmUncertainRetry = false },
        title = { Text("重新发送未确认的请求？") },
        text = { Text("上次请求可能已经计次或计费，重新发送可能再次消耗次数或费用。已完成的检查结果会保留。") },
        confirmButton = { TextButton(onClick = {
            confirmUncertainRetry = false
            run { manager.resume(retryUncertain = true) }
        }) { Text("确认重新发送") } },
        dismissButton = { TextButton(onClick = { confirmUncertainRetry = false }) { Text("保留进度") } },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(if (unresolved) "放弃未确认的结果？" else "重新准备检查？") },
        text = { Text(if (unresolved)
            "旧请求可能仍在处理或已经计次，放弃后无法在这里取回它的结果。下一次检查会使用新的次数或费用。原档案不变。"
        else "当前检查记录及保留选择将被替换。接下来会先显示当前内容的预计次数，点击「开始检查」才会发送新的请求。原档案不变。") },
        confirmButton = { TextButton(onClick = {
            confirmDiscard = false
            run {
                manager.discard(acknowledgeUncertain = unresolved)
                plan = null
                preparationAttempt++
            }
        }) { Text(if (unresolved) "确认放弃" else "重新准备") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("保留当前检查") } },
    )
}

private fun politicalEstimateText(plan: PoliticalExportPlan): String {
    val count = plan.estimate.chunks
    return if (plan.serviceMode == "companion") {
        "预计 $count 段检查，使用 ${companionRequestCountText(count, plan.maxAttemptsPerChunk)} 次免费额度。" +
            if (plan.maxAttemptsPerChunk > 1) "\n${automaticFallbackNotice(plan.maxAttemptsPerChunk)}" else ""
    }
    else plan.estimate.estimatedCost?.let { "预计 $count 次请求，按所填单价保守估算约 ${"%.4f".format(it)}，实际以服务商账单为准。" }
        ?: "预计 $count 次请求，费用由当前服务商计费。"
}

/** Lazy blocks show the entire result without laying out one unbounded text node. */
internal fun politicalPreviewBlocks(text: String, blockSize: Int = 4000): List<String> {
    require(blockSize >= 2)
    if (text.isEmpty()) return emptyList()
    val blocks = mutableListOf<String>()
    var offset = 0
    while (offset < text.length) {
        var end = minOf(text.length, offset + blockSize)
        if (end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        blocks += text.substring(offset, end)
        offset = end
    }
    return blocks
}
