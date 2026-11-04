package app.banzhan.deepseekcontinuation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import app.banzhan.deepseekcontinuation.data.AppState
import app.banzhan.deepseekcontinuation.data.OrganizationTask
import java.security.MessageDigest

internal fun resumablePreparationTask(conversationId: String, state: AppState): OrganizationTask? {
    val archiveIds = state.archives.filter { conversationId in it.conversationIds }.map { it.id }.toSet()
    return state.tasks.firstOrNull { it.archiveId in archiveIds && it.status in setOf("queued", "running", "paused") }
}

internal class MessageSelectionMemory(private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val ranges = mutableMapOf<String, MessageRange>()
    // Never put full transcript-sized id lists in the Activity saved-state
    // bundle. At most eight compact snapshots occupy under 64 KiB in total.
    private val snapshots = savedState.get<ArrayList<String>>("selection_ranges").orEmpty()
        .chunked(2).filter { it.size == 2 }.associateTo(linkedMapOf()) { it[0] to it[1] }

    fun range(key: String, allIds: Set<String>, hadCustomSelection: Boolean): MessageRange {
        val snapshotKey = selectionDigest(listOf(key))
        val existing =
            ranges.getOrPut(key) {
                val restored = if (hadCustomSelection) snapshots[snapshotKey]?.let { restoreSelection(it, allIds) } else allIds
                MessageRange(restored.orEmpty(), hadCustomSelection && restored == null)
            }
        existing.onSelection = { selected ->
            snapshots.remove(snapshotKey)
            selectionSnapshot(allIds, selected)?.let { snapshots[snapshotKey] = it }
            while (snapshots.size > 8) snapshots.remove(snapshots.keys.first())
            savedState["selection_ranges"] = ArrayList(snapshots.flatMap { listOf(it.key, it.value) })
        }
        if (!hadCustomSelection && existing.ids != allIds) existing.select(allIds)
        else if (existing.ids !== allIds && !allIds.containsAll(existing.ids)) existing.invalidate(existing.ids.intersect(allIds))
        return existing
    }
}

internal class MessageRange(initial: Set<String>, needsReview: Boolean) {
    var onSelection: (Set<String>) -> Unit = {}
    var ids by mutableStateOf(initial)
        private set

    var needsReview by mutableStateOf(needsReview)
        private set

    fun select(value: Set<String>) {
        ids = value
        needsReview = false
        onSelection(value)
    }

    fun invalidate(value: Set<String>) {
        ids = value
        needsReview = true
    }
}

internal fun selectionSnapshot(allIds: Set<String>, selected: Set<String>): String? {
    if (!allIds.containsAll(selected)) return null
    val excluded = selected.size > allIds.size / 2
    val indices = allIds.withIndex().filter { (it.value in selected) != excluded }.map { it.index }
    val ranges = StringBuilder()
    var at = 0
    while (at < indices.size) {
        val first = indices[at]
        var last = first
        while (at + 1 < indices.size && indices[at + 1] == last + 1) { at++; last++ }
        if (ranges.isNotEmpty()) ranges.append(',')
        ranges.append(first)
        if (last != first) ranges.append('-').append(last)
        if (ranges.length > 3800) return null
        at++
    }
    return selectionDigest(allIds) + "|" + (if (excluded) "x" else "i") + "|" + ranges
}

internal fun restoreSelection(snapshot: String, allIds: Set<String>): Set<String>? = runCatching {
    require(snapshot.length <= 3900)
    val parts = snapshot.split('|')
    require(parts.size == 3 && parts[0] == selectionDigest(allIds) && parts[1] in setOf("i", "x"))
    val ordered = allIds.toList()
    val selected = if (parts[1] == "x") allIds.toMutableSet() else mutableSetOf()
    var previous = -1
    if (parts[2].isNotEmpty()) parts[2].split(',').forEach { span ->
        val ends = span.split('-').map { it.toInt() }
        require(ends.size in 1..2)
        val first = ends.first()
        val last = ends.last()
        require(first > previous && last >= first && last < ordered.size)
        for (index in first..last) if (parts[1] == "x") selected.remove(ordered[index]) else selected.add(ordered[index])
        previous = last
    }
    selected.toSet()
}.getOrNull()

private fun selectionDigest(values: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
