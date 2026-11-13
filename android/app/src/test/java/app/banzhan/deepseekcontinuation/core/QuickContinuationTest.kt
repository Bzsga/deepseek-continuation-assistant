package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class QuickContinuationTest {
    private val messages = (1..8).flatMap { index -> listOf(
        ChatMessage("u$index", "user", "问题$index：关于合成故事的这一部分。"),
        ChatMessage("a$index", "assistant", "回答$index：保留这一轮的讨论进展。"),
    ) } + ChatMessage("end", "user", "末条问题：我想接着写结尾。")
    private val conversation = ImportedConversation("c", "故事", branches = listOf(
        ConversationBranch("main", "当前版本", messages),
        ConversationBranch("other", "其他版本", listOf(ChatMessage("secret", "user", "不在所选范围的原文"))),
    ))

    @Test fun selectionProducesRecentUsableTextWithoutClaimingAiOrFullHistory() {
        val selected = messages.map { it.id }.toSet()
        val archive = QuickContinuation.fromSelection(conversation, "main", selected)
        val result = QuickContinuation.create(archive, listOf(conversation))
        assertTrue(result.text.contains("未进行新的智能整理"))
        assertTrue(result.text.contains(messages.last().content))
        assertTrue(result.text.contains("最后一段停在我的发言"))
        assertFalse(result.text.contains("不在所选范围的原文"))
        assertFalse(result.text.contains(messages.first().content))
        assertTrue(archive.entries.isEmpty())
        assertEquals(selected, archive.sourceScopes.single().selectedMessageIds.toSet())
    }

    @Test fun neverExpandsPartialSelectionToTheActualConversationEndpoint() {
        val archive = QuickContinuation.fromSelection(conversation, "main", setOf("u2", "a2"))
        val text = QuickContinuation.create(archive, listOf(conversation)).text
        assertTrue(text.contains("问题2"))
        assertFalse(text.contains(messages.last().content))
        assertFalse(text.contains("最后一段停在我的发言"))
    }

    @Test fun originalArchiveRetainsEditsAndTombstonesWithoutReintroducingBlockedOriginals() {
        val raw = Archive("raw", "故事", listOf("c"), listOf(
            ArchiveEntry("first", EntryCategory.QUOTES, "完整原文不应被再次复制", listOf("u1")),
            ArchiveEntry("edit", EntryCategory.RECENT, "我手工修订的结论", listOf("u8"), edited = true),
            ArchiveEntry("deleted", EntryCategory.RECENT, "已删除摘要", listOf("end"), edited = true, deleted = true),
        ), generationMode = "original", continuity = ContinuitySettings(enabled = true),
            sourceScopes = listOf(ArchiveSourceScope("c", "main", messages.map { it.id })))
        val text = QuickContinuation.create(raw, listOf(conversation)).text
        assertTrue(text.contains("我手工修订的结论"))
        assertFalse(text.contains("完整原文不应被再次复制"))
        assertFalse(text.contains("已删除摘要"))
        assertFalse(text.contains(messages.last().content))
        assertFalse(text.contains("问题8："))
        assertEquals(3, raw.entries.size)
        assertEquals("original", raw.generationMode)
    }

    @Test fun explicitCurrentRequirementsRemainIntact() {
        val archive = QuickContinuation.fromSelection(conversation, "main", setOf("u8", "a8", "end"))
            .copy(background = "我当前提供的背景", continuity = ContinuitySettings(enabled = true,
                nextMessage = "请直接讨论新的问题", interactionNotes = "先说判断再说明依据"))
        val text = QuickContinuation.create(archive, listOf(conversation)).text
        assertTrue(text.contains("我当前提供的背景"))
        assertTrue(text.contains("先说判断再说明依据"))
        assertTrue(text.contains("请直接讨论新的问题"))
        assertTrue(text.endsWith(archive.continuity.nextMessage + "\n"))
    }

    @Test fun emptyAndInvalidSelectionsAreRejectedLocally() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickContinuation.fromSelection(conversation, "main", emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickContinuation.fromSelection(conversation, "main", setOf("secret"))
        }
    }
}
