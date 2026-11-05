package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ChatMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PoliticalExportTaskTest {
    private val text = "请保持自然的语气。\n讨论一段政治观点。\n最近在学习素描。"
    private val settings = ServiceSettings(mode = "companion", contextTokens = 64000, maxOutputTokens = 4096)

    @Test fun companionUnknownPostRetainsItsIdentityAfterProcessDeathAndExplicitResume() {
        val batch = batch().copy(started = true)
        val task = task(listOf(batch)).copy(status = "running")
        val restored = appJson.decodeFromString<PoliticalExportTask>(appJson.encodeToString(task)).afterProcessDeath()
        assertEquals("paused", restored.status)
        assertTrue(restored.hasUnknownRequest)
        assertFalse(restored.requiresUncertainRetry)
        val resumed = restored.forExplicitResume(false)
        assertEquals(batch, resumed.batches.single())
        assertEquals(task.id, resumed.id)
        assertEquals(task.settings, resumed.settings)
        assertEquals(task.originalText, resumed.originalText)
    }

    @Test fun byokUnknownRequestCannotBeAutomaticallyResent() {
        val initial = task(listOf(batch().copy(started = true))).copy(settings = settings.copy(mode = "byok"))
        assertTrue(initial.requiresUncertainRetry)
        assertThrows(IllegalArgumentException::class.java) { initial.forExplicitResume(false) }
        val explicit = initial.forExplicitResume(true)
        assertFalse(explicit.batches.single().started)
        assertEquals(1, explicit.batches.single().attempt)
        assertEquals(initial.originalText, explicit.originalText)
    }

    @Test fun resumePollsLiveJobsAndReusesCompletedResultsBeforeFreshTerminalAttempts() {
        val parts = PoliticalFilterProtocol.parts(text)
        val completed = PoliticalExportBatch("done", listOf(parts.first()), started = true, completed = true,
            remoteJobId = "done-job", remoteStatus = "completed")
        val live = PoliticalExportBatch("live", listOf(parts[1]), started = true, remoteJobId = "live-job", remoteStatus = "running")
        val failed = PoliticalExportBatch("failed", listOf(parts.last()), started = true, remoteJobId = "failed-job", remoteStatus = "failed", attempt = 2)
        val resumed = task(listOf(completed, live, failed)).forExplicitResume(false)
        assertEquals(completed, resumed.batches[0])
        assertEquals(live, resumed.batches[1])
        assertEquals(3, resumed.batches[2].attempt)
        assertEquals("", resumed.batches[2].remoteJobId)
        assertFalse(resumed.batches[2].started)
        assertTrue(resumed.hasUnresolvedRemote)
    }

    @Test fun terminalUncertainResultRequiresExplicitRetryAcknowledgment() {
        for (status in listOf("uncertain", "expired")) {
            val value = task(listOf(batch().copy(started = true, remoteJobId = "original", remoteStatus = status)))
            assertTrue(value.requiresUncertainRetry)
            assertThrows(IllegalArgumentException::class.java) { value.forExplicitResume(false) }
            assertEquals("", value.forExplicitResume(true).batches.single().remoteJobId)
        }
    }

    @Test fun onlyCompletedVariantsOmitExactChosenSpansAndKeepSelectionsSurviveSerialization() {
        val parts = PoliticalFilterProtocol.parts(text)
        val political = parts.single { it.text.contains("政治") }
        val hit = PoliticalExportHit(political.id, political.start, political.end, political.text, "可能触发目标平台的政治内容限制")
        val value = task(listOf(PoliticalExportBatch("all", parts, completed = true, hits = listOf(hit))))
        assertThrows(IllegalArgumentException::class.java) { value.variantText() }
        val completed = value.copy(status = "completed")
        validatePoliticalTask(completed)
        assertEquals(text.removeRange(political.start, political.end), completed.variantText())
        val retained = completed.copy(keptHitIds = setOf(hit.id))
        val restored = appJson.decodeFromString<PoliticalExportTask>(appJson.encodeToString(retained))
        validatePoliticalTask(restored)
        assertEquals(text, restored.variantText())
        assertEquals(text, value.originalText)
        assertFalse(restored.canResume)
    }

    @Test fun snapshotsRejectCredentialLeaksChangedOriginalAndInvalidHitRanges() {
        val original = task(listOf(batch()))
        validatePoliticalTask(original)
        assertThrows(IllegalArgumentException::class.java) { validatePoliticalTask(original.copy(originalText = "被改写的文字")) }
        assertThrows(IllegalArgumentException::class.java) { validatePoliticalTask(original.copy(settings = settings.copy(apiKey = "synthetic-secret"))) }
        val part = original.batches.single().parts.first()
        val invalid = PoliticalExportHit(part.id, part.start + 1, part.end, part.text, "原因")
        assertThrows(IllegalArgumentException::class.java) {
            validatePoliticalTask(original.copy(batches = listOf(original.batches.single().copy(completed = true, hits = listOf(invalid)))))
        }
        assertThrows(IllegalArgumentException::class.java) { validatePoliticalTask(original.copy(status = "completed")) }
    }

    @Test fun whitespaceGapsBeforeBetweenAndAfterCheckedPartsSurviveRecoveryAndExport() {
        val source = " \n\n保持原来的语气。\n \n讨论政治观点。\n\n "
        val parts = PoliticalFilterProtocol.parts(source)
        assertTrue(parts.all { it.text.isNotBlank() })
        val value = task(listOf(PoliticalExportBatch("gaps", parts, completed = true))).copy(
            originalText = source, sourceFingerprint = hash(source), status = "completed")
        val restored = appJson.decodeFromString<PoliticalExportTask>(appJson.encodeToString(value))
        validatePoliticalTask(restored)
        assertEquals(source, restored.variantText())
        val incomplete = value.copy(batches = listOf(PoliticalExportBatch("missing", parts.dropLast(1), completed = true)))
        assertThrows(IllegalArgumentException::class.java) { validatePoliticalTask(incomplete) }
    }

    @Test fun unavailableCompanionCapabilityCannotPretendToProduceAFilteredCopy() {
        val configured = settings.copy(trialToken = "synthetic-token")
        val quota = CompanionQuota(true, 10.0, 1.0, "same-model", "", contextTokens = 64000, maxOutputTokens = 4096)
        assertThrows(IllegalArgumentException::class.java) { politicalSettings(configured, quota) }
        val captured = politicalSettings(configured, quota.copy(politicalFilterSupported = true))
        assertEquals("same-model", captured.model)
        assertEquals("synthetic-token", captured.trialToken)
        assertEquals(settings.model, configured.model)
        assertEquals("byok", politicalSettings(settings.copy(mode = "byok", apiKey = "synthetic-key"), null).mode)
    }

    @Test fun missingPrimaryNoticeSurvivesTaskSerializationAndIsNotHiddenByOtherSuccesses() {
        val successful = TaskChunk("success", listOf(ChatMessage("one", "user", "正文")), "success-key", completed = true)
        val absent = TaskChunk("empty", listOf(ChatMessage("two", "user", "新正文")), "empty-key", completed = true,
            contextMessages = listOf(ChatMessage("one", "user", "正文")), resultNotice = "本段未生成正文条目，原文仍保留")
        val restored = appJson.decodeFromString<TaskChunk>(appJson.encodeToString(absent))
        val message = organizationCompletionMessage("整理完成", listOf(successful, restored))
        assertTrue(message.contains("1 段未生成正文条目"))
        assertEquals("整理完成", organizationCompletionMessage("整理完成", listOf(successful, absent.copy(completed = false))))
    }

    private fun batch() = PoliticalExportBatch("batch", PoliticalFilterProtocol.parts(text))
    private fun task(batches: List<PoliticalExportBatch>) = PoliticalExportTask("task", "archive", text, hash(text),
        settings, batches, status = "paused")
}
