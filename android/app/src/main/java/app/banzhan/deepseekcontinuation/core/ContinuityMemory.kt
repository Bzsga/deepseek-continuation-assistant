package app.banzhan.deepseekcontinuation.core

import java.util.TreeMap

/** Selects whole saved summary entries for one export; never changes or expands the archive. */
object ContinuityMemory {
    fun continuationMemory(
        archive: Archive,
        conversations: List<ImportedConversation>,
        maxCharacters: Int,
    ): ExportResult {
        require(maxCharacters >= 0) { "摘要参考长度不能为负数。" }
        val valid = archive.entries.withIndex().filter { !it.value.deleted && it.value.text.isNotBlank() }
        if (archive.generationMode != "summary") return render(archive, valid.map { it.value })

        val protected = valid.filter { it.value.edited || it.value.pinned || it.value.sourceIds.isEmpty() }
        val selected = protected.mapTo(mutableSetOf()) { it.index }
        val automatic = valid.filter { it.index !in selected }
        val baseline = render(archive, protected.map { it.value })
        if (baseline.text.length >= maxCharacters || automatic.isEmpty()) {
            return baseline.copy(omittedCount = automatic.size)
        }

        val recency = sourceRecency(archive, conversations, automatic)
        val byImportance = compareBy<IndexedValue<ArchiveEntry>> { kindPriority(it.value.kind) }
            .thenByDescending { recency[it.index] ?: -1.0 }
            .thenBy { it.index }
        // Give each category a turn within a kind before taking more from a crowded category.
        val categoryRounds = automatic.groupBy { it.value.kind to it.value.category }.values
            .flatMap { group -> group.sortedWith(byImportance).mapIndexed { round, entry -> entry.index to round } }
            .toMap()
        val priority = automatic.sortedWith(compareBy<IndexedValue<ArchiveEntry>> { kindPriority(it.value.kind) }
            .thenBy { categoryRounds.getValue(it.index) }
            .thenByDescending { recency[it.index] ?: -1.0 }
            .thenBy { it.index })

        val sections = EntryCategory.entries.associateWith { TreeMap<Int, ArchiveEntry>() }
        protected.forEach { sections.getValue(it.value.category)[it.index] = it.value }
        var length = baseline.text.length.toLong()
        val accepted = mutableListOf<IndexedValue<ArchiveEntry>>()
        for (candidate in priority) {
            val entry = candidate.value
            val section = sections.getValue(entry.category)
            val previous = section.lowerEntry(candidate.index)?.value
            val next = section.higherEntry(candidate.index)?.value
            var addition = entry.text.length.toLong() + 2
            if (length == 0L) addition += "【续聊摘要】${archive.title}\n".length
            if (section.isEmpty() && !(entry.category == EntryCategory.BACKGROUND && archive.background.isNotBlank())) {
                addition += sectionHeaderLength(entry.category)
            }
            if (previous?.kind != entry.kind) addition += kindHeaderLength(entry.kind)
            if (next != null) {
                if (entry.kind != next.kind) addition += kindHeaderLength(next.kind)
                if (previous?.kind != next.kind) addition -= kindHeaderLength(next.kind)
            }
            if (length + addition > maxCharacters) continue
            selected += candidate.index
            section[candidate.index] = entry
            accepted += candidate
            length += addition
        }

        fun selectedEntries() = valid.filter { it.index in selected }.map { it.value }
        var result = render(archive, selectedEntries())
        // One bounded calibration keeps the budget safe if presentation punctuation changes.
        var excess = result.text.length.toLong() - maxCharacters
        if (excess > 0) {
            for (candidate in accepted.asReversed()) {
                if (excess <= 0) break
                selected -= candidate.index
                excess -= candidate.value.text.length.toLong() + 2
            }
            result = render(archive, selectedEntries())
        }
        return result.copy(omittedCount = valid.size - selected.size, pinnedOverflow = false)
    }

    private fun render(archive: Archive, entries: List<ArchiveEntry>): ExportResult =
        if (entries.isEmpty() && archive.background.isBlank()) ExportResult("", 0, false)
        else ArchiveLogic.continuationExport(archive.copy(entries = entries), includeGuidance = false)

    private data class SourceBranch(val messages: Map<String, ChatMessage>, val positions: Map<String, Int>)

    private fun sourceRecency(
        archive: Archive,
        conversations: List<ImportedConversation>,
        entries: List<IndexedValue<ArchiveEntry>>,
    ): Map<Int, Double> {
        val allowed = archive.conversationIds.toSet()
        val conversationsById = conversations.groupBy { it.id }
        val branches = archive.sourceScopes.groupBy { it.conversationId to it.branchId }.mapNotNull { (key, scopes) ->
            if (key.first !in allowed) return@mapNotNull null
            val conversation = conversationsById[key.first]?.singleOrNull() ?: return@mapNotNull null
            val branch = conversation.branches.singleOrNull { it.id == key.second } ?: return@mapNotNull null
            val positions = mutableMapOf<String, Int>()
            val duplicates = mutableSetOf<String>()
            branch.messages.forEachIndexed { index, message ->
                if (positions.put(message.id, index) != null) duplicates += message.id
            }
            val validScopes = scopes.all { scope ->
                var previous = -1
                scope.selectedMessageIds.all { id ->
                    val position = positions[id]
                    if (id in duplicates || position == null || position <= previous) false
                    else { previous = position; true }
                }
            }
            if (!validScopes) return@mapNotNull null
            val selectedIds = scopes.flatMap { it.selectedMessageIds }.toSet()
            val messages = branch.messages.filter { it.id in selectedIds }
            if (messages.isEmpty()) return@mapNotNull null
            SourceBranch(messages.associateBy { it.id }, messages.mapIndexed { index, message -> message.id to index }.toMap())
        }
        val sourceBranches = mutableMapOf<String, MutableSet<Int>>()
        branches.forEachIndexed { index, branch ->
            branch.messages.keys.forEach { id -> sourceBranches.getOrPut(id) { mutableSetOf() } += index }
        }
        return entries.mapNotNull { indexed ->
            val entry = indexed.value
            val matching = entry.sourceIds.map { sourceBranches[it].orEmpty() }
                .reduceOrNull { first, next -> first.intersect(next) }?.singleOrNull() ?: return@mapNotNull null
            val branch = branches[matching]
            val roles = entry.sourceIds.map { branch.messages.getValue(it).role.lowercase() }
            if (entry.kind == EntryKind.USER_FACT && roles.any { it != "user" }) return@mapNotNull null
            if (entry.kind == EntryKind.ASSISTANT_SUGGESTION && "assistant" !in roles) return@mapNotNull null
            // Relative recency in this exact selected branch, not a global chronology across branches.
            val latest = entry.sourceIds.maxOf { branch.positions.getValue(it) }
            indexed.index to (latest + 1).toDouble() / branch.messages.size
        }.toMap()
    }

    private fun kindPriority(kind: EntryKind): Int = when (kind) {
        EntryKind.USER_FACT -> 0
        EntryKind.UNCONFIRMED -> 1
        EntryKind.ASSISTANT_SUGGESTION -> 2
    }

    // Lengths mirror continuationExport; actual output is rendered there and calibrated once above.
    private fun kindHeaderLength(kind: EntryKind): Int = 4 + when (kind) {
        EntryKind.USER_FACT -> "用户陈述".length
        EntryKind.ASSISTANT_SUGGESTION -> "讨论中的建议".length
        EntryKind.UNCONFIRMED -> "讨论与理解".length
    }

    private fun sectionHeaderLength(category: EntryCategory): Int = 5 + when (category) {
        EntryCategory.BACKGROUND -> "关于我".length
        EntryCategory.PEOPLE -> "重要的人和事".length
        EntryCategory.DECISIONS -> "已确定的事".length
        EntryCategory.RECENT -> "接下来要聊的事".length
        EntryCategory.QUOTES -> "重要原话".length
    }
}
