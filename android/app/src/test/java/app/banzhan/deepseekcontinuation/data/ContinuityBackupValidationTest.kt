package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuitySettings
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ContinuityBackupValidationTest {
    private val first = ChatMessage("m1", "user", "请先回应我刚才的意思。")
    private val reply = ChatMessage("m2", "assistant", "我会先确认你的意思。")
    private val other = ChatMessage("other", "user", "未选择的其他分支。")
    private val conversation = ImportedConversation("c", "合成来源", branches = listOf(
        ConversationBranch("main", "当前", listOf(first, reply), true),
        ConversationBranch("other-branch", "其他", listOf(other)),
    ), currentBranchId = "main")
    private val archive = Archive("a", "续聊", listOf("c"), listOf(
        ArchiveEntry("entry", EntryCategory.RECENT, first.content, listOf(first.id)),
    ), updatedAt = 1, sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf(first.id, reply.id))),
        continuity = ContinuitySettings(enabled = true, interactionNotes = "保持直接的表达", nextMessage = "接着聊刚才的问题。"))

    @Test fun settingsAndExactScopeSurviveBackupSerialization() {
        val restored = appJson.decodeFromString<BackupDocument>(appJson.encodeToString(fixture(archive)))
        BackupValidation.validate(restored)
        assertEquals(archive, appJson.decodeFromString<Archive>(restored.archives.single().payload))
    }

    @Test fun malformedScopeIsRejectedForBothArchiveAndHistoricalRevision() {
        val bad = archive.copy(sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf(other.id))))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.validate(fixture(bad)) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidation.validate(fixture(archive).copy(revisions = listOf(
                RevisionRow("revision", archive.id, 2, appJson.encodeToString(bad)),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupValidation.validate(fixture(archive.copy(sourceScopes = listOf(
                ArchiveSourceScope("not-in-archive", "main", listOf(first.id)),
            ))))
        }
    }

    @Test fun oldBackupDefaultsOffAndKeepsExistingTaskSources() {
        val old = archive.copy(entries = emptyList())
        val oldJson = JsonObject(appJson.parseToJsonElement(appJson.encodeToString(old)).jsonObject
            .filterKeys { it != "continuity" && it != "sourceScopes" }).toString()
        val task = TaskRecord("task", old.id, "旧整理", ServiceSettings(), "",
            listOf(TaskChunk("chunk", listOf(reply), "cache", remoteJobId = "original-remote")), status = "paused")
        val backup = fixture(old).copy(version = 1, archives = listOf(ArchiveRow(old.id, old.updatedAt, oldJson)),
            tasks = listOf(TaskRow(task.id, old.id, 1, appJson.encodeToString(task))))
        BackupValidation.validate(backup)
        val decoded = appJson.decodeFromString<Archive>(oldJson)
        assertFalse(decoded.continuity.enabled)
        assertTrue(decoded.sourceScopes.isEmpty())
        // A continuity range is not a retroactive restriction on existing paid tasks or facts.
        val scoped = decoded.copy(sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf(first.id))))
        BackupValidation.validate(backup.copy(archives = listOf(ArchiveRow(old.id, old.updatedAt,
            appJson.encodeToString(scoped)))))
    }

    @Test fun backupRejectsOversizedNotesButRetainsStaleFragmentPreferences() {
        for (settings in listOf(archive.continuity.copy(interactionNotes = "字".repeat(4001)),
            archive.continuity.copy(nextMessage = "字".repeat(8001)))) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupValidation.validate(fixture(archive.copy(continuity = settings)))
            }
        }
        BackupValidation.validate(fixture(archive.copy(continuity = archive.continuity.copy(
            selectedFragmentIds = listOf("old-fragment-no-longer-recommended")))))
    }

    private fun fixture(value: Archive) = BackupDocument(settings = ServiceSettings(), sources = emptyList(),
        conversations = listOf(ConversationRow(conversation.id, appJson.encodeToString(conversation))),
        archives = listOf(ArchiveRow(value.id, value.updatedAt, appJson.encodeToString(value))),
        revisions = emptyList(), conflicts = emptyList(), tasks = emptyList(), cache = emptyList())
}
