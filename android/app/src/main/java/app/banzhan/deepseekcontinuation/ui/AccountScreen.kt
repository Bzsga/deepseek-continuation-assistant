@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.data.AppController
import app.banzhan.deepseekcontinuation.data.AppState
import kotlinx.coroutines.CancellationException

@Composable
internal fun AccountScreen(
    state: AppState,
    busy: Boolean,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
    onSignedIn: () -> Unit = {},
) {
    AccountScreenContent(
        state, busy, run,
        onLogin = controller::loginAccount,
        onRegister = controller::registerAccount,
        onLogout = controller::logoutAccount,
        onRefresh = controller::refreshAccount,
        onSignedIn = onSignedIn,
    )
}

/** Callbacks keep the form testable without a server or saved passwords. */
@Composable
internal fun AccountScreenContent(
    state: AppState,
    busy: Boolean,
    run: (suspend () -> Unit) -> Unit,
    onLogin: suspend (String, String) -> Unit,
    onRegister: suspend (String, String, String?) -> Unit,
    onLogout: suspend () -> Unit,
    onRefresh: suspend () -> Unit,
    onSignedIn: () -> Unit = {},
) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var revealPassword by remember { mutableStateOf(false) }
    var invitationExpanded by rememberSaveable { mutableStateOf(false) }
    var invitation by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var hideAccountError by remember(state.account.error) { mutableStateOf(false) }
    val working = busy || pending || state.account.loading
    val user = state.account.currentUser
    fun execute(action: suspend () -> Unit) {
        if (busy || pending || state.account.loading) return
        pending = true
        problem = null
        hideAccountError = true
        run {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                problem = error.message ?: "这次操作未完成，请稍后重试。"
            } finally {
                pending = false
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag("account-page"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (user != null) {
            item { SectionTitle(user.username, "已登录 · 续聊记录保存在本机，不随账户同步。") }
            item { FreeQuotaCard(state) { execute(onRefresh) } }
            (problem ?: state.account.error.takeUnless { hideAccountError })?.let {
                item { NoticeCard(it) }
            }
            item {
                TextButton(
                    onClick = { password = ""; execute(onLogout) },
                    enabled = !working,
                    modifier = Modifier.testTag("account-logout"),
                ) { Text(if (pending) "正在退出…" else "退出账户") }
                Text(
                    "退出后，本机记录和已开始的整理任务会保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item { SectionTitle("使用免费智能整理", "账户只用于免费资格与次数，续聊记录保存在本机。") }
            item {
                ActionRow {
                    listOf(false to "登录", true to "注册").forEach { (register, label) ->
                        FilterChip(
                            selected = registering == register,
                            onClick = {
                                registering = register
                                password = ""
                                revealPassword = false
                                invitationExpanded = false
                                problem = null
                                hideAccountError = true
                            },
                            enabled = !working,
                            label = { Text(label) },
                            modifier = Modifier.testTag(if (register) "account-register-mode" else "account-login-mode"),
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    username, { username = it },
                    modifier = Modifier.fillMaxWidth().testTag("account-username"),
                    label = { Text("用户名") },
                    supportingText = if (registering) ({ Text("3–32位字母、数字、_ 或 -，可用中文。") }) else null,
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
            }
            item {
                OutlinedTextField(
                    password, { password = it },
                    modifier = Modifier.fillMaxWidth().testTag("account-password"),
                    label = { Text("密码") },
                    supportingText = if (registering) ({ Text("8–128个字符。") }) else null,
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { revealPassword = !revealPassword }) {
                            Icon(if (revealPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (revealPassword) "隐藏密码" else "显示密码")
                        }
                    },
                )
            }
            if (registering) {
                item {
                    TextButton(
                        onClick = { invitationExpanded = !invitationExpanded },
                        enabled = !working,
                        modifier = Modifier.testTag("account-invitation-toggle"),
                    ) { Text(if (invitationExpanded) "收起邀请码" else "有邀请码？（选填）") }
                    if (invitationExpanded) {
                        OutlinedTextField(
                            invitation, { invitation = it },
                            modifier = Modifier.fillMaxWidth().testTag("account-invitation"),
                            label = { Text("一次性邀请码（选填）") },
                            supportingText = { Text("公开名额已满时，也可凭有效邀请码注册。") },
                            singleLine = true,
                            enabled = !working,
                        )
                    }
                }
            }
            (problem ?: state.account.error.takeUnless { hideAccountError })?.let {
                item { NoticeCard(it) }
            }
            item {
                Button(
                    onClick = {
                        val register = registering
                        val suppliedUsername = username.trim()
                        val suppliedPassword = password
                        val code = invitation.takeIf { register && invitationExpanded }?.trim()?.takeIf { it.isNotEmpty() }
                        password = ""
                        revealPassword = false
                        execute {
                            if (register) onRegister(suppliedUsername, suppliedPassword, code)
                            else onLogin(suppliedUsername, suppliedPassword)
                            onSignedIn()
                        }
                    },
                    enabled = !working && username.isNotBlank() && password.isNotEmpty() &&
                        (!registering || password.length in 8..128),
                    modifier = Modifier.testTag("account-submit"),
                ) { Text(if (working) "请稍候…" else if (registering) "注册并登录" else "登录账户") }
                Text(
                    "使用自己的 API 或带走原文无需登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
