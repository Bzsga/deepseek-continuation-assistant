@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.banzhan.deepseekcontinuation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AiRequest(
    val archiveId: String,
    val conversationId: String,
    val branchId: String,
    val selectedIds: Set<String>,
    val messages: List<ChatMessage>,
)

@Composable
fun ContinuationApp(
    controller: AppController,
    incomingImport: PendingIncomingImport? = null,
    onIncomingImportConfirmed: (String) -> Unit = {},
    onIncomingImportConsumed: (String) -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val politicalState by controller.politicalExport.state.collectAsStateWithLifecycle()
    val updates: AppUpdateViewModel = viewModel()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val currentIncomingImport by rememberUpdatedState(incomingImport)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val screenStates = rememberSaveableStateHolder()
    var route by rememberSaveable { mutableStateOf("home") }
    var conversationId by rememberSaveable { mutableStateOf("") }
    var archiveId by rememberSaveable { mutableStateOf("") }
    var batchIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var batchNumber by rememberSaveable { mutableIntStateOf(0) }
    var selectionTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var advancedBranchId by rememberSaveable { mutableStateOf<String?>(null) }
    var serviceReturn by rememberSaveable { mutableStateOf("prepare") }
    var developerReturn by rememberSaveable { mutableStateOf("settings") }
    var preparationError by rememberSaveable { mutableStateOf<String?>(null) }
    var progressError by rememberSaveable { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pendingDocumentPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDocumentMessage by rememberSaveable { mutableStateOf("") }
    var exportArchive by remember { mutableStateOf<Archive?>(null) }
    var politicalArchiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var aiRequest by remember { mutableStateOf<AiRequest?>(null) }
    var reorganizationPlan by remember { mutableStateOf<ReorganizationPlan?>(null) }
    var reorganizationSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var reorganizationReturnRoute by rememberSaveable { mutableStateOf("result") }
    var reorganizationLoading by remember { mutableStateOf(false) }
    var reorganizationError by remember { mutableStateOf<String?>(null) }
    var restoreConfirmation by remember { mutableStateOf(false) }
    var getRecordsVisible by rememberSaveable { mutableStateOf(false) }
    var installPreparing by remember { mutableStateOf(false) }
    var permissionPendingVersion by rememberSaveable { mutableStateOf<Int?>(null) }
    var permissionLaunchResumeTick by rememberSaveable { mutableIntStateOf(-1) }
    var resumeTick by rememberSaveable { mutableIntStateOf(0) }
    val manualPasteDraft: ManualPasteDraft = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, manualPasteDraft) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) manualPasteDraft.flush()
            if (event == Lifecycle.Event.ON_START) updates.checkOnStart()
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val run: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snackbars.showSnackbar(error.message ?: "操作未完成，请重试。")
            }
        }
    }
    fun prepare(id: String) {
        conversationId = id
        preparationError = null
        route = "prepare"
    }
    fun imported(ids: List<String>) {
        batchIds = ArrayList(ids)
        batchNumber++
        if (ids.size == 1) prepare(ids.first()) else route = "choose"
    }
    fun openRecord(id: String) {
        archiveId = id
        progressError = null
        val record = controller.state.value.archives.firstOrNull { it.id == id }
        val task = controller.state.value.tasks.firstOrNull { it.archiveId == id }
        route =
            if (task != null && task.status != "completed") "progress"
            else if (
                record?.generationMode == "summary" &&
                    task == null &&
                    record.entries.none { !it.deleted && it.text.isNotBlank() }
            )
                "progress"
            else "result"
    }
    fun requestReorganization(id: String, confirmedScopes: List<ArchiveSourceScope>? = null) {
        if (submitting || reorganizationLoading) return
        reorganizationLoading = true
        reorganizationError = null
        reorganizationSourceId = id
        if (route in setOf("result", "archive")) reorganizationReturnRoute = route
        if (reorganizationPlan?.archiveId != id) reorganizationPlan = null
        run {
            try {
                val prepared = controller.prepareReorganization(id, confirmedScopes)
                if (prepared.existingTargetArchiveId != null) {
                    reorganizationPlan = null
                    openRecord(prepared.existingTargetArchiveId)
                } else {
                    if (route in setOf("reorganization_choose", "reorganization_selection")) {
                        archiveId = id
                        route = reorganizationReturnRoute
                    }
                    reorganizationPlan = prepared
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (reorganizationPlan != null) reorganizationError = error.message ?: "暂时无法准备，请重试。"
                else throw error
            } finally {
                reorganizationLoading = false
            }
        }
    }
    fun prepareCurrentRecord() {
        val id =
            controller.state.value.archives
                .firstOrNull { it.id == archiveId }
                ?.conversationIds
                ?.firstOrNull()
        if (id == null) route = "home" else prepare(id)
    }
    fun advancedConversation(id: String, target: String? = null, branch: String? = null) {
        conversationId = id
        selectionTargetId = target
        advancedBranchId = branch
        route = "selection"
    }
    fun service(returnRoute: String) {
        serviceReturn = returnRoute
        route = "service"
    }
    fun back() {
        route =
            when (route) {
                "archive" -> "result"
                "selection" -> if (selectionTargetId != null) "archive" else "prepare"
                "reorganization_selection" -> {
                    val original = state.archives.firstOrNull { it.id == reorganizationSourceId }
                    if (state.conversations.count { it.id in original?.conversationIds.orEmpty() } > 1)
                        "reorganization_choose"
                    else {
                        archiveId = reorganizationSourceId ?: archiveId
                        reorganizationReturnRoute
                    }
                }
                "reorganization_choose" -> {
                    archiveId = reorganizationSourceId ?: archiveId
                    reorganizationReturnRoute
                }
                "prepare" -> if (batchIds.size > 1) "choose" else "home"
                "service" -> serviceReturn
                "developer" -> developerReturn
                "about" -> "settings"
                "feedback" -> "settings"
                "result",
                "progress" -> "home"
                else -> "home"
            }
    }
    fun begin(conversation: ImportedConversation, choice: PreparationChoice, smart: Boolean) {
        if (submitting) return
        submitting = true
        preparationError = null
        progressError = null
        run {
            var createdId: String? = null
            try {
                if (smart) {
                    val settings = controller.state.value.settings
                    val configured = settings.mode == "companion" || settings.apiKey.isNotBlank()
                    if (!configured) {
                        service("prepare")
                        return@run
                    }
                    val blocker = controller.checkOrganizationAvailability()
                    if (blocker != null) {
                        preparationError = blocker
                        return@run
                    }
                }
                val id =
                    controller.createArchive(
                        choice.title,
                        conversation,
                        choice.branchId,
                        choice.selectedIds,
                        choice.background,
                        includeRawEntries = !smart,
                    )
                createdId = id
                archiveId = id
                if (smart) {
                    route = "progress"
                    controller.organize(id, conversation.id, choice.branchId, choice.selectedIds)
                } else route = "result"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "这次整理没有开始，请检查后重试。"
                if (createdId != null) {
                    progressError = message
                    route = "progress"
                } else preparationError = message
            } finally {
                submitting = false
            }
        }
    }
    suspend fun stageDocument(content: String, message: String) {
        pendingDocumentPath =
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "pending-document.txt")
                    .apply { writeText(content) }
                    .absolutePath
            }
        pendingDocumentMessage = message
    }
    fun finishDocument(uri: Uri?) {
        val path = pendingDocumentPath
        val message = pendingDocumentMessage
        pendingDocumentPath = null
        if (path == null) return
        run {
            withContext(Dispatchers.IO) {
                val file = File(path)
                try {
                    if (uri != null)
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("无法写入所选位置")
                } finally { file.delete() }
            }
            if (uri != null) snackbars.showSnackbar(message)
        }
    }
    BackHandler(enabled = route !in setOf("home", "archives", "settings")) { back() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            controller.clearNotice()
            snackbars.showSnackbar(it)
        }
    }
    LaunchedEffect(route, state.settings.mode, state.settings.companionUrl, aiRequest?.archiveId) {
        if (
            state.settings.mode == "companion" &&
                (route in setOf("home", "prepare", "settings", "service") || aiRequest != null)
        ) {
            run { controller.refreshQuota() }
        }
    }
    val currentTask = state.tasks.firstOrNull { it.archiveId == archiveId }
    LaunchedEffect(route, currentTask?.id, currentTask?.status) {
        if (route == "progress" && currentTask?.status == "completed") route = "result"
    }
    val importLauncher =
        rememberLauncherForActivityResult(ImportDocumentContract()) { uri ->
            if (uri != null)
                run {
                    val document = withContext(Dispatchers.IO) { readDocument(context, uri) }
                    imported(controller.importFile(document.second, document.first))
                }
        }
    val saveMarkdown =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
            finishDocument(it)
        }
    val saveContinuation =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            finishDocument(it)
        }
    val saveBackup =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            finishDocument(it)
        }
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                run {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { controller.restoreBackup(it) }
                            ?: error("无法读取所选备份")
                    }
                }
        }
    val import: () -> Unit = {
        try {
            importLauncher.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/json",
                    "text/json",
                    "application/octet-stream",
                )
            )
        } catch (_: android.content.ActivityNotFoundException) {
            run { snackbars.showSnackbar("无法打开文件选择器，请从浏览器下载记录分享 ZIP 到本应用。") }
        }
    }
    val importingSharedFile = incomingImport?.status == IncomingImportStatus.IMPORTING
    val busy = state.busy || submitting || reorganizationLoading || importingSharedFile || installPreparing
    val politicalRunning = politicalState.task?.status in setOf("queued", "running")
    val existingModal = aiRequest != null || exportArchive != null || politicalArchiveId != null || restoreConfirmation || reorganizationPlan != null || reorganizationLoading
    val incomingBlocked =
        busy || existingModal || politicalRunning || state.tasks.any { it.status in setOf("queued", "running") }
    fun installationBlocker(): String? {
        val current = controller.state.value
        return when {
            controller.politicalExport.state.value.task?.status in setOf("queued", "running") -> "温和版检查正在进行，请完成后再安装更新。"
            current.tasks.any { it.status in setOf("queued", "running") } -> "整理正在进行，请完成后再安装更新。"
            currentIncomingImport != null -> "请先处理收到的聊天文件，再安装更新。"
            current.busy || submitting || reorganizationLoading -> "当前操作完成后可安装更新。"
            aiRequest != null ||
                exportArchive != null ||
                politicalArchiveId != null ||
                reorganizationPlan != null ||
                restoreConfirmation ||
                getRecordsVisible -> "请先完成当前页面操作，再安装更新。"
            route !in setOf("home", "archives", "settings", "about") -> "回到首页或关于页后可安装更新。"
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ->
                "回到应用后可继续安装。"
            else -> null
        }
    }
    fun requestUpdateInstallation() {
        if (installPreparing) return
        val blocked = installationBlocker()
        if (blocked != null) {
            run { snackbars.showSnackbar(blocked) }
            return
        }
        installPreparing = true
        run {
            try {
                val verifiedFile = updates.verifiedFileForInstall()
                val blocker = installationBlocker()
                if (blocker != null) {
                    snackbars.showSnackbar(blocker)
                    return@run
                }
                if (AppUpdateInstaller.permissionRequired(context)) {
                    permissionPendingVersion = updates.state.value.manifest?.versionCode
                    permissionLaunchResumeTick = resumeTick
                    context.startActivity(AppUpdateInstaller.permissionIntent(context))
                } else {
                    permissionPendingVersion = null
                    context.startActivity(
                        AppUpdateInstaller.installationIntent(context, verifiedFile)
                    )
                }
            } catch (_: android.content.ActivityNotFoundException) {
                permissionPendingVersion = null
                snackbars.showSnackbar("系统暂时无法打开更新安装页面，请稍后再试。")
            } catch (_: SecurityException) {
                permissionPendingVersion = null
                snackbars.showSnackbar("请允许本应用安装更新后重试。")
            } finally {
                installPreparing = false
            }
        }
    }
    LaunchedEffect(updates) { updates.checkOnStart() }
    val installBlockedReason = if (installPreparing) "正在检查更新包，请稍候。" else installationBlocker()
    val installPermissionRequired = AppUpdateInstaller.permissionRequired(context)
    LaunchedEffect(resumeTick, permissionPendingVersion, updateState.status, installBlockedReason) {
        val pendingVersion = permissionPendingVersion ?: return@LaunchedEffect
        if (resumeTick <= permissionLaunchResumeTick || installPreparing) return@LaunchedEffect
        if (AppUpdateInstaller.permissionRequired(context)) {
            permissionPendingVersion = null
            snackbars.showSnackbar("尚未允许安装更新，可以稍后再试。")
        } else if (updateState.status == AppUpdateStatus.READY && installBlockedReason == null) {
            permissionPendingVersion = null
            if (updateState.manifest?.versionCode == pendingVersion) requestUpdateInstallation()
        } else if (updateState.status == AppUpdateStatus.FAILED) {
            permissionPendingVersion = null
        }
    }
    LaunchedEffect(incomingImport?.id) { if (incomingImport != null) getRecordsVisible = false }
    LaunchedEffect(incomingImport?.id, incomingImport?.status, incomingBlocked, route) {
        val pending = incomingImport ?: return@LaunchedEffect
        when (pending.status) {
            IncomingImportStatus.AWAITING_CONFIRMATION ->
                if (route == "home" && !incomingBlocked) onIncomingImportConfirmed(pending.id)
            IncomingImportStatus.COMPLETED -> {
                imported(pending.conversationIds)
                onIncomingImportConsumed(pending.id)
            }
            else -> Unit
        }
    }
    ContinuationTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (route) {
                                "choose" -> "选择聊天"
                                "prepare" -> "准备续聊"
                                "progress" -> "整理进度"
                                "result" -> "去 DeepSeek 续聊"
                                "selection" -> "调整范围与管理"
                                "reorganization_choose" -> "选择重新整理的聊天"
                                "reorganization_selection" -> "重新选择范围"
                                "archive" -> "详细内容"
                                "tasks" -> "整理进度"
                                "about" -> "关于"
                                "paste" -> "粘贴聊天内容"
                                "service" -> "整理服务"
                                "developer" -> "开发者选项"
                                "feedback" -> "意见信箱"
                                else -> "Deepseek续聊助手"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        if (route !in setOf("home", "archives", "settings"))
                            IconButton(onClick = { back() }) {
                                Icon(Icons.Outlined.ArrowBack, "返回")
                            }
                    },
                    actions = {
                        if (route != "tasks" && route != "progress")
                            IconButton(onClick = { route = "tasks" }) {
                                BadgedBox(
                                    badge = {
                                        val active =
                                            state.tasks.count {
                                                it.status in setOf("queued", "running", "paused")
                                            }
                                        if (active > 0) Badge { Text(active.toString()) }
                                    }
                                ) {
                                    Icon(Icons.Outlined.Checklist, "整理进度")
                                }
                            }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Mist),
                )
            },
            bottomBar = {
                if (route in setOf("home", "archives", "settings"))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            route == "home",
                            { route = "home" },
                            icon = { Icon(Icons.Outlined.Home, null) },
                            label = { Text("首页") },
                        )
                        NavigationBarItem(
                            route == "archives",
                            { route = "archives" },
                            icon = { Icon(Icons.Outlined.FolderOpen, null) },
                            label = { Text("续聊记录") },
                        )
                        NavigationBarItem(
                            route == "settings",
                            { route = "settings" },
                            icon = { Icon(Icons.Outlined.Settings, null) },
                            label = { Text("设置") },
                        )
                    }
            },
            snackbarHost = { SnackbarHost(snackbars) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (
                    updateState.promptVisible &&
                        route in setOf("home", "archives", "settings") &&
                        incomingImport == null &&
                        !existingModal &&
                        !getRecordsVisible &&
                        !WindowInsets.isImeVisible
                ) {
                    AppUpdateBanner(
                        state = updateState,
                        installBlockedReason = installBlockedReason,
                        installPermissionRequired = installPermissionRequired,
                        onDownload = { if (!installPreparing) updates.downloadUpdate() },
                        onCancelDownload = updates::cancelDownload,
                        onInstall = ::requestUpdateInstallation,
                        onLater = updates::dismissForSession,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                incomingImport
                    ?.takeIf { it.status == IncomingImportStatus.AWAITING_CONFIRMATION }
                    ?.let { pending ->
                        IncomingImportBar(
                            pending.fileName,
                            blocked = incomingBlocked,
                            onImport = { onIncomingImportConfirmed(pending.id) },
                            onCancel = { onIncomingImportConsumed(pending.id) },
                        )
                    }
                Box(Modifier.weight(1f)) {
                    val screenKey =
                        when (route) {
                            "prepare",
                            "selection" -> "$route:$conversationId"
                            "reorganization_selection" -> "$route:$reorganizationSourceId:$conversationId"
                            "reorganization_choose" -> "$route:$reorganizationSourceId"
                            "result",
                            "progress",
                            "archive" -> "$route:$archiveId"
                            "choose" -> "choose:$batchNumber"
                            else -> route
                        }
                    screenStates.SaveableStateProvider(screenKey) {
                        when (route) {
                            "home" ->
                                HomeScreen(
                                    state,
                                    import,
                                    { getRecordsVisible = true },
                                    { route = "paste" },
                                    {
                                        run {
                                            controller.loadDemo()
                                            imported(
                                                controller.state.value.lastImportedConversationIds
                                            )
                                        }
                                    },
                                    { openRecord(it) },
                                    { imported(state.conversations.map { it.id }) },
                                    { route = "tasks" },
                                    { run { controller.refreshQuota() } },
                                )
                            "choose" ->
                                ConversationPickerScreen(
                                    state.conversations.filter { it.id in batchIds }
                                ) {
                                    prepare(it)
                                }
                            "reorganization_choose" -> {
                                val original = state.archives.firstOrNull { it.id == reorganizationSourceId }
                                val related = state.conversations.filter { it.id in original?.conversationIds.orEmpty() }
                                if (related.isEmpty()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        NoticeCard("本机没有这份档案关联的聊天。请重新导入原始记录后，回到原档案重新整理。")
                                        TextButton(onClick = {
                                            reorganizationSourceId?.let { openRecord(it) }
                                        }) { Text("返回原档案") }
                                    }
                                } else {
                                    ConversationPickerScreen(
                                        related,
                                        title = "重新整理哪段聊天？",
                                        detail = "这次只整理所选的一段聊天，原档案中的个人修改和设置会保留。",
                                    ) {
                                        conversationId = it
                                        route = "reorganization_selection"
                                    }
                                }
                            }
                            "reorganization_selection" -> {
                                val original = state.archives.firstOrNull { it.id == reorganizationSourceId }
                                val conversation = state.conversations.firstOrNull {
                                    it.id == conversationId && it.id in original?.conversationIds.orEmpty()
                                }
                                if (original == null || conversation == null) {
                                    NoticeCard("原档案或关联聊天已不在本机，请返回后重新导入原始记录。", Modifier.padding(20.dp))
                                } else {
                                    SelectionScreen(
                                        conversation,
                                        emptyList(),
                                        null,
                                        busy,
                                        scopeOnly = true,
                                        selectionContextKey = "reorganization:${original.id}",
                                    ) { _, branchId, selectedIds, _, _, _ ->
                                        val orderedIds = conversation.branches.first { it.id == branchId }
                                            .messages.filter { it.id in selectedIds }.map { it.id }
                                        requestReorganization(original.id, listOf(
                                            ArchiveSourceScope(conversation.id, branchId, orderedIds)
                                        ))
                                    }
                                }
                            }
                            "prepare" -> {
                                val conversation =
                                    state.conversations.firstOrNull { it.id == conversationId }
                                if (conversation == null)
                                    NoticeCard("聊天正在载入，或已被清除。可以返回首页重新导入。", Modifier.padding(20.dp))
                                else
                                    PreparationScreen(
                                        conversation,
                                        state,
                                        busy,
                                        preparationError,
                                        onStart = { begin(conversation, it, true) },
                                        onOriginal = { begin(conversation, it, false) },
                                        onService = { service("prepare") },
                                        onQuotaRefresh = { run { controller.refreshQuota() } },
                                        estimateMessages = controller::estimateForCurrentService,
                                        onAdvanced = {
                                            advancedConversation(conversation.id, branch = it)
                                        },
                                        onSavedTask = { openRecord(it) },
                                    )
                            }
                            "progress" ->
                                OrganizationProgressScreen(
                                    currentTask,
                                    submitting,
                                    progressError,
                                    controller,
                                    run,
                                    onPrepare = { prepareCurrentRecord() },
                                    onService = { service("progress") },
                                    onRebuild = {
                                        run {
                                            currentTask?.let { controller.cancelTask(it.id) }
                                            val originalId = controller.state.value.archives
                                                .firstOrNull { it.id == archiveId }?.rebuiltFromArchiveId
                                            if (originalId != null) {
                                                archiveId = originalId
                                                route = "result"
                                                requestReorganization(originalId)
                                            } else prepareCurrentRecord()
                                        }
                                    },
                                    onSavedContent = { route = "result" },
                                    onOriginalArchive = state.archives.firstOrNull { it.id == archiveId }
                                        ?.rebuiltFromArchiveId
                                        ?.takeIf { id -> state.archives.any { it.id == id } }
                                        ?.let { id -> { openRecord(id) } },
                                )
                            "result" -> {
                                val archive = state.archives.firstOrNull { it.id == archiveId }
                                if (archive == null)
                                    NoticeCard("这份内容正在载入，或已被删除。", Modifier.padding(20.dp))
                                else
                                    ContinuationResultScreen(
                                        archive,
                                        currentTask,
                                        conversations = state.conversations,
                                        onSaveContinuity = { settings ->
                                            controller.saveContinuitySettings(archive.id, settings)
                                        },
                                        onCopyAndOpen = { text ->
                                            run {
                                                require(
                                                    text.toByteArray(Charsets.UTF_8).size <=
                                                        60 * 1024
                                                ) {
                                                    "内容较长，请保存为续聊文件。"
                                                }
                                                (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                        as ClipboardManager)
                                                    .setPrimaryClip(
                                                        ClipData.newPlainText("续聊内容", text)
                                                    )
                                                openDeepSeek(context)
                                            }
                                        },
                                        onSave = { text ->
                                            run {
                                                stageDocument(
                                                    text,
                                                    "续聊文件已保存。请在 DeepSeek 新对话中添加这个文件。",
                                                )
                                                saveContinuation.launch(
                                                    safeFileName(archive.title) + ".txt"
                                                )
                                            }
                                        },
                                        onOpenDeepSeek = { run { openDeepSeek(context) } },
                                        onDetails = { route = "archive" },
                                        onPrepare = { prepareCurrentRecord() },
                                        archives = state.archives,
                                        tasks = state.tasks,
                                        busy = busy,
                                        onReorganize = { requestReorganization(archive.id) },
                                        onOpenRecord = { openRecord(it) },
                                        onPoliticalExport = { politicalArchiveId = archive.id },
                                    )
                            }
                            "paste" ->
                                ManualPasteScreen(manualPasteDraft, busy, run) { title, messages ->
                                    run {
                                        val bytes =
                                            withContext(Dispatchers.Default) {
                                                manualPasteDocument(title, messages)
                                            }
                                        val ids = controller.importFile(bytes, "手动粘贴记录.json")
                                        manualPasteDraft.reset()
                                        imported(ids)
                                    }
                                }
                            "archives" -> ArchivesScreen(state.archives, { openRecord(it) }, import)
                            "selection" -> {
                                val conversation =
                                    state.conversations.firstOrNull { it.id == conversationId }
                                if (conversation == null)
                                    NoticeCard("会话尚未载入，请返回选择。", Modifier.padding(20.dp))
                                else
                                    SelectionScreen(
                                        conversation,
                                        state.archives,
                                        selectionTargetId,
                                        busy,
                                        initialBranchId = advancedBranchId,
                                    ) { title, branch, ids, background, target, ai ->
                                        if (!submitting) {
                                            submitting = true
                                            run {
                                                try {
                                                    val id =
                                                        if (target == null)
                                                            controller.createArchive(
                                                                title,
                                                                conversation,
                                                                branch,
                                                                ids,
                                                                background,
                                                                includeRawEntries = !ai,
                                                            )
                                                        else {
                                                            controller.addConversation(
                                                                target,
                                                                conversation,
                                                                branch,
                                                                ids,
                                                            )
                                                            target
                                                        }
                                                    archiveId = id
                                                    route = "archive"
                                                    if (ai)
                                                        aiRequest =
                                                            AiRequest(
                                                                id,
                                                                conversation.id,
                                                                branch,
                                                                ids,
                                                                conversation.branches
                                                                    .first { it.id == branch }
                                                                    .messages
                                                                    .filter { it.id in ids },
                                                            )
                                                } finally {
                                                    submitting = false
                                                }
                                            }
                                        }
                                    }
                            }
                            "archive" -> {
                                val archive = state.archives.firstOrNull { it.id == archiveId }
                                if (archive == null)
                                    NoticeCard("这份内容尚未载入或已被删除。", Modifier.padding(20.dp))
                                else
                                    ArchiveScreen(
                                        archive,
                                        state,
                                        controller,
                                        run,
                                        { exportArchive = it },
                                        { advancedConversation(it, archive.id) },
                                        { route = "archives" },
                                        onReorganize = { requestReorganization(archive.id) },
                                        onOpenRecord = { openRecord(it) },
                                        onPoliticalExport = { politicalArchiveId = archive.id },
                                    )
                            }
                            "settings",
                            "service" ->
                                SettingsScreen(
                                    state,
                                    busy,
                                    controller,
                                    run,
                                    onBackup = {
                                        run {
                                            val file = controller.backupFile()
                                            pendingDocumentPath = file.absolutePath
                                            pendingDocumentMessage = "备份已保存。密钥与邀请凭证未导出。"
                                            try {
                                                saveBackup.launch("续聊助手备份-${System.currentTimeMillis()}.json")
                                            } catch (error: Throwable) {
                                                pendingDocumentPath = null
                                                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                                                throw error
                                            }
                                        }
                                    },
                                    onRestore = { restoreConfirmation = true },
                                    onClearLocalData = {
                                        manualPasteDraft.reset { controller.clearLocalData() }
                                    },
                                    onAbout = { route = "about" },
                                    onDeveloper = {
                                        developerReturn = route
                                        route = "developer"
                                    },
                                    onFeedback = { route = "feedback" },
                                    setupOnly = route == "service",
                                    onReady = {
                                        preparationError = null
                                        route = serviceReturn
                                        reorganizationPlan?.let { pending ->
                                            requestReorganization(pending.archiveId,
                                                pending.sourceScopes.takeIf { pending.rangeOrigin == ReorganizationRangeOrigin.USER_SELECTION })
                                        }
                                    },
                                )
                            "developer" -> DeveloperScreen(state, controller, run)
                            "feedback" -> FeedbackScreen(state, controller, run)
                            "tasks" -> TasksScreen(state.tasks, controller, run) { openRecord(it) }
                            "about" ->
                                AboutScreen(
                                    updateState = updateState,
                                    installBlockedReason = installBlockedReason,
                                    installPermissionRequired = installPermissionRequired,
                                    onCheckUpdates = {
                                        if (!installPreparing) updates.checkForUpdates()
                                    },
                                    onDownloadUpdate = {
                                        if (!installPreparing) updates.downloadUpdate()
                                    },
                                    onCancelUpdate = updates::cancelDownload,
                                    onInstallUpdate = ::requestUpdateInstallation,
                                )
                        }
                    }
                }
            }
        }
        if (getRecordsVisible)
            GetRecordsDialog(
                onDismiss = { getRecordsVisible = false },
                onPaste = {
                    getRecordsVisible = false
                    route = "paste"
                },
                onImportFile = {
                    getRecordsVisible = false
                    import()
                },
                onOpenOfficialWebsite = {
                    run {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.deepseek.com"))
                        )
                    }
                },
            )
        politicalArchiveId?.let { id ->
            val archive = state.archives.firstOrNull { it.id == id }
            if (archive != null) PoliticalExportDialog(
                archive = archive,
                conversations = state.conversations,
                controller = controller,
                onDismiss = { politicalArchiveId = null },
                onCopy = { text -> run {
                    require(text.toByteArray(Charsets.UTF_8).size <= 60 * 1024) { "内容较长，请保存为温和版文件。" }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("续聊温和版", text))
                    snackbars.showSnackbar("温和版已复制，原档案未改动。")
                } },
                onSave = { text -> run {
                    val checkedArchive = state.archives.firstOrNull { it.id == politicalState.task?.archiveId }
                    stageDocument(text, "温和版已保存，原档案未改动。")
                    saveContinuation.launch(safeFileName(checkedArchive?.title ?: archive.title) + "-温和版.txt")
                } },
            ) else LaunchedEffect(id) { politicalArchiveId = null }
        }
        exportArchive?.let { snapshot ->
            val archive = state.archives.firstOrNull { it.id == snapshot.id } ?: return@let
            ExportDialog(
                archive,
                state.conversations,
                { exportArchive = null },
                onSaveContinuity = { settings ->
                    controller.saveContinuitySettings(archive.id, settings)
                },
                onCopy = { text ->
                    run {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("续聊档案", text))
                        snackbars.showSnackbar("Markdown 已复制。")
                    }
                },
                onSave = { text ->
                    run {
                        stageDocument(text, "Markdown 已保存。")
                        saveMarkdown.launch(safeFileName(archive.title) + ".md")
                    }
                },
                onShare = { text ->
                    run {
                        val file =
                            withContext(Dispatchers.IO) {
                                File(context.cacheDir, "share")
                                    .apply { mkdirs() }
                                    .let { File(it, safeFileName(archive.title) + ".md") }
                                    .apply { writeText(text) }
                            }
                        val uri =
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.files",
                                file,
                            )
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, archive.title)
                                clipData = ClipData.newRawUri("续聊档案", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(Intent.createChooser(intent, "分享续聊档案"))
                    }
                },
            )
        }
        reorganizationPlan?.takeIf {
            route in setOf("result", "archive") && archiveId == it.archiveId
        }?.let { plan ->
            ReorganizationDialog(
                plan = plan,
                state = state,
                busy = busy,
                error = reorganizationError,
                onDismiss = { reorganizationPlan = null; reorganizationError = null },
                onConfirm = {
                    if (!submitting && !reorganizationLoading) {
                        submitting = true
                        reorganizationError = null
                        run {
                            try {
                                val started = controller.reorganizeArchive(plan)
                                reorganizationPlan = null
                                progressError = null
                                openRecord(started.targetArchiveId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                reorganizationError = error.message ?: "这次重新整理未能开始，请检查后重试。"
                            } finally {
                                submitting = false
                            }
                        }
                    }
                },
                onSelectRange = {
                    reorganizationSourceId = plan.archiveId
                    reorganizationPlan = null
                    reorganizationError = null
                    val original = controller.state.value.archives.firstOrNull { it.id == plan.archiveId }
                    val related = controller.state.value.conversations.filter { it.id in original?.conversationIds.orEmpty() }
                    if (related.size == 1) {
                        conversationId = related.first().id
                        route = "reorganization_selection"
                    } else route = "reorganization_choose"
                },
                onSettings = {
                    archiveId = plan.archiveId
                    service(reorganizationReturnRoute)
                },
                onQuotaRefresh = { run { controller.refreshQuota() } },
                onRefresh = {
                    requestReorganization(plan.archiveId,
                        plan.sourceScopes.takeIf { plan.rangeOrigin == ReorganizationRangeOrigin.USER_SELECTION })
                },
            )
        }
        aiRequest?.let { request ->
            OrganizationDialog(
                request.messages,
                state,
                controller,
                { aiRequest = null },
                onQuotaRefresh = { run { controller.refreshQuota() } },
                onSettings = {
                    aiRequest = null
                    service("archive")
                },
            ) {
                if (!submitting) {
                    submitting = true
                    run {
                        try {
                            val blocker = controller.checkOrganizationAvailability()
                            require(blocker == null) { blocker.orEmpty() }
                            controller.organize(
                                request.archiveId,
                                request.conversationId,
                                request.branchId,
                                request.selectedIds,
                            )
                            aiRequest = null
                            archiveId = request.archiveId
                            progressError = null
                            route = "progress"
                        } finally {
                            submitting = false
                        }
                    }
                }
            }
        }
        if (importingSharedFile)
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在导入聊天") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(incomingImport?.fileName.orEmpty(), maxLines = 3)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {},
            )
        incomingImport
            ?.takeIf { it.status == IncomingImportStatus.FAILED && !existingModal }
            ?.let { pending ->
                AlertDialog(
                    onDismissRequest = { onIncomingImportConsumed(pending.id) },
                    title = { Text("这次没有导入") },
                    text = { Text(pending.error ?: "请重新分享聊天 ZIP，或在下载目录选择文件。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onIncomingImportConsumed(pending.id)
                                import()
                            }
                        ) {
                            Text("重新选择文件")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { onIncomingImportConsumed(pending.id) }) {
                            Text("关闭")
                        }
                    },
                )
            }
        if (restoreConfirmation)
            AlertDialog(
                onDismissRequest = { restoreConfirmation = false },
                title = { Text("从备份恢复") },
                text = { Text("恢复会用备份替换本机的对话、续聊记录与历史版本。请先导出需要保留的当前数据。服务密钥需重新填写。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            restoreConfirmation = false
                            restoreLauncher.launch(
                                arrayOf("application/json", "text/json", "application/octet-stream")
                            )
                        }
                    ) {
                        Text("选择备份文件")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { restoreConfirmation = false }) { Text("取消") }
                },
            )
    }
}

@Composable
private fun IncomingImportBar(
    fileName: String,
    blocked: Boolean,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("收到聊天文件", fontWeight = FontWeight.SemiBold)
            Text(
                fileName,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (blocked) Text("当前操作完成后可继续导入。", style = MaterialTheme.typography.bodySmall)
            ActionRow {
                Button(onClick = onImport, enabled = !blocked) { Text("导入") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

private fun openDeepSeek(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("dpsk://chat/new")).setPackage("com.deepseek.chat")
        )
        return
    } catch (_: android.content.ActivityNotFoundException) {
        // Older versions may expose only the ordinary app launcher.
    }
    val launcher = context.packageManager.getLaunchIntentForPackage("com.deepseek.chat")
    if (launcher != null) {
        try {
            context.startActivity(launcher)
            return
        } catch (_: android.content.ActivityNotFoundException) {}
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.deepseek.com")))
}

@Composable
private fun ExportDialog(
    archive: Archive,
    conversations: List<ImportedConversation>,
    onDismiss: () -> Unit,
    onSaveContinuity: suspend (ContinuitySettings) -> Unit,
    onCopy: (String) -> Unit,
    onSave: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var result by remember(archive, conversations) { mutableStateOf<Result<ExportResult>?>(null) }
    var large by remember(archive, conversations) { mutableStateOf(false) }
    var previewLimit by rememberSaveable(archive.id) { mutableIntStateOf(8000) }
    LaunchedEffect(archive, conversations) {
        val prepared = withContext(Dispatchers.Default) {
            val export = runCatching { ArchiveLogic.continuationExport(archive, conversations) }
            export to ((export.getOrNull()?.text?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 60 * 1024)
        }
        large = prepared.second
        result = prepared.first
    }
    AppDialog(
        "带到下一段对话",
        onDismiss,
        actions = {
            ActionRow {
                TextButton(onClick = onDismiss) { Text("关闭") }
                result?.getOrNull()?.let { export ->
                    if (large) {
                        Button(onClick = { onSave(export.text) }) { Text("保存文件") }
                    } else {
                        Button(onClick = { onCopy(export.text) }) { Text("复制 Markdown") }
                        OutlinedButton(onClick = { onSave(export.text) }) { Text("保存文件") }
                        TextButton(onClick = { onShare(export.text) }) { Text("系统分享") }
                    }
                }
            }
        },
    ) {
        ContinuitySetupCard(archive, conversations, onSaveContinuity)
        if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.fold(
            onSuccess = { export ->
                Text(
                    "${export.text.length} 字符",
                    style = MaterialTheme.typography.bodySmall,
                    color = Blue,
                )
                if (export.pinnedOverflow)
                    Text(
                        "部分固定条目未被带入，请核对预览。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                Text(
                    if (large) "内容较长，请保存为文件后，在 DeepSeek 新对话中添加文件。"
                    else "复制后，在 DeepSeek 新对话里粘贴，或添加保存的文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                SelectionContainer { Text(export.text.take(previewLimit), style = MaterialTheme.typography.bodySmall) }
                if (export.text.length > previewLimit) {
                    Text(
                        if (large) "当前预览前 $previewLimit 字符，保存文件会包含全部 ${export.text.length} 字符。"
                        else "当前预览前 $previewLimit 字符，复制和保存会包含全部 ${export.text.length} 字符。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { previewLimit = (previewLimit + 8000).coerceAtMost(export.text.length) }) {
                        Text("继续查看")
                    }
                }
            },
            onFailure = { Text(it.message ?: "暂时无法导出", color = MaterialTheme.colorScheme.error) },
        )
    }
}

@Composable
private fun OrganizationDialog(
    messages: List<ChatMessage>,
    state: AppState,
    controller: AppController,
    onDismiss: () -> Unit,
    onQuotaRefresh: () -> Unit,
    onSettings: () -> Unit,
    onConfirm: () -> Unit,
) {
    val settings = state.settings
    var estimate by remember { mutableStateOf<Result<OrganizationEstimate>?>(null) }
    LaunchedEffect(messages, settings, state.quota) {
        estimate = null
        estimate =
            withContext(Dispatchers.Default) {
                runCatching { controller.estimateForCurrentService(messages) }
            }
    }
    val configured =
        if (settings.mode == "byok") settings.apiKey.isNotBlank() && settings.baseUrl.isNotBlank()
        else settings.companionUrl.isNotBlank()
    val quotaReady =
        settings.mode != "companion" ||
            (state.quota != null && !state.quotaLoading && state.quotaError == null)
    AppDialog(
        "确认 AI 整理",
        onDismiss,
        actions = {
            ActionRow {
                TextButton(onClick = onDismiss) { Text("返回档案") }
                if (configured && estimate?.isSuccess == true && quotaReady)
                    Button(onClick = onConfirm) { Text("确认并开始") }
                else Button(onClick = onSettings) { Text("前往服务设置") }
            }
        },
    ) {
        Text(
            if (settings.mode == "byok") "自有 API Key · ${settings.model}" else "免费智能整理服务",
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (settings.mode == "byok") settings.baseUrl
            else settings.companionUrl.ifBlank { "尚未配置服务地址" },
            style = MaterialTheme.typography.bodySmall,
        )
        Text("将发送所选 ${messages.size} 条消息供整理。请确认这些内容可以提供给上述服务。")
        estimate?.fold(
            onSuccess = { value ->
                Text(
                    "预计 ${value.chunks} 个分块 · 输入约 ${value.inputTokens} tokens · 输出预算 ${value.outputTokens} tokens",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.mode == "companion")
                    Text("免费使用，预计需要 ${value.chunks} 次模型请求，服务端会检查个人与全站剩余次数。")
                else if (value.estimatedCost != null)
                    Text("按填写单价保守估算约 ${"%.4f".format(value.estimatedCost)}（与单价同币种）。实际费用以服务商账单为准。")
                else Text("尚未填写单价，无法估算费用。该服务会按实际使用计费，可在设置中补充价格。")
            },
            onFailure = { Text(it.message ?: "无法估算请求", color = MaterialTheme.colorScheme.error) },
        )
        if (settings.mode == "companion") FreeQuotaCard(state, onQuotaRefresh)
        if (!configured) Text("请先填写对应的服务地址与 API Key。", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onSettings) { Text("查看或调整服务设置") }
    }
}

private fun safeFileName(value: String) =
    value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "续聊档案" }

private fun readDocument(
    context: Context,
    uri: Uri,
    maxBytes: Int = 32 * 1024 * 1024,
): Pair<String, ByteArray> {
    var name = "import.json"
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) ?: name }
    val bytes =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(32 * 1024)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= maxBytes.toLong()) {
                    "文件超过 ${maxBytes / 1024 / 1024} MB，无法读取。"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取所选文件")
    return name to bytes
}
