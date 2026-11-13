package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic source graphs only; initial references are authorized separately from paid primary text. */
class IncrementalContextBackupTest {
    private val settings = ServiceSettings(mode = "byok", contextTokens = 64000, maxOutputTokens = 1024)
    private val prior = ChatMessage("prior", "user", "先讨论虚构人物为什么在意这件事。", "synthetic-1")
    private val priorReply = ChatMessage("prior-reply", "assistant", "先保留原因，之后讨论下一步。", "synthetic-2")
    private val current = ChatMessage("current", "user", "现在补充一个新细节。", "synthetic-3")
    private val next = ChatMessage("next", "assistant", "接着处理这个新细节。", "synthetic-4")
    private val future = ChatMessage("future", "user", "这条尚未选择，不能作为前文。", "synthetic-5")
    private val scope = ArchiveSourceScope("source", "main", listOf(prior.id, priorReply.id))
    private val conversation = ImportedConversation("source", "固定虚构增量来源", branches = listOf(
        ConversationBranch("main", "当前", listOf(prior, priorReply, current, next, future), true),
        ConversationBranch("alternate", "另一分支", listOf(prior, priorReply, current)),
    ), currentBranchId = "main")
    private val archive = Archive("archive", "合成档案", listOf(conversation.id), emptyList(), updatedAt = 1,
        sourceScopes = listOf(ArchiveSourceScope("source", "main", listOf(prior.id, priorReply.id, current.id, next.id))))

    private fun task(chunks: List<TaskChunk> = listOf(TaskChunk("first", listOf(current), "first-key",
        contextMessages = listOf(prior, priorReply)))) = TaskRecord(
        "task", archive.id, "合成增量", settings, "", chunks, status = "paused",
        promptVersion = SummaryProtocol.PROMPT_VERSION, initialContextScope = scope)

    private fun fixture(value: TaskRecord = task(), source: ImportedConversation = conversation,
        saved: Archive = archive) = BackupDocument(settings = settings, sources = emptyList(),
        conversations = listOf(ConversationRow(source.id, appJson.encodeToString(source))),
        archives = listOf(ArchiveRow(saved.id, saved.updatedAt, appJson.encodeToString(saved))),
        revisions = emptyList(), conflicts = emptyList(),
        tasks = listOf(TaskRow(value.id, saved.id, 1, appJson.encodeToString(value))), cache = emptyList())

    private fun rejected(value: BackupDocument) = assertThrows(IllegalArgumentException::class.java) {
        BackupValidation.validate(value)
    }

    @Test fun formatSixRoundTripRetainsExactInitialScopeAndRequestIdentity() {
        val original = task()
        val restored = appJson.decodeFromString<BackupDocument>(appJson.encodeToString(fixture(original)))
        assertEquals(6, restored.version)
        BackupValidation.validate(restored)
        assertEquals(original, restored.tasks.single().decodeTask())
        assertEquals(scope, restored.tasks.single().decodeTask().initialContextScope)
        assertEquals(original.chunks, restored.tasks.single().decodeTask().chunks)
        rejected(restored.copy(version = 5))
        rejected(restored.copy(version = 4))
    }

    @Test fun firstChunkCannotBorrowPreviousArchiveMessagesWithoutRecordedScope() {
        rejected(fixture(task().copy(initialContextScope = null)))
        rejected(fixture(task().copy(promptVersion = SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION)))
    }

    @Test fun recordedScopeMustBeSelectedOrderedAndOnTheExplicitConversationBranch() {
        for (bad in listOf(scope.copy(conversationId = "missing"), scope.copy(branchId = "missing"),
            scope.copy(branchId = "alternate"), scope.copy(selectedMessageIds = listOf(priorReply.id, prior.id)),
            scope.copy(selectedMessageIds = listOf(prior.id, prior.id)),
            scope.copy(selectedMessageIds = listOf(future.id)),
            scope.copy(selectedMessageIds = listOf(current.id)))) {
            rejected(fixture(task().copy(initialContextScope = bad)))
        }
    }

    @Test fun evenSelectedFutureMessagesCannotBecomeInitialContext() {
        val allSelected = archive.copy(sourceScopes = listOf(archive.sourceScopes.single().copy(
            selectedMessageIds = listOf(prior.id, priorReply.id, current.id, next.id, future.id))))
        val bad = task(listOf(TaskChunk("first", listOf(current), "key", contextMessages = listOf(future))))
            .copy(initialContextScope = scope.copy(selectedMessageIds = listOf(future.id)))
        rejected(fixture(bad, saved = allSelected))
    }

    @Test fun initialReferenceAuthorizationCannotBeReusedByLaterChunks() {
        val first = task().chunks.single()
        val later = TaskChunk("later", listOf(next), "later-key", contextMessages = listOf(prior))
        rejected(fixture(task(listOf(first, later))))
        BackupValidation.validate(fixture(task(listOf(first, later.copy(contextMessages = listOf(current))))))
    }

    @Test fun firstReferencesMustFollowScopeOrderAndCannotAddAnotherEarlierMessage() {
        rejected(fixture(task().copy(chunks = listOf(task().chunks.single().copy(
            contextMessages = listOf(priorReply, prior))))))
        rejected(fixture(task().copy(initialContextScope = scope.copy(selectedMessageIds = listOf(prior.id)))))
    }

    @Test fun referenceContentRolesTimestampsAndAttachmentsRemainExactSourceExcerpts() {
        for (bad in listOf(prior.copy(role = "assistant"), prior.copy(content = "未提供的新解释"),
            prior.copy(timestamp = "different"), prior.copy(attachments = listOf("unknown-photo")))) {
            rejected(fixture(task().copy(chunks = listOf(task().chunks.single().copy(
                contextMessages = listOf(bad, priorReply))))))
        }
        val tail = prior.copy(content = prior.content.takeLast(8))
        BackupValidation.validate(fixture(task().copy(chunks = listOf(task().chunks.single().copy(
            contextMessages = listOf(tail, priorReply))))))
        // Explicit safe resplitting can reduce an initial reference without changing the
        // admitted scope or pulling new text from it. The remaining actual IDs stay a subset.
        val reduced = task().copy(chunks = listOf(task().chunks.single().copy(contextMessages = listOf(priorReply))))
        assertEquals(scope, reduced.initialContextScope)
        BackupValidation.validate(fixture(reduced))
    }

    @Test fun reorganizationCannotInventAnInitialReferenceScope() {
        val rebuilt = archive.copy(rebuiltFromArchiveId = "parent-archive")
        rejected(fixture(task().copy(reorganizationOfArchiveId = "parent-archive"), saved = rebuilt))
    }

    @Test fun missingNewFieldRetainsOldFormatFiveRulesAndPaidIdentifiers() {
        val old = task(listOf(
            TaskChunk("paid-primary", listOf(prior), "original-paid-key", requestStarted = true,
                remoteJobId = "original-remote", remoteStatus = "running"),
            TaskChunk("later", listOf(current), "later-key", contextMessages = listOf(prior)),
        )).copy(initialContextScope = null, promptVersion = SummaryProtocol.LONG_TEXT_PROMPT_VERSION)
        val fields = appJson.parseToJsonElement(appJson.encodeToString(old)).jsonObject
        val oldPayload = JsonObject(fields.filterKeys { it != "initialContextScope" }).toString()
        val decoded = appJson.decodeFromString<TaskRecord>(oldPayload)
        assertNull(decoded.initialContextScope)
        assertEquals(old, decoded)
        val restored = fixture(decoded).copy(version = 5, tasks = listOf(TaskRow(old.id, old.archiveId, 1, oldPayload)))
        BackupValidation.validate(restored)
        assertEquals("original-paid-key", restored.tasks.single().decodeTask().chunks.first().cacheKey)
        assertEquals("original-remote", restored.tasks.single().decodeTask().chunks.first().remoteJobId)
    }

    @Test fun processDeathAndUnknownPostKeepScopeAndExactChunkIdentity() {
        val chunk = task().chunks.single().copy(requestStarted = true, inFlight = true)
        val active = task(listOf(chunk)).copy(status = "running", inFlight = true)
        val recovered = appJson.decodeFromString<TaskRecord>(appJson.encodeToString(active)).afterProcessDeath()
        assertEquals(scope, recovered.initialContextScope)
        assertTrue(recovered.uncertain)
        assertEquals(listOf(chunk.copy(inFlight = false)), recovered.chunksForExplicitResume())
        BackupValidation.validate(fixture(recovered))
    }

    @Test fun safeResplitPreservesInitialScopeWhileLaterPiecesUseOnlyProcessedPrimaryText() {
        val long = current.copy(content = "合成的新细节。\n".repeat(1800))
        val source = conversation.copy(branches = conversation.branches.map { branch ->
            branch.copy(messages = branch.messages.map { if (it.id == current.id) long else it }) })
        val failed = TaskChunk("failed", listOf(long), "old-paid-key", requestStarted = true,
            remoteJobId = "failed-remote", remoteStatus = "failed", contextMessages = listOf(prior, priorReply))
        val original = task(listOf(failed))
        val pieces = original.chunksForExplicitResume()
        assertTrue(pieces.size > 1)
        assertEquals(listOf(prior, priorReply), pieces.first().contextMessages)
        assertTrue(pieces.drop(1).all { piece -> piece.contextMessages.all { it.id == current.id } })
        assertEquals(long.content, pieces.flatMap { it.messages }.joinToString("") { it.content })
        val resumed = original.copy(chunks = pieces)
        assertEquals(scope, resumed.initialContextScope)
        BackupValidation.validate(fixture(resumed, source))
    }

    @Test fun restoringAnEarlierRangeRetainsAdmittedTasksOnlyWithOneCompleteHistoricalSelection() {
        val earlier = archive.copy(sourceScopes = listOf(scope))
        val narrowed = fixture(saved = earlier)
        rejected(narrowed)
        val completeHistory = RevisionRow("complete-range", archive.id, 2, appJson.encodeToString(archive))
        BackupValidation.validate(narrowed.copy(revisions = listOf(completeHistory)))

        // Separate partial snapshots cannot manufacture a range that was never selected together.
        val primaryOnly = archive.copy(sourceScopes = listOf(ArchiveSourceScope("source", "main", listOf(current.id, next.id))))
        rejected(narrowed.copy(revisions = listOf(
            RevisionRow("partial-range", archive.id, 2, appJson.encodeToString(primaryOnly)))))
        val otherBranch = archive.copy(sourceScopes = listOf(ArchiveSourceScope("source", "alternate",
            listOf(prior.id, priorReply.id, current.id))))
        rejected(narrowed.copy(revisions = listOf(
            RevisionRow("other-branch-range", archive.id, 2, appJson.encodeToString(otherBranch)))))
    }
}
