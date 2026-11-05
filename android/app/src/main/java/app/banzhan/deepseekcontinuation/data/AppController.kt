@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import app.banzhan.deepseekcontinuation.BuildConfig
import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ArchiveLogic
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityLogic
import app.banzhan.deepseekcontinuation.core.ContinuitySettings
import app.banzhan.deepseekcontinuation.core.DeepSeekImporter
import app.banzhan.deepseekcontinuation.core.EntryConflict
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import app.banzhan.deepseekcontinuation.core.ReorganizationLogic
import java.security.MessageDigest
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AppController internal constructor(application: Application, databaseName: String) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, "continuation.db")

    private val database = Room.databaseBuilder(application, AppDatabase::class.java, databaseName)
        .addMigrations(AppDatabase.MIGRATION_1_2).build()
    private val dao = database.records()
    private val credentials = SecureCredentials(application)
    private val preferences = application.getSharedPreferences("service_settings", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val sessionMutex = Mutex()
    private val quotaMutex = Mutex()
    private val adminMutex = Mutex()
    private val adminStatsMutex = Mutex()
    private val adminUsersMutex = Mutex()
    private val connectionTestMutex = Mutex()
    private val feedbackSubmitMutex = Mutex()
    private val feedbackImageMutex = Mutex()
    private val feedbackGuard = Any()
    private val myFeedbackRequest = AtomicInteger()
    private val adminFeedbackRequest = AtomicInteger()
    private val adminFeedbackDetailRequest = AtomicInteger()
    private var pendingFeedback: Pair<ServiceSettings, FeedbackSubmission>? = null
    private val quotaRefreshGuard = Any()
    private var backgroundQuotaRefresh: Job? = null
    private val ready = CompletableDeferred<Unit>()
    private val jobs = ConcurrentHashMap<String, Job>()
    internal var organizationNetwork: OrganizationNetwork = LiveOrganizationNetwork
    private val activeOperations = AtomicInteger()
    private var clearingData = false
    // Guarded by mutex. A cancelled Room return can follow a successful commit;
    // leave the cache invalid until the committed database is published again.
    private var conversationCacheValid = false
    private val deletingArchives = mutableSetOf<String>()
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    var settings = preferences.getString("settings", null)?.let {
                        appJson.decodeFromString<ServiceSettings>(it)
                    } ?: ServiceSettings()
                    try {
                        val encryptedSettings = credentials.get("current_settings")
                        settings = if (encryptedSettings.isNotBlank()) appJson.decodeFromString<ServiceSettings>(encryptedSettings)
                        else settings.copy(apiKey = credentials.get("api_key"), trialToken = credentials.get("trial_token"))
                    } catch (error: Exception) {
                        mutableState.update { it.copy(notice = error.message) }
                    }
                    val migrated = migrateUnconfiguredService(settings)
                    if (migrated != settings) storeSettings(migrated)
                    settings = migrated
                    var developer = DeveloperPrefs()
                    try {
                        credentials.get("developer_prefs").takeIf { it.isNotBlank() }?.let {
                            developer = appJson.decodeFromString<DeveloperPrefs>(it)
                        }
                        if (developer.sessionToken.isNotBlank() && developerSessionExpired(developer)) {
                            developer = developer.copy(sessionToken = "", expiresAt = "")
                            credentials.put("developer_prefs", appJson.encodeToString(developer))
                        }
                    } catch (error: Exception) {
                        mutableState.update { it.copy(adminError = "管理登录信息无法读取，请重新登录。") }
                    }
                    mutableState.update { it.copy(settings = settings, developerPrefs = developer) }
                    database.withTransaction {
                        dao.tasks().forEach { row ->
                            val task = row.decodeTask()
                            if (task.status in setOf("queued", "running")) {
                                saveTask(task.afterProcessDeath())
                            }
                        }
                    }
                    refresh(reloadConversations = true)
                }
                ready.complete(Unit)
            } catch (error: Exception) {
                mutableState.update { it.copy(notice = "本地数据加载失败。${error.message.orEmpty()}") }
                ready.completeExceptionally(error)
            }
        }
    }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    suspend fun importFile(bytes: ByteArray, fileName: String): List<String> = operation {
        val startedAt = System.nanoTime()
        try {
            require(fileName.isNotBlank() && fileName.length <= 1024) { "导入文件名无效或过长" }
            DiagnosticLog.record("import", "parse", mapOf("input_bytes" to bytes.size.toLong()))
            val result = DeepSeekImporter.parse(bytes, fileName)
            DiagnosticLog.record("import", "save", mapOf("conversation_count" to result.conversations.size.toLong(),
                "message_count" to result.conversations.sumOf { conversation -> conversation.branches.sumOf { it.messages.size.toLong() } }))
            mutex.withLock {
                requireWritable()
                if (!conversationCacheValid) refresh(reloadConversations = true)
                val existing = state.value.conversations.associateBy { it.id }
                conversationCacheValid = false
                val written = database.withTransaction {
                    val conversations = result.conversations.map { conversation ->
                        val previous = existing[conversation.id]
                        val merged = if (previous == null) conversation else mergeImportedConversation(previous, conversation)
                        dao.putConversation(merged)
                        merged
                    }
                    dao.put(SourceRow(hash(bytes), fileName, Base64.encodeToString(bytes, Base64.NO_WRAP), System.currentTimeMillis()))
                    conversations
                }
                val importedIds = written.map { it.id }.distinct()
                // Reuse parsed objects after commit instead of doubling the live account on readback.
                val updated = existing.toMutableMap().apply { written.forEach { put(it.id, it) } }.values.toList()
                mutableState.update { it.copy(conversations = updated, lastImportedConversationIds = importedIds) }
                conversationCacheValid = true
                notice("已导入 ${result.conversations.size} 个会话" + result.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n").orEmpty())
                DiagnosticLog.record("import", "complete", mapOf("conversation_count" to importedIds.size.toLong(),
                    "elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000))
                importedIds
            }
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                DiagnosticLog.record("import", "failed",
                    mapOf("input_bytes" to bytes.size.toLong(), "elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000), error)
                if (error is Exception) viewModelScope.launch { prepareDiagnosticsIdentity() }
            }
            throw error
        }
    }

    suspend fun loadDemo() {
        val sample = """[{"id":"demo-continuation-v1","title":"示例 · 周末摄影计划","messages":[{"id":"demo-1","role":"user","content":"我平时喜欢拍城市夜景，周末想带朋友去河边。预算大约300元。"},{"id":"demo-2","role":"assistant","content":"可以在日落前一小时出发，准备三脚架并检查天气。"},{"id":"demo-3","role":"user","content":"已经确定周六下午5点见面，地点是地铁东站出口。雨天就改去室内展览。"}]}]"""
        importFile(sample.toByteArray(Charsets.UTF_8), "示例对话.json")
    }

    suspend fun saveSettings(settings: ServiceSettings) = operation {
        mutex.withLock {
            val cleaned = settingsForSave(state.value.settings, settings)
            storeSettings(cleaned)
            val changed = cleaned.apiKey != settings.apiKey.trim() || cleaned.trialToken != settings.trialToken.trim()
            mutableState.update { it.copy(settings = cleaned, quota = null, quotaError = null, quotaLoading = false,
                myFeedback = null, myFeedbackError = null,
                notice = if (changed) "地址已修改，旧凭据已清空。已有任务保留原服务。" else "服务设置已保存，已有任务继续使用创建时的服务") }
        }
    }

    suspend fun testApiConnection(draft: ServiceSettings): ConnectionTestResult = withContext(Dispatchers.IO) {
        ready.await()
        check(connectionTestMutex.tryLock()) { "连接测试正在进行，请稍等。" }
        mutableState.update { it.copy(connectionTestLoading = true, connectionTestResult = null, connectionTestError = null) }
        try {
            val settings = settingsForSave(state.value.settings, draft.copy(mode = "byok"))
            require(settings.apiKey.isNotBlank()) { "请填写当前服务地址对应的 API Key。" }
            val result = SummaryNetwork.testConnection(settings)
            mutableState.update { it.copy(connectionTestResult = result) }
            result
        } catch (error: Exception) {
            if (error !is CancellationException) mutableState.update { it.copy(connectionTestError = error.message ?: "连接测试失败，请检查地址、模型与密钥。") }
            throw error
        } finally {
            mutableState.update { it.copy(connectionTestLoading = false) }
            connectionTestMutex.unlock()
        }
    }

    suspend fun redeemInvite(code: String) = operation {
        require(code.isNotBlank()) { "请输入邀请码" }
        val snapshot = state.value.settings
        val token = SummaryNetwork.redeem(snapshot, code.trim())
        mutex.withLock {
            val settings = state.value.settings
            require(settings.companionUrl == snapshot.companionUrl) { "服务地址已改变，请在原地址重新兑换" }
            val updated = settings.copy(trialToken = token, mode = "companion")
            storeSettings(updated)
            mutableState.update { it.copy(settings = updated, notice = "邀请码已兑换") }
        }
        try { refreshQuotaState() } catch (error: Exception) {
            if (error is CancellationException) throw error
            notice("凭据已保存，额度查询暂时不可用")
        }
    }

    /** Reuses this installation's encrypted identity, including after ordinary data restoration. */
    suspend fun refreshQuota() = withContext(Dispatchers.IO) {
        ready.await()
        refreshQuotaState()
        Unit
    }

    /** Diagnostics reuse the same anonymous account and never start a model request. */
    suspend fun prepareDiagnosticsIdentity() = withContext(Dispatchers.IO) {
        try {
            ready.await()
            val current = state.value.settings
            if (!DiagnosticPrivacy.usesCompanion(current.mode, current.companionUrl)) {
                return@withContext
            }
            if (current.trialToken.isNotBlank()) {
                DiagnosticLog.bindAnonymousIdentity(current.mode, current.companionUrl, current.trialToken)
                return@withContext
            }
            if (!DiagnosticLog.shouldAcquireIdentity()) return@withContext
            val identity = ensureCompanionIdentity(requireFreeMode = false, diagnosticsOnly = true)
            DiagnosticLog.bindAnonymousIdentity(identity.mode, identity.companionUrl, identity.trialToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Retain local reports; a later foreground retries without disrupting the user.
        }
    }

    /** Read-only preflight. A passed check does not reserve funds or call a model. */
    suspend fun checkOrganizationAvailability(): String? = operation {
        try {
            if (state.value.settings.mode == "companion") {
                val quota = refreshQuotaState()
                val blocker = quota.organizationBlocker()
                if (blocker == null) settingsWithServerBudget(state.value.settings, quota)
                blocker
            } else {
                validateOrganizationConfiguration(state.value.settings)
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error.message?.take(1500)?.takeIf { it.isNotBlank() } ?: "暂时无法确认智能整理是否可用，请检查网络和服务设置。"
        }
    }

    fun estimateForCurrentService(messages: List<ChatMessage>): OrganizationEstimate {
        val current = state.value
        val settings = if (current.settings.mode == "companion") {
            val quota = current.quota ?: error(current.quotaError ?: "正在获取免费服务的分段预算，请稍后。")
            settingsWithServerBudget(current.settings, quota)
        } else current.settings
        return estimateOrganization(settings, messages)
    }

    private suspend fun ensureCompanionIdentity(requireFreeMode: Boolean = true, diagnosticsOnly: Boolean = false): ServiceSettings = sessionMutex.withLock {
        val snapshot = validateSettings(state.value.settings)
        require(!diagnosticsOnly || DiagnosticPrivacy.usesCompanion(snapshot.mode, snapshot.companionUrl)) { "诊断服务设置已改变" }
        require(!requireFreeMode || snapshot.mode == "companion") { "当前使用自己的 AI 服务。" }
        val identityKey = companionIdentityKey(snapshot.companionUrl)
        val existing = snapshot.trialToken.ifBlank { credentials.get(identityKey) }
        val token = if (existing.isNotBlank()) existing else organizationNetwork.createSession(snapshot).token
        require(token.isNotBlank()) { "免费服务未返回有效身份，请稍后再试。" }
        mutex.withLock {
            val current = state.value.settings
            val known = credentials.get(identityKey)
            // Keep a received identity, while preserving an explicitly changed credential from the same service.
            if (known.isBlank() || known == snapshot.trialToken || known == token) credentials.put(identityKey, token)
            require(current.mode == snapshot.mode && current.companionUrl == snapshot.companionUrl &&
                (current.trialToken.isBlank() || current.trialToken == token)) { "服务设置已改变，请重新检查后开始。" }
            val updated = current.copy(trialToken = token)
            if (updated != current) storeSettings(updated)
            mutableState.update { it.copy(settings = updated) }
            updated
        }
    }

    private suspend fun refreshQuotaState(): CompanionQuota = quotaMutex.withLock {
        mutableState.update { it.copy(quota = null, quotaLoading = true, quotaError = null) }
        var snapshot = state.value.settings
        try {
            snapshot = ensureCompanionIdentity()
            val quota = organizationNetwork.quota(snapshot)
            require(sameCompanionIdentity(state.value.settings, snapshot)) { "服务设置已改变，请重新检查后开始。" }
            mutableState.update { it.copy(quota = quota, quotaLoading = false, quotaError = null) }
            quota
        } catch (error: Exception) {
            if (sameCompanionIdentity(state.value.settings, snapshot) || state.value.settings == snapshot) {
                mutableState.update { it.copy(quota = null, quotaLoading = false,
                    quotaError = if (error is CancellationException) null else error.message ?: "免费额度查询失败，请检查网络后再试。") }
            } else mutableState.update { it.copy(quotaLoading = false) }
            throw error
        }
    }

    suspend fun saveDeveloperServiceUrl(serviceUrl: String) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val previous = state.value.developerPrefs
            val updated = developerPrefsForService(previous, serviceUrl)
            if (updated != previous) {
                credentials.put("developer_prefs", appJson.encodeToString(updated))
                clearDeveloperState(updated)
            }
        }
    }

    suspend fun loginDeveloper(serviceUrl: String, password: String) = withContext(Dispatchers.IO) {
        ready.await()
        saveDeveloperServiceUrl(serviceUrl)
        val starting = state.value.developerPrefs
        mutableState.update { it.copy(adminLoading = true, adminError = null) }
        try {
            val loggedIn = AdminNetwork.loginDeveloper(starting.serviceUrl, password)
            require(!developerSessionExpired(loggedIn)) { "服务返回的登录会话已过期，请稍后重试。" }
            mutex.withLock {
                if (state.value.developerPrefs != starting) throw CancellationException("管理登录状态已改变")
                credentials.put("developer_prefs", appJson.encodeToString(loggedIn))
                clearDeveloperState(loggedIn)
            }
        } catch (error: Exception) {
            if (error !is CancellationException && state.value.developerPrefs == starting) {
                mutableState.update { it.copy(adminError = error.message ?: "开发者登录失败，请重试。") }
            }
            throw error
        } finally {
            mutableState.update { it.copy(adminLoading = false) }
        }
    }

    suspend fun logoutDeveloper() = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val loggedOut = state.value.developerPrefs.copy(sessionToken = "", expiresAt = "")
            credentials.put("developer_prefs", appJson.encodeToString(loggedOut))
            clearDeveloperState(loggedOut)
        }
    }

    suspend fun refreshAdminConfig() = withContext(Dispatchers.IO) {
        ready.await()
        adminMutex.withLock {
            mutableState.update { it.copy(adminConfig = null, adminLoading = true, adminError = null) }
            try {
                val config = withDeveloperSession { AdminNetwork.getAdminConfig(it) }
                mutableState.update { it.copy(adminConfig = config) }
            } catch (error: Exception) {
                if (error !is CancellationException && state.value.developerPrefs.sessionToken.isNotBlank()) {
                    mutableState.update { it.copy(adminError = error.message ?: "管理配置读取失败。") }
                }
                throw error
            } finally { mutableState.update { it.copy(adminLoading = false) } }
        }
    }

    suspend fun updateAdminConfig(config: AdminConfig, upstreamApiKey: String? = null) = withContext(Dispatchers.IO) {
        ready.await()
        adminMutex.withLock {
            mutableState.update { it.copy(adminConfig = null, adminLoading = true, adminError = null) }
            try {
                val confirmed = withDeveloperSession { prefs ->
                    AdminNetwork.updateAdminConfig(prefs, config, upstreamApiKey)
                    try {
                        AdminNetwork.getAdminConfig(prefs)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        throw SummaryFailure("设置已提交，但读取最新配置失败，请刷新确认。", ambiguous = false,
                            statusCode = (error as? SummaryFailure)?.statusCode)
                    }
                }
                mutableState.update { it.copy(adminConfig = confirmed, quota = null, quotaError = null) }
            } catch (error: Exception) {
                if (error !is CancellationException && state.value.developerPrefs.sessionToken.isNotBlank()) {
                    mutableState.update { it.copy(adminError = error.message ?: "管理配置保存失败。") }
                }
                throw error
            } finally { mutableState.update { it.copy(adminLoading = false) } }
        }
    }

    suspend fun refreshAdminStats(days: Int = 7) = withContext(Dispatchers.IO) {
        ready.await()
        adminStatsMutex.withLock {
            mutableState.update { it.copy(adminStats = null, adminStatsLoading = true, adminStatsError = null) }
            try {
                val stats = withDeveloperSession { AdminNetwork.getStats(it, days) }
                mutableState.update { it.copy(adminStats = stats) }
            } catch (error: Exception) {
                if (error !is CancellationException && state.value.developerPrefs.sessionToken.isNotBlank()) {
                    mutableState.update { it.copy(adminStatsError = error.message ?: "统计读取失败。") }
                }
                throw error
            } finally { mutableState.update { it.copy(adminStatsLoading = false) } }
        }
    }

    suspend fun refreshAdminUsers(limit: Int = 50, offset: Int = 0) = withContext(Dispatchers.IO) {
        ready.await()
        adminUsersMutex.withLock {
            mutableState.update { it.copy(adminUsers = null, adminUsersLoading = true, adminUsersError = null) }
            try {
                val users = withDeveloperSession { AdminNetwork.getUsers(it, limit, offset) }
                mutableState.update { it.copy(adminUsers = users) }
            } catch (error: Exception) {
                if (error !is CancellationException && state.value.developerPrefs.sessionToken.isNotBlank()) {
                    mutableState.update { it.copy(adminUsersError = error.message ?: "匿名用户统计读取失败。") }
                }
                throw error
            } finally { mutableState.update { it.copy(adminUsersLoading = false) } }
        }
    }

    private suspend fun <T> withDeveloperSession(block: suspend (DeveloperPrefs) -> T): T {
        val snapshot = state.value.developerPrefs
        if (snapshot.sessionToken.isBlank() || developerSessionExpired(snapshot)) {
            invalidateDeveloperSession(snapshot, "管理登录已过期或尚未登录，请重新登录。")
            error("请先登录开发者入口。")
        }
        try {
            val result = block(snapshot)
            if (state.value.developerPrefs != snapshot) throw CancellationException("管理登录状态已改变")
            return result
        } catch (error: Exception) {
            if (state.value.developerPrefs != snapshot) throw CancellationException("管理登录状态已改变")
            if (error is SummaryFailure && error.statusCode == 401) {
                invalidateDeveloperSession(snapshot, "管理登录已过期，请重新登录。")
            }
            throw error
        }
    }

    private suspend fun invalidateDeveloperSession(snapshot: DeveloperPrefs, message: String) = mutex.withLock {
        if (state.value.developerPrefs == snapshot) {
            val loggedOut = snapshot.copy(sessionToken = "", expiresAt = "")
            credentials.put("developer_prefs", appJson.encodeToString(loggedOut))
            clearDeveloperState(loggedOut, message)
        }
    }

    private fun clearDeveloperState(prefs: DeveloperPrefs, error: String? = null) {
        mutableState.update { it.copy(developerPrefs = prefs, adminConfig = null, adminLoading = false, adminError = error,
            adminStats = null, adminStatsLoading = false, adminStatsError = null,
            adminUsers = null, adminUsersLoading = false, adminUsersError = null,
            adminFeedback = null, adminFeedbackLoading = false, adminFeedbackError = null,
            adminFeedbackDetail = null, adminFeedbackDetailLoading = false, adminFeedbackDetailError = null) }
    }

    fun updateFeedbackDraft(content: String, contactType: String, contact: String) = synchronized(feedbackGuard) {
        val current = state.value
        if (current.feedbackSubmitting || current.feedbackUncertain) {
            mutableState.update { it.copy(feedbackError = "请先重试确认上次提交，或明确重新填写一条意见。") }
        } else {
            mutableState.update { it.copy(feedbackDraft = it.feedbackDraft.copy(content = content, contactType = contactType, contact = contact), feedbackError = null) }
        }
    }

    fun removeFeedbackImage(id: String) = synchronized(feedbackGuard) {
        if (!state.value.feedbackSubmitting && !state.value.feedbackUncertain) {
            mutableState.update { it.copy(feedbackDraft = it.feedbackDraft.copy(images = it.feedbackDraft.images.filterNot { image -> image.id == id }), feedbackError = null) }
        }
    }

    fun resetFeedbackDraft() = synchronized(feedbackGuard) {
        if (!state.value.feedbackSubmitting) {
            pendingFeedback = null
            mutableState.update { it.copy(feedbackDraft = FeedbackDraft(), feedbackUncertain = false, feedbackError = null, feedbackLastSubmitted = null) }
        }
    }

    suspend fun addFeedbackImage(bytes: ByteArray) = withContext(Dispatchers.IO) {
        ready.await()
        feedbackImageMutex.withLock {
            val draftId = synchronized(feedbackGuard) {
                val current = state.value
                require(!current.feedbackSubmitting && !current.feedbackUncertain) { "当前提交尚未确认，请先处理后再修改图片。" }
                require(current.feedbackDraft.images.size < 3) { "最多添加3张图片。" }
                mutableState.update { it.copy(feedbackImageLoading = true, feedbackError = null) }
                current.feedbackDraft.idempotencyKey
            }
            try {
                val image = FeedbackImageProcessor.prepare(bytes)
                synchronized(feedbackGuard) {
                    val current = state.value
                    if (current.feedbackDraft.idempotencyKey == draftId && !current.feedbackSubmitting && !current.feedbackUncertain) {
                        mutableState.update { it.copy(feedbackDraft = it.feedbackDraft.copy(images = it.feedbackDraft.images + image)) }
                    }
                }
            } catch (error: Exception) {
                if (error !is CancellationException) mutableState.update { it.copy(feedbackError = error.message ?: "图片处理失败，请换一张图片。") }
                throw error
            } finally { mutableState.update { it.copy(feedbackImageLoading = false) } }
        }
    }

    suspend fun submitFeedback(): FeedbackRecord = withContext(Dispatchers.IO) {
        ready.await()
        check(feedbackSubmitMutex.tryLock()) { "意见正在提交，请稍等。" }
        var draftId: String? = null
        try {
            val draft = synchronized(feedbackGuard) {
                val current = state.value
                require(!current.feedbackImageLoading) { "请等待图片处理完成后再提交。" }
                validateFeedbackDraft(current.feedbackDraft)
                draftId = current.feedbackDraft.idempotencyKey
                mutableState.update { it.copy(feedbackSubmitting = true, feedbackError = null) }
                current.feedbackDraft
            }
            val previous = synchronized(feedbackGuard) { pendingFeedback?.takeIf { it.second.idempotencyKey == draft.idempotencyKey } }
            val attempt = previous ?: run {
                val identity = ensureCompanionIdentity(requireFreeMode = false).copy(apiKey = "")
                val submission = FeedbackSubmission(draft.idempotencyKey, draft.content.trim(), draft.contactType,
                    draft.contact.trim(), BuildConfig.VERSION_NAME, draft.images.map {
                        FeedbackUploadImage("image/jpeg", Base64.encodeToString(it.jpegBytes, Base64.NO_WRAP))
                    })
                (identity to submission).also { synchronized(feedbackGuard) { pendingFeedback = it } }
            }
            val result = FeedbackNetwork.submit(attempt.first, attempt.second)
            synchronized(feedbackGuard) {
                if (state.value.feedbackDraft.idempotencyKey == draftId) {
                    pendingFeedback = null
                    mutableState.update { it.copy(feedbackDraft = FeedbackDraft(), feedbackUncertain = false,
                        feedbackError = null, feedbackLastSubmitted = result, myFeedback = null, myFeedbackError = null) }
                }
            }
            result
        } catch (error: Exception) {
            synchronized(feedbackGuard) {
                val uncertain = state.value.feedbackUncertain || (error is SummaryFailure && (error.ambiguous || error.statusCode == 409))
                if (draftId == null || state.value.feedbackDraft.idempotencyKey == draftId) {
                    if (!uncertain && error !is CancellationException) pendingFeedback = null
                    mutableState.update { it.copy(feedbackUncertain = uncertain || (error is CancellationException && pendingFeedback != null),
                        feedbackError = if (uncertain || (error is CancellationException && pendingFeedback != null)) "提交结果尚未确认。点击重试会使用相同编号和内容，避免重复提交。"
                        else if (error is CancellationException) "提交已取消，草稿已保留。"
                        else error.message ?: "意见提交失败，请稍后再试。") }
                }
            }
            throw error
        } finally {
            mutableState.update { it.copy(feedbackSubmitting = false) }
            feedbackSubmitMutex.unlock()
        }
    }

    suspend fun refreshMyFeedback(limit: Int = 50, offset: Int = 0) {
        val request = myFeedbackRequest.incrementAndGet()
        withContext(Dispatchers.IO) {
            ready.await()
            mutableState.update { it.copy(myFeedback = null, myFeedbackLoading = true, myFeedbackError = null) }
            try {
                val snapshot = ensureCompanionIdentity(requireFreeMode = false)
                val page = FeedbackNetwork.listMine(snapshot, limit, offset)
                if (request == myFeedbackRequest.get() && state.value.settings.companionUrl == snapshot.companionUrl && state.value.settings.trialToken == snapshot.trialToken) {
                    mutableState.update { it.copy(myFeedback = page) }
                }
            } catch (error: Exception) {
                if (error !is CancellationException && request == myFeedbackRequest.get()) mutableState.update { it.copy(myFeedbackError = error.message ?: "意见记录读取失败。") }
                throw error
            } finally { if (request == myFeedbackRequest.get()) mutableState.update { it.copy(myFeedbackLoading = false) } }
        }
    }

    suspend fun refreshAdminFeedback(status: String? = null, limit: Int = 50, offset: Int = 0) {
        val request = adminFeedbackRequest.incrementAndGet()
        withContext(Dispatchers.IO) {
            ready.await()
            mutableState.update { it.copy(adminFeedback = null, adminFeedbackLoading = true, adminFeedbackError = null) }
            try {
                val page = withDeveloperSession { FeedbackNetwork.listAdmin(it, status, limit, offset) }
                if (request == adminFeedbackRequest.get()) mutableState.update { it.copy(adminFeedback = page) }
            } catch (error: Exception) {
                if (error !is CancellationException && request == adminFeedbackRequest.get()) mutableState.update { it.copy(adminFeedbackError = error.message ?: "意见列表读取失败。") }
                throw error
            } finally { if (request == adminFeedbackRequest.get()) mutableState.update { it.copy(adminFeedbackLoading = false) } }
        }
    }

    suspend fun loadAdminFeedback(id: String) {
        val request = adminFeedbackDetailRequest.incrementAndGet()
        withContext(Dispatchers.IO) {
            ready.await()
            mutableState.update { it.copy(adminFeedbackDetail = null, adminFeedbackDetailLoading = true, adminFeedbackDetailError = null) }
            try {
                val record = withDeveloperSession { FeedbackNetwork.getAdmin(it, id) }
                if (request == adminFeedbackDetailRequest.get()) mutableState.update { it.copy(adminFeedbackDetail = record) }
            } catch (error: Exception) {
                if (error !is CancellationException && request == adminFeedbackDetailRequest.get()) mutableState.update { it.copy(adminFeedbackDetailError = error.message ?: "意见详情读取失败。") }
                throw error
            } finally { if (request == adminFeedbackDetailRequest.get()) mutableState.update { it.copy(adminFeedbackDetailLoading = false) } }
        }
    }

    suspend fun processAdminFeedback(id: String, status: String, adminNote: String, expectedRevision: Int) {
        val request = adminFeedbackDetailRequest.incrementAndGet()
        withContext(Dispatchers.IO) {
            ready.await()
            mutableState.update { it.copy(adminFeedbackDetailLoading = true, adminFeedbackDetailError = null) }
            try {
                val record = withDeveloperSession { FeedbackNetwork.updateAdmin(it, id, status, adminNote, expectedRevision) }
                if (request == adminFeedbackDetailRequest.get()) mutableState.update { it.copy(adminFeedbackDetail = record, adminFeedback = null) }
            } catch (error: Exception) {
                if (error !is CancellationException && request == adminFeedbackDetailRequest.get()) mutableState.update { it.copy(adminFeedbackDetailError = error.message ?: "审核保存失败，请刷新后重试。") }
                throw error
            } finally { if (request == adminFeedbackDetailRequest.get()) mutableState.update { it.copy(adminFeedbackDetailLoading = false) } }
        }
    }

    suspend fun loadFeedbackImage(feedbackId: String, imageId: String, admin: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        ready.await()
        if (admin) withDeveloperSession { FeedbackNetwork.loadAdminImage(it, feedbackId, imageId) }
        else FeedbackNetwork.loadImage(ensureCompanionIdentity(requireFreeMode = false), feedbackId, imageId)
    }

    private fun scheduleQuotaRefresh(task: TaskRecord) {
        if (task.settings.mode != "companion") return
        val snapshot = runCatching { withTaskSecret(task) }.getOrNull() ?: return
        if (!sameCompanionIdentity(state.value.settings, snapshot)) return
        mutableState.update { it.copy(quota = null, quotaLoading = true, quotaError = null) }
        synchronized(quotaRefreshGuard) {
            backgroundQuotaRefresh?.cancel()
            backgroundQuotaRefresh = viewModelScope.launch(Dispatchers.IO) {
                delay(150)
                if (!sameCompanionIdentity(state.value.settings, snapshot)) return@launch
                try { refreshQuotaState() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // refreshQuotaState exposes the failure through quotaError without changing task success.
                }
            }
        }
    }

    suspend fun createArchive(
        title: String, conversation: ImportedConversation, branchId: String,
        selectedIds: Set<String>, background: String, includeRawEntries: Boolean = true,
    ): String = operation {
        require(title.isNotBlank()) { "请填写档案标题" }
        require(background.length <= 100_000) { "背景内容过长" }
        mutex.withLock {
            requireWritable()
            val source = storedConversation(conversation.id)
            val messages = selectMessages(source, branchId, selectedIds)
            require(messages.isNotEmpty() || background.isNotBlank()) { "请选择对话消息或填写背景" }
            val entries = if (includeRawEntries) ArchiveLogic.localEntries(messages) else emptyList()
            val archive = Archive(UUID.randomUUID().toString(), title.trim(), listOf(source.id), entries,
                background = background, generationMode = if (includeRawEntries) "original" else "summary",
                continuity = ContinuitySettings(enabled = true),
                sourceScopes = listOf(ContinuityLogic.captureScope(source, branchId, selectedIds)))
            saveArchive(archive, keepRevision = false)
            refresh()
            archive.id
        }
    }

    suspend fun addConversation(archiveId: String, conversation: ImportedConversation, branchId: String, selectedIds: Set<String>) = operation {
        mutex.withLock {
            requireWritable()
            database.withTransaction {
                val archive = loadArchive(archiveId)
                val source = storedConversation(conversation.id)
                val messages = selectMessages(source, branchId, selectedIds)
                require(messages.isNotEmpty()) { "请选择要加入的消息" }
                val merged = ArchiveLogic.merge(archive.entries, ArchiveLogic.localEntries(messages))
                val scoped = withSelectedSourceScope(archive, source, branchId, selectedIds)
                saveArchive(scoped.copy(conversationIds = (archive.conversationIds + source.id).distinct(), entries = merged.entries,
                    generationMode = if (archive.generationMode == "summary") "legacy" else archive.generationMode))
                saveConflicts(archiveId, merged.conflicts)
            }
            refresh()
            notice("已加入所选对话，保留人工修改和置顶内容")
        }
    }

    suspend fun saveEntry(archiveId: String, entry: ArchiveEntry) = operation {
        require(entry.text.length <= 100_000) { "条目内容过长" }
        mutex.withLock {
            requireWritable()
            database.withTransaction {
                val archive = loadArchive(archiveId)
                require(entry.sourceIds.all { it in sourceIds(archive) }) { "条目引用了档案之外的消息" }
                val id = entry.id.ifBlank { UUID.randomUUID().toString() }
                val previous = archive.entries.indexOfFirst { it.id == id }
                val original = archive.entries.getOrNull(previous)
                val contentChanged = original == null || original.text != entry.text ||
                    original.category != entry.category || original.kind != entry.kind || original.sourceIds != entry.sourceIds
                val updated = entry.copy(id = id, edited = original?.edited == true || contentChanged,
                    continuityCue = if (contentChanged) null else original?.continuityCue)
                val entries = archive.entries.toMutableList()
                if (previous < 0) entries.add(updated) else entries[previous] = updated
                saveArchive(archive.copy(entries = entries))
            }
            refresh()
        }
    }

    suspend fun saveBackground(archiveId: String, background: String) = operation {
        require(background.length <= 100_000) { "背景内容过长" }
        mutex.withLock {
            requireWritable()
            database.withTransaction { saveArchive(loadArchive(archiveId).copy(background = background)) }
            refresh()
        }
    }

    suspend fun saveContinuitySettings(archiveId: String, settings: ContinuitySettings) = operation {
        val validated = ContinuityLogic.validateSettings(settings)
        mutex.withLock {
            requireWritable()
            database.withTransaction {
                val archive = loadArchive(archiveId)
                if (!conversationCacheValid) refresh(reloadConversations = true)
                val candidates = ContinuityLogic.buildPlan(archive, state.value.conversations)
                    .recommendedFragments.map { it.id }.toSet()
                require(validated.selectedFragmentIds?.all { it in candidates } != false) { "选中的互动片段已变化，请重新选择" }
                // UI settings cannot replace sourceScopes or rebuild a running request.
                saveArchive(archive.copy(continuity = validated))
            }
            refresh()
        }
    }

    suspend fun deleteArchive(archiveId: String) = operation {
        val taskIds = mutex.withLock {
            requireWritable()
            deletingArchives.add(archiveId)
            dao.tasks().filter { it.archiveId == archiveId }.map { it.id }
        }
        try {
            taskIds.forEach { cancelTask(it) }
            mutex.withLock {
                database.withTransaction {
                    dao.deleteArchive(archiveId)
                    dao.deleteRevisions(archiveId)
                    dao.deleteConflicts(archiveId)
                    dao.deleteTasks(archiveId)
                }
                taskIds.forEach { credentials.remove("task:$it") }
                refresh()
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { deletingArchives.remove(archiveId) } }
        }
    }

    suspend fun clearLocalData() = operation {
        val taskIds = mutex.withLock {
            requireWritable()
            clearingData = true
            dao.tasks().map { it.id }
        }
        try {
            taskIds.forEach { cancelTask(it) }
            jobs.values.toList().forEach { it.cancelAndJoin() }
            mutex.withLock {
                conversationCacheValid = false
                database.withTransaction {
                    dao.clearSources(); dao.clearConversations(); dao.clearArchives(); dao.clearRevisions()
                    dao.clearConflicts(); dao.clearTasks(); dao.clearCache()
                }
                credentials.removeTaskSecrets()
                android.util.AtomicFile(java.io.File(getApplication<Application>().filesDir, "manual-paste-draft.json")).delete()
                synchronized(feedbackGuard) {
                    pendingFeedback = null
                    myFeedbackRequest.incrementAndGet()
                    adminFeedbackRequest.incrementAndGet()
                    adminFeedbackDetailRequest.incrementAndGet()
                    mutableState.update { it.copy(feedbackDraft = FeedbackDraft(), feedbackUncertain = false, feedbackError = null,
                        feedbackLastSubmitted = null, myFeedback = null, myFeedbackLoading = false, myFeedbackError = null,
                        adminFeedback = null, adminFeedbackLoading = false, adminFeedbackError = null,
                        adminFeedbackDetail = null, adminFeedbackDetailLoading = false, adminFeedbackDetailError = null) }
                }
                refresh(reloadConversations = true)
                mutableState.update { it.copy(lastImportedConversationIds = emptyList()) }
                notice("本地会话、档案、原始文件、版本、整理进度与粘贴草稿已清除，服务设置已保留")
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { clearingData = false } }
        }
    }

    /** Preparing may check service quota, but never submits a model request. */
    suspend fun prepareReorganization(archiveId: String,
        confirmedScopes: List<ArchiveSourceScope>? = null): ReorganizationPlan = operation {
        val (archive, resolved) = mutex.withLock {
            requireWritable()
            require(archiveId !in deletingArchives) { "档案正在删除" }
            val original = loadArchive(archiveId)
            val tasks = dao.tasks().map { it.decodeTask() }
            pendingReorganization(archiveId, tasks)?.let { return@operation existingReorganizationPlan(original, it) }
            if (!conversationCacheValid) refresh(reloadConversations = true)
            val sources = try {
                resolveReorganizationSources(original, state.value.conversations, tasks, confirmedScopes)
            } catch (error: IllegalArgumentException) {
                return@operation baseReorganizationPlan(original).copy(blockedReason = error.message,
                    needsSourceSelection = true)
            } catch (error: IllegalStateException) {
                // A new import may no longer contain the previously selected branch.
                return@operation baseReorganizationPlan(original).copy(blockedReason = error.message,
                    needsSourceSelection = true)
            }
            original to sources
        }
        val base = baseReorganizationPlan(archive).copy(sourceScopes = resolved.sourceScopes,
            messageCount = resolved.messageGroups.sumOf { it.size },
            conversationCount = resolved.sourceScopes.map { it.conversationId }.distinct().size,
            sourceDescription = resolved.description, rangeOrigin = resolved.rangeOrigin,
            sourceFingerprint = reorganizationSourceFingerprint(archive, resolved))
        try {
            val snapshot = if (state.value.settings.mode == "companion") ensureCompanionIdentity() else state.value.settings
            val quota = if (snapshot.mode == "companion") refreshQuotaState() else null
            require(state.value.settings == snapshot) { "服务设置已改变，请重新检查后开始。" }
            quota?.organizationBlocker()?.let { error(it) }
            val settings = validateOrganizationConfiguration(if (quota != null) settingsWithServerBudget(snapshot, quota) else snapshot)
            val estimates = resolved.messageGroups.map { estimateOrganization(settings, it) }
            base.copy(estimate = OrganizationEstimate(estimates.sumOf { it.chunks }, estimates.sumOf { it.inputTokens },
                estimates.sumOf { it.outputTokens }, if (estimates.all { it.estimatedCost != null })
                    estimates.sumOf { it.estimatedCost ?: 0.0 } else null),
                settingsFingerprint = hash(appJson.encodeToString(settings)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            base.copy(blockedReason = error.message ?: "无法检查整理服务，请先检查服务设置。")
        }
    }

    suspend fun reorganizeArchive(plan: ReorganizationPlan): ReorganizationStart = operation {
        // Resolve an existing progress before any network preflight, including a stopping job.
        mutex.withLock {
            requireWritable()
            confirmedReorganization(plan)?.let { return@operation it }
            pendingReorganization(plan.archiveId, dao.tasks().map { it.decodeTask() })?.let {
                return@operation ReorganizationStart(it.archiveId, it.id, true)
            }
        }
        require(plan.blockedReason == null && !plan.needsSourceSelection && plan.sourceFingerprint.isNotBlank() &&
            plan.settingsFingerprint.isNotBlank()) { plan.blockedReason ?: "请重新检查原文范围和服务设置。" }
        val snapshot = if (state.value.settings.mode == "companion") ensureCompanionIdentity() else state.value.settings
        val quota = if (snapshot.mode == "companion") refreshQuotaState() else null
        val started = mutex.withLock {
            requireWritable()
            confirmedReorganization(plan)?.let { return@operation it }
            require(plan.archiveId !in deletingArchives) { "档案正在删除" }
            val tasks = dao.tasks().map { it.decodeTask() }
            pendingReorganization(plan.archiveId, tasks)?.let {
                return@operation ReorganizationStart(it.archiveId, it.id, true)
            }
            val original = loadArchive(plan.archiveId)
            if (!conversationCacheValid) refresh(reloadConversations = true)
            val resolved = resolveReorganizationSources(original, state.value.conversations, tasks, plan.sourceScopes)
            require(reorganizationSourceFingerprint(original, resolved) == plan.sourceFingerprint) {
                "档案或原文已变化，请重新检查后确认整理。"
            }
            require(state.value.settings == snapshot) { "服务设置已改变，请重新检查后开始。" }
            quota?.organizationBlocker()?.let { error(it) }
            val settings = validateOrganizationConfiguration(if (quota != null) settingsWithServerBudget(snapshot, quota) else snapshot)
            require(hash(appJson.encodeToString(settings)) == plan.settingsFingerprint) {
                "服务或分段预算已变化，请重新查看费用后确认。"
            }
            val targetId = UUID.randomUUID().toString()
            val taskId = plan.requestId
            val records = resolved.messageGroups.flatMapIndexed { index, messages ->
                val scope = resolved.sourceScopes[index]
                budgetedChunks(messages, settings).map { chunk ->
                    val chunkId = hash("reorganization-scope-v1\n${scope.conversationId}\n${scope.branchId}\n${chunk.id}")
                    TaskChunk(chunkId, chunk.messages,
                        organizationCacheKey(settings, SummaryProtocol.PROMPT_VERSION, chunkId, chunk.messages, taskId))
                }
            }
            require(records.isNotEmpty()) { "没有可重新整理的文字。" }
            val target = ReorganizationLogic.rebuildArchive(original, targetId)
                .copy(sourceScopes = resolved.sourceScopes)
            val task = TaskRecord(taskId, targetId, target.title, settings.withoutSecrets(), "task:$taskId", records,
                message = estimateCost(settings, records), promptVersion = SummaryProtocol.PROMPT_VERSION,
                reorganizationOfArchiveId = original.id)
            credentials.put(task.secretRef, if (settings.mode == "byok") settings.apiKey else settings.trialToken)
            DiagnosticLog.record("task", "prepare", mapOf("chunk_count" to records.size.toLong()))
            database.withTransaction {
                saveArchive(target, keepRevision = false)
                saveTask(task)
            }
            refresh()
            ReorganizationStart(targetId, taskId, false)
        }
        launchTask(started.taskId)
        started
    }

    private fun baseReorganizationPlan(archive: Archive) = ReorganizationPlan(archive.id, archive.title,
        preservedEntryCount = archive.entries.count { it.edited || it.deleted || it.pinned || it.sourceIds.isEmpty() })

    private fun existingReorganizationPlan(archive: Archive, task: TaskRecord) = baseReorganizationPlan(archive).copy(
        existingTargetArchiveId = task.archiveId, existingTaskId = task.id)

    private suspend fun confirmedReorganization(plan: ReorganizationPlan): ReorganizationStart? {
        val task = dao.task(plan.existingTaskId ?: plan.requestId)?.decodeTask() ?: return null
        require(task.reorganizationOfArchiveId == plan.archiveId ||
            (plan.existingTaskId == task.id && task.archiveId == plan.archiveId)) { "整理确认已失效，请重新检查。" }
        return ReorganizationStart(task.archiveId, task.id, true)
    }

    private fun pendingReorganization(archiveId: String, tasks: List<TaskRecord>): TaskRecord? = tasks.firstOrNull {
        (it.archiveId == archiveId || it.reorganizationOfArchiveId == archiveId) &&
            (it.blocksReorganization() || jobs[it.id]?.isCompleted == false)
    }

    suspend fun organize(archiveId: String, conversationId: String, branchId: String, selectedIds: Set<String>) = operation {
        val settingsSnapshot = if (state.value.settings.mode == "companion") ensureCompanionIdentity() else state.value.settings
        val freeQuota = if (settingsSnapshot.mode == "companion") refreshQuotaState() else null
        val taskId = mutex.withLock {
            requireWritable()
            require(archiveId !in deletingArchives) { "档案正在删除" }
            val archive = loadArchive(archiveId)
            require(conversationId in archive.conversationIds) { "请先把该会话加入档案" }
            val tasks = dao.tasks().map { it.decodeTask() }
            requireTaskSlot(archiveId, null, tasks)
            require(tasks.none { it.archiveId == archiveId && jobs[it.id]?.isCompleted == false }) { "旧任务正在停止，请稍后重试" }
            val source = storedConversation(conversationId)
            val messages = selectMessages(source, branchId, selectedIds)
            require(messages.isNotEmpty()) { "请选择要整理的消息" }
            require(state.value.settings == settingsSnapshot) { "服务设置已改变，请重新检查后开始。" }
            if (freeQuota != null) require(freeQuota.organizationBlocker() == null) { freeQuota.organizationBlocker().orEmpty() }
            val settings = validateOrganizationConfiguration(if (freeQuota != null) settingsWithServerBudget(settingsSnapshot, freeQuota) else settingsSnapshot)
            val secret = if (settings.mode == "byok") settings.apiKey else settings.trialToken
            val chunks = budgetedChunks(messages, settings)
            require(chunks.isNotEmpty()) { "没有可整理的文字消息" }
            val id = UUID.randomUUID().toString()
            credentials.put("task:$id", secret)
            val records = chunks.map { chunk ->
                val key = organizationCacheKey(settings, SummaryProtocol.PROMPT_VERSION, chunk.id, chunk.messages)
                TaskChunk(chunk.id, chunk.messages, key)
            }
            val task = TaskRecord(id, archiveId, archive.title, settings.withoutSecrets(), "task:$id", records,
                message = estimateCost(settings, records), promptVersion = SummaryProtocol.PROMPT_VERSION)
            DiagnosticLog.record("task", "prepare", mapOf("chunk_count" to records.size.toLong()))
            database.withTransaction {
                val scoped = withSelectedSourceScope(archive, source, branchId, selectedIds)
                if (scoped != archive) saveArchive(scoped)
                saveTask(task)
            }
            refresh()
            id
        }
        launchTask(taskId)
    }

    suspend fun resumeTask(taskId: String, acknowledgeCost: Boolean) = operation {
        mutex.withLock {
            requireWritable()
            val task = loadTask(taskId)
            require(task.archiveId !in deletingArchives) { "档案正在删除" }
            val tasks = dao.tasks().map { it.decodeTask() }
            requireTaskSlot(task.archiveId, task.id, tasks)
            require(tasks.none { it.id != task.id && it.archiveId == task.archiveId && jobs[it.id]?.isCompleted == false }) { "其他任务正在停止，请稍后重试" }
            require(task.status != "completed") { "此任务已经完成" }
            require(jobs[taskId]?.isCompleted != false) { "任务仍在运行或停止中，请稍后再继续" }
            require(!task.uncertain || acknowledgeCost) {
                if (task.settings.mode == "companion") "上次请求可能已占用免费次数，确认继续可能消耗额外次数后再试。"
                else "上次请求的计费结果不确定。确认可能重复计费后再继续。"
            }
            var secretRef = task.secretRef
            if (secretRef.isBlank() || credentials.get(secretRef).isBlank()) {
                val current = state.value.settings
                val secret = if (task.settings.mode == "companion") {
                    credentials.get(companionIdentityKey(task.settings.companionUrl)).ifBlank {
                        if (current.companionUrl == task.settings.companionUrl) current.trialToken else ""
                    }
                } else {
                    require(current.mode == "byok" && current.baseUrl == task.settings.baseUrl && current.model == task.settings.model) {
                        "此任务原服务为 ${task.display().provider}，请先保存同一服务的凭据再继续"
                    }
                    current.apiKey
                }
                require(secret.isNotBlank()) { "原任务凭据不在这台设备中。请恢复原凭据，或取消旧任务后重新整理。" }
                secretRef = "task:$taskId"
                credentials.put(secretRef, secret)
            }
            val chunks = task.chunksForExplicitResume()
            DiagnosticLog.record("task", "resume", mapOf("chunk_count" to chunks.size.toLong(),
                "chunk_index" to chunks.count { it.completed }.toLong()))
            saveTask(task.copy(secretRef = secretRef, chunks = chunks, status = "queued", cancelRequested = false,
                inFlight = false, uncertain = false, message = "继续已保存进度"))
            refresh()
        }
        launchTask(taskId)
    }

    suspend fun resumeTask(taskId: String) = resumeTask(taskId, false)

    suspend fun pauseTask(taskId: String) = operation {
        mutex.withLock {
            val task = loadTask(taskId)
            if (task.status in setOf("completed", "cancelled", "paused")) return@withLock
            saveTask(task.withoutInFlight().copy(status = "paused", uncertain = task.uncertain || task.hasUncertainRequest(),
                message = if (task.inFlight && task.settings.mode == "companion") "已暂停。当前请求可能已占用免费次数，已完成进度保留。"
                else if (task.inFlight) "已暂停。当前请求可能已计费，恢复时会保留已完成进度。" else "已暂停，已完成进度保留。"))
            refresh()
        }
        jobs[taskId]?.cancelAndJoin()
        mutex.withLock { dao.task(taskId)?.decodeTask() }?.let { scheduleQuotaRefresh(it) }
    }

    suspend fun cancelTask(taskId: String) = operation {
        val task = mutex.withLock {
            val current = dao.task(taskId)?.decodeTask() ?: return@withLock null
            if (current.status == "completed") return@withLock null
            val updated = current.withoutInFlight().copy(status = "cancelled", cancelRequested = true,
                uncertain = current.uncertain || current.hasUncertainRequest(),
                message = if (current.inFlight && current.settings.mode == "companion") "已停止后续整理。在途请求可能已占用免费次数，已完成分块仍保留。"
                else if (current.inFlight) "已停止后续整理。在途请求可能已计费，已完成分块仍保留。" else "已取消，已完成分块仍保留。")
            saveTask(updated)
            refresh()
            updated
        } ?: return@operation
        jobs[taskId]?.cancelAndJoin()
        val latest = mutex.withLock { dao.task(taskId)?.decodeTask() } ?: task
        if (latest.settings.mode == "companion") {
            var unconfirmed = 0
            for (chunk in latest.chunks.filter { !it.completed && it.remoteJobId.isNotBlank() }.distinctBy { it.remoteJobId }) {
                try {
                    val remote = organizationNetwork.cancel(withTaskSecret(latest), chunk.remoteJobId)
                    withContext(NonCancellable) {
                        mutex.withLock {
                            dao.task(taskId)?.decodeTask()?.let { current ->
                                saveTask(current.updateChunk(chunk.id) { it.copy(remoteStatus = remote.status, inFlight = false) })
                                refresh()
                            }
                        }
                        if (remote.status == "completed") {
                            persistChunk(taskId, chunk, SummaryProtocol.validateEntries(remote.entries, chunk.messages))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unconfirmed++
                }
            }
            if (unconfirmed > 0) notice("本地任务已停止，${unconfirmed}个远端请求的取消暂未确认；已保留任务编号。")
        }
        scheduleQuotaRefresh(latest)
    }

    suspend fun revisions(archiveId: String): List<Archive> = operation {
        mutex.withLock { dao.revisions(archiveId).map { appJson.decodeFromString<Archive>(it.payload) } }
    }

    suspend fun restore(archiveId: String, revision: Archive) = operation {
        mutex.withLock {
            requireWritable()
            require(revision.id == archiveId) { "版本与档案不匹配" }
            require(dao.revisions(archiveId).any { appJson.decodeFromString<Archive>(it.payload) == revision }) { "该版本已不存在，请刷新版本列表" }
            require(dao.tasks().none { it.archiveId == archiveId &&
                (it.decodeTask().status in setOf("queued", "running") || jobs[it.id]?.isCompleted == false) }) { "请先取消正在运行的整理任务" }
            val allowedIds = sourceIds(revision)
            val obsoleteTasks = dao.tasks().map { it.decodeTask() }.filter { it.archiveId == archiveId && !taskSourcesFit(it, allowedIds) }
            database.withTransaction {
                saveArchive(revision)
                dao.deleteConflicts(archiveId)
                obsoleteTasks.forEach { dao.deleteTask(it.id) }
            }
            obsoleteTasks.map { it.secretRef }.filter { it.isNotBlank() }.forEach { credentials.remove(it) }
            refresh()
            notice("已恢复所选版本，恢复前内容保留在历史版本")
        }
    }

    suspend fun conflicts(archiveId: String): List<EntryConflict> = operation {
        mutex.withLock { dao.conflicts(archiveId).map { row ->
            val conflict = appJson.decodeFromString<StoredConflict>(row.payload)
            EntryConflict(conflict.existing, conflict.proposed)
        } }
    }

    suspend fun resolveConflict(archiveId: String, conflict: EntryConflict, useProposed: Boolean,
        observedGroup: List<EntryConflict>? = null) = operation {
        mutex.withLock {
            requireWritable()
            database.withTransaction {
                val all = dao.conflicts(archiveId).map { row ->
                    val stored = appJson.decodeFromString<StoredConflict>(row.payload)
                    EntryConflict(stored.existing, stored.proposed)
                }
                val archive = loadArchive(archiveId)
                val selected = observedGroup ?: listOf(conflict)
                require(conflict in selected) { "待处理内容已变化，请刷新列表。" }
                val merged = ReorganizationLogic.resolveConflictGroup(archive.entries, all, selected, useProposed)
                if (merged.entries != archive.entries) saveArchive(archive.copy(entries = merged.entries))
                dao.deleteConflicts(archiveId)
                saveConflicts(archiveId, merged.conflicts)
            }
            refresh()
        }
    }

    /** Small-fixture compatibility only. Product exports must use backupFile(). */
    internal suspend fun backup(): String = operation {
        mutex.withLock {
            database.withTransaction { appJson.encodeToString(backupDocument()) }
        }
    }

    /** The caller owns the complete private staging file and deletes it after save/cancel. */
    suspend fun backupFile(): File {
        var staged: File? = null
        try {
            return operation {
                val cache = getApplication<Application>().cacheDir
                val staleBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                cache.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("pending-backup-") &&
                    it.name.endsWith(".json") && it.lastModified() < staleBefore }.forEach { it.delete() }
                val file = File.createTempFile("pending-backup-", ".json", cache)
                staged = file
                mutex.withLock {
                    database.withTransaction {
                        file.outputStream().buffered().use { appJson.encodeToStream(backupDocument(), it) }
                    }
                }
                file
            }
        } catch (error: Throwable) {
            // Also handles cancellation while dispatching the finished File back to the UI.
            withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
            throw error
        }
    }

    private suspend fun backupDocument(): BackupDocument {
        val tasks = dao.tasks().map { row ->
            val task = row.decodeTask()
            val clean = task.copy(settings = task.settings.withoutSecrets(), secretRef = "",
                status = if (task.status in setOf("queued", "running")) "paused" else task.status,
                uncertain = task.uncertain || task.inFlight,
                message = "来自备份，恢复前请检查原服务和凭据")
            row.copy(payload = appJson.encodeToString(clean))
        }
        return BackupDocument(settings = state.value.settings.withoutSecrets(), sources = dao.sources(),
            conversations = dao.conversations(), archives = dao.archives(), revisions = dao.revisions(),
            conflicts = dao.conflicts(), tasks = tasks, cache = dao.cache())
    }

    internal suspend fun restoreBackup(json: String) = restoreBackupDocument { appJson.decodeFromString(json) }

    /** The caller owns and closes the URI stream; no whole-file byte array or String is created. */
    suspend fun restoreBackup(input: InputStream) = restoreBackupDocument {
        // Preserve the previous picker bound without retaining its whole byte array.
        val bounded = object : InputStream() {
            private var bytes = 0L
            private fun account(count: Int) {
                if (count > 0) bytes += count
                require(bytes <= Int.MAX_VALUE.toLong()) { "备份文件超过 2047 MB，无法读取。" }
            }
            override fun read(): Int = input.read().also { if (it >= 0) account(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                input.read(buffer, offset, length).also(::account)
        }
        appJson.decodeFromStream(bounded)
    }

    private suspend fun restoreBackupDocument(read: () -> BackupDocument) = operation {
        val backup = read()
        BackupValidation.validate(backup)
        val restoredSettings = validateSettings(backup.settings.withoutSecrets())
        mutex.withLock {
            requireWritable()
            require(jobs.values.all { it.isCompleted }) { "请先取消正在运行的任务再恢复备份" }
            val oldTaskIds = dao.tasks().map { it.id }
            conversationCacheValid = false
            database.withTransaction {
                dao.clearSources(); dao.clearConversations(); dao.clearArchives(); dao.clearRevisions()
                dao.clearConflicts(); dao.clearTasks(); dao.clearCache()
                backup.sources.forEach { dao.put(it) }
                backup.conversations.forEach { dao.putConversation(ConversationStorage.read(it.payload)) }
                backup.archives.forEach { dao.put(it) }
                backup.revisions.forEach { dao.put(it) }
                backup.conflicts.forEach { dao.put(it) }
                backup.cache.forEach { dao.put(it) }
                backup.tasks.forEach { row ->
                    val task = row.decodeTask()
                    val paused = task.copy(secretRef = "", settings = task.settings.withoutSecrets(),
                        status = if (task.status == "completed") "completed" else "paused", cancelRequested = false,
                        uncertain = task.uncertain || task.inFlight,
                        message = "已恢复进度，请保存原服务凭据后继续")
                    dao.put(row.copy(payload = appJson.encodeToString(paused)))
                }
            }
            oldTaskIds.forEach { credentials.remove("task:$it") }
            storeSettings(restoredSettings)
            mutableState.update { it.copy(settings = restoredSettings, quota = null, quotaLoading = false, quotaError = null) }
            refresh(reloadConversations = true)
            mutableState.update { it.copy(lastImportedConversationIds = emptyList()) }
            notice("备份已恢复。凭据不在备份中，请重新填写。")
        }
    }

    private fun launchTask(taskId: String) {
        if (jobs[taskId]?.isActive == true) return
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                runTask(taskId)
            } finally {
                jobs.remove(taskId, coroutineContext[Job])
            }
        }
        val previous = jobs.putIfAbsent(taskId, job)
        if (previous == null) job.start() else job.cancel()
    }

    private suspend fun runTask(taskId: String) {
        try {
            mutex.withLock {
                val task = loadTask(taskId)
                if (task.cancelRequested || task.status != "queued") return
                require(SummaryProtocol.supportsVersion(task.promptVersion)) { "不支持此任务的整理协议版本，请保留进度并更新应用" }
                require(taskSourcesFit(task, sourceIds(loadArchive(task.archiveId)))) { "档案来源已变化，请新建整理任务" }
                saveTask(task.copy(status = "running", message = "正在整理，成功分块将即时保存"))
                refresh()
            }
            val started = mutex.withLock { loadTask(taskId) }
            val failures = dispatchTaskChunks(started.chunks, organizationConcurrency(started.settings)) { chunk, maySubmit ->
                runTaskChunk(taskId, chunk, maySubmit)
            }
            if (failures.isNotEmpty()) throw TaskDispatchException(failures)
            mutex.withLock {
                val task = loadTask(taskId)
                if (!task.cancelRequested && task.status == "running" && task.chunks.all { it.completed }) {
                    val archive = loadArchive(task.archiveId)
                    val hasContent = archive.background.isNotBlank() || archive.entries.any { !it.deleted && it.text.isNotBlank() }
                    val message = if (task.reorganizationOfArchiveId != null && dao.conflicts(archive.id).isNotEmpty())
                        "已保留你的修订/删除，新建议可在详细内容的核对冲突查看"
                    else if (hasContent) "整理完成，人工修改和置顶内容已保留"
                    else "没有提取到可续聊的内容，可改用原文模式。"
                    saveTask(task.withoutInFlight().copy(status = "completed", uncertain = false, message = message))
                }
                refresh()
                scheduleQuotaRefresh(task)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    val task = dao.task(taskId)?.decodeTask()
                    if (task != null && task.status != "completed") {
                        saveTask(task.withoutInFlight().copy(
                            status = if (task.cancelRequested) "cancelled" else "paused",
                            uncertain = task.uncertain || task.hasUncertainRequest(),
                            message = if (task.status in setOf("paused", "cancelled")) task.message
                                else "整理已中断，进度已保存。请检查原请求后继续。"))
                        refresh()
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            mutex.withLock {
                val task = dao.task(taskId)?.decodeTask() ?: return
                val failures = (error as? TaskDispatchException)?.failures.orEmpty()
                val primary = failures.firstOrNull()?.error ?: error
                DiagnosticLog.record("task", "failed", buildMap {
                    put("chunk_count", task.chunks.size.toLong())
                    put("chunk_index", task.chunks.count { it.completed }.toLong())
                    if (primary is SummaryFailure) primary.statusCode?.let { put("http_status", it.toLong()) }
                }, primary)
                viewModelScope.launch { prepareDiagnosticsIdentity() }
                if (!task.cancelRequested) {
                    saveTask(if (failures.isNotEmpty()) task.afterDispatchFailures(failures)
                        else task.withoutInFlight().copy(status = "paused", uncertain = task.uncertain ||
                            (if (primary is SummaryFailure) primary.ambiguous || primary.expired else task.hasUncertainRequest()),
                            message = primary.message?.take(1500) ?: "整理暂停，请检查网络和服务设置"))
                }
                refresh()
                scheduleQuotaRefresh(task)
            }
        }
    }

    private suspend fun runTaskChunk(taskId: String, candidate: TaskChunk, maySubmit: ChunkAdmission) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val task = mutex.withLock { loadTask(taskId) }
        if (task.cancelRequested || task.status != "running") throw CancellationException()
        val chunk = task.chunks.first { it.id == candidate.id }
        if (chunk.completed) return
        val cached = dao.cache(chunk.cacheKey)
        if (cached != null) {
            val entries = SummaryProtocol.validateEntries(appJson.decodeFromString<List<ArchiveEntry>>(cached.payload), chunk.messages)
            withContext(NonCancellable) { persistChunk(taskId, chunk, entries) }
            return
        }
        val settings = withTaskSecret(task)
        SummaryProtocol.validateForRequest(settings, chunk.messages, task.promptVersion)
        val entries = if (settings.mode == "byok") {
            if (!recordChunkStart(taskId, chunk, maySubmit)) return
            organizationNetwork.summarize(settings, chunk.messages, task.promptVersion)
        } else runCompanionChunk(task, chunk, settings, maySubmit) ?: return
        val validated = SummaryProtocol.validateEntries(entries, chunk.messages)
        withContext(NonCancellable) { persistChunk(taskId, chunk, validated) }
        scheduleQuotaRefresh(task)
    }

    private suspend fun recordChunkStart(taskId: String, chunk: TaskChunk, admission: ChunkAdmission): Boolean =
        admission.recordStart(persistStarted = {
            mutex.withLock {
                val current = loadTask(taskId)
                if (current.cancelRequested || current.status != "running") throw CancellationException()
                saveTask(current.updateChunk(chunk.id) { it.copy(requestStarted = true, inFlight = true) })
            }
        }, abandonUnsent = {
            mutex.withLock {
                val current = loadTask(taskId)
                saveTask(current.updateChunk(chunk.id) { it.copy(requestStarted = chunk.requestStarted, inFlight = false) })
            }
        })

    private suspend fun runCompanionChunk(
        task: TaskRecord, chunk: TaskChunk, settings: ServiceSettings, maySubmit: ChunkAdmission,
    ): List<ArchiveEntry>? {
        var remoteId = chunk.remoteJobId
        var remote = if (remoteId.isEmpty()) {
            if (!maySubmit()) return null
            // A lost POST is recovered by the original idempotency identity even
            // when it consumed the last quota or the service budget has changed.
            if (!chunk.requestStarted) {
                val quota = organizationNetwork.quota(settings)
                quota.organizationBlocker()?.let { throw SummaryFailure(it, ambiguous = false) }
                val currentBudget = settingsWithServerBudget(settings, quota, task.promptVersion)
                if (currentBudget.model != settings.model || currentBudget.contextTokens != settings.contextTokens ||
                    currentBudget.maxOutputTokens != settings.maxOutputTokens) {
                    throw SummaryFailure("免费服务配置已更新，已保留完成进度。请取消旧任务后按新配置重新整理。", ambiguous = false)
                }
            }
            if (!recordChunkStart(task.id, chunk, maySubmit)) return null
            val submitted = organizationNetwork.submit(settings, chunk.messages, "${task.id}:${chunk.id}:${chunk.attempt}")
            if (submitted.status !in setOf("queued", "running", "completed")) maySubmit.stop()
            remoteId = submitted.id
            withContext(NonCancellable) {
                mutex.withLock {
                    val current = loadTask(task.id)
                    saveTask(current.updateChunk(chunk.id) { it.copy(remoteJobId = remoteId, remoteStatus = submitted.status) })
                }
            }
            submitted
        } else {
            mutex.withLock {
                val current = loadTask(task.id)
                if (current.cancelRequested || current.status != "running") throw CancellationException()
                saveTask(current.updateChunk(chunk.id) { it.copy(requestStarted = true, inFlight = true) })
            }
            organizationNetwork.poll(settings, remoteId)
        }
        repeat(1200) {
            // Stop admissions before the durable terminal-state write can yield
            // to a sibling that just finished and is looking for its next chunk.
            if (remote.status !in setOf("queued", "running", "completed")) maySubmit.stop()
            if (remote.status !in setOf("queued", "running")) withContext(NonCancellable) {
                mutex.withLock {
                    val current = loadTask(task.id)
                    saveTask(current.updateChunk(chunk.id) { it.copy(remoteStatus = remote.status) })
                }
            }
            when (remote.status) {
                "completed" -> return SummaryProtocol.validateEntries(remote.entries, chunk.messages)
                "uncertain" -> throw SummaryFailure(remote.message.ifBlank { "远端请求结果不确定，请检查已使用次数和任务状态" }, true)
                "failed", "cancelled" -> throw SummaryFailure(remote.message.ifBlank { "远端任务已${if (remote.status == "failed") "失败" else "取消"}，已保留进度" }, false)
                "queued", "running" -> Unit
                else -> throw SummaryFailure("服务返回未知任务状态，已暂停", true)
            }
            delay(1500)
            remote = organizationNetwork.poll(settings, remoteId)
        }
        throw SummaryFailure("远端任务仍在处理，已保存任务编号，可稍后继续查询", false)
    }

    private suspend fun persistChunk(taskId: String, chunk: TaskChunk, entries: List<ArchiveEntry>) {
        mutex.withLock {
            // Save the paid result even if later archive merging cannot finish.
            dao.put(ChunkCacheRow(chunk.cacheKey, appJson.encodeToString(entries)))
            database.withTransaction {
                val task = dao.task(taskId)?.decodeTask() ?: return@withTransaction
                if (task.chunks.first { it.id == chunk.id }.completed) return@withTransaction
                val archive = loadArchive(task.archiveId)
                require(taskSourcesFit(task, sourceIds(archive))) { "档案来源已变化，分块结果已保存，请新建整理任务" }
                val parts = task.chunks.filter { it.completed || it.id == chunk.id }.associate { completed ->
                    val cached = dao.cache(completed.cacheKey) ?: error("已完成分块缓存缺失，请恢复完整备份")
                    completed.id to SummaryProtocol.validateEntries(appJson.decodeFromString<List<ArchiveEntry>>(cached.payload), completed.messages)
                }
                val previousProgress = aggregateChunkEntries(task.chunks.filter { it.completed }.map { parts.getValue(it.id) })
                val currentProgress = aggregateChunkEntries(task.chunks.filter { it.completed || it.id == chunk.id }.map { parts.getValue(it.id) })
                val merged = mergeTaskProgress(archive.entries, previousProgress, currentProgress,
                    rebuilding = task.reorganizationOfArchiveId != null)
                saveArchive(archive.copy(entries = merged.entries,
                    generationMode = if (archive.generationMode == "original" && entries.isNotEmpty()) "legacy" else archive.generationMode))
                saveConflicts(archive.id, merged.conflicts)
                val updated = task.updateChunk(chunk.id) { it.copy(completed = true, inFlight = false) }
                saveTask(updated.copy(message = if (task.status == "running")
                    "已保存 ${updated.chunks.count { it.completed }}/${task.chunks.size} 个分块" else task.message))
            }
            refresh()
        }
    }

    private fun withTaskSecret(task: TaskRecord): ServiceSettings {
        val secret = credentials.get(task.secretRef)
        require(secret.isNotBlank()) { "原任务凭据不可用，请保存原服务凭据后继续" }
        return if (task.settings.mode == "byok") task.settings.copy(apiKey = secret) else task.settings.copy(trialToken = secret)
    }

    private suspend fun storedConversation(id: String): ImportedConversation {
        if (!conversationCacheValid) refresh(reloadConversations = true)
        return state.value.conversations.firstOrNull { it.id == id } ?: error("原会话不存在，请重新导入")
    }

    private fun selectMessages(conversation: ImportedConversation, branchId: String, selectedIds: Set<String>): List<ChatMessage> {
        val branch = conversation.branches.firstOrNull { it.id == branchId } ?: error("请选择有效的对话分支")
        require(branch.messages.map { it.id }.toSet().containsAll(selectedIds)) { "选择中包含其他分支消息，请重新选择" }
        return branch.messages.filter { it.id in selectedIds && it.content.isNotBlank() }
    }

    private fun withSelectedSourceScope(
        archive: Archive, conversation: ImportedConversation, branchId: String, selectedIds: Set<String>,
    ): Archive {
        val scopes = (archive.sourceScopes.ifEmpty {
            ContinuityLogic.inferEntryScopes(archive, state.value.conversations)
        }).toMutableList()
        val index = scopes.indexOfFirst { it.conversationId == conversation.id && it.branchId == branchId }
        val previousIds = if (index >= 0) scopes[index].selectedMessageIds else emptyList()
        val selected = ContinuityLogic.captureScope(conversation, branchId, previousIds.toSet() + selectedIds)
        if (index >= 0) scopes[index] = selected else scopes.add(selected)
        return archive.copy(sourceScopes = scopes)
    }

    private suspend fun sourceIds(archive: Archive): Set<String> {
        if (!conversationCacheValid) refresh(reloadConversations = true)
        return state.value.conversations.asSequence().filter { it.id in archive.conversationIds }
            .flatMap { it.branches }.flatMap { it.messages }.map { it.id }.toSet()
    }

    private suspend fun loadArchive(id: String): Archive = dao.archive(id)?.let { appJson.decodeFromString<Archive>(it.payload) }
        ?: error("档案已不存在")

    private suspend fun loadTask(id: String): TaskRecord = dao.task(id)?.decodeTask() ?: error("任务已不存在")

    private suspend fun saveArchive(archive: Archive, keepRevision: Boolean = true) {
        if (!conversationCacheValid) refresh(reloadConversations = true)
        BackupValidation.validateContinuity(archive, state.value.conversations)
        val previous = dao.archive(archive.id)
        if (keepRevision && previous != null) dao.put(RevisionRow(UUID.randomUUID().toString(), archive.id, System.currentTimeMillis(), previous.payload))
        val updated = archive.copy(updatedAt = System.currentTimeMillis())
        dao.put(ArchiveRow(updated.id, updated.updatedAt, appJson.encodeToString(updated)))
    }

    private suspend fun saveConflicts(archiveId: String, conflicts: List<EntryConflict>) {
        conflicts.forEach { conflict ->
            dao.conflicts(archiveId).filter { row ->
                val previous = appJson.decodeFromString<StoredConflict>(row.payload)
                previous.existing.id == conflict.existing.id && previous.proposed.id == conflict.proposed.id
            }.forEach { dao.deleteConflict(it.id) }
            val payload = appJson.encodeToString(StoredConflict(conflict.existing, conflict.proposed))
            dao.put(ConflictRow(hash(archiveId + payload), archiveId, payload))
        }
    }

    private suspend fun saveTask(task: TaskRecord) {
        require(task.settings.apiKey.isEmpty() && task.settings.trialToken.isEmpty()) { "凭据必须保存到设备安全存储" }
        dao.put(TaskRow(task.id, task.archiveId, System.currentTimeMillis(), appJson.encodeToString(task)))
    }

    private suspend fun refresh(reloadConversations: Boolean = false) {
        val conversations = if (reloadConversations || !conversationCacheValid) dao.conversationIds().map { id ->
            requireNotNull(dao.readConversation(id))
        } else state.value.conversations
        val archives = dao.archives().map { appJson.decodeFromString<Archive>(it.payload) }
        val tasks = dao.tasks().map { it.decodeTask().display() }
        mutableState.update { it.copy(conversations = conversations, archives = archives, tasks = tasks) }
        conversationCacheValid = true
    }

    private fun notice(message: String) = mutableState.update { it.copy(notice = message) }

    private fun requireWritable() = require(!clearingData) { "正在清除本地数据，请稍后操作" }

    private fun storeSettings(settings: ServiceSettings) {
        // Provider metadata and its credential commit together as one encrypted preference value.
        if (settings.trialToken.isNotBlank()) credentials.put(companionIdentityKey(settings.companionUrl), settings.trialToken)
        credentials.put("current_settings", appJson.encodeToString(settings))
        credentials.remove("api_key")
        credentials.remove("trial_token")
        check(preferences.edit().clear().commit()) { "服务设置已保存，但旧设置清理失败" }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        ready.await()
        activeOperations.incrementAndGet()
        mutableState.update { it.copy(busy = true) }
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notice(error.message?.take(1500) ?: "操作失败，请重试")
            throw error
        } finally {
            val busy = activeOperations.decrementAndGet() > 0
            mutableState.update { it.copy(busy = busy) }
        }
    }
}

internal fun TaskRow.decodeTask(): TaskRecord {
    val task = appJson.decodeFromString<TaskRecord>(payload)
    if ((task.inFlight || task.uncertain) && task.chunks.none { it.requestStarted || it.inFlight }) {
        // Old serial versions cleared aggregate inFlight after a lost POST.
        // Migrate only rows that actually predate the per-chunk field: the
        // uncertainty flag on a new, explicitly prepared retry is not evidence
        // that it was already submitted.
        val document = appJson.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
        val chunks = document["chunks"] as kotlinx.serialization.json.JsonArray
        if (chunks.none { "requestStarted" in (it as kotlinx.serialization.json.JsonObject) }) {
            val activeId = task.chunks.firstOrNull { !it.completed }?.id
            return task.copy(chunks = task.chunks.map { if (it.id == activeId) it.copy(requestStarted = true) else it })
        }
    }
    return task
}
internal fun hash(value: String): String = hash(value.toByteArray(Charsets.UTF_8))
internal fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

internal fun validateSettings(settings: ServiceSettings): ServiceSettings {
    require(settings.mode in setOf("byok", "companion")) { "服务类型无效" }
    require(settings.contextTokens in 512..262144) { "上下文预算应为512至262144 tokens" }
    require(settings.maxOutputTokens in 256..8192 && settings.contextTokens > settings.maxOutputTokens) { "输出预算应为256至8192 tokens，且小于上下文预算" }
    require(settings.model.isNotBlank() && settings.model.length <= 200 && settings.model.none { it.isISOControl() }) { "请输入有效模型名称" }
    listOf(settings.inputPricePerMillion, settings.outputPricePerMillion).forEach {
        require(it == null || (it.isFinite() && it >= 0)) { "价格应为非负有限数值" }
    }
    listOf(settings.baseUrl, settings.companionUrl).filter { it.isNotBlank() }.forEach { value ->
        val url = value.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "服务地址必须是无凭据、查询参数或锚点的 HTTPS 地址" }
    }
    if (settings.mode == "byok") require(settings.baseUrl.isNotBlank()) { "请填写 API 地址" }
    else require(settings.companionUrl.isNotBlank()) { "请填写邀请服务地址" }
    return settings.copy(baseUrl = settings.baseUrl.trim().trimEnd('/'), companionUrl = settings.companionUrl.trim().trimEnd('/'),
        model = settings.model.trim(), apiKey = settings.apiKey.trim(), trialToken = settings.trialToken.trim())
}

internal fun validateOrganizationConfiguration(settings: ServiceSettings): ServiceSettings {
    val cleaned = validateSettings(settings)
    val secret = if (cleaned.mode == "byok") cleaned.apiKey else cleaned.trialToken
    require(secret.isNotBlank()) { if (cleaned.mode == "byok") "请先在服务设置中填写 API Key。" else "免费身份尚未建立，请刷新免费额度后再试。" }
    require(secret.length <= 8192 && secret.all { it.code in 33..126 }) { "密钥或令牌格式无效，请在服务设置中重新填写。" }
    SummaryProtocol.chunkCharacters(cleaned)
    return cleaned
}

private fun estimateCost(settings: ServiceSettings, chunks: List<TaskChunk>): String {
    val inputPrice = settings.inputPricePerMillion
    val outputPrice = settings.outputPricePerMillion
    if (settings.mode == "companion") return "共${chunks.size}个分块，每个新请求使用1次免费额度，服务端会先检查当日余量"
    if (inputPrice == null || outputPrice == null) return "共${chunks.size}个分块，费用由原服务商计费。填写单价后可估算。"
    val input = chunks.sumOf { SummaryProtocol.estimatedInputTokens(it.messages) }
    val output = chunks.size * settings.maxOutputTokens
    val estimated = (input * inputPrice + output * outputPrice) / 1_000_000.0
    return "共${chunks.size}个分块，按填写单价保守估算约 ${"%.4f".format(estimated)}，实际以服务商账单为准"
}

internal fun budgetedChunks(messages: List<ChatMessage>, settings: ServiceSettings,
    promptVersion: String = SummaryProtocol.PROMPT_VERSION): List<app.banzhan.deepseekcontinuation.core.SummaryChunk> {
    // Context capacity is not a useful target size for a concise summary: a
    // 64k context previously produced 14k-character chunks with only 4k output.
    // Keep each request bounded by its output budget as well as input capacity.
    var limit = minOf(SummaryProtocol.chunkCharacters(settings, promptVersion), (settings.maxOutputTokens * 2).coerceAtMost(6000))
    while (true) {
        val chunks = ArchiveLogic.chunks(messages, limit)
        if (chunks.all { SummaryProtocol.estimatedInputTokens(it.messages, promptVersion).toLong() + settings.maxOutputTokens <= settings.contextTokens }) return chunks
        require(limit > 2) { "来源编号或消息结构超出上下文预算，请提高上下文长度" }
        limit = (limit / 2).coerceAtLeast(2)
    }
}

fun estimateOrganization(settings: ServiceSettings, messages: List<ChatMessage>): OrganizationEstimate {
    if (messages.isEmpty()) return OrganizationEstimate(0, 0, 0, 0.0)
    val chunks = budgetedChunks(messages, settings)
    val input = chunks.sumOf { SummaryProtocol.estimatedInputTokens(it.messages) }
    val output = chunks.size * settings.maxOutputTokens
    val cost = if (settings.mode == "byok" && settings.inputPricePerMillion != null && settings.outputPricePerMillion != null)
        (input * settings.inputPricePerMillion + output * settings.outputPricePerMillion) / 1_000_000.0 else null
    return OrganizationEstimate(chunks.size, input, output, cost)
}
