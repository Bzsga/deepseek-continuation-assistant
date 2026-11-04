package app.banzhan.deepseekcontinuation.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface as PlaybackSurface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun TutorialVideoDialog(videoResource: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var playbackError by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val playback =
        remember(videoResource, lifecycleOwner) {
            TutorialPlayback(
                context.applicationContext,
                videoResource,
                canPlay = {
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                },
                onReady = { loading = false },
                onCompleted = { completed = true },
                onFailure = {
                    loading = false
                    playbackError = true
                },
            )
        }
    DisposableEffect(playback, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) playback.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playback.release()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 1f),
        ) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("分享演示 · 约7秒", style = MaterialTheme.typography.titleLarge)
                Box(
                    Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    key(playback) {
                        AndroidView(
                            factory = { viewContext ->
                                TextureView(viewContext).apply {
                                    isOpaque = true
                                    surfaceTextureListener =
                                        object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(
                                                texture: SurfaceTexture,
                                                width: Int,
                                                height: Int,
                                            ) {
                                                playback.attach(texture)
                                            }

                                            override fun onSurfaceTextureSizeChanged(
                                                texture: SurfaceTexture,
                                                width: Int,
                                                height: Int,
                                            ) = Unit

                                            override fun onSurfaceTextureDestroyed(
                                                texture: SurfaceTexture
                                            ): Boolean {
                                                playback.detach()
                                                return true
                                            }

                                            override fun onSurfaceTextureUpdated(
                                                texture: SurfaceTexture
                                            ) = Unit
                                        }
                                }
                            },
                            modifier =
                                Modifier.fillMaxHeight()
                                    .aspectRatio(480f / 1054f, matchHeightConstraintsFirst = true),
                        )
                    }
                    if (playbackError) {
                        Text(
                            "视频暂时无法播放，请按教程步骤操作。",
                            color = Color.White,
                            modifier = Modifier.padding(20.dp),
                        )
                    } else if (loading) {
                        Text("正在准备演示…", color = Color.White)
                    }
                }
                if (completed) Text("演示已结束，可重播查看。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            completed = false
                            playbackError = false
                            loading = true
                            playback.replay()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重新播放")
                    }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭演示") }
                }
            }
        }
    }
}

private class TutorialPlayback(
    private val context: Context,
    private val videoResource: Int,
    private val canPlay: () -> Boolean,
    private val onReady: () -> Unit,
    private val onCompleted: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private var player: MediaPlayer? = null
    private var surface: PlaybackSurface? = null
    private var playWhenReady = true
    private var released = false

    fun attach(texture: SurfaceTexture) {
        if (released) return
        releasePlayer()
        releaseSurface()
        try {
            surface = PlaybackSurface(texture)
            prepare()
        } catch (_: RuntimeException) {
            fail()
        }
    }

    fun replay() {
        if (released) return
        playWhenReady = true
        prepare()
    }

    fun pause() {
        playWhenReady = false
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
    }

    fun detach() {
        playWhenReady = false
        releasePlayer()
        releaseSurface()
    }

    fun release() {
        released = true
        detach()
    }

    private fun prepare() {
        releasePlayer()
        val output = surface ?: return
        if (!output.isValid) {
            fail()
            return
        }
        try {
            val next = MediaPlayer()
            player = next
            next.setSurface(output)
            next.setDataSource(
                context,
                Uri.parse("android.resource://${context.packageName}/$videoResource"),
            )
            next.isLooping = false
            next.setVolume(0f, 0f)
            next.setOnPreparedListener { prepared ->
                if (released || player !== prepared) return@setOnPreparedListener
                onReady()
                if (playWhenReady && canPlay()) {
                    try {
                        prepared.start()
                    } catch (_: RuntimeException) {
                        fail()
                    }
                }
            }
            next.setOnCompletionListener { completed ->
                if (!released && player === completed) {
                    playWhenReady = false
                    onCompleted()
                }
            }
            next.setOnErrorListener { failed, _, _ ->
                if (!released && player === failed) fail()
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            fail()
        }
    }

    private fun fail() {
        releasePlayer()
        if (!released) onFailure()
    }

    private fun releasePlayer() {
        val previous = player ?: return
        player = null
        runCatching { previous.setOnPreparedListener(null) }
        runCatching { previous.setOnCompletionListener(null) }
        runCatching { previous.setOnErrorListener(null) }
        runCatching { if (previous.isPlaying) previous.pause() }
        runCatching { previous.release() }
    }

    private fun releaseSurface() {
        val previous = surface
        surface = null
        runCatching { previous?.release() }
    }
}
