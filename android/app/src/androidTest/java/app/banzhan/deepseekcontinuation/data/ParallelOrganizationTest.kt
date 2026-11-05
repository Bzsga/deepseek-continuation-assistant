package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityCue
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.EntryKind
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real controller/Room writes with synthetic sources and a network that never opens a socket. */
@RunWith(AndroidJUnit4::class)
class ParallelOrganizationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application

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

    private suspend fun seed(controller: AppController): String {
        controller.backup() // Wait for initialization; this test has its own database.
        controller.saveSettings(ServiceSettings(companionUrl = "https://synthetic.invalid", contextTokens = 64000, maxOutputTokens = 4096))
        val source = (0..6).joinToString("") { "$it" + "甲".repeat(5999) }
        val input = buildJsonArray { add(buildJsonObject {
            put("id", "parallel-synthetic")
            put("title", "并行合成检查")
            putJsonArray("messages") { add(buildJsonObject {
                put("id", "source")
                put("role", "user")
                put("content", source)
            }) }
        }) }.toString().toByteArray()
        controller.importFile(input, "parallel-synthetic.json")
        val conversation = controller.state.value.conversations.single()
        val branch = conversation.branches.single()
        val selected = branch.messages.map { it.id }.toSet()
        val archiveId = controller.createArchive("并行合成检查", conversation, branch.id, selected, "", includeRawEntries = false)
        controller.saveEntry(archiveId, ArchiveEntry("manual", EntryCategory.BACKGROUND,
            "人工置顶内容", listOf(branch.messages.single().id), pinned = true, kind = EntryKind.USER_FACT))
        controller.organize(archiveId, conversation.id, branch.id, selected)
        return controller.state.value.tasks.single().id
    }

    private suspend fun task(controller: AppController): TaskRecord =
        appJson.decodeFromString<BackupDocument>(controller.backup()).tasks.single().decodeTask()

    private suspend fun awaitCondition(predicate: suspend () -> Boolean) = withTimeout(20_000) {
        while (!predicate()) delay(15)
    }

    @Test fun reverseCompletionPersistsImmediatelyAndMergesInOriginalOrder() = runBlocking<Unit> {
        val network = FakeNetwork()
        val (store, controller) = controller("parallel-order-${UUID.randomUUID()}.db", network)
        try {
            seed(controller)
            val archiveBefore = controller.state.value.archives.single()
            val continuity = archiveBefore.continuity.copy(enabled = false, includeRecent = false,
                selectedFragmentIds = emptyList(), interactionNotes = "本人填写的交流方式", nextMessage = "本人下一句话")
            controller.saveContinuitySettings(archiveBefore.id, continuity)
            awaitCondition { network.keys.size == 2 }
            val original = task(controller)
            assertEquals(7, original.chunks.size)
            assertEquals((0..1).toSet(), network.keys.map { network.indexByKey.getValue(it) }.toSet())
            assertTrue(original.chunks.take(2).all { it.requestStarted })
            assertTrue(original.chunks.drop(2).none { it.requestStarted })
            network.finish(1)
            awaitCondition { controller.state.value.tasks.single().done == 1 && network.keys.size == 3 }
            assertEquals("片段1", controller.state.value.archives.single().entries.single { it.id != "manual" }.text)
            for (index in listOf(0, 2, 3, 4, 5, 6)) network.finish(index)
            awaitCondition { controller.state.value.tasks.single().status == "completed" }
            val persisted = task(controller)
            assertTrue(persisted.chunks.all { it.completed && !it.inFlight && it.remoteJobId.isNotBlank() })
            val archive = controller.state.value.archives.single()
            assertEquals((0..6).joinToString("\n") { "片段$it" }, archive.entries.single { it.id != "manual" }.text)
            assertEquals("人工置顶内容", archive.entries.single { it.id == "manual" }.text)
            assertTrue(archive.entries.single { it.id == "manual" }.pinned)
            assertEquals(7, network.keys.distinct().size)
            assertEquals(continuity, archive.continuity)
            val generated = archive.entries.single { it.id != "manual" }
            assertEquals(ContinuityCue.OPEN_THREAD, generated.continuityCue)
            controller.saveEntry(archive.id, generated.copy(pinned = true))
            val pinned = controller.state.value.archives.single().entries.single { it.id == generated.id }
            assertFalse(pinned.edited)
            assertEquals(ContinuityCue.OPEN_THREAD, pinned.continuityCue)
            controller.saveEntry(archive.id, pinned.copy(text = "本人修改的正文"))
            val edited = controller.state.value.archives.single().entries.single { it.id == generated.id }
            assertTrue(edited.edited)
            assertNull(edited.continuityCue)
            assertEquals(continuity, controller.state.value.archives.single().continuity)
            assertEquals(persisted, task(controller))
        } finally {
            controller.state.value.tasks.forEach { controller.cancelTask(it.id) }
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun failedWindowDrainsAndRecreationRecoversOriginalIdentitiesWithZeroQuota() = runBlocking<Unit> {
        val name = "parallel-resume-${UUID.randomUUID()}.db"
        val network = FakeNetwork(loseFirstSubmission = true)
        val (firstStore, first) = controller(name, network)
        var secondStore: ViewModelStore? = null
        var second: AppController? = null
        try {
            val taskId = seed(first)
            awaitCondition { network.keys.size == 2 }
            network.finish(1)
            awaitCondition { first.state.value.tasks.single().done == 1 && network.keys.size == 3 }
            network.releaseLostPost.complete(Unit)
            network.results.getValue(2).completeExceptionally(SummaryFailure("合成轮询中断", ambiguous = false))
            awaitCondition { first.state.value.tasks.single().status == "paused" }
            val before = task(first)
            assertEquals(1, before.chunks.count { it.completed })
            assertEquals(3, network.keys.size)
            assertTrue(before.uncertain)
            assertTrue(before.chunks[0].requestStarted)
            assertEquals("", before.chunks[0].remoteJobId)
            assertEquals("remote-2", before.chunks[2].remoteJobId)
            val originalKey = network.keys.single { network.indexByKey[it] == 0 }
            instrumentation.runOnMainSync { firstStore.clear() }

            network.quotaRemaining = 0
            network.quotaModel = "changed-synthetic-model"
            network.results[2] = CompletableDeferred()
            network.finish(0)
            network.finish(2)
            val recreated = controller(name, network)
            secondStore = recreated.first
            second = recreated.second
            second.backup()
            assertEquals(before.chunks, task(second).chunks)
            second.resumeTask(taskId, acknowledgeCost = true)
            awaitCondition { second.state.value.tasks.single().status == "paused" && second.state.value.tasks.single().done == 3 }
            val after = task(second)
            assertEquals(4, network.keys.size) // One same-key recovery; no fresh fourth chunk.
            assertEquals(2, network.keys.count { it == originalKey })
            assertEquals(3, network.keys.distinct().size)
            assertTrue(network.polls.count { it == "remote-2" } >= 2)
            assertEquals(before.chunks.map { it.id }, after.chunks.map { it.id })
            assertEquals(before.chunks.map { it.attempt }, after.chunks.map { it.attempt })
            assertTrue(after.chunks.take(3).all { it.completed })
            assertTrue(after.chunks.drop(3).none { it.requestStarted })
            assertEquals((0..2).joinToString("\n") { "片段$it" }, second.state.value.archives.single().entries.single { it.id != "manual" }.text)
        } finally {
            val current = second ?: first
            current.state.value.tasks.forEach { current.cancelTask(it.id) }
            instrumentation.runOnMainSync { firstStore.clear(); secondStore?.clear() }
        }
    }

    @Test fun cancelVisitsEveryRemoteAndKeepsAResultThatFinishedBeforeCancellation() = runBlocking<Unit> {
        val network = FakeNetwork()
        val (store, controller) = controller("parallel-cancel-${UUID.randomUUID()}.db", network)
        try {
            val taskId = seed(controller)
            awaitCondition { task(controller).chunks.take(2).all { it.remoteJobId.isNotBlank() } }
            network.failCancel = 0
            network.completeOnCancel = 1
            controller.cancelTask(taskId)
            val saved = task(controller)
            assertEquals((0..1).map { "remote-$it" }.toSet(), network.cancels.toSet())
            assertEquals(2, network.keys.size)
            assertEquals("cancelled", saved.status)
            assertTrue(saved.cancelRequested)
            assertTrue(saved.chunks[1].completed)
            assertEquals("remote-0", saved.chunks[0].remoteJobId)
            assertEquals("片段1", controller.state.value.archives.single().entries.single { it.id != "manual" }.text)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private class FakeNetwork(private val loseFirstSubmission: Boolean = false) : OrganizationNetwork {
        val keys = CopyOnWriteArrayList<String>()
        val indexByKey = ConcurrentHashMap<String, Int>()
        val sources = ConcurrentHashMap<Int, List<ChatMessage>>()
        val polls = CopyOnWriteArrayList<String>()
        val cancels = CopyOnWriteArrayList<String>()
        val results = ConcurrentHashMap<Int, CompletableDeferred<Unit>>().apply {
            for (index in 0..6) put(index, CompletableDeferred())
        }
        val releaseLostPost = CompletableDeferred<Unit>()
        @Volatile var quotaRemaining = 50
        @Volatile var quotaModel = "synthetic-model"
        @Volatile var failCancel = -1
        @Volatile var completeOnCancel = -1

        private fun completed(index: Int) = RemoteJob("remote-$index", "completed", listOf(
            ArchiveEntry("entry-$index", EntryCategory.RECENT, "片段$index",
                sources.getValue(index).map { it.id }, kind = EntryKind.USER_FACT, continuityCue = ContinuityCue.OPEN_THREAD)))

        fun finish(index: Int) { results.getValue(index).complete(Unit) }

        override suspend fun createSession(settings: ServiceSettings) = CompanionSession("synthetic-token", quota(settings))
        override suspend fun quota(settings: ServiceSettings) = CompanionQuota(true, null, null, quotaModel, "",
            dailyLimit = 500, dailyRemaining = quotaRemaining, userDailyLimit = 50, userDailyRemaining = quotaRemaining,
            accessMode = "public", contextTokens = 64000, maxOutputTokens = 4096)

        override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String): RemoteJob {
            val index = messages.first().content.first().digitToInt()
            val first = indexByKey.putIfAbsent(key, index) == null
            sources[index] = messages
            keys += key
            if (index == 0 && loseFirstSubmission && first) {
                releaseLostPost.await()
                throw SummaryFailure("合成POST响应丢失", ambiguous = true)
            }
            return RemoteJob("remote-$index", "queued")
        }

        override suspend fun poll(settings: ServiceSettings, remoteId: String): RemoteJob {
            polls += remoteId
            val index = remoteId.substringAfterLast('-').toInt()
            results.getValue(index).await()
            return completed(index)
        }

        override suspend fun cancel(settings: ServiceSettings, remoteId: String): RemoteJob {
            cancels += remoteId
            val index = remoteId.substringAfterLast('-').toInt()
            if (index == failCancel) throw SummaryFailure("合成取消失败", ambiguous = false)
            return if (index == completeOnCancel) completed(index) else RemoteJob(remoteId, "cancelled")
        }

        override suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String): List<ArchiveEntry> =
            error("BYOK must not be used by this fixture")
    }
}
