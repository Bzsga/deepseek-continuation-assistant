package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkSchedulerTest {
    @Test fun configuredMaximumRunsFiveChunksAndKeepsTheSixthWaitingForASlot() = runTest {
        val chunks = (1..6).map { chunk("part-$it") }
        val releases = chunks.associate { it.id to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val saved = mutableListOf<String>()
        var active = 0
        var maximum = 0
        val dispatch = async {
            dispatchTaskChunks(chunks, 5) { part, maySubmit ->
                assertTrue(maySubmit())
                started += part.id
                active += 1
                maximum = maxOf(maximum, active)
                try {
                    releases.getValue(part.id).await()
                    saved += part.id
                } finally {
                    active -= 1
                }
            }
        }
        try {
            runCurrent()
            assertEquals(chunks.take(5).map { it.id }, started)
            assertEquals(5, active)
            assertFalse(dispatch.isCompleted)

            releases.getValue("part-3").complete(Unit)
            runCurrent()
            assertEquals(chunks.map { it.id }, started)
            assertEquals(listOf("part-3"), saved)
            assertEquals(5, active)

            releases.values.forEach { it.complete(Unit) }
            assertTrue(dispatch.await().isEmpty())
            assertEquals(chunks.map { it.id }.toSet(), saved.toSet())
            assertEquals(5, maximum)
            assertEquals(0, active)
        } finally {
            dispatch.cancelAndJoin()
        }
    }

    @Test fun oneFailureStopsFreshAdmissionsButWaitsForEveryAlreadySubmittedSibling() = runTest {
        val chunks = (1..6).map { chunk("part-$it") }
        val releases = chunks.associate { it.id to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val failure = IllegalStateException("synthetic first-chunk failure")
        val dispatch = async {
            dispatchChunks(chunks, 5) { part, _ ->
                started += part.id
                releases.getValue(part.id).await()
                if (part == chunks.first()) throw failure
                saved += part.id
            }
        }
        try {
            runCurrent()
            releases.getValue("part-1").complete(Unit)
            runCurrent()
            assertEquals(chunks.take(5).map { it.id }, started)
            assertTrue(saved.isEmpty())
            assertFalse(dispatch.isCompleted)

            chunks.drop(1).take(4).forEach { releases.getValue(it.id).complete(Unit) }
            val failures = dispatch.await()
            assertEquals(1, failures.size)
            assertEquals(chunks.first(), failures.single().chunk)
            assertSame(failure, failures.single().error)
            assertEquals(chunks.drop(1).take(4).map { it.id }.toSet(), saved.toSet())
            assertFalse("part-6" in started)
        } finally {
            dispatch.cancelAndJoin()
        }
    }

    @Test fun preflightCannotSubmitAfterAnotherChunkHasFailed() = runTest {
        val chunks = (1..3).map { chunk("part-$it") }
        val failFirst = CompletableDeferred<Unit>()
        val finishPreflight = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        var preflightFinished = false
        val dispatch = async {
            dispatchChunks(chunks, 2) { part, maySubmit ->
                entered += part.id
                if (part == chunks.first()) {
                    assertTrue(maySubmit())
                    submitted += part.id
                    failFirst.await()
                    throw IllegalStateException("synthetic provider failure")
                }
                finishPreflight.await()
                preflightFinished = true
                if (maySubmit()) submitted += part.id
            }
        }
        try {
            runCurrent()
            assertEquals(chunks.take(2).map { it.id }, entered)
            failFirst.complete(Unit)
            runCurrent()
            assertFalse(dispatch.isCompleted)

            finishPreflight.complete(Unit)
            assertEquals(chunks.first(), dispatch.await().single().chunk)
            assertTrue(preflightFinished)
            assertEquals(listOf("part-1"), submitted)
            assertEquals(chunks.take(2).map { it.id }, entered)
        } finally {
            dispatch.cancelAndJoin()
        }
    }

    @Test fun allRemoteAndUnknownRequestsFinishRecoveryBeforeAnyFreshChunkStarts() = runTest {
        val freshFirst = chunk("fresh-first")
        val remote = chunk("remote").copy(remoteJobId = "server-job")
        val freshLast = chunk("fresh-last")
        val unknown = chunk("unknown").copy(requestStarted = true, attempt = 4)
        val completed = chunk("completed").copy(completed = true, remoteJobId = "saved-job")
        val chunks = listOf(freshFirst, remote, completed, freshLast, unknown)
        val releases = chunks.associate { it.id to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val dispatch = async {
            dispatchTaskChunks(chunks, 5) { part, _ ->
                started += part.id
                releases.getValue(part.id).await()
            }
        }
        try {
            runCurrent()
            assertEquals(listOf("remote", "unknown"), started)
            releases.getValue("remote").complete(Unit)
            runCurrent()
            assertEquals(listOf("remote", "unknown"), started)

            releases.getValue("unknown").complete(Unit)
            runCurrent()
            assertEquals(listOf("remote", "unknown", "fresh-first", "fresh-last"), started)
            releases.getValue("fresh-first").complete(Unit)
            releases.getValue("fresh-last").complete(Unit)
            assertTrue(dispatch.await().isEmpty())
            assertFalse("completed" in started)
        } finally {
            dispatch.cancelAndJoin()
        }
    }

    @Test fun recoveryFailureStillDrainsItsSiblingAndNeverStartsFreshWork() = runTest {
        val remote = chunk("remote").copy(remoteJobId = "server-job")
        val unknown = chunk("unknown").copy(requestStarted = true)
        val fresh = chunk("fresh")
        val failRecovery = CompletableDeferred<Unit>()
        val completeSibling = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val dispatch = async {
            dispatchTaskChunks(listOf(fresh, remote, unknown), 5) { part, _ ->
                started += part.id
                if (part == remote) {
                    failRecovery.await()
                    throw IllegalStateException("synthetic poll failure")
                }
                completeSibling.await()
                saved += part.id
            }
        }
        try {
            runCurrent()
            failRecovery.complete(Unit)
            runCurrent()
            assertEquals(listOf("remote", "unknown"), started)
            assertFalse(dispatch.isCompleted)

            completeSibling.complete(Unit)
            assertEquals(remote, dispatch.await().single().chunk)
            assertEquals(listOf("unknown"), saved)
            assertFalse("fresh" in started)
        } finally {
            dispatch.cancelAndJoin()
        }
    }

    @Test fun parentCancellationStopsAllActiveWaitsWithoutAdmittingMoreChunks() = runTest {
        val chunks = (1..6).map { chunk("part-$it") }
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val dispatch = async {
            dispatchChunks(chunks, 5) { part, _ ->
                started += part.id
                try {
                    awaitCancellation()
                } finally {
                    cancelled += part.id
                }
            }
        }
        try {
            runCurrent()
            assertEquals(chunks.take(5).map { it.id }, started)
            dispatch.cancelAndJoin()
            assertTrue(dispatch.isCancelled)
            assertEquals(started.toSet(), cancelled.toSet())
            assertFalse("part-6" in started)
        } finally {
            dispatch.cancelAndJoin()
        }
    }

    @Test fun byokKeepsOneRequestAtATime() = runTest {
        assertEquals(2, organizationConcurrency(ServiceSettings(mode = "companion")))
        val parallelism = organizationConcurrency(ServiceSettings(mode = "byok"))
        assertEquals(1, parallelism)
        val chunks = listOf(chunk("first"), chunk("second"))
        val releases = chunks.associate { it.id to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val dispatch = async {
            dispatchTaskChunks(chunks, parallelism) { part, _ ->
                started += part.id
                releases.getValue(part.id).await()
            }
        }
        try {
            runCurrent()
            assertEquals(listOf("first"), started)
            releases.getValue("first").complete(Unit)
            runCurrent()
            assertEquals(listOf("first", "second"), started)
            releases.getValue("second").complete(Unit)
            assertTrue(dispatch.await().isEmpty())
        } finally {
            dispatch.cancelAndJoin()
        }
    }

    @Test fun serializationKeepsEachRequestIdentityAndItsOwnInFlightMarker() {
        val remote = chunk("remote").copy(remoteJobId = "server-job", remoteStatus = "running",
            requestStarted = true, inFlight = true, attempt = 2)
        val unknown = chunk("unknown").copy(requestStarted = true, inFlight = true, attempt = 3)
        val original = task(listOf(remote, unknown)).copy(status = "running", inFlight = true)
        val decoded = appJson.decodeFromString<TaskRecord>(appJson.encodeToString(original))
        assertEquals(original, decoded)

        val paused = decoded.afterProcessDeath()
        assertEquals("paused", paused.status)
        assertFalse(paused.inFlight)
        assertTrue(paused.uncertain)
        assertEquals(listOf(remote.copy(inFlight = false), unknown.copy(inFlight = false)), paused.chunks)
    }

    @Test fun unknownPostResumeDoesNotSplitMessagesOrChangeTheIdempotencyIdentity() {
        val unknown = chunk("large-unknown").copy(
            messages = listOf(ChatMessage("long-source", "user", "原始分段".repeat(3000))),
            attempt = 7, requestStarted = true, inFlight = true,
        )
        val settings = ServiceSettings(contextTokens = 64000, maxOutputTokens = 4096)
        assertTrue(budgetedChunks(unknown.messages, settings).size > 1)
        val original = task(listOf(unknown)).copy(settings = settings, status = "paused", uncertain = true)
        val decoded = appJson.decodeFromString<TaskRecord>(appJson.encodeToString(original))
        val resumed = decoded.chunksForExplicitResume()
        assertEquals(listOf(unknown.copy(inFlight = false)), resumed)
        assertEquals("${original.id}:${unknown.id}:${unknown.attempt}",
            "${decoded.id}:${resumed.single().id}:${resumed.single().attempt}")
        assertTrue(decoded.copy(chunks = resumed).hasUnknownRequest())
    }

    @Test fun legacyAggregateInFlightCreatesOnlyTheOriginalUnfinishedRequestMarker() {
        val legacy = appJson.decodeFromString<TaskRecord>("""
            {
              "id":"legacy", "archiveId":"archive", "label":"legacy task", "settings":{},
              "secretRef":"", "status":"running", "inFlight":true,
              "chunks":[
                {"id":"first","messages":[{"id":"m1","role":"user","content":"first"}],"cacheKey":"first-cache"},
                {"id":"second","messages":[{"id":"m2","role":"user","content":"second"}],"cacheKey":"second-cache"}
              ]
            }
        """.trimIndent())
        assertTrue(legacy.chunks.none { it.requestStarted || it.inFlight })
        val paused = legacy.afterProcessDeath()
        assertTrue(paused.uncertain)
        assertFalse(paused.inFlight)
        assertEquals(legacy.chunks.first().copy(requestStarted = true), paused.chunks.first())
        assertEquals(legacy.chunks.last(), paused.chunks.last())
        assertEquals(paused.chunks, paused.chunksForExplicitResume())
    }

    @Test fun completingFirstChunkDoesNotClearSecondUnknownPostOrItsUncertainty() {
        val first = chunk("first").copy(remoteJobId = "server-first", requestStarted = true, inFlight = true)
        val second = chunk("second").copy(requestStarted = true, inFlight = true, attempt = 3)
        val running = task(listOf(first, second)).copy(status = "running", inFlight = true, uncertain = true)
        val updated = running.updateChunk(first.id) { it.copy(completed = true, inFlight = false, remoteStatus = "completed") }
        assertTrue(updated.chunks.first().completed)
        assertEquals(second, updated.chunks.last())
        assertTrue(updated.inFlight)
        assertTrue(updated.uncertain)
        assertTrue(updated.hasUnknownRequest())
        assertTrue(updated.hasUncertainRequest())

        val interrupted = updated.withoutInFlight()
        assertFalse(interrupted.inFlight)
        assertFalse(interrupted.chunks.last().inFlight)
        assertTrue(interrupted.chunks.last().requestStarted)
        assertTrue(interrupted.hasUnknownRequest())
        assertEquals(second.copy(inFlight = false), interrupted.chunksForExplicitResume().last())
    }

    @Test fun legacyPausedUnknownPostMigratesOnceWithoutMistakingNewRetriesForSubmittedWork() {
        val legacyJson = """
            {"id":"old","archiveId":"archive","label":"legacy","settings":{},"secretRef":"",
             "status":"paused","inFlight":false,"uncertain":true,
             "chunks":[{"id":"old-part","messages":[{"id":"source","role":"user","content":"old content"}],"cacheKey":"old-cache"}]}
        """.trimIndent()
        val migrated = TaskRow("old", "archive", 1, legacyJson).decodeTask()
        assertTrue(migrated.chunks.single().requestStarted)
        assertEquals(migrated.chunks, migrated.chunksForExplicitResume())

        val newRetry = task(listOf(chunk("retry").copy(attempt = 3))).copy(status = "paused", uncertain = true)
        val decoded = TaskRow(newRetry.id, newRetry.archiveId, 1, appJson.encodeToString(newRetry)).decodeTask()
        assertEquals(newRetry, decoded)
        assertFalse(decoded.chunks.single().requestStarted)
    }

    @Test fun terminalFailureStopsAdmissionsBeforeItsDurableStateWriteFinishes() = runTest {
        val first = chunk("first")
        val second = chunk("second")
        val third = chunk("third")
        val failedResponse = CompletableDeferred<Unit>()
        val durableWrite = CompletableDeferred<Unit>()
        val siblingResult = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val dispatch = async {
            dispatchChunks(listOf(first, second, third), 2) { part, admission ->
                started += part.id
                if (part == first) {
                    failedResponse.await()
                    admission.stop()
                    durableWrite.await()
                    throw SummaryFailure("synthetic terminal failure", ambiguous = false)
                }
                siblingResult.await()
            }
        }
        runCurrent()
        failedResponse.complete(Unit)
        runCurrent()
        siblingResult.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), started)
        assertFalse(dispatch.isCompleted)
        durableWrite.complete(Unit)
        assertEquals(first, dispatch.await().single().chunk)
    }

    @Test fun recoveryRefusalKeepsUnknownIdentityAndExpiryBelongsToItsActualChunk() {
        val unknown = chunk("unknown").copy(requestStarted = true, inFlight = true, attempt = 4)
        val remote = chunk("remote").copy(remoteJobId = "known-id", requestStarted = true, inFlight = true)
        val running = task(listOf(unknown, remote)).copy(status = "running", inFlight = true)
        val paused = running.afterDispatchFailures(listOf(
            ChunkDispatchFailure(unknown, SummaryFailure("recovery unauthorized", ambiguous = false, statusCode = 401)),
            ChunkDispatchFailure(remote, SummaryFailure("remote expired", ambiguous = false, expired = true)),
        ))
        assertTrue(paused.uncertain)
        assertFalse(paused.inFlight)
        assertEquals("", paused.chunks.first().remoteStatus)
        assertTrue(paused.chunks.first().requestStarted)
        assertEquals("expired", paused.chunks.last().remoteStatus)
        val resumed = paused.chunksForExplicitResume()
        assertEquals(unknown.copy(inFlight = false), resumed.first())
        assertEquals(remote.attempt + 1, resumed.last().attempt)
        assertEquals("", resumed.last().remoteJobId)
        assertFalse(resumed.last().requestStarted)

        val fresh = chunk("fresh")
        val refused = task(listOf(fresh.copy(requestStarted = true, inFlight = true)))
            .afterDispatchFailures(listOf(ChunkDispatchFailure(fresh, SummaryFailure("quota exhausted", ambiguous = false))))
        assertFalse(refused.chunks.single().requestStarted)
        assertFalse(refused.uncertain)
    }

    @Test fun failureDuringDurableAdmissionPreventsPostAndRestoresUnsentState() = runTest {
        for (wasStarted in listOf(false, true)) {
            val admission = ChunkAdmission()
            val diskWrite = CompletableDeferred<Unit>()
            val old = chunk("candidate").copy(requestStarted = wasStarted)
            var stored = old
            var submitted = false
            val attempt = async {
                if (admission.recordStart(persistStarted = {
                        stored = stored.copy(requestStarted = true, inFlight = true)
                        diskWrite.await()
                    }, abandonUnsent = {
                        stored = stored.copy(requestStarted = old.requestStarted, inFlight = false)
                    })) submitted = true
            }
            runCurrent()
            assertTrue(stored.inFlight)
            admission.stop()
            diskWrite.complete(Unit)
            attempt.await()
            assertFalse(submitted)
            assertEquals(old, stored)
        }
    }

    private fun chunk(id: String) = TaskChunk(id,
        listOf(ChatMessage("source-$id", "user", "synthetic message $id")), "cache-$id")

    private fun task(chunks: List<TaskChunk>) = TaskRecord("task", "archive", "synthetic task",
        ServiceSettings(), "", chunks)
}
