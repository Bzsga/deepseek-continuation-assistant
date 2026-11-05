package app.banzhan.deepseekcontinuation.core

/** Lossless presentation cleanup only; never rewrites saved entries or guesses semantic equivalence. */
internal object ContinuityPresentation {
    private data class Branch(val key: Pair<String, String>, val messages: Map<String, ChatMessage>,
        val positions: Map<String, Int>)

    fun consolidate(archive: Archive, conversations: List<ImportedConversation>): Archive {
        if (!archive.continuity.enabled || archive.generationMode != "summary") return archive
        val branches = archive.sourceScopes.groupBy { it.conversationId to it.branchId }.mapNotNull { (key, scopes) ->
            val conversation = conversations.singleOrNull { it.id == key.first && it.id in archive.conversationIds }
                ?: return@mapNotNull null
            val branch = conversation.branches.singleOrNull { it.id == key.second } ?: return@mapNotNull null
            if (scopes.any { scope -> runCatching {
                    ContinuityLogic.captureScope(conversation, branch.id, scope.selectedMessageIds.toSet()) != scope
                }.getOrDefault(true) }) return@mapNotNull null
            val selected = scopes.flatMap { it.selectedMessageIds }.toSet()
            val messages = branch.messages.filter { it.id in selected }
            val indexed = messages.associateBy { it.id }
            if (indexed.size != messages.size) return@mapNotNull null
            Branch(key, indexed, messages.mapIndexed { index, message -> message.id to index }.toMap())
        }
        val blocked = archive.entries.filter { it.edited || it.deleted || it.pinned }.flatMap { it.sourceIds }.toSet()
        data class Key(val branch: Pair<String, String>, val category: EntryCategory, val kind: EntryKind,
            val cue: ContinuityCue?, val text: String)
        val groups = linkedMapOf<Key, MutableList<Int>>()
        val entries = archive.entries.toMutableList()
        val removed = mutableSetOf<Int>()
        entries.forEachIndexed { index, entry ->
            if (entry.edited || entry.deleted || entry.pinned || entry.text.isBlank() || entry.sourceIds.isEmpty() ||
                entry.sourceIds.any { it in blocked }) return@forEachIndexed
            val matching = branches.filter { it.messages.keys.containsAll(entry.sourceIds) }.singleOrNull() ?: return@forEachIndexed
            val roles = entry.sourceIds.map { matching.messages.getValue(it).role.lowercase() }
            if (entry.kind == EntryKind.USER_FACT && roles.any { it != "user" }) return@forEachIndexed
            if (entry.kind == EntryKind.ASSISTANT_SUGGESTION && "assistant" !in roles) return@forEachIndexed
            val key = Key(matching.key, entry.category, entry.kind, entry.continuityCue, entry.text)
            val candidates = groups.getOrPut(key) { mutableListOf() }
            // The same words can recur after a change back. A shared reference is not
            // enough: only consolidate outputs ending at the same source position.
            val lastPosition = entry.sourceIds.maxOf { matching.positions.getValue(it) }
            val overlapping = candidates.filter { prior ->
                entries[prior].sourceIds.maxOf { matching.positions.getValue(it) } == lastPosition
            }
            if (overlapping.isEmpty()) {
                candidates += index
            } else {
                val kept = overlapping.first()
                val allIds = (overlapping.flatMap { entries[it].sourceIds } + entry.sourceIds).toSet()
                entries[kept] = entries[kept].copy(sourceIds = allIds.sortedBy { matching.positions.getValue(it) })
                removed += index
                removed += overlapping.drop(1)
                candidates.removeAll(overlapping.drop(1).toSet())
            }
        }
        return if (removed.isEmpty()) archive else archive.copy(entries = entries.filterIndexed { index, _ -> index !in removed })
    }
}
