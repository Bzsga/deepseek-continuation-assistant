package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Secondary actions live above the page, so opening them never moves the preview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResultToolsSheet(
    large: Boolean,
    summary: Boolean,
    canReorganize: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onSave: () -> Unit,
    onDetails: () -> Unit,
    onPoliticalExport: () -> Unit,
    onReorganize: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().testTag("result-tools-sheet")) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("续聊工具", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("result-tools-close")) {
                    Icon(Icons.Outlined.Close, "关闭工具")
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                ResultToolRow("续聊设置", Icons.Outlined.Tune, onSettings,
                    description = "交流方式、原话范围和接下来想说的话")
                if (!large) ResultToolRow("保存续聊文件", Icons.Outlined.SaveAlt, onSave)
                ResultToolRow("查看与修改详细内容", Icons.Outlined.EditNote, onDetails)
                if (summary || canReorganize) {
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (summary) ResultToolRow("去除敏感内容", Icons.Outlined.FilterAlt, onPoliticalExport,
                    enabled = !busy,
                    description = "聊天记录中若存在政治敏感内容则可能无法继承对话，可尝试此功能")
                if (canReorganize) ResultToolRow("重新整理", Icons.Outlined.AutoAwesome, onReorganize,
                    enabled = !busy)
            }
        }
    }
}

@Composable
private fun ResultToolRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String? = null,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
            }
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = color)
    }
}
