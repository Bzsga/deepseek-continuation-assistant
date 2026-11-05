package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ArchiveLogic
import app.banzhan.deepseekcontinuation.core.MergeResult
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ReorganizationLogic
import kotlinx.serialization.encodeToString
import java.time.OffsetDateTime

internal fun settingsForSave(previous: ServiceSettings, requested: ServiceSettings): ServiceSettings {
    val cleaned = validateSettings(requested)
    return cleaned.copy(
        apiKey = if (cleaned.baseUrl != previous.baseUrl && cleaned.apiKey == previous.apiKey) "" else cleaned.apiKey,
        trialToken = if (cleaned.companionUrl != previous.companionUrl) "" else cleaned.trialToken,
    )
}

internal fun migrateUnconfiguredService(settings: ServiceSettings): ServiceSettings {
    val defaults = ServiceSettings()
    val untouchedByok = settings.mode == "byok" && settings.apiKey.isBlank() &&
        settings.baseUrl.trimEnd('/') == defaults.baseUrl && settings.model == defaults.model &&
        settings.contextTokens == defaults.contextTokens && settings.maxOutputTokens == defaults.maxOutputTokens &&
        settings.inputPricePerMillion == null && settings.outputPricePerMillion == null
    return if (untouchedByok) settings.copy(mode = "companion") else settings
}

internal fun companionIdentityKey(serviceUrl: String): String =
    "companion_identity:" + hash(SummaryNetwork.checkedBase(serviceUrl).toString().trimEnd('/'))

internal fun sameCompanionIdentity(first: ServiceSettings, second: ServiceSettings): Boolean =
    first.mode == "companion" && second.mode == "companion" && first.trialToken == second.trialToken &&
        runCatching { companionIdentityKey(first.companionUrl) == companionIdentityKey(second.companionUrl) }.getOrDefault(false)

internal fun settingsWithServerBudget(settings: ServiceSettings, quota: CompanionQuota,
    promptVersion: String = SummaryProtocol.PROMPT_VERSION): ServiceSettings {
    val context = quota.contextTokens ?: error("服务未提供分段预算，请刷新免费额度或联系管理员更新服务。")
    val output = quota.maxOutputTokens ?: error("服务未提供输出预算，请刷新免费额度或联系管理员更新服务。")
    require(quota.model.isNotBlank()) { "免费服务尚未配置模型，请稍后再试。" }
    val captured = settings.copy(model = quota.model, contextTokens = context, maxOutputTokens = output)
    validateSettings(captured)
    SummaryProtocol.chunkCharacters(captured, promptVersion)
    return captured
}

internal fun developerSessionExpired(prefs: DeveloperPrefs): Boolean = runCatching {
    OffsetDateTime.parse(prefs.expiresAt).toInstant().toEpochMilli() <= System.currentTimeMillis()
}.getOrDefault(true)

internal fun developerPrefsForService(prefs: DeveloperPrefs, serviceUrl: String): DeveloperPrefs {
    val normalized = SummaryNetwork.checkedBase(serviceUrl).toString().trimEnd('/')
    return if (normalized == prefs.serviceUrl) prefs else DeveloperPrefs(serviceUrl = normalized)
}

internal fun requireTaskSlot(archiveId: String, taskId: String?, tasks: List<TaskRecord>) {
    require(tasks.none { it.archiveId == archiveId && it.id != taskId && it.status in setOf("queued", "running", "paused") }) {
        "该档案已有其他未完成任务，请先继续或取消它"
    }
}

internal fun taskSourcesFit(task: TaskRecord, allowedIds: Set<String>): Boolean =
    task.chunks.all { chunk -> chunk.messages.all { it.id in allowedIds } }

internal fun TaskRecord.afterDispatchFailures(failures: List<ChunkDispatchFailure>): TaskRecord {
    var updated = withoutInFlight()
    var needsAcknowledgment = uncertain
    for (failure in failures) {
        val cause = failure.error
        val chunk = chunks.first { it.id == failure.chunk.id }
        val expired = cause is SummaryFailure && cause.expired
        needsAcknowledgment = needsAcknowledgment || expired ||
            (if (cause is SummaryFailure) cause.ambiguous else chunk.requestStarted || chunk.inFlight)
        updated = updated.updateChunk(chunk.id) {
            when {
                expired -> it.copy(remoteStatus = "expired")
                // Refusal of an idempotent recovery says nothing about whether
                // its older POST was admitted. Only a newly attempted request
                // can safely lose its started marker after a definite refusal.
                cause is SummaryFailure && !cause.ambiguous && it.remoteJobId.isBlank() &&
                    !failure.chunk.requestStarted -> it.copy(requestStarted = false)
                else -> it
            }
        }
    }
    return updated.copy(status = "paused", uncertain = needsAcknowledgment || updated.hasUncertainRequest(),
        message = failures.first().error.message?.take(1500) ?: "整理暂停，请检查网络和服务设置")
}

/** Accumulate every successful piece without putting chunk boundaries into entry identity. */
internal fun aggregateChunkEntries(parts: List<List<ArchiveEntry>>): List<ArchiveEntry> =
    parts.flatten().groupBy { it.id }.values.map { group ->
        val first = group.first()
        require(group.all { it.category == first.category && it.kind == first.kind && it.sourceIds.toSet() == first.sourceIds.toSet() &&
            it.continuityCue == first.continuityCue }) { "分块来源身份不一致" }
        val text = group.map { it.text }.distinct().joinToString("\n")
        require(text.length <= 100_000) { "同一来源的摘要超过档案条目上限，分块结果已保存" }
        first.copy(text = text, pinned = false, edited = false, deleted = false)
    }

internal fun mergeTaskProgress(
    existing: List<ArchiveEntry>, previousProgress: List<ArchiveEntry>, currentProgress: List<ArchiveEntry>,
    rebuilding: Boolean = false,
): MergeResult {
    val previousById = previousProgress.associateBy { it.id }
    // Only replace an unchanged output produced by this task. User edits and tombstones stay protected.
    val preserved = existing.filterNot { entry -> !entry.edited && !entry.pinned && !entry.deleted && entry == previousById[entry.id] }
    return if (rebuilding) ReorganizationLogic.mergeRebuiltEntries(preserved, currentProgress)
        else ArchiveLogic.merge(preserved, currentProgress)
}

/** Normal tasks retain their historical key; a rebuild can only reuse its own paid results. */
internal fun organizationCacheKey(settings: ServiceSettings, promptVersion: String, chunkId: String,
    messages: List<ChatMessage>, reorganizationTaskId: String? = null): String {
    val ordinary = hash(appJson.encodeToString(settings.withoutSecrets()) + "\n" + promptVersion + "\n" +
        chunkId + "\n" + appJson.encodeToString(messages))
    return if (reorganizationTaskId == null) ordinary else hash("reorganization-v1\n$reorganizationTaskId\n$ordinary")
}

internal fun TaskRecord.blocksReorganization(): Boolean = status in setOf("queued", "running", "paused") ||
    hasUnknownRequest() || chunks.any { !it.completed && it.remoteJobId.isNotBlank() &&
        it.remoteStatus !in setOf("failed", "uncertain", "cancelled", "expired") }
