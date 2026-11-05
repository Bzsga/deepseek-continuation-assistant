package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** QA emulator only. Run import/reopen stages separately with a force-stop between them. */
@RunWith(AndroidJUnit4::class)
class BranchedImportRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application

    private fun controller(): Pair<ViewModelStore, AppController> {
        val store = ViewModelStore()
        lateinit var controller: AppController
        instrumentation.runOnMainSync {
            controller = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[AppController::class.java]
        }
        return store to controller
    }

    @Test fun import24ChatsWith156325BranchMessageReferences() = runBlocking {
        val (store, controller) = controller()
        try {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings(mode = "byok"))
            DiagnosticLog.bindAnonymousIdentity("byok", "", "")
            val zip = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { output ->
                    output.putNextEntry(ZipEntry("conversations.json"))
                    output.write("[{\"id\":\"heavy\",\"title\":\"Shared long prefixes\",\"current_node\":\"m925\",\"mapping\":{".toByteArray())
                    repeat(926) { index ->
                        if (index > 0) output.write(','.code)
                        output.write("\"m$index\":".toByteArray())
                        val parent = when {
                            index == 0 -> null
                            index < 624 -> "m${index - 1}"
                            index <= 873 -> "m623"
                            else -> "m${index - 1}"
                        }
                        val node = buildJsonObject {
                            put("id", "m$index")
                            put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
                            put("role", "user")
                            put("content", body(index))
                        }
                        output.write(node.toString().toByteArray())
                    }
                    output.write("}}".toByteArray())
                    repeat(23) { item ->
                        val index = 926 + item
                        output.write(','.code)
                        output.write(buildJsonObject {
                            put("id", "single-$index")
                            put("title", "Single $index")
                            put("messages", buildJsonArray { add(buildJsonObject {
                                put("id", "m$index"); put("role", "user"); put("content", body(index))
                            }) })
                        }.toString().toByteArray())
                    }
                    output.write(']'.code)
                    output.closeEntry()
                }
            }.toByteArray()
            assertTrue(zip.size in 10 * 1024 * 1024..14 * 1024 * 1024)
            withTimeout(90_000) { controller.importFile(zip, "synthetic-branched.zip") }
            verify(controller)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun coldReopenAndBackupRestorePreserveBranchesAndEdits() = runBlocking {
        val (store, controller) = controller()
        try {
            withTimeout(90_000) { controller.revisions("wait-for-startup") }
            verify(controller)
            controller.state.value.archives.filter { it.title == "preserved edit" }.forEach { controller.deleteArchive(it.id) }
            val conversation = controller.state.value.conversations.single { it.title == "Shared long prefixes" }
            val branch = conversation.branches.first()
            val archiveId = controller.createArchive("preserved edit", conversation, branch.id,
                branch.messages.take(2).map { it.id }.toSet(), "manual background")
            val entry = controller.state.value.archives.single().entries.first()
            controller.saveEntry(archiveId, entry.copy(text = "保留人工修订", pinned = true))
            val backup = controller.backupFile()
            try {
                val prefix = backup.inputStream().use { input -> ByteArray(160).let { it.copyOf(input.read(it)).toString(Charsets.UTF_8) } }
                assertTrue(prefix.contains("\"version\":3"))
                withTimeout(90_000) { backup.inputStream().use { controller.restoreBackup(it) } }
            } finally { backup.delete() }
            verify(controller)
            assertEquals("保留人工修订", controller.state.value.archives.single().entries.first().text)
            assertTrue(controller.state.value.archives.single().entries.first().pinned)

            val legacy = ImportedConversation("legacy", "Old backup", branches = listOf(
                ConversationBranch("legacy-branch", "current", listOf(ChatMessage("legacy-message", "user", "旧版备份🐳")), true)),
                currentBranchId = "legacy-branch")
            appJson.encodeToString(BackupDocument(version = 1, settings = ServiceSettings(mode = "byok"),
                sources = emptyList(), conversations = listOf(ConversationRow(legacy.id, appJson.encodeToString(legacy))),
                archives = emptyList(), revisions = emptyList(), conflicts = emptyList(), tasks = emptyList(), cache = emptyList()))
                .byteInputStream().use { controller.restoreBackup(it) }
            assertEquals(legacy, controller.state.value.conversations.single())
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings())
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun roomStreamingRollsBackOnCancellationAndCodecFailure() = runBlocking {
        val name = "conversation-stream-rollback.db"
        application.deleteDatabase(name)
        val db = Room.databaseBuilder(application, AppDatabase::class.java, name).build()
        val original = ImportedConversation("c", "old", branches = listOf(
            ConversationBranch("b", "branch", listOf(ChatMessage("m", "user", "保留🐳".repeat(30_000))), true)), currentBranchId = "b")
        try {
            // The old schema-2 string payload remains readable.
            db.records().put(ConversationRow(original.id, appJson.encodeToString(original)))
            assertEquals(original, db.records().readConversation("c"))
            assertNotNull(runCatching {
                db.withTransaction {
                    db.records().putConversation(original.copy(title = "cancelled"))
                    throw CancellationException("synthetic cancellation")
                }
            }.exceptionOrNull())
            assertEquals(original, db.records().readConversation("c"))
            val invalid = original.copy(branches = original.branches + ConversationBranch("bad", "bad",
                listOf(ChatMessage("m", "assistant", "conflicting body"))))
            assertNotNull(runCatching { db.records().putConversation(invalid) }.exceptionOrNull())
            assertEquals(original, db.records().readConversation("c"))
            db.records().putConversation(original)
            assertEquals(original, db.records().readConversation("c"))
        } finally { db.close(); application.deleteDatabase(name) }
    }

    private fun verify(controller: AppController) {
        val conversations = controller.state.value.conversations
        assertEquals(24, conversations.size)
        assertEquals(156_325, conversations.sumOf { it.branches.sumOf { branch -> branch.messages.size } })
        val heavy = conversations.single { it.title == "Shared long prefixes" }
        assertEquals(250, heavy.branches.size)
        assertEquals(677, heavy.branches.single { it.isCurrent }.messages.size)
        assertSame(heavy.branches.first().messages.first(), heavy.branches.last().messages.first())
        val unique = conversations.asSequence().flatMap { it.branches }.flatMap { it.messages }.distinctBy { it.id }.toList()
        assertEquals(949, unique.size)
        unique.forEach { message -> assertEquals(body(message.content.substringBefore('|').toInt()), message.content) }
    }

    private fun body(index: Int): String {
        val random = Random(index.toLong())
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        return "$index|" + String(CharArray(16_000) { alphabet[random.nextInt(alphabet.length)] }) + "末尾🐳"
    }
}
