package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ConversationStorageTest {
    private fun sample(): ImportedConversation {
        val shared = ChatMessage("root", "user", "保留引号\"、换行\n和emoji🐳", "today", listOf("file.pdf"))
        return ImportedConversation("c", "title", "created", "updated", listOf(
            ConversationBranch("a", "first", listOf(shared, ChatMessage("a", "assistant", "答复A")), false),
            ConversationBranch("b", "current", listOf(shared, ChatMessage("b", "assistant", "答复B")), true)), "b")
    }

    private fun encoded(value: ImportedConversation): String = ByteArrayOutputStream().use {
        ConversationStorage.write(value, it)
        it.toString("UTF-8")
    }

    @Test fun compactAndLegacyPayloadsRestoreEveryFieldAndSharedReferences() {
        val expected = sample()
        for (payload in listOf(encoded(expected), appJson.encodeToString(expected))) {
            val restored = ConversationStorage.read(payload)
            assertEquals(expected, restored)
            assertSame(restored.branches[0].messages[0], restored.branches[1].messages[0])
        }
        assertEquals(hash(appJson.encodeToString(expected)), serializedHash(ImportedConversation.serializer(), expected))
    }

    @Test fun conflictingMessageIdsAreRejectedInsteadOfDroppingOneBody() {
        val value = sample()
        val broken = value.copy(branches = value.branches + ConversationBranch("bad", "bad", listOf(ChatMessage("root", "user", "changed"))))
        assertTrue(runCatching { encoded(broken) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ConversationStorage.read(appJson.encodeToString(broken)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun malformedIndicesAndOrphansAreRejected() {
        val payload = encoded(sample())
        val invalid = listOf(
            payload.replace("\"storageVersion\":1", "\"storageVersion\":9"),
            payload.replace("\"messageIndices\":[0,1]", "\"messageIndices\":[0,99]"),
            payload.replace("\"messageIndices\":[0,1]", "\"messageIndices\":[0,0]"),
            payload.replace("\"messageIndices\":[0,1]", "\"messageIndices\":[0]"),
            payload.replace("\"currentBranchId\":\"b\"", "\"currentBranchId\":\"missing\""),
        )
        invalid.forEach { assertTrue(runCatching { ConversationStorage.read(it) }.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test(timeout = 20_000) fun boundedPipesPropagateErrorsAndCancellationBeforeCodecStarts() = runBlocking {
        val source = ("跨分片🐳\n".repeat(30_000)).toByteArray()
        assertArrayEquals(source, withJsonWriter(write = { it.write(source) }) { it.readBytes() })
        assertArrayEquals(source, withJsonReader(write = { it.write(source) }) { it.readBytes() })
        assertNotNull(runCatching {
            withJsonWriter(write = { throw IllegalArgumentException("synthetic") }) { it.readBytes() }
        }.exceptionOrNull())
        assertNotNull(runCatching {
            withJsonReader(write = { it.write(source) }) { throw IOException("synthetic") }
        }.exceptionOrNull())

        for (reading in listOf(false, true)) {
            val executor = Executors.newSingleThreadExecutor()
            val release = CountDownLatch(1)
            executor.submit { release.await() }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val entered = CompletableDeferred<Unit>()
                val operation = async(Dispatchers.Default) {
                    if (reading) withJsonReader(write = {
                        entered.complete(Unit)
                        it.write(source)
                    }, codecDispatcher = dispatcher) { it.readBytes() }
                    else withJsonWriter(write = { it.write(source) }, codecDispatcher = dispatcher) {
                        entered.complete(Unit)
                        it.readBytes()
                    }
                }
                withTimeout(2_000) { entered.await() }
                // The codec is still queued behind the latch. Closing on completion
                // must unblock the pipe even though its use{} body never ran.
                operation.cancel()
                release.countDown()
                withTimeout(2_000) { operation.cancelAndJoin() }
            } finally {
                release.countDown()
                dispatcher.close()
                executor.awaitTermination(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun branchHeavyRoundTripFits256MiBWithoutDuplicatingMessageBodies() {
        val retainedAccount = ByteArray(128 * 1024 * 1024) { 7 }
        val common = List(624) { index -> ChatMessage("m$index", "user", "[$index]" + "abcdef🐳".repeat(2_000)) }
        val branches = (0 until 249).map { index ->
            ConversationBranch("b$index", "branch $index", common + ChatMessage("leaf$index", "assistant", "leaf $index"))
        } + ConversationBranch("last", "current", common + List(76) { ChatMessage("tail$it", "assistant", "tail $it") }, true)
        val original = ImportedConversation("pressure", "shared prefixes", branches = branches, currentBranchId = "last")
        assertEquals(156_325, original.branches.sumOf { it.messages.size })
        val file = File.createTempFile("conversation-storage-test-", ".json")
        try {
            file.outputStream().use { ConversationStorage.write(original, it) }
            assertTrue("stored JSON should scale with unique messages", file.length() < 20 * 1024 * 1024)
            val decoded = file.inputStream().use(ConversationStorage::read)
            assertEquals(original, decoded)
            assertSame(decoded.branches.first().messages.first(), decoded.branches.last().messages.first())
            assertEquals(7, retainedAccount.last().toInt())
        } finally { file.delete() }
    }
}
