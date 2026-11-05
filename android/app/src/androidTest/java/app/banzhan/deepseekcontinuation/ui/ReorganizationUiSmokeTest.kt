package app.banzhan.deepseekcontinuation.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReorganizationUiSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmationRequiresAnExplicitTap() {
        val confirms = AtomicInteger(0)
        val otherActions = AtomicInteger(0)
        val plan = ReorganizationPlan(
            archiveId = "original", archiveTitle = "合成档案", messageCount = 2,
            conversationCount = 1, sourceDescription = "上次选择的两条消息",
            estimate = OrganizationEstimate(1, 120, 80, null),
        )
        compose.setContent {
            ContinuationTheme {
                ReorganizationDialog(
                    plan = plan, state = byokState(), busy = false, error = null,
                    onDismiss = { otherActions.incrementAndGet() },
                    onConfirm = { confirms.incrementAndGet() },
                    onSelectRange = { otherActions.incrementAndGet() },
                    onSettings = { otherActions.incrementAndGet() },
                    onQuotaRefresh = { otherActions.incrementAndGet() },
                    onRefresh = { otherActions.incrementAndGet() },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(0, confirms.get())
        assertEquals(0, otherActions.get())
        saveReorganizationScreenshot("reorganization-confirm.png")
        compose.onNodeWithText("确认并重新整理").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, confirms.get())
        assertEquals(0, otherActions.get())
    }

    @Test fun missingSourceRangeOnlyOffersRangeSelection() {
        val confirms = AtomicInteger(0)
        val selectedRanges = AtomicInteger(0)
        val plan = ReorganizationPlan(
            archiveId = "legacy", archiveTitle = "范围未保存的旧档案",
            blockedReason = "请明确选择这次要使用的聊天范围。", needsSourceSelection = true,
        )
        compose.setContent {
            ContinuationTheme {
                ReorganizationDialog(
                    plan = plan, state = byokState(), busy = false, error = null,
                    onDismiss = {}, onConfirm = { confirms.incrementAndGet() },
                    onSelectRange = { selectedRanges.incrementAndGet() },
                    onSettings = {}, onQuotaRefresh = {}, onRefresh = {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals(0, confirms.get())
        assertEquals(0, selectedRanges.get())
        compose.onNodeWithText("确认并重新整理").assertDoesNotExist()
        compose.onNodeWithText("重新选择范围").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, selectedRanges.get())
        assertEquals(0, confirms.get())
    }

    @Test fun cancelledRebuildAllowsAnotherAttemptButPausedRebuildTakesPriority() {
        val original = Archive("original", "原档案", emptyList(), listOf(
            ArchiveEntry("manual", EntryCategory.RECENT, "保留我的修改。", edited = true),
        ), updatedAt = 10L, background = "原有背景")
        val originalSnapshot = original.copy(entries = original.entries.map { it.copy() })
        val cancelledChild = original.copy(id = "cancelled-child", updatedAt = 200L, rebuiltFromArchiveId = original.id)
        val pausedChild = original.copy(id = "paused-child", updatedAt = 100L, rebuiltFromArchiveId = original.id)
        val cancelledTask = OrganizationTask("cancelled-task", cancelledChild.id, "上次整理", "cancelled", 1, 2)
        val pausedTask = OrganizationTask("paused-task", pausedChild.id, "中断整理", "paused", 1, 2)
        val tasks = mutableStateOf(listOf(cancelledTask))
        val opened = AtomicReference<String?>(null)
        val reorganizes = AtomicInteger(0)
        compose.setContent {
            ContinuationTheme {
                ArchiveReorganizationActions(
                    archive = original, archives = listOf(original, cancelledChild, pausedChild),
                    tasks = tasks.value, busy = false,
                    onReorganize = { reorganizes.incrementAndGet() }, onOpenRecord = { opened.set(it) },
                )
            }
        }
        compose.waitForIdle()
        assertNull(opened.get())
        assertEquals(0, reorganizes.get())
        compose.onNodeWithText("重新整理").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("查看上次整理").performClick()
        assertEquals(cancelledChild.id, opened.get())

        compose.runOnIdle {
            opened.set(null)
            tasks.value = listOf(cancelledTask, pausedTask)
        }
        compose.onNodeWithText("重新整理").assertDoesNotExist()
        compose.onNodeWithText("查看上次整理").assertDoesNotExist()
        compose.onNodeWithText("继续上次整理").assertIsDisplayed().performClick()
        assertEquals(pausedChild.id, opened.get())
        assertEquals(0, reorganizes.get())
        assertEquals(originalSnapshot, original)
    }

    @Test fun scopeOnlySelectionReturnsRangeWithoutArchiveOrAiActions() {
        val messages = listOf(ChatMessage("u", "user", "合成问题。"), ChatMessage("a", "assistant", "合成回答。"))
        val conversation = ImportedConversation("scope-chat", "重新选择的范围", branches =
            listOf(ConversationBranch("main", "当前版本", messages, isCurrent = true)), currentBranchId = "main")
        val original = Archive("source", "原档案", listOf(conversation.id), emptyList(), background = "保持原背景")
        val selected = AtomicReference<Pair<String, Set<String>>?>(null)
        val target = AtomicReference<String?>("not-called")
        val calls = AtomicInteger(0)
        compose.setContent {
            ContinuationTheme {
                SelectionScreen(
                    conversation = conversation, archives = listOf(original), initialTargetId = original.id,
                    busy = false, scopeOnly = true, selectionContextKey = "reorganization-scope-smoke",
                ) { _, branch, ids, _, archiveTarget, _ ->
                    selected.set(branch to ids)
                    target.set(archiveTarget)
                    calls.incrementAndGet()
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, calls.get())
        listOf("新建档案", "创建档案", "加入档案", "AI 整理").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("使用此范围"))
        compose.onNodeWithText("使用此范围").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, calls.get())
        assertEquals("main", selected.get()!!.first)
        assertEquals(messages.map { it.id }.toSet(), selected.get()!!.second)
        assertNull(target.get())
        assertEquals("保持原背景", original.background)
    }

    private fun byokState() = AppState(settings = ServiceSettings(
        mode = "byok", baseUrl = "https://example.invalid/v1", apiKey = "synthetic-ui-test-key", model = "synthetic-model",
    ))
}
