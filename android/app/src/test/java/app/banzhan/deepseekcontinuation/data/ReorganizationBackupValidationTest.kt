package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ReorganizationBackupValidationTest {
    private val message = ChatMessage("m1", "user", "Synthetic source only.")
    private val conversation = ImportedConversation("conversation", "合成对话", branches = listOf(
        ConversationBranch("main", "当前", listOf(message), true)), currentBranchId = "main")
    private val archive = Archive("rebuilt", "新版档案", listOf(conversation.id), emptyList(),
        updatedAt = 1, rebuiltFromArchiveId = "deleted-parent")
    private val task = TaskRecord("rebuild-task", archive.id, "重整", ServiceSettings(), "",
        listOf(TaskChunk("chunk", listOf(message), "isolated-cache")), status = "paused",
        reorganizationOfArchiveId = archive.rebuiltFromArchiveId)

    private fun fixture(value: Archive = archive, pending: TaskRecord = task) = BackupDocument(
        settings = ServiceSettings(), sources = emptyList(),
        conversations = listOf(ConversationRow(conversation.id, appJson.encodeToString(conversation))),
        archives = listOf(ArchiveRow(value.id, value.updatedAt, appJson.encodeToString(value))),
        revisions = emptyList(), conflicts = emptyList(),
        tasks = listOf(TaskRow(pending.id, value.id, 1, appJson.encodeToString(pending))), cache = emptyList())

    @Test fun deletedParentIsSoftProvenanceButTaskAndChildMustStillAgree() {
        val restored = appJson.decodeFromString<BackupDocument>(appJson.encodeToString(fixture()))
        BackupValidation.validate(restored)
        assertFalse(restored.archives.any { it.id == "deleted-parent" })
        assertEquals("deleted-parent", appJson.decodeFromString<Archive>(restored.archives.single().payload).rebuiltFromArchiveId)
        assertEquals("deleted-parent", restored.tasks.single().decodeTask().reorganizationOfArchiveId)
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidation.validate(fixture(pending = task.copy(reorganizationOfArchiveId = "different-parent")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidation.validate(fixture(value = archive.copy(rebuiltFromArchiveId = archive.id)))
        }
    }

    @Test fun newBackupWritesVersionThreeAndLegacyVersionOneAndTwoStillReadWithDefaults() {
        val encoded = appJson.encodeToString(fixture())
        val wireVersion = appJson.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.int
        assertEquals(3, wireVersion)
        // This is the former reader's version gate, not a claim that its binary was executed.
        assertFalse("The v1..2 reader must reject rebuild semantics before interpreting tasks", wireVersion in 1..2)

        val oldArchive = JsonObject(appJson.parseToJsonElement(appJson.encodeToString(archive)).jsonObject
            .filterKeys { it !in setOf("rebuiltFromArchiveId", "continuity", "sourceScopes") }).toString()
        val oldTask = JsonObject(appJson.parseToJsonElement(appJson.encodeToString(task)).jsonObject
            .filterKeys { it !in setOf("reorganizationOfArchiveId", "promptVersion") }).toString()
        for (version in 1..2) {
            val old = fixture().copy(version = version,
                archives = listOf(ArchiveRow(archive.id, archive.updatedAt, oldArchive)),
                tasks = listOf(TaskRow(task.id, archive.id, 1, oldTask)))
            val restored = appJson.decodeFromString<BackupDocument>(appJson.encodeToString(old))
            BackupValidation.validate(restored)
            val restoredArchive = appJson.decodeFromString<Archive>(restored.archives.single().payload)
            val restoredTask = restored.tasks.single().decodeTask()
            assertNull(restoredArchive.rebuiltFromArchiveId)
            assertFalse(restoredArchive.continuity.enabled)
            assertTrue(restoredArchive.sourceScopes.isEmpty())
            assertNull(restoredTask.reorganizationOfArchiveId)
            assertEquals(SummaryProtocol.LEGACY_PROMPT_VERSION, restoredTask.promptVersion)
        }
    }
}
