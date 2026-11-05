package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityLogic
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import kotlinx.serialization.Serializable

internal data class ResolvedReorganizationSources(
    val sourceScopes: List<ArchiveSourceScope>,
    val messageGroups: List<List<ChatMessage>>,
    val rangeOrigin: ReorganizationRangeOrigin,
    val description: String,
)

/** Resolve only a recorded or explicitly selected range; never infer the whole conversation. */
internal fun resolveReorganizationSources(
    archive: Archive,
    conversations: List<ImportedConversation>,
    tasks: List<TaskRecord>,
    confirmedScopes: List<ArchiveSourceScope>? = null,
): ResolvedReorganizationSources {
    if (confirmedScopes != null) return resolveScopes(archive, conversations, confirmedScopes,
        ReorganizationRangeOrigin.USER_SELECTION, "按本次重新选择的范围")
    if (archive.sourceScopes.isNotEmpty()) return resolveScopes(archive, conversations, archive.sourceScopes,
        ReorganizationRangeOrigin.SAVED_SCOPE, "沿原选择的范围")

    val historical = tasks.filter { it.archiveId == archive.id }
    require(historical.isNotEmpty()) { "旧档案未保存完整选择范围，请重新选择要整理的原文。" }
    val scopes = historical.map { task -> historicalScope(archive, conversations, task) }
    val scope = scopes.first()
    require(scopes.all { it == scope }) { "历史整理使用了不同范围，无法确认原选区，请重新选择原文。" }
    val selectedIds = scope.selectedMessageIds.toSet()
    require(archive.entries.all { entry -> entry.sourceIds.all { it in selectedIds } }) {
        "历史整理范围未覆盖档案的全部来源，包括已修改或删除的内容，请重新选择原文。"
    }
    return resolveScopes(archive, conversations, listOf(scope), ReorganizationRangeOrigin.HISTORICAL_TASK,
        "按上次整理的${scope.selectedMessageIds.size}条原文")
}

private fun resolveScopes(
    archive: Archive,
    conversations: List<ImportedConversation>,
    scopes: List<ArchiveSourceScope>,
    origin: ReorganizationRangeOrigin,
    description: String,
): ResolvedReorganizationSources {
    require(scopes.isNotEmpty()) { "请选择要重新整理的原文。" }
    ContinuityLogic.validateSourceScopes(scopes, conversations, archive.conversationIds.toSet())
    val normalizedScopes = scopes.groupBy { it.conversationId to it.branchId }.map { (source, selections) ->
        val conversation = conversations.single { it.id == source.first }
        val branch = conversation.branches.single { it.id == source.second }
        val selectedIds = selections.flatMap { it.selectedMessageIds }.toSet()
        ArchiveSourceScope(conversation.id, branch.id, branch.messages.filter { it.id in selectedIds }.map { it.id })
    }
    val groups = normalizedScopes.map { scope ->
        val conversation = conversations.single { it.id == scope.conversationId }
        val branch = conversation.branches.single { it.id == scope.branchId }
        val ids = scope.selectedMessageIds.toSet()
        branch.messages.filter { it.id in ids && it.content.isNotBlank() }
    }
    require(groups.any { it.isNotEmpty() }) { "所选范围没有可重新整理的文字，请重新选择原文。" }
    return ResolvedReorganizationSources(normalizedScopes, groups, origin, description)
}

private fun historicalScope(
    archive: Archive,
    conversations: List<ImportedConversation>,
    task: TaskRecord,
): ArchiveSourceScope {
    require(task.chunks.isNotEmpty() && task.chunks.all { it.messages.isNotEmpty() }) {
        "历史整理缺少原消息，无法确认完整选区，请重新选择原文。"
    }
    val messages = reassembleMessages(task)
    val selectedIds = messages.map { it.id }.toSet()
    val matches = conversations.asSequence().filter { it.id in archive.conversationIds }.flatMap { conversation ->
        conversation.branches.asSequence().mapNotNull { branch ->
            // Equality checks full content, role, timestamp and attachments, as well as source order.
            if (branch.messages.filter { it.id in selectedIds } == messages)
                ArchiveSourceScope(conversation.id, branch.id, messages.map { it.id })
            else null
        }
    }.take(2).toList()
    require(matches.size == 1) { "历史原文不完整或聊天版本无法唯一确认，请重新选择要整理的原文。" }
    return matches.single()
}

/** Split pieces retain their source ID; only adjacent pieces with identical metadata may join. */
private fun reassembleMessages(task: TaskRecord): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    val seen = mutableSetOf<String>()
    var original: ChatMessage? = null
    val content = StringBuilder()

    fun flush() {
        val message = original ?: return
        require(message.id.isNotBlank() && seen.add(message.id)) {
            "历史消息分片顺序无法确认，请重新选择原文。"
        }
        result += message.copy(content = content.toString())
        content.setLength(0)
    }

    for (chunk in task.chunks) for (part in chunk.messages) {
        val previous = original
        if (previous == null || previous.id != part.id) {
            flush()
            original = part
        } else require(previous.role == part.role && previous.timestamp == part.timestamp &&
            previous.attachments == part.attachments) {
            "历史消息分片的来源信息不一致，请重新选择原文。"
        }
        content.append(part.content)
    }
    flush()
    return result
}

@Serializable
private data class ReorganizationSourceSnapshot(
    val archive: Archive,
    val sourceScopes: List<ArchiveSourceScope>,
    val messageGroups: List<List<ChatMessage>>,
)

/** Uses the shared streaming JSON digest; no serialized transcript-sized String is allocated. */
internal fun reorganizationSourceFingerprint(
    archive: Archive, resolved: ResolvedReorganizationSources,
): String = serializedHash(ReorganizationSourceSnapshot.serializer(),
    ReorganizationSourceSnapshot(archive, resolved.sourceScopes, resolved.messageGroups))
