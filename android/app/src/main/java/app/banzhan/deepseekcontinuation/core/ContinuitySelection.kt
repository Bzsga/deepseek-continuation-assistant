package app.banzhan.deepseekcontinuation.core

/** Metadata only: the caller validates sources and supplies observed, source-backed features. */
internal data class ContinuityCandidate(
    val id: String,
    val order: Int,
    val branchKey: String,
    val featureKeys: Set<String> = emptySet(),
    val pinned: Boolean = false,
    val explicitlySelected: Boolean = false,
    val characterCount: Int = 0,
    val focusScore: Int = 0,
)

/**
 * Selects representative exchanges without interpreting their text.
 * Explicit selections survive the limit; pins have priority within the remaining capacity.
 * Results retain source order, independently of the caller's candidate enumeration order.
 */
internal fun chooseContinuityCandidates(
    candidates: List<ContinuityCandidate>,
    limit: Int = 3,
    branchKey: String? = null,
): List<String> {
    val tieBreak = compareByDescending<ContinuityCandidate> { it.order }
        .thenBy { it.characterCount.coerceAtLeast(0) }
        .thenBy { it.id }
    val eligible = candidates.filter { branchKey == null || it.branchKey == branchKey }
        .sortedWith(compareByDescending<ContinuityCandidate> { it.explicitlySelected }
            .thenByDescending { it.pinned }.then(tieBreak))
        .distinctBy { it.id }
    val chosen = eligible.filter { it.explicitlySelected }.toMutableList()
    val remaining = eligible.filterNot { it.explicitlySelected }.toMutableList()
    val covered = chosen.flatMap { candidate ->
        candidate.featureKeys.map { candidate.branchKey to it }
    }.toMutableSet()

    while (chosen.size < limit.coerceAtLeast(0) && remaining.isNotEmpty()) {
        val hasFocusedChoice = chosen.any { it.focusScore > 0 }
        val next = remaining.minWithOrNull(
            compareByDescending<ContinuityCandidate> { it.pinned }
                // Give the current topic one place, then retain representative feature coverage.
                .thenByDescending { !hasFocusedChoice && it.focusScore > 0 }
                .thenByDescending { candidate ->
                    candidate.featureKeys.count { (candidate.branchKey to it) !in covered }
                }
                .thenByDescending { it.focusScore.coerceAtLeast(0) }
                .then(tieBreak)
        ) ?: break
        chosen += next
        remaining.remove(next)
        covered += next.featureKeys.map { next.branchKey to it }
    }
    return chosen.sortedWith(compareBy<ContinuityCandidate> { it.order }.thenBy { it.id }).map { it.id }
}
