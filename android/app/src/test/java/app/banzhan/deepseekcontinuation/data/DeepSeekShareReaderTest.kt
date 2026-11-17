package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.DeepSeekShareLink
import app.banzhan.deepseekcontinuation.core.DeepSeekShareParser
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class DeepSeekShareReaderTest {
    private val link = DeepSeekShareLink.parse("https://chat.deepseek.com/share/abc123")
    private val payload = """{"code":0,"data":{"biz_code":0,"biz_data":{"messages":[{"id":1,"role":"user","content":"合成消息"}]}}}""".toByteArray()

    @Test fun usesOnlyAnonymousFixedEndpointAndReturnsOriginalSnapshotBytes() = runBlocking {
        val factory = FakeFactory { request -> response(request, payload) }
        val document = DeepSeekShareReader(factory).read(link)
        val request = factory.created.await().request()
        assertEquals("https://chat.deepseek.com/api/v0/share/content?share_id=abc123", request.url.toString())
        assertEquals("GET", request.method)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertEquals("identity", request.header("Accept-Encoding"))
        assertTrue(request.header("User-Agent").orEmpty().startsWith("Mozilla/5.0"))
        assertArrayEquals(payload, document.bytes)
        assertEquals("合成消息", document.result.conversations.single().branches.single().messages.single().content)
    }

    @Test fun refusesRedirectsOversizedBodiesEncodingAndExpiredShares() = runBlocking {
        listOf<(Request) -> Response>(
            { response(it, ByteArray(0), code = 302).newBuilder().header("Location", "https://evil.example/").build() },
            { response(it, payload, declaredSize = DeepSeekShareParser.MAX_BYTES.toLong() + 1) },
            { response(it, ByteArray(DeepSeekShareParser.MAX_BYTES + 1), declaredSize = -1) },
            { response(it, payload).newBuilder().header("Content-Encoding", "gzip").build() },
            { response(it, ByteArray(0), code = 404) },
            { response(it, "private malformed response".toByteArray()) },
        ).forEach { respond ->
            val factory = FakeFactory(respond)
            try { DeepSeekShareReader(factory).read(link); throw AssertionError("Accepted invalid response") }
            catch (expected: DeepSeekShareReadException) { assertFalse(expected.message.orEmpty().contains("private")) }
            assertEquals(1, factory.count)
        }
    }

    @Test fun cancellingPreviewCancelsTheActualRequest() = runBlocking {
        val factory = FakeFactory { null }
        val job = launch { DeepSeekShareReader(factory).read(link) }
        val call = factory.created.await()
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        call.callback?.onFailure(call, IOException("private transport detail"))
        assertTrue(job.isCancelled)
    }

    @Test fun accessRejectionsKeepTheHttpCodeAndDoNotAutomaticallyRetry() = runBlocking {
        listOf(403, 429, 502).forEach { code ->
            val factory = FakeFactory { response(it, ByteArray(0), code = code) }
            try {
                DeepSeekShareReader(factory).read(link)
                fail("An access rejection must not become imported content")
            } catch (expected: DeepSeekShareReadException) {
                assertTrue(expected.message.orEmpty().contains("HTTP $code"))
            }
            assertEquals(1, factory.count)
        }
    }

    private fun response(request: Request, bytes: ByteArray, code: Int = 200, declaredSize: Long = bytes.size.toLong()): Response {
        val body = object : ResponseBody() {
            private val buffer = Buffer().write(bytes)
            override fun contentType(): MediaType? = null
            override fun contentLength() = declaredSize
            override fun source(): BufferedSource = buffer
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body).build()
    }

    private class FakeFactory(private val respond: (Request) -> Response?) : Call.Factory {
        val created = CompletableDeferred<FakeCall>()
        var count = 0
        override fun newCall(request: Request): Call = FakeCall(request, respond).also { count++; created.complete(it) }
    }

    private class FakeCall(private val initial: Request, private val respond: (Request) -> Response?) : Call {
        private var cancelled = false
        private var executed = false
        var callback: Callback? = null
        override fun request() = initial
        override fun execute(): Response = error("Only asynchronous requests are supported in this test")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            callback = responseCallback
            respond(initial)?.let { responseCallback.onResponse(this, it) }
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(initial, respond)
    }
}
