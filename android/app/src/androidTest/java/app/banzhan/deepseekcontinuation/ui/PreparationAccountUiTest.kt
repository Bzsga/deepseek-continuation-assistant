package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local callbacks and synthetic messages; no service or model calls. */
@RunWith(AndroidJUnit4::class)
class PreparationAccountUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortAnonymousConversationDefaultsToAllOriginalMessages() {
        val original = AtomicReference<PreparationChoice?>()
        val started = AtomicInteger()
        val login = AtomicInteger()
        show(AppState(), short(), started, login, original)
        node("直接使用原文").assertIsEnabled()
        compose.onNodeWithText("开始智能整理").assertDoesNotExist()
        assertNull(original.get())
        assertEquals(0, started.get())
        screenshot("short-original.png")
        node("直接使用原文").performClick()
        assertEquals(setOf("u", "a"), original.get()!!.selectedIds)
        assertEquals("branch", original.get()!!.branchId)
        assertEquals(0, started.get())
        assertEquals(0, login.get())
    }

    @Test fun smartChoiceAsksForLoginWithoutSubmittingAndKeepsOriginalAvailable() {
        val original = AtomicReference<PreparationChoice?>()
        val started = AtomicInteger()
        val login = AtomicInteger()
        show(AppState(), short(), started, login, original)
        node("改用智能整理").performClick()
        node("登录后免费整理").assertIsEnabled().performClick()
        assertEquals(1, login.get())
        assertEquals(0, started.get())
        node("直接使用完整原文").performClick()
        assertEquals(setOf("u", "a"), original.get()!!.selectedIds)
        assertEquals(0, started.get())
    }

    @Test fun ownApiCanOrganizeLongSelectionWithoutAnAccount() {
        val started = AtomicInteger()
        val login = AtomicInteger()
        val source = short().copy(branches = listOf(ConversationBranch("branch", "当前",
            listOf(ChatMessage("long", "user", "字".repeat(6001))), true)))
        show(AppState(settings = ServiceSettings(mode = "byok", apiKey = "synthetic-key")), source,
            started, login, AtomicReference())
        node("开始智能整理").assertIsEnabled().performClick()
        assertEquals(1, started.get())
        assertEquals(0, login.get())
        compose.onNodeWithText("登录后免费整理").assertDoesNotExist()
    }

    private fun show(state: AppState, source: ImportedConversation, started: AtomicInteger,
        login: AtomicInteger, original: AtomicReference<PreparationChoice?>) {
        compose.setContent {
            ContinuationTheme {
                Frame {
                    PreparationScreen(source, state, false, null,
                        onStart = { started.incrementAndGet() }, onOriginal = original::set,
                        onService = {}, onQuotaRefresh = {},
                        estimateMessages = { OrganizationEstimate(1, 100, 100, null) },
                        onAdvanced = {}, onSavedTask = {}, onAccount = { login.incrementAndGet() })
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Frame(content: @Composable () -> Unit) {
        Scaffold(topBar = { TopAppBar(title = { Text("准备续聊") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Mist)) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { content() }
        }
    }

    private fun short() = ImportedConversation("short", "合成周末对话", branches = listOf(
        ConversationBranch("branch", "当前", listOf(
            ChatMessage("u", "user", "嗯…周末也许去公园，暂时还没决定。\n先留着这个想法。"),
            ChatMessage("a", "assistant", "好，等你想好了我们再接着说。")), true)), currentBranchId = "branch")

    private fun node(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("preparation-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrument.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val directory = File(instrument.targetContext.filesDir, "account-preparation-qa")
        check(directory.isDirectory || directory.mkdirs())
        try { File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
