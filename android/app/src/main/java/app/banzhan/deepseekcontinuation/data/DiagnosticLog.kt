package app.banzhan.deepseekcontinuation.data

import android.content.Context
import android.os.Build
import app.banzhan.deepseekcontinuation.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/** Test-stage technical diagnostics. No conversation content or exception messages are collected. */
object DiagnosticLog {
    private const val COMPANION_URL = DiagnosticPrivacy.COMPANION_URL
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploading = Mutex()
    @Volatile private var queue: DiagnosticQueue? = null
    @Volatile private var token = ""
    @Volatile private var lastAttempt = 0L
    private var lastIdentityAttempt = 0L
    // Release a small reserve before trying to record an OutOfMemoryError.
    private var crashReserve: ByteArray? = null
    private val client by lazy {
        OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    @Synchronized fun initialize(context: Context) {
        if (queue != null) return
        try {
            queue = DiagnosticQueue(File(context.noBackupFilesDir, "diagnostics"))
            crashReserve = ByteArray(128 * 1024)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(DiagnosticExceptionHandler(previous) { error ->
                crashReserve = null
                record("crash", "uncaught", error = error)
            })
            record("lifecycle", "start")
        } catch (_: Throwable) { /* Diagnostics must not prevent application startup. */ }
    }

    /** The caller passes only the existing anonymous identity, never the BYOK configuration. */
    fun bindAnonymousIdentity(mode: String, companionUrl: String, anonymousToken: String) {
        token = if (DiagnosticPrivacy.usesCompanion(mode, companionUrl) && anonymousToken.length in 1..8192 &&
            anonymousToken.all { it.code in 33..126 }) anonymousToken else ""
        requestUpload()
    }

    /** Use AppController's single persisted identity path only after a failure/interruption. */
    @Synchronized fun shouldAcquireIdentity(): Boolean = try {
        val now = System.nanoTime()
        if (token.isNotBlank() || (lastIdentityAttempt != 0L && now - lastIdentityAttempt < TimeUnit.MINUTES.toNanos(5)) ||
            queue?.hasFailureOrInterruptedWork() != true) false
        else { lastIdentityAttempt = now; true }
    } catch (_: Throwable) { false }

    fun record(kind: String, stage: String, metrics: Map<String, Long> = emptyMap(), error: Throwable? = null): String? {
        val destination = queue ?: return null
        return try {
            if (kind !in DiagnosticPrivacy.kinds || stage !in DiagnosticPrivacy.stages) return null
            val runtime = Runtime.getRuntime()
            val safeMetrics = DiagnosticPrivacy.metrics(metrics) + mapOf(
                "heap_free_bytes" to runtime.freeMemory(), "heap_max_bytes" to runtime.maxMemory(),
                "heap_used_bytes" to (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0))
            val event = DiagnosticEvent(
                occurredAt = System.currentTimeMillis() / 1000.0,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = DiagnosticPrivacy.version(BuildConfig.VERSION_NAME),
                androidSdk = Build.VERSION.SDK_INT,
                manufacturer = DiagnosticPrivacy.device(Build.MANUFACTURER.orEmpty()),
                model = DiagnosticPrivacy.device(Build.MODEL.orEmpty()),
                kind = kind, stage = stage, metrics = safeMetrics,
                exceptionClass = DiagnosticPrivacy.exception(error), stackFrames = DiagnosticPrivacy.frames(error),
            )
            destination.append(event)
            if (stage == "failed" || stage == "complete") requestUpload()
            event.eventId
        } catch (_: Throwable) { null /* Includes OOM/disk-full: preserve the original operation. */ }
    }

    /** Bounded best-effort retry on foreground/errors; never creates an account or calls a model. */
    fun requestUpload() {
        if (queue == null || token.isBlank()) return
        scope.launch {
            if (!uploading.tryLock()) return@launch
            try {
                val now = System.nanoTime()
                if (lastAttempt != 0L && now - lastAttempt < TimeUnit.SECONDS.toNanos(30)) return@launch
                lastAttempt = now
                val identity = token
                if (identity.isBlank()) return@launch
                val destination = queue ?: return@launch
                repeat(4) {
                    if (token != identity) return@launch
                    val batch = destination.batch()
                    if (batch.isEmpty()) return@launch
                    val payload = destination.payload(batch)
                    val request = SummaryNetwork.authenticated(SummaryNetwork.companionEndpoint(COMPANION_URL, "diagnostics"), identity)
                        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@launch
                        val source = response.body?.source() ?: return@launch
                        source.request((DiagnosticQueue.MAX_BATCH_BYTES + 1).toLong())
                        if (source.buffer.size > DiagnosticQueue.MAX_BATCH_BYTES) return@launch
                        val acknowledgement = destination.acknowledged(source.readUtf8(), batch)
                        if (acknowledgement.isEmpty()) return@launch
                        destination.acknowledge(acknowledgement)
                    }
                }
            } catch (_: Exception) { /* Keep events for the next foreground; no recursive logging. */ }
            finally { uploading.unlock() }
        }
    }
}
