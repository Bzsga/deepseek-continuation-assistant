@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.data.*

@Composable
internal fun DeveloperScreen(
    state: AppState,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
) {
    val prefs = state.developerPrefs
    var serverUrl by remember(prefs.serviceUrl) { mutableStateOf(prefs.serviceUrl) }
    var password by remember { mutableStateOf("") }
    var loginPending by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("overview") }
    var days by rememberSaveable { mutableIntStateOf(7) }
    val signedIn = prefs.sessionToken.isNotBlank()
    val hideManagementHeader =
        WindowInsets.isImeVisible && page in setOf("users", "server", "local", "feedback")
    LaunchedEffect(signedIn, prefs.serviceUrl, prefs.sessionToken, page, days) {
        if (signedIn) {
            when (page) {
                "overview" -> run { controller.refreshAdminStats(days) }
                "users" -> run {
                    controller.refreshAdminConfig()
                    controller.refreshAdminUsers(limit = 25, offset = 0)
                }
                "server" -> run { controller.refreshAdminConfig() }
            }
        }
    }
    if (!signedIn) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { SectionTitle("登录开发者管理", "使用服务器设置的开发者密码。登录后可查看统计和修改配置。") }
            item {
                OutlinedTextField(
                    serverUrl,
                    { serverUrl = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("管理服务器地址（HTTPS）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !loginPending,
                )
            }
            item {
                OutlinedTextField(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("开发者密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !loginPending,
                )
            }
            state.adminError?.let { item { NoticeCard(it) } }
            item {
                Button(
                    onClick = {
                        if (!loginPending) {
                            val suppliedPassword = password
                            password = ""
                            loginPending = true
                            run {
                                try {
                                    controller.loginDeveloper(serverUrl, suppliedPassword)
                                } finally {
                                    loginPending = false
                                }
                            }
                        }
                    },
                    enabled =
                        !loginPending &&
                            !state.adminLoading &&
                            password.isNotBlank() &&
                            serverUrl.isNotBlank(),
                ) {
                    Text(if (loginPending) "正在登录…" else "登录管理")
                }
            }
            item {
                Text(
                    "手机只保存短效管理会话，密码不会进入应用备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (!hideManagementHeader)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开发者管理", fontWeight = FontWeight.SemiBold)
                        if (prefs.expiresAt.isNotBlank())
                            Text(
                                "会话到期 · ${readableServerTime(prefs.expiresAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    TextButton(onClick = { run { controller.logoutDeveloper() } }) { Text("退出管理") }
                }
                ActionRow {
                    listOf(
                            "overview" to "统计概览",
                            "users" to "用户与注册",
                            "feedback" to "意见审批",
                            "server" to "服务器",
                            "local" to "本机参数",
                        )
                        .forEach { (id, title) ->
                            FilterChip(page == id, { page = id }, label = { Text(title) })
                        }
                }
            }
        Box(Modifier.weight(1f)) {
            when (page) {
                "overview" ->
                    AdminOverview(
                        state,
                        days,
                        { days = it },
                        { run { controller.refreshAdminStats(days) } },
                    )
                "users" ->
                    AdminUsers(
                        state,
                        controller,
                        run,
                        onPage = { offset ->
                            run { controller.refreshAdminUsers(limit = 25, offset = offset) }
                        },
                    )
                "server" -> AdminServerSettings(state, controller, run)
                "local" -> LocalApiSettings(state.settings, state.busy, controller, run)
                "feedback" -> AdminFeedbackScreen(state, controller, run)
            }
        }
    }
}

@Composable
private fun AdminOverview(
    state: AppState,
    days: Int,
    onDays: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val stats = state.adminStats
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ActionRow {
                listOf(7, 30).forEach { count ->
                    FilterChip(days == count, { onDays(count) }, label = { Text("最近 $count 天") })
                }
                TextButton(onClick = onRefresh, enabled = !state.adminStatsLoading) { Text("刷新统计") }
            }
        }
        if (state.adminStatsLoading)
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取服务器统计。", style = MaterialTheme.typography.bodySmall)
            }
        state.adminStatsError?.let {
            item { NoticeCard("未能获取统计。$it" + if (stats != null) "\n下面显示上次成功读取的数据。" else "") }
        }
        if (stats == null) {
            if (!state.adminStatsLoading && state.adminStatsError == null)
                item { NoticeCard("尚未加载统计数据。") }
        } else {
            item {
                Text(
                    "${stats.startDate} — ${stats.endDate} · ${stats.timezone}\n更新于 ${readableServerTime(stats.generatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AdminSection("今日全站请求") {
                    MetricRow("已用", stats.today.dailyUsed.toString())
                    MetricRow("排队预留", stats.today.dailyReserved.toString())
                    MetricRow(
                        "剩余 / 上限",
                        "${stats.today.dailyRemaining} / ${stats.today.dailyLimit}",
                    )
                    Text(
                        "重置时间 · ${readableServerTime(stats.today.resetAt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                AdminSection("用户与请求") {
                    MetricRow("用户总数", stats.totals.users.toString())
                    MetricRow("期间新增用户", stats.totals.newUsers.toString())
                    MetricRow("期间活跃用户", stats.totals.activeUsers.toString())
                    MetricRow("期间模型请求", stats.totals.requests.toString())
                }
            }
            item {
                AdminSection("任务与用量") {
                    MetricRow("完成 / 失败", "${stats.totals.completed} / ${stats.totals.failed}")
                    MetricRow(
                        "结果不确定 / 已取消",
                        "${stats.totals.uncertain} / ${stats.totals.cancelled}",
                    )
                    MetricRow("排队 / 处理中", "${stats.totals.queued} / ${stats.totals.running}")
                    Text("Token 用量仅统计服务方已报告的部分。", style = MaterialTheme.typography.bodySmall)
                    MetricRow("已报告输入 tokens", stats.totals.inputTokens.toString())
                    MetricRow("已报告输出 tokens", stats.totals.outputTokens.toString())
                    MetricRow("用量未知的请求", stats.totals.usageUnknownRequests?.toString() ?: "未提供")
                }
            }
            item { SectionTitle("按日查看") }
            items(stats.daily, key = { it.date }) { day ->
                AdminSection(day.date) {
                    MetricRow("新增 / 活跃用户", "${day.newUsers} / ${day.activeUsers}")
                    MetricRow("模型请求", day.requests.toString())
                    MetricRow(
                        "完成 / 失败 / 不确定",
                        "${day.completed} / ${day.failed} / ${day.uncertain}",
                    )
                    MetricRow("取消", day.cancelled.toString())
                    MetricRow("已报告输入 / 输出", "${day.inputTokens} / ${day.outputTokens}")
                    MetricRow("用量未知的请求", day.usageUnknownRequests?.toString() ?: "未提供")
                }
            }
        }
    }
}

@Composable
private fun AdminUsers(
    state: AppState,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
    onPage: (Int) -> Unit,
) {
    val page = state.adminUsers
    var invitationsExpanded by rememberSaveable { mutableStateOf(false) }
    var invitationCount by rememberSaveable { mutableIntStateOf(10) }
    LaunchedEffect(invitationsExpanded) {
        if (invitationsExpanded) run { controller.refreshAdminRegistrationInvites() }
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            RegistrationSettingsCard(state.adminConfig, state.adminLoading) { updated ->
                run {
                    controller.updateAdminConfig(updated)
                    controller.refreshAdminUsers(limit = page?.limit ?: 25, offset = page?.offset ?: 0)
                }
            }
        }
        state.adminError?.let { item { NoticeCard(it) } }
        item {
            RegistrationInvitationControls(
                expanded = invitationsExpanded,
                loading = state.adminRegistrationLoading,
                onToggle = { invitationsExpanded = !invitationsExpanded; invitationCount = 10 },
                onRefresh = { run { controller.refreshAdminRegistrationInvites() } },
                onCreate = { count, days -> run { controller.createAdminRegistrationInvites(count, days) } },
            )
        }
        if (invitationsExpanded) {
            state.adminRegistrationError?.let { item { NoticeCard(it) } }
            val invitations = state.adminRegistrationInvites?.filter { it.usedAt == null }.orEmpty()
            if (state.adminRegistrationInvites != null && invitations.isEmpty())
                item { Text("还没有未使用的邀请码。", style = MaterialTheme.typography.bodySmall) }
            items(invitations.take(invitationCount), key = { "invite:${it.id}" }) { invitation ->
                RegistrationInvitationCard(invitation)
            }
            if (invitations.size > invitationCount) item {
                TextButton(onClick = { invitationCount += 10 }) { Text("显示更多邀请码") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("注册用户", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { run {
                        controller.refreshAdminConfig()
                        controller.refreshAdminUsers(limit = page?.limit ?: 25, offset = page?.offset ?: 0)
                    } },
                    enabled = !state.adminUsersLoading,
                ) {
                    Text("刷新")
                }
            }
        }
        if (state.adminUsersLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.adminUsersError?.let {
            item { NoticeCard("未能获取用户列表。$it" + if (page != null) "\n下面显示上次成功读取的列表。" else "") }
        }
        if (page == null) {
            if (!state.adminUsersLoading && state.adminUsersError == null)
                item { NoticeCard("尚未加载用户列表。") }
        } else {
            if (page.users.isEmpty()) item { NoticeCard("这一页没有用户记录。") }
            items(page.users.filter { !it.username.isNullOrBlank() }, key = { it.id }) { user ->
                RegisteredUserQuotaCard(user, state.adminConfig?.userDailyLimit, state.adminUsersLoading) { limit, remaining ->
                    run { controller.updateAdminUserQuota(user.id, limit, remaining, page.day.takeIf { remaining != null }) }
                }
            }
            item {
                val first = if (page.users.isEmpty()) 0 else page.offset + 1
                Text(
                    "显示 $first–${page.offset + page.users.size} / ${page.total}",
                    style = MaterialTheme.typography.bodySmall,
                )
                ActionRow {
                    OutlinedButton(
                        onClick = { onPage((page.offset - page.limit).coerceAtLeast(0)) },
                        enabled = !state.adminUsersLoading && page.offset > 0,
                    ) {
                        Text("上一页")
                    }
                    OutlinedButton(
                        onClick = { onPage(page.offset + page.limit) },
                        enabled =
                            !state.adminUsersLoading && page.offset + page.users.size < page.total,
                    ) {
                        Text("下一页")
                    }
                }
            }
        }
    }
}

@Composable
internal fun RegistrationSettingsCard(config: AdminConfig?, loading: Boolean, onSave: (AdminConfig) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var capacity by remember(config) { mutableStateOf(config?.publicRegistrationLimit?.toString().orEmpty()) }
    var defaultLimit by remember(config) { mutableStateOf(config?.userDailyLimit?.toString().orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    AdminSection("注册与默认次数") {
        if (config == null) {
            Text(if (loading) "正在读取设置…" else "尚未读取注册设置。", style = MaterialTheme.typography.bodySmall)
        } else {
            MetricRow("公开注册人数 / 上限", "${config.publicRegisteredCount} / ${config.publicRegistrationLimit}")
            Text("默认每人每日 ${config.userDailyLimit} 次 · 邀请注册不占公开名额", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("admin-registration-toggle")) {
                Text(if (expanded) "收起设置" else "调整名额与默认次数")
            }
            if (expanded) {
                NumberField("公开注册人数上限", capacity, !loading) { capacity = it }
                NumberField("默认每人每日免费次数", defaultLimit, !loading) { defaultLimit = it }
                Text("新老账号均适用，个人单独设置优先。", style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        try {
                            val cap = requireNotNull(capacity.toIntOrNull()?.takeIf { it in 0..1_000_000 }) { "公开人数上限应为0到1000000之间的整数。" }
                            val limit = requireNotNull(defaultLimit.toIntOrNull()?.takeIf { it in 0..1_000_000 }) { "默认次数应为0到1000000之间的整数。" }
                            problem = null
                            onSave(config.copy(publicRegistrationLimit = cap, userDailyLimit = limit))
                        } catch (error: IllegalArgumentException) { problem = error.message }
                    },
                    enabled = !loading,
                    modifier = Modifier.testTag("admin-registration-save"),
                ) { Text("保存注册设置") }
            }
        }
    }
}

@Composable
internal fun RegisteredUserQuotaCard(
    user: UserUsage,
    defaultLimit: Int?,
    loading: Boolean,
    onSave: (Int?, Int?) -> Unit,
) {
    var expanded by rememberSaveable(user.id) { mutableStateOf(false) }
    var followDefault by remember(user) { mutableStateOf(user.dailyLimitOverride == null) }
    var limit by remember(user) { mutableStateOf((user.dailyLimitOverride ?: user.dailyLimit).toString()) }
    var today by remember(user) { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AdminSection(user.username ?: user.id.take(12)) {
        MetricRow("今日剩余 / 每日上限", "${user.dailyRemaining} / ${user.dailyLimit}")
        Text(
            "${if (user.registrationKind == "invitation") "邀请码注册" else "公开注册"} · " +
                if (user.dailyLimitOverride == null) "跟随默认次数" else "个人单独设置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("admin-user-toggle:${user.id}")) {
            Text(if (expanded) "收起调整" else "调整免费次数")
        }
        if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(followDefault, { followDefault = it }, enabled = !loading,
                    modifier = Modifier.testTag("admin-user-follow-default:${user.id}"))
                Text("跟随默认每日次数" + (defaultLimit?.let { "（$it 次）" } ?: ""),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (!followDefault) NumberField("个人每日免费次数", limit, !loading) { limit = it }
            NumberField("仅今天的剩余次数（选填）", today, !loading) { today = it }
            Text("留空保留今日已用情况。已开始的请求与预留次数不会被取消。", style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    try {
                        val personal = if (followDefault) null else requireNotNull(limit.toIntOrNull()?.takeIf { it in 0..1_000_000 }) { "个人次数应为0到1000000之间的整数。" }
                        val remaining = if (today.isBlank()) null else requireNotNull(today.toIntOrNull()?.takeIf { it in 0..1_000_000 }) { "今日剩余应为0到1000000之间的整数。" }
                        problem = null
                        onSave(personal, remaining)
                    } catch (error: IllegalArgumentException) { problem = error.message }
                },
                enabled = !loading,
                modifier = Modifier.testTag("admin-user-save:${user.id}"),
            ) { Text("保存个人次数") }
            Text("今日已用 ${user.dailyUsed} 次 · 排队预留 ${user.dailyReserved} 次", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text("用户 ID · ${user.id}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun RegistrationInvitationControls(
    expanded: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: (Int, Int) -> Unit,
) {
    var count by rememberSaveable { mutableStateOf("1") }
    var days by rememberSaveable { mutableStateOf("7") }
    var problem by remember { mutableStateOf<String?>(null) }
    AdminSection("一次性注册邀请码") {
        TextButton(onClick = onToggle, modifier = Modifier.testTag("admin-invitations-toggle")) {
            Text(if (expanded) "收起邀请码" else "生成与查看未使用邀请码")
        }
        if (expanded) {
            Text("每个邀请码可注册一个账户，不占公开名额。", style = MaterialTheme.typography.bodySmall)
            NumberField("生成数量", count, !loading) { count = it }
            NumberField("有效天数", days, !loading) { days = it }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ActionRow {
                Button(onClick = {
                    try {
                        val amount = requireNotNull(count.toIntOrNull()?.takeIf { it in 1..100 }) { "每次可生成1到100个邀请码。" }
                        val duration = requireNotNull(days.toIntOrNull()?.takeIf { it in 1..365 }) { "有效天数应为1到365天。" }
                        problem = null
                        onCreate(amount, duration)
                    } catch (error: IllegalArgumentException) { problem = error.message }
                }, enabled = !loading, modifier = Modifier.testTag("admin-invitations-create")) { Text("生成邀请码") }
                TextButton(onClick = onRefresh, enabled = !loading) { Text("刷新列表") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun RegistrationInvitationCard(invitation: AdminRegistrationInvite) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(invitation.code) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(18.dp).testTag("admin-invitation:${invitation.id}"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!invitation.code.isNullOrBlank()) {
                SelectionContainer { Text(invitation.code, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(invitation.code))
                    copied = true
                }, modifier = Modifier.testTag("admin-invitation-copy:${invitation.id}")) { Text(if (copied) "已复制" else "复制邀请码") }
            } else Text("这条邀请码未提供完整内容，请刷新列表。", style = MaterialTheme.typography.bodySmall)
            Text("到期时间 · ${readableServerTime(invitation.expiresAt)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdminServerSettings(
    state: AppState,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("服务器配置", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { run { controller.refreshAdminConfig() } },
                    enabled = !state.adminLoading,
                ) {
                    Text("重新读取")
                }
            }
        }
        if (state.adminLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.adminError?.let { item { NoticeCard(it) } }
        val config = state.adminConfig
        if (config == null) {
            if (!state.adminLoading && state.adminError == null) item { NoticeCard("请先读取服务器配置。") }
        } else
            item {
                RemoteConfigEditor(
                    config,
                    canSave = !state.adminLoading && state.adminError == null,
                    onSave = { updated, upstreamKey ->
                        run { controller.updateAdminConfig(updated, upstreamKey) }
                    },
                )
            }
    }
}

@Composable
private fun RemoteConfigEditor(
    config: AdminConfig,
    canSave: Boolean,
    onSave: (AdminConfig, String?) -> Unit,
) {
    var publicEnabled by remember(config) { mutableStateOf(config.publicEnabled) }
    var apiEnabled by remember(config) { mutableStateOf(config.apiEnabled) }
    var mockMode by remember(config) { mutableStateOf(config.mockMode) }
    var dailyLimit by remember(config) { mutableStateOf(config.dailyLimit.toString()) }
    var userLimit by remember(config) { mutableStateOf(config.userDailyLimit.toString()) }
    var model by remember(config) { mutableStateOf(config.model) }
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var contextTokens by remember(config) { mutableStateOf(config.contextTokens.toString()) }
    var outputTokens by remember(config) { mutableStateOf(config.maxOutputTokens.toString()) }
    var timeout by remember(config) { mutableStateOf(config.providerTimeoutSeconds.toString()) }
    var upstreamKey by remember { mutableStateOf("") }
    var clearKey by remember(config) { mutableStateOf(false) }
    var fieldError by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "当前配置版本 · ${config.configVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingToggle("免费服务总开关", publicEnabled, canSave) { publicEnabled = it }
        NumberField("全站每日模型请求上限", dailyLimit, canSave) { dailyLimit = it }
        NumberField("默认每人每日免费次数", userLimit, canSave) { userLimit = it }
        Text("新老账号均适用，个人单独设置优先。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("上游模型")
        SettingToggle("启用上游 API", apiEnabled, canSave) { apiEnabled = it }
        SettingToggle("模拟模式", mockMode, canSave) { mockMode = it }
        OutlinedTextField(
            model,
            { model = it },
            Modifier.fillMaxWidth(),
            label = { Text("模型名称") },
            singleLine = true,
        )
        OutlinedTextField(
            baseUrl,
            { baseUrl = it },
            Modifier.fillMaxWidth(),
            label = { Text("上游服务地址（HTTPS）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Text(
            "上游 API Key · ${if (config.apiKeyConfigured) "已配置" else "未配置"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            upstreamKey,
            { upstreamKey = it },
            Modifier.fillMaxWidth(),
            label = { Text("更换上游 API Key（留空保留）") },
            singleLine = true,
            enabled = canSave && !clearKey,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(clearKey, { clearKey = it }, enabled = canSave)
            Text("清除服务器已保存的上游 Key", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        NumberField("上下文预算（tokens）", contextTokens, canSave) { contextTokens = it }
        NumberField("每次最大输出（tokens）", outputTokens, canSave) { outputTokens = it }
        OutlinedTextField(
            timeout,
            { timeout = it },
            Modifier.fillMaxWidth(),
            label = { Text("上游请求超时（秒）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(
            "上游费用配置 · ${if (config.costKnown) "已提供" else "未提供"}",
            style = MaterialTheme.typography.bodySmall,
        )
        fieldError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                try {
                    val total =
                        requireNotNull(dailyLimit.toIntOrNull()?.takeIf { it >= 0 }) {
                            "全站每日上限应为非负整数"
                        }
                    val personal =
                        requireNotNull(userLimit.toIntOrNull()?.takeIf { it >= 0 }) {
                            "每人每日上限应为非负整数"
                        }
                    val context =
                        requireNotNull(contextTokens.toIntOrNull()?.takeIf { it > 0 }) {
                            "上下文预算应为正整数"
                        }
                    val output =
                        requireNotNull(outputTokens.toIntOrNull()?.takeIf { it > 0 }) {
                            "输出预算应为正整数"
                        }
                    val seconds =
                        requireNotNull(
                            timeout.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                        ) {
                            "超时应为正数"
                        }
                    val updated =
                        config.copy(
                            publicEnabled = publicEnabled,
                            dailyLimit = total,
                            userDailyLimit = personal,
                            model = model.trim(),
                            baseUrl = baseUrl.trim(),
                            contextTokens = context,
                            maxOutputTokens = output,
                            providerTimeoutSeconds = seconds,
                            apiEnabled = apiEnabled,
                            mockMode = mockMode,
                        )
                    val suppliedKey =
                        if (clearKey) "" else upstreamKey.trim().takeIf { it.isNotEmpty() }
                    upstreamKey = ""
                    fieldError = null
                    onSave(updated, suppliedKey)
                } catch (error: IllegalArgumentException) {
                    fieldError = error.message
                }
            },
            enabled = canSave,
        ) {
            Text("保存服务器配置")
        }
        Text(
            "保存后会重新读取服务器状态，确认实际生效的配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalApiSettings(
    settings: ServiceSettings,
    busy: Boolean,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
) {
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var newKey by remember { mutableStateOf("") }
    var contextTokens by remember(settings) { mutableStateOf(settings.contextTokens.toString()) }
    var outputTokens by remember(settings) { mutableStateOf(settings.maxOutputTokens.toString()) }
    var inputPrice by
        remember(settings) { mutableStateOf(settings.inputPricePerMillion?.toString().orEmpty()) }
    var outputPrice by
        remember(settings) { mutableStateOf(settings.outputPricePerMillion?.toString().orEmpty()) }
    var companionUrl by remember(settings) { mutableStateOf(settings.companionUrl) }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionTitle("本机高级 API 参数", "用于自己的 API。免费服务的模型与预算由服务器配置决定。") }
        item {
            OutlinedTextField(
                baseUrl,
                {
                    baseUrl = it
                    newKey = ""
                },
                Modifier.fillMaxWidth(),
                label = { Text("API 服务地址（HTTPS）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        item {
            OutlinedTextField(
                model,
                { model = it },
                Modifier.fillMaxWidth(),
                label = { Text("模型名称") },
                singleLine = true,
            )
        }
        item {
            Text(
                "本机 API Key · ${if (settings.apiKey.isNotBlank()) "已配置" else "未配置"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedTextField(
                newKey,
                { newKey = it },
                Modifier.fillMaxWidth(),
                label = { Text("新的 API Key（留空保留）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("更换服务地址后，请重新填写对应 Key。") },
            )
        }
        item { NumberField("每次上下文预算（tokens）", contextTokens) { contextTokens = it } }
        item { NumberField("每次最大输出（tokens）", outputTokens) { outputTokens = it } }
        item { Text("估算单价按每百万 tokens 填写，两项使用同一币种。", style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedTextField(
                inputPrice,
                { inputPrice = it },
                Modifier.fillMaxWidth(),
                label = { Text("输入单价（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        item {
            OutlinedTextField(
                outputPrice,
                { outputPrice = it },
                Modifier.fillMaxWidth(),
                label = { Text("输出单价（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        item {
            HorizontalDivider()
            SectionTitle("本机使用的免费服务")
        }
        item {
            OutlinedTextField(
                companionUrl,
                { companionUrl = it },
                Modifier.fillMaxWidth(),
                label = { Text("整理服务地址（HTTPS）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        item {
            Button(
                onClick = {
                    run {
                        fun price(value: String): Double? =
                            if (value.isBlank()) null
                            else
                                requireNotNull(
                                    value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                                ) {
                                    "价格应为非负数或留空"
                                }
                        val changed =
                            baseUrl.trim().trimEnd('/') != settings.baseUrl.trim().trimEnd('/')
                        val suppliedKey =
                            newKey.trim().ifBlank { if (changed) "" else settings.apiKey }
                        controller.saveSettings(
                            settings.copy(
                                baseUrl = baseUrl,
                                model = model,
                                apiKey = suppliedKey,
                                companionUrl = companionUrl,
                                contextTokens =
                                    requireNotNull(contextTokens.toIntOrNull()) { "上下文预算应为整数" },
                                maxOutputTokens =
                                    requireNotNull(outputTokens.toIntOrNull()) { "输出预算应为整数" },
                                inputPricePerMillion = price(inputPrice),
                                outputPricePerMillion = price(outputPrice),
                            )
                        )
                        newKey = ""
                    }
                },
                enabled = !busy,
            ) {
                Text("保存本机参数")
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun SettingToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun AdminSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, color = Blue)
    }
}
