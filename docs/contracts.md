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
@Serializable data class ArchiveEntry(val id:String, val category:EntryCategory, val text:String, val sourceIds:List<String> = emptyList(), val kind:EntryKind=EntryKind.UNCONFIRMED, val pinned:Boolean=false, val edited:Boolean=false, val deleted:Boolean=false)
@Serializable data class Archive(val id:String, val title:String, val conversationIds:List<String>, val entries:List<ArchiveEntry>, val updatedAt:Long=System.currentTimeMillis(), val background:String="", val generationMode:String="legacy")
@Serializable data class SummaryChunk(val id:String, val messages:List<ChatMessage>)
data class EntryConflict(val existing:ArchiveEntry, val proposed:ArchiveEntry)
data class MergeResult(val entries:List<ArchiveEntry>, val conflicts:List<EntryConflict>)
data class ExportResult(val text:String, val omittedCount:Int, val pinnedOverflow:Boolean)
object DeepSeekImporter { fun parse(bytes:ByteArray,fileName:String):ImportResult }
object ArchiveLogic {
 fun chunks(messages:List<ChatMessage>,maxCharacters:Int=6000):List<SummaryChunk>
 fun localEntries(messages:List<ChatMessage>):List<ArchiveEntry>
 fun merge(existing:List<ArchiveEntry>,incoming:List<ArchiveEntry>):MergeResult
 fun continuationExport(archive:Archive):ExportResult
 fun export(archive:Archive,maxCharacters:Int=6000):ExportResult
}
```
Message IDs must be stable and conversation-scoped. Split long content without losing source ids. Import explicit roles, never alternate inferred role by position. Reject unsupported/unsafe ZIP with helpful errors. Empty export is an error.

### Continuation output

- `continuationExport` is the ordinary result-screen output. It includes the complete nonblank background and every nondeleted entry with nonblank text, including pinned and edited entries. It returns `omittedCount=0` and `pinnedOverflow=false`. An archive with no usable content throws `IllegalArgumentException`.
- Output asks DeepSeek to continue from the background, discussed viewpoints, and recent progress, using later user corrections. Clarification is limited to actual contradictions, important decisions, and explicitly unresolved information. Personal experiences, medical history, commitments, and completed actions stay grounded in explicit user statements. Original modal wording such as “可能” and “一种解释” is preserved in full.
- The sections are 关于我, 重要的人和事, 已确定的事, 接下来要聊的事, and 重要原话. `USER_FACT`, `ASSISTANT_SUGGESTION`, and `UNCONFIRMED` display as 用户陈述, 讨论中的建议, and 讨论与理解. Consecutive entries of the same kind share one subheading within their category, preserving entry order. Stored kinds, source IDs, pinned/manual edits, and entry text retain their original values. Newly created assistant excerpts use the neutral role label 助手原文.
- `generationMode="original"` displays 聊天原文 and states that the transcript contains the selected content. `"summary"` displays 续聊摘要. `"legacy"` and unknown values display 已保存的续聊内容. Older stored JSON and backups with this field absent decode as `"legacy"`.
- The UI measures the complete output in UTF-8 bytes. Content over `60 * 1024` bytes is offered as a saved continuation file. The core function preserves the full text at every size. Advanced operations retain `export(archive,maxCharacters=6000)` and its explicit omission/overflow result, using the same neutral kind labels. Technical source IDs remain locally traceable and are included in advanced output. Ordinary continuation output omits those technical IDs.

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
 val developerPrefs:DeveloperPrefs=DeveloperPrefs(),
 val adminConfig:AdminConfig?=null, val adminLoading:Boolean=false, val adminError:String?=null,
 val adminStats:AdminStats?=null, val adminStatsLoading:Boolean=false, val adminStatsError:String?=null,
 val adminUsers:AdminUsersPage?=null, val adminUsersLoading:Boolean=false, val adminUsersError:String?=null)
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
 val maxOutputTokens:Int?=null,val dailyReserved:Int?=null,val userDailyReserved:Int?=null)
@Serializable data class DeveloperPrefs(val serviceUrl:String="https://digimonlegend.com/continuation-api",val sessionToken:String="",val expiresAt:String="")
data class AdminConfig(
 val publicEnabled:Boolean,val dailyLimit:Int,val userDailyLimit:Int,val model:String,val baseUrl:String,
 val apiKeyConfigured:Boolean,val contextTokens:Int,val maxOutputTokens:Int,val providerTimeoutSeconds:Double,
 val apiEnabled:Boolean,val mockMode:Boolean,val costKnown:Boolean,val configVersion:Int)
class AppController(application:Application) : AndroidViewModel(application) {
 val state:StateFlow<AppState>
 suspend fun importFile(bytes:ByteArray,fileName:String):List<String>
 suspend fun loadDemo()
 fun clearNotice()
 suspend fun saveSettings(settings:ServiceSettings)
 suspend fun testApiConnection(draft:ServiceSettings):ConnectionTestResult
 suspend fun redeemInvite(code:String)
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
 suspend fun addConversation(archiveId:String,conversation:ImportedConversation,branchId:String,selectedIds:Set<String>)
 suspend fun saveEntry(archiveId:String,entry:ArchiveEntry)
 suspend fun saveBackground(archiveId:String,background:String)
 suspend fun deleteArchive(archiveId:String)
 suspend fun organize(archiveId:String,conversationId:String,branchId:String,selectedIds:Set<String>)
 suspend fun resumeTask(taskId:String,acknowledgeCost:Boolean)
 suspend fun resumeTask(taskId:String)
 suspend fun cancelTask(taskId:String)
 suspend fun pauseTask(taskId:String)
 suspend fun clearLocalData()
 suspend fun revisions(archiveId:String):List<Archive>
 suspend fun restore(archiveId:String,revision:Archive)
 suspend fun conflicts(archiveId:String):List<EntryConflict>
 suspend fun resolveConflict(archiveId:String,conflict:EntryConflict,useProposed:Boolean)
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
- Generation mode records the content origin. Progress and completion come from `OrganizationTask.status`, `done`, and `total`. A summary archive may still be awaiting work or contain partial saved results. Adding raw excerpts to a summary archive changes its mode to `"legacy"`. Saving nonempty organization output into an original archive also changes its mode to `"legacy"` because the saved content then combines original excerpts and summaries.
- `checkOrganizationAvailability` returns `null` when preflight passes, or a user-facing blocker string. BYOK performs local configuration and credential-format checks. Companion mode reuses its service-scoped encrypted identity, creating an anonymous session when needed, then refreshes quota. Public access requires an enabled service and positive global/personal remaining counts. Invitation access retains positive remaining/per-job money checks. Companion preparation also validates the server model, context, and output budgets. Validation/network failures become blocker text, and coroutine cancellation propagates.
- `refreshQuota` publishes independent `quotaLoading` and `quotaError` state, clearing the previous quota while refreshing. Responses are accepted only for the same current companion identity. The anonymous identity survives ordinary data clearing/restoration and is reused for that service. Companion task progress schedules quota refreshes.
- `estimateForCurrentService` uses the latest quota model and token budgets for companion mode. `maxOutputTokens` is captured in task settings and participates in chunk sizing, output allowance, estimates, and cache identity. Android validates an output budget of 256–8192 tokens below the context budget. Before submitting each new companion chunk, the client verifies the current model/context/output budgets still match the task snapshot. A mismatch pauses further work with completed results retained. `OrganizationTask.serviceMode` is the captured settings mode (`"companion"` or `"byok"`), with `"byok"` as its compatibility default.
- Preflight is a point-in-time result. Reservations occur during companion job submission. The ordinary UI checks availability before creating a summary archive, and the advanced confirmation path checks before organization. `organize` validates configuration, selection, and task slot before persisting and launching a task. Successful BYOK local validation leaves provider authentication and balance unverified.
- `busy` reflects active wrapped controller operations. Quota and administration have their own loading/error fields. Background organization progress is observed through `tasks`. The one-argument `resumeTask` delegates with `acknowledgeCost=false`. Ambiguous or expired work requires the explicit acknowledged path.
- Developer login is stored as encrypted `DeveloperPrefs`, separate from BYOK and companion credentials. Changing the developer service address or logging out clears its session and loaded administration state. Expiry or HTTP 401 invalidates the local admin session. Config updates use PATCH followed by GET before publishing confirmed configuration. Stats and user-list refreshes each replace their own result/error state.

## Application update channel

- The current Android source is 1.0.2 / versionCode 11. Clients with the updater can check for a newer same-signature build; older clients without it need a manual upgrade. Lifecycle ON_START (launch or return to foreground) checks `https://digimonlegend.com/continuation-updates/latest.json` quietly. `checkOnStart` uses elapsed time to throttle automatic checks to at most once per five minutes; this is a foreground-entry limit, not a periodic background timer. About exposes a manual check. A newer version is offered through a nonmodal notice that users can postpone. A check only reads the static manifest and never starts a model call or consumes organization request quota.
- Download begins only after the user's explicit action. The downloaded APK stays in the application-private cache and is accepted for installation only when its actual package name, newer version, byte size, SHA-256 and signer match the manifest/current application as required. Bad, truncated or mismatched files cannot be handed to the installer. APK verification does not replace Android's own package/signature checks.
- Installation must not start while AI organization or saving work is busy. The application requests Android's per-app unknown-source install permission where required and hands the verified APK to the system installer; the user still confirms installation in Android. There is no silent installation or automatic model retry tied to updating.
- The original manifest and immutable versioned APKs are hosted separately from the organization API. Publishing validates the release, uploads the immutable APK before atomically switching latest.json, and leaves API runtime parameters and accounting unchanged. Forks must configure their own hosting, package identity, signing and trusted update URLs.
- Full manifest schema, installation/update limits and publisher invocation are owned by [app-updates.md](app-updates.md) and `tools/publish_release.py`; no flags or payload field names are duplicated here. Build instructions are in [building.md](building.md); actual release validation is recorded in the corresponding Release notes.

## Companion HTTP (backend + Android agree)
`POST /v1/sessions`, invitation redemption, and admin login establish their respective credentials. Quota and job endpoints require `Authorization: Bearer <companion token>`. Administration uses its own bearer session.

- POST `/v1/sessions` accepts an empty request or `{}` and returns `{ "token":"...", "quota":{...} }` for a new anonymous public identity.
- POST `/v1/invitations/redeem` accepts `{ "code":"..." }` and returns the same token/quota envelope for the retained invitation flow.
- GET `/v1/quota` returns every field below. JSON names use snake_case, and the Android quota model maps the corresponding camelCase fields.

```
Quota {
 enabled: boolean, access_mode: "public" | "invitation", model: string, message: string,
 mock_mode: boolean, cost_known: boolean,
 remaining_cny: number, per_job_limit_cny: number,
 context_tokens: integer, max_output_tokens: integer,
 daily_limit: integer, daily_used: integer, daily_reserved: integer, daily_remaining: integer,
 user_daily_limit: integer, user_daily_used: integer, user_daily_reserved: integer,
 user_daily_remaining: integer, reset_at: ISO-8601 timestamp
}
```

- Public mode uses daily counts and reports both money fields as zero. `enabled` describes service readiness. Remaining-count fields determine whether another request fits. Default limits are 50 dispatches across the service and 5 per anonymous user each day, configurable by administration. The server uses `Asia/Shanghai`, and `reset_at` is the next Beijing midnight.
- Each remote job covers one summary chunk. Enqueue reserves one count atomically against both limits. Dispatch moves it to `used` on that Beijing calendar date. All outstanding reservations reduce availability, including reservations across midnight. Queued cancellation or rejection before dispatch releases its reservation. Dispatched failed/uncertain/cancelled work retains its used count. A repeated idempotency key for identical content reuses the job. A multi-chunk archive can consume multiple counts.
- POST `/v1/jobs` body `{ "idempotency_key":"...", "messages":[{"id":"...","role":"user","content":"..."}] }` => job.
- Submission accepts 1–10000 source messages and at most 40000 total content characters. Each source ID has 1–512 characters. Roles are user, assistant, system, tool, developer, and unknown, and all remain quoted transcript data. The idempotency key has 8–200 ASCII characters from `[A-Za-z0-9_.:-]`. The configured body-byte and provider-context limits are additional admission checks. Validation rejects before quota reservation.
- GET `/v1/jobs/{id}` => job.
- POST `/v1/jobs/{id}/cancel` => job.
- A job contains `id`, `status` (`queued|running|completed|failed|uncertain|cancelled`), `entries`, `message`, `usage.input_tokens`, `usage.output_tokens`, `cost_cny`, `cost_known`, `usage_known`, `reserved_cny`, `request_counted`, `cancel_requested`, `mock_mode`, and `content_expired`. Each entry contains `id`, `category`, `text`, `sourceIds`, and `kind` using the core enum names. `request_counted=true` means dispatch consumed a count. Cost values with `cost_known=false` have unknown monetary pricing. `usage_known` independently indicates whether both input/output counts are trustworthy; zero placeholders with false do not mean zero consumption. Android accepts the optional field for old-service compatibility.
- Error response detail carries useful user-facing text (Chinese supported). Duplicate idempotency key with changed payload returns 409. Job id scoped to owner. Server model fixed by configuration. `/health` read-only health.
- Expired job bodies/results return HTTP 410, including idempotent resubmission. Android requires an explicit user confirmation before allocating a replacement key and potentially paying again.

Provider snapshots now use protocol_version 3 for new jobs. Version 2 (including legacy missing-version snapshots) reconstructs the exact frozen v2 prompt and original input bound; unsupported versions and insufficient bounds fail before HTTP. V3 only adds explicit quoted-source boundaries and task/output_requirements, preserving source aliasing, output schema and fees. Diagnostics expose validated integer prompt_version 2 or 3.

The provider maps each original source ID to a short per-request `m1/m2…` alias, and restores only validated aliases before returning entries. Valid sources with incompatible role classification are conservatively downgraded to `UNCONFIRMED`; unknown aliases are rejected. One complete JSON or unlabelled code fence may be unwrapped; BOM, fence whitespace and U+200B/U+2060/FEFF at the outer text edges are tolerated; characters inside JSON strings are preserved. Surrounding prose, multiple documents, truncation, duplicate keys and non-finite numbers remain invalid. Failure codes and diagnostics expose only safe structural metadata, never original model output or transcript text.

Usage is known only with a complete, non-conflicting prompt/completion or input/output pair, accepting nonnegative integers and decimal integer strings. Booleans, floats, missing pairs, total-only estimates and conflicting values are untrusted. Over-bound reported usage pauses the budget. A public count-mode job with unconfigured prices may complete with unknown usage only after a valid single choice, `stop`, complete JSON and source validation; monetary-mode unknown usage remains uncertain with its reservation retained. There is no automatic billed repair request.

LLM prompt asks for JSON object `{"entries":[...]}` using only supplied source ids, treats transcript as untrusted quoted data, excludes reasoning_content. Both client/server validate references, bounded text, enums and response size. No auto second paid request to repair malformed JSON.

### Administration HTTP

- POST `/v1/admin/login` accepts the string field `password` and returns `{ "token":"...", "expires_at":"..." }`. The bearer session expires 12 hours after login. Five recent failed attempts per source IP trigger HTTP 429 during a 15-minute window. Password values are transient login input. Subsequent requests carry the returned token. Authentication/configuration failures use HTTP 401/503 and validation failures use HTTP 422 with a `detail` message.
- GET `/v1/admin/config` returns `public_enabled`, `daily_limit`, `user_daily_limit`, `model`, `base_url`, `context_tokens`, `max_output_tokens`, `provider_timeout_seconds`, `api_key_configured`, `api_enabled`, `mock_mode`, `cost_known`, and `config_version`.
- PATCH `/v1/admin/config` accepts the first eight configuration fields above plus optional `api_key`. Omitted fields preserve their values, and explicit nulls are rejected. `api_key` accepts an empty string to clear the configured key. Responses use the GET shape with the boolean `api_key_configured`. The runtime configuration update increments `config_version` when its stored override changes. Queued work is rechecked against current settings before dispatch.
- GET `/v1/admin/stats?days=7` accepts 1–90 days and returns `timezone`, `days`, `start_date`, `end_date`, `generated_at`, `totals`, `today`, and `daily`. `totals` contains `users`, `new_users`, `active_users`, `requests`, `input_tokens`, `output_tokens`, `completed`, `failed`, `uncertain`, `cancelled`, `queued`, and `running`, plus `usage_known_requests` and `usage_unknown_requests`. `today` contains global daily limit/used/reserved/remaining and `reset_at`. Each daily row contains `date`, `new_users`, `active_users`, `requests`, input/output tokens, known/unknown usage request counts, and the four terminal-status counts. `users` is the current total, `active_users` is distinct over the requested period, and queued/running are current counts. Request/token series use dispatch dates, while terminal-status series use job creation dates.
- GET `/v1/admin/users?limit=50&offset=0` accepts limit 1–200 and offset ≥0. It returns `limit`, `offset`, `total`, and `users` ordered by creation time descending and ID. Each user has `id`, `access_mode`, `created_at`, nullable `last_seen_at`, personal `daily_limit`, `daily_used`, `daily_reserved`, `daily_remaining`, `total_requests`, `completed`, `failed`, `uncertain`, `cancelled`, `input_tokens`, `output_tokens`, `usage_known_requests`, and `usage_unknown_requests`.

Reported token totals include only `usage_known=true` jobs. Known/unknown usage counts cover dispatched requests, including failed or uncertain outcomes; they are separate from completion counts. Android uses nullable count fields when reading older responses.

Android maps these payloads to `AdminConfig`, `AdminStats` (`AdminTotals`, `AdminTodayQuota`, `AdminDailyUsage`), and `AdminUsersPage` (`UserUsage`). Their field names follow the camelCase mapping. Successful administration responses use `Cache-Control: no-store`. Deployment and accounting implementation details are maintained in `docs/backend.md`.

### Feedback HTTP and worker integration

- Owner bearer: `POST /v1/feedback`, `GET /v1/feedback`, and `GET /v1/feedback/{id}/images/{image_id}`. Text/contact/images submit in one transaction; same-key same-payload resubmission is idempotent. Separate Beijing daily limit is three feedback items per owner.
- Admin bearer: list/detail/image GET under `/v1/admin/feedback` and PATCH `/{id}` with status/note and expected revision. Changing approval or note invalidates an older worker claim. Status is `pending|approved|ignored`.
- Restricted worker bearer: `GET /v1/feedback-worker/queue`, image GET with matching revision, POST `/{id}/claim`, and POST `/{id}/complete`. Only current approved revisions are readable; contacts and admin configuration are excluded. Claims have a two-hour lease. Completion is `completed|needs_input|failed` with a result note safe for the submitter to read; identical writes are idempotent.
- Only feedback POST allows a 4.5 MiB request body; image payloads are at most three JPEG/PNG files of 1 MiB each after base64 decoding. Other write routes retain their previous limits. Full schemas, validation and privacy boundaries: [feedback.md](feedback.md). Self-hosted worker configuration and credential boundaries are described in [backend.md](backend.md). No active scheduler or production credential is included.

## Persistence and identity
- Room schema 2 stores large cells as bounded `payload_parts` records. DAO transparently splits/reconstructs them in transactions. Schema 1 migration splits old cells in SQL before a CursorWindow reads them.
- From 1.0.1, conversation payload storageVersion 1 keeps one messageIndex and integer messageIndices per branch; decode restores shared message objects and rejects conflicting IDs, invalid/duplicate references and orphan index entries. Legacy storageVersion 0 branch-full payloads remain readable. A bounded 64 KiB pipe connects the stream codec to transactional Room payload parts, avoiding the giant expanded JSON string on write and refresh. The Room schema remains 2. Import reuses parsed objects and refresh reuses unchanged conversations.
- BackupDocument version 2 contains the new conversation payload format; version 1 backups remain supported and pre-1.0.1 apps reject version 2 instead of silently misreading it. The product backup path streams JSON into a uniquely named private temporary file and copies it to the chosen URI; save, cancellation and failures clean staging. Restore decodes the URI InputStream without whole-file bytes/String copies, validates the complete graph before replacement, and keeps the previous 2 GiB input bound. Only internal small-fixture compatibility methods return/accept whole Strings. The decoded object graph is still resident; this does not promise constant-memory backup of arbitrary libraries.
- Entry identity is based on source lineage/category/kind, independent of summary chunk boundaries. Same-lineage text from multiple chunks is aggregated before merge. Cache identity additionally includes content, prompt and provider settings.
- Empty message selection means none. Provider address changes invalidate old credentials. Archived source and manual edits/tombstones survive updates. Restoring an older archive removes incompatible tasks before they can append data again.

## Module responsibilities

- `android/app/src/main/java/app/banzhan/deepseekcontinuation/core/` contains serializable domain models, import parsing, archive merging, chunking, and continuation formatting. Core unit tests and `fixtures/` validate these behaviors.
- `android/app/src/main/java/app/banzhan/deepseekcontinuation/data/` owns Room persistence, secure credentials, source storage, revisions, task state, quota checks, and service requests.
- `android/app/src/main/java/app/banzhan/deepseekcontinuation/ui/`, `MainActivity.kt`, and Android resources own navigation, preparation/progress/result screens, readable output actions, and advanced controls.
- `android/` build configuration and [building.md](building.md) describe compilation, packaging, and verification setup.
- `backend/` and `docs/backend.md` own invitation redemption, budget enforcement, organization jobs, and service operations.
- `docs/` records integration contracts, usage, privacy and validation limits.
