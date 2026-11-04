@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ImagePreviewDialog(title: String, painter: Painter, onDismiss: () -> Unit) {
    var scale by remember(title) { mutableFloatStateOf(1f) }
    var offset by remember(title) { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text("双指放大，拖动查看细节", style = MaterialTheme.typography.bodySmall)
                Box(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(title) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter,
                        title,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                        contentScale = ContentScale.Fit,
                    )
                }
                ActionRow {
                    TextButton(onClick = { scale = (scale + 1f).coerceAtMost(6f) }) { Text("放大") }
                    TextButton(
                        onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    ) {
                        Text("全图")
                    }
                    Button(onClick = onDismiss) { Text("关闭大图") }
                }
            }
        }
    }
}
