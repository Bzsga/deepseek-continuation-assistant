package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityLogic
import app.banzhan.deepseekcontinuation.core.ContinuitySettings
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic, isolated Room databases; no organization or network call is made. */
@RunWith(AndroidJUnit4::class)
class ContinuityPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val first = ChatMessage("m1", "user", "请不要急着给建议，先确认你是否理解我的意思。")
    private val reply = ChatMessage("m2", "assistant", "明白，我会先确认你的意思，再一起讨论。")
    private val unselected = ChatMessage("gap", "user", "这是范围外的中间消息，不能自动带入。")
    private val last = ChatMessage("m3", "user", "接下来继续谈这个具体问题。")
    private val lastReply = ChatMessage("m4", "assistant", "好，我们继续讨论刚才的问题。")
    private val other = ChatMessage("other", "user", "另一个分支的私人内容不应带入。")
    private val conversation = ImportedConversation("c", "合成互动", branches = listOf(
        ConversationBranch("main", "当前", listOf(first, reply, unselected, last, lastReply), true),
        ConversationBranch("other-branch", "其他", listOf(other)),
    ), currentBranchId = "main")

    private fun controller(name: String): Pair<ViewModelStore, AppController> {
        val store = ViewModelStore()
        lateinit var controller: AppController
        instrumentation.runOnMainSync {
            controller = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppController(application, name) as T
            })[AppController::class.java]
        }
        return store to controller
    }

    private suspend fun restore(controller: AppController, backup: BackupDocument) {
        appJson.encodeToString(backup).byteInputStream().use { controller.restoreBackup(it) }
    }

    private fun exported(controller: AppController): String = ContinuityLogic.buildPlan(
        controller.state.value.archives.single(), controller.state.value.conversations,
    ).export.text

    @Test fun savedSettingsScopeAndExportSurviveRecreationHistoryAndBackup() = runBlocking<Unit> {
        val name = "continuity-roundtrip-${UUID.randomUUID()}.db"
        val (firstStore, firstController) = controller(name)
        var secondStore: ViewModelStore? = null
        var backupFile: File? = null
        try {
            firstController.backup() // Await initialization of this isolated database.
            restore(firstController, fixture())
            val selected = linkedSetOf(lastReply.id, first.id, last.id, reply.id)
            val id = firstController.createArchive("互动续聊", conversation, "main", selected, "人工背景", includeRawEntries = false)
            val exact = listOf(first.id, reply.id, last.id, lastReply.id)
            assertEquals(exact, firstController.state.value.archives.single().sourceScopes.single().selectedMessageIds)
            assertTrue(firstController.state.value.archives.single().continuity.enabled)
            val settings = ContinuitySettings(enabled = true, recentTurns = 1,
                interactionNotes = "先确认我的意思，再给具体建议。", nextMessage = "我们接着刚才的问题聊。")
            firstController.saveContinuitySettings(id, settings)
            firstController.addConversation(id, conversation, "main", setOf(first.id, reply.id))
            val raw = firstController.state.value.archives.single().entries.first { first.id in it.sourceIds }
            assertFalse(raw.edited)
            firstController.saveEntry(id, raw.copy(pinned = true))
            val pinned = firstController.state.value.archives.single().entries.single { it.id == raw.id }
            assertFalse(pinned.edited)
            assertTrue(ContinuityLogic.buildPlan(firstController.state.value.archives.single(), listOf(conversation))
                .recommendedFragments.any { fragment -> fragment.messages.any { it.id == first.id } })
            firstController.saveEntry(id, pinned.copy(text = "我修改后的表达"))
            val edited = firstController.state.value.archives.single().entries.single { it.id == raw.id }
            assertTrue(edited.edited)
            assertFalse(ContinuityLogic.buildPlan(firstController.state.value.archives.single(), listOf(conversation))
                .recommendedFragments.any { fragment -> fragment.messages.any { it.id == first.id } })
            firstController.saveEntry(id, edited.copy(pinned = false, edited = false))
            assertTrue(firstController.state.value.archives.single().entries.single { it.id == raw.id }.edited)
            firstController.saveEntry(id, ArchiveEntry("manual", EntryCategory.RECENT, "本人保留的事实", listOf(first.id), pinned = true))
            val saved = firstController.state.value.archives.single()
            val expected = exported(firstController)
            assertFalse(expected.contains(unselected.content))
            assertFalse(expected.contains(other.content))
            assertTrue(expected.contains(settings.interactionNotes))
            assertTrue(expected.contains(settings.nextMessage))
            firstController.saveContinuitySettings(id, settings.copy(interactionNotes = "临时修改"))
            firstController.restore(id, saved)
            assertEquals(expected, exported(firstController))
            assertEquals(saved.sourceScopes, firstController.state.value.archives.single().sourceScopes)
            val savedBackup = firstController.backupFile()
            backupFile = savedBackup
            instrumentation.runOnMainSync { firstStore.clear() }

            val (reopenedStore, reopened) = controller(name)
            secondStore = reopenedStore
            reopened.backup()
            assertEquals(expected, exported(reopened))
            assertEquals(settings, reopened.state.value.archives.single().continuity)
            reopened.saveContinuitySettings(id, settings.copy(nextMessage = "备份外的改动"))
            savedBackup.inputStream().use { reopened.restoreBackup(it) }
            assertEquals(expected, exported(reopened))
            assertEquals(saved.sourceScopes, reopened.state.value.archives.single().sourceScopes)
            assertTrue(runCatching { reopened.saveContinuitySettings(id, settings.copy(
                selectedFragmentIds = listOf("outside-the-selected-scope"))) }.isFailure)
            assertEquals(expected, exported(reopened))
        } finally {
            backupFile?.delete()
            instrumentation.runOnMainSync { firstStore.clear(); secondStore?.clear() }
            application.deleteDatabase(name)
        }
    }

    @Test fun legacyArchiveRemainsConservativeAndWrongBranchBackupCannotReplaceData() = runBlocking<Unit> {
        val name = "continuity-legacy-${UUID.randomUUID()}.db"
        val (store, controller) = controller(name)
        try {
            controller.backup()
            val legacy = Archive("legacy", "旧档案", listOf(conversation.id), listOf(
                ArchiveEntry("legacy-entry", EntryCategory.RECENT, "旧事实仍保留", listOf(first.id))), updatedAt = 1)
            val oldJson = JsonObject(appJson.parseToJsonElement(appJson.encodeToString(legacy)).jsonObject
                .filterKeys { it != "continuity" && it != "sourceScopes" }).toString()
            val oldTask = TaskRecord("legacy-task", legacy.id, "旧进度", ServiceSettings(), "",
                listOf(TaskChunk("old-chunk", listOf(last), "old-cache", remoteJobId = "old-remote")), status = "paused")
            restore(controller, fixture().copy(version = 1,
                archives = listOf(ArchiveRow(legacy.id, legacy.updatedAt, oldJson)),
                tasks = listOf(TaskRow(oldTask.id, legacy.id, 1, appJson.encodeToString(oldTask)))))
            val loaded = controller.state.value.archives.single()
            assertFalse(loaded.continuity.enabled)
            assertTrue(loaded.sourceScopes.isEmpty())
            val enabled = loaded.continuity.copy(enabled = true, interactionNotes = "保留旧设置")
            val taskBefore = appJson.decodeFromString<BackupDocument>(controller.backup()).tasks.single()
            controller.saveContinuitySettings(legacy.id, enabled)
            assertFalse(exported(controller).contains(other.content))
            assertFalse(exported(controller).contains(unselected.content))
            assertFalse(exported(controller).contains(last.content))
            assertEquals(taskBefore, appJson.decodeFromString<BackupDocument>(controller.backup()).tasks.single())
            controller.addConversation(legacy.id, conversation, "main", setOf(reply.id))
            val expanded = controller.state.value.archives.single()
            assertEquals(listOf(first.id, reply.id), expanded.sourceScopes.single().selectedMessageIds)
            assertEquals(enabled, expanded.continuity)
            val before = exported(controller)
            val backup = appJson.decodeFromString<BackupDocument>(controller.backup())
            val invalid = expanded.copy(sourceScopes = listOf(ArchiveSourceScope(conversation.id, "main", listOf(other.id))))
            val rejected = backup.copy(archives = listOf(ArchiveRow(invalid.id, invalid.updatedAt, appJson.encodeToString(invalid))))
            assertTrue(runCatching { restore(controller, rejected) }.isFailure)
            assertEquals(before, exported(controller))
            assertEquals(expanded.sourceScopes, controller.state.value.archives.single().sourceScopes)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            application.deleteDatabase(name)
        }
    }

    private fun fixture() = BackupDocument(settings = ServiceSettings(mode = "byok"), sources = emptyList(),
        conversations = listOf(ConversationRow(conversation.id, appJson.encodeToString(conversation))),
        archives = emptyList(), revisions = emptyList(), conflicts = emptyList(), tasks = emptyList(), cache = emptyList())
}
