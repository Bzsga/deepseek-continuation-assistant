package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityCue
import app.banzhan.deepseekcontinuation.core.EntryCategory
import app.banzhan.deepseekcontinuation.core.EntryKind
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ContinuityCueProtocolTest {
    private val messages = listOf(ChatMessage("m1", "user", "请简短回答，继续讨论还没说完的问题。"),
        ChatMessage("m2", "assistant", "好的，我们接着讨论。"))
    private fun entry(cue: ContinuityCue? = null, kind: EntryKind = EntryKind.USER_FACT,
        sources: List<String> = listOf("m1")) = ArchiveEntry("model-id", EntryCategory.RECENT,
        "保持简短回答", sources, kind, continuityCue = cue)

    @Test fun optionalMalformedCueNeverDiscardsAnOtherwiseValidEntry() {
        val ordinary = SummaryProtocol.validateEntries(listOf(entry()), messages).single()
        for (value in listOf("null", "42", "true", "[]", "{}", "\"UNKNOWN_FUTURE_CUE\"")) {
            val payload = """{"entries":[{"id":"model","category":"RECENT","kind":"USER_FACT","text":"保持简短回答","sourceIds":["m1"],"continuityCue":$value}]}"""
            assertEquals(ordinary, SummaryProtocol.validateEntries(payload, messages).single())
        }
        for (cue in ContinuityCue.entries.filter { it != ContinuityCue.RESPONSE_STYLE }) {
            val payload = """{"entries":[{"id":"model","category":"RECENT","kind":"USER_FACT","text":"保持简短回答","sourceIds":["m1"],"continuityCue":"${cue.name}"}]}"""
            assertEquals(cue, SummaryProtocol.validateEntries(payload, messages).single().continuityCue)
        }
        val remote = SummaryNetwork.parseJob("""{"id":"remote","status":"completed","entries":[{"id":"model","category":"RECENT","kind":"USER_FACT","text":"保持简短回答","sourceIds":["m1"],"continuityCue":{"wrong":true}}]}""")
        assertNull(remote.entries.single().continuityCue)
    }

    @Test fun unsupportedCueEvidenceFallsBackToOrdinaryEntryAndKeepsKind() {
        for (cue in listOf(ContinuityCue.PREFERENCE, ContinuityCue.CORRECTION, ContinuityCue.SHARED_LANGUAGE)) {
            val result = SummaryProtocol.validateEntries(listOf(entry(cue, EntryKind.ASSISTANT_SUGGESTION, listOf("m2"))), messages).single()
            assertNull(result.continuityCue)
            assertEquals(EntryKind.ASSISTANT_SUGGESTION, result.kind)
            assertEquals("保持简短回答", result.text)
        }
        assertNull(SummaryProtocol.validateEntries(listOf(entry(ContinuityCue.PREFERENCE,
            EntryKind.UNCONFIRMED, listOf("m1", "m2"))), messages).single().continuityCue)
        for (cue in listOf(ContinuityCue.CORRECTION, ContinuityCue.SHARED_LANGUAGE)) {
            assertEquals(cue, SummaryProtocol.validateEntries(listOf(entry(cue,
                EntryKind.UNCONFIRMED, listOf("m1", "m2"))), messages).single().continuityCue)
        }
    }

    @Test fun cueIdentityIsSeparateWhileNullKeepsItsExactOldId() {
        val values = SummaryProtocol.validateEntries(listOf(entry(), entry(ContinuityCue.PREFERENCE),
            entry(ContinuityCue.OPEN_THREAD)), messages)
        assertEquals(3, values.size)
        assertEquals(3, values.map { it.id }.distinct().size)
        assertEquals("summary-334c9f40d603b2bcec55da56898d4fe3", values.single { it.continuityCue == null }.id)
        val second = values.first { it.continuityCue == ContinuityCue.PREFERENCE }.copy(text = "另一段明确偏好")
        val aggregated = aggregateChunkEntries(listOf(values, listOf(second)))
        assertEquals(3, aggregated.size)
        assertTrue(aggregated.single { it.continuityCue == ContinuityCue.PREFERENCE }.text.contains(second.text))
        assertThrows(IllegalArgumentException::class.java) {
            aggregateChunkEntries(listOf(listOf(values.first(), values.first().copy(continuityCue = ContinuityCue.OPEN_THREAD))))
        }
    }

    @Test fun legacyRequestBytesAndBudgetStayFrozenAndUnknownVersionFailsLocally() {
        val source = listOf(ChatMessage("m1", "user", "Keep this unchanged."))
        val settings = ServiceSettings(model = "legacy-test", maxOutputTokens = 1024)
        val old = SummaryProtocol.LEGACY_PROMPT_VERSION
        assertEquals("43c3b1520493bd4dc5937712991f3277b289249de8f385e8ccb1e79c8b013f26",
            hash(SummaryProtocol.requestBody(settings, source, old)))
        assertEquals(941, SummaryProtocol.estimatedInputTokens(source, old))
        assertFalse(SummaryProtocol.requestBody(settings, source, old).contains("continuityCue"))
        assertTrue(SummaryProtocol.requestBody(settings, source).contains("continuityCue"))
        assertTrue(SummaryProtocol.supportsVersion(old))
        assertTrue(SummaryProtocol.supportsVersion(SummaryProtocol.PROMPT_VERSION))
        assertThrows(IllegalArgumentException::class.java) { SummaryProtocol.requestBody(settings, source, "unknown") }

        val small = settings.copy(contextTokens = 2600)
        SummaryProtocol.validateForRequest(small, source, old)
        assertThrows(IllegalArgumentException::class.java) { SummaryProtocol.validateForRequest(small, source) }
        val quota = CompanionQuota(true, null, null, small.model, "", contextTokens = 2600, maxOutputTokens = 1024)
        assertEquals(small, settingsWithServerBudget(small, quota, old))
    }

    @Test fun missingVersionAndExplicitResumeKeepLegacyChunkAndRemoteIdentity() {
        val old = SummaryProtocol.LEGACY_PROMPT_VERSION
        val settings = ServiceSettings(mode = "byok", contextTokens = 4096, maxOutputTokens = 1024)
        val source = ChatMessage("m1", "user", "x".repeat(SummaryProtocol.chunkCharacters(settings, old)))
        val chunk = TaskChunk("original-chunk", listOf(source), "original-cache")
        val task = TaskRecord("original-task", "archive", "旧任务", settings, "", listOf(chunk), status = "paused")
        val missingVersion = JsonObject(appJson.parseToJsonElement(appJson.encodeToString(task)).jsonObject
            .filterKeys { it != "promptVersion" }).toString()
        val restored = TaskRow(task.id, task.archiveId, 1, missingVersion).decodeTask()
        assertEquals(old, restored.promptVersion)
        assertEquals(task.chunks, restored.chunksForExplicitResume())
        assertTrue(restored.copy(promptVersion = SummaryProtocol.PROMPT_VERSION).chunksForExplicitResume().size > 1)
        val unknown = chunk.copy(requestStarted = true)
        val remote = chunk.copy(id = "remote-chunk", remoteJobId = "old-remote", requestStarted = true)
        val inFlight = restored.copy(chunks = listOf(unknown, remote))
        assertEquals(inFlight.chunks, inFlight.afterProcessDeath().chunksForExplicitResume())
        assertEquals(old, appJson.decodeFromString<TaskRecord>(appJson.encodeToString(restored)).promptVersion)
    }
}
