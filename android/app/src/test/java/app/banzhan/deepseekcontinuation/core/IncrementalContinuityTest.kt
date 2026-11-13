package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class IncrementalContinuityTest {
    private val old = ChatMessage("m1", "user", "先讨论为什么在意。", "t1")
    private val reply = ChatMessage("m2", "assistant", "先保留这个问题。", "t2")
    private val added = ChatMessage("m3", "user", "现在补充一个新的细节。", "t3")

    private fun conversation(
        id: String = "c", branch: String = "main", messages: List<ChatMessage> = listOf(old, reply, added),
    ) = ImportedConversation(id, "合成对话", branches = listOf(ConversationBranch(branch, "当前", messages, true)),
        currentBranchId = branch)

    private fun archive(source: ImportedConversation = conversation(), mode: String = "original") = Archive(
        "archive", "合成档案", listOf(source.id), ArchiveLogic.localEntries(listOf(old, reply)),
        generationMode = mode, sourceScopes = listOf(ArchiveSourceScope(source.id, source.branches.single().id, listOf(old.id, reply.id))))

    private fun selection(archive: Archive, source: ImportedConversation = conversation(),
        completed: List<List<ChatMessage>> = emptyList(), includeLocal: Boolean = true,
        stored: List<ImportedConversation> = listOf(source), selectedIds: Set<String> = source.branches.single().messages.map { it.id }.toSet()) =
        IncrementalContinuity.select(archive, stored, source, source.branches.single().id, selectedIds, completed, includeLocal)

    @Test fun repeatedImportOnlyAddsNewMessagesInTheExplicitSelection() {
        val result = selection(archive())
        assertEquals(listOf(added.id), result.messageIds)
        assertEquals(1, result.newMessageCount)
        assertEquals(2, result.alreadyIncludedCount)
        assertEquals(listOf(old.id, reply.id, added.id), result.sourceScope.selectedMessageIds)
        val limited = selection(archive(), selectedIds = setOf(old.id, added.id))
        assertEquals(listOf(added.id), limited.messageIds)
        assertEquals(listOf(old.id, added.id), limited.sourceScope.selectedMessageIds)
    }

    @Test fun sourceScopeOfAnUnorganizedDraftIsNotCompletedModelCoverage() {
        val draft = archive(mode = "summary").copy(entries = emptyList())
        assertEquals(listOf(old.id, reply.id, added.id), selection(draft).messageIds)
        assertEquals(listOf(old.id, reply.id, added.id), selection(archive(), includeLocal = false).messageIds)
        val processed = selection(draft, completed = listOf(listOf(old, reply)), includeLocal = false)
        assertEquals(listOf(added.id), processed.messageIds)
    }

    @Test fun onlyAWholeVerifiedPrimaryMessageCountsAcrossCompletedChunks() {
        val draft = archive(mode = "summary").copy(entries = emptyList())
        val half = old.copy(content = old.content.take(4))
        assertTrue(old.id in selection(draft, completed = listOf(listOf(half)), includeLocal = false).messageIds)
        val rest = old.copy(content = old.content.drop(4))
        assertFalse(old.id in selection(draft, completed = listOf(listOf(half, rest)), includeLocal = false).messageIds)
        // Pieces from two different runs cannot complete one another.
        assertTrue(old.id in selection(draft, completed = listOf(listOf(half), listOf(rest)), includeLocal = false).messageIds)
        assertTrue(old.id in selection(draft, completed = listOf(listOf(rest, half)), includeLocal = false).messageIds)
        assertTrue(old.id in selection(draft, completed = listOf(listOf(old, old)), includeLocal = false).messageIds)
    }

    @Test fun changedContentRoleTimeOrAttachmentsNeverMatchesCompletedCoverage() {
        val draft = archive(mode = "summary").copy(entries = emptyList())
        for (different in listOf(old.copy(content = "另一件事"), old.copy(role = "assistant"),
            old.copy(timestamp = "later"), old.copy(attachments = listOf("photo")))) {
            assertTrue(old.id in selection(draft, completed = listOf(listOf(different)), includeLocal = false).messageIds)
        }
        val incoming = conversation(messages = listOf(old.copy(content = "同ID却已改变"), reply, added))
        assertTrue(old.id in selection(archive(), incoming, stored = listOf(conversation())).messageIds)
    }

    @Test fun anotherConversationOrBranchCannotBorrowCoverageEvenForEqualMessageIds() {
        val another = conversation(id = "another")
        assertEquals(listOf(old.id, reply.id, added.id), selection(archive(), another,
            completed = listOf(listOf(old, reply)), stored = listOf(conversation(), another)).messageIds)
        val alternate = conversation(branch = "alternate")
        assertEquals(listOf(old.id, reply.id, added.id), selection(archive(), alternate,
            completed = listOf(listOf(old, reply)), stored = listOf(conversation(), alternate)).messageIds)
    }

    @Test fun ambiguousSharedSourceIdsRemainEligibleForNewOrganization() {
        val main = conversation()
        val twoBranches = main.copy(branches = main.branches + main.branches.single().copy(id = "other"))
        val ambiguous = archive(main, "summary").copy(entries = emptyList(), sourceScopes = listOf(
            ArchiveSourceScope("c", "main", listOf(old.id, reply.id)),
            ArchiveSourceScope("c", "other", listOf(old.id, reply.id))))
        val result = IncrementalContinuity.select(ambiguous, listOf(twoBranches), twoBranches, "main",
            setOf(old.id, reply.id), listOf(listOf(old, reply)), includeLocalCoverage = false)
        assertEquals(listOf(old.id, reply.id), result.messageIds)
    }

    @Test fun manualEditsAndDeletionsAreNotReintroducedByAnAdditionalImport() {
        val source = conversation()
        val local = ArchiveLogic.localEntries(listOf(old, reply))
        val protected = archive(source, "summary").copy(entries = listOf(
            local.first().copy(text = "人工修订", edited = true), local.last().copy(deleted = true)))
        assertEquals(listOf(added.id), selection(protected).messageIds)
        assertEquals(listOf(added.id), selection(protected, includeLocal = false).messageIds)
        assertEquals(2, protected.entries.size)
        assertTrue(protected.entries.last().deleted)

        val note = archive(source, "summary").copy(entries = listOf(
            ArchiveEntry("manual-note", EntryCategory.RECENT, "另外补充一条想法", listOf(old.id), edited = true)))
        assertEquals(listOf(old.id, reply.id, added.id), selection(note).messageIds)
        assertEquals(listOf(old.id, reply.id, added.id), selection(note, includeLocal = false).messageIds)
    }

    @Test fun legacyRangesAndInvalidScopesNeverSilentlyBroadenOrEliminateTheSelection() {
        val legacy = archive().copy(sourceScopes = emptyList())
        assertEquals(listOf(old.id, reply.id, added.id), selection(legacy).messageIds)
        assertTrue(selection(legacy).notice.contains("无法完整核实"))
        val reversed = archive().copy(sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf(reply.id, old.id))))
        assertEquals(listOf(old.id, reply.id, added.id), selection(reversed).messageIds)
        assertEquals(emptyList<String>(), selection(archive(), selectedIds = emptySet()).messageIds)
        assertThrows(IllegalArgumentException::class.java) { selection(archive(), selectedIds = setOf("unselected-branch")) }
    }

    @Test fun savedLocalExcerptsWorkAfterAnArchiveBecomesMixedMode() {
        val mixed = archive(mode = "legacy")
        assertEquals(listOf(added.id), selection(mixed).messageIds)
        val allSaved = mixed.copy(sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf(old.id, reply.id, added.id))),
            entries = mixed.entries + ArchiveLogic.localEntries(listOf(added)))
        val result = selection(allSaved)
        assertTrue(result.messageIds.isEmpty())
        assertEquals(3, result.alreadyIncludedCount)
        assertTrue(result.notice.contains("没有新增"))
    }

    @Test fun incrementalReferencesUseOnlyEarlierSelectedUnmodifiedSameBranchSources() {
        val source = conversation()
        assertEquals(listOf(old, reply), IncrementalContinuity.priorSelectedMessages(archive(), listOf(source),
            source, "main", setOf(added.id)))
        val protected = archive().copy(entries = listOf(
            ArchiveEntry("manual", EntryCategory.RECENT, "改过的意思", listOf(old.id), edited = true)))
        assertEquals(listOf(reply), IncrementalContinuity.priorSelectedMessages(protected, listOf(source),
            source, "main", setOf(added.id)))
        val limited = archive().copy(sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf(old.id))))
        assertEquals(listOf(old), IncrementalContinuity.priorSelectedMessages(limited, listOf(source),
            source, "main", setOf(added.id)))
        assertTrue(IncrementalContinuity.priorSelectedMessages(archive(), listOf(source), source,
            "main", setOf(old.id)).isEmpty())
        val other = conversation(id = "another")
        assertTrue(IncrementalContinuity.priorSelectedMessages(archive(), listOf(source, other), other,
            "main", setOf(added.id)).isEmpty())
    }

    @Test fun originalModeAndCapturedRangeAloneDoNotPretendMissingRawTextWasSaved() {
        val expanded = archive().copy(sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf(old.id, reply.id, added.id))))
        // This is the state after linking extra sources for AI and then cancelling confirmation.
        assertEquals("original", expanded.generationMode)
        assertEquals(listOf(added.id), selection(expanded).messageIds)
        assertEquals(listOf(old.id, reply.id, added.id), selection(expanded, includeLocal = false).messageIds)
        assertEquals(listOf(old.id, reply.id, added.id), selection(expanded.copy(entries = emptyList())).messageIds)
    }
}
