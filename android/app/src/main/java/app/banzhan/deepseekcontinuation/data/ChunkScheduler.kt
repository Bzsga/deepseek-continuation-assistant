package app.banzhan.deepseekcontinuation.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val COMPANION_CHUNK_CONCURRENCY = 2

internal fun organizationConcurrency(settings: ServiceSettings): Int =
    if (settings.mode == "companion") COMPANION_CHUNK_CONCURRENCY else 1

internal data class ChunkDispatchFailure(val chunk: TaskChunk, val error: Exception)

internal class TaskDispatchException(val failures: List<ChunkDispatchFailure>) :
    Exception(failures.first().error.message, failures.first().error)

internal class ChunkAdmission(private val stopped: AtomicBoolean = AtomicBoolean(false)) {
    operator fun invoke(): Boolean = !stopped.get()
    fun stop() { stopped.set(true) }

    suspend fun recordStart(persistStarted: suspend () -> Unit, abandonUnsent: suspend () -> Unit): Boolean {
        if (!invoke()) return false
        persistStarted()
        // Room may suspend while another response closes the admission gate.
        if (invoke()) return true
        abandonUnsent()
        return false
    }
}

/** Failure stops admissions, but never cancels a sibling that may already have paid work. */
internal suspend fun dispatchChunks(
    chunks: List<TaskChunk>, parallelism: Int,
    process: suspend (TaskChunk, maySubmit: ChunkAdmission) -> Unit,
): List<ChunkDispatchFailure> = coroutineScope {
    require(parallelism in 1..5)
    val guard = Mutex()
    val admission = ChunkAdmission()
    val failures = mutableListOf<ChunkDispatchFailure>()
    var next = 0
    List(minOf(parallelism, chunks.size)) {
        launch {
            while (true) {
                val chunk = guard.withLock {
                    if (!admission() || next >= chunks.size) null else chunks[next++]
                } ?: break
                try {
                    process(chunk, admission)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    admission.stop()
                    guard.withLock { failures += ChunkDispatchFailure(chunk, error) }
                }
            }
        }
    }.joinAll()
    failures.toList()
}

/** Resolve every old remote/unknown submission before admitting any new work. */
internal suspend fun dispatchTaskChunks(
    chunks: List<TaskChunk>, parallelism: Int,
    process: suspend (TaskChunk, maySubmit: ChunkAdmission) -> Unit,
): List<ChunkDispatchFailure> {
    val unfinished = chunks.filterNot { it.completed }
    val (recovering, fresh) = unfinished.partition { it.remoteJobId.isNotBlank() || it.requestStarted }
    val failures = dispatchChunks(recovering, parallelism, process)
    return if (failures.isNotEmpty()) failures else dispatchChunks(fresh, parallelism, process)
}
