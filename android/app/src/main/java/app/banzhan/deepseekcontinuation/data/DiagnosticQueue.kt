package app.banzhan.deepseekcontinuation.data

import java.io.File
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Deliberately has no message, transcript, URI, filename, account or credential fields. */
@Serializable
internal data class DiagnosticEvent(
    @SerialName("event_id") val eventId: String = UUID.randomUUID().toString(),
    @SerialName("occurred_at") val occurredAt: Double,
    @SerialName("app_version_code") val appVersionCode: Int,
    @SerialName("app_version_name") val appVersionName: String,
    @SerialName("android_sdk") val androidSdk: Int,
    val manufacturer: String,
    val model: String,
    val kind: String,
    val stage: String,
    val metrics: Map<String, Long> = emptyMap(),
    @SerialName("exception_class") val exceptionClass: String = "",
    @SerialName("stack_frames") val stackFrames: List<String> = emptyList(),
)

internal object DiagnosticPrivacy {
    const val COMPANION_URL = "https://digimonlegend.com/continuation-api"
    val kinds = setOf("import", "task", "crash", "lifecycle")
    val stages = setOf("start", "read", "parse", "save", "complete", "failed", "uncaught", "recovered",
        "prepare", "submit", "poll", "resume", "cancel")
    private val metricNames = setOf("input_bytes", "unzipped_bytes", "elapsed_ms", "heap_free_bytes",
        "heap_max_bytes", "heap_used_bytes", "conversation_count", "message_count", "chunk_index", "chunk_count", "http_status")
    private val prefixes = listOf("app.banzhan.deepseekcontinuation.", "java.", "javax.", "kotlin.",
        "kotlinx.", "android.", "androidx.", "okhttp3.", "okio.")
    private val className = Regex("[A-Za-z_$][A-Za-z0-9_.$]*")
    private val methodName = Regex("(?:[A-Za-z_$][A-Za-z0-9_$-]*|<init>|<clinit>)")
    private val sourceName = Regex("[A-Za-z0-9_.-]+\\.(kt|java)")
    fun usesCompanion(mode: String, url: String) = mode == "companion" && url.trimEnd('/') == COMPANION_URL
    fun device(value: String) = value.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " ._()+-" }.take(80)
    fun version(value: String) = value.filter { it.isLetterOrDigit() && it.code < 128 || it in "._+-" }.take(80)
    fun exception(error: Throwable?): String = error?.javaClass?.name?.takeIf(::allowedClass).orEmpty()
    private fun allowedClass(value: String) = value.length <= 200 && className.matches(value) && prefixes.any(value::startsWith)
    fun metrics(values: Map<String, Long>) = values.filter { (key, value) -> key in metricNames && value in 0..9_007_199_254_740_992L }
    fun frames(error: Throwable?): List<String> = error?.stackTrace.orEmpty().asSequence().mapNotNull { frame ->
        if (!allowedClass(frame.className) || !methodName.matches(frame.methodName)) return@mapNotNull null
        val file = frame.fileName?.takeIf { it.length <= 100 && sourceName.matches(it) }
        val source = when {
            frame.isNativeMethod -> "Native Method"
            file != null && frame.lineNumber in 1..9_999_999 -> "$file:${frame.lineNumber}"
            else -> "Unknown Source"
        }
        "${frame.className}.${frame.methodName}($source)".takeIf { it.length <= 400 }
    }.take(30).toList()
}

/** Independent event files avoid rewriting the entire queue while recording a crash. */
internal class DiagnosticQueue(private val directory: File, private val now: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    @Synchronized fun append(event: DiagnosticEvent) {
        require(idPattern.matches(event.eventId))
        require(event.kind in DiagnosticPrivacy.kinds && event.stage in DiagnosticPrivacy.stages)
        directory.mkdirs()
        val bytes = json.encodeToString(event).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_EVENT_BYTES)
        val target = File(directory, "${event.eventId}.json")
        val temporary = File(directory, "${event.eventId}.tmp")
        temporary.outputStream().use { it.write(bytes) }
        if (!temporary.renameTo(target)) { temporary.delete(); return }
        trim()
    }

    @Synchronized fun batch(): List<DiagnosticEvent> {
        trim()
        val events = mutableListOf<DiagnosticEvent>()
        var size = 32
        files().forEach { file ->
            if (events.size == 8 || size + file.length() + 1 > MAX_BATCH_BYTES) return@forEach
            val event = runCatching { json.decodeFromString(DiagnosticEvent.serializer(), file.readText(Charsets.UTF_8)) }.getOrNull()
            if (event == null || event.occurredAt < now() - RETENTION_SECONDS) { file.delete(); return@forEach }
            events += event
            size += file.length().toInt() + 1
        }
        return events
    }

    @Synchronized fun acknowledge(ids: Collection<String>) {
        ids.filter { idPattern.matches(it) }.forEach { File(directory, "$it.json").delete() }
    }

    @Synchronized fun hasFailureOrInterruptedWork(): Boolean {
        trim()
        val events = files().mapNotNull { file ->
            runCatching { json.decodeFromString(DiagnosticEvent.serializer(), file.readText(Charsets.UTF_8)) }.getOrNull()
        }
        if (events.any { it.kind == "crash" || it.stage == "failed" }) return true
        // A process killed by Android cannot run an uncaught-exception handler.
        // Its last durable import breadcrumb still justifies shipping technical diagnostics.
        val lastImport = events.filter { it.kind == "import" }.maxByOrNull { it.occurredAt } ?: return false
        val latestStartup = events.filter { it.kind == "lifecycle" && it.stage == "start" }.maxByOrNull { it.occurredAt }
        return lastImport.stage in setOf("start", "read", "parse", "save") && latestStartup != null &&
            latestStartup.occurredAt > lastImport.occurredAt
    }

    internal fun payload(events: List<DiagnosticEvent>) = "{\"events\":" + json.encodeToString(events) + "}"

    internal fun acknowledged(response: String, sent: List<DiagnosticEvent>): Set<String> {
        require(response.toByteArray(Charsets.UTF_8).size <= MAX_BATCH_BYTES)
        val root = json.parseToJsonElement(response) as? JsonObject ?: return emptySet()
        val sentIds = sent.map { it.eventId }.toSet()
        return listOf("accepted_event_ids", "duplicate_event_ids", "expired_event_ids").flatMap { key ->
            (root[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.filter { it in sentIds }.toSet()
    }

    private fun files() = directory.listFiles { file -> file.isFile && file.extension == "json" && idPattern.matches(file.nameWithoutExtension) }
        .orEmpty().sortedBy { it.lastModified() }

    private fun trim() {
        val cutoffMillis = ((now() - RETENTION_SECONDS) * 1000).toLong()
        directory.listFiles { file -> file.extension == "tmp" && file.lastModified() < System.currentTimeMillis() - 60_000 }
            .orEmpty().forEach { it.delete() }
        val items = files().filter { file ->
            if (file.lastModified() < cutoffMillis || file.length() > MAX_EVENT_BYTES) { file.delete(); false } else true
        }
        var bytes = items.sumOf { it.length() }
        var count = items.size
        items.forEach { file ->
            if (count > MAX_EVENTS || bytes > MAX_QUEUE_BYTES) { bytes -= file.length(); count--; file.delete() }
        }
    }

    companion object {
        const val MAX_EVENTS = 64
        const val MAX_EVENT_BYTES = 16 * 1024
        const val MAX_BATCH_BYTES = 28 * 1024
        const val MAX_QUEUE_BYTES = 256 * 1024
        const val RETENTION_SECONDS = 30 * 24 * 3600.0
    }
}

/** Always delegates, even if diagnostic storage itself throws (including memory pressure). */
internal class DiagnosticExceptionHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val record: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { record(error) } catch (_: Throwable) { /* Never replace the original crash. */ }
        finally { previous?.uncaughtException(thread, error) }
    }
}
