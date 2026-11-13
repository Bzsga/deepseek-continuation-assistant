package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class ContinuityMemoryTest {
    @Test fun wholeEntryUsesActualRenderedBudgetAndPreservesLiteralText() {
        val entry = entry("one", "  我在2026-11-05借出12345.67元。😀\n下一行仍有空格  ")
        val archive = archive(listOf(entry)).copy(title = "完整标题😀")
        val expected = full(archive)
        assertEquals(expected, export(archive, expected.text.length))
        val tooSmall = export(archive, expected.text.length - 1)
        assertEquals("", tooSmall.text)
        assertEquals(1, tooSmall.omittedCount)
        assertFalse(tooSmall.pinnedOverflow)
    }

    @Test fun zeroBudgetRetainsAllProtectedContentAndNeverRevivesDeletedOrBlankEntries() {
        val pinned = entry("shared", "固定正文😀".repeat(180)).copy(pinned = true)
        val edited = entry("edited", "手改正文\n".repeat(180)).copy(edited = true)
        val manual = entry("manual", "无来源手工正文").copy(sourceIds = emptyList())
        // Identical IDs must not let selection of a protected item pull in an automatic item.
        val automatic = entry("shared", "预算不足的自动摘要")
        val deleted = entry("deleted", "删除的固定正文").copy(deleted = true, pinned = true, edited = true)
        val blank = entry("blank", " \n\t").copy(pinned = true)
        val archive = archive(listOf(pinned, edited, manual, automatic, deleted, blank))
            .copy(background = "全部背景😀\n".repeat(200))
        val result = export(archive, 0)
        assertEquals(full(archive.copy(entries = listOf(pinned, edited, manual))).text, result.text)
        assertEquals(1, result.omittedCount)
        assertFalse(result.pinnedOverflow)
        assertEquals(listOf(pinned, edited, manual, automatic, deleted, blank), archive.entries)
        assertTrue(result.text.contains(archive.background))
    }

    @Test fun emptyAutomaticSelectionAndEmptyArchiveReturnEmptyText() {
        val populated = export(archive(listOf(entry("a", "普通内容"))), 0)
        assertEquals(ExportResult("", 1, false), populated)
        assertEquals(ExportResult("", 0, false), export(archive(emptyList()).copy(background = " \n"), 0))
    }

    @Test fun userFactWinsOverOrdinaryAdviceEvenWithoutUsableSources() {
        val advice = entry("advice", "建议试试每天运动。").copy(kind = EntryKind.ASSISTANT_SUGGESTION)
        val fact = entry("fact", "我周末要去看奶奶。")
        val archive = archive(listOf(advice, fact))
        val budget = full(archive.copy(entries = listOf(fact))).text.length
        assertEquals(full(archive.copy(entries = listOf(fact))).text, export(archive, budget).text)
        assertEquals(1, export(archive, budget).omittedCount)
    }

    @Test fun selectedBranchRecencyAndCategoryCoverageChooseWholeFacts() {
        val old = entry("old", "旧背景：喜欢周末出门。")
        val recent = entry("recent", "新背景：改成周末休息。")
        val person = entry("person", "小林：每周三一起散步。").copy(category = EntryCategory.PEOPLE)
        val messages = listOf(old, person, recent).map { ChatMessage(it.id, "user", "原文-${it.id}") }
        val conversation = conversation(messages)
        val archive = archive(listOf(old, recent, person)).copy(sourceScopes = listOf(scope(messages)))
        val expected = full(archive.copy(entries = listOf(recent, person)))
        val result = ContinuityMemory.continuationMemory(archive, listOf(conversation), expected.text.length)
        assertEquals(expected.text, result.text)
        assertEquals(1, result.omittedCount)
        assertTrue(result.text.indexOf(recent.text) < result.text.indexOf(person.text))
    }

    @Test fun newerSourcesAffectSelectionButOutputKeepsSavedOrder() {
        val first = entry("first", "甲：第一项。")
        val second = entry("second", "乙：第二项。")
        val third = entry("third", "丙：第三项。")
        val messages = listOf(first, third, second).map { ChatMessage(it.id, "user", "原文") }
        val archive = archive(listOf(first, second, third)).copy(sourceScopes = listOf(scope(messages)))
        val expected = full(archive.copy(entries = listOf(second, third)))
        val result = ContinuityMemory.continuationMemory(archive, listOf(conversation(messages)), expected.text.length)
        assertEquals(expected.text, result.text)
        assertEquals(1, result.omittedCount)
    }

    @Test fun unselectedSourceCannotGainRecencyOrIntroduceOriginalText() {
        val outside = entry("outside", "仅存摘要甲。")
        val selected = entry("selected", "仅存摘要乙。")
        val messages = listOf(ChatMessage("selected", "user", "不可额外导入的原文"),
            ChatMessage("outside", "user", "不属于所选范围的原文", "2099-12-31"))
        val archive = archive(listOf(outside, selected)).copy(sourceScopes = listOf(scope(messages.take(1))))
        val expected = full(archive.copy(entries = listOf(selected)))
        val result = ContinuityMemory.continuationMemory(archive, listOf(conversation(messages)), expected.text.length)
        assertEquals(expected.text, result.text)
        assertFalse(result.text.contains("原文"))
        val enough = ContinuityMemory.continuationMemory(archive, listOf(conversation(messages)), 10000)
        assertTrue(enough.text.contains(outside.text))
        assertEquals(listOf(scope(messages.take(1))), archive.sourceScopes)
    }

    @Test fun ambiguousBranchesAndInvalidScopesDoNotDiscardSavedFacts() {
        val saved = entry("shared", "已有事实保留，不能补原文。")
        val branchA = ConversationBranch("a", "a", listOf(ChatMessage("shared", "user", "分支甲原文")))
        val branchB = ConversationBranch("b", "b", listOf(ChatMessage("shared", "user", "分支乙原文")))
        val source = ImportedConversation("c", "chat", branches = listOf(branchA, branchB))
        val archive = archive(listOf(saved)).copy(sourceScopes = listOf(
            ArchiveSourceScope("c", "a", listOf("shared")), ArchiveSourceScope("c", "b", listOf("shared"))))
        assertEquals(full(archive), ContinuityMemory.continuationMemory(archive, listOf(source), 10000))
        val invalid = archive.copy(sourceScopes = listOf(ArchiveSourceScope("c", "a", listOf("missing", "shared"))))
        assertEquals(full(invalid), ContinuityMemory.continuationMemory(invalid, listOf(source), 10000))
        assertEquals(full(archive), ContinuityMemory.continuationMemory(archive, emptyList(), 10000))
    }

    @Test fun oversizedAutomaticEntryDoesNotBlockLaterShortEntry() {
        val long = entry("long", "长段落".repeat(1000))
        val short = entry("short", "稍后继续这个问题。")
        val archive = archive(listOf(long, short))
        val expected = full(archive.copy(entries = listOf(short)))
        val result = export(archive, expected.text.length)
        assertEquals(expected.text, result.text)
        assertEquals(1, result.omittedCount)
    }

    @Test fun kindTransitionsAroundProtectedEntriesFitTheExactBudget() {
        val first = entry("first", "第一项事实。")
        val protected = entry("pinned", "保留的讨论建议。")
            .copy(kind = EntryKind.ASSISTANT_SUGGESTION, pinned = true)
        val third = entry("third", "第二项事实。")
        val archive = archive(listOf(first, protected, third))
        assertEquals(full(archive), export(archive, full(archive).text.length))
        val sameKindProtected = archive.copy(entries = listOf(first, protected.copy(kind = EntryKind.USER_FACT), third))
        assertEquals(full(sameKindProtected), export(sameKindProtected, full(sameKindProtected).text.length))
    }

    @Test fun attributedAndTerseAdviceUseTheirActualLeadsAtTheExactBudget() {
        val first = entry("first", "也许可以先留一点时间。").copy(kind = EntryKind.ASSISTANT_SUGGESTION)
        val saved = entry("saved", "你当时说，不必急着想明白。")
            .copy(kind = EntryKind.ASSISTANT_SUGGESTION, pinned = true)
        val last = entry("last", "另一种可能是先放一放。").copy(kind = EntryKind.ASSISTANT_SUGGESTION)
        val archive = archive(listOf(first, saved, last))
        val expected = full(archive)
        assertEquals(expected, export(archive, expected.text.length))
        assertEquals(listOf(first, saved, last), archive.entries)
    }

    @Test fun mixedKindsAndCategoriesStayWithinBudgetWithStableWholeEntries() {
        val entries = (0 until 45).map { index ->
            entry("item-$index", "条目$index：原样保留😀123.45。".repeat(index % 3 + 1)).copy(
                category = EntryCategory.entries[index % EntryCategory.entries.size],
                kind = EntryKind.entries[index % EntryKind.entries.size],
            )
        }
        val archive = archive(entries)
        for (budget in listOf(100, 400, 900, full(archive).text.length)) {
            val result = export(archive, budget)
            assertTrue("budget $budget", result.text.length <= budget)
            val included = entries.filter { result.text.contains(it.text) }
            assertEquals(entries.size - included.size, result.omittedCount)
            assertEquals(full(archive.copy(entries = included)).text, result.text)
            assertEquals(result, export(archive, budget))
        }
    }

    @Test fun originalLegacyAndUnknownModesRemainCompleteAtZeroBudget() {
        val entries = listOf(entry("a", "完整原文😀".repeat(200)), entry("b", "另一段正文"))
        for (mode in listOf("original", "legacy", "future-mode")) {
            val archive = archive(entries).copy(generationMode = mode, background = "完整背景")
            assertEquals(full(archive), export(archive, 0))
        }
    }

    @Test fun guidanceFlagOnlyRemovesTheExistingOpeningInstructions() {
        val archive = archive(listOf(entry("a", "内容保持原样。")))
        val default = ArchiveLogic.continuationExport(archive)
        assertEquals(default, ArchiveLogic.continuationExport(archive, includeGuidance = true))
        val noGuidance = full(archive)
        assertTrue(noGuidance.text.startsWith("【续聊摘要】"))
        assertTrue(default.text.endsWith(noGuidance.text))
        assertTrue(default.text.length > noGuidance.text.length)
        assertEquals(0, noGuidance.omittedCount)
    }

    @Test fun currentTopicCanRetainAnOlderRelevantMemoryAheadOfNewerUnrelatedFacts() {
        val database = entry("database", "数据库连接池上限为二十。")
        val holiday = entry("holiday", "周末准备去海边散步。")
        val messages = listOf(ChatMessage("database", "user", "早期说明甲"),
            ChatMessage("holiday", "user", "早期说明乙"))
        val archive = archive(listOf(database, holiday)).copy(sourceScopes = listOf(scope(messages)),
            continuity = ContinuitySettings(enabled = true, nextMessage = "数据库连接池为什么超时"))
        val expected = full(archive.copy(entries = listOf(database)))
        val result = ContinuityMemory.continuationMemory(archive, listOf(conversation(messages)), expected.text.length)
        assertEquals(expected.text, result.text)
        assertEquals(1, result.omittedCount)
        assertEquals(listOf(database, holiday), archive.entries)
    }

    @Test fun topicMatchingStillCoversDifferentMemoryCategories() {
        val database = entry("database", "数据库备份每周进行。")
        val duplicateTopic = entry("backup", "数据库备份采用压缩。")
        val owner = entry("owner", "数据库由小林维护。几项职责都已说明。")
            .copy(category = EntryCategory.PEOPLE)
        val archive = archive(listOf(database, duplicateTopic, owner)).copy(
            continuity = ContinuitySettings(enabled = true, nextMessage = "数据库备份"))
        val expected = full(archive.copy(entries = listOf(database, owner)))
        val result = ContinuityMemory.continuationMemory(archive, emptyList(), expected.text.length)
        assertEquals(expected.text, result.text)
    }

    @Test fun noTopicMatchPreservesTheExistingPriorityAndExplicitRequirementsStayWhole() {
        val entries = listOf(entry("a", "周末去海边散步。"), entry("b", "今天在家做饭。"))
        val base = archive(entries)
        val budget = full(base.copy(entries = entries.take(1))).text.length
        assertEquals(export(base, budget), export(base.copy(continuity = ContinuitySettings(enabled = true,
            nextMessage = "PostgreSQL")), budget))
        val preference = entry("preference", "讨论时先听我说完，不急着给建议。")
            .copy(continuityCue = ContinuityCue.PREFERENCE)
        assertEquals(full(base.copy(entries = listOf(preference))).text,
            export(base.copy(entries = entries + preference), 0).text)
    }

    private fun entry(id: String, text: String) = ArchiveEntry(
        id, EntryCategory.BACKGROUND, text, listOf(id), EntryKind.USER_FACT,
    )

    private fun archive(entries: List<ArchiveEntry>) = Archive("archive", "标题", listOf("c"), entries,
        generationMode = "summary")

    private fun conversation(messages: List<ChatMessage>) = ImportedConversation(
        "c", "chat", branches = listOf(ConversationBranch("branch", "main", messages)),
    )

    private fun scope(messages: List<ChatMessage>) = ArchiveSourceScope("c", "branch", messages.map { it.id })

    private fun full(archive: Archive) = if (archive.entries.isEmpty() && archive.background.isBlank()) ExportResult("", 0, false)
        else ArchiveLogic.continuationExport(archive, includeGuidance = false)

    private fun export(archive: Archive, budget: Int) = ContinuityMemory.continuationMemory(archive, emptyList(), budget)
}
