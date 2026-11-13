package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityCue
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.EntryCategory
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
        promptVersion = "continuation-v1.2",
        reorganizationOfArchiveId = archive.rebuiltFromArchiveId)

    private fun fixture(value: Archive = archive, pending: TaskRecord = task,
        source: ImportedConversation = conversation) = BackupDocument(
        settings = ServiceSettings(), sources = emptyList(),
        conversations = listOf(ConversationRow(source.id, appJson.encodeToString(source))),
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

    @Test fun versionFourRetainsResponseStyleDisabledSettingAndOriginalTaskPrompt() {
        val styleMessage = ChatMessage("style-source", "assistant", "我会先核对你的意思，再分点回应。")
        val secondStyleMessage = ChatMessage("style-source-2", "assistant", "我先复述刚才的问题，再解释我的判断。")
        val source = conversation.copy(branches = listOf(conversation.branches.single().copy(
            messages = listOf(message, styleMessage, secondStyleMessage))))
        val withStyle = archive.copy(entries = listOf(ArchiveEntry("style", EntryCategory.RECENT,
            "先核对意思，再分点回应。", listOf(styleMessage.id, secondStyleMessage.id), continuityCue = ContinuityCue.RESPONSE_STYLE)),
            continuity = archive.continuity.copy(includeResponseStyle = false))
        val restored = appJson.decodeFromString<BackupDocument>(appJson.encodeToString(fixture(withStyle, source = source).copy(version = 4)))
        assertEquals(4, restored.version)
        BackupValidation.validate(restored)
        val restoredArchive = appJson.decodeFromString<Archive>(restored.archives.single().payload)
        assertEquals(ContinuityCue.RESPONSE_STYLE, restoredArchive.entries.single().continuityCue)
        assertFalse(restoredArchive.continuity.includeResponseStyle)
        assertEquals(withStyle, restoredArchive)
        assertEquals(task, restored.tasks.single().decodeTask())
        assertEquals("continuation-v1.2", restored.tasks.single().decodeTask().promptVersion)
    }

    @Test fun newBackupWritesVersionSixAndLegacyVersionsOneThroughThreeStillReadWithDefaults() {
        val encoded = appJson.encodeToString(fixture())
        val wireVersion = appJson.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.int
        assertEquals(6, wireVersion)
        // This is the former reader's version gate, not a claim that its binary was executed.
        assertFalse("The v1..5 reader must reject scoped initial references before interpreting them", wireVersion in 1..5)

        val oldArchiveFields = appJson.parseToJsonElement(appJson.encodeToString(archive.copy(
            continuity = archive.continuity.copy(includeResponseStyle = false)))).jsonObject
            .filterKeys { it !in setOf("rebuiltFromArchiveId", "sourceScopes") }
        val oldContinuity = JsonObject(oldArchiveFields.getValue("continuity").jsonObject
            .filterKeys { it != "includeResponseStyle" })
        val oldTaskFields = appJson.parseToJsonElement(appJson.encodeToString(task)).jsonObject
            .filterKeys { it != "reorganizationOfArchiveId" }
        for (version in 1..3) {
            val oldArchive = JsonObject(if (version == 1) oldArchiveFields.filterKeys { it != "continuity" }
                else oldArchiveFields + ("continuity" to oldContinuity)).toString()
            val oldTask = JsonObject(if (version == 3) oldTaskFields
                else oldTaskFields.filterKeys { it != "promptVersion" }).toString()
            val old = fixture().copy(version = version,
                archives = listOf(ArchiveRow(archive.id, archive.updatedAt, oldArchive)),
                tasks = listOf(TaskRow(task.id, archive.id, 1, oldTask)))
            val restored = appJson.decodeFromString<BackupDocument>(appJson.encodeToString(old))
            BackupValidation.validate(restored)
            val restoredArchive = appJson.decodeFromString<Archive>(restored.archives.single().payload)
            val restoredTask = restored.tasks.single().decodeTask()
            assertNull(restoredArchive.rebuiltFromArchiveId)
            assertFalse(restoredArchive.continuity.enabled)
            assertTrue(restoredArchive.continuity.includeResponseStyle)
            assertTrue(restoredArchive.sourceScopes.isEmpty())
            assertNull(restoredTask.reorganizationOfArchiveId)
            assertEquals(if (version == 3) "continuation-v1.2" else SummaryProtocol.LEGACY_PROMPT_VERSION,
                restoredTask.promptVersion)
        }
    }
}
