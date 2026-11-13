package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ChatMessage
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SourceFaithfulProtocolTest {
    @Test fun releasedV15RequestBytesAndBudgetRemainFrozen() {
        val version = SummaryProtocol.DISCUSSION_PROGRESS_PROMPT_VERSION
        val sources = listOf(ChatMessage("m1", "user", "Keep this unchanged."))
        val settings = ServiceSettings(model = "legacy-test", maxOutputTokens = 1024)
        assertEquals("cedaf528c4887bcd8945cc57ec73d520273349880d90bc51ce1d5a870f0dfeb7",
            hash(SummaryProtocol.requestBody(settings, sources, version)))
        assertEquals(9, SummaryProtocol.requiredCompanionProtocol(version))
        assertTrue(SummaryProtocol.supportsContext(version))
    }

    @Test fun latestRequestKeepsOriginalWordsQuotedAndFitsExistingSmallBudget() {
        val settings = ServiceSettings(contextTokens = 4096, maxOutputTokens = 1024)
        val sources = listOf(ChatMessage("now", "user", "也许我还想再想想，暂时没有决定。"))
        val references = listOf(ChatMessage("earlier", "assistant", "This is a suggestion, not an agreed decision."))
        val body = SummaryProtocol.json.parseToJsonElement(
            SummaryProtocol.requestBody(settings, sources, contextMessages = references)).jsonObject
        val messages = body.getValue("messages").jsonArray
        assertEquals(2, messages.size)
        val instruction = messages[0].jsonObject.getValue("content").jsonPrimitive.content
        val quoted = SummaryProtocol.json.parseToJsonElement(
            messages[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
        assertEquals(SummaryProtocol.messagesJson(sources), quoted["source_messages"])
        assertEquals(SummaryProtocol.messagesJson(references), quoted["context_messages"])
        assertFalse(instruction.contains(sources.single().content))
        assertTrue(instruction.contains("important original wording"))
        assertTrue(instruction.contains("我/你/我们"))
        assertTrue(SummaryProtocol.chunkCharacters(settings) >= 64)
        assertTrue(SummaryProtocol.estimatedInputTokens(sources, contextMessages = references) + settings.maxOutputTokens <= settings.contextTokens)
        assertEquals("continuation-v1.6", SummaryProtocol.PROMPT_VERSION)
        assertEquals(10, SummaryProtocol.requiredCompanionProtocol(SummaryProtocol.PROMPT_VERSION))
    }

    @Test fun newTasksNegotiateLatestWithoutUpgradingOldServicesOrPersistedTasks() {
        val settings = ServiceSettings(mode = "companion")
        fun quota(version: Int?) = CompanionQuota(true, null, null, "synthetic", "",
            organizationProtocolVersion = version)
        assertEquals(SummaryProtocol.PROMPT_VERSION, organizationPromptVersion(settings.copy(mode = "byok")))
        assertEquals(SummaryProtocol.PROMPT_VERSION, organizationPromptVersion(settings, quota(10)))
        assertEquals(SummaryProtocol.DISCUSSION_PROGRESS_PROMPT_VERSION, organizationPromptVersion(settings, quota(9)))
        assertEquals(SummaryProtocol.LONG_TEXT_PROMPT_VERSION, organizationPromptVersion(settings, quota(8)))
        assertEquals(SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION, organizationPromptVersion(settings, quota(7)))
        assertEquals(SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION, organizationPromptVersion(settings, quota(null)))
        val task = TaskRecord("saved", "archive", "synthetic", settings, "", emptyList(),
            promptVersion = SummaryProtocol.DISCUSSION_PROGRESS_PROMPT_VERSION)
        val restored = TaskRow(task.id, task.archiveId, 1, appJson.encodeToString(TaskRecord.serializer(), task)).decodeTask()
        assertEquals(task.promptVersion, restored.promptVersion)
    }
}
