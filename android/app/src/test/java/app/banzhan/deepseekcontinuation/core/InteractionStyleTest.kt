package app.banzhan.deepseekcontinuation.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InteractionStyleTest {
    @Test fun currentInstructionsAndExplicitUserCuesComeBeforeObservedStyleAndMemory() {
        val conversation = conversation(6)
        val preference = entry("preference", "明确偏好：先问我想从哪里说起。", listOf("u0"), EntryKind.USER_FACT, ContinuityCue.PREFERENCE)
        val correction = entry("correction", "事实修正：这次聚会是在周二，不是周一。", listOf("u1"), EntryKind.USER_FACT, ContinuityCue.CORRECTION)
        val shared = entry("shared", "共同说法：先拆开一个小问题。", listOf("u2", "a2"), EntryKind.UNCONFIRMED, ContinuityCue.SHARED_LANGUAGE)
        val style = style("style", "回应做法：先复述具体疑问，再给一个短例子。", "a0", "a1")
        val fact = entry("fact", "完整必要记忆。".repeat(1600), listOf("u3"), EntryKind.USER_FACT, null)
        val open = entry("open", "未完事项：下一次想继续讨论结尾。", listOf("u4"), EntryKind.USER_FACT, ContinuityCue.OPEN_THREAD)
        val source = archive(conversation, listOf(open, fact, style, shared, correction, preference)).copy(
            continuity = ContinuitySettings(enabled = true, recentTurns = 1, selectedFragmentIds = emptyList(),
                interactionNotes = "当前要求：今天直接回答，不要先复述。", nextMessage = "接下来：我想先说结尾。"))
        val plan = ContinuityLogic.buildPlan(source, listOf(conversation))
        val text = plan.export.text
        val ordered = listOf(source.continuity.interactionNotes, preference.text, correction.text, shared.text, style.text,
            open.text, fact.text, "【原文片段", source.continuity.nextMessage)
        assertTrue(ordered.all { text.contains(it) })
        assertTrue(ordered.map { text.indexOf(it) }.zipWithNext().all { (first, second) -> first < second })
        source.entries.forEach { assertEquals(1, Regex(Regex.escape(it.text)).findAll(text).count()) }
        assertEquals(1, plan.responseStyleCount)
        assertTrue(text.contains("回应方式观察（非用户要求）"))
        assertTrue(text.contains("来源角色：助手 · 第2、4条"))
        assertTrue(text.contains("与历史观察不同时以此为准"))
        assertTrue(text.contains("事实修正不自动变成沟通偏好"))
        assertTrue(text.contains("观察不等于用户偏好、助手身份或关系承诺"))
        assertTrue(text.contains("不要从我的沉默推断偏好"))
        assertTrue(text.contains("不沿用历史错误、危险建议、排他或过度承诺"))
        assertTrue(text.contains("优先回应我接下来想说的内容"))
    }

    @Test fun styleNeedsTwoDistinctAssistantSourcesAndAnAllowedKind() {
        val conversation = conversation(3)
        val invalid = listOf(
            style("one", "只有一条助手来源的观察仍完整保存。", "a0"),
            style("duplicate", "重复编号不算两个独立来源。", "a0", "a0"),
            style("mixed", "用户与助手混合来源不能作为助手观察。", "u0", "a0"),
            style("user-kind", "助手观察不得标为用户事实。", "a0", "a1").copy(kind = EntryKind.USER_FACT),
            style("missing", "来源缺失的观察仍完整保存。", "a0", "missing"),
        )
        val valid = style("valid", "两条助手原话支持的具体做法。", "a0", "a1").copy(kind = EntryKind.UNCONFIRMED)
        val plan = ContinuityLogic.buildPlan(archive(conversation, invalid + valid), listOf(conversation))
        assertEquals(1, plan.responseStyleCount)
        assertTrue(plan.export.text.indexOf(valid.text) < plan.export.text.indexOf("【必要记忆 · 历史资料】"))
        invalid.forEach {
            assertTrue(plan.export.text.indexOf(it.text) > plan.export.text.indexOf("【必要记忆 · 历史资料】"))
            assertEquals(1, Regex(Regex.escape(it.text)).findAll(plan.export.text).count())
        }
    }

    @Test fun disablingStyleOmitsOnlyValidatedAutomaticStyleWithoutChangingTheArchive() {
        val conversation = conversation(3)
        val valid = style("valid", "已校验的自动回应做法。", "a0", "a1")
        val invalid = style("invalid", "证据不足的正文仍保留。", "a2")
        val source = archive(conversation, listOf(valid, invalid)).copy(continuity =
            ContinuitySettings(enabled = true, includeRecent = false, selectedFragmentIds = emptyList(), includeResponseStyle = false))
        // updatedAt has a clock-based default; encode it consistently across both reads.
        val snapshotJson = Json { encodeDefaults = true }
        val snapshot = snapshotJson.encodeToString(source)
        val plan = ContinuityLogic.buildPlan(source, listOf(conversation))
        assertEquals(1, plan.responseStyleCount)
        assertFalse(plan.export.text.contains(valid.text))
        assertFalse(plan.export.text.contains("【观察到的助手回应方式】"))
        assertTrue(plan.export.text.contains(invalid.text))
        assertTrue(plan.notices.any { it.contains("已关闭自动回应方式") && it.contains("条目仍保留") })
        assertEquals(snapshot, snapshotJson.encodeToString(source))
        val disabled = source.copy(continuity = source.continuity.copy(enabled = false, includeResponseStyle = true))
        assertEquals(ArchiveLogic.continuationExport(disabled.copy(entries = listOf(invalid))),
            ContinuityLogic.export(disabled, listOf(conversation)))
        val old = source.copy(entries = listOf(invalid.copy(continuityCue = null)), continuity = ContinuitySettings(enabled = false))
        assertEquals(ArchiveLogic.continuationExport(old), ContinuityLogic.export(old, listOf(conversation)))
        assertTrue(Json.decodeFromString<ContinuitySettings>("""{"enabled":false}""").includeResponseStyle)
    }

    @Test fun onlyFourDistinctRecentRepresentativesAreAddedAndAllStoredEntriesRemain() {
        val conversation = conversation(8)
        val styles = (0..5).map { index -> style("style-$index", "具体回应做法编号$index。", "a$index", "a${index + 1}") }
        val duplicate = styles.last().copy(id = "same-wording")
        val source = archive(conversation, styles + duplicate)
        val plan = ContinuityLogic.buildPlan(source, listOf(conversation))
        assertEquals(7, plan.responseStyleCount)
        assertEquals(4, Regex("回应方式观察（非用户要求）").findAll(plan.export.text).count())
        (0..1).forEach { assertFalse(plan.export.text.contains(styles[it].text)) }
        (2..5).forEach { assertEquals(1, Regex(Regex.escape(styles[it].text)).findAll(plan.export.text).count()) }
        assertTrue((2..5).map { plan.export.text.indexOf(styles[it].text) }.zipWithNext().all { (a, b) -> a < b })
        assertTrue(plan.notices.any { it.contains("4 条代表回应方式") && it.contains("其余 3 条") })
        assertEquals(styles + duplicate, source.entries)
        assertFalse(plan.export.text.contains("【必要记忆 · 历史资料】"))
    }

    @Test fun stylesCannotCrossBranchesOrUseEditedDeletedOrUnselectedSources() {
        val conversation = conversation(3)
        val first = conversation.branches.single()
        val second = first.copy(id = "other", label = "另一个版本", messages = first.messages.map {
            if (it.id == "a1") it.copy(id = "other-a1", content = "另一个版本的回复") else it
        })
        val branched = conversation.copy(branches = listOf(first, second))
        val sharedSources = style("ambiguous", "共享来源不能猜属于哪个版本。", "a0", "a2")
        val mixedBranches = style("mixed", "跨版本的来源不能拼成观察。", "a1", "other-a1")
        val source = archive(branched, listOf(sharedSources, mixedBranches)).copy(sourceScopes = listOf(
            ArchiveSourceScope("c", first.id, first.messages.map { it.id }), ArchiveSourceScope("c", second.id, second.messages.map { it.id })))
        val ambiguous = ContinuityLogic.buildPlan(source, listOf(branched))
        assertEquals(0, ambiguous.responseStyleCount)
        assertTrue(ambiguous.export.text.contains(sharedSources.text))
        assertTrue(ambiguous.export.text.contains(mixedBranches.text))
        val valid = style("valid", "来源本可支持的回应方式。", "a0", "a1")
        for (protected in listOf(valid.copy(edited = true, text = "用户手改的全文。"), valid.copy(deleted = true))) {
            val plan = ContinuityLogic.buildPlan(archive(conversation, listOf(protected)), listOf(conversation))
            assertEquals(0, plan.responseStyleCount)
            assertEquals(!protected.deleted, plan.export.text.contains(protected.text))
        }
        val manual = entry("edited", "优先保留此处修订。", listOf("a0"), EntryKind.UNCONFIRMED, null).copy(edited = true)
        val blocked = ContinuityLogic.buildPlan(archive(conversation, listOf(valid, manual)), listOf(conversation))
        assertEquals(0, blocked.responseStyleCount)
        assertTrue(blocked.export.text.contains(valid.text))
        val unselected = archive(conversation, listOf(valid)).copy(sourceScopes = listOf(ArchiveSourceScope("c", "b", listOf("u0", "a0"))))
        assertEquals(0, ContinuityLogic.buildPlan(unselected, listOf(conversation)).responseStyleCount)
    }

    @Test fun explicitlySelectedOriginalExchangesSurviveTurningOffAutomaticStyle() {
        val conversation = conversation(4)
        val observation = style("style", "先确认具体问题，再解释理由。", "a0", "a1")
        val base = archive(conversation, listOf(observation)).copy(continuity = ContinuitySettings(enabled = true, includeRecent = false, recentTurns = 1))
        val active = ContinuityLogic.buildPlan(base, listOf(conversation))
        assertEquals(2, active.recommendedFragments.size)
        val chosen = active.recommendedFragments.first().id
        val off = ContinuityLogic.buildPlan(base.copy(continuity = base.continuity.copy(includeResponseStyle = false,
            selectedFragmentIds = listOf(chosen))), listOf(conversation))
        assertEquals(listOf(chosen), off.selectedFragmentIds)
        assertEquals(listOf(chosen), off.includedFragmentIds)
        assertTrue(off.export.text.contains("回答0"))
        assertFalse(off.export.text.contains(observation.text))
        val empty = ContinuityLogic.buildPlan(base.copy(continuity = base.continuity.copy(selectedFragmentIds = emptyList())), listOf(conversation))
        assertTrue(empty.includedFragmentIds.isEmpty())
    }

    private fun conversation(rounds: Int) = ImportedConversation("c", "合成对话", branches = listOf(ConversationBranch("b", "已选版本",
        (0 until rounds).flatMap { listOf(ChatMessage("u$it", "user", "问题$it"), ChatMessage("a$it", "assistant", "回答$it")) })))

    private fun entry(id: String, text: String, sources: List<String>, kind: EntryKind, cue: ContinuityCue?) =
        ArchiveEntry(id, EntryCategory.RECENT, text, sources, kind, continuityCue = cue)

    private fun style(id: String, text: String, vararg sources: String) = entry(id, text, sources.toList(), EntryKind.ASSISTANT_SUGGESTION, ContinuityCue.RESPONSE_STYLE)

    private fun archive(conversation: ImportedConversation, entries: List<ArchiveEntry>) = Archive("archive", "续聊", listOf(conversation.id), entries,
        continuity = ContinuitySettings(enabled = true, includeRecent = false, selectedFragmentIds = emptyList()),
        sourceScopes = listOf(ArchiveSourceScope(conversation.id, conversation.branches.first().id, conversation.branches.first().messages.map { it.id })))
}
