package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ContinuityLogic
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.util.Base64
import kotlinx.serialization.decodeFromString

/** Validate the complete graph before the single replacement transaction begins. */
internal object BackupValidation {
    fun validate(backup: BackupDocument) {
        require(backup.format == "deepseek-continuation-assistant" && backup.version in 1..6) { "不支持此备份格式或版本" }
        require(backup.settings.apiKey.isEmpty() && backup.settings.trialToken.isEmpty()) { "备份不应包含服务凭据" }
        unique(backup.sources.map { it.id }, "原始文件")
        unique(backup.conversations.map { it.id }, "会话")
        unique(backup.archives.map { it.id }, "档案")
        unique(backup.revisions.map { it.id }, "历史版本")
        unique(backup.conflicts.map { it.id }, "待处理冲突")
        unique(backup.tasks.map { it.id }, "任务")
        unique(backup.cache.map { it.id }, "分块缓存")
        backup.sources.forEach { source ->
            require(source.fileName.isNotBlank() && source.fileName.length <= 1024) { "备份原始文件名无效" }
            val bytes = try { Base64.getDecoder().decode(source.bytesBase64) }
            catch (_: IllegalArgumentException) { error("备份原始文件编码损坏") }
            require(hash(bytes) == source.id) { "备份原始文件校验失败" }
        }
        val messages = mutableMapOf<String, ChatMessage>()
        val conversations = backup.conversations.associate { row ->
            val conversation = ConversationStorage.read(row.payload)
            require(row.id == conversation.id && conversation.branches.isNotEmpty()) { "备份会话结构无效" }
            unique(conversation.branches.map { it.id }, "会话分支")
            require(conversation.currentBranchId == null || conversation.branches.any { it.id == conversation.currentBranchId }) { "备份当前分支不存在" }
            conversation.branches.forEach { branch ->
                unique(branch.messages.map { it.id }, "分支消息")
                branch.messages.forEach { message ->
                    require(message.role.isNotBlank()) { "备份消息缺少角色" }
                    val existing = messages.putIfAbsent(message.id, message)
                    require(existing == null || existing == message) { "备份消息编号对应不同内容" }
                }
            }
            row.id to conversation
        }
        fun validateArchive(archive: Archive) {
            validateSoftOrigin(archive.rebuiltFromArchiveId, archive.id)
            require(archive.title.isNotBlank() && archive.title.length <= 100_000 && archive.background.length <= 100_000) { "备份档案文字无效" }
            unique(archive.conversationIds, "档案会话引用")
            require(archive.conversationIds.all { it in conversations }) { "备份档案引用了不存在的会话" }
            validateContinuity(archive, conversations.values.toList())
            val allowed = archive.conversationIds.flatMap { conversations.getValue(it).branches }.flatMap { it.messages }.map { it.id }.toSet()
            unique(archive.entries.map { it.id }, "档案条目")
            archive.entries.forEach { validateEntry(it, allowed) }
        }
        val archives = backup.archives.associate { row ->
            val archive = appJson.decodeFromString<Archive>(row.payload)
            require(row.id == archive.id && row.updatedAt == archive.updatedAt) { "备份档案编号或时间不匹配" }
            validateArchive(archive)
            row.id to archive
        }
        val historicalArchives = backup.revisions.map { row ->
            val archive = appJson.decodeFromString<Archive>(row.payload)
            require(row.archiveId in archives && archive.id == row.archiveId) { "备份历史版本引用无效" }
            validateArchive(archive)
            archive
        }.groupBy { it.id }
        backup.conflicts.forEach { row ->
            val archive = archives[row.archiveId] ?: error("备份冲突引用的档案不存在")
            val conflict = appJson.decodeFromString<StoredConflict>(row.payload)
            val allowed = archive.conversationIds.flatMap { conversations.getValue(it).branches }.flatMap { it.messages }.map { it.id }.toSet()
            validateEntry(conflict.existing, allowed)
            validateEntry(conflict.proposed, allowed)
            require(archive.entries.any { it.id == conflict.existing.id }) { "备份冲突引用的条目不存在" }
        }
        backup.tasks.forEach { row ->
            val task = row.decodeTask()
            val archive = archives[row.archiveId] ?: error("备份任务引用的档案不存在")
            require(task.id == row.id && task.archiveId == row.archiveId) { "备份任务编号不匹配" }
            validateSoftOrigin(task.reorganizationOfArchiveId, task.archiveId)
            require(task.reorganizationOfArchiveId == null || task.reorganizationOfArchiveId == archive.rebuiltFromArchiveId) {
                "备份重整任务与新版档案来源不匹配"
            }
            require(task.settings.apiKey.isEmpty() && task.settings.trialToken.isEmpty() && task.secretRef.isEmpty()) { "备份任务不应包含凭据" }
            validateSettings(task.settings)
            require(SummaryProtocol.supportsVersion(task.promptVersion)) { "备份包含不支持的整理协议" }
            require(backup.version >= 5 || !SummaryProtocol.supportsContext(task.promptVersion)) { "长文整理任务需要第 5 版备份" }
            require(task.status in setOf("queued", "running", "paused", "completed", "cancelled")) { "备份任务状态无效" }
            unique(task.chunks.map { it.id }, "任务分块")
            val branchMessageIds = archive.conversationIds.flatMap { conversations.getValue(it).branches }
                .map { branch -> branch.messages.map { it.id }.toSet() }
            val allowed = branchMessageIds.flatten().toSet()
            val initialPositions = validateInitialContextScope(task, archive, conversations, backup.version,
                historicalArchives[archive.id].orEmpty())
            val initialIds = task.initialContextScope?.selectedMessageIds.orEmpty().toSet()
            val priorPrimaryIds = mutableSetOf<String>()
            val priorPrimaryEnds = mutableMapOf<String, Int>()
            task.chunks.forEachIndexed { chunkIndex, chunk ->
                require(chunk.cacheKey.isNotBlank() && chunk.messages.isNotEmpty() && chunk.attempt >= 0) { "备份分块结构无效" }
                require(!chunk.completed || backup.cache.any { it.id == chunk.cacheKey }) { "备份缺少已完成分块缓存" }
                val nextPrimaryEnds = mutableMapOf<String, Int>()
                chunk.messages.forEach { part ->
                    val original = messages[part.id]
                    require(part.id in allowed && original != null && original.role == part.role && original.content.contains(part.content)) { "备份分块引用了无效原消息" }
                    if (SummaryProtocol.supportsContext(task.promptVersion)) {
                        val previousEnd = nextPrimaryEnds[part.id] ?: priorPrimaryEnds[part.id] ?: 0
                        // Reorganization can select branches sharing complete source messages.
                        val offset = if (initialPositions == null && previousEnd == original.content.length) 0 else previousEnd
                        require(original.timestamp == part.timestamp && original.attachments == part.attachments &&
                            original.content.regionMatches(offset, part.content, 0, part.content.length)) { "备份长文分片顺序或来源信息不匹配" }
                        nextPrimaryEnds[part.id] = offset + part.content.length
                    }
                }
                if (chunk.contextMessages.isNotEmpty()) {
                    require(SummaryProtocol.supportsContext(task.promptVersion) && backup.version >= 5 &&
                        chunk.contextMessages.sumOf { it.content.length.toLong() } <= 800L) { "备份跨段参考不符合整理协议" }
                    val primaryIds = chunk.messages.map { it.id }.toSet()
                    val referenceIds = chunk.contextMessages.map { it.id }.toSet()
                    require(referenceIds.all { it in priorPrimaryIds || (chunkIndex == 0 && it in initialIds) }) {
                        "备份跨段参考超出本次已选前文"
                    }
                    if (initialPositions != null) {
                        val positions = chunk.contextMessages.map { part ->
                            initialPositions[part.id] ?: error("备份增量参考不属于已选聊天版本")
                        }
                        require(referenceIds.size == chunk.contextMessages.size && positions.zipWithNext().all { (a, b) -> a < b }) {
                            "备份增量参考顺序或编号无效"
                        }
                    }
                    val sameBranch = branchMessageIds.any { it.containsAll(primaryIds + referenceIds) }
                    require(sameBranch) { "备份跨段参考混用了不同聊天版本" }
                    chunk.contextMessages.forEach { part ->
                        val original = messages[part.id]
                        require(part.id in allowed && original != null && original.role == part.role &&
                            original.timestamp == part.timestamp && original.attachments == part.attachments &&
                            original.content.contains(part.content)) { "备份跨段参考引用了无效原消息" }
                        val occurrence = original.content.indexOf(part.content)
                        require(occurrence >= 0 && ((chunkIndex == 0 && part.id in initialIds) ||
                            occurrence + part.content.length <= (priorPrimaryEnds[part.id] ?: 0))) {
                            "备份跨段参考包含尚未处理的后文"
                        }
                    }
                }
                priorPrimaryIds += chunk.messages.map { it.id }
                priorPrimaryEnds.putAll(nextPrimaryEnds)
                require(chunk.remoteJobId.length <= 200 && chunk.remoteJobId.all { it.isLetterOrDigit() || it in "-_" }) { "备份远端任务编号无效" }
            }
        }
        backup.cache.forEach { row ->
            val entries = appJson.decodeFromString<List<ArchiveEntry>>(row.payload)
            SummaryProtocol.validateEntries(entries, messages.values.toList())
        }
    }

    /** Only new v6 tasks can name a bounded, exact earlier scope for the first request. */
    private fun validateInitialContextScope(
        task: TaskRecord, archive: Archive, conversations: Map<String, ImportedConversation>, backupVersion: Int,
        historicalArchives: List<Archive>,
    ): Map<String, Int>? {
        val scope = task.initialContextScope ?: return null
        require(backupVersion >= 6 && SummaryProtocol.supportsContext(task.promptVersion) && task.reorganizationOfArchiveId == null) {
            "增量首段参考需要第 6 版备份及对应整理协议"
        }
        require(scope.selectedMessageIds.isNotEmpty() && scope.selectedMessageIds.size <= 4 && task.chunks.isNotEmpty()) {
            "备份增量首段参考范围无效"
        }
        require(scope.conversationId in archive.conversationIds) { "备份增量首段参考不属于此档案" }
        val source = requireNotNull(conversations[scope.conversationId]) { "备份增量首段参考会话不存在" }
        val branch = requireNotNull(source.branches.singleOrNull { it.id == scope.branchId }) { "备份增量首段参考分支不存在" }
        require(scope.selectedMessageIds.distinct().size == scope.selectedMessageIds.size &&
            ContinuityLogic.captureScope(source, branch.id, scope.selectedMessageIds.toSet()) == scope) {
            "备份增量首段参考顺序或消息范围无效"
        }
        val positions = branch.messages.mapIndexed { index, message -> message.id to index }.toMap()
        val primaryIds = task.chunks.flatMap { it.messages }.map { it.id }
        val requiredIds = scope.selectedMessageIds + primaryIds
        // Restoring an older archive revision must not invalidate an already admitted task.
        // One complete saved snapshot must prove its range; never combine unrelated revisions.
        val rangeWasSelected = (listOf(archive) + historicalArchives).any { snapshot ->
            val selected = snapshot.sourceScopes.filter { it.conversationId == scope.conversationId && it.branchId == scope.branchId }
                .flatMap { it.selectedMessageIds }.toSet()
            requiredIds.all { it in selected }
        }
        require(primaryIds.isNotEmpty() && requiredIds.all { it in positions } && rangeWasSelected) {
            "备份增量首段参考或正文超出原档案已选范围"
        }
        val primaryPositions = primaryIds.map { positions.getValue(it) }
        require(primaryPositions.zipWithNext().all { (a, b) -> a <= b } &&
            scope.selectedMessageIds.all { positions.getValue(it) < primaryPositions.first() }) {
            "备份增量首段参考包含未处理的后文或正文顺序无效"
        }
        return positions
    }

    internal fun validateContinuity(archive: Archive, conversations: List<ImportedConversation>) {
        ContinuityLogic.validateSettings(archive.continuity)
        ContinuityLogic.validateSourceScopes(archive.sourceScopes, conversations, archive.conversationIds.toSet())
        // Legacy or stale fragment IDs are handled conservatively by buildPlan;
        // they must not make an otherwise valid historical backup unreadable.
    }

    private fun unique(ids: List<String>, label: String) {
        require(ids.all { it.isNotBlank() && it.length <= 1024 } && ids.distinct().size == ids.size) { "备份${label}编号无效或重复" }
    }

    private fun validateSoftOrigin(origin: String?, archiveId: String) {
        require(origin == null || (origin.isNotBlank() && origin.length <= 1024 && origin != archiveId)) { "备份新版档案来源编号无效" }
    }

    private fun validateEntry(entry: ArchiveEntry, allowedSources: Set<String>) {
        require(entry.id.isNotBlank() && entry.text.length <= 100_000 && entry.sourceIds.all { it in allowedSources }) { "备份条目文字或来源引用无效" }
    }
}
