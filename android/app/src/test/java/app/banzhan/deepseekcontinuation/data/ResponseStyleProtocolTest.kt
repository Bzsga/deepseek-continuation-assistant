package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityCue
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.EntryKind
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ResponseStyleProtocolTest {
    private val messages = listOf(ChatMessage("u1", "user", "先确认你理解的问题。"),
        ChatMessage("u2", "user", "这里还有一点分歧。"),
        ChatMessage("a1", "assistant", "我先复述我的理解，再谈建议。"),
        ChatMessage("a2", "assistant", "这一点我有不同判断，我会解释理由。"),
        ChatMessage("a3", "assistant", "我们先把前提核对清楚。"),
        ChatMessage("tool", "tool", "工具返回的文字。"))

    private fun entry(sources: List<String> = listOf("a1", "a2"), kind: EntryKind = EntryKind.UNCONFIRMED,
        text: String = "旧助手先复述理解，再说明建议与分歧理由。") = ArchiveEntry("model-result",
        EntryCategory.RECENT, text, sources, kind, continuityCue = ContinuityCue.RESPONSE_STYLE)

    @Test fun v12BytesBudgetAndPersistedTaskResumeKeepTheirOriginalIdentity() {
        val source = listOf(ChatMessage("m1", "user", "Keep this unchanged."))
        val settings = ServiceSettings(model = "legacy-test", maxOutputTokens = 1024)
        val version = SummaryProtocol.CONTINUITY_PROMPT_VERSION
        assertEquals("continuation-v1.2", version)
        assertEquals("7d0b1096d1eb6d13dc6a922a14e97ed3bf5b6260a3a0c2530f2d2c8bd3cf6f12",
            hash(SummaryProtocol.requestBody(settings, source, version)))
        assertEquals(2004, SummaryProtocol.estimatedInputTokens(source, version))
        assertEquals(1210, SummaryProtocol.chunkCharacters(settings, version))
        assertFalse(SummaryProtocol.requestBody(settings, source, version).contains("RESPONSE_STYLE"))

        val small = settings.copy(contextTokens = 4096)
        val oldSource = ChatMessage("m1", "user", "x".repeat(SummaryProtocol.chunkCharacters(small, version)))
        val chunk = TaskChunk("old-v12-chunk", listOf(oldSource),
            organizationCacheKey(small, version, "old-v12-chunk", listOf(oldSource)))
        val task = TaskRecord("old-v12-task", "archive", "原任务", small, "", listOf(chunk),
            status = "paused", promptVersion = version)
        val restored = appJson.decodeFromString<TaskRecord>(appJson.encodeToString(task))
        assertEquals(version, restored.promptVersion)
        assertEquals(task.chunks, restored.chunksForExplicitResume())
        val remote = restored.copy(chunks = listOf(chunk.copy(remoteJobId = "original-remote", requestStarted = true)))
        assertEquals(remote.chunks, remote.afterProcessDeath().chunksForExplicitResume())
        assertTrue(restored.copy(promptVersion = SummaryProtocol.PROMPT_VERSION).chunksForExplicitResume().size > 1)
    }

    @Test fun releasedV13KeepsObservedStyleWithinTheSameQuotedTranscriptCall() {
        assertEquals("continuation-v1.3", SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION)
        for (version in listOf(SummaryProtocol.LEGACY_PROMPT_VERSION,
            SummaryProtocol.CONTINUITY_PROMPT_VERSION, SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION, SummaryProtocol.PROMPT_VERSION)) {
            assertTrue(SummaryProtocol.supportsVersion(version))
        }
        assertFalse(SummaryProtocol.supportsVersion("continuation-v9"))
        val request = SummaryProtocol.json.parseToJsonElement(
            SummaryProtocol.requestBody(ServiceSettings(), messages, SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION)).jsonObject
        val prompts = request.getValue("messages").jsonArray
        assertEquals(2, prompts.size)
        val instruction = prompts[0].jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(instruction.contains("RESPONSE_STYLE"))
        assertTrue(instruction.contains("Cite only assistant sources, at least two distinct messages"))
        assertTrue(instruction.contains("at most 2"))
        assertTrue(instruction.contains("preserving key memories"))
        assertTrue(instruction.contains("Silence is not approval"))
        assertTrue(instruction.contains("identity or exclusive intimacy promises"))
        assertTrue(instruction.contains("all distinct explicit communication requirements, corrections and boundaries"))
        assertTrue(instruction.contains("not just the last one"))
        assertTrue(instruction.contains("all meanings and necessary user sources remain"))
        assertTrue(instruction.contains("Prefer later corrections on the same topic"))
        assertTrue(instruction.contains("compress ordinary assistant content suggestions and repetition first"))
        assertTrue(instruction.contains("style must not displace explicit requirements"))
        assertEquals("user", prompts[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(SummaryProtocol.messagesJson(messages).toString(), prompts[1].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun styleRequiresTwoDistinctAssistantSourcesButInvalidOptionalEvidenceKeepsText() {
        for (kind in listOf(EntryKind.UNCONFIRMED, EntryKind.ASSISTANT_SUGGESTION)) {
            val valid = SummaryProtocol.validateEntries(listOf(entry(kind = kind)), messages).single()
            assertEquals(ContinuityCue.RESPONSE_STYLE, valid.continuityCue)
            assertEquals(kind, valid.kind)
        }
        for (unsupported in listOf(entry(listOf("a1")), entry(listOf("a1", "a1")),
            entry(listOf("a1", "u1")), entry(listOf("a1", "a2", "u1")), entry(listOf("a1", "tool")),
            entry(listOf("u1", "u2"), EntryKind.USER_FACT))) {
            val result = SummaryProtocol.validateEntries(listOf(unsupported), messages).single()
            val ordinary = SummaryProtocol.validateEntries(listOf(unsupported.copy(continuityCue = null)), messages).single()
            assertNull(result.continuityCue)
            assertEquals(unsupported.text, result.text)
            assertEquals(unsupported.kind, result.kind)
            assertEquals(ordinary, result)
        }
        val parsed = SummaryProtocol.validateEntries("""{"entries":[{"id":"model","category":"RECENT","kind":"UNCONFIRMED","text":"具体的回应方式","sourceIds":["a1","a2"],"continuityCue":"RESPONSE_STYLE"}]}""", messages).single()
        assertEquals(ContinuityCue.RESPONSE_STYLE, parsed.continuityCue)
    }

    @Test fun optionalStyleCannotHideUnknownSourcesOrInvalidFactAndSuggestionKinds() {
        for (invalid in listOf(entry(listOf("a1", "missing")), entry(kind = EntryKind.USER_FACT),
            entry(listOf("u1", "u2"), EntryKind.ASSISTANT_SUGGESTION))) {
            assertThrows(IllegalArgumentException::class.java) {
                SummaryProtocol.validateEntries(listOf(invalid), messages)
            }
        }
    }

    @Test fun extraValidStyleCluesKeepTheirTypeAndCannotLeakIntoOrdinaryFacts() {
        val fact = entry(listOf("u1"), EntryKind.USER_FACT, "必须保留的用户事实").copy(continuityCue = null)
        val first = entry(listOf("a1", "a2"), text = "先理解，再解释。")
        val sameLineage = first.copy(id = "another-id", text = "另一段具体回应。")
        val second = entry(listOf("a2", "a3"), text = "分歧时说明理由。")
        val third = entry(listOf("a1", "a3"), text = "核对具体前提。")
        val result = SummaryProtocol.validateEntries(listOf(fact, first, sameLineage, second, third), messages)
        assertEquals(4, result.size)
        assertEquals(3, result.count { it.continuityCue == ContinuityCue.RESPONSE_STYLE })
        assertTrue(result.any { it.text == fact.text && it.kind == EntryKind.USER_FACT })
        assertEquals(first.text + "\n" + sameLineage.text, result.single { it.sourceIds == first.sourceIds }.text)
        assertEquals(SummaryProtocol.validateEntries(listOf(third), messages).single(),
            result.single { it.sourceIds == third.sourceIds })
        assertEquals(result, SummaryProtocol.validateEntries(result, messages))
    }
}
