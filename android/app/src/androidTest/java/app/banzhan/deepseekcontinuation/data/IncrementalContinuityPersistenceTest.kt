package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.EntryKind
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Re-imports through the real parser and Room; every model/service boundary is synthetic. */
@RunWith(AndroidJUnit4::class)
class IncrementalContinuityPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val settings = ServiceSettings(
        companionUrl = "https://incremental.synthetic.invalid",
        trialToken = "synthetic-token",
        model = "synthetic-model",
        contextTokens = 64000,
        maxOutputTokens = 4096,
    )
    private val sourceMessages = listOf(
        ChatMessage("m1", "user", "先讨论周末摄影的安排。"),
        ChatMessage("m2", "assistant", "我们先确认时间和地点。"),
        ChatMessage("m3", "user", "时间已确定，接下来讨论交通。"),
        ChatMessage("m4", "assistant", "好，接着比较地铁和公交。"),
    )

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

    private fun document(count: Int): ByteArray = buildJsonArray {
        add(buildJsonObject {
            put("id", "incremental-synthetic-chat")
            put("title", "增量合成来源")
            putJsonArray("messages") {
                sourceMessages.take(count).forEach { message -> add(buildJsonObject {
                    put("id", message.id)
                    put("role", message.role)
                    put("content", message.content)
                }) }
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    private fun current(conversation: ImportedConversation): ConversationBranch =
        conversation.branches.firstOrNull { it.id == conversation.currentBranchId }
            ?: conversation.branches.first { it.isCurrent }

    private suspend fun awaitTaskCount(controller: AppController, count: Int) = withTimeout(20_000) {
        while (controller.state.value.tasks.size != count || controller.state.value.tasks.any { it.status != "completed" }) delay(15)
    }

    private suspend fun backup(controller: AppController): BackupDocument =
        appJson.decodeFromString(controller.backup())

    @Test fun repeatedImportOnlyOrganizesNewMessagesAndCompletedCoverageSurvivesRecreation() = runBlocking<Unit> {
        val name = "incremental-persistence-${UUID.randomUUID()}.db"
        val network = FakeNetwork()
        val (firstStore, first) = controller(name, network)
        var secondStore: ViewModelStore? = null
        try {
            first.backup()
            first.saveSettings(settings)
            val sourceId = first.importFile(document(2), "incremental-first.json").single()
            val originalSource = first.state.value.conversations.single { it.id == sourceId }
            val originalBranch = current(originalSource)
            val originalIds = originalBranch.messages.map { it.id }.toSet()
            val archiveId = first.createArchive("增量检查", originalSource, originalBranch.id, originalIds,
                "", includeRawEntries = false)
            assertTrue(first.state.value.archives.single().entries.isEmpty())
            assertEquals(originalIds, first.state.value.archives.single().sourceScopes.single().selectedMessageIds.toSet())
            assertTrue(network.requests.isEmpty())
            first.saveEntry(archiveId, ArchiveEntry("derived-before-first", EntryCategory.RECENT,
                "首次整理前的手工补充", listOf(originalBranch.messages.first().id), pinned = true))
            val derived = first.state.value.archives.single().entries.single()
            assertTrue(derived.edited && derived.pinned)

            // Capturing a scope before dispatch must not make this empty draft look completed.
            first.organize(archiveId, sourceId, originalBranch.id, originalIds)
            awaitTaskCount(first, 1)
            assertEquals(originalBranch.messages, network.requests.single())
            assertEquals(derived, first.state.value.archives.single().entries.single { it.id == derived.id })
            first.saveEntry(archiveId, ArchiveEntry("manual", EntryCategory.BACKGROUND,
                "人工保留的背景", kind = EntryKind.USER_FACT, pinned = true))
            val manual = first.state.value.archives.single().entries.single { it.id == "manual" }

            assertEquals(sourceId, first.importFile(document(4), "incremental-next.json").single())
            val extended = first.state.value.conversations.single { it.id == sourceId }
            val extendedBranch = current(extended)
            val allIds = extendedBranch.messages.map { it.id }.toSet()
            val addedMessages = extendedBranch.messages.takeLast(2)
            val addedIds = addedMessages.map { it.id }
            val preview = first.incrementalSelection(archiveId, sourceId, extendedBranch.id, allIds)
            assertEquals(addedIds, preview.messageIds)
            assertEquals(2, preview.alreadyIncludedCount)
            assertEquals(1, network.requests.size)

            val addition = first.addConversation(archiveId, extended, extendedBranch.id, allIds)
            assertEquals(addedIds, addition.messageIds)
            assertEquals(2, addition.newMessageCount)
            assertEquals(manual, first.state.value.archives.single().entries.single { it.id == "manual" })
            assertEquals(1, first.state.value.tasks.size)
            first.organize(archiveId, sourceId, extendedBranch.id, addition.messageIds.toSet())
            awaitTaskCount(first, 2)
            assertEquals(addedMessages, network.requests.last())
            assertEquals(2, network.requests.size)
            val incrementalBackup = backup(first)
            assertEquals(6, incrementalBackup.version)
            val incrementalTask = incrementalBackup.tasks.map { it.decodeTask() }.single { task ->
                task.chunks.flatMap { it.messages }.map { it.id } == addedIds
            }
            val leading = originalBranch.messages.filterNot { it.id in derived.sourceIds }
            assertTrue(leading.isNotEmpty())
            assertEquals(leading, network.references.last())
            assertEquals(leading, incrementalTask.chunks.first().contextMessages)
            val leadingScope = ArchiveSourceScope(sourceId, extendedBranch.id, leading.map { it.id })
            assertEquals(leadingScope, incrementalTask.initialContextScope)
            val firstPrimaryIndex = extendedBranch.messages.indexOfFirst { it.id in addedIds }
            assertTrue(leadingScope.selectedMessageIds.all { id ->
                id in originalIds && extendedBranch.messages.indexOfFirst { it.id == id } in 0 until firstPrimaryIndex
            })
            BackupValidation.validate(incrementalBackup)

            val saved = first.state.value.archives.single()
            first.importFile(document(4), "incremental-same.json")
            val sameSource = first.state.value.conversations.single { it.id == sourceId }
            val noAddition = first.addConversation(archiveId, sameSource, extendedBranch.id, allIds)
            assertEquals(0, noAddition.newMessageCount)
            assertEquals(4, noAddition.alreadyIncludedCount)
            assertEquals(saved, first.state.value.archives.single())
            assertEquals(2, first.state.value.tasks.size)
            assertEquals(2, network.requests.size)

            instrumentation.runOnMainSync { firstStore.clear() }
            val (reopenedStore, reopened) = controller(name, network)
            secondStore = reopenedStore
            val reopenedBackup = backup(reopened)
            assertEquals(6, reopenedBackup.version)
            val reopenedTask = reopenedBackup.tasks.single { it.id == incrementalTask.id }.decodeTask()
            assertEquals(incrementalTask.initialContextScope, reopenedTask.initialContextScope)
            assertEquals(incrementalTask.promptVersion, reopenedTask.promptVersion)
            assertEquals(incrementalTask.chunks, reopenedTask.chunks)
            assertEquals(saved, reopened.state.value.archives.single())
            // A no-op must not need credentials, quota, or even an available service.
            reopened.saveSettings(ServiceSettings(mode = "byok", baseUrl = "https://offline.synthetic.invalid",
                model = "synthetic-model", apiKey = ""))
            val quotaCalls = network.quotaCalls.get()
            reopened.organize(archiveId, sourceId, extendedBranch.id, allIds)
            assertEquals(quotaCalls, network.quotaCalls.get())
            assertEquals(2, network.requests.size)
            assertEquals(2, reopened.state.value.tasks.size)
            assertEquals(saved, reopened.state.value.archives.single())
            val portable = backup(reopened)
            assertEquals(6, portable.version)
            BackupValidation.validate(portable)
            reopened.restoreBackup(appJson.encodeToString(portable))
            val restored = backup(reopened)
            val restoredTask = restored.tasks.single { it.id == incrementalTask.id }.decodeTask()
            assertEquals(leadingScope, restoredTask.initialContextScope)
            assertEquals(incrementalTask.promptVersion, restoredTask.promptVersion)
            assertEquals(incrementalTask.chunks, restoredTask.chunks)
            val restoredQuotaCalls = network.quotaCalls.get()
            reopened.organize(archiveId, sourceId, extendedBranch.id, allIds)
            assertEquals(restoredQuotaCalls, network.quotaCalls.get())
            assertEquals(2, network.requests.size)
            assertEquals(2, reopened.state.value.tasks.size)
            assertEquals(saved, reopened.state.value.archives.single())
            BackupValidation.validate(backup(reopened))

            // A legitimate restore can narrow the archive's current range while the exact
            // earlier paid task remains. Its complete pre-restore snapshot is retained.
            val earlierRange = reopened.revisions(archiveId).first { revision ->
                revision.sourceScopes.singleOrNull()?.selectedMessageIds?.toSet() == originalIds
            }
            reopened.restore(archiveId, earlierRange)
            assertEquals(originalIds, reopened.state.value.archives.single().sourceScopes.single().selectedMessageIds.toSet())
            assertEquals(2, reopened.state.value.tasks.size)
            assertEquals(incrementalTask.chunks, backup(reopened).tasks.single { it.id == incrementalTask.id }.decodeTask().chunks)
            BackupValidation.validate(backup(reopened))
            assertEquals(2, network.requests.size)
        } finally {
            instrumentation.runOnMainSync { firstStore.clear(); secondStore?.clear() }
            application.deleteDatabase(name)
        }
    }

    @Test fun reimportPreservesEditedDeletedAndPinnedContentWithoutReaddingOldRawEntries() = runBlocking<Unit> {
        val name = "incremental-protection-${UUID.randomUUID()}.db"
        val network = FakeNetwork()
        val (store, controller) = controller(name, network)
        try {
            controller.backup()
            controller.saveSettings(settings)
            val sourceId = controller.importFile(document(2), "protected-original.json").single()
            val original = controller.state.value.conversations.single { it.id == sourceId }
            val branch = current(original)
            val archiveId = controller.createArchive("原文保护", original, branch.id,
                branch.messages.map { it.id }.toSet(), "")
            val initial = controller.state.value.archives.single().entries
            controller.saveEntry(archiveId, initial.first().copy(text = "人工修订后的表达", pinned = true))
            controller.saveEntry(archiveId, initial.last().copy(deleted = true))
            val protected = controller.state.value.archives.single().entries

            controller.importFile(document(4), "protected-extended.json")
            val extended = controller.state.value.conversations.single { it.id == sourceId }
            val latest = current(extended)
            val addition = controller.addConversation(archiveId, extended, latest.id, latest.messages.map { it.id }.toSet())
            assertEquals(latest.messages.takeLast(2).map { it.id }, addition.messageIds)
            assertEquals(2, addition.alreadyIncludedCount)
            val result = controller.state.value.archives.single()
            protected.forEach { old -> assertEquals(old, result.entries.single { it.id == old.id }) }
            assertTrue(result.entries.single { it.id == initial.first().id }.let { it.edited && it.pinned })
            assertTrue(result.entries.single { it.id == initial.last().id }.deleted)
            assertEquals(4, result.entries.size)
            assertTrue(controller.state.value.tasks.isEmpty())
            assertTrue(network.requests.isEmpty())
            // Real generated original entries retain their edit/delete boundary even when
            // starting a fresh AI task would otherwise assign different output entry IDs.
            controller.organize(archiveId, sourceId, latest.id, branch.messages.map { it.id }.toSet())
            assertTrue(controller.state.value.tasks.isEmpty())
            assertTrue(network.requests.isEmpty())
            controller.organize(archiveId, sourceId, latest.id, latest.messages.map { it.id }.toSet())
            awaitTaskCount(controller, 1)
            assertEquals(latest.messages.takeLast(2), network.requests.single())
            protected.forEach { old ->
                assertEquals(old, controller.state.value.archives.single().entries.single { it.id == old.id })
            }
            BackupValidation.validate(backup(controller))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            application.deleteDatabase(name)
        }
    }

    @Test fun cancellingAiConfirmationDoesNotHideMissingOriginalTextOnTheNextAddition() = runBlocking<Unit> {
        val name = "incremental-original-fallback-${UUID.randomUUID()}.db"
        val network = FakeNetwork()
        val (store, controller) = controller(name, network)
        try {
            controller.backup()
            val sourceId = controller.importFile(document(2), "original-first.json").single()
            val original = controller.state.value.conversations.single { it.id == sourceId }
            val initialBranch = current(original)
            val archiveId = controller.createArchive("原文继续补入", original, initialBranch.id,
                initialBranch.messages.map { it.id }.toSet(), "")
            controller.importFile(document(4), "original-added.json")
            val extended = controller.state.value.conversations.single { it.id == sourceId }
            val latest = current(extended)
            val allIds = latest.messages.map { it.id }.toSet()
            val addedIds = latest.messages.takeLast(2).map { it.id }

            // Linking sources prepares AI confirmation, but does not yet add raw entries
            // or dispatch. Leaving that confirmation is a normal reversible operation.
            val prepared = controller.addConversation(archiveId, extended, latest.id, allIds, includeRawEntries = false)
            assertEquals(addedIds, prepared.messageIds)
            assertEquals("original", controller.state.value.archives.single().generationMode)
            assertEquals(2, controller.state.value.archives.single().entries.size)
            assertEquals(allIds, controller.state.value.archives.single().sourceScopes.single().selectedMessageIds.toSet())

            val retained = controller.addConversation(archiveId, extended, latest.id, allIds, includeRawEntries = true)
            assertEquals(addedIds, retained.messageIds)
            assertEquals(4, controller.state.value.archives.single().entries.size)
            assertTrue(addedIds.all { id -> controller.state.value.archives.single().entries.any { id in it.sourceIds } })
            assertEquals(0, controller.addConversation(archiveId, extended, latest.id, allIds).newMessageCount)
            assertTrue(controller.state.value.tasks.isEmpty())
            assertTrue(network.requests.isEmpty())
            BackupValidation.validate(backup(controller))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            application.deleteDatabase(name)
        }
    }

    private class FakeNetwork : OrganizationNetwork {
        val requests = CopyOnWriteArrayList<List<ChatMessage>>()
        val references = CopyOnWriteArrayList<List<ChatMessage>>()
        val quotaCalls = AtomicInteger()
        private val keys = CopyOnWriteArrayList<String>()

        override suspend fun createSession(settings: ServiceSettings) = CompanionSession("synthetic-token", quota(settings))
        override suspend fun quota(settings: ServiceSettings): CompanionQuota {
            quotaCalls.incrementAndGet()
            return CompanionQuota(true, null, null, "synthetic-model", "", accessMode = "public",
                dailyLimit = 500, dailyRemaining = 500, userDailyLimit = 50, userDailyRemaining = 50,
                contextTokens = 64000, maxOutputTokens = 4096, organizationProtocolVersion = 9)
        }
        override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String): RemoteJob =
            submit(settings, messages, key, emptyList())

        override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String,
            contextMessages: List<ChatMessage>): RemoteJob {
            val index = synchronized(keys) {
                val previous = keys.indexOf(key)
                if (previous >= 0) previous else {
                    keys += key
                    requests.add(messages)
                    references.add(contextMessages)
                    keys.lastIndex
                }
            }
            return RemoteJob("incremental-remote-$index", "completed", listOf(ArchiveEntry(
                "incremental-result-$index", EntryCategory.RECENT, "合成整理结果$index",
                messages.map { it.id }, kind = EntryKind.UNCONFIRMED)))
        }
        override suspend fun poll(settings: ServiceSettings, remoteId: String): RemoteJob =
            error("This synchronous fixture never polls a remote service")
        override suspend fun cancel(settings: ServiceSettings, remoteId: String) = RemoteJob(remoteId, "cancelled")
        override suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String): List<ArchiveEntry> =
            error("This fixture uses only the synthetic companion boundary")
    }
}
