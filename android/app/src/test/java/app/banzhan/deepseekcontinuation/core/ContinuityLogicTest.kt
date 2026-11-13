package app.banzhan.deepseekcontinuation.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ContinuityLogicTest {
    @Test fun oldDefaultsKeepTheExistingFactualExportUnchanged() {
        val old = Json.decodeFromString<Archive>("""{"id":"a","title":"旧档案","conversationIds":["c"],"entries":[],"updatedAt":1,"background":"完整背景"}""")
        assertFalse(old.continuity.enabled)
        assertTrue(old.sourceScopes.isEmpty())
        assertEquals(ArchiveLogic.continuationExport(old), ContinuityLogic.export(old, emptyList()))
        assertEquals(old, Json.decodeFromString<Archive>(Json.encodeToString(old)))
    }

    @Test fun scopeGapsAndEditedOrDeletedSourcesNeverBecomeInventedExchanges() {
        val conversation = conversation(3)
        val original = archive(conversation).copy(entries = listOf(
            fact(),
            ArchiveEntry("edit", EntryCategory.RECENT, "以这条修订为准", listOf("a0"), edited = true),
            ArchiveEntry("gone", EntryCategory.QUOTES, "已删除条目", listOf("u1"), deleted = true),
        ))
        val plan = ContinuityLogic.buildPlan(original, listOf(conversation))
        assertTrue(plan.recentFragments.flatMap { it.messages }.none { it.id in setOf("a0", "u1", "a1") })
        assertTrue(plan.export.text.contains("以这条修订为准"))
        assertFalse(plan.export.text.contains("回答0"))
        assertFalse(plan.export.text.contains("已删除条目"))
        assertTrue(plan.notices.any { it.contains("修订") })

        val gap = original.copy(entries = listOf(fact()), sourceScopes = listOf(
            ArchiveSourceScope("c", "b", listOf("u0", "a1", "u2", "a2"))))
        val separated = ContinuityLogic.buildPlan(gap, listOf(conversation))
        assertTrue(separated.recentFragments.none { fragment ->
            fragment.messages.any { it.id == "u0" } && fragment.messages.any { it.id == "a1" }
        })
        assertFalse(separated.export.text.contains("回答1"))
        assertTrue(separated.export.text.contains("间隔处可能还有未附带的对话"))
    }

    @Test fun invalidScopesAndAmbiguousOldBranchesNeverExpandTheSelectedRange() {
        val conversation = conversation(2)
        val base = archive(conversation)
        for (scope in listOf(ArchiveSourceScope("other", "b", listOf("u0", "a0")),
                ArchiveSourceScope("c", "missing", listOf("u0", "a0")))) {
            val plan = ContinuityLogic.buildPlan(base.copy(sourceScopes = listOf(scope)), listOf(conversation))
            assertTrue(plan.recentFragments.isEmpty())
            assertTrue(plan.export.text.contains(fact().text))
            assertFalse(plan.export.text.contains("回答0"))
        }
        val legacy = base.copy(sourceScopes = emptyList(), entries = listOf(fact().copy(sourceIds = listOf("u0"))))
        val recovered = ContinuityLogic.buildPlan(legacy, listOf(conversation))
        assertEquals(listOf("u0"), recovered.recentFragments.flatMap { it.messages }.map { it.id })
        assertFalse(recovered.export.text.contains("回答0"))
        val ambiguous = conversation.copy(branches = conversation.branches + conversation.branches.single().copy(id = "alternate"))
        assertTrue(ContinuityLogic.inferEntryScopes(legacy, listOf(ambiguous)).isEmpty())
        assertTrue(ContinuityLogic.buildPlan(legacy, listOf(ambiguous)).recentFragments.isEmpty())
        assertTrue(ContinuityLogic.captureScope(conversation, "b", emptySet()).selectedMessageIds.isEmpty())
        ContinuityLogic.validateSourceScopes(listOf(ArchiveSourceScope("c", "b", emptyList())), listOf(conversation), setOf("c"))
    }

    @Test fun recommendationsAreSmallDisjointFromRecentAndRespectExplicitEmptyOrStaleSelections() {
        val conversation = conversation(12)
        val base = archive(conversation).copy(continuity = ContinuitySettings(enabled = true, recentTurns = 2),
            entries = listOf(fact()) + (0..11).map { index ->
                ArchiveEntry("quote-$index", EntryCategory.QUOTES, "引用标记$index", listOf("a$index"), pinned = index == 0)
            })
        val plan = ContinuityLogic.buildPlan(base, listOf(conversation))
        assertEquals(8, plan.recommendedFragments.size)
        assertEquals(3, plan.selectedFragmentIds.size)
        assertTrue(plan.recommendedFragments.all { it.messages.map { message -> message.role } == listOf("user", "assistant") })
        assertTrue(plan.recommendedFragments.none { key -> plan.recentFragments.any { it.id == key.id } })
        val pinned = plan.recommendedFragments.single { fragment -> fragment.messages.any { it.id == "a0" } }
        assertTrue(pinned.id in plan.selectedFragmentIds)
        val empty = ContinuityLogic.buildPlan(base.copy(continuity = base.continuity.copy(includeRecent = false,
            selectedFragmentIds = emptyList())), listOf(conversation))
        assertTrue(empty.selectedFragmentIds.isEmpty())
        assertTrue(empty.includedFragmentIds.isEmpty())
        val stale = ContinuityLogic.buildPlan(base.copy(continuity = base.continuity.copy(includeRecent = false,
            selectedFragmentIds = listOf("old-fragment"))), listOf(conversation))
        assertTrue(stale.selectedFragmentIds.isEmpty())
        assertTrue(stale.includedFragmentIds.isEmpty())
        assertTrue(stale.notices.any { it.contains("未自动改选") })
        val onlyRecent = ContinuityLogic.buildPlan(base.copy(continuity = base.continuity.copy(selectedFragmentIds = emptyList())), listOf(conversation))
        assertEquals(plan.recentFragments.map { it.id }.toSet(), onlyRecent.includedFragmentIds.toSet())
    }

    @Test fun longFactsDoNotDisplaceRecentDialogueAndOversizedRecentQuotesUseMarkedExcerpts() {
        val conversation = conversation(1)
        val background = "需要完整保留的事实。".repeat(3500)
        val base = archive(conversation).copy(background = background)
        val plan = ContinuityLogic.buildPlan(base, listOf(conversation), maxCharacters = 1200)
        assertTrue(plan.export.text.contains(background))
        assertTrue(plan.export.text.contains("提问0"))
        assertTrue(plan.export.text.contains("回答0"))
        assertEquals(1, plan.includedFragmentIds.size)
        assertTrue(plan.notices.any { it.contains("保存为文件") })
        val huge = conversation.copy(branches = listOf(conversation.branches.single().copy(messages = listOf(
            ChatMessage("u0", "user", "😀完整原话\n".repeat(1000)), ChatMessage("a0", "assistant", "对应完整回复")))))
        val excerpted = ContinuityLogic.buildPlan(base, listOf(huge), maxCharacters = 1200)
        assertEquals(1, excerpted.includedFragmentIds.size)
        assertTrue(excerpted.export.text.contains(background))
        assertTrue(excerpted.export.text.contains("> 😀完整原话\n"))
        assertTrue(excerpted.export.text.contains("对应完整回复"))
        assertTrue(excerpted.export.text.contains("【原文节选"))
        assertTrue(excerpted.export.text.contains("〔节选说明：省略中间部分原文〕"))
        assertTrue(Regex(Regex.escape("> 😀完整原话\n")).findAll(excerpted.export.text).count() in 1..999)
        assertTrue(excerpted.notices.any { it.contains("完整原文仍在本机") })
        assertEquals(huge.branches.single().messages, excerpted.recentFragments.single().messages)
    }

    @Test fun sharedQuestionWithDivergentAnswersNeverGetsSplicedAcrossBranches() {
        val user = ChatMessage("shared", "user", "分支共享的问题")
        val first = ConversationBranch("first", "版本一", listOf(user, ChatMessage("answer-a", "assistant", "版本一回答")))
        val second = ConversationBranch("second", "版本二", listOf(user, ChatMessage("answer-b", "assistant", "版本二回答")))
        val conversation = ImportedConversation("c", "分支聊天", branches = listOf(first, second))
        val base = Archive("a", "档案", listOf("c"), listOf(fact()), continuity = ContinuitySettings(enabled = true),
            sourceScopes = listOf(ArchiveSourceScope("c", first.id, first.messages.map { it.id }),
                ArchiveSourceScope("c", second.id, second.messages.map { it.id })))
        val plan = ContinuityLogic.buildPlan(base, listOf(conversation))
        assertEquals(1, plan.includedFragmentIds.size)
        assertEquals(1, Regex("分支共享的问题").findAll(plan.export.text).count())
        assertTrue(plan.export.text.contains("版本二回答"))
        assertFalse(plan.export.text.contains("版本一回答"))
        assertTrue(plan.notices.any { it.contains("未拼接不同版本") })
    }

    @Test fun originalExportDoesNotDuplicateRawTextOrInferTheSpeakerFromUneditedSources() {
        val messages = listOf(ChatMessage("u0", "user", "原始问题"), ChatMessage("a0", "assistant", "原始回答"),
            ChatMessage("u1", "user", "旧的最后一句"))
        val conversation = ImportedConversation("c", "原文", branches = listOf(ConversationBranch("b", "当前", messages)))
        val entries = ArchiveLogic.localEntries(messages).map {
            if ("u1" in it.sourceIds) it.copy(text = "修订后的最后一句", edited = true) else it
        }
        val base = archive(conversation).copy(entries = entries, generationMode = "original",
            continuity = ContinuitySettings(enabled = true, interactionNotes = "先听我说完。"))
        val result = ContinuityLogic.buildPlan(base, listOf(conversation)).export.text
        assertTrue(result.contains(ArchiveLogic.continuationExport(base, includeGuidance = false).text))
        assertEquals(1, Regex("原始问题").findAll(result).count())
        assertTrue(result.contains("修订后的最后一句"))
        assertFalse(result.contains("旧的最后一句"))
        assertFalse(result.contains("上次最后一段是你说的话"))
        assertFalse(result.contains("【原文片段"))
        assertTrue(result.contains("先听我说完。"))
    }

    @Test fun cuesKeepLegacyDefaultsAndExplicitlyDisabledExportsUnchanged() {
        val old = Json.decodeFromString<ArchiveEntry>("""{"id":"old","category":"RECENT","text":"旧条目","sourceIds":["u0"],"kind":"USER_FACT"}""")
        assertNull(old.continuityCue)
        val conversation = conversation(1)
        val entry = old.copy(continuityCue = ContinuityCue.PREFERENCE)
        assertEquals(entry, Json.decodeFromString<ArchiveEntry>(Json.encodeToString(entry)))
        val base = archive(conversation).copy(entries = listOf(entry), continuity = ContinuitySettings(enabled = false))
        assertEquals(ArchiveLogic.continuationExport(base), ContinuityLogic.export(base, listOf(conversation)))
        assertFalse(ContinuityLogic.export(base, listOf(conversation)).text.contains("【之前说起过的事】"))
    }

    @Test fun verifiedCuesRetainFullTextAndKindsInSourceOrderWithoutDuplicatingFacts() {
        val conversation = conversation(3)
        val earlier = ArchiveEntry("preference", EntryCategory.BACKGROUND, "我希望先把想法完整说出来。", listOf("u0"), EntryKind.USER_FACT,
            continuityCue = ContinuityCue.PREFERENCE)
        val correction = ArchiveEntry("correction", EntryCategory.DECISIONS, "更正同一事项：现在我愿意先听一个简短的问题。", listOf("u1"), EntryKind.USER_FACT,
            continuityCue = ContinuityCue.CORRECTION)
        val shared = ArchiveEntry("shared", EntryCategory.PEOPLE, "用户沿用了慢慢拆开的说法，助手仍只是建议这种做法，尚未作为用户承诺。", listOf("u1", "a1"), EntryKind.ASSISTANT_SUGGESTION,
            continuityCue = ContinuityCue.SHARED_LANGUAGE)
        val open = ArchiveEntry("open", EntryCategory.RECENT, "还有另一个独立的话题，尚未讨论完成。", listOf("u2"), EntryKind.USER_FACT,
            continuityCue = ContinuityCue.OPEN_THREAD)
        val entries = listOf(open, shared, correction, earlier)
        val base = archive(conversation).copy(entries = entries, continuity = ContinuitySettings(enabled = true,
            includeRecent = false, selectedFragmentIds = emptyList(), interactionNotes = "保留我的手填说明。", nextMessage = "我现在想补充一句。"))
        val plan = ContinuityLogic.buildPlan(base, listOf(conversation))
        val text = plan.export.text
        assertTrue(text.contains("【之前说起过的事】"))
        assertFalse(text.contains("【已保存的续聊内容】"))
        entries.forEach { assertEquals(1, Regex(Regex.escape(it.text)).findAll(text).count()) }
        assertTrue(text.indexOf(earlier.text) < text.indexOf(correction.text))
        assertTrue(text.indexOf(correction.text) < text.indexOf(shared.text))
        assertTrue(text.indexOf(shared.text) < text.indexOf(open.text))
        assertFalse(text.contains("原话第"))
        assertEquals(entries, base.entries)
        assertTrue(text.contains(base.continuity.interactionNotes))
        assertTrue(text.contains(base.continuity.nextMessage))
        assertTrue(plan.includedFragmentIds.isEmpty())
    }

    @Test fun uncertainCueRolesOrSourcesRemainCompleteOrdinaryFacts() {
        val first = ConversationBranch("first", "版本一", listOf(ChatMessage("shared", "user", "共享问题"),
            ChatMessage("answer-a", "assistant", "版本一回答"), ChatMessage("unselected", "user", "未选择的消息")))
        val second = ConversationBranch("second", "版本二", listOf(first.messages.first(), ChatMessage("answer-b", "assistant", "版本二回答")))
        val conversation = ImportedConversation("c", "多版本", branches = listOf(first, second))
        val entries = listOf(
            ArchiveEntry("assistant-preference", EntryCategory.BACKGROUND, "助手猜测用户偏好，不能自动升级。", listOf("answer-a"), EntryKind.ASSISTANT_SUGGESTION,
                continuityCue = ContinuityCue.PREFERENCE),
            ArchiveEntry("assistant-correction", EntryCategory.RECENT, "只有助手提出的更正仍保留建议身份。", listOf("answer-a"), EntryKind.ASSISTANT_SUGGESTION,
                continuityCue = ContinuityCue.CORRECTION),
            ArchiveEntry("assistant-language", EntryCategory.RECENT, "助手单方说法不能标为共同用语。", listOf("answer-a"), EntryKind.ASSISTANT_SUGGESTION,
                continuityCue = ContinuityCue.SHARED_LANGUAGE),
            ArchiveEntry("ambiguous", EntryCategory.RECENT, "无法确定共享问题来自哪个版本。", listOf("shared"), EntryKind.USER_FACT,
                continuityCue = ContinuityCue.OPEN_THREAD),
            ArchiveEntry("mixed", EntryCategory.RECENT, "不能把两个版本合成一条自动线索。", listOf("answer-a", "answer-b"), EntryKind.ASSISTANT_SUGGESTION,
                continuityCue = ContinuityCue.SHARED_LANGUAGE),
            ArchiveEntry("missing", EntryCategory.RECENT, "缺失来源的全文仍需保留。", listOf("missing"), EntryKind.USER_FACT,
                continuityCue = ContinuityCue.PREFERENCE),
            ArchiveEntry("outside", EntryCategory.RECENT, "没有选择的来源不能用于自动线索。", listOf("unselected"), EntryKind.USER_FACT,
                continuityCue = ContinuityCue.PREFERENCE),
        )
        val base = archive(conversation).copy(entries = entries, sourceScopes = listOf(
            ArchiveSourceScope("c", "first", listOf("shared", "answer-a")), ArchiveSourceScope("c", "second", listOf("shared", "answer-b"))),
            continuity = ContinuitySettings(enabled = true, includeRecent = false, selectedFragmentIds = emptyList()))
        val text = ContinuityLogic.export(base, listOf(conversation)).text
        assertFalse(text.contains("【之前说起过的事】"))
        assertTrue(text.contains(ArchiveLogic.continuationExport(base, includeGuidance = false).text))
        entries.forEach { assertEquals(1, Regex(Regex.escape(it.text)).findAll(text).count()) }
    }

    @Test fun editedDeletedAndClearedCuesDoNotReturnButPinOnlyKeepsTheCue() {
        val conversation = conversation(3)
        val cue = ArchiveEntry("preference", EntryCategory.BACKGROUND, "沿用我的交流偏好。", listOf("u0"), EntryKind.USER_FACT,
            continuityCue = ContinuityCue.PREFERENCE)
        val settings = ContinuitySettings(enabled = true, includeRecent = false, recentTurns = 1)
        val base = archive(conversation).copy(entries = listOf(cue), continuity = settings)
        assertEquals(1, ContinuityLogic.buildPlan(base, listOf(conversation)).recommendedFragments.size)
        for (entry in listOf(cue.copy(edited = true, text = "以手改内容为准。"), cue.copy(deleted = true), cue.copy(continuityCue = null))) {
            val plan = ContinuityLogic.buildPlan(base.copy(entries = listOf(fact(), entry)), listOf(conversation))
            assertFalse(plan.export.text.contains("【之前说起过的事】"))
            assertTrue(plan.recommendedFragments.isEmpty())
            if (entry.deleted) assertFalse(plan.export.text.contains(entry.text)) else assertTrue(plan.export.text.contains(entry.text))
        }
        val blockedByOtherEntry = ContinuityLogic.buildPlan(base.copy(entries = listOf(cue,
            fact().copy(id = "edited", sourceIds = listOf("u0"), edited = true))), listOf(conversation))
        assertFalse(blockedByOtherEntry.export.text.contains("【之前说起过的事】"))
        assertTrue(blockedByOtherEntry.export.text.contains(cue.text))
        for (pinned in listOf(true, false)) {
            val plan = ContinuityLogic.buildPlan(base.copy(entries = listOf(cue.copy(pinned = pinned))), listOf(conversation))
            assertTrue(plan.export.text.contains("【之前说起过的事】"))
            assertEquals(1, plan.recommendedFragments.size)
        }
    }

    @Test fun cueAnchorsRespectExactRangesAndExplicitEmptySelection() {
        val conversation = conversation(4)
        val cue = ArchiveEntry("multi-source", EntryCategory.BACKGROUND, "两个已选位置支持的偏好。", listOf("u0", "u2"), EntryKind.USER_FACT,
            continuityCue = ContinuityCue.PREFERENCE)
        val base = archive(conversation).copy(entries = listOf(cue), sourceScopes = listOf(
            ArchiveSourceScope("c", "b", listOf("u0", "a0")), ArchiveSourceScope("c", "b", listOf("u2", "a2", "u3", "a3"))),
            continuity = ContinuitySettings(enabled = true, includeRecent = false, recentTurns = 1))
        val plan = ContinuityLogic.buildPlan(base, listOf(conversation))
        assertEquals(2, plan.recommendedFragments.size)
        assertEquals(2, plan.selectedFragmentIds.size)
        assertEquals(2, plan.includedFragmentIds.size)
        assertTrue(plan.export.text.contains(cue.text))
        assertFalse(plan.export.text.contains("提问1"))
        assertTrue(plan.recommendedFragments.all { it.messages.size == 2 })
        val empty = ContinuityLogic.buildPlan(base.copy(continuity = base.continuity.copy(selectedFragmentIds = emptyList())), listOf(conversation))
        assertTrue(empty.selectedFragmentIds.isEmpty())
        assertTrue(empty.includedFragmentIds.isEmpty())
        assertTrue(empty.export.text.contains(cue.text))
        assertFalse(empty.export.text.contains("【原文片段"))
    }

    @Test fun confirmedCueAnchorsStayOnTheirBranchWhenAUserMessageIsShared() {
        val shared = ChatMessage("shared", "user", "共享提问")
        val first = ConversationBranch("first", "版本一", listOf(shared, ChatMessage("answer-a", "assistant", "线索所在版本的回答")))
        val second = ConversationBranch("second", "版本二", listOf(shared, ChatMessage("answer-b", "assistant", "另一个版本的回答"),
            ChatMessage("later-user", "user", "版本二的后续问题"), ChatMessage("later-assistant", "assistant", "版本二的后续回答")))
        val conversation = ImportedConversation("c", "共享开头", branches = listOf(first, second))
        val cue = ArchiveEntry("shared-language", EntryCategory.PEOPLE, "用户沿用版本一讨论中的说法。", listOf("shared", "answer-a"), EntryKind.UNCONFIRMED,
            continuityCue = ContinuityCue.SHARED_LANGUAGE)
        val base = archive(conversation).copy(entries = listOf(cue), sourceScopes = listOf(
            ArchiveSourceScope("c", first.id, first.messages.map { it.id }), ArchiveSourceScope("c", second.id, second.messages.map { it.id })),
            continuity = ContinuitySettings(enabled = true, includeRecent = false, recentTurns = 1))
        val plan = ContinuityLogic.buildPlan(base, listOf(conversation))
        assertEquals(listOf("first"), plan.recommendedFragments.map { it.branchId })
        assertEquals(plan.recommendedFragments.map { it.id }, plan.includedFragmentIds)
        assertTrue(plan.export.text.contains("线索所在版本的回答"))
        assertFalse(plan.export.text.contains("另一个版本的回答"))
        assertTrue(plan.export.text.contains(cue.text))
    }

    /** Fixed synthetic exports for a human context/wording review; no private input or network. */
    @Test fun syntheticExportsForReviewRespectTheLastSpeakerAndTheUsersNextMessage() {
        val messages = listOf(
            ChatMessage("u0", "user", "我在重新练习写作，总觉得句子不够好。"),
            ChatMessage("a0", "assistant", "可以先写一个不求完美的小片段，再一起看哪句话最接近你的意思。"),
            ChatMessage("u1", "user", "上次这样慢慢拆开问题，我会比较放松。"),
            ChatMessage("a1", "assistant", "那就沿用这个节奏：先听你说完，再一起挑一个小问题。"),
            ChatMessage("u2", "user", "今天卡在开头，我想先写窗边的声音。"),
        )
        val conversation = ImportedConversation("c", "写作练习", branches = listOf(ConversationBranch("b", "已选择的版本", messages)))
        val base = archive(conversation).copy(title = "接着聊写作", entries = listOf(
            ArchiveEntry("fact", EntryCategory.RECENT, "用户正在重新练习写作。", listOf("u0"), EntryKind.USER_FACT)),
            continuity = ContinuitySettings(enabled = true, interactionNotes = "别一开始就列很多步骤。先回应我正在说的，再问一个具体的小问题。"))
        val pending = ContinuityLogic.buildPlan(base, listOf(conversation))
        assertTrue(pending.recentFragments.last().title.contains("上次停在我的发言"))
        assertEquals(messages.last(), pending.recentFragments.last().messages.single())
        assertTrue(pending.export.text.contains("上次停在我的这句话"))
        println("SYNTHETIC_PENDING_USER_EXPORT\n${pending.export.text}")

        val finished = base.copy(sourceScopes = listOf(ArchiveSourceScope("c", "b", messages.dropLast(1).map { it.id })))
        val assistantLast = ContinuityLogic.buildPlan(finished, listOf(conversation))
        assertTrue(assistantLast.export.text.contains("上次最后一段是你说的话"))
        println("SYNTHETIC_ASSISTANT_LAST_EXPORT\n${assistantLast.export.text}")
        val next = ContinuityLogic.export(finished.copy(continuity = finished.continuity.copy(nextMessage = "我先写了一句：窗外有很轻的脚步声。")), listOf(conversation))
        assertTrue(next.text.contains("【我接下来想说】\n我先写了一句：窗外有很轻的脚步声。"))
        assertFalse(next.text.contains("上次最后一段是你说的话"))
        println("SYNTHETIC_NEXT_MESSAGE_EXPORT\n${next.text}")
    }

    private fun conversation(rounds: Int) = ImportedConversation("c", "合成聊天", branches = listOf(
        ConversationBranch("b", "当前版本", (0 until rounds).flatMap { index -> listOf(
            ChatMessage("u$index", "user", "提问$index"), ChatMessage("a$index", "assistant", "回答$index")) })))

    private fun fact() = ArchiveEntry("fact", EntryCategory.RECENT, "事实档案完整保留。", kind = EntryKind.USER_FACT)

    private fun archive(conversation: ImportedConversation) = Archive("a", "合成档案", listOf(conversation.id), listOf(fact()),
        continuity = ContinuitySettings(enabled = true), sourceScopes = listOf(ArchiveSourceScope(conversation.id,
            conversation.branches.first().id, conversation.branches.first().messages.map { it.id })))
}
