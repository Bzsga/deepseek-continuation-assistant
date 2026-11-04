package app.banzhan.deepseekcontinuation.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticQueueTest {
    private val now = System.currentTimeMillis() / 1000.0
    private fun event(stage: String = "failed") = DiagnosticEvent(
        occurredAt = now, appVersionCode = 9, appVersionName = "1.0.0-preview.9", androidSdk = 35,
        manufacturer = "OPPO", model = "Find X9", kind = "import", stage = stage)

    @Test fun diagnosticUploadIsLimitedToCompanionModeAndTheOriginalEndpoint() {
        assertTrue(DiagnosticPrivacy.usesCompanion("companion", "https://digimonlegend.com/continuation-api/"))
        assertFalse(DiagnosticPrivacy.usesCompanion("byok", "https://digimonlegend.com/continuation-api"))
        assertFalse(DiagnosticPrivacy.usesCompanion("companion", "https://elsewhere.invalid/continuation-api"))
        assertFalse(DiagnosticPrivacy.usesCompanion("companion", "https://digimonlegend.com/continuation-api?secret=x"))
    }

    @Test fun exceptionMessagesPathsAndUnknownMetricsCannotEnterPayload() {
        val privateMarker = "PRIVATE-CHAT-AND-KEY"
        val error = IllegalStateException(privateMarker)
        error.stackTrace = arrayOf(
            StackTraceElement("app.banzhan.deepseekcontinuation.data.AppController", "importFile", "AppController.kt", 123),
            StackTraceElement("third.party.$privateMarker", "method", "$privateMarker.java", 5),
            StackTraceElement("java.lang.Thread", "run", "/storage/$privateMarker.java", 7),
        )
        val item = event().copy(exceptionClass = DiagnosticPrivacy.exception(error), stackFrames = DiagnosticPrivacy.frames(error),
            metrics = DiagnosticPrivacy.metrics(mapOf(privateMarker to 12L, "input_bytes" to 32L, "http_status" to -1)))
        val directory = Files.createTempDirectory("diagnostic-private").toFile()
        try {
            val queue = DiagnosticQueue(directory)
            queue.append(item)
            val payload = queue.payload(queue.batch())
            assertFalse(payload.contains(privateMarker))
            assertFalse(payload.contains("/storage/"))
            assertTrue(payload.contains("AppController.importFile(AppController.kt:123)"))
            assertTrue(payload.contains("java.lang.Thread.run(Unknown Source)"))
            assertEquals(mapOf("input_bytes" to 32L), item.metrics)
        } finally { directory.deleteRecursively() }
    }

    @Test fun queueIsBoundedAndBatchFitsBodyBudget() {
        val directory = Files.createTempDirectory("diagnostic-bound").toFile()
        try {
            val queue = DiagnosticQueue(directory)
            repeat(100) { queue.append(event()) }
            assertEquals(64, directory.listFiles()!!.size)
            val batch = queue.batch()
            assertEquals(8, batch.size)
            assertTrue(queue.payload(batch).toByteArray().size < 32 * 1024)
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= DiagnosticQueue.MAX_QUEUE_BYTES)
        } finally { directory.deleteRecursively() }
    }

    @Test fun onlyAcknowledgedSentEventsAreDeletedAcrossRestart() {
        val directory = Files.createTempDirectory("diagnostic-ack").toFile()
        try {
            val queue = DiagnosticQueue(directory)
            val first = event(); val second = event()
            queue.append(first); queue.append(second)
            val batch = queue.batch()
            val acknowledgement = queue.acknowledged("""{"accepted_event_ids":["${first.eventId}","../../outside"],"expired_event_ids":[],"duplicate_event_ids":[]}""", batch)
            queue.acknowledge(acknowledgement)
            val reopened = DiagnosticQueue(directory).batch()
            assertEquals(listOf(second.eventId), reopened.map { it.eventId })
            assertTrue(queue.acknowledged("{}", reopened).isEmpty())
            assertTrue(queue.acknowledged("""{"accepted_event_ids":["${first.eventId}"]}""", reopened).isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun expiredAndOversizedFilesDoNotBlockFreshReports() {
        val directory = Files.createTempDirectory("diagnostic-expiry").toFile()
        try {
            val queue = DiagnosticQueue(directory)
            val old = event().copy(occurredAt = now - DiagnosticQueue.RETENTION_SECONDS - 10)
            val fresh = event()
            queue.append(old); queue.append(fresh)
            assertEquals(listOf(fresh.eventId), queue.batch().map { it.eventId })
            val extra = event()
            java.io.File(directory, "${extra.eventId}.json").writeText("x".repeat(20 * 1024))
            assertEquals(listOf(fresh.eventId), queue.batch().map { it.eventId })
            assertEquals(1, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun crashCaptureAlwaysDelegatesEvenWhenLoggingFails() {
        val error = OutOfMemoryError("must not be serialized")
        var recorded: Throwable? = null
        var delegated: Throwable? = null
        val handler = DiagnosticExceptionHandler(Thread.UncaughtExceptionHandler { _, original -> delegated = original }) {
            recorded = it
            throw OutOfMemoryError("simulated storage failure")
        }
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, recorded)
        assertSame(error, delegated)
    }

    @Test fun identityIsNeededOnlyForFailureOrWorkInterruptedBeforeThisLaunch() {
        val directory = Files.createTempDirectory("diagnostic-identity").toFile()
        try {
            val queue = DiagnosticQueue(directory)
            queue.append(event("start").copy(kind = "lifecycle", occurredAt = now - 5))
            queue.append(event("parse").copy(occurredAt = now - 3))
            assertFalse(queue.hasFailureOrInterruptedWork())
            queue.append(event("start").copy(kind = "lifecycle", occurredAt = now - 1))
            assertTrue(queue.hasFailureOrInterruptedWork())
            queue.append(event("complete").copy(occurredAt = now))
            assertFalse(queue.hasFailureOrInterruptedWork())
            queue.append(event("failed").copy(occurredAt = now + 1))
            assertTrue(queue.hasFailureOrInterruptedWork())
        } finally { directory.deleteRecursively() }
    }

    @Test fun stackAndDeviceMetadataAreBounded() {
        val error = RuntimeException("private")
        error.stackTrace = Array(80) { StackTraceElement("java.lang.Thread", "run", "Thread.java", 1) }
        assertEquals(30, DiagnosticPrivacy.frames(error).size)
        assertEquals("OPPO Find X9", DiagnosticPrivacy.device("OPPO Find X9\n\u0000中"))
        assertEquals("1.0.0-preview.9", DiagnosticPrivacy.version("1.0.0-preview.9\n"))
        assertEquals("", DiagnosticPrivacy.exception(org.junit.AssumptionViolatedException("private")))
    }
}
