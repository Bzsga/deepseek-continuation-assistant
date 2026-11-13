package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

data class AppState(
    val conversations: List<ImportedConversation> = emptyList(),
    val archives: List<Archive> = emptyList(),
    val settings: ServiceSettings = ServiceSettings(),
    val tasks: List<OrganizationTask> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val lastImportedConversationIds: List<String> = emptyList(),
    val quota: CompanionQuota? = null,
    val quotaLoading: Boolean = false,
    val quotaError: String? = null,
    val developerPrefs: DeveloperPrefs = DeveloperPrefs(),
    val adminConfig: AdminConfig? = null,
    val adminLoading: Boolean = false,
    val adminError: String? = null,
    val adminStats: AdminStats? = null,
    val adminStatsLoading: Boolean = false,
    val adminStatsError: String? = null,
    val adminUsers: AdminUsersPage? = null,
    val adminUsersLoading: Boolean = false,
    val adminUsersError: String? = null,
    val connectionTestLoading: Boolean = false,
    val connectionTestResult: ConnectionTestResult? = null,
    val connectionTestError: String? = null,
    val feedbackDraft: FeedbackDraft = FeedbackDraft(),
    val feedbackSubmitting: Boolean = false,
    val feedbackImageLoading: Boolean = false,
    val feedbackError: String? = null,
    val feedbackUncertain: Boolean = false,
    val feedbackLastSubmitted: FeedbackRecord? = null,
    val myFeedback: FeedbackPage? = null,
    val myFeedbackLoading: Boolean = false,
    val myFeedbackError: String? = null,
    val adminFeedback: FeedbackPage? = null,
    val adminFeedbackLoading: Boolean = false,
    val adminFeedbackError: String? = null,
    val adminFeedbackDetail: FeedbackRecord? = null,
    val adminFeedbackDetailLoading: Boolean = false,
    val adminFeedbackDetailError: String? = null,
)

data class ConnectionTestResult(val reply: String, val model: String?)

data class OrganizationEstimate(val chunks: Int, val inputTokens: Int, val outputTokens: Int, val estimatedCost: Double?)

enum class ReorganizationRangeOrigin { SAVED_SCOPE, HISTORICAL_TASK, USER_SELECTION, UNAVAILABLE }

data class ReorganizationPlan(
    val archiveId: String,
    val archiveTitle: String,
    val messageCount: Int = 0,
    val conversationCount: Int = 0,
    val sourceDescription: String = "",
    val preservedEntryCount: Int = 0,
    val estimate: OrganizationEstimate? = null,
    val blockedReason: String? = null,
    val existingTargetArchiveId: String? = null,
    val existingTaskId: String? = null,
    val sourceScopes: List<ArchiveSourceScope> = emptyList(),
    val rangeOrigin: ReorganizationRangeOrigin = ReorganizationRangeOrigin.UNAVAILABLE,
    val needsSourceSelection: Boolean = false,
    internal val sourceFingerprint: String = "",
    internal val settingsFingerprint: String = "",
    internal val requestId: String = java.util.UUID.randomUUID().toString(),
    internal val promptVersion: String = "",
)

data class ReorganizationStart(val targetArchiveId: String, val taskId: String, val reusedExisting: Boolean)

@Serializable
data class ServiceSettings(
    val mode: String = "companion",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val apiKey: String = "",
    val companionUrl: String = "https://digimonlegend.com/continuation-api",
    val trialToken: String = "",
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val contextTokens: Int = 8192,
    val maxOutputTokens: Int = 1024,
)

@Serializable
data class OrganizationTask(
    val id: String,
    val archiveId: String,
    val label: String,
    val status: String,
    val done: Int,
    val total: Int,
    val message: String = "",
    val provider: String = "",
    val uncertain: Boolean = false,
    val serviceMode: String = "byok",
)

@Serializable
internal data class TaskChunk(
    val id: String,
    val messages: List<ChatMessage>,
    val cacheKey: String,
    val completed: Boolean = false,
    val remoteJobId: String = "",
    val remoteStatus: String = "",
    val attempt: Int = 0,
    // Durable before a POST: a lost response must reuse this exact chunk and idempotency key.
    val requestStarted: Boolean = false,
    val inFlight: Boolean = false,
    // Quoted references only; never treated as another piece of primary transcript coverage.
    val contextMessages: List<ChatMessage> = emptyList(),
    val resultNotice: String = "",
)

@Serializable
internal data class TaskRecord(
    val id: String,
    val archiveId: String,
    val label: String,
    val settings: ServiceSettings,
    val secretRef: String,
    val chunks: List<TaskChunk>,
    val status: String = "queued",
    val message: String = "等待整理",
    val inFlight: Boolean = false,
    val uncertain: Boolean = false,
    val cancelRequested: Boolean = false,
    // Missing in legacy JSON must never opt an already admitted task into a new prompt.
    val promptVersion: String = SummaryProtocol.LEGACY_PROMPT_VERSION,
    val reorganizationOfArchiveId: String? = null,
    // Exact prior, already-selected source IDs used by this task's first chunk only.
    // Missing fields on existing paid tasks never authorize additional references.
    val initialContextScope: ArchiveSourceScope? = null,
) {
    fun display() = OrganizationTask(
        id, archiveId, label, status, chunks.count { it.completed }, chunks.size, message,
        if (settings.mode == "byok") "${settings.model} · ${settings.baseUrl}"
        else "免费服务 · ${settings.companionUrl}", uncertain, settings.mode,
    )

    fun currentRemoteId(): String = chunks.firstOrNull { !it.completed }?.remoteJobId.orEmpty()

    /** Older serial tasks only recorded the aggregate inFlight/uncertain flags. */
    fun chunksWithRecordedRequests(): List<TaskChunk> {
        if (!inFlight || chunks.any { it.requestStarted || it.inFlight }) return chunks
        val legacyActiveId = chunks.firstOrNull { !it.completed }?.id ?: return chunks
        return chunks.map { if (it.id == legacyActiveId) it.copy(requestStarted = true) else it }
    }

    fun hasUnknownRequest(): Boolean = chunksWithRecordedRequests().any {
        !it.completed && it.requestStarted && it.remoteJobId.isBlank() &&
            it.remoteStatus !in setOf("failed", "uncertain", "cancelled", "expired")
    }

    fun hasUncertainRequest(): Boolean = hasUnknownRequest() || chunks.any {
        !it.completed && it.remoteStatus in setOf("uncertain", "expired")
    }

    fun withoutInFlight(): TaskRecord = copy(inFlight = false,
        chunks = chunksWithRecordedRequests().map { if (it.inFlight) it.copy(inFlight = false) else it })

    fun updateChunk(chunkId: String, update: (TaskChunk) -> TaskChunk): TaskRecord {
        val updated = chunks.map { if (it.id == chunkId) update(it) else it }
        return copy(chunks = updated, inFlight = updated.any { it.inFlight && !it.completed })
    }

    fun chunksForExplicitResume(): List<TaskChunk> = chunksWithRecordedRequests().flatMap { original ->
        val chunk = if (original.inFlight) original.copy(inFlight = false) else original
        val terminal = chunk.remoteStatus in setOf("failed", "uncertain", "cancelled", "expired")
        // A live or as-yet-unknown remote request must be polled by its original
        // identity. Only an explicit resume may replace known terminal work.
        if (chunk.completed || ((chunk.remoteJobId.isNotBlank() || chunk.requestStarted) && !terminal)) return@flatMap listOf(chunk)
        val retry = if (terminal) chunk.copy(remoteJobId = "", remoteStatus = "", attempt = chunk.attempt + 1,
            requestStarted = false) else chunk
        val pieces = budgetedChunks(chunk.messages, settings, promptVersion, chunk.contextMessages)
        if (pieces.size <= 1) return@flatMap listOf(retry)
        pieces.mapIndexed { index, piece ->
            val id = hash("resume-split-v1\n${chunk.id}\n${retry.attempt}\n$index\n${piece.id}")
            val key = organizationCacheKey(settings, promptVersion, id, piece.messages,
                if (reorganizationOfArchiveId != null) this.id else null, piece.contextMessages)
            TaskChunk(id, piece.messages, key, attempt = retry.attempt, contextMessages = piece.contextMessages)
        }
    }

    fun afterProcessDeath(): TaskRecord {
        if (status !in setOf("queued", "running")) return this
        return withoutInFlight().copy(status = "paused", uncertain = uncertain || hasUncertainRequest(),
            message = if (inFlight && settings.mode == "companion") "应用中断，已保留进度。上次请求可能已占用免费次数，恢复前请检查任务状态。"
            else if (inFlight) "应用中断，已保留进度。请求可能已计费，恢复前请检查任务状态。"
            else "应用中断，已保留进度，可继续整理。")
    }
}

@Serializable
internal data class StoredConflict(val existing: ArchiveEntry, val proposed: ArchiveEntry)

@Serializable
internal data class BackupDocument(
    val format: String = "deepseek-continuation-assistant",
    val version: Int = 6,
    val createdAt: Long = System.currentTimeMillis(),
    val settings: ServiceSettings,
    val sources: List<SourceRow>,
    val conversations: List<ConversationRow>,
    val archives: List<ArchiveRow>,
    val revisions: List<RevisionRow>,
    val conflicts: List<ConflictRow>,
    val tasks: List<TaskRow>,
    val cache: List<ChunkCacheRow>,
)

internal fun ServiceSettings.withoutSecrets() = copy(apiKey = "", trialToken = "")
