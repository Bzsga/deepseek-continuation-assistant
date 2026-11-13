package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveLogic
import app.banzhan.deepseekcontinuation.core.ArchiveSourceScope
import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LongTextTaskTest {
    private val settings = ServiceSettings(mode = "byok", contextTokens = 64000, maxOutputTokens = 1024)
    private val earlier = ChatMessage("earlier", "user", "讨论的是小说中的林舟。先听完，再提建议。", timestamp = "synthetic-1")
    private val current = ChatMessage("current", "assistant", "我先确认这个角色的经历。", timestamp = "synthetic-2")
    private val future = ChatMessage("future", "user", "后来补充：这个角色还没有决定搬家。")

    @Test fun companionNegotiatesBeforePlanningAndByokUsesNewProtocol() {
        val companion = settings.copy(mode = "companion")
        val quota = CompanionQuota(true, 0.0, 0.0, "synthetic-model", "",
            contextTokens = 64000, maxOutputTokens = 1024)
        assertEquals(SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION, organizationPromptVersion(companion))
        assertEquals(SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION, organizationPromptVersion(companion, quota))
        assertEquals(SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION,
            organizationPromptVersion(companion, quota.copy(organizationProtocolVersion = 7)))
        assertEquals(SummaryProtocol.LONG_TEXT_PROMPT_VERSION,
            organizationPromptVersion(companion, quota.copy(organizationProtocolVersion = 8)))
        assertEquals(SummaryProtocol.DISCUSSION_PROGRESS_PROMPT_VERSION,
            organizationPromptVersion(companion, quota.copy(organizationProtocolVersion = 9)))
        assertEquals(SummaryProtocol.SOURCE_FAITHFUL_PROMPT_VERSION,
            organizationPromptVersion(companion, quota.copy(organizationProtocolVersion = 10)))
        assertEquals(SummaryProtocol.NATURAL_RECALL_PROMPT_VERSION,
            organizationPromptVersion(companion, quota.copy(organizationProtocolVersion = 11)))
        assertEquals(SummaryProtocol.PROMPT_VERSION,
            organizationPromptVersion(companion, quota.copy(organizationProtocolVersion = 12)))
        assertEquals(SummaryProtocol.PROMPT_VERSION, organizationPromptVersion(settings))
    }

    @Test fun estimateCountsReferenceInputAndExactlyThePlannedRequests() {
        val messages = (0 until 20).flatMap { turn -> listOf(
            earlier.copy(id = "u-$turn", content = earlier.content.repeat(10)),
            current.copy(id = "a-$turn", content = current.content.repeat(20))) }
        val chunks = budgetedChunks(messages, settings)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.drop(1).any { it.contextMessages.isNotEmpty() })
        chunks.forEach { SummaryProtocol.validateForRequest(settings, it.messages, SummaryProtocol.PROMPT_VERSION, it.contextMessages) }
        assertEquals(messages.joinToString("") { it.content }, chunks.flatMap { it.messages }.joinToString("") { it.content })
        val estimate = estimateOrganization(settings, messages)
        assertEquals(chunks.size, estimate.chunks)
        assertEquals(chunks.size * settings.maxOutputTokens, estimate.outputTokens)
        assertEquals(chunks.sumOf { SummaryProtocol.estimatedInputTokens(it.messages, SummaryProtocol.PROMPT_VERSION, it.contextMessages) },
            estimate.inputTokens)
        assertTrue(estimate.inputTokens > chunks.sumOf { SummaryProtocol.estimatedInputTokens(it.messages) })
    }

    @Test fun oldTaskChunkingAndEmptyReferenceCacheDigestRemainFrozen() {
        val messages = listOf(earlier.copy(content = "片段🐳".repeat(1500)), current)
        val version = SummaryProtocol.RESPONSE_STYLE_PROMPT_VERSION
        val limit = minOf(SummaryProtocol.chunkCharacters(settings, version), (settings.maxOutputTokens * 2).coerceAtMost(6000))
        assertEquals(ArchiveLogic.chunks(messages, limit), budgetedChunks(messages, settings, version))
        val oldDigest = hash(appJson.encodeToString(settings.withoutSecrets()) + "\n" + version + "\npart\n" + appJson.encodeToString(messages))
        assertEquals(oldDigest, organizationCacheKey(settings, version, "part", messages))
        val referenced = organizationCacheKey(settings, SummaryProtocol.PROMPT_VERSION, "part", listOf(current), contextMessages = listOf(earlier))
        assertNotEquals(organizationCacheKey(settings, SummaryProtocol.PROMPT_VERSION, "part", listOf(current)), referenced)
        assertNotEquals(referenced, organizationCacheKey(settings, SummaryProtocol.PROMPT_VERSION, "part", listOf(current),
            contextMessages = listOf(earlier.copy(content = "不同的前文"))))
        assertNotEquals(referenced, organizationCacheKey(settings, SummaryProtocol.PROMPT_VERSION, "part", listOf(current),
            "another-reorganization", listOf(earlier)))
    }

    @Test fun processDeathAndUnknownPostsKeepOriginalReferencesAndRequestIdentity() {
        val chunk = TaskChunk("part", listOf(current), "paid-key", requestStarted = true, inFlight = true,
            contextMessages = listOf(earlier))
        val task = task(listOf(chunk)).copy(status = "running", inFlight = true)
        val restored = appJson.decodeFromString<TaskRecord>(appJson.encodeToString(task)).afterProcessDeath()
        assertTrue(restored.uncertain)
        assertEquals(listOf(chunk.copy(inFlight = false)), restored.chunksForExplicitResume())
        val remote = restored.copy(chunks = listOf(chunk.copy(inFlight = false, remoteJobId = "known-remote", remoteStatus = "running")))
        assertEquals(remote.chunks, remote.chunksForExplicitResume())
        val completed = restored.copy(chunks = listOf(chunk.copy(completed = true, inFlight = false)))
        assertEquals(completed.chunks, completed.chunksForExplicitResume())
        assertFalse(taskSourcesFit(restored, setOf(current.id)))
        assertTrue(taskSourcesFit(restored, setOf(earlier.id, current.id)))
    }

    @Test fun safeResplitKeepsFirstReferenceAndEveryPrimaryCharacter() {
        val long = current.copy(content = "句子🐳。\n".repeat(2000))
        val paid = TaskChunk("paid", listOf(earlier), "paid-key", completed = true)
        val failed = TaskChunk("long", listOf(long), "old-key", remoteJobId = "failed-job",
            remoteStatus = "failed", attempt = 2, contextMessages = listOf(earlier))
        val task = task(listOf(paid, failed))
        val pieces = task.chunksForExplicitResume()
        assertEquals(paid, pieces.first())
        val split = pieces.drop(1)
        assertTrue(split.size > 1)
        assertEquals(listOf(earlier), split.first().contextMessages)
        assertEquals(long.content, split.flatMap { it.messages }.joinToString("") { it.content })
        assertTrue(split.drop(1).all { it.contextMessages.any { reference -> reference.id == long.id } })
        assertTrue(split.all { it.attempt == 3 && it.remoteJobId.isEmpty() && !it.requestStarted && it.cacheKey != failed.cacheKey })
        assertEquals(pieces.size, pieces.map { it.id }.distinct().size)
        split.forEach { SummaryProtocol.validateForRequest(settings, it.messages, task.promptVersion, it.contextMessages) }
        assertEquals(pieces, task.copy(chunks = pieces).chunksForExplicitResume())
    }

    @Test fun backupFiveRetainsReferencesAndRejectsWrongScopeRoleOrText() {
        val chunks = listOf(TaskChunk("one", listOf(earlier), "one-key"),
            TaskChunk("two", listOf(current), "two-key", contextMessages = listOf(earlier)))
        val backup = fixture(task(chunks)).copy(version = 5)
        val restored = appJson.decodeFromString<BackupDocument>(appJson.encodeToString(backup))
        assertEquals(5, restored.version)
        BackupValidation.validate(restored)
        assertEquals(chunks, restored.tasks.single().decodeTask().chunks)
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.validate(backup.copy(version = 4)) }
        for (invalid in listOf(earlier.copy(role = "assistant"), earlier.copy(content = "不存在的句子"),
            earlier.copy(timestamp = "different-time"), future)) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupValidation.validate(fixture(task(chunks.dropLast(1) + chunks.last().copy(contextMessages = listOf(invalid)))))
            }
        }
    }

    @Test fun backupRejectsReferencesThatBorrowAnotherBranchEvenWhenPreviouslySelected() {
        val other = ChatMessage("other", "user", "不同分支里的约定")
        val chunks = listOf(TaskChunk("one", listOf(other), "one-key"),
            TaskChunk("two", listOf(current), "two-key", contextMessages = listOf(other)))
        val backup = fixture(task(chunks), other)
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.validate(backup) }
    }

    @Test fun backupAllowsCompleteSharedMessagesRepeatedByTwoSelectedBranches() {
        val chunks = listOf(
            TaskChunk("main-one", listOf(earlier), "main-one-key"),
            TaskChunk("main-two", listOf(current), "main-two-key", contextMessages = listOf(earlier)),
            TaskChunk("fork-one", listOf(earlier), "fork-one-key"),
            TaskChunk("fork-two", listOf(current), "fork-two-key", contextMessages = listOf(earlier)),
        )
        val backup = fixture(task(chunks))
        val original = ConversationStorage.read(backup.conversations.single().payload)
        val source = original.copy(branches = original.branches +
            ConversationBranch("fork", "另一版本", listOf(earlier, current)))
        val archive = appJson.decodeFromString<Archive>(backup.archives.single().payload)
        val selected = archive.copy(sourceScopes = archive.sourceScopes +
            ArchiveSourceScope(source.id, "fork", listOf(earlier.id, current.id)))
        BackupValidation.validate(backup.copy(
            conversations = listOf(ConversationRow(source.id, appJson.encodeToString(source))),
            archives = listOf(ArchiveRow(selected.id, selected.updatedAt, appJson.encodeToString(selected)))))
    }

    @Test fun backupReferenceCannotBorrowTheUnprocessedTailOfTheSameLongMessage() {
        val split = earlier.content.indexOf('。') + 1
        val prefix = earlier.copy(content = earlier.content.substring(0, split))
        val tail = earlier.copy(content = earlier.content.substring(split))
        val chunks = listOf(TaskChunk("prefix", listOf(prefix), "prefix-key"),
            TaskChunk("tail", listOf(tail), "tail-key", contextMessages = listOf(tail)))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.validate(fixture(task(chunks))) }
        BackupValidation.validate(fixture(task(chunks.dropLast(1) + chunks.last().copy(contextMessages = listOf(prefix)))))
    }

    @Test fun safeResplitDoesNotSendWhitespaceOnlyReferenceSuffixes() {
        val reference = earlier.copy(content = "有内容的前文" + " ".repeat(500))
        val long = current.copy(content = "合成正文。".repeat(1000))
        val chunks = budgetedChunks(listOf(long), settings, SummaryProtocol.PROMPT_VERSION, listOf(reference))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().contextMessages.isEmpty())
        assertTrue(chunks.flatMap { it.contextMessages }.all { it.content.isNotBlank() })
        assertEquals(long.content, chunks.flatMap { it.messages }.joinToString("") { it.content })
    }

    @Test fun missingTaskAndChunkFieldsReadAsFrozenLegacyWithoutReferences() {
        val value = task(listOf(TaskChunk("one", listOf(earlier), "one-key")))
        val fields = appJson.parseToJsonElement(appJson.encodeToString(value)).jsonObject
        val oldChunks = JsonArray(fields.getValue("chunks").jsonArray.map { element ->
            JsonObject(element.jsonObject.filterKeys { it != "contextMessages" }) })
        val old = JsonObject(fields.filterKeys { it != "promptVersion" } + ("chunks" to oldChunks)).toString()
        val restored = appJson.decodeFromString<TaskRecord>(old)
        assertEquals(SummaryProtocol.LEGACY_PROMPT_VERSION, restored.promptVersion)
        assertTrue(restored.chunks.single().contextMessages.isEmpty())
        BackupValidation.validate(fixture(restored).copy(version = 4))
    }

    private fun task(chunks: List<TaskChunk>) = TaskRecord("task", "archive", "合成长文", settings, "", chunks,
        status = "paused", promptVersion = SummaryProtocol.PROMPT_VERSION)

    private fun fixture(task: TaskRecord, other: ChatMessage? = null): BackupDocument {
        val branches = listOf(ConversationBranch("main", "当前", listOf(earlier, current, future), true)) +
            listOfNotNull(other?.let { ConversationBranch("other", "另一分支", listOf(it)) })
        val conversation = ImportedConversation("conversation", "固定虚构样例", branches = branches, currentBranchId = "main")
        val archive = Archive("archive", "合成档案", listOf(conversation.id), emptyList(), updatedAt = 1,
            sourceScopes = listOf(ArchiveSourceScope(conversation.id, "main", listOf(earlier.id, current.id))))
        return BackupDocument(settings = settings, sources = emptyList(),
            conversations = listOf(ConversationRow(conversation.id, appJson.encodeToString(conversation))),
            archives = listOf(ArchiveRow(archive.id, archive.updatedAt, appJson.encodeToString(archive))),
            revisions = emptyList(), conflicts = emptyList(),
            tasks = listOf(TaskRow(task.id, task.archiveId, 1, appJson.encodeToString(task))), cache = emptyList())
    }
}
