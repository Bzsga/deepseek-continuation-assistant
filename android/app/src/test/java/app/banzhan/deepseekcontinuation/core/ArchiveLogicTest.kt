package app.banzhan.deepseekcontinuation.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArchiveLogicTest {
    @Test fun chunksPreserveContentSourceAndUnicode() {
        val source = ChatMessage("conversation/message", "user", "ab😀cd😀ef".repeat(5), "2026-11-03", listOf("photo.png"))
        val chunks = ArchiveLogic.chunks(listOf(source), 7)
        val parts = chunks.flatMap { it.messages }
        assertEquals(source.content, parts.joinToString("") { it.content })
        assertTrue(chunks.all { it.messages.sumOf { message -> message.content.length } <= 7 })
        assertTrue(parts.all { it.id == source.id && it.role == source.role && it.attachments == source.attachments })
        assertTrue(parts.all { !it.content.first().isLowSurrogate() && !it.content.last().isHighSurrogate() })
    }

    @Test fun chunkIdsAreStableContentSensitiveAndUniqueForRepeatedContent() {
        val source = ChatMessage("c/m", "user", "xxxx".repeat(8))
        val first = ArchiveLogic.chunks(listOf(source), 4)
        assertEquals(first, ArchiveLogic.chunks(listOf(source), 4))
        assertEquals(first.size, first.map { it.id }.distinct().size)
        assertNotEquals(first.first().id, ArchiveLogic.chunks(listOf(source.copy(content = "y" + source.content.drop(1))), 4).first().id)
        assertNotEquals(first.first().id, ArchiveLogic.chunks(listOf(source.copy(role = "assistant")), 4).first().id)
    }

    @Test fun chunkBoundaryMovesWholeSurrogatePairToNextChunk() {
        val messages = listOf(ChatMessage("a", "user", "a"), ChatMessage("b", "assistant", "😀"))
        val chunks = ArchiveLogic.chunks(messages, 2)
        assertEquals(listOf("a", "😀"), chunks.flatMap { it.messages }.map { it.content })
        assertEquals(2, chunks.size)
    }

    @Test fun localEntriesAreFaithfulLabeledExcerpts() {
        val text = "第一行\n  保留空格😀".repeat(250)
        val message = ChatMessage("conversation/source", "user", text)
        val entries = ArchiveLogic.localEntries(listOf(message))
        assertTrue(entries.size > 1)
        assertEquals(text, entries.joinToString("") { it.text.substringAfter('\n') })
        assertTrue(entries.all { it.kind == EntryKind.USER_FACT && it.category == EntryCategory.QUOTES && it.sourceIds == listOf(message.id) })
        assertEquals(entries, ArchiveLogic.localEntries(listOf(message)))
        val assistant = ArchiveLogic.localEntries(listOf(message.copy(id = "assistant", role = "assistant"))).first()
        assertEquals(EntryKind.ASSISTANT_SUGGESTION, assistant.kind)
        assertTrue(assistant.text.startsWith("【助手原文"))
        assertFalse(assistant.text.contains("待用户确认"))
        assertEquals(listOf("assistant"), assistant.sourceIds)
    }

    @Test fun attachmentOnlySourceIsRepresentedAsMetadata() {
        val entries = ArchiveLogic.localEntries(listOf(ChatMessage("attachment", "user", "", attachments = listOf("image.png"))))
        assertEquals(1, entries.size)
        assertTrue(entries.single().text.contains("未读取附件正文"))
        assertTrue(entries.single().text.contains("image.png"))
    }

    @Test fun unknownRolesRemainUnconfirmed() {
        val entry = ArchiveLogic.localEntries(listOf(ChatMessage("unknown", "unknown", "原文"))).single()
        assertEquals(EntryKind.UNCONFIRMED, entry.kind)
        assertTrue(entry.text.contains("角色待核验"))
        assertTrue(entry.text.endsWith("原文"))
    }

    @Test fun tombstoneSurvivesIdenticalRegenerationAndChangedTextConflicts() {
        val tombstone = entry("same", "旧内容").copy(deleted = true, edited = true, pinned = true)
        val same = ArchiveLogic.merge(listOf(tombstone), listOf(tombstone.copy(deleted = false, edited = false, pinned = false)))
        assertEquals(listOf(tombstone), same.entries)
        assertTrue(same.conflicts.isEmpty())
        val proposed = tombstone.copy(text = "新内容", deleted = false, edited = false)
        val changed = ArchiveLogic.merge(listOf(tombstone), listOf(proposed))
        assertEquals(listOf(tombstone), changed.entries)
        assertEquals(listOf(EntryConflict(tombstone, proposed)), changed.conflicts)
    }

    @Test fun mergePreservesEditedPinnedAndPlainEntriesAndRecordsConflicts() {
        val existing = listOf(entry("edited", "手工内容").copy(edited = true), entry("pinned", "置顶").copy(pinned = true), entry("plain", "原内容"))
        val proposed = existing.map { it.copy(text = "更新", pinned = false, edited = false) }
        val result = ArchiveLogic.merge(existing, proposed + entry("new", "新增"))
        assertEquals(existing + entry("new", "新增"), result.entries)
        assertEquals(3, result.conflicts.size)
    }

    @Test fun conflictingDuplicateIncomingIdsCannotSilentlyOverwrite() {
        val first = entry("id", "甲")
        val second = entry("id", "乙")
        val result = ArchiveLogic.merge(emptyList(), listOf(first, first, second, second))
        assertEquals(listOf(first), result.entries)
        assertEquals(listOf(EntryConflict(first, second)), result.conflicts)
    }

    @Test fun storedDuplicateTombstoneTakesPriority() {
        val live = entry("id", "旧")
        val deleted = live.copy(text = "已删除", deleted = true)
        val result = ArchiveLogic.merge(listOf(live, deleted), listOf(live))
        assertEquals(listOf(deleted), result.entries)
        assertEquals(1, result.conflicts.size)
    }

    @Test fun exportPrioritizesPinnedWithoutTruncatingIt() {
        val pinned = entry("p", "必须完整保留".repeat(10)).copy(pinned = true)
        val ordinary = entry("u", "普通".repeat(500))
        val result = ArchiveLogic.export(archive(listOf(ordinary, pinned)), 300)
        assertTrue(result.text.length <= 300)
        assertTrue(result.text.contains(pinned.text))
        assertFalse(result.text.contains(ordinary.text))
        assertEquals(1, result.omittedCount)
        assertFalse(result.pinnedOverflow)
        assertTrue(result.text.contains("省略 1 条"))
    }

    @Test fun oversizedPinnedEntryHasExplicitBoundedOverflow() {
        val pinned = entry("p", "超长置顶".repeat(2000)).copy(pinned = true)
        val result = ArchiveLogic.export(archive(listOf(pinned)))
        assertTrue(result.text.length <= 6000)
        assertEquals(1, result.omittedCount)
        assertTrue(result.pinnedOverflow)
        assertTrue(result.text.contains("置顶条目或背景超出上限"))
        assertFalse(result.text.contains("超长置顶"))
    }

    @Test fun oversizedBackgroundHasExplicitOverflowAndDeletedEntriesAreExcluded() {
        val archive = archive(listOf(entry("deleted", "秘密").copy(deleted = true), entry("a", "内容")))
            .copy(background = "背景".repeat(500))
        val result = ArchiveLogic.export(archive, 200)
        assertTrue(result.text.length <= 200)
        assertTrue(result.pinnedOverflow)
        assertFalse(result.text.contains("秘密"))
        assertTrue(result.text.contains("用户陈述"))
        assertTrue(result.text.contains("来源 source-a"))
    }

    @Test fun smallestSupportedExportIsBounded() {
        val result = ArchiveLogic.export(archive(listOf(entry("p", "x".repeat(500)).copy(pinned = true))).copy(title = "标题".repeat(200)), 100)
        assertTrue(result.text.length <= 100)
        assertTrue(result.pinnedOverflow)
    }

    @Test(expected = IllegalArgumentException::class) fun emptyArchiveCannotExport() {
        ArchiveLogic.export(archive(listOf(entry("d", "deleted").copy(deleted = true))))
    }

    @Test(expected = IllegalArgumentException::class) fun impossibleUnicodeChunkBudgetIsRejected() {
        ArchiveLogic.chunks(listOf(ChatMessage("id", "user", "😀")), 1)
    }

    @Test fun oldArchiveJsonDefaultsToHonestLegacyMode() {
        val saved = """{"id":"old","title":"旧档案","conversationIds":[],"entries":[],"updatedAt":0,"background":"合成测试背景"}"""
        val restored = Json.decodeFromString<Archive>(saved)
        assertEquals("legacy", restored.generationMode)
        val result = ArchiveLogic.continuationExport(restored)
        assertTrue(result.text.contains("【已保存的续聊内容】旧档案"))
        assertTrue(result.text.contains("合成测试背景"))
        assertFalse(result.text.contains("【续聊摘要】"))
    }

    @Test fun continuationExportRetainsCompleteLongProtectedContentWithoutTechnicalIds() {
        val pinned = entry("pinned", "完整保留的置顶原文😀\n".repeat(1600)).copy(pinned = true)
        val edited = entry("edited", "手工修改后的完整内容").copy(edited = true)
        val deleted = entry("deleted", "已删除的内容不应输出").copy(deleted = true, pinned = true)
        val background = "用户补充背景\n".repeat(1000)
        val result = ArchiveLogic.continuationExport(archive(listOf(pinned, edited, deleted)).copy(background = background))
        assertTrue(result.text.length > 6000)
        assertTrue(result.text.toByteArray(Charsets.UTF_8).size > 60 * 1024)
        assertTrue(result.text.contains(pinned.text))
        assertTrue(result.text.contains(edited.text))
        assertTrue(result.text.contains(background))
        assertFalse(result.text.contains(deleted.text))
        assertFalse(result.text.contains("source-pinned"))
        assertFalse(result.text.contains("source-edited"))
        assertEquals(0, result.omittedCount)
        assertFalse(result.pinnedOverflow)
    }

    @Test fun continuationModesSectionsAndSourceKindsRemainDistinct() {
        val entries = EntryCategory.entries.mapIndexed { index, category ->
            entry("test-$index", "合成内容$index").copy(category = category, kind = EntryKind.entries[index % EntryKind.entries.size])
        } + listOf(
            entry("suggestion-one", "也许可以先写一个短篇。").copy(kind = EntryKind.ASSISTANT_SUGGESTION, pinned = true),
            entry("suggestion-two", "另一种可能是先整理素材。").copy(kind = EntryKind.ASSISTANT_SUGGESTION, edited = true),
            entry("interpretation", "一种解释是故事节奏需要调整。").copy(kind = EntryKind.UNCONFIRMED),
            entry("user-followup", "我已经写完开头。"),
        )
        val expectedLabels = mapOf("original" to "聊天原文", "summary" to "续聊摘要", "legacy" to "已保存的续聊内容", "future-mode" to "已保存的续聊内容")
        for ((mode, label) in expectedLabels) {
            val source = archive(entries).copy(generationMode = mode)
            val restored = Json.decodeFromString<Archive>(Json.encodeToString(source))
            assertEquals(mode, restored.generationMode)
            val text = ArchiveLogic.continuationExport(restored).text
            assertTrue(text.contains("【$label】"))
            assertEquals(mode == "original", text.contains("聊天原文仅包含我所选的内容"))
            assertTrue(text.contains("【用户陈述】"))
            assertTrue(text.contains("【讨论中的建议】"))
            assertTrue(text.contains("【讨论与理解】"))
            assertFalse(text.contains("尚未经用户确认"))
            assertFalse(text.contains("需要时先向我核实"))
            assertTrue(text.contains("以我后续的纠正为准"))
            assertTrue(text.contains("仅在出现真实矛盾、涉及重要决定或信息明确未定时向我核实"))
            assertTrue(text.contains("个人经历、病史、承诺和已采取的行动以我的明确陈述为依据"))
            val recent = text.substringAfter("## 接下来要聊的事\n").substringBefore("## 重要原话\n")
            assertEquals(1, Regex("【讨论中的建议】").findAll(recent).count())
            assertTrue(recent.indexOf("也许可以先写一个短篇。") < recent.indexOf("另一种可能是先整理素材。"))
            assertTrue(recent.indexOf("一种解释是故事节奏需要调整。") < recent.indexOf("我已经写完开头。"))
            entries.forEach { assertTrue(text.contains(it.text)) }
            assertEquals(entries, restored.entries)
            listOf("关于我", "重要的人和事", "已确定的事", "接下来要聊的事", "重要原话").forEach { section ->
                assertTrue(text.contains("## $section\n"))
            }
        }
    }

    @Test fun advancedExportKeepsSourceIdsAndModalTextWithNeutralKindLabels() {
        val suggestion = entry("suggestion", "可能可以先讨论安排。").copy(kind = EntryKind.ASSISTANT_SUGGESTION)
        val interpretation = entry("interpretation", "一种解释是时间安排有冲突。").copy(kind = EntryKind.UNCONFIRMED)
        val result = ArchiveLogic.export(archive(listOf(suggestion, interpretation)))
        assertTrue(result.text.contains("讨论中的建议"))
        assertTrue(result.text.contains("讨论与理解"))
        assertFalse(result.text.contains("待确认"))
        assertTrue(result.text.contains(suggestion.text))
        assertTrue(result.text.contains(interpretation.text))
        assertTrue(result.text.contains("来源 source-suggestion"))
        assertTrue(result.text.contains("来源 source-interpretation"))
    }

    @Test(expected = IllegalArgumentException::class) fun continuationExportRejectsEmptyOrDeletedContent() {
        ArchiveLogic.continuationExport(archive(listOf(entry("gone", "已删除").copy(deleted = true))))
    }

    private fun entry(id: String, text: String) = ArchiveEntry(id, EntryCategory.RECENT, text, listOf("source-$id"), EntryKind.USER_FACT)
    private fun archive(entries: List<ArchiveEntry>) = Archive("archive", "测试", listOf("conversation"), entries, updatedAt = 0)
}
