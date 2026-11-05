package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic sources, real Room transactions, and a network boundary that never opens a socket. */
@RunWith(AndroidJUnit4::class)
class ReorganizationPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val settings = ServiceSettings(companionUrl = "https://reorganization.synthetic.invalid",
        trialToken = "synthetic-token", model = "synthetic-model", contextTokens = 64000, maxOutputTokens = 4096)
    private val messages = listOf(ChatMessage("m1", "user", "请先理解我的意思。"),
        ChatMessage("m2", "assistant", "我会先核对你的意思。"), ChatMessage("m3", "user", "之后继续讨论。"))
    private val conversation = ImportedConversation("c", "合成来源", branches = listOf(
        ConversationBranch("main", "当前", messages, true)), currentBranchId = "main")
    private val archive = Archive("original", "原档案", listOf("c"), listOf(
        ArchiveEntry("edit", EntryCategory.RECENT, "人工修订", listOf("m1"), edited = true),
        ArchiveEntry("pin", EntryCategory.RECENT, "置顶内容", listOf("m1"), pinned = true),
        ArchiveEntry("deleted", EntryCategory.RECENT, "已删除内容", listOf("m2"), deleted = true),
        ArchiveEntry("manual", EntryCategory.BACKGROUND, "无来源的手写条目"),
        ArchiveEntry("old-summary", EntryCategory.RECENT, "旧自动摘要", listOf("m3"))),
        updatedAt = 1, background = "人工背景", generationMode = "summary",
        continuity = ContinuitySettings(enabled = false, includeRecent = false, selectedFragmentIds = emptyList(),
            interactionNotes = "手写互动备注", nextMessage = "手写下一句", includeResponseStyle = false),
        sourceScopes = listOf(ArchiveSourceScope("c", "main", messages.map { it.id })))

    private fun controller(name: String, network: FakeNetwork): Pair<ViewModelStore, AppController> {
        val store = ViewModelStore()
        lateinit var controller: AppController
        instrumentation.runOnMainSync {
            controller = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppController(application, name).also { it.organizationNetwork = network } as T
            })[AppController::class.java]
        }
        return store to controller
    }

    private fun fixture(value: Archive = archive, source: ImportedConversation = conversation,
        cache: List<ChunkCacheRow> = emptyList()) = BackupDocument(settings = settings.withoutSecrets(),
        sources = emptyList(), conversations = listOf(ConversationRow(source.id, appJson.encodeToString(source))),
        archives = listOf(ArchiveRow(value.id, value.updatedAt, appJson.encodeToString(value))),
        revisions = emptyList(), conflicts = emptyList(), tasks = emptyList(), cache = cache)

    private suspend fun seed(controller: AppController, document: BackupDocument = fixture()) {
        controller.backup()
        controller.restoreBackup(appJson.encodeToString(document))
        controller.saveSettings(settings)
    }

    private suspend fun backup(controller: AppController) = appJson.decodeFromString<BackupDocument>(controller.backup())
    private suspend fun awaitCondition(predicate: suspend () -> Boolean) = withTimeout(20_000) {
        while (!predicate()) delay(15)
    }
    private suspend fun completed(controller: AppController, id: String) = awaitCondition {
        controller.state.value.tasks.single { it.id == id }.status == "completed"
    }
    private suspend fun finish(store: ViewModelStore, controller: AppController) {
        controller.state.value.tasks.forEach { controller.cancelTask(it.id) }
        instrumentation.runOnMainSync { store.clear() }
    }

    @Test fun freshRequestPreservesOriginalAndSameConfirmationCannotPayAgainAfterCompletion() = runBlocking<Unit> {
        val network = FakeNetwork()
        val (store, controller) = controller("rebuild-fresh-${UUID.randomUUID()}.db", network)
        try {
            val oldChunk = budgetedChunks(messages, settings).single()
            val oldCacheKey = organizationCacheKey(settings, SummaryProtocol.PROMPT_VERSION, oldChunk.id, oldChunk.messages)
            val oldEntries = listOf(ArchiveEntry("old-cache", EntryCategory.RECENT, "旧缓存不会进入新版", listOf("m3")))
            seed(controller, fixture(cache = listOf(ChunkCacheRow(oldCacheKey, appJson.encodeToString(oldEntries)))))
            val originalRow = backup(controller).archives.single()
            val plan = controller.prepareReorganization(archive.id)
            assertNull(plan.blockedReason)
            assertEquals(4, plan.preservedEntryCount)
            assertEquals(1, plan.estimate!!.chunks)
            assertTrue(network.keys.isEmpty())
            val result = controller.reorganizeArchive(plan)
            completed(controller, result.taskId)
            assertEquals(1, network.keys.size)
            assertEquals(messages, network.requests.single())
            val child = controller.state.value.archives.single { it.id == result.targetArchiveId }
            assertEquals(archive, controller.state.value.archives.single { it.id == archive.id })
            assertEquals(originalRow, backup(controller).archives.single { it.id == archive.id })
            assertEquals(archive.continuity, child.continuity)
            assertEquals(archive.background, child.background)
            assertEquals(archive.sourceScopes, child.sourceScopes)
            assertEquals(archive.entries.filter { it.id != "old-summary" }, child.entries)
            assertEquals(archive.id, child.rebuiltFromArchiveId)
            val storedTask = backup(controller).tasks.single().decodeTask()
            assertEquals(archive.id, storedTask.reorganizationOfArchiveId)
            assertTrue(storedTask.chunks.all { it.cacheKey != oldCacheKey })
            val same = controller.reorganizeArchive(plan)
            assertEquals(result.copy(reusedExisting = true), same)
            assertEquals(1, network.keys.size)
            assertEquals(1, backup(controller).tasks.size)
        } finally { finish(store, controller) }
    }

    @Test fun duplicateAndRecreationKeepChildAndResumeOnlyUnfinishedRemoteChunks() = runBlocking<Unit> {
        val name = "rebuild-resume-${UUID.randomUUID()}.db"
        val network = FakeNetwork(autoComplete = false)
        val (firstStore, first) = controller(name, network)
        var secondStore: ViewModelStore? = null
        var second: AppController? = null
        try {
            val longMessages = (0..2).map { ChatMessage("long-$it", "user", "字".repeat(6000)) }
            val source = conversation.copy(branches = listOf(ConversationBranch("main", "当前", longMessages, true)))
            val original = archive.copy(entries = emptyList(), sourceScopes = listOf(
                ArchiveSourceScope("c", "main", longMessages.map { it.id })))
            seed(first, fixture(original, source))
            val plan = first.prepareReorganization(original.id)
            val start = first.reorganizeArchive(plan)
            awaitCondition { network.keys.size == 2 }
            assertEquals(start.copy(reusedExisting = true), first.reorganizeArchive(plan))
            network.complete("remote-0")
            awaitCondition { first.state.value.tasks.single().done == 1 && network.keys.size == 3 }
            first.pauseTask(start.taskId)
            val before = backup(first).tasks.single().decodeTask()
            assertEquals(1, before.chunks.count { it.completed })
            instrumentation.runOnMainSync { firstStore.clear() }
            val recreated = controller(name, network)
            secondStore = recreated.first
            second = recreated.second
            second.backup()
            val recoveredPlan = second.prepareReorganization(original.id)
            assertEquals(start.targetArchiveId, recoveredPlan.existingTargetArchiveId)
            assertEquals(start.taskId, recoveredPlan.existingTaskId)
            assertEquals(start.copy(reusedExisting = true), second.reorganizeArchive(recoveredPlan))
            network.complete("remote-1")
            network.complete("remote-2")
            second.resumeTask(start.taskId, acknowledgeCost = true)
            completed(second, start.taskId)
            val after = backup(second).tasks.single().decodeTask()
            assertEquals(3, network.keys.size)
            assertEquals(before.chunks.map { it.cacheKey }, after.chunks.map { it.cacheKey })
            assertEquals(1, network.polls.count { it == "remote-0" })
            assertTrue(after.chunks.all { it.completed })
            assertEquals(original, second.state.value.archives.single { it.id == original.id })
        } finally {
            if (second != null) finish(secondStore!!, second) else finish(firstStore, first)
            instrumentation.runOnMainSync { firstStore.clear() }
        }
    }

    @Test fun missingRangeAndStaleSourceOrSettingsNeverDispatchUntilExplicitSelection() = runBlocking<Unit> {
        val network = FakeNetwork()
        val (store, controller) = controller("rebuild-source-${UUID.randomUUID()}.db", network)
        try {
            seed(controller, fixture(archive.copy(sourceScopes = emptyList())))
            val missing = controller.prepareReorganization(archive.id)
            assertTrue(missing.needsSourceSelection)
            val missingBranch = controller.prepareReorganization(archive.id,
                listOf(archive.sourceScopes.single().copy(branchId = "no-longer-available")))
            assertTrue(missingBranch.needsSourceSelection)
            assertTrue(runCatching { controller.reorganizeArchive(missing) }.isFailure)
            val selected = listOf(ArchiveSourceScope("c", "main", listOf("m1")))
            val stale = controller.prepareReorganization(archive.id, selected)
            controller.saveBackground(archive.id, "确认后修改的背景")
            assertTrue(runCatching { controller.reorganizeArchive(stale) }.isFailure)
            val staleSettings = controller.prepareReorganization(archive.id, selected)
            controller.saveSettings(settings.copy(companionUrl = "https://changed.synthetic.invalid"))
            assertTrue(runCatching { controller.reorganizeArchive(staleSettings) }.isFailure)
            assertTrue(network.keys.isEmpty())
            val plan = controller.prepareReorganization(archive.id, selected)
            assertEquals(ReorganizationRangeOrigin.USER_SELECTION, plan.rangeOrigin)
            val start = controller.reorganizeArchive(plan)
            completed(controller, start.taskId)
            assertEquals(listOf(messages.first()), network.requests.single())
            val child = controller.state.value.archives.single { it.id == start.targetArchiveId }
            assertTrue(child.entries.any { it.id == "deleted" && it.deleted })
            assertEquals(selected, child.sourceScopes)
        } finally { finish(store, controller) }
    }

    @Test fun cancellationWithoutRemoteConfirmationReturnsExistingProgress() = runBlocking<Unit> {
        val network = FakeNetwork(autoComplete = false, failCancel = true)
        val (store, controller) = controller("rebuild-cancel-${UUID.randomUUID()}.db", network)
        try {
            seed(controller)
            val plan = controller.prepareReorganization(archive.id)
            val start = controller.reorganizeArchive(plan)
            awaitCondition { backup(controller).tasks.single().decodeTask().chunks.single().remoteJobId.isNotBlank() }
            controller.cancelTask(start.taskId)
            val cancelled = backup(controller).tasks.single().decodeTask()
            assertEquals("cancelled", cancelled.status)
            assertEquals("queued", cancelled.chunks.single().remoteStatus)
            val again = controller.prepareReorganization(archive.id)
            assertEquals(start.taskId, again.existingTaskId)
            assertEquals(start.copy(reusedExisting = true), controller.reorganizeArchive(again))
            assertEquals(1, network.keys.size)
            assertEquals(archive, controller.state.value.archives.single { it.id == archive.id })
        } finally { finish(store, controller) }
    }

    @Test fun acceptingWholeConflictGroupIsAtomicAndProducesValidBackup() = runBlocking<Unit> {
        val network = FakeNetwork()
        val (store, controller) = controller("rebuild-conflicts-${UUID.randomUUID()}.db", network)
        try {
            seed(controller)
            val start = controller.reorganizeArchive(controller.prepareReorganization(archive.id))
            completed(controller, start.taskId)
            assertTrue(controller.state.value.tasks.single().message.contains("修订/删除"))
            val group = ReorganizationLogic.groupConflicts(controller.conflicts(start.targetArchiveId)).single()
            assertEquals(3, group.size)
            val before = controller.state.value.archives.single { it.id == start.targetArchiveId }
            assertTrue(runCatching { controller.resolveConflict(start.targetArchiveId, group.first(), true,
                observedGroup = listOf(group.first())) }.isFailure)
            assertEquals(before, controller.state.value.archives.single { it.id == start.targetArchiveId })
            assertEquals(group.toSet(), controller.conflicts(start.targetArchiveId).toSet())
            controller.resolveConflict(start.targetArchiveId, group.first(), true, observedGroup = group)
            assertTrue(controller.conflicts(start.targetArchiveId).isEmpty())
            val after = controller.state.value.archives.single { it.id == start.targetArchiveId }
            assertEquals(2, after.entries.size)
            val accepted = after.entries.single { it.id != "manual" }
            assertTrue(accepted.edited && accepted.pinned && !accepted.deleted)
            assertEquals(messages.map { it.id }, accepted.sourceIds)
            BackupValidation.validate(backup(controller))
            assertEquals(archive, controller.state.value.archives.single { it.id == archive.id })
        } finally { finish(store, controller) }
    }

    @Test fun deletingOriginalLeavesChildBackupReadableAndChildCanBeReorganized() = runBlocking<Unit> {
        val network = FakeNetwork()
        val (store, controller) = controller("rebuild-parent-${UUID.randomUUID()}.db", network)
        try {
            seed(controller)
            val start = controller.reorganizeArchive(controller.prepareReorganization(archive.id))
            completed(controller, start.taskId)
            controller.deleteArchive(archive.id)
            val document = backup(controller)
            assertEquals(5, document.version)
            BackupValidation.validate(document)
            val childBeforeRestore = controller.state.value.archives.single()
            assertEquals(archive.id, childBeforeRestore.rebuiltFromArchiveId)
            assertFalse(childBeforeRestore.continuity.includeResponseStyle)
            controller.restoreBackup(appJson.encodeToString(document))
            controller.saveSettings(settings)
            val restoredChild = controller.state.value.archives.single()
            assertEquals(childBeforeRestore.continuity, restoredChild.continuity)
            assertFalse(restoredChild.continuity.includeResponseStyle)
            val next = controller.reorganizeArchive(controller.prepareReorganization(start.targetArchiveId))
            completed(controller, next.taskId)
            assertNotEquals(start.targetArchiveId, next.targetArchiveId)
            assertEquals(2, network.keys.size)
            val grandchild = controller.state.value.archives.single { it.id == next.targetArchiveId }
            assertEquals(restoredChild.continuity, grandchild.continuity)
            assertFalse(grandchild.continuity.includeResponseStyle)
            val beforeToggle = backup(controller)
            val enabledStyle = grandchild.continuity.copy(includeResponseStyle = true)
            controller.saveContinuitySettings(grandchild.id, enabledStyle)
            val afterToggle = backup(controller)
            val savedGrandchild = appJson.decodeFromString<Archive>(afterToggle.archives.single { it.id == grandchild.id }.payload)
            assertEquals(enabledStyle, savedGrandchild.continuity)
            assertEquals(grandchild.sourceScopes, savedGrandchild.sourceScopes)
            assertEquals(beforeToggle.tasks, afterToggle.tasks)
            assertFalse(controller.state.value.archives.single { it.id == restoredChild.id }.continuity.includeResponseStyle)
            assertEquals(2, network.keys.size)
            BackupValidation.validate(afterToggle)
        } finally { finish(store, controller) }
    }

    private class FakeNetwork(val autoComplete: Boolean = true, val failCancel: Boolean = false) : OrganizationNetwork {
        val keys = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<List<ChatMessage>>()
        val polls = CopyOnWriteArrayList<String>()
        private val sources = ConcurrentHashMap<String, List<ChatMessage>>()
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun complete(id: String) { gates.getValue(id).complete(Unit) }
        private fun result(id: String) = RemoteJob(id, "completed", listOf(ArchiveEntry("new-result", EntryCategory.RECENT,
            "本轮重新整理的建议", sources.getValue(id).map { it.id }, kind = EntryKind.UNCONFIRMED,
            continuityCue = ContinuityCue.OPEN_THREAD)))
        override suspend fun createSession(settings: ServiceSettings) = CompanionSession("synthetic-token", quota(settings))
        override suspend fun quota(settings: ServiceSettings) = CompanionQuota(true, null, null, "synthetic-model", "",
            dailyLimit = 500, dailyRemaining = 500, userDailyLimit = 50, userDailyRemaining = 50,
            accessMode = "public", contextTokens = 64000, maxOutputTokens = 4096)
        override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String): RemoteJob {
            val id = synchronized(keys) {
                val existing = keys.indexOf(key)
                if (existing >= 0) "remote-$existing" else {
                    val remote = "remote-${keys.size}"
                    sources[remote] = messages
                    gates[remote] = CompletableDeferred()
                    requests.add(messages)
                    keys += key
                    remote
                }
            }
            return if (autoComplete) result(id) else RemoteJob(id, "queued")
        }
        override suspend fun poll(settings: ServiceSettings, remoteId: String): RemoteJob {
            polls += remoteId
            gates.getValue(remoteId).await()
            return result(remoteId)
        }
        override suspend fun cancel(settings: ServiceSettings, remoteId: String): RemoteJob {
            if (failCancel) throw SummaryFailure("合成远端取消未确认", ambiguous = false)
            return RemoteJob(remoteId, "cancelled")
        }
        override suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String): List<ArchiveEntry> =
            error("This fixture only uses the fake companion service")
    }
}
