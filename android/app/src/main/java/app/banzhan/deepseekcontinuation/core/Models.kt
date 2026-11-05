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
enum class ContinuityCue { PREFERENCE, CORRECTION, SHARED_LANGUAGE, OPEN_THREAD, RESPONSE_STYLE }

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
    val continuityCue: ContinuityCue? = null,
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
    val continuity: ContinuitySettings = ContinuitySettings(),
    val sourceScopes: List<ArchiveSourceScope> = emptyList(),
    // Optional provenance only; deleting the source archive does not invalidate this archive.
    val rebuiltFromArchiveId: String? = null,
)

/** Exact selected branch/range; message bodies remain in the imported source store. */
@Serializable
data class ArchiveSourceScope(
    val conversationId: String,
    val branchId: String,
    val selectedMessageIds: List<String>,
)

@Serializable
data class ContinuitySettings(
    val enabled: Boolean = false,
    val includeRecent: Boolean = true,
    val recentTurns: Int = 3,
    // null follows the small default selection; an empty list selects no key fragments.
    val selectedFragmentIds: List<String>? = null,
    val interactionNotes: String = "",
    val nextMessage: String = "",
    val includeResponseStyle: Boolean = true,
)

data class ContinuityFragment(
    val id: String,
    val conversationId: String,
    val branchId: String,
    val title: String,
    val messages: List<ChatMessage>,
)

data class ContinuityPlan(
    val recentFragments: List<ContinuityFragment>,
    val recommendedFragments: List<ContinuityFragment>,
    val selectedFragmentIds: List<String>,
    val includedFragmentIds: List<String>,
    val notices: List<String>,
    val export: ExportResult,
    // Source-validated style entries available, including when their use is disabled.
    val responseStyleCount: Int = 0,
)

@Serializable
data class SummaryChunk(
    val id: String,
    val messages: List<ChatMessage>,
    // Earlier selected excerpts help resolve references; they are not another primary segment.
    val contextMessages: List<ChatMessage> = emptyList(),
)

data class EntryConflict(val existing: ArchiveEntry, val proposed: ArchiveEntry)
data class MergeResult(val entries: List<ArchiveEntry>, val conflicts: List<EntryConflict>)
data class ExportResult(val text: String, val omittedCount: Int, val pinnedOverflow: Boolean)
