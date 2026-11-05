package app.banzhan.deepseekcontinuation.core

/** Rebuilds into a separate archive while keeping saved user decisions intact. */
object ReorganizationLogic {
    fun rebuildArchive(
        original: Archive, newId: String, newTitle: String = original.title,
        updatedAt: Long = System.currentTimeMillis(),
    ): Archive {
        require(newId.isNotBlank() && newId != original.id) { "新版档案必须使用新的编号。" }
        require(newTitle.isNotBlank()) { "档案名称不能为空。" }
        return original.copy(id = newId, title = newTitle, updatedAt = updatedAt,
            entries = original.entries.filter(::isProtected), generationMode = "summary", rebuiltFromArchiveId = original.id)
    }

    /** Caller removes unchanged previous task output before submitting cumulative new output. */
    fun mergeRebuiltEntries(existing: List<ArchiveEntry>, incoming: List<ArchiveEntry>): MergeResult {
        val normalized = ArchiveLogic.merge(existing, emptyList())
        val entries = normalized.entries.associateByTo(linkedMapOf()) { it.id }
        val conflicts = normalized.conflicts.toMutableList()
        for (proposed in incoming) {
            val current = entries[proposed.id]
            // An identical saved value already contains the full result. In
            // particular, regenerating it must not clear its tombstone or pin.
            if (current != null && sameContent(current, proposed)) continue
            val guards = entries.values.filter { entry ->
                entry.id == proposed.id || (isProtected(entry) && overlaps(entry, proposed))
            }
            if (guards.isEmpty()) entries[proposed.id] = proposed
            else guards.forEach { conflicts += EntryConflict(it, proposed) }
        }
        return MergeResult(entries.values.toList(), conflicts.distinct())
    }

    /** Persisted conflicts stay as pairs; connected pairs must be reviewed together. */
    fun groupConflicts(conflicts: List<EntryConflict>): List<List<EntryConflict>> {
        val distinct = conflicts.distinct()
        val indexesById = mutableMapOf<String, MutableList<Int>>()
        distinct.forEachIndexed { index, conflict ->
            setOf(conflict.existing.id, conflict.proposed.id).forEach { id ->
                indexesById.getOrPut(id) { mutableListOf() } += index
            }
        }
        val visited = mutableSetOf<Int>()
        return distinct.indices.mapNotNull { start ->
            if (!visited.add(start)) return@mapNotNull null
            val pending = ArrayDeque<Int>()
            val indexes = mutableListOf<Int>()
            pending.add(start)
            while (pending.isNotEmpty()) {
                val index = pending.removeFirst()
                indexes += index
                val conflict = distinct[index]
                for (id in setOf(conflict.existing.id, conflict.proposed.id)) {
                    for (next in indexesById.remove(id).orEmpty()) {
                        if (visited.add(next)) pending.add(next)
                    }
                }
            }
            indexes.sorted().map { distinct[it] }
        }
    }

    /** Returns the updated entries and all still-pending conflicts in one pure operation. */
    fun resolveConflictGroup(
        entries: List<ArchiveEntry>, allConflicts: List<EntryConflict>, selectedGroup: List<EntryConflict>, useProposed: Boolean,
    ): MergeResult {
        require(selectedGroup.isNotEmpty()) { "没有选中的待处理内容。" }
        val selected = selectedGroup.toSet()
        require(groupConflicts(allConflicts).any { it.toSet() == selected }) { "待处理内容已变化，请刷新后查看完整一组。" }
        val remaining = allConflicts.filterNot { it in selected }.distinct()
        if (!useProposed) return MergeResult(entries, remaining)

        require(entries.map { it.id }.distinct().size == entries.size) { "档案条目编号重复，暂不能替换。" }
        val old = uniqueSnapshots(selectedGroup.map { it.existing })
        val proposed = uniqueSnapshots(selectedGroup.map { it.proposed })
        val currentById = entries.associateBy { it.id }
        require(old.all { currentById[it.id] == it }) { "相关条目已被修改，请刷新后重新检查。" }
        val oldIds = old.map { it.id }.toSet()
        // The user may have edited, pinned, or deleted another same-source entry
        // since seeing the proposal. A stale group cannot approve that change.
        val affectedNow = entries.filter { entry ->
            proposed.any { suggestion -> entry.id == suggestion.id || (isProtected(entry) && overlaps(entry, suggestion)) }
        }
        require(affectedNow.all { it.id in oldIds }) { "出现了新的同来源修订或删除，请刷新后查看完整冲突。" }
        val accepted = proposed.map { suggestion ->
            suggestion.copy(edited = true, deleted = false,
                pinned = old.any { it.pinned && (it.id == suggestion.id || overlaps(it, suggestion)) })
        }
        return MergeResult(entries.filterNot { it.id in oldIds } + accepted, remaining)
    }

    private fun uniqueSnapshots(entries: List<ArchiveEntry>): List<ArchiveEntry> = entries.groupBy { it.id }.values.map { group ->
        require(group.distinct().size == 1) { "待处理内容包含不同版本，请刷新后重新检查。" }
        group.first()
    }

    private fun isProtected(entry: ArchiveEntry): Boolean = entry.edited || entry.deleted || entry.pinned || entry.sourceIds.isEmpty()

    private fun overlaps(first: ArchiveEntry, second: ArchiveEntry): Boolean = first.sourceIds.any { it in second.sourceIds }

    private fun sameContent(first: ArchiveEntry, second: ArchiveEntry): Boolean =
        first.category == second.category && first.text == second.text && first.kind == second.kind &&
            first.sourceIds.toSet() == second.sourceIds.toSet() && first.continuityCue == second.continuityCue
}
