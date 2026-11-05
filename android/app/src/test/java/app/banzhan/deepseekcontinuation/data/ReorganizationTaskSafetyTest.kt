package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ChatMessage
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReorganizationTaskSafetyTest {
    private val settings = ServiceSettings(mode = "byok", baseUrl = "https://synthetic.invalid/v1",
        model = "synthetic-model", apiKey = "synthetic-key", contextTokens = 8192, maxOutputTokens = 256)
    private val messages = listOf(ChatMessage("m1", "user", "Keep the source unchanged."))

    private fun task(chunks: List<TaskChunk>, status: String = "paused") = TaskRecord(
        "rebuild-task", "rebuilt-archive", "重整任务", settings, "synthetic-reference", chunks,
        status = status, promptVersion = SummaryProtocol.LEGACY_PROMPT_VERSION,
        reorganizationOfArchiveId = "original-archive")

    @Test fun ordinaryCacheKeyKeepsHistoricalFormulaWhileRebuildsHaveStableTaskIsolation() {
        val version = SummaryProtocol.LEGACY_PROMPT_VERSION
        val historical = hash(appJson.encodeToString(settings.withoutSecrets()) + "\n" + version +
            "\nchunk\n" + appJson.encodeToString(messages))
        val ordinary = organizationCacheKey(settings, version, "chunk", messages)
        assertEquals(historical, ordinary)
        val rebuilt = organizationCacheKey(settings, version, "chunk", messages, "rebuild-one")
        assertNotEquals(ordinary, rebuilt)
        assertEquals(rebuilt, organizationCacheKey(settings, version, "chunk", messages, "rebuild-one"))
        assertNotEquals(rebuilt, organizationCacheKey(settings, version, "chunk", messages, "rebuild-two"))
        assertEquals(rebuilt, organizationCacheKey(settings.copy(apiKey = "replacement-key"),
            version, "chunk", messages, "rebuild-one"))
    }

    @Test fun resumedRebuildSplitsOnlySafeWorkAndRetainsTaskIsolationForEveryNewPiece() {
        val longSource = ChatMessage("long-source", "user", "甲".repeat(4000))
        val completed = TaskChunk("done", listOf(longSource.copy(id = "done-source")), "done-cache",
            completed = true, remoteJobId = "done-remote", remoteStatus = "completed", attempt = 1,
            requestStarted = true)
        val unknown = TaskChunk("unknown", listOf(longSource.copy(id = "unknown-source")), "unknown-cache",
            attempt = 2, requestStarted = true)
        val retry = TaskChunk("retry", listOf(longSource), "old-retry-cache", remoteJobId = "failed-remote",
            remoteStatus = "failed", attempt = 2, requestStarted = true)
        val original = task(listOf(completed, unknown, retry))
        val resumed = original.chunksForExplicitResume()
        assertEquals(completed, resumed[0])
        assertEquals(unknown, resumed[1])
        val pieces = resumed.drop(2)
        assertTrue("The fixture must exercise actual re-splitting", pieces.size > 1)
        assertEquals(longSource.content, pieces.flatMap { it.messages }.joinToString("") { it.content })
        assertEquals(pieces.size, pieces.map { it.id }.distinct().size)
        pieces.forEach { piece ->
            assertEquals(organizationCacheKey(original.settings, original.promptVersion,
                piece.id, piece.messages, original.id), piece.cacheKey)
            assertNotEquals(organizationCacheKey(original.settings, original.promptVersion,
                piece.id, piece.messages), piece.cacheKey)
            assertEquals(3, piece.attempt)
            assertEquals("", piece.remoteJobId)
            assertEquals("", piece.remoteStatus)
            assertFalse(piece.requestStarted)
            assertFalse(piece.completed)
        }
        assertEquals(resumed, original.chunksForExplicitResume())
        assertEquals(listOf(completed, unknown, retry), original.chunks)
    }

    @Test fun unresolvedWorkBlocksAnotherRebuildEvenAfterLocalCancellation() {
        val unsent = TaskChunk("chunk", messages, "cache")
        for (status in listOf("queued", "running", "paused")) {
            assertTrue(status, task(listOf(unsent), status).blocksReorganization())
        }
        assertTrue(task(listOf(unsent.copy(requestStarted = true)), "cancelled").blocksReorganization())
        // The oldest tasks recorded only the aggregate inFlight bit.
        assertTrue(task(listOf(unsent), "cancelled").copy(inFlight = true).blocksReorganization())
        for (remoteStatus in listOf("", "queued", "running", "completed")) {
            val pending = unsent.copy(remoteJobId = "original-remote", remoteStatus = remoteStatus,
                requestStarted = true)
            assertTrue(remoteStatus, task(listOf(pending), "cancelled").blocksReorganization())
        }
        for (remoteStatus in listOf("failed", "uncertain", "cancelled", "expired")) {
            val terminal = unsent.copy(remoteJobId = "original-remote", remoteStatus = remoteStatus,
                requestStarted = true)
            assertFalse(remoteStatus, task(listOf(terminal), "cancelled").blocksReorganization())
        }
        assertFalse(task(listOf(unsent), "cancelled").blocksReorganization())
        assertFalse(task(listOf(unsent.copy(completed = true, remoteJobId = "done-remote",
            remoteStatus = "completed", requestStarted = true)), "completed").blocksReorganization())
    }
}
