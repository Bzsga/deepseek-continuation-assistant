@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.EntryKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Blue = Color(0xFF356FC6)
internal val Ink = Color(0xFF253A50)
internal val Mist = Color(0xFFEEF4FB)
internal val Peach = Color(0xFFF7D8CA)

@Composable
internal fun ContinuationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Blue,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFDEEAFE),
                onPrimaryContainer = Ink,
                secondary = Color(0xFF697E97),
                secondaryContainer = Peach,
                onSecondaryContainer = Ink,
                background = Mist,
                onBackground = Ink,
                surface = Color.White,
                onSurface = Ink,
                surfaceVariant = Color(0xFFE6EDF6),
                onSurfaceVariant = Color(0xFF687B91),
                outline = Color(0xFF8B9CAE),
                outlineVariant = Color(0xFFDCE5F0),
            ),
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}

@Composable
internal fun Whale(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = size.width / 200f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        val body =
            Path().apply {
                moveTo(23f * scale, 78f * scale)
                cubicTo(
                    29f * scale,
                    38f * scale,
                    86f * scale,
                    29f * scale,
                    126f * scale,
                    58f * scale,
                )
                cubicTo(
                    143f * scale,
                    70f * scale,
                    143f * scale,
                    83f * scale,
                    149f * scale,
                    88f * scale,
                )
                cubicTo(
                    164f * scale,
                    85f * scale,
                    171f * scale,
                    76f * scale,
                    179f * scale,
                    66f * scale,
                )
                cubicTo(
                    188f * scale,
                    87f * scale,
                    179f * scale,
                    104f * scale,
                    155f * scale,
                    112f * scale,
                )
                cubicTo(
                    139f * scale,
                    147f * scale,
                    88f * scale,
                    151f * scale,
                    51f * scale,
                    129f * scale,
                )
                cubicTo(
                    28f * scale,
                    116f * scale,
                    18f * scale,
                    96f * scale,
                    23f * scale,
                    78f * scale,
                )
                close()
            }
        drawOval(Color(0xFFDCE7F6), point(35f, 145f), Size(120f * scale, 12f * scale))
        drawPath(body, Blue)
        val belly =
            Path().apply {
                moveTo(30f * scale, 102f * scale)
                cubicTo(
                    68f * scale,
                    128f * scale,
                    118f * scale,
                    133f * scale,
                    152f * scale,
                    110f * scale,
                )
                cubicTo(
                    131f * scale,
                    148f * scale,
                    67f * scale,
                    151f * scale,
                    30f * scale,
                    102f * scale,
                )
            }
        drawPath(belly, Color(0xFF92BAEE))
        drawCircle(Color.White, 7f * scale, point(60f, 79f))
        drawCircle(Ink, 3.8f * scale, point(61f, 80f))
        drawCircle(Color.White, 1.3f * scale, point(62f, 78f))
        drawOval(Peach, point(40f, 92f), Size(18f * scale, 9f * scale))
        drawArc(
            Ink,
            5f,
            130f,
            false,
            point(64f, 86f),
            Size(15f * scale, 10f * scale),
            style = Stroke(2f * scale),
        )
        drawCircle(Color(0xFFB4CEF0), 6f * scale, point(94f, 24f))
        drawCircle(Color(0xFFB4CEF0), 3.5f * scale, point(109f, 12f))
        drawCircle(Color(0xFFD0DFF3), 5f * scale, point(165f, 32f))
    }
}

@Composable
internal fun SectionTitle(title: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (detail != null)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
internal fun NoticeCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ActionRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
internal fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    Modifier.weight(1f, fill = false)
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                actions()
            }
        }
    }
}

internal fun EntryCategory.label() =
    when (this) {
        EntryCategory.BACKGROUND -> "背景与事实"
        EntryCategory.PEOPLE -> "人物与关系"
        EntryCategory.DECISIONS -> "决定与约定"
        EntryCategory.RECENT -> "近期进展"
        EntryCategory.QUOTES -> "重要原话"
    }

internal fun EntryKind.label() =
    when (this) {
        EntryKind.USER_FACT -> "用户陈述"
        EntryKind.ASSISTANT_SUGGESTION -> "助手建议"
        EntryKind.UNCONFIRMED -> "讨论与理解"
    }

internal fun roleLabel(role: String) =
    when (role.lowercase()) {
        "user",
        "human" -> "我"
        "assistant" -> "DeepSeek"
        "unknown" -> "角色不确定"
        "system" -> "系统"
        else -> role
    }

internal fun formatTime(epoch: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA).format(Date(epoch))

internal fun app.banzhan.deepseekcontinuation.data.OrganizationTask.requestUncertaintyText():
    String =
    if (serviceMode == "companion") "上一次请求的结果不确定，可能已占用免费次数。继续可能消耗额外请求次数。"
    else "上一次请求的结果不确定，服务方可能已计费。继续可能产生额外费用。"

internal fun app.banzhan.deepseekcontinuation.data.OrganizationTask.cancelRequestText(): String =
    if (serviceMode == "companion") "已完成的内容会保留。在途请求可能已占用免费次数，后续内容将停止整理。"
    else "已完成的内容会保留。在途请求可能已经计费，后续内容将停止整理。"
