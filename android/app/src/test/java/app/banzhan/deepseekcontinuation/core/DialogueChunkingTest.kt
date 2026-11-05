package app.banzhan.deepseekcontinuation.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DialogueChunkingTest {
    @Test fun movesAWholeExchangeInsteadOfFillingThePreviousSegment() {
        val messages = listOf(message("u1", "user", 35), message("a1", "assistant", 30),
            message("u2", "user", 20), message("a2", "assistant", 30))
        val chunks = ArchiveLogic.dialogueChunks(messages, 80)
        assertEquals(listOf(listOf("u1", "a1"), listOf("u2", "a2")), chunks.map { it.messages.map { m -> m.id } })
        assertEquals(messages, chunks.flatMap { it.messages })
        assertTrue(chunks.first().contextMessages.isEmpty())
    }

    @Test fun oversizedExchangeStillKeepsEveryIndividuallyFittingMessageWhole() {
        val messages = listOf(message("u", "user", 20), message("a", "assistant", 70),
            message("tool", "tool", 20))
        val chunks = ArchiveLogic.dialogueChunks(messages, 80)
        assertEquals(messages, chunks.flatMap { it.messages })
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.messages.sumOf { m -> m.content.length } <= 80 })
    }

    @Test fun longAnswerKeepsShortQuestionAndSplitsAtNearbyParagraphs() {
        val question = ChatMessage("u", "user", "问")
        val answer = ChatMessage("a", "assistant", "甲".repeat(60) + "\n" + "乙".repeat(70) + "\n" + "丙".repeat(70))
        val chunks = ArchiveLogic.dialogueChunks(listOf(question, answer), 90)
        assertEquals(listOf("u", "a"), chunks.first().messages.map { it.id })
        assertEquals("甲".repeat(60) + "\n", chunks.first().messages.last().content)
        assertEquals("乙".repeat(70) + "\n", chunks[1].messages.single().content)
        assertEquals(answer.content, chunks.flatMap { it.messages }.filter { it.id == "a" }.joinToString("") { it.content })
        assertTrue(chunks.drop(1).all { it.contextMessages.any { context -> context == question } })
    }

    @Test fun longMessageUsesSentenceBoundaryWhenNoNearbyParagraphExists() {
        val source = ChatMessage("long", "user", "甲".repeat(60) + "。" + "乙".repeat(60) + "。" + "丙".repeat(40))
        val chunks = ArchiveLogic.dialogueChunks(listOf(source), 90)
        assertEquals("甲".repeat(60) + "。", chunks.first().messages.single().content)
        assertEquals(source.content, chunks.flatMap { it.messages }.joinToString("") { it.content })
    }

    @Test fun everyPrimaryCharacterAndMetadataSurvivesUnicodeFallback() {
        val source = ChatMessage("long", "assistant", "甲😀乙😎".repeat(40), "2026-11-05", listOf("document.pdf"))
        for (limit in listOf(2, 7, 15)) {
            val chunks = ArchiveLogic.dialogueChunks(listOf(source), limit)
            val parts = chunks.flatMap { it.messages }
            assertEquals(source.content, parts.joinToString("") { it.content })
            assertTrue(chunks.all { it.messages.sumOf { m -> m.content.length } <= limit })
            assertTrue(parts.all { it.copy(content = source.content) == source })
            assertTrue((parts + chunks.flatMap { it.contextMessages }).all {
                it.content.isEmpty() || (!it.content.first().isLowSurrogate() && !it.content.last().isHighSurrogate())
            })
        }
    }

    @Test fun recentCorrectionAndQuestionAnchorTravelOnlyToFollowingChunks() {
        val messages = listOf(
            ChatMessage("early", "user", "先建议"), message("a1", "assistant", 110),
            ChatMessage("correction", "user", "改为先听完"), message("a2", "assistant", 110),
            ChatMessage("unfinished", "user", "那就继续"), message("a3", "assistant", 110),
        )
        val chunks = ArchiveLogic.dialogueChunks(messages, 120)
        assertEquals(3, chunks.size)
        assertTrue(chunks[1].contextMessages.contains(messages[0]))
        assertTrue(chunks[2].contextMessages.contains(messages[2]))
        assertEquals(messages, chunks.flatMap { it.messages })
        val processed = mutableMapOf<String, String>()
        chunks.forEach { chunk ->
            assertTrue(chunk.contextMessages.sumOf { it.content.length } <= 20)
            assertTrue(chunk.contextMessages.size <= 4)
            chunk.contextMessages.forEach { context ->
                assertTrue("Context must have already appeared in the selected primary input", processed[context.id]?.contains(context.content) == true)
            }
            chunk.messages.forEach { processed[it.id] = processed[it.id].orEmpty() + it.content }
        }
    }

    @Test fun noContextCanEscapeTheSuppliedSelection() {
        val selected = listOf(ChatMessage("selected-question", "user", "这里的角色是小说人物"),
            ChatMessage("selected-answer", "assistant", "小说讨论".repeat(180)))
        val chunks = ArchiveLogic.dialogueChunks(selected, 120)
        assertTrue(chunks.flatMap { it.messages + it.contextMessages }.all { fragment ->
            selected.any { source -> source.id == fragment.id && source.content.contains(fragment.content) }
        })
        assertEquals(selected.last().content,
            chunks.flatMap { it.messages }.filter { it.id == selected.last().id }.joinToString("") { it.content })
    }

    @Test fun blankTailOfUsefulEarlierTextIsNotSentAsAReferenceMessage() {
        val earlierQuestion = ChatMessage("u1", "user", "先听完")
        val earlierAnswer = ChatMessage("a1", "assistant", "有正文的回复" + " \n".repeat(50))
        val next = listOf(ChatMessage("u2", "user", "继续说"), message("a2", "assistant", 110))
        val messages = listOf(earlierQuestion, earlierAnswer) + next
        val chunks = ArchiveLogic.dialogueChunks(messages, 120)
        assertEquals(2, chunks.size)
        assertEquals(messages, chunks.flatMap { it.messages })
        assertEquals(listOf(earlierQuestion), chunks[1].contextMessages)
        assertTrue(chunks.flatMap { it.contextMessages }.all { it.content.isNotBlank() })
    }

    @Test fun idsAreDeterministicDistinguishRepeatedPositionsAndIncludeContext() {
        val repeated = listOf(ChatMessage("same-id", "user", "x".repeat(16)), ChatMessage("same-id", "user", "xxxx"))
        val parts = ArchiveLogic.dialogueChunks(repeated, 4)
        assertEquals(parts, ArchiveLogic.dialogueChunks(repeated, 4))
        assertEquals(parts.size, parts.map { it.id }.toSet().size)

        val messages = listOf(ChatMessage("u1", "user", "旧约定"), message("a1", "assistant", 110),
            ChatMessage("u2", "user", "继续"), message("a2", "assistant", 110))
        val before = ArchiveLogic.dialogueChunks(messages, 120)
        val after = ArchiveLogic.dialogueChunks(messages.map { if (it.id == "u1") it.copy(content = "新约定") else it }, 120)
        assertEquals(before[1].messages, after[1].messages)
        assertNotEquals(before[1].contextMessages, after[1].contextMessages)
        assertNotEquals(before[1].id, after[1].id)
    }

    @Test fun tinyBudgetsEmptyInputsAndAttachmentOnlyMessagesStayUsable() {
        assertTrue(ArchiveLogic.dialogueChunks(emptyList(), 2).isEmpty())
        val empty = ChatMessage("attachment", "user", "", attachments = listOf("image.png"))
        val emoji = ChatMessage("emoji", "assistant", "😀😎")
        val chunks = ArchiveLogic.dialogueChunks(listOf(empty, emoji), 2)
        assertEquals(empty, chunks.first().messages.first())
        assertEquals(emoji.content, chunks.flatMap { it.messages }.filter { it.id == emoji.id }.joinToString("") { it.content })
        assertTrue(chunks.all { it.contextMessages.isEmpty() })
    }

    @Test fun historicalChunkSerializationDefaultsToNoContext() {
        val old = Json.decodeFromString<SummaryChunk>("""{"id":"old","messages":[{"id":"m","role":"user","content":"原文"}]}""")
        assertTrue(old.contextMessages.isEmpty())
        assertTrue(ArchiveLogic.chunks(old.messages, 10).all { it.contextMessages.isEmpty() })
    }

    private fun message(id: String, role: String, length: Int) = ChatMessage(id, role, "文".repeat(length))
}
