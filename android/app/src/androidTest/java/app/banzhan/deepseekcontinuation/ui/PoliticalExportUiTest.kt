package app.banzhan.deepseekcontinuation.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.AtomicFile
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import app.banzhan.deepseekcontinuation.data.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Completed synthetic snapshots only; opening these dialogs never dispatches a model request. */
@RunWith(AndroidJUnit4::class)
class PoliticalExportUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val settings = ServiceSettings(mode = "byok", baseUrl = "https://political-ui.synthetic.invalid",
        model = "synthetic-model", contextTokens = 64000, maxOutputTokens = 4096)

    @Test fun longPreviewReachesBottomAndKeepingAHitChangesOnlyTheExportCopy() {
        val fixture = fixture(long = true)
        val snapshot = ArchiveLogic.continuationExport(fixture.first, listOf(fixture.second)).text
        val task = task(fixture.first.id, snapshot, completed = true, hitText = HIT_MARKER)
        val omitted = task.variantText()
        assertFalse(omitted.contains(HIT_MARKER))
        assertTrue(omitted.contains(TAIL_MARKER))
        val originalArchive = appJson.encodeToString(fixture.first)
        val copied = AtomicReference<String?>(null)
        val saved = AtomicReference<String?>(null)
        withSnapshot(task) { controller, visible ->
            compose.setContent {
                ContinuationTheme {
                    if (visible.value) PoliticalExportDialog(fixture.first, listOf(fixture.second), controller,
                        onDismiss = { visible.value = false }, onCopy = { copied.set(it) }, onSave = { saved.set(it) })
                }
            }
            awaitCopyEnabled()
            compose.onNodeWithText("复制温和版").assertIsDisplayed().performClick()
            compose.onNodeWithText("保存温和版").assertIsDisplayed().performClick()
            assertEquals(omitted, copied.get())
            assertEquals(omitted, saved.get())

            val endText = "已显示全部 ${omitted.length} 字符"
            scrollTo(endText).assertIsDisplayed()
            compose.onNode(hasText(TAIL_MARKER, substring = true)).assertIsDisplayed()
            compose.onNodeWithText("复制温和版").assertIsDisplayed()
            compose.onNodeWithText("保存温和版").assertIsDisplayed()
            screenshot("political-long-preview-end.png")

            scrollTo("查看 1 处命中原文与原因").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保留这段（误判时勾选）"))
            compose.onNode(isToggleable()).assertIsOff().performClick()
            compose.waitUntil(10_000) { controller.politicalExport.state.value.task?.keptHitIds == task.hits.map { it.id }.toSet() }
            awaitCopyEnabled()
            copied.set(null)
            saved.set(null)
            compose.onNodeWithText("复制温和版").assertIsDisplayed().performClick()
            compose.onNodeWithText("保存温和版").assertIsDisplayed().performClick()
            assertEquals(snapshot, copied.get())
            assertEquals(snapshot, saved.get())
            assertEquals(originalArchive, appJson.encodeToString(fixture.first))
            assertEquals(snapshot, ArchiveLogic.continuationExport(fixture.first, listOf(fixture.second)).text)
            val persisted = readTask()
            assertEquals(task.originalText, persisted.originalText)
            assertEquals(task.hits.map { it.id }.toSet(), persisted.keptHitIds)
            screenshot("political-retained-hit.png")
        }
    }

    @Test fun unfinishedSnapshotCannotBeCopiedOrSavedAndDoesNotStartItself() {
        val fixture = fixture(long = false)
        val snapshot = ArchiveLogic.continuationExport(fixture.first, listOf(fixture.second)).text
        val task = task(fixture.first.id, snapshot, completed = false)
        val copied = AtomicReference<String?>(null)
        val saved = AtomicReference<String?>(null)
        withSnapshot(task) { controller, visible ->
            compose.setContent {
                ContinuationTheme {
                    if (visible.value) PoliticalExportDialog(fixture.first, listOf(fixture.second), controller,
                        onDismiss = { visible.value = false }, onCopy = { copied.set(it) }, onSave = { saved.set(it) })
                }
            }
            compose.onNodeWithText("继续检查").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("复制温和版").assertDoesNotExist()
            compose.onNodeWithText("保存温和版").assertDoesNotExist()
            compose.onNodeWithText("开始检查").assertDoesNotExist()
            screenshot("political-unfinished-no-export.png")
            compose.onNodeWithText("关闭").performClick()
            compose.runOnIdle {
                assertNull(copied.get())
                assertNull(saved.get())
                val retained = controller.politicalExport.state.value.task!!
                assertEquals("paused", retained.status)
                assertTrue(retained.batches.none { it.started || it.remoteJobId.isNotEmpty() })
                assertEquals(task, retained)
            }
        }
    }

    private fun fixture(long: Boolean): Pair<Archive, ImportedConversation> {
        val ordinary = if (long) (1..290).joinToString("\n") {
            "合成段落 $it：慢慢理解这段写作回忆，保留人物和时间顺序。"
        } else "这是一段完全虚构的写作讨论。"
        val messages = listOf(ChatMessage("ordinary", "user", ordinary),
            ChatMessage("hit", "user", HIT_MARKER), ChatMessage("tail", "user", TAIL_MARKER))
        val conversation = ImportedConversation("political-ui-source", "温和版合成来源", branches = listOf(
            ConversationBranch("main", "当前版本", messages, true)), currentBranchId = "main")
        val archive = Archive("political-ui-archive", "温和版界面测试", listOf(conversation.id),
            messages.map { ArchiveEntry("entry-${it.id}", EntryCategory.RECENT, it.content, listOf(it.id), EntryKind.USER_FACT) },
            updatedAt = 1, generationMode = "summary", continuity = ContinuitySettings(enabled = false),
            sourceScopes = listOf(ArchiveSourceScope(conversation.id, "main", messages.map { it.id })))
        return archive to conversation
    }

    private fun task(archiveId: String, text: String, completed: Boolean, hitText: String? = null): PoliticalExportTask {
        val batches = PoliticalFilterProtocol.batches(text, settings).mapIndexed { index, parts ->
            val hits = if (completed && hitText != null) parts.filter { it.text.contains(hitText) }.map {
                PoliticalExportHit(it.id, it.start, it.end, it.text, "合成测试：这段被标为政治议题，可核对并保留。")
            } else emptyList()
            PoliticalExportBatch("synthetic-ui-batch-$index", parts, completed = completed, hits = hits)
        }
        return PoliticalExportTask("synthetic-ui-${UUID.randomUUID()}", archiveId, text, hash(text), settings,
            batches, status = if (completed) "completed" else "paused", message = if (completed) "合成检查已完成" else "合成检查已暂停")
    }

    private fun withSnapshot(task: PoliticalExportTask, action: (AppController, MutableState<Boolean>) -> Unit) {
        val target = File(application.noBackupFilesDir, "political-export-task.json")
        check(listOf(target, File(target.path + ".bak"), File(target.path + ".new")).none { it.exists() }) {
            "Refusing to overwrite an existing political export record; use the task-owned QA emulator."
        }
        val record = AtomicFile(target)
        val bytes = buildJsonObject { put("version", 1); put("task", appJson.encodeToJsonElement(task)) }.toString().toByteArray()
        val output = record.startWrite()
        try { output.write(bytes); record.finishWrite(output) } catch (error: Exception) { record.failWrite(output); throw error }
        val store = ViewModelStore()
        val databaseName = "political-ui-${UUID.randomUUID()}.db"
        val visible = mutableStateOf(true)
        try {
            lateinit var controller: AppController
            instrumentation.runOnMainSync {
                controller = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppController(application, databaseName) as T
                })[AppController::class.java]
            }
            compose.waitUntil(10_000) { controller.politicalExport.state.value.loaded }
            assertNull(controller.politicalExport.state.value.error)
            assertEquals(task, controller.politicalExport.state.value.task)
            action(controller, visible)
        } finally {
            compose.runOnIdle { visible.value = false }
            instrumentation.runOnMainSync { store.clear() }
            if (target.exists()) {
                check(readTask().id == task.id) { "Refusing to remove a record outside this test." }
                record.delete()
            }
            application.deleteDatabase(databaseName)
        }
    }

    private fun readTask(): PoliticalExportTask {
        val root = appJson.parseToJsonElement(File(application.noBackupFilesDir, "political-export-task.json").readText()).jsonObject
        return appJson.decodeFromJsonElement(root.getValue("task"))
    }

    private fun awaitCopyEnabled() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText("复制温和版") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Could not capture test UI")
        val directory = File(application.getExternalFilesDir(null), "political-export-ui-qa")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    companion object {
        private const val HIT_MARKER = "合成命中片段：虚构的政治争议只用于界面测试。"
        private const val TAIL_MARKER = "温和预览末尾标记：这一句仍然完整。"
    }
}
