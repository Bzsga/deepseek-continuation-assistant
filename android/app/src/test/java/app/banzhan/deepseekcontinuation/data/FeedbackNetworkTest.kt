package app.banzhan.deepseekcontinuation.data

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackNetworkTest {
    @Test
    fun submissionPreservesIdempotencyAndOnlySendsDocumentedFields() {
        val submission = sampleSubmission()
        val payload = FeedbackNetwork.submissionPayload(submission)
        assertEquals(payload, FeedbackNetwork.submissionPayload(submission))
        val root = json(payload)
        assertEquals(setOf("idempotency_key", "content", "contact_type", "contact", "app_version", "images"), root.keys)
        assertEquals(submission.idempotencyKey, root["idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(submission.content, root["content"]!!.jsonPrimitive.content)
        assertEquals(0, (root["images"] as JsonArray).size)
    }

    @Test
    fun submissionRejectsInvalidContactImageCountAndNonBase64InputWithoutEchoingValues() {
        val sample = sampleSubmission()
        listOf(sample.copy(content = " "), sample.copy(contactType = "email"), sample.copy(contact = " "),
            sample.copy(idempotencyKey = "invalid/id"), sample.copy(appVersion = "x".repeat(101))).forEach {
            assertThrows(IllegalArgumentException::class.java) { FeedbackNetwork.submissionPayload(it) }
        }
        val image = FeedbackUploadImage("image/jpeg", Base64.getEncoder().encodeToString(jpegHeader()))
        assertThrows(IllegalArgumentException::class.java) {
            FeedbackNetwork.submissionPayload(sample.copy(images = List(4) { image }))
        }
        val invalidBase64 = "data:image/png;base64,private-test-data"
        val error = assertThrows(IllegalArgumentException::class.java) {
            FeedbackNetwork.submissionPayload(sample.copy(images = listOf(FeedbackUploadImage("image/png", invalidBase64))))
        }
        assertFalse(error.message.orEmpty().contains(invalidBase64))
    }

    @Test
    fun ownerResponseKeepsProcessingResultAndOmitsAdministratorFields() {
        val record = FeedbackNetwork.parseRecord(recordPayload())
        assertEquals("feedback-1", record.id)
        assertEquals("test-account", record.contact)
        assertEquals("needs_review", record.processing!!.resultStatus)
        assertEquals(1, record.processing!!.revision)
        assertNull(record.ownerId)
        assertNull(record.adminNote)
        assertNull(record.revision)
        assertTrue(record.processingHistory.isEmpty())
    }

    @Test
    fun administratorResponseKeepsApprovalRevisionAndIndependentProcessingHistory() {
        val record = FeedbackNetwork.parseRecord(recordPayload(), admin = true)
        assertEquals("owner-1", record.ownerId)
        assertEquals("approved scope", record.adminNote)
        assertEquals(2, record.revision)
        assertEquals("approved", record.status)
        assertEquals("needs_review", record.processingHistory.single().resultStatus)
        assertEquals(1, record.processingHistory.single().revision)
        val update = json(FeedbackNetwork.updatePayload("ignored", "", 2))
        assertEquals("2", update["expected_revision"]!!.jsonPrimitive.content)
        assertEquals("", update["admin_note"]!!.jsonPrimitive.content)
        assertEquals("ignored", update["status"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { FeedbackNetwork.updatePayload("invalid", "", 2) }
        assertThrows(IllegalArgumentException::class.java) { FeedbackNetwork.updatePayload("pending", "", 0) }
    }

    @Test
    fun paginatedResponsePreservesLongTotalsAndRejectsMalformedMetadata() {
        val payload = """{"items":[${recordPayload()}],"total":3000000000,"limit":1,"offset":2}"""
        val page = FeedbackNetwork.parsePage(payload)
        assertEquals(3_000_000_000L, page.total)
        assertEquals(1, page.items.size)
        assertEquals(2, page.offset)
        listOf(
            payload.replace("\"limit\":1", "\"limit\":101"),
            payload.replace("\"total\":3000000000", "\"total\":-1"),
            payload.replace("\"byte_size\":68", "\"byte_size\":1048577"),
            payload.replace("image/png", "image/gif"),
            payload.replace("\"status\":\"approved\"", "\"status\":\"completed\""),
            payload.replace("feedback-1", "../feedback")
        ).forEach { invalid -> assertThrows(Exception::class.java) { FeedbackNetwork.parsePage(invalid) } }
    }

    @Test
    fun imageTransportRequiresAllowedMimeMatchingHeaderAndOneMiBLimit() {
        FeedbackNetwork.validateImageBytes("image/jpeg", jpegHeader())
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        FeedbackNetwork.validateImageBytes("image/png", pngHeader)
        assertThrows(IllegalArgumentException::class.java) { FeedbackNetwork.validateImageBytes("image/png", jpegHeader()) }
        assertThrows(IllegalArgumentException::class.java) { FeedbackNetwork.validateImageBytes("image/gif", pngHeader) }
        assertThrows(IllegalArgumentException::class.java) { FeedbackNetwork.validateImageBytes("image/png", ByteArray(1_048_577)) }
        assertThrows(IllegalArgumentException::class.java) { FeedbackNetwork.validateImageBytes("image/jpeg", byteArrayOf()) }
    }

    @Test
    fun fixedFailuresKeepStatusAndUncertainty() {
        val conflict = FeedbackNetwork.safeFailure(409, mutation = true, ambiguous = false)
        assertEquals(409, conflict.statusCode)
        assertFalse(conflict.ambiguous)
        assertTrue(conflict.message.orEmpty().contains("刷新"))
        val lostUpload = FeedbackNetwork.safeFailure(null, mutation = true, ambiguous = true)
        assertTrue(lostUpload.ambiguous)
        assertTrue(lostUpload.message.orEmpty().contains("保留原内容"))
        val unauthorized = FeedbackNetwork.safeFailure(401, mutation = false, ambiguous = false)
        assertEquals(401, unauthorized.statusCode)
    }

    private fun sampleSubmission() = FeedbackSubmission("test-feedback-0001", "请增加一项测试选项", "qq", "test-account", "test-version", emptyList())
    private fun jpegHeader() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
    private fun json(payload: String): JsonObject = SummaryProtocol.json.parseToJsonElement(payload) as JsonObject

    private fun recordPayload(): String = """
        {"id":"feedback-1","content":"synthetic feedback","contact_type":"qq","contact":"test-account","app_version":"test-version",
        "status":"approved","created_at":"2026-11-04T10:00:00+08:00","updated_at":"2026-11-04T11:00:00+08:00",
        "images":[{"id":"image-1","mime_type":"image/png","byte_size":68}],
        "processing":{"revision":1,"result_status":"needs_review","result_note":"Please add an example.","processed_at":"2026-11-04T10:30:00+08:00"},
        "owner_id":"owner-1","admin_note":"approved scope","revision":2,
        "processing_history":[{"revision":1,"result_status":"needs_review","result_note":"Please add an example.","processed_at":"2026-11-04T10:30:00+08:00"}]}
    """.trimIndent()
}
