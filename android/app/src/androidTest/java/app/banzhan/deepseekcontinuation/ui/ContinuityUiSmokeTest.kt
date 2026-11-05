package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic content only: verify the real editor can preview, scroll, and save on a small device. */
@RunWith(AndroidJUnit4::class)
class ContinuityUiSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewsOriginalContextAndSavesContinuationChoices() {
        val messages = listOf(
            ChatMessage("u1", "user", "我说的灯塔不是让你一直安慰我，只是希望我们讨论时有个方向。"),
            ChatMessage("a1", "assistant", "明白，灯塔指的是一起辨认方向，不替你决定该走哪条路。"),
            ChatMessage("u2", "user", "对，拿不准时你可以直接指出来。"),
            ChatMessage("a2", "assistant", "那我们保留这个约定：先说判断，再把不确定的地方说明白。"),
            ChatMessage("u3", "user", "昨天那个选择，我决定先试着写一小段。"),
            ChatMessage("a3", "assistant", "这一小段里，你最想保留下来的是什么？"),
            ChatMessage("u4", "user", "是那种还没想清楚，但愿意继续说下去的感觉。"),
        )
        val conversation = ImportedConversation("synthetic", "灯塔与写作", branches =
            listOf(ConversationBranch("main", "当前对话", messages, true)), currentBranchId = "main")
        val archive = Archive("ui-continuity", "灯塔与写作", listOf(conversation.id), listOf(
            ArchiveEntry("quote", EntryCategory.QUOTES, "灯塔是共同辨认方向的比喻。", listOf("u1", "a1")),
            ArchiveEntry("recent", EntryCategory.RECENT, "准备先写一小段，继续讨论想保留的感受。", listOf("u3", "u4")),
        ), generationMode = "summary", sourceScopes = listOf(
            ArchiveSourceScope(conversation.id, "main", messages.map { it.id }),
        ), continuity = ContinuitySettings(enabled = true, recentTurns = 1))
        val saved = AtomicReference<ContinuitySettings?>(null)
        compose.setContent {
            ContinuationTheme {
                var showing by remember { mutableStateOf(true) }
                if (showing) ContinuityEditor(archive, listOf(conversation), { showing = false }) {
                    saved.set(it)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("查看 ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存").assertIsDisplayed()
        screenshot("continuity-editor-top.png")
        compose.onAllNodes(hasText("查看 ", substring = true)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("查看完整原话").assertIsDisplayed()
        compose.onNodeWithText("返回设置").assertIsDisplayed()
        screenshot("continuity-original-preview.png")
        compose.onNodeWithText("返回设置").performClick()
        compose.onNodeWithContentDescription("最近对话").performScrollTo().performClick()
        compose.onNodeWithText("希望延续的交流方式").performScrollTo().performTextInput("先说判断，再说明不确定的地方。")
        compose.onNodeWithText("接下来想说的话").performScrollTo().performTextInput("接着上次那一小段说吧。")
        compose.onNodeWithText("保存").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { saved.get() != null }
        val settings = saved.get()!!
        assertTrue(settings.enabled)
        assertFalse(settings.includeRecent)
        assertEquals("先说判断，再说明不确定的地方。", settings.interactionNotes)
        assertEquals("接着上次那一小段说吧。", settings.nextMessage)
        val result = ArchiveLogic.continuationExport(archive.copy(continuity = settings), listOf(conversation))
        assertTrue(result.text.contains(settings.interactionNotes))
        assertTrue(result.text.contains(settings.nextMessage))
    }

    @Test fun longOriginalPreviewCanReachTheEnd() {
        val messages = listOf(
            ChatMessage("u", "user", "这是一段用于检查完整原文预览的虚构长对话。"),
            ChatMessage("a", "assistant", "合成长文，测试预览滚动与分段展开。\n".repeat(500)),
            ChatMessage("tail", "assistant", "原话末尾仍完整"),
        )
        val conversation = ImportedConversation("long", "长原话预览", branches =
            listOf(ConversationBranch("main", "当前版本", messages)))
        val archive = Archive("long-ui", "长原话预览", listOf("long"), emptyList(),
            background = "这是本机合成测试。", generationMode = "summary",
            continuity = ContinuitySettings(enabled = true),
            sourceScopes = listOf(ArchiveSourceScope("long", "main", messages.map { it.id })))
        compose.setContent {
            ContinuationTheme { ContinuityEditor(archive, listOf(conversation), {}, {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("查看 ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onAllNodes(hasText("查看 ", substring = true)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("原话末尾仍完整").assertDoesNotExist()
        compose.onNodeWithText("继续查看").performScrollTo().performClick()
        compose.onNodeWithText("原话末尾仍完整").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回设置").assertIsDisplayed()
        screenshot("continuity-long-preview-end.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Could not capture test UI")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "continuity-ui-qa")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
