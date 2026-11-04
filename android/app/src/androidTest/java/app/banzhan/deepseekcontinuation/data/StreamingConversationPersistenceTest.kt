package app.banzhan.deepseekcontinuation.data

import android.util.Base64
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local data only. Never opens an account export or sends model requests. */
@RunWith(AndroidJUnit4::class)
class StreamingConversationPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun largeSingleConversationAndOriginalBytesSurviveReopenAndLegacyExport() = runBlocking<Unit> {
        val name = "streaming-conversation-device-test.db"
        context.deleteDatabase(name)
        val expected = largeConversation()
        assertTrue(expected.branches.single().messages.sumOf { it.content.length.toLong() } >= 12_000_000L)
        var database = open(name)
        try {
            database.records().putConversation(expected)
            val sourceDigest = putSyntheticSource(database.records())
            assertTrue(partCount(database, expected.id) > 1)
            database.close()
            database = open(name)

            // These helpers return before the next large representation is materialized.
            verifyImportedMessages(database.records(), expected)
            verifyLegacyExport(database.records(), expected)
            val source = database.records().sources().single()
            assertEquals("synthetic-source", source.id)
            assertEquals("synthetic-original.zip", source.fileName)
            assertEquals(1234L, source.importedAt)
            val restoredBytes = Base64.decode(source.bytesBase64, Base64.NO_WRAP)
            assertEquals(SOURCE_BYTES, restoredBytes.size)
            assertArrayEquals(sourceDigest, MessageDigest.getInstance("SHA-256").digest(restoredBytes))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun legacyRowsRemainReadableAndFailedReplacementRestoresAllSegments() = runBlocking<Unit> {
        val name = "streaming-conversation-rollback-test.db"
        context.deleteDatabase(name)
        val database = open(name)
        try {
            val dao = database.records()
            val inline = conversation("legacy-inline", "原始短消息🐳\n\"引号\"")
            val segmented = conversation("legacy-segmented", "旧版分段🐳\n\"引用\"\u0000".repeat(14_000))
            dao.put(ConversationRow(inline.id, appJson.encodeToString(inline)))
            dao.put(ConversationRow(segmented.id, appJson.encodeToString(segmented)))
            assertEquals(0, partCount(database, inline.id))
            assertTrue(partCount(database, segmented.id) > 1)
            assertEquals(inline, dao.readConversation(inline.id))
            assertEquals(segmented, dao.readConversation(segmented.id))

            val beforeParts = partSnapshot(database, segmented.id)
            val replacement = segmented.copy(title = "transaction must roll back", branches = listOf(
                segmented.branches.single().copy(messages = listOf(ChatMessage("replacement", "assistant", "替换正文"))),
            ))
            try {
                database.withTransaction {
                    dao.putConversation(replacement)
                    assertEquals(replacement, dao.readConversation(segmented.id))
                    throw IllegalStateException("synthetic rollback")
                }
                fail("the synthetic transaction must fail")
            } catch (failure: IllegalStateException) {
                assertEquals("synthetic rollback", failure.message)
            }
            assertEquals(beforeParts, partSnapshot(database, segmented.id))
            assertEquals(segmented, dao.readConversation(segmented.id))
            assertEquals(inline, dao.readConversation(inline.id))
            assertEquals(setOf(inline.id, segmented.id), dao.conversationIds().toSet())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun open(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2).build()

    private fun largeConversation(): ImportedConversation {
        val text = buildString {
            while (length < 12_000) append("保留原文🐳 \"引号\"\\路径\n\t\b\r\u0000\u001f尾部|")
        }
        // Sharing fixture text avoids constructing a second account-sized JSON or body in the test.
        val messages = List(1000) { index ->
            ChatMessage("synthetic-message-$index", if (index % 2 == 0) "user" else "assistant", text,
                timestamp = "synthetic-time-$index", attachments = listOf("synthetic-attachment-$index"))
        }
        return ImportedConversation("single-large-conversation", "大单会话合成记录", "created", "updated",
            listOf(ConversationBranch("synthetic-current", "当前版本", messages, true)), "synthetic-current")
    }

    private fun conversation(id: String, text: String) = ImportedConversation(id, id,
        branches = listOf(ConversationBranch("$id:branch", "当前", listOf(ChatMessage("$id:message", "user", text)), true)),
        currentBranchId = "$id:branch")

    private suspend fun verifyImportedMessages(dao: AppDao, expected: ImportedConversation) {
        val actual = dao.readConversation(expected.id)
        assertNotNull(actual)
        verifyEveryMessage(expected, requireNotNull(actual))
    }

    private suspend fun verifyLegacyExport(dao: AppDao, expected: ImportedConversation) {
        val exported = dao.conversations().single()
        assertEquals(expected.id, exported.id)
        assertTrue("legacy export must contain JSON rather than an internal segment marker", exported.payload.startsWith("{"))
        verifyEveryMessage(expected, ConversationStorage.read(exported.payload))
    }

    private fun verifyEveryMessage(expected: ImportedConversation, actual: ImportedConversation) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.title, actual.title)
        assertEquals(expected.createdAt, actual.createdAt)
        assertEquals(expected.updatedAt, actual.updatedAt)
        assertEquals(expected.currentBranchId, actual.currentBranchId)
        assertEquals(expected.branches.size, actual.branches.size)
        expected.branches.indices.forEach { branchIndex ->
            val original = expected.branches[branchIndex]
            val restored = actual.branches[branchIndex]
            assertEquals(original.id, restored.id)
            assertEquals(original.label, restored.label)
            assertEquals(original.isCurrent, restored.isCurrent)
            assertEquals(original.messages.size, restored.messages.size)
            original.messages.indices.forEach { messageIndex ->
                assertEquals("source message $branchIndex:$messageIndex", original.messages[messageIndex], restored.messages[messageIndex])
            }
        }
    }

    private suspend fun putSyntheticSource(dao: AppDao): ByteArray {
        val bytes = ByteArray(SOURCE_BYTES) { index -> (index * 31 + index / 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        dao.put(SourceRow("synthetic-source", "synthetic-original.zip", Base64.encodeToString(bytes, Base64.NO_WRAP), 1234L))
        return digest
    }

    private fun partCount(database: AppDatabase, id: String): Int = database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM payload_parts WHERE owner = ? AND recordId = ?", arrayOf("conversations", id),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun partSnapshot(database: AppDatabase, id: String): List<Pair<Int, String>> =
        database.openHelper.readableDatabase.query(
            "SELECT part, content FROM payload_parts WHERE owner = ? AND recordId = ? ORDER BY part",
            arrayOf("conversations", id),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getString(1)) }
        }

    private companion object { const val SOURCE_BYTES = 11_798_524 }
}

