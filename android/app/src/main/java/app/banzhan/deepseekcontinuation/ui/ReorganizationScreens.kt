@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.data.*

internal fun preferredRebuiltArchive(
    archiveId: String,
    archives: List<Archive>,
    tasks: List<OrganizationTask>,
): Archive? = archives.filter { it.rebuiltFromArchiveId == archiveId }
    .sortedWith(compareByDescending<Archive> { archive ->
        tasks.any { it.archiveId == archive.id && it.status in setOf("queued", "running", "paused") }
    }.thenByDescending { it.updatedAt })
    .firstOrNull()

@Composable
internal fun ArchiveReorganizationActions(
    archive: Archive,
    archives: List<Archive>,
    tasks: List<OrganizationTask>,
    busy: Boolean,
    onReorganize: () -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    val rebuilt = remember(archive.id, archives, tasks) {
        preferredRebuiltArchive(archive.id, archives, tasks)
    }
    val rebuiltTask = tasks.firstOrNull { it.archiveId == rebuilt?.id && it.status != "completed" }
    ActionRow {
        archive.rebuiltFromArchiveId?.let { originalId ->
            if (archives.any { it.id == originalId }) {
                TextButton(onClick = { onOpenRecord(originalId) }, enabled = !busy) { Text("返回原档案") }
            } else {
                Text("原档案已不在本机", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (rebuilt != null) {
            TextButton(onClick = { onOpenRecord(rebuilt.id) }, enabled = !busy) {
                Text(when (rebuiltTask?.status) {
                    "queued", "running" -> "查看整理进度"
                    "paused" -> "继续上次整理"
                    "cancelled" -> "查看上次整理"
                    else -> if (rebuiltTask == null) "查看新版" else "查看整理进度"
                })
            }
        }
        if (rebuiltTask?.status !in setOf("queued", "running", "paused")) {
            TextButton(onClick = onReorganize, enabled = !busy) { Text("重新整理") }
        }
    }
}

@Composable
internal fun ReorganizationDialog(
    plan: ReorganizationPlan,
    state: AppState,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onSelectRange: () -> Unit,
    onSettings: () -> Unit,
    onQuotaRefresh: () -> Unit,
    onRefresh: () -> Unit,
) {
    val settings = state.settings
    val companion = settings.mode == "companion"
    val configured = if (companion) settings.companionUrl.isNotBlank()
        else settings.baseUrl.isNotBlank() && settings.apiKey.isNotBlank()
    val quotaReady = !companion || (state.quota != null && !state.quotaLoading &&
        state.quotaError == null && state.quota.organizationBlocker() == null)
    val canConfirm = !busy && configured && quotaReady && plan.blockedReason == null && plan.estimate != null
    AppDialog(
        "重新整理这份档案",
        { if (!busy) onDismiss() },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRefresh, enabled = !busy) { Text("重新检查") }
                }
                ActionRow {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    if (plan.needsSourceSelection) {
                        OutlinedButton(onClick = onSelectRange, enabled = !busy) { Text("重新选择范围") }
                    } else {
                        Button(onClick = onConfirm, enabled = canConfirm) {
                            Text(if (busy) "请稍候…" else "确认并重新整理")
                        }
                    }
                }
            }
        },
    ) {
        Text(plan.archiveTitle, fontWeight = FontWeight.SemiBold)
        Text("另存一份新版，原档案完整保留。")
        Text(
            if (plan.preservedEntryCount > 0)
                "新版会保留 ${plan.preservedEntryCount} 条手动修改、固定或删除记录，以及个人设置。"
            else "新版会沿用补充背景和个人设置。",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()
        Text("本次整理范围", fontWeight = FontWeight.SemiBold)
        Text(plan.sourceDescription, style = MaterialTheme.typography.bodyMedium)
        if (plan.rangeOrigin == ReorganizationRangeOrigin.USER_SELECTION) {
            TextButton(onClick = onSelectRange, enabled = !busy) { Text("调整本次范围") }
        }
        if (plan.messageCount > 0)
            Text("${plan.conversationCount} 段聊天 · ${plan.messageCount} 条消息", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.blockedReason?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
        Text(if (companion) "免费智能整理服务" else "自有 API · ${settings.model}", fontWeight = FontWeight.SemiBold)
        Text(if (companion) settings.companionUrl else settings.baseUrl,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.estimate?.let { estimate ->
            Text("点击确认会将上述消息发送给此服务。")
            if (companion) {
                Text("预计使用 ${state.quota.requestCountText(estimate.chunks)} 次免费模型请求。", style = MaterialTheme.typography.bodyMedium)
            } else if (estimate.estimatedCost != null) {
                Text("按填写单价估算约 ${"%.4f".format(estimate.estimatedCost)}（与单价同币种），实际费用以服务商账单为准。")
            } else {
                Text("此服务会按实际使用计费。未填写单价，暂时无法估算金额。")
            }
        }
        if (companion) FreeQuotaCard(state, onQuotaRefresh)
        if (!configured) Text("请先完成服务设置。", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onSettings, enabled = !busy) { Text("服务设置") }
    }
}
