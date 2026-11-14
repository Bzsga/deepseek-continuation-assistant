package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.util.AtomicFile
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class PoliticalExportPlan(
    val id: String,
    val archiveId: String,
    val originalText: String,
    val estimate: OrganizationEstimate,
    val serviceMode: String,
    val maxAttemptsPerChunk: Int = 1,
)

@Serializable
data class PoliticalExportHit(val id: String, val start: Int, val end: Int, val text: String, val reason: String)

@Serializable
data class PoliticalExportBatch(
    val id: String,
    val parts: List<PoliticalTextPart>,
    val started: Boolean = false,
    val completed: Boolean = false,
    val remoteJobId: String = "",
    val remoteStatus: String = "",
    val attempt: Int = 0,
    val hits: List<PoliticalExportHit> = emptyList(),
) {
    internal fun unknownRequest(): Boolean = !completed && started && remoteJobId.isEmpty() &&
        remoteStatus !in setOf("failed", "cancelled")
}

@Serializable
data class PoliticalExportTask(
    val id: String,
    val archiveId: String,
    val originalText: String,
    val sourceFingerprint: String,
    val settings: ServiceSettings,
    val batches: List<PoliticalExportBatch>,
    val promptVersion: String = "political-filter-v1",
    val status: String = "queued",
    val message: String = "等待检查",
    val keptHitIds: Set<String> = emptySet(),
    // Missing on old local checks; never infer their owner from a later login.
    val ownerUserId: String? = null,
) {
    val done: Int get() = batches.count { it.completed }
    val total: Int get() = batches.size
    val hits: List<PoliticalExportHit> get() = batches.filter { it.completed }.flatMap { it.hits }.sortedBy { it.start }
    val hasUnknownRequest: Boolean get() = batches.any { it.unknownRequest() }
    val hasUnresolvedRemote: Boolean get() = batches.any { !it.completed && it.remoteJobId.isNotEmpty() &&
        it.remoteStatus !in setOf("completed", "failed", "cancelled", "uncertain", "expired") }
    val requiresUncertainRetry: Boolean get() = batches.any { !it.completed &&
        (it.remoteStatus in setOf("uncertain", "expired") || (settings.mode == "byok" && it.unknownRequest())) }
    val canResume: Boolean get() = status in setOf("paused", "cancelled") && done < total

    fun variantText(): String {
        require(status == "completed") { "检查尚未完成，请先继续检查；原文和已完成结果仍保留。" }
        return PoliticalFilterProtocol.omitParts(originalText, batches.flatMap { it.parts },
            hits.map { it.id }.toSet() - keptHitIds)
    }

    internal fun afterProcessDeath(): PoliticalExportTask = if (status !in setOf("queued", "running")) this
        else copy(status = "paused", message = "检查已中断，已完成结果和原请求编号已保存。")

    /** Only an explicit user action can create a fresh attempt after a terminal result. */
    internal fun forExplicitResume(retryUncertain: Boolean): PoliticalExportTask {
        require(canResume) { "此检查当前不能继续" }
        require(!requiresUncertainRetry || retryUncertain) {
            "上次请求的结果未确认，不能自动重新发送；重新发送可能重复计费。"
        }
        val next = batches.map { batch ->
            if (batch.completed) batch else if (batch.remoteStatus in setOf("failed", "cancelled", "uncertain", "expired") ||
                (settings.mode == "byok" && batch.unknownRequest()))
                batch.copy(started = false, remoteJobId = "", remoteStatus = "", attempt = batch.attempt + 1)
            else batch // Live jobs and companion unknown POSTs retain the exact original identity.
        }
        return copy(batches = next, status = "queued", message = "准备继续检查，已完成部分会直接复用。")
    }
}

data class PoliticalExportState(val loaded: Boolean = false, val task: PoliticalExportTask? = null, val error: String? = null)

@Serializable
private data class PoliticalExportDocument(val version: Int = 1, val task: PoliticalExportTask)

/** One private, resumable export copy. No method writes an archive or its sources. */
class PoliticalExportManager internal constructor(
    application: Application,
    private val credentials: SecureCredentials,
    private val scope: CoroutineScope,
) {
    private val file = AtomicFile(File(application.noBackupFilesDir, "political-export-task.json"))
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(PoliticalExportState())
    val state: StateFlow<PoliticalExportState> = mutableState.asStateFlow()
    private var worker: Job? = null
    private var loadFailed = false
    private var prepared: Prepared? = null
    private var accountSession: AccountSession? = null
    private data class Prepared(val plan: PoliticalExportPlan, val settings: ServiceSettings, val batches: List<List<PoliticalTextPart>>)

    init {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val task = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                        val document = file.openRead().use { input ->
                            require(input.available() <= MAX_FILE_BYTES) { "本地检查记录过大" }
                            appJson.decodeFromString<PoliticalExportDocument>(input.readBytes().toString(Charsets.UTF_8))
                        }
                        require(document.version == 1) { "不支持的本地检查记录版本" }
                        validatePoliticalTask(document.task)
                        document.task.afterProcessDeath()
                    } else null
                    if (task != null) persist(task)
                    else mutableState.value = PoliticalExportState(loaded = true)
                } catch (_: Exception) {
                    loadFailed = true
                    mutableState.value = PoliticalExportState(loaded = true, error = "本地检查记录暂时无法读取，原文件已保留。可在设置中清除本地数据。")
                }
                ready.complete(Unit)
            }
        }
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    /** Updates only the current account context; never starts or resumes a request. */
    suspend fun setAccountSession(session: AccountSession?) {
        ready.await()
        mutex.withLock {
            if (accountSession != session && prepared?.settings?.mode == "companion") prepared = null
            accountSession = session
        }
    }

    /** Stop future account requests while preserving original IDs, progress and task credentials. */
    suspend fun suspendAccountTasks() = withContext(Dispatchers.IO) {
        ready.await()
        val running = mutex.withLock {
            val task = mutableState.value.task
            if (task?.settings?.mode != "companion") return@withContext
            if (task.status in setOf("queued", "running")) persist(task.afterAccountLogout())
            worker
        }
        running?.cancelAndJoin()
    }

    suspend fun prepare(archiveId: String, text: String, settings: ServiceSettings, quota: CompanionQuota?): PoliticalExportPlan =
        withContext(Dispatchers.IO) {
            ready.await()
            mutex.withLock {
                require(!loadFailed) { "请先处理无法读取的本地检查记录" }
                require(mutableState.value.task == null) { "已有一份检查记录，请先查看、继续或结束它，再检查新内容。" }
                require(archiveId.isNotBlank() && text.isNotBlank() && text.length <= MAX_TEXT_CHARS) {
                    "续聊文本为空或过长，请减少原文片段后再检查。"
                }
                politicalOwnerForNewTask(settings, accountSession)
                val captured = politicalSettings(settings, quota)
                val batches = PoliticalFilterProtocol.batches(text, captured)
                require(batches.isNotEmpty()) { "没有可检查的文字" }
                val input = batches.sumOf { PoliticalFilterProtocol.estimatedInputTokens(PoliticalFilterProtocol.messages(it)) }
                val output = batches.size * captured.maxOutputTokens
                val cost = if (captured.mode == "byok" && captured.inputPricePerMillion != null && captured.outputPricePerMillion != null)
                    (input * captured.inputPricePerMillion + output * captured.outputPricePerMillion) / 1_000_000.0 else null
                val plan = PoliticalExportPlan(UUID.randomUUID().toString(), archiveId, text,
                    OrganizationEstimate(batches.size, input, output, cost), captured.mode,
                    if (captured.mode == "companion") quota?.fallbackAttemptLimit ?: 1 else 1)
                prepared = Prepared(plan, captured, batches)
                mutableState.update { it.copy(error = null) }
                plan
            }
        }

    suspend fun start(plan: PoliticalExportPlan) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val current = mutableState.value.task
            if (current?.id == plan.id) return@withLock
            require(current == null && !loadFailed) { "已有检查记录，请先处理它" }
            val pending = prepared?.takeIf { it.plan == plan } ?: error("检查预估已失效，请重新查看预计次数。")
            val ownerId = politicalOwnerForNewTask(pending.settings, accountSession)
            val task = PoliticalExportTask(plan.id, plan.archiveId, plan.originalText, hash(plan.originalText),
                pending.settings.withoutSecrets(), pending.batches.mapIndexed { index, parts ->
                    PoliticalExportBatch(hash("political-batch-v1\n${plan.id}\n$index\n${parts.joinToString { it.id }}"), parts)
                }, ownerUserId = ownerId)
            val secret = if (pending.settings.mode == "byok") pending.settings.apiKey else pending.settings.trialToken
            credentials.put(SECRET_REF, secret)
            try { persist(task) } catch (error: Exception) { credentials.remove(SECRET_REF); throw error }
            prepared = null
            launchLocked()
        }
    }

    suspend fun resume(retryUncertain: Boolean = false) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            require(worker?.isActive != true) { "检查正在停止，请稍后继续" }
            val original = current()
            val secret = politicalResumeCredential(original, accountSession, credentials.get(SECRET_REF))
            val task = original.forExplicitResume(retryUncertain)
            credentials.put(SECRET_REF, secret)
            persist(task)
            launchLocked()
        }
    }

    suspend fun pause() = withContext(Dispatchers.IO) {
        ready.await()
        val running = mutex.withLock {
            val task = mutableState.value.task
            if (task != null && task.status in setOf("queued", "running")) persist(task.copy(status = "paused",
                message = "已暂停后续检查。已发出的请求可能继续计次，恢复时会先查询原请求。"))
            worker
        }
        running?.cancelAndJoin()
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        pause()
        val task = mutex.withLock {
            val task = mutableState.value.task ?: return@withContext
            if (task.status == "completed") return@withContext
            persist(task.copy(status = "cancelled", message = "已停止后续检查，已完成的部分仍保留。"))
            task
        }
        if (task.settings.mode == "companion") {
            for (batch in task.batches.filter { !it.completed && it.remoteJobId.isNotEmpty() &&
                it.remoteStatus !in setOf("completed", "failed", "cancelled", "uncertain", "expired") }) {
                try {
                    val settings = mutex.withLock {
                        val secret = politicalReadCredential(task, accountSession, credentials.get(SECRET_REF))
                        task.settings.copy(trialToken = secret)
                    }
                    val remote = SummaryNetwork.cancel(settings, batch.remoteJobId)
                    mutex.withLock { updateBatch(task.id, batch.id) { it.copy(remoteStatus = remote.status) } }
                    if (remote.status == "completed") saveResults(task.id, batch, remote.entries)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Preserve the original remote identity for a later query. */ }
            }
        }
        mutex.withLock {
            val latest = current()
            if (latest.id == task.id && latest.status != "completed") {
                if (latest.done == latest.total) completeLocked(latest)
                else persist(latest.copy(message = if (latest.hasUnknownRequest || latest.hasUnresolvedRemote)
                    "已停止后续检查；部分已发出的请求尚未确认，原编号仍保留，可能继续计次。"
                    else "已取消检查，原文和已完成的部分仍保留。"))
            }
        }
    }

    suspend fun setKeptHitIds(ids: Set<String>) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val task = current()
            require(task.hits.map { it.id }.toSet().containsAll(ids)) { "选择中包含不存在的检查结果" }
            persist(task.copy(keptHitIds = ids))
        }
    }

    suspend fun discard(acknowledgeUncertain: Boolean = false) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            require(worker?.isActive != true && mutableState.value.task?.status !in setOf("queued", "running")) {
                "请先暂停或取消正在进行的检查"
            }
            val task = mutableState.value.task
            require(acknowledgeUncertain || task == null || (!task.hasUnknownRequest && !task.hasUnresolvedRemote)) {
                "仍有请求状态未确认，请先恢复查询；放弃结果可能仍会计次。"
            }
            eraseLocked()
        }
    }

    /** Called only by the explicit clear-local-data action; never dispatches a recovery POST. */
    internal suspend fun clearLocalData(): String? = withContext(Dispatchers.IO) {
        ready.await()
        cancel()
        mutex.withLock {
            val task = mutableState.value.task
            val notice = if (task != null && (task.hasUnknownRequest || task.hasUnresolvedRemote))
                "检查副本已清除，但先前已发出的检查请求尚未确认，可能仍会计次。" else null
            eraseLocked()
            notice
        }
    }

    private fun launchLocked() {
        if (worker?.isActive == true) return
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try { run() } finally {
                val finished = coroutineContext[Job]
                withContext(NonCancellable) { mutex.withLock { if (worker === finished) worker = null } }
            }
        }
        worker = job
        job.start()
    }

    private suspend fun run() {
        var activeBatchId: String? = null
        try {
            mutex.withLock {
                val task = current()
                if (task.status != "queued") return
                require(task.promptVersion == PoliticalFilterProtocol.PROMPT_VERSION) { "不支持此检查协议，请保留记录并更新应用。" }
                persist(task.copy(status = "running", message = "正在检查政治相关内容，已完成结果会即时保存。"))
            }
            while (true) {
                val pair = mutex.withLock {
                    val task = current()
                    if (task.status != "running") return
                    val batch = task.batches.firstOrNull { !it.completed }
                    if (batch == null) { completeLocked(task); return }
                    task to batch
                }
                val (task, batch) = pair
                activeBatchId = batch.id
                val settings = withSecret(task)
                val messages = PoliticalFilterProtocol.messages(batch.parts)
                PoliticalFilterProtocol.validateForRequest(settings, messages)
                val entries = if (settings.mode == "byok") {
                    require(!batch.started) { "上次自有 API 请求结果未确认，不能自动重新发送。" }
                    markStarted(task.id, batch.id)
                    SummaryNetwork.filterPolitical(settings, messages)
                } else runCompanion(task, batch, settings)
                withContext(NonCancellable) { saveResults(task.id, batch, entries) }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { mutex.withLock {
                mutableState.value.task?.takeIf { it.status in setOf("queued", "running") }?.let {
                    persist(it.copy(status = "paused", message = "检查中断，已保留进度和原请求编号。"))
                }
            } }
            throw cancelled
        } catch (error: Exception) {
            mutex.withLock {
                val task = mutableState.value.task ?: return
                if (task.status == "completed") return
                val next = task.batches.map { batch ->
                    if (batch.id != activeBatchId || batch.completed) batch
                    else if (error is SummaryFailure && error.expired) batch.copy(remoteStatus = "expired")
                    else if (batch.remoteJobId.isNotEmpty() || !batch.started) batch
                    else if (error is SummaryFailure && !error.ambiguous) batch.copy(remoteStatus = "failed")
                    else if (task.settings.mode == "byok") batch.copy(remoteStatus = "uncertain")
                    else batch // Unknown companion POST must be recovered with this same identity.
                }
                persist(task.copy(batches = next, status = "paused",
                    message = error.message?.take(1200)?.ifBlank { null } ?: "检查暂停，已保留原文和完成的部分。"))
            }
        }
    }

    private suspend fun runCompanion(task: PoliticalExportTask, batch: PoliticalExportBatch, settings: ServiceSettings): List<ArchiveEntry> {
        var remote = if (batch.remoteJobId.isNotEmpty()) SummaryNetwork.poll(settings, batch.remoteJobId) else {
            if (!batch.started) {
                val quota = SummaryNetwork.getQuota(settings)
                val current = politicalSettings(settings, quota)
                if (current.model != settings.model || current.contextTokens != settings.contextTokens ||
                    current.maxOutputTokens != settings.maxOutputTokens)
                    throw SummaryFailure("服务预算已变化，已保留原检查。请结束后查看新的预计次数再检查。", ambiguous = false)
                markStarted(task.id, batch.id)
            }
            mutex.withLock {
                val current = current()
                if (current.id != task.id || current.status != "running") throw CancellationException()
                requirePoliticalSubmission(task, batch, settings, accountSession)
            }
            val submitted = SummaryNetwork.submitPolitical(settings, PoliticalFilterProtocol.messages(batch.parts),
                "${task.id}:${batch.id}:${batch.attempt}")
            withContext(NonCancellable) { mutex.withLock {
                updateBatch(task.id, batch.id) { it.copy(started = true, remoteJobId = submitted.id, remoteStatus = submitted.status) }
            } }
            submitted
        }
        repeat(1200) {
            withContext(NonCancellable) { mutex.withLock {
                updateBatch(task.id, batch.id) { it.copy(remoteStatus = remote.status) }
            } }
            when (remote.status) {
                "completed" -> return remote.entries
                "failed", "cancelled" -> throw SummaryFailure(remote.message.ifBlank { "本段检查未完成，已保留此前结果。" }, ambiguous = false)
                "uncertain" -> throw SummaryFailure(remote.message.ifBlank { "本段检查结果未确认，重新发送可能再次计费。" }, ambiguous = true)
                "queued", "running" -> Unit
                else -> throw SummaryFailure("服务返回未知检查状态，已保留原请求编号。", ambiguous = true)
            }
            delay(1500)
            remote = SummaryNetwork.poll(settings, remote.id)
        }
        throw SummaryFailure("服务仍在检查，原任务编号已保留，可稍后继续查询。", ambiguous = false)
    }

    private suspend fun markStarted(taskId: String, batchId: String) = mutex.withLock {
        val task = current()
        if (task.id != taskId || task.status != "running") throw CancellationException()
        if (task.settings.mode == "companion") {
            val batch = task.batches.first { it.id == batchId }
            requirePoliticalSubmission(task, batch, withSecret(task), accountSession)
        }
        updateBatch(taskId, batchId) { it.copy(started = true) }
    }

    private suspend fun saveResults(taskId: String, batch: PoliticalExportBatch, entries: List<ArchiveEntry>) {
        val validated = PoliticalFilterProtocol.validateEntries(entries, PoliticalFilterProtocol.messages(batch.parts))
        val parts = batch.parts.associateBy { it.id }
        val hits = validated.map { entry ->
            val part = parts.getValue(entry.sourceIds.single())
            PoliticalExportHit(part.id, part.start, part.end, part.text, entry.text)
        }
        mutex.withLock {
            val task = current()
            if (task.id != taskId) return
            val next = task.copy(batches = task.batches.map { if (it.id == batch.id) it.copy(completed = true, hits = hits,
                remoteStatus = if (it.remoteJobId.isNotEmpty()) "completed" else it.remoteStatus) else it })
            if (next.done == next.total) completeLocked(next)
            else persist(next.copy(message = "已检查 ${next.done}/${next.total} 段，结果已保存。"))
        }
    }

    private fun updateBatch(taskId: String, batchId: String, change: (PoliticalExportBatch) -> PoliticalExportBatch) {
        val task = current()
        require(task.id == taskId) { "检查任务已变化" }
        persist(task.copy(batches = task.batches.map { if (it.id == batchId) change(it) else it }))
    }

    private fun completeLocked(task: PoliticalExportTask) {
        persist(task.copy(status = "completed", message = if (task.hits.isEmpty())
            "本次检查未标记政治相关段落；这不代表目标平台一定接受。"
            else "检查完成，标记了 ${task.hits.size} 处可能影响接续的政治相关内容。可保留误判段落后生成副本。"))
        credentials.remove(SECRET_REF)
    }

    private fun current(): PoliticalExportTask = mutableState.value.task ?: error("没有可继续的检查记录")

    private fun withSecret(task: PoliticalExportTask): ServiceSettings {
        val secret = credentials.get(SECRET_REF)
        require(secret.isNotBlank()) { "原服务凭据不可用，已保留原文和完成结果。" }
        return if (task.settings.mode == "byok") task.settings.copy(apiKey = secret) else task.settings.copy(trialToken = secret)
    }

    private fun persist(task: PoliticalExportTask) {
        val bytes = appJson.encodeToString(PoliticalExportDocument(task = task)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "检查记录过大，已保留此前进度" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
        mutableState.value = PoliticalExportState(loaded = true, task = task)
    }

    private fun eraseLocked() {
        file.delete()
        credentials.remove(SECRET_REF)
        prepared = null
        loadFailed = false
        mutableState.value = PoliticalExportState(loaded = true)
    }

    private companion object {
        const val SECRET_REF = "political_export_active"
        const val MAX_TEXT_CHARS = 2_000_000
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
    }
}

internal fun politicalSettings(settings: ServiceSettings, quota: CompanionQuota?): ServiceSettings {
    val captured = if (settings.mode == "companion") {
        require(quota != null && quota.politicalFilterSupported) { "当前免费服务尚未开放政治内容检查，请待服务更新后使用，或选择自己的 AI 服务。" }
        quota.organizationBlocker()?.let { error(it) }
        settings.copy(model = quota.model, contextTokens = quota.contextTokens ?: error("服务未提供检查预算"),
            maxOutputTokens = quota.maxOutputTokens ?: error("服务未提供检查预算"))
    } else settings
    val cleaned = validateSettings(captured)
    val secret = if (cleaned.mode == "byok") cleaned.apiKey else cleaned.trialToken
    require(secret.isNotBlank() && secret.length <= 8192 && secret.all { it.code in 33..126 }) { "请先配置有效的服务凭据。" }
    return cleaned
}

internal fun PoliticalExportTask.afterAccountLogout(): PoliticalExportTask =
    if (settings.mode != "companion" || status !in setOf("queued", "running")) this else copy(
        status = "paused", message = "账户已退出，后续检查已暂停。已完成结果和原请求编号仍保留，请登录原账户后继续。",
    )

internal fun politicalOwnerForNewTask(settings: ServiceSettings, session: AccountSession?): String? {
    if (settings.mode != "companion") return null
    require(session != null && !accountSessionExpired(session) &&
        samePoliticalService(session.serviceUrl, settings.companionUrl) && session.token == settings.trialToken) {
        "请先登录账户，再使用免费内容检查。"
    }
    return session.user.id
}

/** Explicit resume may rebind only a known owner's current login, never another account. */
internal fun politicalResumeCredential(task: PoliticalExportTask, session: AccountSession?, originalSecret: String): String {
    if (task.settings.mode == "byok") {
        require(originalSecret.isNotBlank()) { "原服务凭据不可用；已保留原文和检查结果。" }
        return originalSecret
    }
    if (task.ownerUserId != null) {
        require(session != null && session.user.id == task.ownerUserId && !accountSessionExpired(session) &&
            samePoliticalService(session.serviceUrl, task.settings.companionUrl)) {
            "请登录创建此检查的原账户后继续。"
        }
        return session.token
    }
    require(originalSecret.isNotBlank()) { "旧检查的原服务凭据不可用；已保留原文和检查结果。" }
    val firstPending = task.batches.firstOrNull { !it.completed }
    require(firstPending != null && firstPending.started &&
        firstPending.remoteStatus !in setOf("failed", "cancelled", "uncertain", "expired")) {
        "旧检查的账户归属无法确认，只能查询已发出的原请求。可保留结果，重新登录后创建检查。"
    }
    return originalSecret
}

/** A current login of the same owner can read/cancel old work without rebinding future POSTs. */
internal fun politicalReadCredential(task: PoliticalExportTask, session: AccountSession?, originalSecret: String): String {
    if (task.settings.mode == "companion" && task.ownerUserId != null && session != null &&
        session.user.id == task.ownerUserId && !accountSessionExpired(session) &&
        samePoliticalService(session.serviceUrl, task.settings.companionUrl)) return session.token
    require(originalSecret.isNotBlank()) { "原服务凭据不可用，已保留原请求编号。请登录原账户后继续。" }
    return originalSecret
}

/** Old anonymous unknown POSTs can only recover their exact server-side idempotent job. */
internal fun requirePoliticalSubmission(task: PoliticalExportTask, batch: PoliticalExportBatch,
    settings: ServiceSettings, session: AccountSession?) {
    if (settings.mode != "companion") return
    if (task.ownerUserId == null) {
        require(batch.started && batch.remoteJobId.isBlank() &&
            batch.remoteStatus !in setOf("failed", "cancelled", "uncertain", "expired")) {
            "旧检查只能查询原请求，不能使用后来的账户继续派发。"
        }
        return
    }
    require(session != null && session.user.id == task.ownerUserId && !accountSessionExpired(session) &&
        samePoliticalService(session.serviceUrl, settings.companionUrl) && session.token == settings.trialToken) {
        "账户登录已变化，请登录原账户后继续检查。"
    }
}

private fun samePoliticalService(first: String, second: String): Boolean = runCatching {
    SummaryNetwork.checkedBase(first).toString().trimEnd('/') == SummaryNetwork.checkedBase(second).toString().trimEnd('/')
}.getOrDefault(false)

/** Validate a local snapshot before it can produce a variant or resume a paid request. */
internal fun validatePoliticalTask(task: PoliticalExportTask) {
    require(task.id.isNotBlank() && task.archiveId.isNotBlank() && task.originalText.isNotBlank() &&
        hash(task.originalText) == task.sourceFingerprint) { "检查记录与原文不匹配" }
    require(task.settings.apiKey.isEmpty() && task.settings.trialToken.isEmpty()) { "检查记录不应包含凭据" }
    require(task.ownerUserId == null || (task.settings.mode == "companion" && task.ownerUserId.isNotBlank() &&
        task.ownerUserId.length <= 200)) { "检查账户归属无效" }
    require(task.promptVersion == PoliticalFilterProtocol.PROMPT_VERSION && task.batches.isNotEmpty()) { "检查协议或分段无效" }
    require(task.status in setOf("queued", "running", "paused", "cancelled", "completed")) { "检查状态无效" }
    require(task.batches.map { it.id }.distinct().size == task.batches.size) { "检查分段编号重复" }
    val parts = task.batches.flatMap { it.parts }
    PoliticalFilterProtocol.omitParts(task.originalText, parts, emptySet())
    var coveredUntil = 0
    parts.forEach { part ->
        require(part.start >= coveredUntil && task.originalText.substring(coveredUntil, part.start).isBlank()) {
            "检查分段遗漏了原文内容"
        }
        coveredUntil = part.end
    }
    require(task.originalText.substring(coveredUntil).isBlank()) { "检查分段遗漏了原文内容" }
    task.batches.forEach { batch ->
        require(batch.parts.isNotEmpty() && batch.attempt >= 0 && (batch.completed || batch.hits.isEmpty())) { "检查分段状态无效" }
        val byId = batch.parts.associateBy { it.id }
        require(batch.hits.map { it.id }.distinct().size == batch.hits.size && batch.hits.all { hit ->
            val part = byId[hit.id]
            part != null && part.start == hit.start && part.end == hit.end && part.text == hit.text &&
                hit.reason.isNotBlank() && hit.reason.length <= 400
        }) { "检查结果引用无效原文" }
    }
    require(task.hits.map { it.id }.toSet().containsAll(task.keptHitIds)) { "保留选择引用无效结果" }
    require(task.status != "completed" || task.done == task.total) { "检查记录缺少已完成结果" }
}
