package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.abs
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
        compose.onNodeWithTag("result-primary").assertIsDisplayed()
        compose.onNodeWithText("打开 DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("去除敏感内容").assertDoesNotExist()
        compose.onNodeWithText("重新整理").assertDoesNotExist()
        compose.onNodeWithText("查看与修改详细内容").assertDoesNotExist()
        screenshot("result-short-primary-actions.png")
        resultText("复制并打开 DeepSeek").assertIsDisplayed().performClick()
        compose.onNodeWithText("保存续聊文件").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals(ArchiveLogic.continuationExport(archive, emptyList()).text, copied.get())
        assertNull(saved.get())
        assertEquals(0, openedOnly.get())
        assertEquals(0, political.get())
        assertEquals(0, reorganized.get())

        openTools()
        compose.onNodeWithText("续聊设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存续聊文件").performScrollTo().performClick()
        awaitToolsClosed()
        assertEquals(copied.get(), saved.get())
        openTools()
        screenshot("result-short-tools-sheet.png")
        compose.onNodeWithText("去除敏感内容").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SENSITIVE_CONTENT_NOTE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("去除敏感内容").performScrollTo().performClick()
        awaitToolsClosed()
        openTools()
        compose.onNodeWithText("查看与修改详细内容").performScrollTo().performClick()
        awaitToolsClosed()
        openTools()
        compose.onNodeWithText("重新整理").performScrollTo().performClick()
        awaitToolsClosed()
        assertEquals(1, political.get())
        assertEquals(1, details.get())
        assertEquals(1, reorganized.get())
        compose.onNodeWithText("去除敏感内容").assertDoesNotExist()
        resultText("下次继续讨论这段虚构故事。", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("收起续聊内容").performScrollTo().performClick()
        compose.onNodeWithText("下次继续讨论这段虚构故事。", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("展开续聊内容").performClick()
        resultText("下次继续讨论这段虚构故事。", substring = true).assertIsDisplayed()
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
                ResultTestFrame {
                ContinuationResultScreen(archive, null, emptyList(), {}, { copies.incrementAndGet() }, saved::set,
                    { openedOnly.incrementAndGet() }, {}, {}, archives = listOf(archive, child), tasks = listOf(pending),
                    onOpenRecord = resumed::set)
                }
            }
        }
        awaitText("保存文件")
        compose.onNodeWithText("复制并打开 DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("保存文件").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("打开 DeepSeek").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("继续上次整理").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(ArchiveLogic.continuationExport(archive, emptyList()).text, saved.get())
        assertTrue(saved.get()!!.toByteArray(Charsets.UTF_8).size > 60 * 1024)
        assertEquals(1, openedOnly.get())
        assertEquals(child.id, resumed.get())
        assertEquals(0, copies.get())
        compose.onNodeWithTag("result-list").performScrollToIndex(0)
        compose.onNodeWithText("保存文件").assertIsDisplayed()
        compose.onNodeWithText("打开 DeepSeek").assertIsDisplayed()
        screenshot("result-long-file-and-recovery.png")
        openTools()
        compose.onNodeWithText("去除敏感内容").assertDoesNotExist()
        compose.onNodeWithText("重新整理").assertDoesNotExist()
        compose.onNode(hasText("保存续聊文件") and hasAnyAncestor(hasTestTag("result-tools-sheet")))
            .assertDoesNotExist()
        compose.onNodeWithText("查看与修改详细内容").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("result-tools-close").performClick()
        awaitToolsClosed()
        resultText("继续上次整理").assertIsEnabled()
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
        resultText("续聊内容").assertIsDisplayed()
        compose.onNodeWithText("长预览末尾标记", substring = true).assertDoesNotExist()
        compose.onNodeWithText("继续查看").performScrollTo().performClick()
        compose.onNodeWithText("长预览末尾标记", substring = true).performScrollTo().assertIsDisplayed()
        resultText("复制并打开 DeepSeek").performClick()
        compose.runOnIdle {
            assertEquals(ArchiveLogic.continuationExport(archive, emptyList()).text, copied.get())
            assertTrue(requireNotNull(copied.get()).contains("长预览末尾标记"))
        }
    }

    @Test fun openingToolsKeepsPreviewInPlaceAndBothExportsKeepTheCompleteText() {
        val archive = Archive("stable-result-layout", "合成排版检查", emptyList(), listOf(
            ArchiveEntry("layout-entry", EntryCategory.RECENT,
                "几何检查的合成资料。\n".repeat(900) + "完整文本尾标记", edited = true),
        ), generationMode = "summary")
        val copied = AtomicReference<String?>(null)
        val saved = AtomicReference<String?>(null)
        val extraActions = AtomicInteger()
        compose.setContent {
            ContinuationTheme {
                ResultTestFrame {
                ContinuationResultScreen(archive, null, emptyList(), {}, copied::set, saved::set,
                    {}, {}, {}, onAddRecent = { extraActions.incrementAndGet() })
                }
            }
        }
        awaitText("复制并打开 DeepSeek")
        compose.onNodeWithTag("result-list").performScrollToIndex(0)
        compose.onNodeWithTag("result-tool-trigger").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起续聊内容").assertIsDisplayed()
        val previewBefore = compose.onNodeWithTag("result-preview").fetchSemanticsNode().boundsInRoot
        val primaryBefore = compose.onNodeWithTag("result-primary").fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Preview must follow the primary actions", previewBefore.top >= primaryBefore.bottom)
        assertTrue("Secondary tools must not occupy the page", previewBefore.top - primaryBefore.bottom <= 100f * density)
        compose.onNodeWithText("完整文本尾标记", substring = true).assertDoesNotExist()
        screenshot("result-preview-before-tools.png")

        compose.onNodeWithTag("result-tool-trigger").performClick()
        compose.onNodeWithTag("result-tools-sheet").assertIsDisplayed()
        compose.onNodeWithText("续聊工具").assertIsDisplayed()
        compose.onNodeWithTag("result-tools-close").performClick()
        awaitToolsClosed()
        val previewAfter = compose.onNodeWithTag("result-preview").fetchSemanticsNode().boundsInRoot
        val primaryAfter = compose.onNodeWithTag("result-primary").fetchSemanticsNode().boundsInRoot
        assertTrue("Opening tools must not move the preview", abs(previewAfter.top - previewBefore.top) <= density)
        assertTrue("Opening tools must not resize the primary area", abs(primaryAfter.bottom - primaryBefore.bottom) <= density)
        compose.onNodeWithContentDescription("收起续聊内容").assertIsDisplayed()
        assertNull(copied.get())
        assertNull(saved.get())
        assertEquals(0, extraActions.get())
        screenshot("result-preview-after-tools.png")

        resultText("复制并打开 DeepSeek").performClick()
        openTools()
        compose.onNodeWithText("保存续聊文件").performScrollTo().performClick()
        awaitToolsClosed()
        val expected = ArchiveLogic.continuationExport(archive, emptyList()).text
        assertEquals(expected, copied.get())
        assertEquals(expected, saved.get())
        assertTrue(expected.length > 8000)
        assertTrue(requireNotNull(saved.get()).contains("完整文本尾标记"))
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

    /** Mirror the real result route's shared app bar; navigation callbacks stay inert. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ResultTestFrame(content: @Composable () -> Unit) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("去 DeepSeek 续聊", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Outlined.Checklist, "整理进度") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Mist),
            )
        }) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) { content() }
        }
    }

    private fun resultText(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.onNodeWithTag("result-list").performScrollToNode(hasText(text, substring = substring))
        return compose.onNodeWithText(text, substring = substring).performScrollTo()
    }

    private fun openTools() {
        compose.onNodeWithTag("result-list").performScrollToNode(hasTestTag("result-tool-trigger"))
        compose.onNodeWithTag("result-tool-trigger").performScrollTo().performClick()
        compose.onNodeWithTag("result-tools-sheet").assertIsDisplayed()
    }

    private fun awaitToolsClosed() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("result-tools-sheet")).fetchSemanticsNodes().isEmpty()
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
        val directory = File(instrumentation.targetContext.filesDir, "result-layout-qa")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
