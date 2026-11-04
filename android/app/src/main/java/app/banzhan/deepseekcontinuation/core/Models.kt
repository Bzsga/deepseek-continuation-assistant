package app.banzhan.deepseekcontinuation.core

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String = "",
    val attachments: List<String> = emptyList(),
)

@Serializable
data class ConversationBranch(
    val id: String,
    val label: String,
    val messages: List<ChatMessage>,
    val isCurrent: Boolean = false,
)

@Serializable
data class ImportedConversation(
    val id: String,
    val title: String,
    val createdAt: String = "",
    val updatedAt: String = "",
    val branches: List<ConversationBranch>,
    val currentBranchId: String? = null,
)

data class ImportResult(val conversations: List<ImportedConversation>, val warnings: List<String>)

@Serializable
enum class EntryCategory { BACKGROUND, PEOPLE, DECISIONS, RECENT, QUOTES }

@Serializable
enum class EntryKind { USER_FACT, ASSISTANT_SUGGESTION, UNCONFIRMED }

@Serializable
data class ArchiveEntry(
    val id: String,
    val category: EntryCategory,
    val text: String,
    val sourceIds: List<String> = emptyList(),
    val kind: EntryKind = EntryKind.UNCONFIRMED,
    val pinned: Boolean = false,
    val edited: Boolean = false,
    val deleted: Boolean = false,
)

@Serializable
data class Archive(
    val id: String,
    val title: String,
    val conversationIds: List<String>,
    val entries: List<ArchiveEntry>,
    val updatedAt: Long = System.currentTimeMillis(),
    val background: String = "",
    val generationMode: String = "legacy",
)

@Serializable
data class SummaryChunk(val id: String, val messages: List<ChatMessage>)

data class EntryConflict(val existing: ArchiveEntry, val proposed: ArchiveEntry)
data class MergeResult(val entries: List<ArchiveEntry>, val conflicts: List<EntryConflict>)
data class ExportResult(val text: String, val omittedCount: Int, val pinnedOverflow: Boolean)
