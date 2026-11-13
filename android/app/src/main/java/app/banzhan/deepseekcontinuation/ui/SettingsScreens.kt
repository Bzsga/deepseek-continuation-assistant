@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.BuildConfig
import app.banzhan.deepseekcontinuation.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
internal fun SettingsScreen(
    state: AppState,
    busy: Boolean,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onClearLocalData: suspend () -> Unit,
    onAbout: () -> Unit,
    onDeveloper: () -> Unit,
    onFeedback: () -> Unit,
    setupOnly: Boolean = false,
    onReady: () -> Unit = {},
) {
    val settings = state.settings
    var mode by remember(settings.mode) { mutableStateOf(settings.mode) }
    var key by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var revealKey by remember { mutableStateOf(false) }
    var testPending by remember { mutableStateOf(false) }
    val testing = testPending || state.connectionTestLoading
    var testResult by remember { mutableStateOf<String?>(null) }
    var clearConfirmation by remember { mutableStateOf(false) }
    var serviceProblem by remember { mutableStateOf<String?>(null) }
    var serviceExpanded by rememberSaveable { mutableStateOf(setupOnly) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!setupOnly) item {
            ActionRow {
                Text(
                    "当前版本 ${BuildConfig.VERSION_NAME}",
                    Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onAbout) { Text("关于与开源致谢") }
            }
        }
        if (!setupOnly) item {
            Card(onClick = { serviceExpanded = !serviceExpanded },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("整理服务", fontWeight = FontWeight.SemiBold)
                        Text(if (settings.mode == "companion") "免费智能整理" else "自己的 API · ${settings.model}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (serviceExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (serviceExpanded) "收起服务设置" else "展开服务设置")
                }
            }
        }
        if (serviceExpanded || setupOnly) {
        if (setupOnly) item { SectionTitle("整理服务", "免费服务可以直接使用，也可以选择自己的 API。") }
        item {
            ActionRow {
                FilterChip(
                    mode == "companion",
                    {
                        mode = "companion"
                        serviceProblem = null
                    },
                    label = { Text("免费智能整理") },
                )
                FilterChip(
                    mode == "byok",
                    {
                        mode = "byok"
                        serviceProblem = null
                    },
                    label = { Text("自己的 API") },
                )
            }
        }
        if (mode == "companion") {
            item {
                if (settings.mode == "companion")
                    FreeQuotaCard(state, onRefresh = { run { controller.refreshQuota() } })
                else NoticeCard("保存设置后即可使用免费服务，并查看今日剩余次数。")
            }
            item {
                Text(
                    "开始整理时，会将你选中的聊天内容发送到我们的整理服务。免费次数按实际模型请求计算，长聊天可能需要多次请求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Text(
                    "支持兼容 OpenAI Chat Completions 的服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    baseUrl,
                    {
                        baseUrl = it
                        key = ""
                        testResult = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("API Base URL（HTTPS）") },
                    singleLine = true,
                    enabled = !testing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            item {
                OutlinedTextField(
                    model,
                    {
                        model = it
                        testResult = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("模型名称") },
                    singleLine = true,
                    enabled = !testing,
                )
            }
            item {
                OutlinedTextField(
                    key,
                    {
                        key = it
                        testResult = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    enabled = !testing,
                    supportingText = {
                        Text(if (key.isNotBlank()) "密钥只保存在本机。" else "填写你在服务方申请的 API Key。")
                    },
                    visualTransformation =
                        if (revealKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                if (revealKey) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                if (revealKey) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                )
            }
            item {
                Text(
                    "由你选择的服务方按实际使用计费。测试连通会用当前填写的参数发出一条极短请求，也可能计费。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        if (!testing) {
                            val draft =
                                settings.copy(
                                    mode = "byok",
                                    baseUrl = baseUrl,
                                    model = model,
                                    apiKey = key,
                                )
                            testPending = true
                            testResult = null
                            run {
                                try {
                                    val result = controller.testApiConnection(draft)
                                    testResult =
                                        "连接成功 · ${result.model ?: draft.model}\n${result.reply}"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    testResult = error.message ?: "连接测试未完成，请检查网络和参数。"
                                } finally {
                                    testPending = false
                                }
                            }
                        }
                    },
                    enabled =
                        !busy &&
                            !testing &&
                            baseUrl.isNotBlank() &&
                            model.isNotBlank() &&
                            key.isNotBlank(),
                ) {
                    Text(if (testing) "正在测试…" else "测试连通（可能计费）")
                }
                Text(
                    "测试使用当前填写值，保存设置后才用于后续整理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (testing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            testResult?.let { item { NoticeCard(it) } }
        }
        serviceProblem?.let { item { NoticeCard(it) } }
        item {
            Button(
                onClick = {
                    run {
                        controller.saveSettings(
                            settings.copy(
                                mode = mode,
                                apiKey = key,
                                baseUrl = baseUrl,
                                model = model,
                            )
                        )
                        if (setupOnly) {
                            serviceProblem = controller.checkOrganizationAvailability()
                            if (serviceProblem == null) onReady()
                        } else serviceExpanded = false
                    }
                },
                enabled = !busy && !testing,
            ) {
                Text(if (setupOnly) "保存并返回准备页" else "保存设置")
            }
            if (setupOnly)
                Text(
                    "保存只检查服务可用性。返回后，点击「开始智能整理」才会发送聊天内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        }
        if (!setupOnly) {
            item {
                TextButton(onClick = onFeedback) {
                    Icon(Icons.Outlined.MailOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("意见反馈")
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("本地数据", "备份含对话和续聊记录。服务凭据与管理会话不进入备份。")
            }
            item {
                ActionRow {
                    OutlinedButton(onClick = onBackup, enabled = !busy) { Text("导出备份") }
                    OutlinedButton(onClick = onRestore, enabled = !busy) { Text("恢复备份") }
                }
            }
            item {
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Icon(if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    Text(if (advancedExpanded) "收起高级选项" else "高级选项")
                }
                if (advancedExpanded) ActionRow {
                    TextButton(onClick = onDeveloper) { Text("开发者选项") }
                    TextButton(onClick = { clearConfirmation = true }, enabled = !busy) {
                        Text("清除本地资料", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (clearConfirmation)
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("清除本地资料？") },
            text = { Text("将清除导入原件、会话、续聊记录、历史版本、任务、整理片段缓存和粘贴草稿。服务设置会保留。请先备份需要保存的资料。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            onClearLocalData()
                            clearConfirmation = false
                        }
                    }
                ) {
                    Text("确认清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } },
        )
}

@Composable
internal fun TasksScreen(
    tasks: List<OrganizationTask>,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
    onArchive: (String) -> Unit,
) {
    var resume by remember { mutableStateOf<OrganizationTask?>(null) }
    var cancel by remember { mutableStateOf<OrganizationTask?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("整理任务", "每个完成的分块都会保存。暂停后可从进度继续。") }
        if (tasks.isEmpty()) item { NoticeCard("还没有整理任务。在对话范围选择页点击「AI 整理」即可开始。") }
        items(tasks, key = { it.id }) { task ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(task.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        task.provider,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { if (task.total > 0) task.done.toFloat() / task.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${taskStatus(task.status)} · ${task.done} / ${task.total} 分块",
                        style = MaterialTheme.typography.labelLarge,
                        color = Blue,
                    )
                    if (task.message.isNotBlank())
                        Text(task.message, style = MaterialTheme.typography.bodySmall)
                    ActionRow {
                        if (task.status in setOf("queued", "running"))
                            TextButton(onClick = { run { controller.pauseTask(task.id) } }) {
                                Text("暂停")
                            }
                        if (task.status in setOf("paused", "cancelled", "failed", "uncertain"))
                            TextButton(
                                onClick = {
                                    if (task.uncertain) resume = task
                                    else run { controller.resumeTask(task.id, false) }
                                }
                            ) {
                                Text("继续")
                            }
                        if (task.status !in setOf("completed", "cancelled"))
                            TextButton(onClick = { cancel = task }) { Text("取消任务") }
                        TextButton(onClick = { onArchive(task.archiveId) }) { Text("查看档案") }
                    }
                }
            }
        }
    }
    resume?.let { task ->
        AlertDialog(
            onDismissRequest = { resume = null },
            title = { Text("确认继续整理") },
            text = { Text(task.requestUncertaintyText() + "\n\n${task.provider}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            controller.resumeTask(task.id, true)
                            resume = null
                        }
                    }
                ) {
                    Text(if (task.serviceMode == "companion") "确认消耗次数并继续" else "确认可能的额外费用并继续")
                }
            },
            dismissButton = { TextButton(onClick = { resume = null }) { Text("暂不继续") } },
        )
    }
    cancel?.let { task ->
        AlertDialog(
            onDismissRequest = { cancel = null },
            title = { Text("取消整理任务？") },
            text = { Text(task.cancelRequestText()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            controller.cancelTask(task.id)
                            cancel = null
                        }
                    }
                ) {
                    Text("取消任务")
                }
            },
            dismissButton = { TextButton(onClick = { cancel = null }) { Text("返回") } },
        )
    }
}

private fun taskStatus(value: String) =
    when (value) {
        "queued" -> "等待中"
        "running" -> "整理中"
        "paused" -> "已暂停"
        "completed" -> "已完成"
        "cancelled" -> "已取消"
        "uncertain" -> "结果待确认"
        else -> "需要处理"
    }

private data class LicenseItem(
    val name: String,
    val version: String,
    val author: String,
    val url: String,
    val license: String,
    val textAsset: String,
)

@Composable
internal fun AboutScreen(
    updateState: AppUpdateState = AppUpdateState(),
    installBlockedReason: String? = null,
    installPermissionRequired: Boolean = false,
    onCheckUpdates: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onCancelUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
) {
    val context = LocalContext.current
    var licenses by remember { mutableStateOf<List<LicenseItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<LicenseItem?>(null) }
    var licenseText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching {
                withContext(Dispatchers.IO) {
                    val json =
                        context.assets.open("licenses/index.json").bufferedReader().use {
                            it.readText()
                        }
                    Json.parseToJsonElement(json).jsonObject["components"]!!.jsonArray.map { value
                        ->
                        val row = value.jsonObject
                        fun field(name: String) = row[name]?.jsonPrimitive?.content.orEmpty()
                        LicenseItem(
                            field("name"),
                            field("version"),
                            field("author"),
                            field("url"),
                            field("license"),
                            field("textAsset"),
                        )
                    }
                }
            }
            .onSuccess { licenses = it }
            .onFailure { loadError = "开源清单暂时无法读取。${it.message.orEmpty()}" }
    }
    LaunchedEffect(selected) {
        licenseText = ""
        selected?.let { item ->
            licenseText =
                withContext(Dispatchers.IO) {
                    runCatching {
                            context.assets.open(item.textAsset).bufferedReader().use {
                                it.readText()
                            }
                        }
                        .getOrElse { "许可证正文读取失败。${it.message.orEmpty()}" }
                }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Whale(Modifier.width(150.dp).height(125.dp))
                    Text(
                        "Deepseek续聊助手",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "当前版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("作者：半盏丶时光")
                    Text("由GPT-6制作")
                    Text(
                        "独立制作的续聊档案工具。导出续聊文件后，可以在你选择的聊天应用中继续交流。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            AppUpdateCard(
                state = updateState,
                installBlockedReason = installBlockedReason,
                installPermissionRequired = installPermissionRequired,
                onCheck = onCheckUpdates,
                onDownload = onDownloadUpdate,
                onCancelDownload = onCancelUpdate,
                onInstall = onInstallUpdate,
            )
        }
        item { SectionTitle("开源致谢", "以下组件的许可证已随应用离线提供。") }
        loadError?.let { item { NoticeCard(it) } }
        items(licenses, key = { it.name + it.version }) { item ->
            Card(
                onClick = { selected = item },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("${item.name} ${item.version}", fontWeight = FontWeight.SemiBold)
                    Text(item.author, style = MaterialTheme.typography.bodySmall)
                    Text(item.license, style = MaterialTheme.typography.labelMedium, color = Blue)
                }
            }
        }
    }
    selected?.let { item ->
        AppDialog(
            item.name,
            { selected = null },
            actions = { TextButton(onClick = { selected = null }) { Text("关闭") } },
        ) {
            Text(item.url, style = MaterialTheme.typography.bodySmall, color = Blue)
            SelectionContainer {
                Text(licenseText.ifBlank { "读取许可证……" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
