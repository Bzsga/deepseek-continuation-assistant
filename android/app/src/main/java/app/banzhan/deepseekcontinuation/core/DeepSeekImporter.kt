package app.banzhan.deepseekcontinuation.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Imports text and metadata only. Attachments are never extracted or fetched. */
object DeepSeekImporter {
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 512
    private const val MAX_NODES = 100_000
    private const val MAX_BRANCHES = 256
    private const val MAX_PATH_NODES = 500_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(bytes: ByteArray, fileName: String): ImportResult {
        require(bytes.isNotEmpty()) { "导入文件为空。请选择 DeepSeek 导出的 JSON 或 ZIP。" }
        require(bytes.size <= MAX_INPUT_BYTES) { "导入文件超过 32 MiB，请拆分后导入。" }
        val warnings = linkedSetOf<String>()
        val looksLikeZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
        val conversations = linkedMapOf<String, ImportedConversation>()
        val fingerprints = mutableMapOf<String, String>()
        fun consume(conversation: JsonObject) {
            val result = parseConversation(conversation, warnings)
            val fingerprint = canonicalDigest(conversation)
            val previous = fingerprints[result.id]
            require(previous == null || previous == fingerprint) {
                "发现 ID 相同、内容不同的会话（${result.title}），无法安全合并。"
            }
            if (previous != null) warnings += "已忽略重复会话「${result.title}」。"
            fingerprints[result.id] = fingerprint
            conversations[result.id] = result
        }
        if (fileName.endsWith(".zip", true) || looksLikeZip) {
            readZip(bytes, warnings, ::consume)
        } else {
            require(fileName.endsWith(".json", true)) { "仅支持 DeepSeek JSON 或 ZIP 导出文件。" }
            readJson(ByteArrayInputStream(bytes), fileName, warnings, ::consume)
        }
        require(conversations.isNotEmpty()) { "未找到可导入的聊天内容。需要含明确角色的 mapping、messages 或会话数组。" }
        return ImportResult(conversations.values.toList(), warnings.toList())
    }

    private fun readZip(bytes: ByteArray, warnings: MutableSet<String>, consume: (JsonObject) -> Unit) {
        var documents = 0
        val names = mutableSetOf<String>()
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= MAX_ZIP_ENTRIES) { "ZIP 内文件过多，最多支持 $MAX_ZIP_ENTRIES 项。" }
                    val name = entry.name.replace('\\', '/')
                    require(name.isNotBlank() && !name.startsWith('/') && !name.contains(':') &&
                        name.split('/').none { it == ".." || it == "." } && name.none { it.code < 32 }) {
                        "ZIP 包含不安全路径，已停止导入。"
                    }
                    require(names.add(name.lowercase(Locale.ROOT))) { "ZIP 包含重复路径，已停止导入。" }
                    require(entry.size <= MAX_INPUT_BYTES) { "ZIP 内单个文件超过 32 MiB。" }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val isJson = name.endsWith(".json", true)
                    val buffer = ByteArray(8192)
                    var entryBytes = 0
                    val bounded = object : InputStream() {
                        override fun read(): Int {
                            val byte = ByteArray(1)
                            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
                        }
                        override fun read(target: ByteArray, offset: Int, length: Int): Int {
                            val count = zip.read(target, offset, length)
                            if (count > 0) {
                                entryBytes += count
                                total += count
                                require(entryBytes <= MAX_INPUT_BYTES && total <= MAX_UNCOMPRESSED_BYTES) {
                                    "ZIP 解压内容超过安全上限（单文件 32 MiB、总计 64 MiB）。"
                                }
                            }
                            return count
                        }
                    }
                    if (isJson) {
                        readJson(bounded, name, warnings, consume)
                        documents++
                    } else {
                        while (bounded.read(buffer) >= 0) { /* Bound ignored attachment bytes too. */ }
                        warnings += "ZIP 中的非 JSON 文件未导入，附件正文未读取。"
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("ZIP 无法读取，可能已损坏、加密或压缩格式不受支持。", error)
        }
        require(documents > 0) { "ZIP 中没有 JSON 会话文件。" }
    }

    private fun readJson(input: InputStream, fileName: String, warnings: MutableSet<String>, consume: (JsonObject) -> Unit) {
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val possibleMessages = mutableListOf<JsonObject>()
            var conversationArray = false
            var found = false
            JsonDocumentReader(InputStreamReader(input, decoder)).read { text, arrayItem ->
                validateStructure(text)
                val value = json.parseToJsonElement(text)
                if (!arrayItem) {
                    conversationObjects(value).forEach { found = true; consume(it) }
                } else {
                    val item = value as? JsonObject ?: throw IllegalArgumentException("会话数组包含非对象项。")
                    if (!conversationArray && item.hasMessageShape()) possibleMessages += item
                    else {
                        require(item.hasConversationShape() && possibleMessages.all { it.hasConversationShape() }) {
                            "会话数组含无法识别的对象，未丢弃这些项。"
                        }
                        possibleMessages.forEach { found = true; consume(it) }
                        possibleMessages.clear()
                        conversationArray = true
                        found = true
                        consume(item)
                    }
                }
            }
            if (possibleMessages.isNotEmpty()) {
                consume(JsonObject(mapOf("messages" to JsonArray(possibleMessages))))
                found = true
            }
            if (!found) warnings += "$fileName 中未找到受支持的会话结构。"
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$fileName 无法导入。${error.message ?: "JSON 格式不正确。"}", error)
        } catch (error: Exception) {
            throw IllegalArgumentException("$fileName 不是有效的 UTF-8 JSON 文件。", error)
        }
    }

    private fun conversationObjects(source: JsonElement): List<JsonObject> = when (source) {
        is JsonArray -> {
            val objects = source.mapNotNull { it as? JsonObject }
            require(objects.size == source.size) { "会话数组包含非对象项。" }
            if (objects.isNotEmpty() && objects.all { it.hasMessageShape() }) {
                listOf(JsonObject(mapOf("messages" to source)))
            } else {
                require(objects.all { it.hasConversationShape() }) { "会话数组含无法识别的对象，未丢弃这些项。" }
                objects
            }
        }
        is JsonObject -> when {
            source.hasConversationShape() -> listOf(source)
            source["conversations"] is JsonArray -> conversationObjects(source.getValue("conversations"))
            source["chats"] is JsonArray -> conversationObjects(source.getValue("chats"))
            source["data"] is JsonArray || source["data"] is JsonObject -> conversationObjects(source.getValue("data"))
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun JsonObject.hasConversationShape(): Boolean =
        this["mapping"] is JsonObject || this["messages"] is JsonArray ||
            this["nodes"] is JsonArray || this["tree"] is JsonObject

    private fun JsonObject.hasMessageShape(): Boolean =
        containsKey("content") && listOf("role", "author", "sender", "type").any { containsKey(it) }

    private fun parseConversation(source: JsonObject, warnings: MutableSet<String>): ImportedConversation {
        val rawId = source.value("id", "conversation_id", "chat_session_id", "uuid")
        val id = "conversation-${if (rawId != null) digest(rawId) else canonicalDigest(source)}"
        val title = source.value("title", "name")?.takeIf { it.isNotBlank() } ?: "未命名会话"
        if (rawId == null) warnings += "部分会话缺少原始 ID，已按内容生成稳定标识；源内容变化时会作为新会话导入。"
        val current = source.value("current_node", "currentNode", "current_message_id", "currentMessageId", "leaf_id")
        val branches = when {
            source["mapping"] is JsonObject -> parseTree(id, title, source.getValue("mapping") as JsonObject, current, warnings)
            source["nodes"] is JsonArray -> parseTree(id, title, nodesToMapping(source.getValue("nodes") as JsonArray), current, warnings)
            source["tree"] is JsonObject -> parseTree(id, title, nestedTree(source.getValue("tree") as JsonObject), current, warnings)
            else -> parseMessages(id, title, source["messages"] as? JsonArray ?: JsonArray(emptyList()), current, warnings)
        }
        require(branches.any { it.messages.isNotEmpty() }) {
            "会话「$title」没有可识别的文本或附件元数据。请确认选择了聊天 JSON。"
        }
        return ImportedConversation(
            id = id,
            title = title,
            createdAt = source.value("inserted_at", "created_at", "create_time", "createdAt") ?: "",
            updatedAt = source.value("updated_at", "update_time", "updatedAt") ?: "",
            branches = branches,
            currentBranchId = branches.singleOrNull { it.isCurrent }?.id,
        )
    }

    private fun nodesToMapping(array: JsonArray): JsonObject {
        require(array.size <= MAX_NODES) { "会话节点过多。" }
        val nodes = linkedMapOf<String, JsonElement>()
        array.forEach { item ->
            val node = item as? JsonObject ?: throw IllegalArgumentException("会话节点必须是对象。")
            val id = node.value("id", "message_id", "uuid") ?: throw IllegalArgumentException("树节点缺少 ID。")
            require(nodes.put(id, node) == null) { "会话中出现重复节点 ID。" }
        }
        return JsonObject(nodes)
    }

    private fun nestedTree(root: JsonObject): JsonObject {
        val nodes = linkedMapOf<String, JsonElement>()
        val pending = ArrayDeque<Pair<JsonObject, String?>>()
        pending.add(root to null)
        while (pending.isNotEmpty()) {
            val (node, parent) = pending.removeFirst()
            val id = node.value("id", "message_id", "uuid") ?: throw IllegalArgumentException("树节点缺少 ID。")
            val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
            val childIds = children.map { child ->
                val objectChild = child as? JsonObject ?: throw IllegalArgumentException("嵌套树的 children 必须是节点对象。")
                val childId = objectChild.value("id", "message_id", "uuid") ?: throw IllegalArgumentException("树节点缺少 ID。")
                pending.add(objectChild to id)
                JsonPrimitive(childId)
            }
            val normalized = node.toMutableMap()
            normalized["children"] = JsonArray(childIds)
            normalized["parent"] = parent?.let(::JsonPrimitive) ?: JsonNull
            require(nodes.put(id, JsonObject(normalized)) == null) { "嵌套树存在重复节点 ID。" }
            require(nodes.size + pending.size <= MAX_NODES) { "会话节点过多。" }
        }
        return JsonObject(nodes)
    }

    private fun parseMessages(
        conversationId: String,
        title: String,
        array: JsonArray,
        current: String?,
        warnings: MutableSet<String>,
    ): List<ConversationBranch> {
        require(array.size <= MAX_NODES) { "会话消息过多。" }
        val objects = array.map { it as? JsonObject ?: throw IllegalArgumentException("消息必须是对象。") }
        if (objects.any { it.containsKey("parent") || it.containsKey("parent_id") || it.containsKey("children") }) {
            return parseTree(conversationId, title, nodesToMapping(array), current, warnings)
        }
        val seen = mutableSetOf<String>()
        val contentOccurrences = mutableMapOf<String, Int>()
        val indexedMessages = objects.mapIndexedNotNull { index, message ->
            val explicitId = message.value("id", "message_id", "uuid")
            val key = if (explicitId != null) {
                require(seen.add(explicitId)) { "会话「$title」存在重复消息 ID。" }
                explicitId
            } else {
                val hash = canonicalDigest(message)
                val occurrence = contentOccurrences.getOrDefault(hash, 0)
                contentOccurrences[hash] = occurrence + 1
                "generated-$hash-$occurrence"
            }
            parseMessage(message, conversationId, key, title, warnings)?.let { index to it }
        }
        val messages = indexedMessages.map { it.second }
        val branchId = "$conversationId:branch:linear"
        val currentIndex = objects.indexOfFirst { it.value("id", "message_id", "uuid") == current }
        if (current != null && currentIndex < 0) {
            warnings += "会话「$title」的当前节点标记无效，已保留线性消息顺序。"
            return listOf(ConversationBranch(branchId, "线性分支", messages, false))
        }
        if (current != null && currentIndex < objects.lastIndex) {
            val prefix = indexedMessages.filter { it.first <= currentIndex }.map { it.second }
            return listOf(
                ConversationBranch("$conversationId:branch:${digest(current)}", "当前分支", prefix, true),
                ConversationBranch(branchId, "完整线性分支", messages, false),
            )
        }
        return listOf(ConversationBranch(branchId, "当前分支", messages, true))
    }

    private fun parseTree(
        conversationId: String,
        title: String,
        mapping: JsonObject,
        current: String?,
        warnings: MutableSet<String>,
    ): List<ConversationBranch> {
        require(mapping.size <= MAX_NODES) { "会话节点过多。" }
        val nodes = mapping.mapValues { (_, value) -> value as? JsonObject ?: throw IllegalArgumentException("mapping 节点必须是对象。") }
        val parents = mutableMapOf<String, String>()
        val children = nodes.keys.associateWith { linkedSetOf<String>() }
        val explicitIds = mutableSetOf<String>()
        nodes.forEach { (id, node) ->
            node.value("id")?.let { declared ->
                require(explicitIds.add(declared)) { "会话「$title」存在重复节点 ID。" }
                require(declared == id) { "会话「$title」的节点 ID 与 mapping 键不一致。" }
            }
            val parent = node.value("parent", "parent_id")
            if (parent != null) {
                if (parent in nodes) link(parent, id, parents, children)
                else warnings += "会话「$title」存在缺失父节点，相关内容保留为独立分支。"
            }
            val childList = node["children"]
            require(childList == null || childList is JsonNull || childList is JsonArray) { "children 必须是节点 ID 数组。" }
            (childList as? JsonArray)?.forEach { child ->
                val childId = (child as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("children 包含无效节点 ID。")
                if (childId in nodes) link(id, childId, parents, children)
                else warnings += "会话「$title」存在缺失子节点引用，已保留可读内容。"
            }
        }
        validateAcyclic(nodes.keys, children)
        val parsed = nodes.mapValues { (key, node) ->
            val raw = node["message"]
            when {
                raw is JsonObject -> parseMessage(raw, conversationId, key, title, warnings, node)
                raw != null && raw !is JsonNull -> throw IllegalArgumentException("message 必须是对象或 null。")
                node.containsKey("content") || node.containsKey("fragments") -> parseMessage(node, conversationId, key, title, warnings)
                else -> null
            }
        }
        val leaves = nodes.keys.filter { children.getValue(it).isEmpty() }.toMutableList()
        val validCurrent = current?.takeIf { it in nodes }
        if (current != null && validCurrent == null) warnings += "会话「$title」的当前节点不存在，请手动选择分支。"
        if (validCurrent != null && validCurrent !in leaves) leaves.add(0, validCurrent)
        require(leaves.size <= MAX_BRANCHES) { "会话分支超过 $MAX_BRANCHES 条，请拆分导出。" }
        var pathNodes = 0
        val paths = leaves.mapNotNull { leaf ->
            val path = mutableListOf<ChatMessage>()
            var cursor: String? = leaf
            while (cursor != null) {
                pathNodes += 1
                require(pathNodes <= MAX_PATH_NODES) { "会话分支展开过大，请拆分导出。" }
                parsed[cursor]?.let { path += it }
                cursor = parents[cursor]
            }
            if (path.isEmpty()) null else leaf to path.asReversed()
        }
        val selected = validCurrent ?: paths.singleOrNull()?.first.takeIf { current == null }
        if (selected == null && current == null && paths.size > 1) warnings += "会话「$title」包含多个分支，源文件未标记当前分支，请手动选择。"
        return paths.mapIndexed { index, (leaf, messages) ->
            ConversationBranch(
                id = "$conversationId:branch:${digest(leaf)}",
                label = if (leaf == selected) "当前分支" else "分支 ${index + 1}",
                messages = messages,
                isCurrent = leaf == selected,
            )
        }
    }

    private fun link(parent: String, child: String, parents: MutableMap<String, String>, children: Map<String, MutableSet<String>>) {
        val previous = parents.putIfAbsent(child, parent)
        require(previous == null || previous == parent) { "同一节点存在多个父节点，无法安全确定分支。" }
        children.getValue(parent).add(child)
    }

    private fun validateAcyclic(ids: Set<String>, children: Map<String, Set<String>>) {
        val color = mutableMapOf<String, Int>()
        ids.forEach { start ->
            if (color[start] == 2) return@forEach
            val pending = ArrayDeque<Pair<String, Boolean>>()
            pending.add(start to false)
            while (pending.isNotEmpty()) {
                val (id, exiting) = pending.removeLast()
                if (exiting) {
                    color[id] = 2
                    continue
                }
                require(color[id] != 1) { "会话节点存在循环引用，已停止导入。" }
                if (color[id] == 2) continue
                color[id] = 1
                pending.add(id to true)
                children.getValue(id).reversed().forEach { pending.add(it to false) }
            }
        }
    }

    private fun parseMessage(
        message: JsonObject,
        conversationId: String,
        key: String,
        title: String,
        warnings: MutableSet<String>,
        node: JsonObject = message,
    ): ChatMessage? {
        val role = messageRole(message, node, title, warnings)
        val attachments = mutableListOf<String>()
        listOf("attachments", "files").forEach { field ->
            (message[field] as? JsonArray)?.forEach { attachment ->
                attachmentMetadata(attachment)?.let { attachments += it }
            }
        }
        val metadata = message["metadata"] as? JsonObject
        (metadata?.get("attachments") as? JsonArray)?.forEach { attachment ->
            attachmentMetadata(attachment)?.let { attachments += it }
        }
        val body = message["fragments"]?.takeUnless { it is JsonNull } ?: message["content"]
        val content = body?.let { readContent(it, attachments, warnings) } ?: ""
        if (message.containsKey("reasoning_content") || message.containsKey("reasoning") || message.containsKey("analysis")) {
            warnings += "已排除推理过程字段，仅导入对话正文。"
        }
        if (content.isBlank() && attachments.isEmpty()) return null
        if (attachments.isNotEmpty()) warnings += "附件仅保留描述和来源元数据，附件正文未纳入续聊内容。"
        return ChatMessage(
            id = "$conversationId:message:${digest(key)}",
            role = role,
            content = content,
            timestamp = message.value("inserted_at", "created_at", "create_time", "timestamp", "createdAt") ?: "",
            attachments = attachments.distinct(),
        )
    }

    private fun messageRole(message: JsonObject, node: JsonObject, title: String, warnings: MutableSet<String>): String {
        val author = message["author"] as? JsonObject
        val explicit = (message.value("role", "sender") ?: author?.value("role") ?: message.value("author", "type")
            ?: node.value("role", "sender"))?.lowercase(Locale.ROOT)
        val role = when (explicit) {
            "request" -> "user"
            "response" -> "assistant"
            else -> explicit
        }
        // DeepSeek official exports express the speaker through REQUEST/RESPONSE fragments.
        val fragmentRoles = (message["fragments"] as? JsonArray).orEmpty().mapNotNull {
            when ((it as? JsonObject)?.value("type")?.uppercase(Locale.ROOT)) {
                "REQUEST" -> "user"
                "RESPONSE" -> "assistant"
                else -> null
            }
        }.toSet()
        val supported = role?.takeIf { it in setOf("user", "assistant", "system", "tool", "developer") }
        if (fragmentRoles.size > 1 || (supported != null && fragmentRoles.any { it != supported })) {
            warnings += "会话「$title」有角色标记相互冲突的消息，已保留为 unknown，请核对原文。"
            return "unknown"
        }
        return supported ?: fragmentRoles.singleOrNull() ?: "unknown".also {
            warnings += "会话「$title」有无法确定角色的消息，已保留为 unknown；不会按消息位置猜测角色。"
        }
    }

    private fun readContent(value: JsonElement, attachments: MutableList<String>, warnings: MutableSet<String>): String = when (value) {
        is JsonNull -> ""
        is JsonPrimitive -> if (value.isString) value.content else {
            warnings += "已跳过非文本正文值。"
            ""
        }
        is JsonArray -> value.map { readContent(it, attachments, warnings) }.filter { it.isNotEmpty() }.joinToString("\n")
        is JsonObject -> {
            val type = value.value("content_type", "type")?.lowercase(Locale.ROOT)
            when {
                type in setOf("thinking", "think", "reasoning", "reasoning_content", "analysis") -> {
                    warnings += "已排除推理过程字段，仅导入对话正文。"
                    ""
                }
                type in setOf("image", "image_url", "input_image", "file", "audio", "video") -> {
                    val files = value["files"] as? JsonArray
                    if (files != null) files.forEach { file -> attachmentMetadata(file)?.let { attachments += it } }
                    else attachmentMetadata(value)?.let { attachments += it }
                    ""
                }
                type == null || type in setOf("text", "input_text", "output_text", "request", "response", "multimodal_text") -> {
                    val text = value["parts"] ?: value["text"] ?: value["content"]
                    if (text == null) {
                        warnings += "已跳过无法识别的正文对象。"
                        ""
                    } else readContent(text, attachments, warnings)
                }
                else -> {
                    warnings += "已跳过不受支持的正文类型「$type」。"
                    ""
                }
            }
        }
    }

    private fun attachmentMetadata(value: JsonElement): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it.length <= 4096 && !it.startsWith("data:", true) }
        is JsonObject -> {
            val allowed = setOf("id", "file_id", "name", "file_name", "filename", "mime_type", "mimeType", "size", "type", "url", "asset_pointer")
            val fields = value.filter { (key, item) ->
                key in allowed && item is JsonPrimitive && (item.contentOrNull?.length ?: 0) <= 4096 &&
                    item.contentOrNull?.startsWith("data:", true) != true
            }.toMutableMap()
            (value["image_url"] as? JsonObject)?.value("url")?.takeIf { it.length <= 4096 && !it.startsWith("data:", true) }
                ?.let { fields["url"] = JsonPrimitive(it) }
            if (fields.isEmpty()) "附件（源文件未提供可读元数据）" else JsonObject(fields).toString()
        }
        else -> null
    }

    private fun JsonObject.value(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull
    }

    /** Same canonical bytes as earlier versions, without retaining another complete transcript. */
    private fun canonicalDigest(value: JsonElement): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun append(text: String) { hash.update(text.toByteArray(Charsets.UTF_8)) }
        fun visit(item: JsonElement) {
            when (item) {
                is JsonObject -> {
                    append("{")
                    item.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
                        if (index > 0) append(",")
                        append(JsonPrimitive(entry.key).toString())
                        append(":")
                        visit(entry.value)
                    }
                    append("}")
                }
                is JsonArray -> {
                    append("[")
                    item.forEachIndexed { index, child ->
                        if (index > 0) append(",")
                        visit(child)
                    }
                    append("]")
                }
                else -> append(item.toString())
            }
        }
        visit(value)
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** kotlinx.serialization accepts repeated keys. Reject them before parsing so nodes cannot disappear silently. */
    private fun validateStructure(text: String) {
        data class Container(val objectKeys: MutableSet<String>?, var expectsKey: Boolean)
        val stack = ArrayDeque<Container>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    val isObject = text[index] == '{'
                    stack.addLast(Container(if (isObject) mutableSetOf() else null, isObject))
                    require(stack.size <= 128) { "JSON 嵌套超过 128 层。" }
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
                ':' -> stack.lastOrNull()?.expectsKey = false
                ',' -> stack.lastOrNull()?.let { it.expectsKey = it.objectKeys != null }
                '"' -> {
                    val start = index
                    index += 1
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index += 1
                        index += 1
                    }
                    require(index < text.length) { "JSON 字符串未闭合。" }
                    val current = stack.lastOrNull()
                    if (current?.expectsKey == true) {
                        val key = (json.parseToJsonElement(text.substring(start, index + 1)) as JsonPrimitive).content
                        require(current.objectKeys!!.add(key)) { "JSON 存在重复字段「${key.take(80)}」。" }
                    }
                }
            }
            index += 1
        }
    }
}
