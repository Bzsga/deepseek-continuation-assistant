package app.banzhan.deepseekcontinuation.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.banzhan.deepseekcontinuation.R
import kotlinx.coroutines.launch

private data class TutorialStep(
    val title: String,
    val instruction: String,
    val image: Int,
    val aspectRatio: Float,
    val alignment: Alignment = Alignment.Center,
    val contentScale: ContentScale = ContentScale.Crop,
)

private val tutorialSteps =
    listOf(
        TutorialStep(
            "打开左上角菜单",
            "登录 DeepSeek 网页版，点左上角的菜单。",
            R.drawable.tutorial_web_menu,
            1.5f,
            Alignment.TopCenter,
        ),
        TutorialStep(
            "点击底部「···」",
            "打开侧边栏后，点底部的「···」按钮。",
            R.drawable.tutorial_web_more,
            1.8f,
            Alignment.BottomCenter,
        ),
        TutorialStep(
            "选择「系统设置」",
            "在弹出的菜单中，点「系统设置」。",
            R.drawable.tutorial_web_settings,
            501f / 614f,
            contentScale = ContentScale.Fit,
        ),
        TutorialStep("下载聊天 ZIP", "先选「数据管理」，再点「下载」。", R.drawable.tutorial_web_download, 1f),
        TutorialStep(
            "找到 ZIP，点分享",
            "在浏览器下载记录中，长按 ZIP 或点文件菜单，再选「分享」。",
            R.drawable.tutorial_share_menu,
            0.95f,
            Alignment.TopCenter,
        ),
        TutorialStep(
            "选择续聊助手",
            "在分享列表中，选择「Deepseek续聊助手」。",
            R.drawable.tutorial_share_target,
            1f,
            Alignment.BottomCenter,
        ),
    )

private object DownloadButtonFocus : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val crop = ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
        return ScaleFactor(crop.scaleX * 2f, crop.scaleY * 2f)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadShareTutorialDialog(
    onDismiss: () -> Unit,
    onOpenOfficialWebsite: () -> Unit,
    onImportFile: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { tutorialSteps.size })
    val scope = rememberCoroutineScope()
    var navigating by remember { mutableStateOf(false) }
    var selectedImage by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var showVideo by remember { mutableStateOf(false) }
    val stepIndex = pagerState.currentPage.coerceIn(0, tutorialSteps.lastIndex)
    val navigationEnabled = !navigating && !pagerState.isScrollInProgress

    fun goToPage(requestedPage: Int) {
        if (navigating || pagerState.isScrollInProgress) return
        val target = requestedPage.coerceIn(0, tutorialSteps.lastIndex)
        if (target == pagerState.currentPage) return
        navigating = true
        scope.launch {
            try {
                pagerState.animateScrollToPage(target)
            } finally {
                navigating = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            // Keep the scroll viewport inside this window's system-bar bounds,
            // independent of the hosting screen's edge-to-edge inset consumption.
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "下载与分享教程",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${stepIndex + 1}/${tutorialSteps.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭教程") }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(tutorialSteps.size) { index ->
                            Box(
                                Modifier.weight(1f)
                                    .height(4.dp)
                                    .background(
                                        if (index <= stepIndex) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        MaterialTheme.shapes.small,
                                    )
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { goToPage(stepIndex - 1) },
                            enabled = navigationEnabled && stepIndex > 0,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Text("上一步")
                        }
                        Button(
                            onClick = {
                                if (stepIndex == tutorialSteps.lastIndex) onDismiss()
                                else goToPage(stepIndex + 1)
                            },
                            enabled = navigationEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Text(if (stepIndex == tutorialSteps.lastIndex) "我知道了" else "下一步")
                        }
                    }
                    Text(
                        "左右滑动切换步骤",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    key = { page -> page },
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val step = tutorialSteps[page]
                    val scroll = rememberScrollState()
                    LaunchedEffect(pagerState.settledPage) {
                        if (pagerState.settledPage == page) scroll.scrollTo(0)
                    }
                    Column(
                        Modifier.fillMaxSize()
                            .testTag("tutorial-page-$page")
                            .verticalScroll(scroll)
                            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            step.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            step.instruction,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (page == 0) {
                            Button(onClick = onOpenOfficialWebsite) { Text("打开官方网页版") }
                        }
                        if (page < 4) {
                            TextButton(onClick = { goToPage(4) }, enabled = navigationEnabled) {
                                Text("我已下载，直接看分享")
                            }
                        }
                        if (page >= 4) {
                            OutlinedButton(onClick = { showVideo = true }) {
                                Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("看分享演示（约7秒）")
                            }
                        }
                        if (page == 3) {
                            Text("先选「数据管理」", fontWeight = FontWeight.SemiBold)
                            TutorialFocusImage(
                                step,
                                aspectRatio = 3f,
                                alignment = Alignment.TopCenter,
                            ) {
                                selectedImage = step.title to step.image
                            }
                            Text("再点「下载」", fontWeight = FontWeight.SemiBold)
                            TutorialFocusImage(
                                step,
                                aspectRatio = 2.2f,
                                alignment = BiasAlignment(1f, 0.55f),
                                contentScale = DownloadButtonFocus,
                            ) {
                                selectedImage = step.title to step.image
                            }
                        } else {
                            TutorialFocusImage(step) { selectedImage = step.title to step.image }
                        }
                        Text(
                            "点图查看完整原图",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = onImportFile,
                            modifier = Modifier.align(Alignment.Start),
                        ) {
                            Text("手动选择 ZIP（备用）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    selectedImage?.let { (title, resource) ->
        ImagePreviewDialog("$title · 完整原图", painterResource(resource)) { selectedImage = null }
    }
    if (showVideo) TutorialVideoDialog(R.raw.tutorial_share_demo) { showVideo = false }
}

@Composable
private fun TutorialFocusImage(
    step: TutorialStep,
    aspectRatio: Float = step.aspectRatio,
    alignment: Alignment = step.alignment,
    contentScale: ContentScale = step.contentScale,
    onOpenOriginal: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .aspectRatio(aspectRatio)
            .clickable(onClick = onOpenOriginal)
    ) {
        Image(
            painterResource(step.image),
            "${step.title}图示，点击查看完整原图",
            Modifier.fillMaxSize(),
            alignment = alignment,
            contentScale = contentScale,
        )
    }
}
