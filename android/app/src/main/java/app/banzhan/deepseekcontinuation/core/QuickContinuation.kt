package app.banzhan.deepseekcontinuation.core

/** A disposable, local export. Preparing it never changes an archive or starts organization. */
object QuickContinuation {
    fun fromSelection(
        conversation: ImportedConversation,
        branchId: String,
        selectedIds: Set<String>,
        title: String = conversation.title,
        background: String = "",
    ): Archive {
        require(selectedIds.isNotEmpty()) { "请先选择要继续的消息。" }
        val source = ContinuityLogic.captureScope(conversation, branchId, selectedIds)
        return Archive(
            id = "quick:${conversation.id}:$branchId",
            title = title.ifBlank { conversation.title },
            conversationIds = listOf(conversation.id),
            entries = emptyList(),
            background = background,
            generationMode = "summary",
            continuity = ContinuitySettings(enabled = true),
            sourceScopes = listOf(source),
        )
    }

    fun create(archive: Archive, conversations: List<ImportedConversation>): ExportResult {
        // Raw entries would reproduce the entire transcript. Keep edits, pins and tombstones,
        // and let the existing exact-range renderer choose recent original exchanges instead.
        val entries = if (archive.generationMode == "original") archive.entries.filter {
            it.edited || it.pinned || it.deleted || it.sourceIds.isEmpty()
        } else archive.entries
        val local = archive.copy(entries = entries, generationMode = "summary")
        val result = ContinuityLogic.export(local, conversations, maxCharacters = 12000)
        return result.copy(text = "【这次先带来的内容】\n" +
            "本次未进行新的智能整理，只带已有记忆和所选范围中的近期原话，不代表完整历史。\n\n" + result.text)
    }
}
