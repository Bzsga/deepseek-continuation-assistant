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
        assertTrue(separated.export.text.contains("片段之间可能有未选内容"))
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

    @Test fun longFactsDoNotDisplaceRecentDialogueAndOversizedQuotesAreSkippedWhole() {
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
        val skipped = ContinuityLogic.buildPlan(base, listOf(huge), maxCharacters = 1200)
        assertTrue(skipped.includedFragmentIds.isEmpty())
        assertTrue(skipped.export.text.contains(background))
        assertFalse(skipped.export.text.contains("😀"))
        assertTrue(skipped.notices.any { it.contains("未截断原话") })
        assertEquals(huge.branches.single().messages, skipped.recentFragments.single().messages)
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
        assertTrue(result.contains(ArchiveLogic.continuationExport(base).text))
        assertEquals(1, Regex("原始问题").findAll(result).count())
        assertTrue(result.contains("修订后的最后一句"))
        assertFalse(result.contains("旧的最后一句"))
        assertFalse(result.contains("不必重复回答上一轮"))
        assertFalse(result.contains("【原文片段"))
        assertTrue(result.contains("先听我说完。"))
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
        assertTrue(pending.recentFragments.last().title.contains("待回应"))
        assertEquals(messages.last(), pending.recentFragments.last().messages.single())
        assertTrue(pending.export.text.contains("请从这句话继续回应"))
        println("SYNTHETIC_PENDING_USER_EXPORT\n${pending.export.text}")

        val finished = base.copy(sourceScopes = listOf(ArchiveSourceScope("c", "b", messages.dropLast(1).map { it.id })))
        val assistantLast = ContinuityLogic.buildPlan(finished, listOf(conversation))
        assertTrue(assistantLast.export.text.contains("不必重复回答上一轮"))
        println("SYNTHETIC_ASSISTANT_LAST_EXPORT\n${assistantLast.export.text}")
        val next = ContinuityLogic.export(finished.copy(continuity = finished.continuity.copy(nextMessage = "我先写了一句：窗外有很轻的脚步声。")), listOf(conversation))
        assertTrue(next.text.contains("【我接下来想说】\n我先写了一句：窗外有很轻的脚步声。"))
        assertFalse(next.text.contains("不必重复回答上一轮"))
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
