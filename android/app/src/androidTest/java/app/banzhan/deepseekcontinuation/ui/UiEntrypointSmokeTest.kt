package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.OrganizationTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic callbacks only: opening optional controls must never dispatch paid work. */
@RunWith(AndroidJUnit4::class)
class UiEntrypointSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortResultKeepsDirectExportAndGroupsOptionalActions() {
        val archive = Archive("short-actions", "合成续聊", emptyList(), listOf(
            ArchiveEntry("entry", EntryCategory.RECENT, "下次继续讨论这段虚构故事。", edited = true),
        ), updatedAt = 1, generationMode = "summary")
        val copied = AtomicReference<String?>(null)
        val saved = AtomicReference<String?>(null)
        val openedOnly = AtomicInteger()
        val political = AtomicInteger()
        val details = AtomicInteger()
        val reorganized = AtomicInteger()
        compose.setContent {
            ContinuationTheme {
                ContinuationResultScreen(archive, null, emptyList(), {}, copied::set, saved::set,
                    { openedOnly.incrementAndGet() }, { details.incrementAndGet() }, {},
                    onReorganize = { reorganized.incrementAndGet() },
                    onPoliticalExport = { political.incrementAndGet() })
            }
        }
        awaitText("复制并打开 DeepSeek")
        compose.onNodeWithText("打开 DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("生成温和版").assertDoesNotExist()
        compose.onNodeWithText("重新整理").assertDoesNotExist()
        compose.onNodeWithText("查看与修改详细内容").assertDoesNotExist()
        screenshot("result-short-primary-actions.png")
        compose.onNodeWithText("复制并打开 DeepSeek").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("保存续聊文件").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(ArchiveLogic.continuationExport(archive, emptyList()).text, copied.get())
        assertEquals(copied.get(), saved.get())
        assertEquals(0, openedOnly.get())
        assertEquals(0, political.get())
        assertEquals(0, reorganized.get())

        compose.onNodeWithText("调整与更多").performScrollTo().performClick()
        compose.onNodeWithText("调整（可选）").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        screenshot("result-short-expanded-options.png")
        compose.onNodeWithText("生成温和版").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("查看与修改详细内容").performScrollTo().performClick()
        compose.onNodeWithText("重新整理").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, political.get())
        assertEquals(1, details.get())
        assertEquals(1, reorganized.get())
        compose.onNodeWithText("收起更多选项").performScrollTo().performClick()
        compose.onNodeWithText("生成温和版").assertDoesNotExist()
        compose.onNodeWithText("复制并打开 DeepSeek").performScrollTo().assertIsDisplayed()
    }

    @Test fun longOriginalKeepsFileAndOpenActionsWithRecoveryVisible() {
        val archive = Archive("long-actions", "合成所选原文", emptyList(), listOf(
            ArchiveEntry("raw", EntryCategory.QUOTES, "这是合成的长篇原文，用来检查文件导出。\n".repeat(1600)),
        ), updatedAt = 1, generationMode = "original")
        val child = archive.copy(id = "pending-rebuild", title = "正在重整", rebuiltFromArchiveId = archive.id)
        val pending = OrganizationTask("pending-task", child.id, "合成重整", "paused", 1, 3)
        val saved = AtomicReference<String?>(null)
        val openedOnly = AtomicInteger()
        val resumed = AtomicReference<String?>(null)
        val copies = AtomicInteger()
        compose.setContent {
            ContinuationTheme {
                ContinuationResultScreen(archive, null, emptyList(), {}, { copies.incrementAndGet() }, saved::set,
                    { openedOnly.incrementAndGet() }, {}, {}, archives = listOf(archive, child), tasks = listOf(pending),
                    onOpenRecord = resumed::set)
            }
        }
        awaitText("保存续聊文件")
        compose.onNodeWithText("复制并打开 DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("保存续聊文件").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("打开 DeepSeek").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("继续上次整理").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(ArchiveLogic.continuationExport(archive, emptyList()).text, saved.get())
        assertTrue(saved.get()!!.toByteArray(Charsets.UTF_8).size > 60 * 1024)
        assertEquals(1, openedOnly.get())
        assertEquals(child.id, resumed.get())
        assertEquals(0, copies.get())
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("保存续聊文件").assertIsDisplayed()
        compose.onNodeWithText("打开 DeepSeek").assertIsDisplayed()
        screenshot("result-long-file-and-recovery.png")
        compose.onNodeWithText("调整与更多").performScrollTo().performClick()
        compose.onNodeWithText("生成温和版").assertDoesNotExist()
        compose.onNodeWithText("重新整理").assertDoesNotExist()
        compose.onNodeWithText("查看与修改详细内容").performScrollTo().assertIsDisplayed()
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Semantics can lead the first SurfaceFlinger frame; capture only after the rendered UI settles.
        instrumentation.uiAutomation.waitForIdle(100, 2_000)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Could not capture test UI")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "continuity-1.0.9-ui")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
