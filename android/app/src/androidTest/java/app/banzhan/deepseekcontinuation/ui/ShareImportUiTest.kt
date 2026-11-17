package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportResult
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import app.banzhan.deepseekcontinuation.data.AppState
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic callbacks only; opening or previewing a share never dispatches model work. */
@RunWith(AndroidJUnit4::class)
class ShareImportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inputRejectsOtherSitesAndSuppliesOnlyACanonicalDeepSeekLink() {
        val read = AtomicReference<String?>(null)
        val cancelled = AtomicInteger()
        compose.setContent {
            ContinuationTheme { ShareLinkInputDialog({ cancelled.incrementAndGet() }, read::set) }
        }
        compose.onNodeWithText("读取聊天").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("https://example.com/share/synthetic123")
        compose.onNodeWithText("读取聊天").performClick()
        compose.runOnIdle { assertNull(read.get()); assertEquals(0, cancelled.get()) }
        compose.onNode(hasSetTextAction()).performTextReplacement("这是我的分享：https://chat.deepseek.com/share/synthetic123")
        compose.onNodeWithText("读取聊天").performClick()
        compose.runOnIdle { assertEquals("https://chat.deepseek.com/share/synthetic123", read.get()) }
    }

    @Test fun previewRetainsTheUnansweredQuestionAndRequiresAnExplicitSave() {
        val saves = AtomicInteger()
        val cancelled = AtomicInteger()
        val blocked = mutableStateOf(true)
        val tail = "那么下次还可以一起看日出吗？"
        val result = ImportResult(listOf(ImportedConversation(
            "synthetic-share", "合成 · 海边的约定", branches = listOf(ConversationBranch(
                "synthetic-main", "当前对话", listOf(
                    ChatMessage("u1", "user", "我想记住这次聊天。"),
                    ChatMessage("a1", "assistant", "我们说好，天气好时去海边。"),
                    ChatMessage("u2", "user", tail),
                ), isCurrent = true,
            )), currentBranchId = "synthetic-main",
        )), emptyList())
        compose.setContent {
            ContinuationTheme {
                ShareImportPreviewDialog(result, blocked.value, { saves.incrementAndGet() }, { cancelled.incrementAndGet() })
            }
        }
        compose.onNodeWithText("确认导入 · 3 条消息").assertIsDisplayed()
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText(tail).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves.get()); blocked.value = false }
        compose.onNodeWithText("确认导入").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves.get()) }
        screenshot("share-preview-component.png")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, cancelled.get()); assertEquals(1, saves.get()) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun homeKeepsBothImportChoicesReachableWithTheStandardAppBar() {
        val shares = AtomicInteger()
        val files = AtomicInteger()
        compose.setContent {
            ContinuationTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Deepseek续聊助手") }) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        HomeScreen(AppState(), { files.incrementAndGet() }, {}, {}, {}, {}, {}, {}, { shares.incrementAndGet() })
                    }
                }
            }
        }
        compose.onNodeWithText("导入分享链接").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("导入聊天文件").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, shares.get()); assertEquals(1, files.get()) }
        screenshot("share-home-with-app-bar.png")
    }

    @Test fun failedShareOffersRetryAndFileFallbackWithoutConsumingOnRetry() {
        val retries = AtomicInteger()
        val fileChoices = AtomicInteger()
        val dismissals = AtomicInteger()
        val blocked = mutableStateOf(true)
        compose.setContent {
            ContinuationTheme {
                IncomingImportFailureDialog(
                    error = "合成：网络暂时不可用，请稍后重试。",
                    isShareLink = true,
                    blocked = blocked.value,
                    onRetry = { retries.incrementAndGet() },
                    onChooseFile = { fileChoices.incrementAndGet() },
                    onDismiss = { dismissals.incrementAndGet() },
                )
            }
        }
        compose.onNodeWithText("重试链接").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("改用文件导入").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, retries.get())
            assertEquals(0, fileChoices.get())
            assertEquals(0, dismissals.get())
            blocked.value = false
        }
        compose.onNodeWithText("重试链接").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, retries.get())
            assertEquals(0, fileChoices.get())
            assertEquals(0, dismissals.get())
        }
        screenshot("share-failure-component.png")
        compose.onNodeWithText("改用文件导入").performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle {
            assertEquals(1, fileChoices.get())
            assertEquals(1, dismissals.get())
            assertEquals(1, retries.get())
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.waitForIdleSync()
        val bitmap = instrument.uiAutomation.takeScreenshot() ?: error("Could not capture share UI")
        val directory = File(instrument.targetContext.filesDir, "share-qa")
        check(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
