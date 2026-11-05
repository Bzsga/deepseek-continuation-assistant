@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.data.AppUpdateState
import app.banzhan.deepseekcontinuation.data.AppUpdateStatus

/** The caller controls safe placement and session dismissal. */
@Composable
internal fun AppUpdateBanner(
    state: AppUpdateState,
    installBlockedReason: String? = null,
    installPermissionRequired: Boolean = false,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.manifest == null) return
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UpdateDetails(state, compact = true)
            UpdateProgress(state)
            UpdateInstallGuidance(state, installBlockedReason, installPermissionRequired)
            ActionRow {
                UpdateActions(
                    state,
                    installBlockedReason,
                    installPermissionRequired,
                    onDownload,
                    onCancelDownload,
                    onInstall,
                )
                TextButton(onClick = onLater) {
                    Text(
                        if (
                            state.status in
                                setOf(AppUpdateStatus.DOWNLOADING, AppUpdateStatus.VERIFYING)
                        )
                            "收起"
                        else "稍后"
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateCard(
    state: AppUpdateState,
    installBlockedReason: String? = null,
    installPermissionRequired: Boolean = false,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "应用更新",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.manifest != null) UpdateDetails(state, compact = false)
            if (state.checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在检查新版本…", style = MaterialTheme.typography.bodySmall)
            }
            state.message
                ?.takeIf { it.isNotBlank() && state.status != AppUpdateStatus.DOWNLOADING }
                ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            UpdateProgress(state)
            UpdateInstallGuidance(state, installBlockedReason, installPermissionRequired)
            ActionRow {
                UpdateActions(
                    state,
                    installBlockedReason,
                    installPermissionRequired,
                    onDownload,
                    onCancelDownload,
                    onInstall,
                )
                OutlinedButton(
                    onClick = onCheck,
                    enabled =
                        !state.checking &&
                            state.status !in
                                setOf(AppUpdateStatus.DOWNLOADING, AppUpdateStatus.VERIFYING),
                ) {
                    Text(if (state.checking) "正在检查…" else "检查更新")
                }
            }
        }
    }
}

@Composable
private fun UpdateDetails(state: AppUpdateState, compact: Boolean) {
    val manifest = state.manifest ?: return
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(24.dp), tint = Blue)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "新版本 ${manifest.versionName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "下载大小 · ${Formatter.formatShortFileSize(context, manifest.apkSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!compact || state.status in setOf(AppUpdateStatus.AVAILABLE, AppUpdateStatus.FAILED)) {
        val notes = if (compact) manifest.changelog.take(2) else manifest.changelog
        notes.forEach { note ->
            Text(
                "• $note",
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UpdateProgress(state: AppUpdateState) {
    val context = LocalContext.current
    when (state.status) {
        AppUpdateStatus.DOWNLOADING -> {
            val total = state.totalBytes.takeIf { it > 0 } ?: state.manifest?.apkSize ?: 0L
            if (total > 0) {
                val progress =
                    (state.downloadedBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "正在下载 ${(progress * 100).toInt()}% · ${Formatter.formatShortFileSize(context, state.downloadedBytes)} / ${Formatter.formatShortFileSize(context, total)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "正在下载 · ${Formatter.formatShortFileSize(context, state.downloadedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                state.message ?: "可以切到后台，系统会继续下载。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AppUpdateStatus.VERIFYING -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在校验更新包…", style = MaterialTheme.typography.bodySmall)
        }
        AppUpdateStatus.READY -> Text("更新包已准备好。", style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
    state.error
        ?.takeIf { it.isNotBlank() }
        ?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
}

@Composable
private fun UpdateInstallGuidance(
    state: AppUpdateState,
    installBlockedReason: String?,
    installPermissionRequired: Boolean,
) {
    if (state.status != AppUpdateStatus.READY) return
    if (!installBlockedReason.isNullOrBlank()) {
        Text(
            installBlockedReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (installPermissionRequired) {
        Text(
            "请在系统设置中允许本应用安装更新包，返回后继续安装。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateActions(
    state: AppUpdateState,
    installBlockedReason: String?,
    installPermissionRequired: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    when (state.status) {
        AppUpdateStatus.AVAILABLE,
        AppUpdateStatus.FAILED -> {
            if (state.manifest != null)
                Button(onClick = onDownload, enabled = !state.checking) {
                    Text(if (state.status == AppUpdateStatus.FAILED) "重试下载" else "下载更新")
                }
        }
        AppUpdateStatus.DOWNLOADING,
        AppUpdateStatus.VERIFYING -> OutlinedButton(onClick = onCancelDownload) { Text("取消") }
        AppUpdateStatus.READY ->
            Button(
                onClick = onInstall,
                enabled = !state.checking && installBlockedReason.isNullOrBlank(),
            ) {
                Text(if (installPermissionRequired) "允许安装更新" else "安装更新")
            }
        AppUpdateStatus.IDLE -> Unit
    }
}
