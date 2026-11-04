package app.banzhan.deepseekcontinuation.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import app.banzhan.deepseekcontinuation.data.AppState
import app.banzhan.deepseekcontinuation.data.CompanionQuota
import app.banzhan.deepseekcontinuation.data.OrganizationTask
import app.banzhan.deepseekcontinuation.data.estimateOrganization
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic screen state only: no controller, credential, or network request. */
@RunWith(AndroidJUnit4::class)
class PreparationRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun raisedQuotaReenablesStartAndEmptyRangeOffersRecoveryWithoutLosingSavedTask() {
        val messages = listOf(ChatMessage("c:m1", "user", "合成记录：周末去公园"), ChatMessage("c:m2", "assistant", "合成建议：带水"))
        val conversation = ImportedConversation("c", "恢复测试", branches = listOf(ConversationBranch("b", "当前", messages, true)), currentBranchId = "b")
        val quota = CompanionQuota(true, null, null, "synthetic", "", dailyLimit = 500,
            dailyUsed = 23, dailyRemaining = 477, userDailyLimit = 20, userDailyUsed = 20,
            userDailyRemaining = 0, accessMode = "public", contextTokens = 64000, maxOutputTokens = 4096)
        val state = mutableStateOf(AppState(quota = quota,
            archives = listOf(Archive("archive", "已有记录", listOf("c"), emptyList())),
            tasks = listOf(OrganizationTask("task", "archive", "已有记录", "paused", 8, 10))))
        var openedArchive = ""
        var started = 0
        compose.setContent {
            ContinuationTheme {
                PreparationScreen(conversation, state.value, false, null,
                    onStart = { started++ }, onOriginal = {}, onService = {}, onQuotaRefresh = {},
                    estimateMessages = { estimateOrganization(state.value.settings, it) },
                    onAdvanced = {}, onSavedTask = { openedArchive = it })
            }
        }
        compose.onNodeWithText("回到上次整理").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("archive", openedArchive)
            assertEquals(0, started)
        }
        compose.onNodeWithText("开始智能整理").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(quota = quota.copy(userDailyLimit = 50, userDailyRemaining = 30)) }
        compose.waitForIdle()
        compose.onNodeWithText("开始智能整理").performScrollTo().assertIsEnabled()
        scrollTo("调整范围与更多选项").performClick()
        scrollTo("逐条选择消息").performClick()
        scrollTo("清空选择").performClick()
        scrollTo("开始智能整理").assertIsNotEnabled()
        scrollTo("使用当前版本全部消息").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("开始智能整理").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, started)
            assertEquals(8, state.value.tasks.single().done)
        }
    }

    private fun scrollTo(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        // LazyColumn does not compose distant rows until the container scrolls.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
}
