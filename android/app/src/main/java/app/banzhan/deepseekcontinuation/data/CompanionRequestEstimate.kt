package app.banzhan.deepseekcontinuation.data

/** Display-only server capability; never part of a task identity or source selection. */
internal val CompanionQuota.fallbackAttemptLimit: Int
    get() = if (accessMode == "public" && automaticFallbackEnabled) maxAttemptsPerChunk.coerceIn(1, 3) else 1

internal fun companionRequestCountText(chunks: Int, maximumAttemptsPerChunk: Int = 1): String {
    val count = chunks.coerceAtLeast(0).toLong()
    val maximum = count * maximumAttemptsPerChunk.coerceIn(1, 3)
    return if (count == maximum) "$count" else "$count～$maximum"
}

internal fun CompanionQuota?.requestCountText(chunks: Int): String =
    companionRequestCountText(chunks, this?.fallbackAttemptLimit ?: 1)

internal fun CompanionQuota?.requestUpperBound(chunks: Int): Long =
    chunks.coerceAtLeast(0).toLong() * (this?.fallbackAttemptLimit ?: 1)

internal fun automaticFallbackNotice(maximumAttemptsPerChunk: Int): String =
    "响应较慢时会自动切换备用模型，每段最多 ${maximumAttemptsPerChunk.coerceIn(1, 3)} 次请求，按实际发出的请求扣次。"
