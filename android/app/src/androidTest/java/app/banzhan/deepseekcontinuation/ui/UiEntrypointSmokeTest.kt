package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.AppState
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
        compose.onNodeWithText("去除敏感内容").assertDoesNotExist()
        compose.onNodeWithText("重新整理").assertDoesNotExist()
        compose.onNodeWithText("查看与修改详细内容").assertDoesNotExist()
        screenshot("result-short-primary-actions.png")
        compose.onNodeWithText("复制并打开 DeepSeek").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("保存续聊文件").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals(ArchiveLogic.continuationExport(archive, emptyList()).text, copied.get())
        assertNull(saved.get())
        assertEquals(0, openedOnly.get())
        assertEquals(0, political.get())
        assertEquals(0, reorganized.get())

        compose.onNodeWithText("调整与更多").performScrollTo().performClick()
        compose.onNodeWithText("续聊设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存续聊文件").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(copied.get(), saved.get())
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        screenshot("result-short-expanded-options.png")
        compose.onNodeWithText("去除敏感内容").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SENSITIVE_CONTENT_NOTE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("去除敏感内容").performScrollTo().performClick()
        compose.onNodeWithText("查看与修改详细内容").performScrollTo().performClick()
        compose.onNodeWithText("重新整理").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, political.get())
        assertEquals(1, details.get())
        assertEquals(1, reorganized.get())
        compose.onNodeWithText("收起更多选项").performScrollTo().performClick()
        compose.onNodeWithText("去除敏感内容").assertDoesNotExist()
        compose.onNodeWithText("复制并打开 DeepSeek").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("下次继续讨论这段虚构故事。", substring = true))
        compose.onNodeWithText("下次继续讨论这段虚构故事。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("收起续聊内容").performScrollTo().performClick()
        compose.onNodeWithText("下次继续讨论这段虚构故事。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("查看续聊内容（", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("下次继续讨论这段虚构故事。", substring = true).performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("去除敏感内容").assertDoesNotExist()
        compose.onNodeWithText("重新整理").assertDoesNotExist()
        compose.onNodeWithText("查看与修改详细内容").performScrollTo().assertIsDisplayed()
    }

    @Test fun defaultExpandedLongPreviewCanRevealTheRestWithoutChangingCopiedText() {
        val archive = Archive("expanded-preview", "合成长预览", emptyList(), listOf(
            ArchiveEntry("long-entry", EntryCategory.RECENT,
                "虚构的长记忆，用于检查原文预览。\n".repeat(480) + "长预览末尾标记", edited = true),
        ), generationMode = "summary")
        val copied = AtomicReference<String?>(null)
        compose.setContent {
            ContinuationTheme {
                ContinuationResultScreen(archive, null, emptyList(), {}, copied::set, {}, {}, {}, {})
            }
        }
        awaitText("复制并打开 DeepSeek")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("当前续聊版本预览"))
        compose.onNodeWithText("当前续聊版本预览").assertIsDisplayed()
        compose.onNodeWithText("长预览末尾标记", substring = true).assertDoesNotExist()
        compose.onNodeWithText("继续查看").performScrollTo().performClick()
        compose.onNodeWithText("长预览末尾标记", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("复制并打开 DeepSeek"))
        compose.onNodeWithText("复制并打开 DeepSeek").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ArchiveLogic.continuationExport(archive, emptyList()).text, copied.get())
            assertTrue(requireNotNull(copied.get()).contains("长预览末尾标记"))
        }
    }

    @Test fun homeHasOneImportActionAndKeepsHelpAndAlternativeInputReachable() {
        val imports = AtomicInteger()
        val tutorials = AtomicInteger()
        val pastes = AtomicInteger()
        val demos = AtomicInteger()
        compose.setContent {
            ContinuationTheme {
                HomeScreen(AppState(), { imports.incrementAndGet() }, { tutorials.incrementAndGet() },
                    { pastes.incrementAndGet() }, { demos.incrementAndGet() }, {}, {}, {})
            }
        }
        compose.onNodeWithText("导入聊天文件").assertIsDisplayed().performClick()
        compose.onNodeWithText("如何下载聊天记录").performScrollTo().performClick()
        compose.onNodeWithText("粘贴聊天内容").assertDoesNotExist()
        compose.onNodeWithText("体验示例").assertDoesNotExist()
        compose.onNodeWithText("导入 JSON 文件").assertDoesNotExist()
        compose.onNodeWithText("今日免费次数").assertDoesNotExist()
        screenshot("home-simple-import.png")
        compose.onNodeWithText("其他方式").performScrollTo().performClick()
        compose.onNodeWithText("粘贴聊天内容").performScrollTo().performClick()
        compose.onNodeWithText("体验示例").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, imports.get())
            assertEquals(1, tutorials.get())
            assertEquals(1, pastes.get())
            assertEquals(1, demos.get())
        }
    }

    @Test fun entryMenuKeepsSourcePinAndDeleteBehindAnExplicitChoice() {
        val edits = AtomicInteger()
        val pins = AtomicInteger()
        val sources = AtomicInteger()
        val deletes = AtomicInteger()
        val entry = ArchiveEntry("entry-menu", EntryCategory.RECENT, "这条合成记忆仍未决定。", listOf("u1"))
        compose.setContent {
            ContinuationTheme {
                ArchiveEntryActions(entry, { edits.incrementAndGet() }, { pins.incrementAndGet() },
                    { sources.incrementAndGet() }, { deletes.incrementAndGet() })
            }
        }
        compose.onNodeWithText("固定").assertDoesNotExist()
        compose.onNodeWithText("来源 1").assertDoesNotExist()
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.onNodeWithText("编辑").assertIsDisplayed().performClick()
        compose.onNodeWithText("条目操作").performClick()
        compose.onNodeWithText("来源 1").assertIsDisplayed().performClick()
        compose.onNodeWithText("来源 1").assertDoesNotExist()
        compose.onNodeWithText("条目操作").performClick()
        compose.onNodeWithText("固定").performClick()
        compose.onNodeWithText("条目操作").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle {
            assertEquals(1, edits.get())
            assertEquals(1, sources.get())
            assertEquals(1, pins.get())
            assertEquals(1, deletes.get())
        }
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
