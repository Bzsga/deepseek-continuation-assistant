package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room persistence with synthetic sources; the fake service never opens a socket. */
@RunWith(AndroidJUnit4::class)
class BatchArchiveDeletionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val settings = ServiceSettings(companionUrl = "https://batch.synthetic.invalid",
        trialToken = "synthetic-token", model = "synthetic-model", contextTokens = 64000, maxOutputTokens = 4096)

    private fun newController(name: String, network: FakeNetwork): Pair<ViewModelStore, AppController> {
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

    private fun observer(name: String) = Room.databaseBuilder(application, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2).build()

    private fun source(messages: List<ChatMessage>) = ImportedConversation("source", "合成原文", branches = listOf(
        ConversationBranch("main", "当前", messages, true)), currentBranchId = "main")

    private fun fixture(large: Boolean): BackupDocument {
        val text = if (large) "原".repeat(90_000) else "保留原话"
        val messages = listOf(ChatMessage("m1", "user", text))
        val conversation = source(messages)
        val bytes = appJson.encodeToString(conversation).toByteArray()
        val archives = listOf("selected-a", "selected-b", "kept-child").map { id ->
            Archive(id, id, listOf(conversation.id), listOf(
                ArchiveEntry("entry-$id", EntryCategory.RECENT, text, listOf("m1"))),
                updatedAt = 1, background = text,
                rebuiltFromArchiveId = if (id == "kept-child") "selected-a" else null,
                sourceScopes = listOf(ArchiveSourceScope(conversation.id, "main", listOf("m1"))))
        }
        return BackupDocument(settings = settings.withoutSecrets(), sources = listOf(
            SourceRow(hash(bytes), "synthetic-source.json", Base64.getEncoder().encodeToString(bytes), 1)),
            conversations = listOf(ConversationRow(conversation.id, appJson.encodeToString(conversation))),
            archives = archives.map { ArchiveRow(it.id, it.updatedAt, appJson.encodeToString(it)) },
            revisions = archives.map { RevisionRow("revision-${it.id}", it.id, 1, appJson.encodeToString(it)) },
            conflicts = archives.map { archive -> ConflictRow("conflict-${archive.id}", archive.id,
                appJson.encodeToString(StoredConflict(archive.entries.single(),
                    archive.entries.single().copy(id = "proposed-${archive.id}")))) },
            tasks = archives.map { archive ->
                // An admitted request with no remote identity must never be replayed during deletion.
                val task = TaskRecord("task-${archive.id}", archive.id, archive.title, settings.withoutSecrets(), "",
                    listOf(TaskChunk("chunk-${archive.id}", messages, "cache-${archive.id}", requestStarted = true)),
                    status = "paused", uncertain = true)
                TaskRow(task.id, archive.id, 1, appJson.encodeToString(task))
            }, cache = emptyList())
    }

    private suspend fun seed(controller: AppController, document: BackupDocument) {
        controller.backup()
        controller.restoreBackup(appJson.encodeToString(document))
        controller.saveSettings(settings)
    }

    private suspend fun backup(controller: AppController) =
        appJson.decodeFromString<BackupDocument>(controller.backup()).copy(createdAt = 0)
    private suspend fun awaitCondition(predicate: suspend () -> Boolean) = withTimeout(20_000) {
        while (!predicate()) delay(15)
    }
    private suspend fun finish(store: ViewModelStore, controller: AppController) {
        controller.state.value.tasks.forEach { controller.cancelTask(it.id) }
        instrumentation.runOnMainSync { store.clear() }
    }
    private fun parts(database: AppDatabase, owner: String, id: String): Int = database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM payload_parts WHERE owner = ? AND recordId = ?", arrayOf(owner, id),
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    @Test fun selectedRowsAndPartsAreDeletedWhileChildSourcesAndPortableBackupSurvive() = runBlocking<Unit> {
        val name = "batch-parts-${UUID.randomUUID()}.db"
        val network = FakeNetwork()
        val (store, controller) = newController(name, network)
        val database = observer(name)
        val secrets = SecureCredentials(application)
        val selected = setOf("selected-a", "selected-b")
        val owners = listOf("archives" to "", "revisions" to "revision-", "conflicts" to "conflict-", "tasks" to "task-")
        try {
            seed(controller, fixture(large = true))
            val before = backup(controller)
            for ((owner, prefix) in owners) for (id in selected + "kept-child") {
                assertTrue("$owner/$id must exercise segmented storage", parts(database, owner, prefix + id) > 0)
            }
            (selected + "kept-child").forEach { secrets.put("task:task-$it", "synthetic-$it") }
            controller.deleteArchives(emptySet())
            assertEquals(before, backup(controller))
            controller.deleteArchives(selected)
            val after = backup(controller)
            assertEquals(before.sources, after.sources)
            assertEquals(before.conversations, after.conversations)
            assertEquals(before.archives.filter { it.id == "kept-child" }, after.archives)
            assertEquals(before.revisions.filter { it.archiveId == "kept-child" }, after.revisions)
            assertEquals(before.conflicts.filter { it.archiveId == "kept-child" }, after.conflicts)
            assertEquals(before.tasks.filter { it.archiveId == "kept-child" }, after.tasks)
            for ((owner, prefix) in owners) {
                selected.forEach { assertEquals(0, parts(database, owner, prefix + it)) }
                assertTrue(parts(database, owner, prefix + "kept-child") > 0)
            }
            selected.forEach { assertEquals("", secrets.get("task:task-$it")) }
            assertEquals("synthetic-kept-child", secrets.get("task:task-kept-child"))
            assertTrue(network.keys.isEmpty())
            assertTrue(network.cancelled.isEmpty())
            BackupValidation.validate(after)
            controller.restoreBackup(appJson.encodeToString(after))
            assertEquals(after, backup(controller))
        } finally {
            (selected + "kept-child").forEach { secrets.remove("task:task-$it") }
            finish(store, controller)
            database.close()
        }
    }

    @Test fun deletionFailureRollsBackTheWholeSelectionAndCanBeRetried() = runBlocking<Unit> {
        val name = "batch-rollback-${UUID.randomUUID()}.db"
        val network = FakeNetwork()
        val (store, controller) = newController(name, network)
        val database = observer(name)
        try {
            seed(controller, fixture(large = false))
            val before = backup(controller)
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_batch_delete BEFORE DELETE ON archives " +
                "WHEN OLD.id = 'selected-b' BEGIN SELECT RAISE(ABORT, 'synthetic deletion failure'); END")
            assertTrue(runCatching { controller.deleteArchives(linkedSetOf("selected-a", "selected-b")) }.isFailure)
            val failed = backup(controller)
            assertEquals(before.archives, failed.archives)
            assertEquals(before.revisions, failed.revisions)
            assertEquals(before.conflicts, failed.conflicts)
            assertEquals(before.sources, failed.sources)
            assertEquals(before.conversations, failed.conversations)
            assertEquals(before.tasks.map { it.id }.toSet(), failed.tasks.map { it.id }.toSet())
            assertTrue(failed.tasks.filter { it.archiveId != "kept-child" }.all { it.decodeTask().cancelRequested })
            BackupValidation.validate(failed)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_batch_delete")
            controller.deleteArchives(setOf("selected-a", "selected-b"))
            assertEquals(listOf("kept-child"), controller.state.value.archives.map { it.id })
            assertTrue(network.keys.isEmpty())
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS block_batch_delete")
            finish(store, controller)
            database.close()
        }
    }

    @Test fun allSelectedRunningTasksStopBeforeRemoteCancellationAndNeverResurrect() = runBlocking<Unit> {
        val name = "batch-running-${UUID.randomUUID()}.db"
        val network = FakeNetwork(blockCancellation = true)
        val (store, controller) = newController(name, network)
        var recreated: Pair<ViewModelStore, AppController>? = null
        try {
            val messages = (0..2).map { ChatMessage("long-$it", "user", "字".repeat(6000)) }
            val conversation = source(messages)
            val document = fixture(large = false).copy(
                conversations = listOf(ConversationRow(conversation.id, appJson.encodeToString(conversation))),
                archives = emptyList(), revisions = emptyList(), conflicts = emptyList(), tasks = emptyList())
            seed(controller, document)
            val selected = (0..1).map { controller.createArchive("运行档案$it", conversation, "main",
                messages.map { it.id }.toSet(), "", includeRawEntries = false) }.toSet()
            val kept = controller.createArchive("保留档案", conversation, "main", setOf(messages.first().id), "保留背景")
            selected.forEach { controller.organize(it, conversation.id, "main", messages.map { it.id }.toSet()) }
            awaitCondition {
                val tasks = backup(controller).tasks.map { it.decodeTask() }
                tasks.size == 2 && tasks.all { it.chunks.count { chunk -> chunk.remoteJobId.isNotBlank() } == 2 }
            }
            val taskIds = controller.state.value.tasks.map { it.id }
            val submissions = network.keys.size
            val deleting = async { controller.deleteArchives(selected) }
            withTimeout(20_000) { network.cancelEntered.await() }
            val stopping = backup(controller).tasks.map { it.decodeTask() }
            assertTrue(stopping.all { it.cancelRequested && it.status == "cancelled" && !it.inFlight })
            assertTrue(runCatching { controller.saveBackground(selected.last(), "不可并发修改") }.isFailure)
            assertTrue(runCatching { controller.organize(selected.last(), conversation.id, "main", setOf(messages.first().id)) }.isFailure)
            assertTrue(runCatching { controller.clearLocalData() }.isFailure)
            assertTrue(runCatching { controller.restoreBackup(appJson.encodeToString(document)) }.isFailure)
            assertEquals(submissions, network.keys.size)
            network.releaseCancel.complete(Unit)
            deleting.await()
            val after = backup(controller)
            assertFalse(controller.state.value.notice.orEmpty().contains("取消暂未确认"))
            assertEquals(listOf(kept), after.archives.map { it.id })
            assertTrue(after.tasks.isEmpty())
            assertEquals(submissions, network.cancelled.distinct().size)
            // Ordinary tasks with identical inputs share cached results across archives.
            val expectedCacheKeys = stopping.flatMap { it.chunks }.filter { it.remoteJobId.isNotBlank() }
                .map { it.cacheKey }.toSet()
            assertEquals(expectedCacheKeys, after.cache.map { it.id }.toSet())
            taskIds.forEach { assertEquals("", SecureCredentials(application).get("task:$it")) }
            BackupValidation.validate(after)
            network.completePolls()
            instrumentation.runOnMainSync { store.clear() }
            val next = newController(name, network)
            recreated = next
            val reopened = backup(next.second)
            assertEquals(after, reopened)
            assertEquals(submissions, network.keys.size)
        } finally {
            network.releaseCancel.complete(Unit)
            network.completePolls()
            finish(store, controller)
            recreated?.let { finish(it.first, it.second) }
        }
    }

    private class FakeNetwork(private val blockCancellation: Boolean = false) : OrganizationNetwork {
        val keys = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val cancelEntered = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()
        private val requests = ConcurrentHashMap<String, List<ChatMessage>>()
        private val results = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun completePolls() { results.values.forEach { it.complete(Unit) } }
        private fun completed(id: String) = RemoteJob(id, "completed", listOf(ArchiveEntry("result-$id",
            EntryCategory.RECENT, "合成整理结果", requests.getValue(id).map { it.id })))
        override suspend fun createSession(settings: ServiceSettings) = CompanionSession("synthetic-token", quota(settings))
        override suspend fun quota(settings: ServiceSettings) = CompanionQuota(true, null, null, "synthetic-model", "",
            dailyLimit = 500, dailyRemaining = 500, userDailyLimit = 50, userDailyRemaining = 50,
            accessMode = "public", contextTokens = 64000, maxOutputTokens = 4096)
        override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String): RemoteJob {
            val id = synchronized(keys) {
                val existing = keys.indexOf(key)
                if (existing >= 0) "remote-$existing" else {
                    val remote = "remote-${keys.size}"
                    requests[remote] = messages
                    results[remote] = CompletableDeferred()
                    keys += key
                    remote
                }
            }
            return RemoteJob(id, "queued")
        }
        override suspend fun poll(settings: ServiceSettings, remoteId: String): RemoteJob {
            results.getValue(remoteId).await()
            return completed(remoteId)
        }
        override suspend fun cancel(settings: ServiceSettings, remoteId: String): RemoteJob {
            cancelled += remoteId
            cancelEntered.complete(Unit)
            if (blockCancellation) releaseCancel.await()
            return completed(remoteId)
        }
        override suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String): List<ArchiveEntry> =
            error("This fixture only uses the fake companion service")
    }
}
