@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ChatMessage
import app.banzhan.deepseekcontinuation.core.ConversationBranch
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

@Serializable
private data class StoredConversation(
    val storageVersion: Int = 0,
    val id: String,
    val title: String,
    val createdAt: String = "",
    val updatedAt: String = "",
    val messageIndex: List<ChatMessage>? = null,
    val branches: List<StoredBranch>,
    val currentBranchId: String? = null,
)

@Serializable
private data class StoredBranch(
    val id: String,
    val label: String,
    val messageIndices: List<Int>? = null,
    // Version 0 is the pre-1.0.1 payload. Read it without changing existing IDs.
    val messages: List<ChatMessage>? = null,
    val isCurrent: Boolean = false,
)

/** Persist each message body once, even when hundreds of branches share the same long prefix. */
internal object ConversationStorage {
    fun write(conversation: ImportedConversation, output: OutputStream) {
        require(conversation.branches.map { it.id }.distinct().size == conversation.branches.size &&
            (conversation.currentBranchId == null || conversation.branches.any { it.id == conversation.currentBranchId })) {
            "会话分支编号或当前分支无效。"
        }
        val indices = linkedMapOf<String, Int>()
        val messages = mutableListOf<ChatMessage>()
        val branches = conversation.branches.map { branch ->
            val references = branch.messages.map { message ->
                indices[message.id]?.also { index ->
                    require(messages[index] == message) { "同一会话消息编号对应不同内容，无法保存。" }
                } ?: messages.size.also { index ->
                    indices[message.id] = index
                    messages += message
                }
            }
            require(references.distinct().size == references.size) { "会话分支重复引用了同一消息。" }
            StoredBranch(branch.id, branch.label, messageIndices = references, isCurrent = branch.isCurrent)
        }
        appJson.encodeToStream(StoredConversation.serializer(), StoredConversation(
            1, conversation.id, conversation.title, conversation.createdAt, conversation.updatedAt,
            messages, branches, conversation.currentBranchId), output)
    }

    fun read(input: InputStream): ImportedConversation = decode(appJson.decodeFromStream(input))
    fun read(payload: String): ImportedConversation = decode(appJson.decodeFromString(payload))

    private fun decode(stored: StoredConversation): ImportedConversation {
        require(stored.storageVersion in 0..1) { "不支持此会话存储版本。" }
        val indexed = stored.messageIndex
        require((stored.storageVersion == 1) == (indexed != null)) { "会话消息索引不完整。" }
        val unique = mutableMapOf<String, ChatMessage>()
        indexed?.forEach { message ->
            require(unique.put(message.id, message) == null) { "会话消息索引存在重复编号。" }
        }
        val referenced = BooleanArray(indexed?.size ?: 0)
        if (indexed != null) require(stored.branches.map { it.id }.distinct().size == stored.branches.size &&
            (stored.currentBranchId == null || stored.branches.any { it.id == stored.currentBranchId })) {
            "会话分支编号或当前分支无效。"
        }
        val branches = stored.branches.map { branch ->
            val messages = if (indexed != null) {
                require(branch.messages == null && branch.messageIndices != null) { "会话分支索引格式无效。" }
                require(branch.messageIndices.distinct().size == branch.messageIndices.size) { "会话分支重复引用了同一消息。" }
                branch.messageIndices.map { index ->
                    require(index in indexed.indices) { "会话分支引用了缺失的消息。" }
                    referenced[index] = true
                    indexed[index]
                }
            } else {
                require(branch.messageIndices == null && branch.messages != null) { "旧版会话分支内容不完整。" }
                branch.messages.map { message ->
                    val existing = unique.putIfAbsent(message.id, message)
                    require(existing == null || existing == message) { "同一会话消息编号对应不同内容。" }
                    existing ?: message
                }
            }
            ConversationBranch(branch.id, branch.label, messages, branch.isCurrent)
        }
        require(referenced.all { it }) { "会话消息索引包含未归属任何分支的消息。" }
        return ImportedConversation(stored.id, stored.title, stored.createdAt, stored.updatedAt,
            branches, stored.currentBranchId)
    }
}

/** Preserve legacy hashes without constructing the serialized value as one enormous String. */
internal fun <T> serializedHash(serializer: SerializationStrategy<T>, value: T): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val discard = object : OutputStream() {
        override fun write(value: Int) {}
        override fun write(bytes: ByteArray, offset: Int, length: Int) {}
    }
    DigestOutputStream(discard, digest).use { appJson.encodeToStream(serializer, value, it) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** A bounded bridge between synchronous JSON codecs and suspending Room queries. */
internal suspend fun <T> withJsonWriter(
    write: (OutputStream) -> Unit, codecDispatcher: CoroutineDispatcher = Dispatchers.IO,
    read: suspend (InputStream) -> T,
): T {
    val caller = currentCoroutineContext()
    return coroutineScope {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        val writer = async(codecDispatcher) { output.use { write(it) } }
        writer.invokeOnCompletion { output.close() }
        try {
            val result = input.use { read(it) }
            writer.await()
            result
        } catch (error: Throwable) {
            caller.ensureActive()
            throw error
        } finally {
            input.close()
            output.close()
            writer.cancel()
        }
    }
}

internal suspend fun <T> withJsonReader(
    write: suspend (OutputStream) -> Unit, codecDispatcher: CoroutineDispatcher = Dispatchers.IO,
    read: (InputStream) -> T,
): T {
    val caller = currentCoroutineContext()
    return coroutineScope {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        // Room calls stay in the caller's transaction context. Only the pure codec
        // runs in the background, so a blocking pipe never occupies its query thread.
        val reader = async(codecDispatcher) { input.use { read(it) } }
        reader.invokeOnCompletion { input.close() }
        try {
            output.use { write(it) }
            reader.await()
        } catch (error: Throwable) {
            caller.ensureActive()
            throw error
        } finally {
            output.close()
            input.close()
            reader.cancel()
        }
    }
}
