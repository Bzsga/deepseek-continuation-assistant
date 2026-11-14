# Integration contracts

Android namespace `app.banzhan.deepseekcontinuation`. Domain uses kotlinx.serialization. Json settings ignoreUnknownKeys=true, encodeDefaults=true. Epoch milliseconds for app-owned dates, source dates preserved as strings.

## Core domain
```
@Serializable data class ChatMessage(val id:String, val role:String, val content:String, val timestamp:String="", val attachments:List<String> = emptyList())
@Serializable data class ConversationBranch(val id:String, val label:String, val messages:List<ChatMessage>, val isCurrent:Boolean=false)
@Serializable data class ImportedConversation(val id:String, val title:String, val createdAt:String="", val updatedAt:String="", val branches:List<ConversationBranch>, val currentBranchId:String?=null)
data class ImportResult(val conversations:List<ImportedConversation>, val warnings:List<String>)
enum class EntryCategory { BACKGROUND, PEOPLE, DECISIONS, RECENT, QUOTES }
enum class EntryKind { USER_FACT, ASSISTANT_SUGGESTION, UNCONFIRMED }
enum class ContinuityCue { PREFERENCE, CORRECTION, SHARED_LANGUAGE, OPEN_THREAD, RESPONSE_STYLE }
@Serializable data class ArchiveEntry(val id:String, val category:EntryCategory, val text:String, val sourceIds:List<String> = emptyList(), val kind:EntryKind=EntryKind.UNCONFIRMED, val pinned:Boolean=false, val edited:Boolean=false, val deleted:Boolean=false, val continuityCue:ContinuityCue?=null)
@Serializable data class ArchiveSourceScope(val conversationId:String, val branchId:String, val selectedMessageIds:List<String>)
@Serializable data class ContinuitySettings(val enabled:Boolean=false, val includeRecent:Boolean=true, val recentTurns:Int=3, val selectedFragmentIds:List<String>?=null, val interactionNotes:String="", val nextMessage:String="", val includeResponseStyle:Boolean=true)
@Serializable data class Archive(val id:String, val title:String, val conversationIds:List<String>, val entries:List<ArchiveEntry>, val updatedAt:Long=System.currentTimeMillis(), val background:String="", val generationMode:String="legacy", val continuity:ContinuitySettings=ContinuitySettings(), val sourceScopes:List<ArchiveSourceScope> = emptyList(), val rebuiltFromArchiveId:String?=null)
@Serializable data class SummaryChunk(val id:String, val messages:List<ChatMessage>, val contextMessages:List<ChatMessage> = emptyList())
data class EntryConflict(val existing:ArchiveEntry, val proposed:ArchiveEntry)
data class MergeResult(val entries:List<ArchiveEntry>, val conflicts:List<EntryConflict>)
data class ExportResult(val text:String, val omittedCount:Int, val pinnedOverflow:Boolean)
object DeepSeekImporter { fun parse(bytes:ByteArray,fileName:String):ImportResult }
object ArchiveLogic {
 fun chunks(messages:List<ChatMessage>,maxCharacters:Int=6000):List<SummaryChunk>
 fun dialogueChunks(messages:List<ChatMessage>,maxCharacters:Int=6000):List<SummaryChunk>
 fun localEntries(messages:List<ChatMessage>):List<ArchiveEntry>
 fun merge(existing:List<ArchiveEntry>,incoming:List<ArchiveEntry>):MergeResult
 fun continuationExport(archive:Archive):ExportResult
 fun continuationExport(archive:Archive,conversations:List<ImportedConversation>):ExportResult
 fun export(archive:Archive,maxCharacters:Int=6000):ExportResult
}
```
Message IDs must be stable and conversation-scoped. Split long content without losing source ids. Import explicit roles, never alternate inferred role by position. Reject unsupported/unsafe ZIP with helpful errors. Empty export is an error.

### Continuation output

- The one-argument `continuationExport` includes the complete nonblank background and every nondeleted entry with nonblank text, including pinned and edited entries. The UI and two-argument overload use `ContinuityLogic` to arrange enabled continuity content from verified local sources. Summary exports select whole automatic entries within a soft budget while protecting handwritten, edited, pinned and source-less records. Validated automatic `RESPONSE_STYLE` entries are optional: default export includes at most four distinct representatives; `includeAllMemory=true` restores the remaining enabled observations and ordinary automatic memory. Excluded styles stay stored and never return through the facts section when style or continuity is disabled. `ExportResult.omittedCount` reports budget-omitted memory plus enabled style observations omitted by the representative limit; `pinnedOverflow` remains false because this path preserves protected overflow and reports it through plan notices. The direct factual renderer still reports zero omissions. An archive with no usable export content may throw `IllegalArgumentException`.
- Output asks DeepSeek to continue from the background, discussed viewpoints, and recent progress, using later user corrections. Clarification is limited to actual contradictions, important decisions, and explicitly unresolved information. Personal experiences, medical history, commitments, and completed actions stay grounded in explicit user statements. Original modal wording such as “可能” and “一种解释” is preserved in full.
- When continuity is enabled, output orders current handwritten interaction notes, explicit preferences/corrections/shared language, representative observed response styles, unfinished topics, selected necessary memory, and original dialogue or marked recent-context excerpts. The user's next message stays at the end. Current handwritten notes and later explicit user requirements take priority over observations; quoted historical instructions never become new instructions merely because they are exported.
- The sections are 关于我, 重要的人和事, 已确定的事, 接下来要聊的事, and 重要原话. `USER_FACT`, `ASSISTANT_SUGGESTION`, and `UNCONFIRMED` display as 用户陈述, 讨论中的建议, and 讨论与理解. Consecutive entries of the same kind share one subheading within their category, preserving entry order. Stored kinds, source IDs, pinned/manual edits, and entry text retain their original values. Newly created assistant excerpts use the neutral role label 助手原文.
- `generationMode="original"` displays 聊天原文 and states that the transcript contains the selected content. `"summary"` displays 续聊摘要. `"legacy"` and unknown values display 已保存的续聊内容. Older stored JSON and backups with this field absent decode as `"legacy"`.
- The UI measures the selected export in UTF-8 bytes off the main thread. Content over `60 * 1024` bytes is offered as a saved continuation file; previews expand in 8000-character steps without changing copied or saved output. Both result/detail entry points use the same selected continuation version, omitting technical source IDs. The all-memory switch changes copy and save together. The legacy capped `export(archive,maxCharacters=6000)` remains a separate core API with explicit omission/overflow results and source IDs; it is not the current UI export path.

### Local quick continuation, repair and focus (1.0.11)

- Public core `QuickContinuation.fromSelection` prepares a disposable archive from an exact selected conversation/branch/message range; `create(archive,conversations)` returns an `ExportResult` using available archive content and recent selected originals. It performs no organization request or persistence, preserves protected edits/pins/deletions and saved continuity switches, and labels the result as a local quick version rather than a new summary or complete history.
- Public core `ContinuityRepair.create(archive,conversations,issue,clarification="",observedReply="")` returns a bounded `ExportResult` for correcting the current new chat. The four issues cover missing background, repeated discussion, misunderstanding and unfamiliar style. Current clarification is separate from quoted history and pasted replies; source validation and saved style switches still apply. This API does not modify the archive, submit a model request, upload drafts or send the result to another app.
- Internal `ContinuityFocus` supplies bounded lexical relevance through `fromText`, `forArchive` and `Query.score`. Archive focus uses current written text and, when verifiable, recent originals from one exact selected branch; ambiguous or protected sources do not widen its input. Scores only rank material for export/repair, never establish factual support, semantic equivalence or user preferences. Callers keep existing protection and fallback ordering.

## Android state and controller
Room stores raw conversations, archives, revisions, and persisted per-chunk summaries/tasks. UI sees StateFlow in a `AppController(application:Application):AndroidViewModel`.
```
data class AppState(
 val conversations:List<ImportedConversation> = emptyList(),
 val archives:List<Archive> = emptyList(),
 val settings:ServiceSettings = ServiceSettings(),
 val tasks:List<OrganizationTask> = emptyList(),
 val busy:Boolean=false, val notice:String?=null,
 val lastImportedConversationIds:List<String> = emptyList(),
 val quota:CompanionQuota?=null, val quotaLoading:Boolean=false, val quotaError:String?=null,
 val account:AccountState=AccountState(),
 val developerPrefs:DeveloperPrefs=DeveloperPrefs(),
 val adminConfig:AdminConfig?=null, val adminLoading:Boolean=false, val adminError:String?=null,
 val adminStats:AdminStats?=null, val adminStatsLoading:Boolean=false, val adminStatsError:String?=null,
 val adminUsers:AdminUsersPage?=null, val adminUsersLoading:Boolean=false, val adminUsersError:String?=null,
 val adminRegistrationInvites:List<AdminRegistrationInvite>?=null,
 val adminRegistrationLoading:Boolean=false, val adminRegistrationError:String?=null)
@Serializable data class AccountUser(val id:String,val username:String,val registrationKind:String,val createdAt:String)
data class AccountState(val currentUser:AccountUser?=null,val loading:Boolean=false,val error:String?=null)
@Serializable data class AccountSession(val serviceUrl:String,val token:String,val expiresAt:String,val user:AccountUser)
data class OrganizationEstimate(val chunks:Int,val inputTokens:Int,val outputTokens:Int,val estimatedCost:Double?)
@Serializable data class ServiceSettings(
 val mode:String="companion", val baseUrl:String="https://api.deepseek.com", val model:String="deepseek-flash",
 val apiKey:String="", val companionUrl:String="https://digimonlegend.com/continuation-api", val trialToken:String="",
 val inputPricePerMillion:Double?=null, val outputPricePerMillion:Double?=null,
 val contextTokens:Int=8192, val maxOutputTokens:Int=1024)
@Serializable data class OrganizationTask(val id:String,val archiveId:String,val label:String,val status:String,val done:Int,val total:Int,val message:String="",val provider:String="",val uncertain:Boolean=false,val serviceMode:String="byok")
data class CompanionQuota(
 val enabled:Boolean,val remainingCny:Double?,val perJobLimitCny:Double?,val model:String,val message:String,
 val dailyLimit:Int?=null,val dailyUsed:Int?=null,val dailyRemaining:Int?=null,
 val userDailyLimit:Int?=null,val userDailyUsed:Int?=null,val userDailyRemaining:Int?=null,
 val resetAt:String?=null,val accessMode:String="invitation",val contextTokens:Int?=null,
 val maxOutputTokens:Int?=null,val dailyReserved:Int?=null,val userDailyReserved:Int?=null,
 val organizationProtocolVersion:Int?=null,val politicalFilterSupported:Boolean=false,
 val automaticFallbackEnabled:Boolean=false,val maxAttemptsPerChunk:Int=1)
@Serializable data class DeveloperPrefs(val serviceUrl:String="https://digimonlegend.com/continuation-api",val sessionToken:String="",val expiresAt:String="")
data class AdminConfig(
 val publicEnabled:Boolean,val dailyLimit:Int,val userDailyLimit:Int,val model:String,val baseUrl:String,
 val apiKeyConfigured:Boolean,val contextTokens:Int,val maxOutputTokens:Int,val providerTimeoutSeconds:Double,
 val apiEnabled:Boolean,val mockMode:Boolean,val costKnown:Boolean,val configVersion:Int,
 val publicRegistrationLimit:Int=20,val publicRegisteredCount:Int=0)
data class AdminRegistrationInvite(val id:String,val code:String?=null,val expiresAt:String,
 val createdAt:String="",val usedAt:String?=null,val usedBy:String?=null)
class AppController(application:Application) : AndroidViewModel(application) {
 val state:StateFlow<AppState>
 suspend fun importFile(bytes:ByteArray,fileName:String):List<String>
 suspend fun loadDemo()
 fun clearNotice()
 suspend fun saveSettings(settings:ServiceSettings)
 suspend fun testApiConnection(draft:ServiceSettings):ConnectionTestResult
 suspend fun redeemInvite(code:String)
 suspend fun registerAccount(username:String,password:String,invitationCode:String?=null)
 suspend fun loginAccount(username:String,password:String)
 suspend fun logoutAccount()
 suspend fun refreshAccount()
 suspend fun refreshQuota()
 suspend fun checkOrganizationAvailability():String?
 fun estimateForCurrentService(messages:List<ChatMessage>):OrganizationEstimate
 suspend fun saveDeveloperServiceUrl(serviceUrl:String)
 suspend fun loginDeveloper(serviceUrl:String,password:String)
 suspend fun logoutDeveloper()
 suspend fun refreshAdminConfig()
 suspend fun updateAdminConfig(config:AdminConfig,upstreamApiKey:String?=null)
 suspend fun refreshAdminStats(days:Int=7)
 suspend fun refreshAdminUsers(limit:Int=50,offset:Int=0)
 suspend fun updateAdminUserQuota(userId:String,dailyLimit:Int?,todayRemaining:Int?=null,day:String?=null)
 suspend fun refreshAdminRegistrationInvites()
 suspend fun createAdminRegistrationInvites(count:Int,validDays:Int)
 fun updateFeedbackDraft(content:String,contactType:String,contact:String)
 fun removeFeedbackImage(id:String)
 fun resetFeedbackDraft()
 suspend fun addFeedbackImage(bytes:ByteArray)
 suspend fun submitFeedback():FeedbackRecord
 suspend fun refreshMyFeedback(limit:Int=50,offset:Int=0)
 suspend fun refreshAdminFeedback(status:String?=null,limit:Int=50,offset:Int=0)
 suspend fun loadAdminFeedback(id:String)
 suspend fun processAdminFeedback(id:String,status:String,adminNote:String,expectedRevision:Int)
 suspend fun loadFeedbackImage(feedbackId:String,imageId:String,admin:Boolean=false):ByteArray
 suspend fun createArchive(title:String,conversation:ImportedConversation,branchId:String,selectedIds:Set<String>,background:String,includeRawEntries:Boolean=true):String
 suspend fun incrementalSelection(archiveId:String,conversationId:String,branchId:String,selectedIds:Set<String>):IncrementalSelection
 suspend fun addConversation(archiveId:String,conversation:ImportedConversation,branchId:String,selectedIds:Set<String>,includeRawEntries:Boolean=true):IncrementalSelection
 suspend fun saveEntry(archiveId:String,entry:ArchiveEntry)
 suspend fun saveBackground(archiveId:String,background:String)
 suspend fun saveContinuitySettings(archiveId:String,settings:ContinuitySettings)
 suspend fun deleteArchive(archiveId:String)
 suspend fun organize(archiveId:String,conversationId:String,branchId:String,selectedIds:Set<String>)
 suspend fun prepareReorganization(archiveId:String,confirmedScopes:List<ArchiveSourceScope>?=null):ReorganizationPlan
 suspend fun reorganizeArchive(plan:ReorganizationPlan):ReorganizationStart
 suspend fun resumeTask(taskId:String,acknowledgeCost:Boolean)
 suspend fun resumeTask(taskId:String)
 suspend fun cancelTask(taskId:String)
 suspend fun pauseTask(taskId:String)
 suspend fun clearLocalData()
 suspend fun revisions(archiveId:String):List<Archive>
 suspend fun restore(archiveId:String,revision:Archive)
 suspend fun conflicts(archiveId:String):List<EntryConflict>
 suspend fun resolveConflict(archiveId:String,conflict:EntryConflict,useProposed:Boolean,observedGroup:List<EntryConflict>?=null)
 suspend fun backupFile():File
 suspend fun restoreBackup(input:InputStream)
 suspend fun restoreBackup(json:String)
}
fun estimateOrganization(settings:ServiceSettings,messages:List<ChatMessage>):OrganizationEstimate
```
Avoid long work on main thread. UI uses viewModelScope or remembered coroutine launch. Data operations switch to IO. Organization launches a controller-owned job and returns, and chunk successes persist immediately. Tasks interrupted by process death appear paused. API settings changes affect only new tasks. Retries use captured original provider settings, with secrets obtained from secure storage. User explicitly authorizes retry of ambiguous tasks. BYOK supports HTTPS only, and configurable context budget reserves output and prompt. Cancel must prevent new calls and preserve already-completed chunks.

### External file entry and picker

- The preferred entry is browser download followed by sharing the ZIP to this app. Home uses the blue primary button 查看下载与分享教程, and 手动选择 ZIP is secondary. The official DeepSeek web entry remains prominent in a six-step near-fullscreen guide: web menu → ··· → system settings → data-management download → ZIP share → choose this app. It uses six focused tutorial images, with native large text, a short instruction and focused image on each step. Images support full-image zoom; fixed Previous/Next controls sit directly below the progress bar (the last step uses 我知道了 to dismiss). Foundation `HorizontalPager` uses `PagerState` as the sole page index for native backward/forward swipes. First/last pages do not wrap, and swiping beyond the last page does not dismiss; only 我知道了 or explicit close ends the guide. Button targets are clamped and page controls are briefly disabled during scrolling/animation to prevent rapid-tap races. Each page retains vertical scrolling and image-tap zoom in the compact large-text layout; the dialog draws edge to edge with safe-drawing padding and at least 48dp page-button height. 我已下载，直接看分享 jumps to step 5, and manual ZIP selection remains available as fallback. The offline share demo is a short muted raw-resource clip opened only after a tap. `TutorialVideoDialog` uses `MediaPlayer` on `TextureView`, pauses on lifecycle ON_PAUSE and releases both player and surface on dismissal. It ends without looping and provides replay; the account page, unrelated download-list content and original metadata are removed from the recording-derived assets. Files need no extraction. File-manager share/open remains an alternative; none of these navigation actions initiates AI.
- `MainActivity` accepts one exported ZIP/JSON document through `ACTION_SEND` or `ACTION_VIEW`. SEND reads only `EXTRA_STREAM` (or one ClipData URI); VIEW reads data URI. The entry accepts `content:` URI grants and the supported ZIP/JSON MIME types, including octet-stream fallback, then parses the actual bytes. It does not accept arbitrary file/network paths, text instructions or nested intents and adds no broad storage permission. Sender extras are removed from the retained Activity intent before creating the saved-state ViewModel.
- `IncomingImportViewModel` owns a bounded FIFO of receipts and its local import coroutine; the Compose screen only observes the current pending item and requests confirm/consume. `PendingIncomingImport` exposes id, safe display name, status (`AWAITING_CONFIRMATION|IMPORTING|COMPLETED|FAILED`), transient imported conversation IDs and safe error text. Reading is on IO and independently enforces 32 MiB even if provider metadata understates the size, then calls the normal `AppController.importFile` transaction and parser.
- When the home route is idle with no active queued/running organization task or blocking modal, an awaiting receipt starts local import. Else it remains queued with explicit continue/cancel actions and does not discard the current edit. Successful import routes only to chat selection or, for a single chat, preparation; it never starts an API request. Each genuine warm `onNewIntent` is a new receipt even for an identical URI, allowing intentional reimport of an updated document. Rotation retains the ViewModel/coroutine. Saved-state restoration drops completed receipts and marks an interrupted importing receipt as failed with a check-saved-data/re-share message; it does not silently rerun completed import or assume a temporary grant remains valid.
- `ImportDocumentContract` retains the system OpenDocument flow and adds `DocumentsContract.EXTRA_INITIAL_URI` pointing to the Downloads root. This is a provider hint: OEM pickers may ignore it, and Recent/manual directory navigation remains available. Home/help text says no extraction is needed and directs users to the six-step download/share guide above. Build and test instructions are in [building.md](building.md); these contracts do not claim device coverage.

### Connection testing and feedback state

- `AppState` additionally publishes independent connection test loading/result/error fields; `ConnectionTestResult(reply,model?)` contains bounded, secret-redacted provider text. `testApiConnection` normalizes the current unsaved draft as BYOK, requires that address's key, and sends only `Reply with OK.` with `max_tokens=32`, `stream=false`. It does not save settings, create organization jobs, send transcripts, or retry automatically. The UI discloses possible provider cost before the explicit button.
- Feedback state includes an in-memory `FeedbackDraft`, submission/image-loading/error/uncertain fields, last submitted record, owner page and admin list/detail loading/error state. Draft contact/content/image bytes never enter repr/logs or saved Bundle state. Images are processed on IO: rotate as needed, resize to a maximum 1280-pixel edge, re-encode JPEG quality 80 without source metadata, and enforce 1 MiB per image, at most three.
- Submission uses the service-scoped anonymous identity even when the user's organization service is BYOK. An ambiguous submission freezes the original payload, idempotency key and identity for an explicit retry; editing is locked until its state is resolved. Pagination, details and images use the corresponding owner/admin credential. Administration sends `expectedRevision`, and request sequence guards prevent stale responses from replacing newer state. Secrets are excluded from backups. Full HTTP payloads and worker scope are authoritative in [feedback.md](feedback.md).

### Import, preparation, and task state

- `importFile` returns the distinct IDs written by that successful import, including IDs merged with existing conversations. Raw source bytes and imported conversations are saved in a transaction. After refreshing the library, the controller publishes those same IDs in `lastImportedConversationIds`. The UI uses this import result to choose the next conversation. The full library remains available separately through `conversations`.
- `lastImportedConversationIds` is transient controller state. It starts empty, changes after a successful import, and is cleared after local-data deletion or backup restoration. A failed import retains the previous successful import IDs, so callers advance the workflow only after `importFile` returns successfully. `loadDemo` uses the same import path.
- `createArchive` reads the stored conversation, checks the requested branch and message IDs, and returns the new archive ID. It requires a nonblank title and either selected nonblank message content or a nonblank background. Background length is limited to 100000 characters. An empty selection selects zero messages.
- With `includeRawEntries=true`, creation stores faithful local excerpts and sets `generationMode="original"`. With `false`, creation starts with an empty entry list and sets `generationMode="summary"`, while preserving the supplied background and source conversation reference. The UI invokes `organize` with its chosen branch/message IDs when the user starts intelligent organization.
- From 1.0.16, `ConversationPreparation.prefersOriginal` defaults preparation to complete selected original dialogue when the selection has 1–30 messages, at least one nonblank body, and at most 6000 total `content.length` characters, including whitespace. It does not trim or excerpt the selection. The original path is local and requires no login or model request; users may explicitly choose intelligent organization instead. Longer selections retain the organization flow. Login returns to the saved preparation choice without automatically sending chat content.
- `incrementalSelection` previews exact source-backed coverage locally and returns `IncrementalSelection` with the remaining message IDs, covered count, source scope and notice. `addConversation` rechecks that selection transactionally and returns the same result type; its `includeRawEntries` defaults to `true` and controls whether new raw entries are merged. With `false`, it only links selected sources for subsequent explicit organization. Neither method dispatches a model request, and an empty increment leaves the archive unchanged. Matching requires verifiable conversation, branch and message content; a saved scope or merely started request does not prove completed coverage. Existing edits, deletions, pins and unresolved request identities remain protected.
- Generation mode records the content origin. Progress and completion come from `OrganizationTask.status`, `done`, and `total`. A summary archive may still be awaiting work or contain partial saved results. Adding raw excerpts to a summary archive changes its mode to `"legacy"`. Saving nonempty organization output into an original archive also changes its mode to `"legacy"` because the saved content then combines original excerpts and summaries.
- `checkOrganizationAvailability` returns `null` when preflight passes, or a user-facing blocker string. BYOK performs local configuration and credential-format checks without requiring an account. New free companion work requires an active registered account session; an anonymous diagnostic/feedback identity cannot satisfy this check. Public access still requires an enabled service and positive global/personal remaining counts, and companion preparation validates the server model, context, and output budgets. Retained monetary invitation access preserves its money checks. Validation/network failures become blocker text, and coroutine cancellation propagates.
- `refreshQuota` publishes independent `quotaLoading` and `quotaError` state, clearing the previous quota while refreshing. Responses are accepted only for the same current service and account identity. `refreshAccount` reads the registered profile and quota even when the chosen organization mode is BYOK. Anonymous diagnostic/feedback credentials remain separate from `AccountSession`; automatic anonymous acquisition never signs the user in. Ordinary quota cards display only personal counts, while the HTTP response retains global fields for admission and compatibility.
- `registerAccount` and `loginAccount` publish `AccountState`, save only an encrypted `AccountSession`, and throw on failure. Password input is transient, never saved in Bundle/backup state, and is cleared on submission and login/register switching. Registration has an optional collapsed invitation field. Settings has one account entry; BYOK and local exports remain available without login. Account login does not synchronize archives.
- New companion `TaskRecord` snapshots retain optional `ownerUserId` separately from frozen `ServiceSettings` and cache identity. Logout clears the current account and pauses its local dispatch, preserving task secrets, completed results and ownership. Explicit resume requires the same stable user and service, then rebinds that task's secret to the new session token. Another account cannot resume it. Legacy admitted/unknown requests may use their original credentials solely to recover the original identity; missing legacy credentials never fall back to the current account. Original request IDs and idempotency keys remain unchanged.
- `estimateForCurrentService` uses the latest quota model and token budgets for companion mode. `maxOutputTokens` is captured in task settings and participates in chunk sizing, output allowance, estimates, and cache identity. Android validates an output budget of 256–8192 tokens below the context budget. Before submitting each new companion chunk, the client verifies the current model/context/output budgets still match the task snapshot. A mismatch pauses further work with completed results retained. `OrganizationTask.serviceMode` is the captured settings mode (`"companion"` or `"byok"`), with `"byok"` as its compatibility default.
- Preflight is a point-in-time result. Reservations occur during companion job submission. The ordinary UI checks availability before creating a summary archive, and the advanced confirmation path checks before organization. `organize` validates configuration, selection, and task slot before persisting and launching a task. Successful BYOK local validation leaves provider authentication and balance unverified.
- `busy` reflects active wrapped controller operations. Quota and administration have their own loading/error fields. Background organization progress is observed through `tasks`. The one-argument `resumeTask` delegates with `acknowledgeCost=false`. Ambiguous or expired work requires the explicit acknowledged path.
- Developer login is stored as encrypted `DeveloperPrefs`, separate from BYOK and companion credentials. Changing the developer service address or logging out clears its session and loaded administration state. Expiry or HTTP 401 invalidates the local admin session. Config updates use PATCH followed by GET before publishing confirmed configuration. Stats and user-list refreshes each replace their own result/error state.

## Reorganizing saved archives (1.0.6)

- `prepareReorganization` resolves saved selection scopes, or a uniquely verifiable historical task range when old scope metadata is absent. It may query service quota but never submits a model request. `ReorganizationPlan` carries scope/count descriptions, protected-entry count, estimate, blocking reason, range origin, and any existing target/task IDs. The UI shows one explicit send/cost confirmation and passes the returned plan unchanged to `reorganizeArchive`.
- Missing or ambiguous ranges require explicit selection limited to the original archive's conversations. The selection screen returns `confirmedScopes` to `prepareReorganization` for the same original archive; it does not call ordinary archive creation or append APIs. Missing messages and branches do not silently widen the scope.
- `reorganizeArchive` revalidates original content, sources and service settings, then creates a new archive and task atomically. Its `ReorganizationStart` contains `targetArchiveId`, `taskId` and `reusedExisting`. A plan's internal request identity makes repeated submission return the same target/task, including after completion; pending work is reused before new model dispatch. New reorganization tasks have a separate cache identity, while resuming the same task retains completed chunks and its original request identities.
- `ReorganizationLogic.rebuildArchive` copies background, continuity settings and entries that are edited, deleted, pinned or have no source IDs; ordinary old summaries are not seeded into the new result. New suggestions overlapping protected sources become stored conflicts, including when their IDs differ. The original archive is not modified.
- `ReorganizationLogic.groupConflicts` groups pairs connected by either existing or proposed entry ID. UI shows all affected old/new content and deletion markers, then submits the complete visible `observedGroup` for one keep/replace decision. Resolution checks the complete group, current snapshots and newly overlapping protected entries before one transaction; stale groups require refresh. Keeping current content preserves deletion state; adopting a group is an explicit replacement, never an automatic default.
- `Archive.rebuiltFromArchiveId` and the task's `reorganizationOfArchiveId` retain provenance. The archive link is optional: deleting the original does not invalidate the new archive. UI prioritizes unfinished work, permits a fresh check after a completed cancellation, and provides links between versions. Completion with only protected/deleted content and pending suggestions is still completion, not evidence that the model extracted nothing.

User-facing behavior is described in [conversation-continuity.md](conversation-continuity.md#重新整理已有档案).

## Application update channel

- The current Android source is 1.0.16 / versionCode 25. Clients with the updater can check for a newer same-signature build; older clients without it need a manual upgrade. Lifecycle ON_START (launch or return to foreground) checks `https://digimonlegend.com/continuation-updates/latest.json` quietly. `checkOnStart` uses elapsed time to throttle automatic checks to at most once per five minutes; this is a foreground-entry limit, not a periodic background timer. About exposes a manual check. A newer version is offered through a nonmodal notice that users can postpone. A check only reads the static manifest and never starts a model call or consumes organization request quota.
- Download begins only after the user's explicit action. The downloaded APK stays in the application-private cache and is accepted for installation only when its actual package name, newer version, byte size, SHA-256 and signer match the manifest/current application as required. Bad, truncated or mismatched files cannot be handed to the installer. APK verification does not replace Android's own package/signature checks.
- Installation must not start while AI organization or saving work is busy. The application requests Android's per-app unknown-source install permission where required and hands the verified APK to the system installer; the user still confirms installation in Android. There is no silent installation or automatic model retry tied to updating.
- The original manifest and immutable versioned APKs are hosted separately from the organization API. Publishing validates the release, uploads the immutable APK before atomically switching latest.json, and leaves API runtime parameters and accounting unchanged. Forks must configure their own hosting, package identity, signing and trusted update URLs.
- Full manifest schema, installation/update limits and publisher invocation are owned by [app-updates.md](app-updates.md) and `tools/publish_release.py`; no flags or payload field names are duplicated here. Build instructions are in [building.md](building.md); actual release validation is recorded in the corresponding Release notes.

## Companion HTTP (backend + Android agree)
Account registration/login, legacy anonymous sessions, monetary invitation redemption, and admin login establish separate credentials. Quota and job endpoints require `Authorization: Bearer <companion token>`; new public free jobs require an active registered account session. Administration uses its own bearer session. Account endpoints do not upload or synchronize archives.

- POST `/v1/accounts/register` accepts `{ "username":"...", "password":"...", "invitation_code":"..." }`; `invitation_code` is optional or null. POST `/v1/accounts/login` accepts only username/password. Both return `{ "token":"...", "expires_at":"...", "user":{...}, "quota":{...} }`, with a 30-day session. User fields are `id`, `username`, `registration_kind` (`public|invitation`), and `created_at`.
- Usernames normalize with NFKC and trim outer whitespace, contain 3–32 Unicode letters/digits or `_`/`-`, and compare case-insensitively for uniqueness. Passwords are 8–128 characters and are never trimmed. Registration atomically checks `public_registration_limit` and increments `public_registered_count`; a valid one-use registration invitation bypasses the cap and does not increment that counter. Accounts without a personal override receive the current `user_daily_limit`, initially 5 in source defaults, including after later default changes.
- GET `/v1/accounts/me` returns `{ "user":{...}, "quota":{...} }`. POST `/v1/accounts/logout` returns `{}` and revokes only the supplied account session. A later login of the same account retains its stable owner ID and can recover that owner's admitted work using the original job/idempotency identity. Duplicate usernames return 409, exhausted public capacity 403, invalid/used/expired registration invitations 400, and invalid login 401. Login and registration also enforce source/username rate limits with 429. Successful account responses use `Cache-Control: no-store`.
- POST `/v1/sessions` accepts an empty request or `{}` and returns `{ "token":"...", "quota":{...} }` for an anonymous compatibility identity. It supports diagnostics/feedback and existing admitted work, not new free model jobs.
- POST `/v1/invitations/redeem` accepts `{ "code":"..." }` and returns the token/quota envelope for the retained monetary invitation flow. It is separate from the one-use `invitation_code` supplied during account registration.
- GET `/v1/quota` returns every field below. JSON names use snake_case, and the Android quota model maps the corresponding camelCase fields.

```
Quota {
 enabled: boolean, access_mode: "public" | "invitation", model: string, message: string,
 mock_mode: boolean, cost_known: boolean,
 remaining_cny: number, per_job_limit_cny: number,
 context_tokens: integer, max_output_tokens: integer,
 organization_protocol_version: integer, political_filter_supported: boolean,
 automatic_fallback_enabled?: boolean, max_attempts_per_chunk?: integer,
 daily_limit: integer, daily_used: integer, daily_reserved: integer, daily_remaining: integer,
 user_daily_limit: integer, user_daily_used: integer, user_daily_reserved: integer,
 user_daily_remaining: integer, reset_at: ISO-8601 timestamp
}
```

- Public mode uses daily counts and reports both money fields as zero. `enabled` describes service readiness. Remaining-count fields determine whether another request fits. Default limits are 50 dispatches across the service and 5 per anonymous user each day, configurable by administration. The server uses `Asia/Shanghai`, and `reset_at` is the next Beijing midnight.
- Optional `automatic_fallback_enabled` and `max_attempts_per_chunk` map to the Android quota fields above. Missing or invalid values default to `false` and `1`; only an explicit boolean `true` enables the capability, and the client accepts an integer attempt bound of 1–3. These fields describe the companion service's configured behavior; BYOK does not acquire its credentials or routes.
- Each remote job covers one organization chunk or one explicitly requested political-filter batch. Enqueue reserves one count atomically against both limits. Dispatch moves it to `used` on that Beijing calendar date. All outstanding reservations reduce availability, including reservations across midnight. Queued cancellation or rejection before dispatch releases its reservation. Dispatched failed/uncertain/cancelled work retains its used count. A repeated idempotency key for identical content reuses the job. A multi-chunk archive can consume multiple counts.
- POST `/v1/jobs` body `{ "idempotency_key":"...", "messages":[{"id":"...","role":"user","content":"..."}] }` => job. Optional `operation` defaults to `organize`; `political_filter` is a separate opt-in operation. Optional `context_messages` contains earlier references for organization only. See the operation contracts below.
- New public free submissions require registered-account authentication. Lookup of an existing same-owner idempotency key occurs before new admission, so historical anonymous admitted requests remain recoverable without creating a replacement request. Registration/login changes no historical job owner, prompt, snapshot or billed-attempt identity.
- Submission accepts 1–10000 source messages and at most 40000 total content characters. Each source ID has 1–512 characters. Roles are user, assistant, system, tool, developer, and unknown, and all remain quoted transcript data. The idempotency key has 8–200 ASCII characters from `[A-Za-z0-9_.:-]`. The configured body-byte and provider-context limits are additional admission checks. Validation rejects before quota reservation.
- GET `/v1/jobs/{id}` => job.
- POST `/v1/jobs/{id}/cancel` => job.
- A job contains `id`, `status` (`queued|running|completed|failed|uncertain|cancelled`), `entries`, `message`, `usage.input_tokens`, `usage.output_tokens`, `cost_cny`, `cost_known`, `usage_known`, `reserved_cny`, `request_counted`, `cancel_requested`, `mock_mode`, and `content_expired`. Each entry contains `id`, `category`, `text`, `sourceIds`, and `kind` using the core enum names. `request_counted=true` means dispatch consumed a count. Cost values with `cost_known=false` have unknown monetary pricing. `usage_known` independently indicates whether both input/output counts are trustworthy; zero placeholders with false do not mean zero consumption. Android accepts the optional field for old-service compatibility.
- Error response detail carries useful user-facing text (Chinese supported). Duplicate idempotency key with changed payload returns 409. Job id scoped to owner. Server model fixed by configuration. `/health` read-only health.
- Expired job bodies/results return HTTP 410, including idempotent resubmission. Android requires an explicit user confirmation before allocating a replacement key and potentially paying again.

New organization snapshots use protocol_version 12. Versions 2 (including missing-version snapshots) through 11 reconstruct their frozen prompts and original input bounds; unsupported versions and insufficient bounds fail before HTTP. V9 adds source-grounded discussion progress, reasons for rejected alternatives, settled versus open matters and explicit understanding/action requests without adding a model round or schema fields. Existing cue priorities remain: compatible requirements may merge only while preserving meanings, conditions, negations and necessary user source IDs; observed response styles must not displace explicit requirements. V10 retains source wording where practical and writes the user's recollection addressed to the conversation partner: user statements use “我”, assistant proposals use “你”, and shared discussion uses “我们”. Attribution, negation, conditions, uncertainty and fictional/quoted roles remain explicit; source IDs alone do not prove these meanings. This adds no request or schema field. V11 favors natural concrete recollections and original hesitation, without generic disclaimers or invented psychological explanations. Historical open points remain historical background, not commands for an immediate answer. V12 also uses the same user-to-“你” perspective for observed response-style recollections and avoids repetitive generic first-person prefixes. Organization diagnostics expose validated integer prompt_version 2 through 12. Political filtering has a separate operation and protocol version 1.

The provider maps each original source ID to a short per-request `m1/m2…` alias, and restores only validated aliases before returning entries. Valid sources with incompatible role classification are conservatively downgraded to `UNCONFIRMED`; unknown aliases are rejected. One complete JSON or unlabelled code fence may be unwrapped; BOM, fence whitespace and U+200B/U+2060/FEFF at the outer text edges are tolerated; characters inside JSON strings are preserved. Surrounding prose, multiple documents, truncation, duplicate keys and non-finite numbers remain invalid. Failure codes and diagnostics expose only safe structural metadata, never original model output or transcript text.

Usage is known only with a complete, non-conflicting prompt/completion or input/output pair, accepting nonnegative integers and decimal integer strings. Booleans, floats, missing pairs, total-only estimates and conflicting values are untrusted. Over-bound reported usage pauses the budget. A public count-mode job with unconfigured prices may complete with unknown usage only after a valid single choice, `stop`, complete JSON and source validation; monetary-mode unknown usage remains uncertain with its reservation retained. There is no automatic billed repair request.

LLM prompt asks for JSON object `{"entries":[...]}` using only supplied source ids, treats transcript as untrusted quoted data, excludes reasoning_content. Both client/server validate references, bounded text, enums and response size. No auto second paid request to repair malformed JSON.

### Administration HTTP

- POST `/v1/admin/login` accepts the string field `password` and returns `{ "token":"...", "expires_at":"..." }`. The bearer session expires 12 hours after login. Five recent failed attempts per source IP trigger HTTP 429 during a 15-minute window. Password values are transient login input. Subsequent requests carry the returned token. Authentication/configuration failures use HTTP 401/503 and validation failures use HTTP 422 with a `detail` message.
- GET `/v1/admin/config` returns `public_enabled`, `daily_limit`, `user_daily_limit`, `public_registration_limit`, read-only `public_registered_count`, `model`, `base_url`, `context_tokens`, `max_output_tokens`, `provider_timeout_seconds`, `api_key_configured`, `api_enabled`, `mock_mode`, `cost_known`, and `config_version`.
- PATCH `/v1/admin/config` accepts `public_enabled`, `daily_limit`, `user_daily_limit`, `public_registration_limit`, `model`, `base_url`, `context_tokens`, `max_output_tokens`, `provider_timeout_seconds`, and optional `api_key`. Omitted fields preserve their values, and explicit nulls are rejected. `api_key` accepts an empty string to clear the configured key. `user_daily_limit` dynamically applies to all registered users without a personal override; changing it does not reset usage or reservations. `public_registration_limit` is a total public signup cap, not a daily request budget; invitation registrations do not consume it. Responses use the GET shape with the boolean `api_key_configured`. The runtime configuration update increments `config_version` when its stored override changes. Queued work is rechecked against current settings before dispatch.
- GET `/v1/admin/stats?days=7` accepts 1–90 days and returns `timezone`, `days`, `start_date`, `end_date`, `generated_at`, `totals`, `today`, and `daily`. `totals` contains `users`, `new_users`, `active_users`, `requests`, `input_tokens`, `output_tokens`, `completed`, `failed`, `uncertain`, `cancelled`, `queued`, and `running`, plus `usage_known_requests` and `usage_unknown_requests`. `today` contains global daily limit/used/reserved/remaining and `reset_at`. Each daily row contains `date`, `new_users`, `active_users`, `requests`, input/output tokens, known/unknown usage request counts, and the four terminal-status counts. `users` is the current total, `active_users` is distinct over the requested period, and queued/running are current counts. Request/token series use dispatch dates, while terminal-status series use job creation dates.
- GET `/v1/admin/users?limit=50&offset=0` accepts limit 1–200 and offset ≥0. It lists only registered accounts, returning `limit`, `offset`, `total`, server `day`/`reset_at`, and `users` ordered by registration time descending and ID. Each user has `id`, `username`, `registration_kind`, nullable `daily_limit_override`, `access_mode`, `created_at`, nullable `last_seen_at`, effective personal `daily_limit`, `daily_used`, `daily_reserved`, `daily_remaining`, `total_requests`, `completed`, `failed`, `uncertain`, `cancelled`, `input_tokens`, `output_tokens`, `usage_known_requests`, and `usage_unknown_requests`.
- PATCH `/v1/admin/users/{id}/quota` accepts optional `daily_limit` and `today_remaining`, each 0–1000000. Explicit `daily_limit:null` clears the permanent personal override and follows the current default. Omitted fields preserve their values. `today_remaining` cannot be null; it sets that day's effective total to already-used + reserved + requested remaining, without rewriting dispatch records. Its override takes priority until the next Beijing day. Optional `day` accompanies `today_remaining` and must equal the current server day, otherwise 409 requests a refresh. Restoring the permanent default does not clear an existing same-day override. The response contains `id`, `daily_limit_override`, `day`, effective `daily_limit`, `daily_used`, `daily_reserved`, `daily_remaining`, and `reset_at`.
- POST `/v1/admin/registration-invites` accepts `{ "count":1, "valid_days":7 }` (count 1–100, days 1–365) and returns `{ "invitations":[{ "id":"...", "code":"...", "expires_at":"..." }] }`. GET the same path accepts `limit` 1–100 and nonnegative `offset`, returning paged unused, unrevoked, unexpired invitations with complete `code`, `created_at`, `expires_at`, `redeemed_at`, `redeemed_by`, and `revoked_at`. The returned `limit`/`offset`/`total` support fetching the full available list; Android loads the pages and reveals rows on demand for copying. Complete unused codes are privately retained so they can be shared again, and redemption clears their copyable plaintext. These are account-registration invitations, not monetary redemption codes.

Reported token totals include only `usage_known=true` jobs. Known/unknown usage counts cover dispatched requests, including failed or uncertain outcomes; they are separate from completion counts. Android uses nullable count fields when reading older responses.

Android maps these payloads to `AdminConfig`, `AdminStats` (`AdminTotals`, `AdminTodayQuota`, `AdminDailyUsage`), and `AdminUsersPage` (`UserUsage`). Their field names follow the camelCase mapping. Successful administration responses use `Cache-Control: no-store`. Deployment and accounting implementation details are maintained in `docs/backend.md`.

### Feedback HTTP and worker integration

- Owner bearer: `POST /v1/feedback`, `GET /v1/feedback`, and `GET /v1/feedback/{id}/images/{image_id}`. Text/contact/images submit in one transaction; same-key same-payload resubmission is idempotent. Separate Beijing daily limit is three feedback items per owner.
- Admin bearer: list/detail/image GET under `/v1/admin/feedback` and PATCH `/{id}` with status/note and expected revision. Changing approval or note invalidates an older worker claim. Status is `pending|approved|ignored`.
- Restricted worker bearer: `GET /v1/feedback-worker/queue`, image GET with matching revision, POST `/{id}/claim`, and POST `/{id}/complete`. Only current approved revisions are readable; contacts and admin configuration are excluded. Claims have a two-hour lease. Completion is `completed|needs_input|failed` with a result note safe for the submitter to read; identical writes are idempotent.
- Only feedback POST allows a 4.5 MiB request body; image payloads are at most three JPEG/PNG files of 1 MiB each after base64 decoding. Other write routes retain their previous limits. Full schemas, validation and privacy boundaries: [feedback.md](feedback.md). Self-hosted worker configuration and credential boundaries are described in [backend.md](backend.md). No active scheduler or production credential is included.

## Persistence and identity
- Room schema 2 stores large cells as bounded `payload_parts` records. DAO transparently splits/reconstructs them in transactions. Schema 1 migration splits old cells in SQL before a CursorWindow reads them.
- Account sessions use encrypted device credentials and are excluded from backups along with passwords, API keys and task secrets. `TaskRecord.ownerUserId` is non-secret ownership metadata retained in format-6 task records; use 1.0.16+ to preserve account-aware recovery. Restoring does not sign in or upload archives. Explicit task resume must authenticate the original account and preserve its frozen settings, request IDs and cache identity.
- From 1.0.1, conversation payload storageVersion 1 keeps one messageIndex and integer messageIndices per branch; decode restores shared message objects and rejects conflicting IDs, invalid/duplicate references and orphan index entries. Legacy storageVersion 0 branch-full payloads remain readable. A bounded 64 KiB pipe connects the stream codec to transactional Room payload parts, avoiding the giant expanded JSON string on write and refresh. The Room schema remains 2. Import reuses parsed objects and refresh reuses unchanged conversations.
- From 1.0.11, `BackupDocument` writes version 6 and reads versions 1 through 6; format 6 requires a compatible 1.0.11+ reader. Backups containing continuation-v1.7 or continuation-v1.8 task snapshots require 1.0.13+; v1.6 snapshots require 1.0.12+; older readers safely reject unsupported prompt versions before replacement even though the JSON format stays 6. It retains `TaskRecord.initialContextScope` solely for the exact already-selected original messages referenced by the first chunk of a cross-task incremental continuation. It does not widen archive source selection or add references to an existing admitted request. Format 5, introduced in 1.0.8, retains long-text references and task recovery and requires a compatible 1.0.8+ reader. Version 4 introduced response-style types/settings; version 2 introduced deduplicated conversation payloads, and version 3 introduced reorganization task protection. This version boundary protects persisted task/enum/settings compatibility; accepting unknown provider-output labels does not make old apps compatible with new backups. The product backup path streams JSON into a uniquely named private temporary file and copies it to the chosen URI; save, cancellation and failures clean staging. Restore decodes the URI InputStream without whole-file bytes/String copies, validates the complete graph before replacement, and keeps the previous 2 GiB input bound. Only internal small-fixture compatibility methods return/accept whole Strings. The decoded object graph is still resident; this does not promise constant-memory backup of arbitrary libraries.
- Entry identity is based on source lineage/category/kind, independent of summary chunk boundaries. Same-lineage text from multiple chunks is aggregated before merge. Cache identity additionally includes content, prompt and provider settings.
- Empty message selection means none. Provider address changes invalidate old credentials. Archived source and manual edits/tombstones survive updates. Restoring an older archive removes incompatible tasks before they can append data again.

## Module responsibilities

- `android/app/src/main/java/app/banzhan/deepseekcontinuation/core/` contains serializable domain models, import parsing, archive merging, chunking, and continuation formatting. Core unit tests and `fixtures/` validate these behaviors.
- `android/app/src/main/java/app/banzhan/deepseekcontinuation/data/` owns Room persistence, secure credentials, source storage, revisions, task state, quota checks, and service requests.
- `android/app/src/main/java/app/banzhan/deepseekcontinuation/ui/`, `MainActivity.kt`, and Android resources own navigation, preparation/progress/result screens, readable output actions, and advanced controls.
- `android/` build configuration and [building.md](building.md) describe compilation, packaging, and verification setup.
- `backend/` and `docs/backend.md` own invitation redemption, budget enforcement, organization jobs, and service operations.
- `docs/` records integration contracts, usage, privacy and validation limits.

## Automatic continuity (1.0.7)

`ArchiveEntry.continuityCue` is optional and defaults to null. Its values are `PREFERENCE`, `CORRECTION`, `SHARED_LANGUAGE`, `OPEN_THREAD`, and `RESPONSE_STYLE`; the original category, kind, text and sourceIds remain required. Provider-output normalization converts unknown or malformed optional cue labels to null without discarding otherwise valid text and sources. This tolerance is not a promise that older clients can read new persisted enum values; format 4 backups require 1.0.7+. PREFERENCE requires USER_FACT and exclusively user sources; CORRECTION and SHARED_LANGUAGE require at least one user source. Valid IDs establish source association, not semantic truth.

RESPONSE_STYLE describes observable response behavior supported by at least two distinct source messages, all with the assistant role. Its kind must not be USER_FACT. Export requires all cited sources to resolve to one unambiguous selected conversation/branch and remain within its selected ranges; shared or conflicting branch attribution is not guessed. Unsupported attribution is retained as ordinary text rather than promoted to style guidance. The prompt excludes broad personality labels, assumed user approval, identity continuity and relationship commitments. Current handwritten instructions and later explicit user requirements override observed behavior.

The model identifies cues within the existing organization request, without an extra request, new billing path or forced per-kind count. New BYOK tasks use continuation-v1.8; persisted continuation-v1.1 through continuation-v1.7 tasks retain their frozen prompt bytes, budgets, cache keys and request identities. Missing task promptVersion means v1.1. Optional cue types distinguish new summary identities, while null-cue identities remain unchanged.

Enabled continuation export promotes only unedited, undeleted cues with unambiguous selected branch sources, preserving their full text and original classifications. Explicit interaction cues precede observed styles; unfinished topics precede the necessary memory and retain their source-time state rather than claiming they are still unresolved now. Corrections and records sharing their sources are grouped in source order without claiming semantic replacement; other earlier requirements are not silently removed. Raw candidates may use cues as anchors; representative and explicit selections retain whole exchanges, while oversized recent exchanges can use labelled exact-source excerpts. Source gaps, manual edits and deletions keep priority, without inferring a cross-branch chronology.

`ContinuitySettings.includeResponseStyle` defaults to true and is appended to the existing settings model. When enabled, default export includes at most four representative styles, deduplicated by trimmed entry text while retaining each selected representative's own provenance. Other valid automatic style entries remain stored, with a notice, and are excluded from the factual section. `includeAllMemory=true` includes the remaining distinct valid observations, still respecting saved switches. Styles omitted only by the representative limit count toward `ExportResult.omittedCount`, so the all-memory option remains available even when ordinary factual memory fits. When either the style switch or overall continuity is disabled, no validated automatic style entry is exported or moved back into facts. This does not prohibit explicitly selected source quotes or erase manual edits; invalid and manual entries retain ordinary factual protection. `ContinuityPlan.responseStyleCount` defaults to 0 and reports the valid available entry count even when the switch is off.

The main flow requires no continuity settings step or persona form. Optional adjustment adds “参考旧助手的回应方式” and preserves overall disabled state, explicitly saved style-off state, explicit empty selections, null automatic selections and written notes. Upgrading does not reprocess old archives or enable disabled ones. Reorganization carries these settings forward. See [conversation-continuity.md](conversation-continuity.md).


## Local continuation selection and budget (1.0.9)

`chooseContinuityCandidates` consumes already-scoped metadata, retains explicit selections, prioritizes pins within the automatic limit and covers existing source-backed cue/category/style features before using recency to break ties. Features stay branch-scoped; automatic recommendation/selection defaults remain 8 and 3. The selector does not infer emotion or personality from original text.

`renderContinuityFragment` returns its text, an `excerpted` flag and only actually represented message IDs. Full rendering retains original roles and attachment metadata. The bounded recent-context fallback takes unchanged sentence/paragraph substrings, preserves Unicode boundaries and marks omitted text/messages and metadata separately from quoted content. It prioritizes the latest user/assistant messages, returns null if no safe segment fits and is not used to shorten representative or explicitly selected key exchanges.

`ContinuityLogic.buildPlan/export` default to `includeAllMemory=false` and a 24000-character soft target. Interaction/recent context gets reserved space before whole automatic summary entries are selected. Handwritten background, edited/pinned/source-less records and explicit requirements remain protected and can exceed that target; original-mode transcripts stay whole. Omitted memory is reported without changing stored archives, sources, history or backups. The all-memory option restores enabled memory, not unselected original messages, and preserves saved switches.

Continuation wording checks the true selected endpoint and actually represented source IDs: an assistant endpoint is context, a user endpoint with later excluded messages is not assumed unanswered, and excerpts or missing endpoint text are identified. Explicit next-message text takes precedence. Copy and save use the same current preview export. These are local operations; backend v8 / BYOK v1.4, Room schema and backup format 5 are unchanged.

## Long-text organization (1.0.8)

`dialogueChunks` is used for newly admitted v1.4 tasks; the earlier `chunks` behavior remains available to legacy tasks. It keeps complete user/assistant exchanges together where they fit, then falls back to whole messages and natural text boundaries before a Unicode-safe hard split. Primary content stays in source order. Each chunk may include up to `min(800, primaryLimit / 6)` characters of earlier selected material as `contextMessages`; no unselected source or other branch is added. A split message can appear under the same source ID in both primary and reference sections.

The companion advertises `organization_protocol_version=12`. New clients use v1.8 with capability 12+, the frozen v1.7 path with capability 11, v1.6 with capability 10, v1.5 with capability 9, the frozen v1.4 path with capability 8, and v1.3 with earlier services. Organization POSTs accept at most 800 reference characters within the existing combined 40000-character and 10000-message limits. Input estimates include references; every retained output must cite a primary source. Reference-only entries do not displace valid primary results, and a reference-bearing request with no primary result retains a visible task notice. There is no separate semantic consolidation request.

Reference content participates in cache identities, private task persistence and source validation. Format 5 introduced within-task long-text references; current format 6 also preserves `TaskRecord.initialContextScope` for bounded exact original-source references in the first chunk of a cross-task incremental continuation. Restoring format 6 requires 1.0.11 or newer. Completed results and live or unknown request identities are reused. Safe explicit re-splitting retains only previously selected reference material, while a lost POST keeps its original payload and idempotency identity. The server stores references with the expiring task body, outside long-lived provider configuration.

Continuation presentation merges only identical automatic text with equal category/kind/cue, one unambiguous selected branch and the same latest source position. It preserves all cited IDs, different-time repetitions and all entries tied to edited, pinned or deleted sources. This is display cleanup, not semantic contradiction resolution or a rewrite of saved entries.

## Optional political-topic export (1.0.8)

The result and archive-detail screens offer “生成温和版” for summaries. Preparation captures the current saved continuation export and shows actual batch counts and any available price estimate; opening the dialog is not a model submission. Explicit start creates a private export task, and every dispatched batch uses the ordinary quota/accounting rules. The original archive remains untouched.

`GET /v1/quota` advertises `political_filter_supported=true`. Unsupported services are rejected before submission. `POST /v1/jobs` with `operation="political_filter"` uses provider protocol 1; the BYOK prompt is `political-filter-v1`. Each batch has 1–12 unique user-role excerpt IDs, at most 6000 characters and no context references. The transport role does not assert that the user wrote or endorsed the quoted text. Returned marks use `category=QUOTES`, `kind=UNCONFIRMED`, one provided source ID and a short reason of at most 400 characters. There is no model-supplied replacement prose. Ordinary organization v2–v8 prompts remain independent.

The client applies marks to exact Unicode-safe spans of an immutable text snapshot. By default it omits marked spans in the export copy; a user may retain suspected false positives. Text outside omitted spans, including whitespace gaps, stays unchanged. An empty finding list returns the original snapshot, not a claim of safety or guaranteed acceptance by another platform. The scope is political topics, not general privacy removal or inference of a person's beliefs.

One private AtomicFile record holds the snapshot, captured nonsecret service settings, per-batch request identities/results and retained-span choices. It is not part of Room archive backups. Credentials remain in secure storage. Closing the dialog keeps progress; process death pauses work. Existing remote jobs are polled, unknown companion POSTs retain their keys, and uncertain BYOK replacement attempts need explicit confirmation. Partial work cannot be exported as a completed check. Changing the archive does not silently replace the task; starting a new check is explicit. Clearing local data removes the private record and its credential after attempting cancellation, but cannot undo charges for already dispatched requests.
