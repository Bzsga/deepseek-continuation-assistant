package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.AppUpdateManifestParser
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateNetworkTest {

    @Test
    fun checkUsesOnlyTheFixedPublicEndpointAndNoCredentials() = runBlocking {
        val factory = FakeFactory { request -> response(request, manifestJson().toByteArray()) }
        val actual = AppUpdateNetwork(factory).fetchManifest()
        assertEquals(7, actual.versionCode)
        assertEquals(AppUpdateManifestParser.MANIFEST_URL, factory.last.request().url.toString())
        assertNull(factory.last.request().header("Authorization"))
        assertNull(factory.last.request().header("Cookie"))
        assertEquals("identity", factory.last.request().header("Accept-Encoding"))
    }

    @Test
    fun redirectsAndEncodedBodiesAreRejected() = runBlocking {
        val redirect = FakeFactory { request -> response(request, ByteArray(0), code = 302) }
        expectFailure { AppUpdateNetwork(redirect).fetchManifest() }
        assertEquals(1, redirect.created)
        val encoded = FakeFactory { request -> response(request, manifestJson().toByteArray()).newBuilder()
            .header("Content-Encoding", "gzip").build() }
        expectFailure { AppUpdateNetwork(encoded).fetchManifest() }
    }

    @Test
    fun cancellingACheckCancelsItsActualHttpCall() = runBlocking {
        val factory = FakeFactory { null }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { AppUpdateNetwork(factory).fetchManifest() }
        assertFalse(factory.last.isCanceled())
        job.cancelAndJoin()
        assertTrue(factory.last.isCanceled())
        // A late failure after cancellation cannot resume the cancelled continuation.
        factory.last.callback?.onFailure(factory.last, IOException("private upstream detail"))
        assertTrue(job.isCancelled)
    }


    private fun manifestJson() = """{"schemaVersion":1,"packageName":"app.banzhan.deepseekcontinuation","versionCode":7,"versionName":"1.0.0-preview.7","apkUrl":"https://digimonlegend.com/continuation-updates/apks/preview7.apk","apkSize":4,"sha256":"${"a".repeat(64)}","minSdk":26,"releasedAt":"2026-11-04T00:00:00Z","changelog":["支持应用内更新"]}"""

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected a safe update error")
        } catch (expected: AppUpdateDownloadException) {
            assertFalse(expected.message.orEmpty().contains("private"))
        }
    }

    private fun response(request: Request, bytes: ByteArray, code: Int = 200, declaredSize: Long = bytes.size.toLong()): Response {
        val body = object : ResponseBody() {
            private val buffer = Buffer().write(bytes)
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = declaredSize
            override fun source(): BufferedSource = buffer
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body).build()
    }

    private class FakeFactory(private val respond: (Request) -> Response?) : Call.Factory {
        lateinit var last: FakeCall
        var created = 0
        override fun newCall(request: Request): Call = FakeCall(request, respond).also { last = it; created++ }
    }

    private class FakeCall(private val initial: Request, private val respond: (Request) -> Response?) : Call {
        private var cancelled = false
        private var executed = false
        var callback: Callback? = null
        override fun request(): Request = initial
        override fun execute(): Response = error("Only asynchronous requests are supported in this test")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            callback = responseCallback
            respond(initial)?.let { responseCallback.onResponse(this, it) }
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(initial, respond)
    }
}
