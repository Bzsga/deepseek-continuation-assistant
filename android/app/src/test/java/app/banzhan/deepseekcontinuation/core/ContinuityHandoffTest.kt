package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class ContinuityHandoffTest {
    @Test fun omittedLatestUserNeverTurnsAnEarlierAssistantIntoTheStoppingPoint() {
        val conversation = conversation(listOf(user("u0", "较早的问题"), assistant("a0", "较早的回答"),
            user("u1", "无边界".repeat(12000))))
        val plan = ContinuityLogic.buildPlan(archive(conversation), listOf(conversation), 2400)
        assertTrue(plan.export.text.contains("最后的发言未附带原话"))
        assertFalse(plan.export.text.contains("等我接着说"))
        assertFalse(plan.export.text.contains("请从这句话继续回应"))
    }

    @Test fun recentLongExchangesUseLabelledExactSentencesAndKeepOriginalArchive() {
        val question = "我想继续谈这个虚构故事。" + (1..180).joinToString("") { "这是第${it}个不同的细节。" } + "最后我还没有决定结尾。"
        val reply = "先保留你的两个选择。" + "中间这个地方可以慢慢再想。".repeat(160) + "你现在更在意哪个人物？"
        val conversation = conversation(listOf(user("u0", question), assistant("a0", reply)))
        val source = archive(conversation)
        val plan = ContinuityLogic.buildPlan(source, listOf(conversation), 6000)
        assertTrue(plan.export.text.contains("节选"))
        assertTrue(plan.export.text.contains("最后我还没有决定结尾。"))
        assertTrue(plan.export.text.contains("你现在更在意哪个人物？"))
        assertEquals(question, conversation.branches.single().messages.first().content)
        assertEquals(listOf("u0", "a0"), source.sourceScopes.single().selectedMessageIds)
        assertTrue(plan.notices.any { it.contains("完整原文仍在本机") })
    }

    @Test fun protectedLastMessageNeverReappearsAsRawContextOrAnEarlierStoppingPoint() {
        val conversation = conversation(listOf(user("u0", "较早问题"), assistant("a0", "较早回答"), user("u1", "不能再引用的旧话")))
        for (deleted in listOf(false, true)) {
            val protected = ArchiveEntry("changed", EntryCategory.RECENT, "用户保存的修订", listOf("u1"),
                EntryKind.USER_FACT, edited = !deleted, deleted = deleted)
            val result = ContinuityLogic.export(archive(conversation, listOf(protected)), listOf(conversation)).text
            assertFalse(result.contains("不能再引用的旧话"))
            assertEquals(!deleted, result.contains("用户保存的修订"))
            assertFalse(result.contains("【接续停点】"))
        }
    }

    @Test fun explicitRawSwitchesAndNextMessageKeepTheirMeaning() {
        val conversation = conversation(listOf(user("u0", "已经解释过的问题"), assistant("a0", "已经解释过的回答")))
        val source = archive(conversation).copy(continuity = ContinuitySettings(enabled = true, includeRecent = false,
            selectedFragmentIds = emptyList()))
        val off = ContinuityLogic.export(source, listOf(conversation)).text
        assertFalse(off.contains("已经解释过的问题"))
        assertFalse(off.contains("【接续停点】"))
        assertTrue(off.contains("先简短自然地接上一两句"))
        val recent = ContinuityLogic.buildPlan(source.copy(continuity = source.continuity.copy(includeRecent = true)), listOf(conversation))
        assertTrue(recent.export.text.contains("已经解释过的问题"))
        assertTrue(recent.export.text.contains("不必重复回答上一轮"))
        assertTrue(recent.export.text.contains("先简短自然地接上一两句"))
        assertFalse(recent.export.text.contains("等我接着说"))
        val next = ContinuityLogic.export(source.copy(continuity = source.continuity.copy(includeRecent = true, nextMessage = "现在想聊另一件事。")), listOf(conversation)).text
        assertTrue(next.endsWith("优先回应我接下来想说的内容。\n"))
        assertFalse(next.contains("等我接着说"))
        assertFalse(next.contains("先简短自然地接上一两句"))
    }

    @Test fun reversedRangesStillUseBranchOrderButMultipleBranchesHaveNoGlobalStop() {
        val conversation = conversation(listOf(user("u0", "较早问题"), assistant("a0", "较早回答"), user("u1", "最新问题")))
        val source = archive(conversation).copy(sourceScopes = listOf(ArchiveSourceScope("c", "b", listOf("u1")),
            ArchiveSourceScope("c", "b", listOf("u0", "a0"))))
        val plan = ContinuityLogic.buildPlan(source, listOf(conversation))
        assertEquals("u1", plan.recentFragments.last().messages.last().id)
        assertTrue(plan.export.text.contains("请从这句话继续回应"))
        assertFalse(plan.export.text.contains("先简短自然地接上一两句"))
        val second = ConversationBranch("second", "另一个版本", listOf(user("u2", "另一个问题"), assistant("a2", "另一个回答")))
        val branched = conversation.copy(branches = conversation.branches + second)
        val mixed = source.copy(sourceScopes = source.sourceScopes + ArchiveSourceScope("c", "second", listOf("u2", "a2")))
        assertFalse(ContinuityLogic.export(mixed, listOf(branched)).text.contains("【接续停点】"))
    }

    @Test fun aUserAtSelectionBoundaryIsNotNecessarilyAwaitingAnAnswer() {
        val conversation = conversation(listOf(user("u0", "被选中的问题"), assistant("a0", "未选的后续回答")))
        val source = archive(conversation).copy(sourceScopes = listOf(ArchiveSourceScope("c", "b", listOf("u0"))))
        val text = ContinuityLogic.export(source, listOf(conversation)).text
        assertTrue(text.contains("不能认定这句话仍待回应"))
        assertFalse(text.contains("未选的后续回答"))
        assertFalse(text.contains("请从这句话继续回应"))
    }

    @Test fun changesRetainConditionsAndRelatedEarlierRecordWithoutInventingSupersession() {
        val conversation = conversation((0..4).flatMap { listOf(user("u$it", "用户原话$it"), assistant("a$it", "助手回应$it")) })
        val earlier = ArchiveEntry("before", EntryCategory.BACKGROUND, "讨论写作时，希望先听一个例子。", listOf("u0"),
            EntryKind.USER_FACT, continuityCue = ContinuityCue.PREFERENCE)
        val changed = ArchiveEntry("after", EntryCategory.BACKGROUND, "今天先听我说完，是否继续用例子还没决定。", listOf("u0", "u3"),
            EntryKind.USER_FACT, continuityCue = ContinuityCue.CORRECTION)
        val other = ArchiveEntry("other", EntryCategory.DECISIONS, "讨论预算时，希望直接指出数字错误。", listOf("u2"),
            EntryKind.USER_FACT, continuityCue = ContinuityCue.PREFERENCE)
        val source = archive(conversation, listOf(changed, other, earlier))
        val text = ContinuityLogic.export(source, listOf(conversation)).text
        assertTrue(text.indexOf("【明确修正与变化】") < text.indexOf(earlier.text))
        assertTrue(text.indexOf(earlier.text) < text.indexOf(changed.text))
        assertTrue(text.contains("不把一次情境中的要求变成永久偏好"))
        listOf(earlier, changed, other).forEach { assertEquals(1, Regex(Regex.escape(it.text)).findAll(text).count()) }
        assertEquals(listOf(changed, other, earlier), source.entries)
    }

    @Test fun automaticMemoryBudgetLeavesRoomForCurrentContextAndDisclosesOmissions() {
        val conversation = conversation((0..30).flatMap { listOf(user("u$it", "问题$it"), assistant("a$it", "回答$it")) } + user("last", "请接着讨论刚才的结尾。"))
        val entries = (0..30).map { ArchiveEntry("e$it", EntryCategory.RECENT, "第${it}项合成资料。".repeat(25), listOf("u$it"), EntryKind.USER_FACT) }
        val source = archive(conversation, entries)
        val result = ContinuityLogic.export(source, listOf(conversation), 3500)
        assertTrue(result.text.length <= 3500)
        assertTrue(result.omittedCount > 0)
        assertFalse(result.pinnedOverflow)
        assertTrue(result.text.contains("完整条目仍在原档案"))
        assertTrue(result.text.contains("请接着讨论刚才的结尾。"))
        assertTrue(result.text.contains("请从这句话继续回应"))
        assertEquals(entries, source.entries)
    }

    @Test fun representativeExchangesCoverEarlierDistinctCuesInsteadOfOnlyNewestRepetitions() {
        val conversation = conversation((0..8).flatMap { listOf(user("u$it", "问题$it"), assistant("a$it", "回答$it")) })
        val entries = listOf(
            ArchiveEntry("shared", EntryCategory.PEOPLE, "共同说法的由来。", listOf("u0", "a0"), continuityCue = ContinuityCue.SHARED_LANGUAGE),
            ArchiveEntry("correction", EntryCategory.DECISIONS, "当时误解后用户作出的修正。", listOf("u1"), EntryKind.USER_FACT, continuityCue = ContinuityCue.CORRECTION),
        ) + (2..7).map { ArchiveEntry("preference$it", EntryCategory.BACKGROUND, "相似的近期偏好$it。", listOf("u$it"),
            EntryKind.USER_FACT, continuityCue = ContinuityCue.PREFERENCE) }
        val source = archive(conversation, entries).copy(continuity = ContinuitySettings(enabled = true, recentTurns = 1))
        val plan = ContinuityLogic.buildPlan(source, listOf(conversation))
        val selected = plan.recommendedFragments.filter { it.id in plan.selectedFragmentIds }.flatMap { it.messages }.map { it.id }
        assertTrue("u0" in selected)
        assertTrue("u1" in selected)
        assertEquals(3, plan.selectedFragmentIds.size)
    }

    @Test fun explicitFragmentRemainsSelectedWhenRecentWindowGrowsButAutomaticRecentIsOff() {
        val conversation = conversation((0..3).flatMap { listOf(user("u$it", "问题$it"), assistant("a$it", "回答$it")) })
        val source = archive(conversation, listOf(ArchiveEntry("anchor", EntryCategory.QUOTES, "原话锚点", listOf("u0", "a0"))))
            .copy(continuity = ContinuitySettings(enabled = true, recentTurns = 1))
        val selected = ContinuityLogic.buildPlan(source, listOf(conversation)).recommendedFragments.single().id
        val changed = source.copy(continuity = source.continuity.copy(recentTurns = 4, includeRecent = false,
            selectedFragmentIds = listOf(selected)))
        val plan = ContinuityLogic.buildPlan(changed, listOf(conversation))
        assertEquals(listOf(selected), plan.selectedFragmentIds)
        assertTrue(plan.export.text.contains("问题0"))
        assertFalse(plan.export.text.contains("问题3"))
        assertFalse(plan.notices.any { it.contains("旧片段选择已失效") })
    }

    @Test fun allMemoryRestoresAutomaticEntriesWithoutExpandingOriginalSelections() {
        val conversation = conversation((0..20).flatMap { listOf(user("u$it", "问题$it"), assistant("a$it", "回答$it")) })
        val entries = (0..19).map { ArchiveEntry("e$it", EntryCategory.RECENT, "记忆条目${it}。".repeat(30),
            listOf("u$it"), EntryKind.USER_FACT) }
        val source = archive(conversation, entries).copy(sourceScopes = listOf(ArchiveSourceScope("c", "b",
            conversation.branches.single().messages.dropLast(2).map { it.id })),
            continuity = ContinuitySettings(enabled = true, includeRecent = false, selectedFragmentIds = emptyList()))
        val limited = ContinuityLogic.export(source, listOf(conversation), 2000)
        val full = ContinuityLogic.export(source, listOf(conversation), 2000, includeAllMemory = true)
        assertTrue(limited.omittedCount > 0)
        assertEquals(0, full.omittedCount)
        entries.forEach { assertTrue(full.text.contains(it.text)) }
        assertFalse(full.text.contains("问题20"))
        assertFalse(full.text.contains("【互动接续】"))
        assertEquals(entries, source.entries)
    }

    @Test fun omittedStylesAreCountedForFullMemoryOptionAndDisabledStylesStayOff() {
        val conversation = conversation((0..6).flatMap { listOf(user("u$it", "问题$it"), assistant("a$it", "回答$it")) })
        val styles = (0..5).map { ArchiveEntry("s$it", EntryCategory.PEOPLE, "具体回应方式${it}。",
            listOf("a$it", "a${it + 1}"), EntryKind.ASSISTANT_SUGGESTION, continuityCue = ContinuityCue.RESPONSE_STYLE) }
        val source = archive(conversation, styles).copy(continuity = ContinuitySettings(enabled = true,
            includeRecent = false, selectedFragmentIds = emptyList()))
        val limited = ContinuityLogic.export(source, listOf(conversation))
        assertEquals(2, limited.omittedCount)
        val full = ContinuityLogic.export(source, listOf(conversation), includeAllMemory = true)
        assertEquals(0, full.omittedCount)
        styles.forEach { assertTrue(full.text.contains(it.text)) }
        val disabled = source.copy(background = "保留背景", continuity = source.continuity.copy(includeResponseStyle = false))
        val off = ContinuityLogic.export(disabled, listOf(conversation), includeAllMemory = true)
        styles.forEach { assertFalse(off.text.contains(it.text)) }
        assertEquals(0, off.omittedCount)
    }

    private fun user(id: String, text: String) = ChatMessage(id, "user", text)
    private fun assistant(id: String, text: String) = ChatMessage(id, "assistant", text)
    private fun conversation(messages: List<ChatMessage>) = ImportedConversation("c", "虚构写作讨论", branches = listOf(ConversationBranch("b", "已选版本", messages)))
    private fun archive(conversation: ImportedConversation, entries: List<ArchiveEntry> = listOf(
        ArchiveEntry("f", EntryCategory.BACKGROUND, "已保存的合成背景。", listOf("u0"), EntryKind.USER_FACT),
    )) = Archive("a", "续聊", listOf("c"), entries, updatedAt = 1, generationMode = "summary",
        continuity = ContinuitySettings(enabled = true), sourceScopes = listOf(
            ArchiveSourceScope("c", "b", conversation.branches.first().messages.map { it.id })))
}
