package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class ContinuityPresentationTest {
    private val messages = (0..5).map { ChatMessage("u$it", "user", "测试原话$it") }
    private val conversation = ImportedConversation("c", "虚构测试", branches = listOf(ConversationBranch("b", "当前", messages)))
    private fun entry(id: String, vararg sources: String) = ArchiveEntry(id, EntryCategory.RECENT,
        "先讨论小说结尾，不替我决定。", sources.toList(), EntryKind.USER_FACT, continuityCue = ContinuityCue.OPEN_THREAD)
    private fun archive(entries: List<ArchiveEntry>) = Archive("archive", "档案", listOf("c"), entries,
        generationMode = "summary", continuity = ContinuitySettings(enabled = true, includeRecent = false,
            selectedFragmentIds = emptyList()), sourceScopes = listOf(ArchiveSourceScope("c", "b", messages.map { it.id })))

    @Test fun overlappingDuplicatesConsolidateWithoutMutatingSavedEntriesOrSources() {
        val original = archive(listOf(entry("one", "u1"), entry("two", "u0", "u1")))
        val result = ContinuityPresentation.consolidate(original, listOf(conversation))
        assertEquals(1, result.entries.size)
        assertEquals(listOf("u0", "u1"), result.entries.single().sourceIds)
        assertEquals(2, original.entries.size)
        val plan = ContinuityLogic.buildPlan(original, listOf(conversation))
        assertEquals(1, Regex(Regex.escape(original.entries.first().text)).findAll(plan.export.text).count())
        assertTrue(plan.export.text.contains("第1、2条"))
        assertTrue(plan.notices.any { it.contains("已合并 1 条") })
    }

    @Test fun independentRepetitionsAndDifferentMeaningRemainInSequence() {
        val early = entry("early", "u0")
        val change = entry("change", "u1").copy(text = "现在可以给结尾建议。", continuityCue = ContinuityCue.CORRECTION)
        val later = entry("later", "u0", "u2")
        val original = archive(listOf(early, change, later))
        assertEquals(original, ContinuityPresentation.consolidate(original, listOf(conversation)))
        for (variant in listOf(early.copy(id = "kind", kind = EntryKind.UNCONFIRMED),
                early.copy(id = "category", category = EntryCategory.DECISIONS),
                early.copy(id = "cue", continuityCue = ContinuityCue.PREFERENCE))) {
            val distinct = archive(listOf(early, variant))
            assertEquals(distinct, ContinuityPresentation.consolidate(distinct, listOf(conversation)))
        }
    }

    @Test fun protectedContentAndAmbiguousBranchesAreNeverMerged() {
        for (protected in listOf(entry("p", "u0").copy(edited = true), entry("p", "u0").copy(deleted = true),
                entry("p", "u0").copy(pinned = true))) {
            val original = archive(listOf(entry("one", "u0"), entry("two", "u0", "u1"), protected))
            assertEquals(original, ContinuityPresentation.consolidate(original, listOf(conversation)))
        }
        val original = archive(listOf(entry("one", "u0"), entry("two", "u0", "u1")))
        val second = conversation.copy(branches = conversation.branches + conversation.branches.single().copy(id = "b2"))
        val ambiguous = original.copy(sourceScopes = original.sourceScopes + original.sourceScopes.single().copy(branchId = "b2"))
        assertEquals(ambiguous, ContinuityPresentation.consolidate(ambiguous, listOf(second)))
        assertEquals(original.copy(generationMode = "original"),
            ContinuityPresentation.consolidate(original.copy(generationMode = "original"), listOf(conversation)))
    }

    @Test fun overlappingComponentsRetainEverySourceInSourceOrder() {
        val result = ContinuityPresentation.consolidate(archive(listOf(entry("first", "u0", "u3"), entry("last", "u3"),
            entry("bridge", "u0", "u3", "u1"))), listOf(conversation))
        assertEquals(listOf("u0", "u1", "u3"), result.entries.single().sourceIds)
    }

    @Test fun unfinishedTopicPrecedesLongDetailsButKeepsHistoricalStatus() {
        val open = entry("open", "u0")
        val detail = entry("detail", "u1").copy(text = "完整主题资料。".repeat(4000), continuityCue = null)
        val plan = ContinuityLogic.buildPlan(archive(listOf(detail, open)), listOf(conversation))
        assertTrue(plan.export.text.indexOf(open.text) < plan.export.text.indexOf(detail.text))
        assertTrue(plan.export.text.contains(detail.text))
        assertTrue(plan.export.text.contains("不把早期的待定状态自动视为现在仍未解决"))
    }
}
