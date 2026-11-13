package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.AppState
import app.banzhan.deepseekcontinuation.data.OrganizationEstimate
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose controls, synthetic local data only: no controller, API, or external application. */
@RunWith(AndroidJUnit4::class)
class ContinuityStageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repairWorksWithoutFillingOptionalFields() {
        val fixture = fixture()
        val copied = AtomicReference<String?>(null)
        compose.setContent {
            ContinuationTheme {
                ContinuityRepairDialog(fixture.archive, listOf(fixture.conversation), {}, copied::set)
            }
        }
        compose.onNodeWithText("没接上，调整一下").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertRepairActionsInsideSafeArea()
        scrollToRepairText("补充哪里没接上（可选）").assertIsDisplayed()
        val body = compose.onNodeWithTag("repair-dialog-body").fetchSemanticsNode().boundsInRoot
        val optional = compose.onNodeWithText("补充哪里没接上（可选）").fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("Short content should not leave a large blank area", body.bottom - optional.bottom < 36f * density)
        screenshot("repair-compact-before-generate.png")
        compose.onNodeWithText("生成修正内容").assertIsDisplayed().assertIsEnabled().performClick()
        awaitCopyButton()
        screenshot("repair-default.png")
        compose.onNodeWithText("复制修正内容").performClick()
        compose.runOnIdle {
            val text = requireNotNull(copied.get())
            assertTrue(text.contains("漏了重要背景"))
            assertTrue(text.contains("我补上与这次问题有关的背景"))
            assertTrue(text.contains("试做一个短篇"))
            assertEquals(3, fixture.archive.entries.size)
        }
    }

    @Test fun changingProblemInvalidatesPreviewAndLongDraftKeepsActionsReachable() {
        val fixture = fixture()
        val copied = AtomicReference<String?>(null)
        val clarification = "已经选定试做一个短篇，这次只想讨论开头，不需要再选题。"
        val reply = "合成的新会话回复：我们再从选题开始分析。\n".repeat(150)
        compose.setContent {
            ContinuationTheme {
                ContinuityRepairDialog(fixture.archive, listOf(fixture.conversation), {}, copied::set)
            }
        }
        scrollToRepairText("理解偏了").performClick()
        scrollToRepairText("补充哪里没接上（可选）").performClick()
        scrollToRepairText("你现在想补充或纠正的内容").performClick().performTextInput(clarification)
        scrollToRepairText("贴回新会话的回复（可选）").performClick().performTextInput(reply)
        assertRepairActionsInsideSafeArea()
        screenshot("repair-keyboard-before-generate.png")
        compose.onNodeWithText("生成修正内容").assertIsDisplayed().assertIsEnabled().performClick()
        awaitCopyButton()
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithText("复制修正内容").assertIsDisplayed().performClick()
        assertRepairActionsInsideSafeArea()
        compose.runOnIdle {
            assertTrue(requireNotNull(copied.get()).contains(clarification))
            assertTrue(requireNotNull(copied.get()).contains("刚才的回复"))
        }
        screenshot("repair-long-draft.png")

        scrollToRepairText("说话变陌生了").performClick()
        compose.onNodeWithText("复制修正内容").assertDoesNotExist()
        compose.onNodeWithText("生成修正内容").assertIsDisplayed().assertIsEnabled().performClick()
        awaitCopyButton()
        compose.onNodeWithText("复制修正内容").performClick()
        compose.runOnIdle {
            val text = requireNotNull(copied.get())
            assertTrue(text.contains("说话变陌生了"))
            assertTrue(text.contains("先说判断，再说明依据"))
            assertTrue(text.contains(clarification))
        }
    }

    @Test fun quickPreviewExpandsAndCopyAndSaveUseTheSameCompleteText() {
        val fixture = fixture()
        val quick = QuickContinuation.fromSelection(
            fixture.conversation, "main", fixture.conversation.branches.single().messages.map { it.id }.toSet(),
            background = "这是用户保留的合成背景。".repeat(460),
        )
        val copied = AtomicReference<String?>(null)
        val saved = AtomicReference<String?>(null)
        compose.setContent {
            ContinuationTheme {
                QuickContinuationDialog(quick, listOf(fixture.conversation), {}, copied::set, saved::set)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("保存文件")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("先快速接续").assertIsDisplayed()
        compose.onNodeWithText("续聊末条标记：先讨论开头。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("继续查看").performScrollTo().performClick()
        compose.onNodeWithText("续聊末条标记：先讨论开头。", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存文件").assertIsDisplayed().performClick()
        compose.onNodeWithText("复制并打开 DeepSeek").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val text = requireNotNull(saved.get())
            assertEquals(text, copied.get())
            assertTrue(text.length > 4000)
            assertTrue(text.contains("续聊末条标记：先讨论开头。"))
            assertTrue(text.contains("未进行新的智能整理"))
            assertTrue(quick.entries.isEmpty())
        }
        screenshot("quick-preview-expanded.png")
    }

    @Test fun resultCanOpenRepairWithoutStartingAnExport() {
        val fixture = fixture()
        var ordinaryCopies = 0
        val corrected = AtomicReference<String?>(null)
        compose.setContent {
            ContinuationTheme {
                var repairing by remember { mutableStateOf(false) }
                ContinuationResultScreen(
                    fixture.archive, null, listOf(fixture.conversation),
                    onSaveContinuity = {}, onCopyAndOpen = { ordinaryCopies++ }, onSave = {},
                    onOpenDeepSeek = {}, onDetails = {}, onPrepare = {}, onRepair = { repairing = true },
                )
                if (repairing) ContinuityRepairDialog(
                    fixture.archive, listOf(fixture.conversation), { repairing = false }, corrected::set,
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("复制并打开 DeepSeek")).fetchSemanticsNodes().isNotEmpty()
        }
        scrollToLazyText("没接上？调整一下").performClick()
        compose.onNodeWithText("没接上，调整一下").assertIsDisplayed()
        compose.onNodeWithText("生成修正内容").performClick()
        awaitCopyButton()
        compose.onNodeWithText("复制修正内容").performClick()
        compose.runOnIdle {
            assertNotNull(corrected.get())
            assertEquals(0, ordinaryCopies)
        }
        screenshot("result-repair-entry.png")
    }

    @Test fun preparationAllowsQuickContinuationWhenOrganizationServiceIsUnavailable() {
        val fixture = fixture()
        val selection = AtomicReference<PreparationChoice?>(null)
        var starts = 0
        compose.setContent {
            ContinuationTheme {
                PreparationScreen(
                    fixture.conversation, AppState(quotaError = "合成：整理服务暂时不可用"), false, null,
                    onStart = { starts++ }, onOriginal = {}, onService = {}, onQuotaRefresh = {},
                    estimateMessages = { OrganizationEstimate(2, 100, 50, null) },
                    onAdvanced = {}, onSavedTask = {}, onQuick = selection::set,
                )
            }
        }
        scrollToLazyText("开始智能整理").assertIsNotEnabled()
        scrollToLazyText("先快速接续").assertIsEnabled().performClick()
        compose.runOnIdle {
            val choice = requireNotNull(selection.get())
            assertEquals("main", choice.branchId)
            assertEquals(fixture.conversation.branches.single().messages.map { it.id }.toSet(), choice.selectedIds)
            assertEquals(0, starts)
        }
        screenshot("preparation-quick-offline.png")
    }

    @Test fun recentConversationPickerCanImportOrChooseAnExistingChat() {
        val fixture = fixture()
        val other = fixture.conversation.copy(id = "stage-other", title = "其他合成聊天")
        val selected = AtomicReference<String?>(null)
        var imports = 0
        compose.setContent {
            ContinuationTheme {
                ConversationPickerScreen(
                    conversations = listOf(fixture.conversation, other),
                    title = "补入最近聊天",
                    onImport = { imports++ },
                    onSelect = selected::set,
                )
            }
        }
        scrollToLazyText("导入最近导出的记录").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, imports)
            assertNull(selected.get())
        }
        scrollToLazyText("搜索聊天标题").performTextInput("短篇")
        scrollToLazyText(fixture.conversation.title).performClick()
        compose.runOnIdle {
            assertEquals(fixture.conversation.id, selected.get())
            assertEquals(1, imports)
        }
        screenshot("recent-conversation-picker.png")
    }

    private fun awaitCopyButton() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("复制修正内容")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("复制修正内容").assertIsDisplayed().assertIsEnabled()
    }

    private fun assertRepairActionsInsideSafeArea() {
        compose.waitForIdle()
        val safe = compose.onNodeWithTag("repair-dialog-safe-area").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("repair-dialog-surface").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("repair-dialog-actions").fetchSemanticsNode().boundsInRoot
        assertTrue("Dialog height should stay bounded", surface.height <= safe.height * 0.86f)
        assertTrue("Actions must stay inside the safe area", actions.bottom <= safe.bottom)
        assertTrue("Actions need bottom breathing room", actions.bottom < surface.bottom)
        compose.onNodeWithText("关闭").assertIsDisplayed()
    }

    private fun scrollToRepairText(text: String): SemanticsNodeInteraction {
        // The compact dialog can legitimately scroll even before optional fields are open.
        compose.onNodeWithTag("repair-dialog-body").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun scrollToLazyText(text: String): SemanticsNodeInteraction {
        // Lazy rows outside the viewport must be composed before their descendants can be found.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun screenshot(name: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrument.targetContext.filesDir, "continuity-stage-qa").apply { mkdirs() }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        try {
            File(directory, name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class Fixture(val conversation: ImportedConversation, val archive: Archive)

    private fun fixture(): Fixture {
        val messages = listOf(
            ChatMessage("u1", "user", "讨论时请先说判断，再说明依据。"),
            ChatMessage("a1", "assistant", "我的判断是可以先做小样，依据是它能较快暴露问题。"),
            ChatMessage("u2", "user", "我已经决定试做一个短篇，暂时不考虑长篇。"),
            ChatMessage("a2", "assistant", "那就围绕短篇接着讨论，保留长篇作为以后再考虑的方案。"),
            ChatMessage("u3", "user", "续聊末条标记：先讨论开头。"),
        )
        val conversation = ImportedConversation(
            "stage-synthetic", "短篇写作合成场景", branches = listOf(ConversationBranch("main", "当前版本", messages, true)),
            currentBranchId = "main",
        )
        val archive = Archive(
            "stage-synthetic-archive", conversation.title, listOf(conversation.id), entries = listOf(
                ArchiveEntry("preference", EntryCategory.BACKGROUND, "讨论时先说判断，再说明依据。", listOf("u1"),
                    kind = EntryKind.USER_FACT, continuityCue = ContinuityCue.PREFERENCE),
                ArchiveEntry("decision", EntryCategory.DECISIONS, "已经决定试做一个短篇，暂不考虑长篇。", listOf("u2"),
                    kind = EntryKind.USER_FACT),
                ArchiveEntry("open", EntryCategory.RECENT, "当前继续讨论短篇开头。", listOf("u3"),
                    kind = EntryKind.USER_FACT, continuityCue = ContinuityCue.OPEN_THREAD),
            ), generationMode = "summary", continuity = ContinuitySettings(enabled = true),
            sourceScopes = listOf(ArchiveSourceScope(conversation.id, "main", messages.map { it.id })),
        )
        return Fixture(conversation, archive)
    }
}
