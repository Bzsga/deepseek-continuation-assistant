package app.banzhan.deepseekcontinuation.core

/** A local preview only. Selecting or linking sources never dispatches a model request. */
data class IncrementalSelection(
    val messageIds: List<String>,
    val alreadyIncludedCount: Int,
    val notice: String,
    val sourceScope: ArchiveSourceScope,
) {
    val newMessageCount: Int get() = messageIds.size
}

/**
 * Reuses only exact, source-backed coverage. Similar text, a message ID in another
 * conversation/branch, or a request that merely started is not completed coverage.
 */
object IncrementalContinuity {
    /** Earlier references come only from the same already selected, unmodified source range. */
    fun priorSelectedMessages(
        archive: Archive, conversations: List<ImportedConversation>, incoming: ImportedConversation,
        branchId: String, newMessageIds: Set<String>,
    ): List<ChatMessage> {
        if (newMessageIds.isEmpty()) return emptyList()
        val source = conversations.singleOrNull { it.id == incoming.id && it.id in archive.conversationIds }
            ?: return emptyList()
        val savedBranch = source.branches.singleOrNull { it.id == branchId } ?: return emptyList()
        val branch = incoming.branches.singleOrNull { it.id == branchId } ?: return emptyList()
        val firstNew = branch.messages.indexOfFirst { it.id in newMessageIds }
        if (firstNew <= 0) return emptyList()
        val selected = archive.sourceScopes.filter { scope ->
            scope.conversationId == source.id && scope.branchId == branchId && runCatching {
                ContinuityLogic.captureScope(source, branchId, scope.selectedMessageIds.toSet()) == scope
            }.getOrDefault(false)
        }.flatMap { it.selectedMessageIds }.toSet()
        val blocked = archive.entries.filter { it.edited || it.deleted }.flatMap { it.sourceIds }.toSet()
        val savedById = savedBranch.messages.associateBy { it.id }
        return branch.messages.take(firstNew).filter { message ->
            message.id in selected && message.id !in blocked && message.content.isNotBlank() && savedById[message.id] == message
        }.takeLast(4)
    }

    fun select(
        archive: Archive,
        conversations: List<ImportedConversation>,
        incoming: ImportedConversation,
        branchId: String,
        selectedIds: Set<String>,
        completedPrimaryBatches: List<List<ChatMessage>> = emptyList(),
        includeLocalCoverage: Boolean = true,
    ): IncrementalSelection {
        val selection = ContinuityLogic.captureScope(incoming, branchId, selectedIds)
        val branch = incoming.branches.single { it.id == branchId }
        val selected = branch.messages.filter { it.id in selectedIds }
        val stored = conversations.singleOrNull { it.id == incoming.id && it.id in archive.conversationIds }
        val savedBranch = stored?.branches?.singleOrNull { it.id == branchId }
        val origins = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        val coveredOnBranch = linkedSetOf<String>()
        var unverifiableRange = false

        archive.sourceScopes.forEach { scope ->
            val source = conversations.singleOrNull { it.id == scope.conversationId && it.id in archive.conversationIds }
            val sourceBranch = source?.branches?.singleOrNull { it.id == scope.branchId }
            val valid = source != null && sourceBranch != null && runCatching {
                ContinuityLogic.captureScope(source, sourceBranch.id, scope.selectedMessageIds.toSet()) == scope
            }.getOrDefault(false)
            if (!valid) {
                if (scope.conversationId == incoming.id && scope.branchId == branchId) unverifiableRange = true
                return@forEach
            }
            scope.selectedMessageIds.forEach { id ->
                origins.getOrPut(id) { linkedSetOf() } += scope.conversationId to scope.branchId
            }
            if (scope.conversationId == incoming.id && scope.branchId == branchId) {
                coveredOnBranch += scope.selectedMessageIds
            }
        }

        val sourceById = savedBranch?.messages?.associateBy { it.id }.orEmpty()
        val origin = incoming.id to branchId
        val eligible = selected.filter { message ->
            message.id in coveredOnBranch && sourceById[message.id] == message &&
                origins[message.id] == setOf(origin)
        }
        val entriesById = archive.entries.associateBy { it.id }
        val completed = completedCoverage(eligible, completedPrimaryBatches)
        val represented = eligible.filter { message ->
            if (message.id in completed) true else {
                val local = ArchiveLogic.localEntries(listOf(message))
                // A real original excerpt that was edited/deleted must not be sent again
                // under a fresh summary ID. A manually added source-linked note does not
                // establish that the whole underlying message was saved or processed.
                val protectedOriginal = local.any { entry ->
                    entriesById[entry.id]?.let { it.edited || it.deleted } == true
                }
                protectedOriginal || (includeLocalCoverage && local.isNotEmpty() && local.all { it.id in entriesById })
            }
        }.map { it.id }.toSet()
        val newIds = selection.selectedMessageIds.filterNot { it in represented }
        val skipped = selection.selectedMessageIds.size - newIds.size
        val notice = buildString {
            when {
                selection.selectedMessageIds.isEmpty() -> append("尚未选择要补入的消息。")
                newIds.isEmpty() -> append("所选消息已保留，没有新增内容，可继续使用原档案。")
                skipped > 0 -> append("新增 ${newIds.size} 条消息，已跳过 $skipped 条已保留的内容。")
                else -> append("本次补入 ${newIds.size} 条所选消息。")
            }
            if (unverifiableRange || (archive.sourceScopes.isEmpty() && archive.entries.isNotEmpty())) {
                append("旧范围无法完整核实，未凭相似内容自动排除本次选择。")
            }
        }
        return IncrementalSelection(newIds, skipped, notice, selection)
    }

    /** Chunk fragments count only when their combined primary text matches a whole source. */
    private fun completedCoverage(
        eligible: List<ChatMessage>, completedPrimaryBatches: List<List<ChatMessage>>,
    ): Set<String> {
        if (eligible.isEmpty() || completedPrimaryBatches.isEmpty()) return emptySet()
        val originals = eligible.associateBy { it.id }
        val completed = mutableSetOf<String>()
        completedPrimaryBatches.forEach { batch ->
            batch.asSequence().filter { it.id in originals }.groupBy { it.id }.forEach { (id, pieces) ->
                val original = originals.getValue(id)
                if (pieces.all { it.role == original.role && it.timestamp == original.timestamp && it.attachments == original.attachments } &&
                    pieces.sumOf { it.content.length.toLong() } == original.content.length.toLong() &&
                    pieces.joinToString("") { it.content } == original.content) completed += id
            }
        }
        return completed
    }
}
