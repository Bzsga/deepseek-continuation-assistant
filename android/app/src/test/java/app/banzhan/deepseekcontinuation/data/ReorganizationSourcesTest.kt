package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import org.junit.Assert.*
import org.junit.Test

class ReorganizationSourcesTest {
    private val first = ChatMessage("m1", "user", "Keep this unchanged.", "2026-11-05", listOf("note.txt"))
    private val second = ChatMessage("m2", "assistant", "A distinct reply.")
    private val blank = ChatMessage("blank", "user", " \n ")
    private val other = ChatMessage("other", "user", "Another branch only.")
    private val conversation = ImportedConversation("c", "合成对话", branches = listOf(
        ConversationBranch("main", "当前", listOf(first, second, blank), true),
        ConversationBranch("other-branch", "其他", listOf(other)),
    ), currentBranchId = "main")
    private val archive = Archive("archive", "旧档案", listOf("c"), listOf(
        ArchiveEntry("entry", EntryCategory.RECENT, "已保存的摘要", listOf(first.id)),
    ), updatedAt = 1)
    private val scope = ArchiveSourceScope("c", "main", listOf(first.id, second.id))

    private fun task(id: String = "task", groups: List<List<ChatMessage>> = listOf(listOf(first, second))) =
        TaskRecord(id, archive.id, "历史整理", ServiceSettings(), "", groups.mapIndexed { index, messages ->
            TaskChunk("$id-chunk-$index", messages, "$id-cache-$index", completed = true)
        }, status = "completed")

    private fun rejected(block: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { block() }
        assertFalse(failure.message.isNullOrBlank())
    }

    @Test fun savedScopesKeepBranchesSeparateAndNeverFallBackFromAnEmptyOrInvalidRange() {
        val selected = listOf(ArchiveSourceScope("c", "main", listOf(first.id, blank.id)),
            ArchiveSourceScope("c", "other-branch", listOf(other.id)))
        val saved = archive.copy(sourceScopes = selected)
        val resolved = resolveReorganizationSources(saved, listOf(conversation), listOf(task()))
        assertEquals(selected, resolved.sourceScopes)
        assertEquals(listOf(listOf(first), listOf(other)), resolved.messageGroups)
        assertEquals(ReorganizationRangeOrigin.SAVED_SCOPE, resolved.rangeOrigin)
        assertEquals("沿原选择的范围", resolved.description)
        for (invalid in listOf(
            listOf(scope.copy(selectedMessageIds = emptyList())),
            listOf(scope.copy(selectedMessageIds = listOf(blank.id))),
            listOf(scope.copy(selectedMessageIds = listOf(other.id))),
            listOf(scope.copy(selectedMessageIds = listOf(second.id, first.id))),
        )) rejected {
            resolveReorganizationSources(archive.copy(sourceScopes = invalid), listOf(conversation), listOf(task()))
        }

        val fingerprint = reorganizationSourceFingerprint(saved, resolved)
        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, reorganizationSourceFingerprint(saved, resolved))
        assertNotEquals(fingerprint, reorganizationSourceFingerprint(saved.copy(
            continuity = saved.continuity.copy(interactionNotes = "新的个人设置")), resolved))
        assertNotEquals(fingerprint, reorganizationSourceFingerprint(saved.copy(entries = emptyList()), resolved))
        val changedSource = conversation.copy(branches = conversation.branches.map { branch ->
            branch.copy(messages = branch.messages.map { if (it.id == first.id) it.copy(content = "Changed original") else it })
        })
        assertNotEquals(fingerprint, reorganizationSourceFingerprint(saved,
            resolveReorganizationSources(saved, listOf(changedSource), emptyList())))
        val smaller = saved.copy(sourceScopes = listOf(selected.first()))
        assertNotEquals(fingerprint, reorganizationSourceFingerprint(smaller,
            resolveReorganizationSources(smaller, listOf(conversation), emptyList())))
    }

    @Test fun repeatedBranchScopesMergeInSourceOrderWithoutFillingUnselectedGaps() {
        val unselected = ChatMessage("gap", "user", "Do not include this gap.")
        val withGap = conversation.copy(branches = listOf(
            conversation.branches.first().copy(messages = listOf(first, unselected, second, blank)),
            conversation.branches.last(),
        ))
        val repeated = listOf(scope.copy(selectedMessageIds = listOf(second.id)),
            ArchiveSourceScope("c", "other-branch", listOf(other.id)),
            scope.copy(selectedMessageIds = listOf(first.id)), scope.copy(selectedMessageIds = listOf(second.id)))
        val saved = archive.copy(sourceScopes = repeated)
        val resolved = resolveReorganizationSources(saved, listOf(withGap), emptyList())
        val canonical = listOf(scope, ArchiveSourceScope("c", "other-branch", listOf(other.id)))
        assertEquals(canonical, resolved.sourceScopes)
        assertEquals(listOf(listOf(first, second), listOf(other)), resolved.messageGroups)
        val explicitlyCanonical = resolveReorganizationSources(saved, listOf(withGap), emptyList(), canonical)
        assertEquals(reorganizationSourceFingerprint(saved, resolved),
            reorganizationSourceFingerprint(saved, explicitlyCanonical))
    }

    @Test fun historicalPiecesReassembleExactlyAcrossChunkBoundaries() {
        val split = task(groups = listOf(listOf(first.copy(content = "Keep ")),
            listOf(first.copy(content = "this unchanged."), second)))
        val resolved = resolveReorganizationSources(archive, listOf(conversation), listOf(split))
        assertEquals(listOf(scope), resolved.sourceScopes)
        assertEquals(listOf(listOf(first, second)), resolved.messageGroups)
        assertEquals(ReorganizationRangeOrigin.HISTORICAL_TASK, resolved.rangeOrigin)
        assertEquals("按上次整理的2条原文", resolved.description)
    }

    @Test fun everyHistoricalTaskMustResolveToTheSameScopeWithoutSkippingBrokenRecords() {
        val split = task("split", listOf(listOf(first.copy(content = "Keep ")),
            listOf(first.copy(content = "this unchanged.")), listOf(second)))
        val resolved = resolveReorganizationSources(archive, listOf(conversation), listOf(task(), split,
            task("unrelated", emptyList()).copy(archiveId = "another-archive")))
        assertEquals(listOf(scope), resolved.sourceScopes)
        for (different in listOf(
            task("subset", listOf(listOf(first))),
            task("missing", emptyList()),
            task("empty-chunk", listOf(listOf(first, second), emptyList())),
        )) rejected { resolveReorganizationSources(archive, listOf(conversation), listOf(task(), different)) }
        rejected { resolveReorganizationSources(archive, listOf(conversation), emptyList()) }
    }

    @Test fun historyRejectsAmbiguousBranchesPartialTextChangedMetadataOrderAndUncoveredEntries() {
        val ambiguous = conversation.copy(branches = conversation.branches +
            conversation.branches.first().copy(id = "shared-branch", isCurrent = false))
        rejected { resolveReorganizationSources(archive, listOf(ambiguous), listOf(task())) }
        for (messages in listOf(
            listOf(first.copy(content = "Keep "), second),
            listOf(first.copy(role = "assistant"), second),
            listOf(first.copy(timestamp = "changed"), second),
            listOf(first.copy(attachments = emptyList()), second),
            listOf(second, first),
            listOf(first.copy(content = "Keep "), second, first.copy(content = "this unchanged.")),
            listOf(first.copy(content = "Keep "), first.copy(content = "this unchanged.", timestamp = "changed"), second),
        )) rejected { resolveReorganizationSources(archive, listOf(conversation), listOf(task(groups = listOf(messages)))) }
        for (entry in listOf(
            ArchiveEntry("edited", EntryCategory.RECENT, "人工修改", listOf(second.id), edited = true),
            ArchiveEntry("deleted", EntryCategory.RECENT, "已删除", listOf(second.id), deleted = true),
            ArchiveEntry("missing", EntryCategory.RECENT, "缺失来源", listOf("missing-source")),
        )) rejected {
            resolveReorganizationSources(archive.copy(entries = archive.entries + entry), listOf(conversation),
                listOf(task(groups = listOf(listOf(first)))))
        }
    }

    @Test fun explicitSelectionCanExcludeProtectedEntriesButCannotAddAnotherConversation() {
        val guardedArchive = archive.copy(entries = archive.entries +
            ArchiveEntry("manual", EntryCategory.RECENT, "保留的修订", listOf(second.id), edited = true, deleted = true),
            sourceScopes = listOf(scope.copy(branchId = "no-longer-present")))
        val chosen = listOf(scope.copy(selectedMessageIds = listOf(first.id)))
        val resolved = resolveReorganizationSources(guardedArchive, listOf(conversation), listOf(task(groups = emptyList())), chosen)
        assertEquals(chosen, resolved.sourceScopes)
        assertEquals(listOf(listOf(first)), resolved.messageGroups)
        assertEquals(ReorganizationRangeOrigin.USER_SELECTION, resolved.rangeOrigin)
        assertEquals("按本次重新选择的范围", resolved.description)
        rejected { resolveReorganizationSources(guardedArchive, listOf(conversation), listOf(task()), emptyList()) }
        val outside = conversation.copy(id = "not-in-archive")
        rejected { resolveReorganizationSources(guardedArchive, listOf(conversation, outside), emptyList(),
            listOf(scope.copy(conversationId = outside.id))) }
        rejected { resolveReorganizationSources(guardedArchive, listOf(conversation), emptyList(),
            listOf(scope.copy(selectedMessageIds = listOf(other.id)))) }
    }
}
