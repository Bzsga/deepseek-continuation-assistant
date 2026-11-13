package app.banzhan.deepseekcontinuation.core

internal data class ContinuityRenderedFragment(
    val text: String,
    val excerpted: Boolean,
    val representedMessageIds: Set<String>,
)

/** Sources are already scoped by the caller; this renderer never invents or rewrites quoted text. */
internal fun renderContinuityFragment(
    fragment: ContinuityFragment,
    maxCharacters: Int,
): ContinuityRenderedFragment? {
    if (maxCharacters <= 0) return null
    if (fragment.messages.none { it.content.isNotBlank() || it.attachments.any { attachment -> attachment.isNotBlank() } }) return null
    val title = continuityExcerptLabel(fragment.title)
    val sourceLength = fragment.messages.sumOf { message ->
        message.content.length.toLong() + message.attachments.sumOf { it.length.toLong() }
    }
    // Do not allocate a second copy of a very long exchange just to discover it cannot fit.
    if (sourceLength <= maxCharacters) {
        val full = buildString {
            append("\n【原文片段 · $title】\n")
            for (message in fragment.messages) {
                append(continuityRoleHeader(message))
                append(quoteContinuityText(message.content))
                if (message.attachments.isNotEmpty()) {
                    append("> 附件元数据（未读取内容）：\n")
                    message.attachments.forEach { append(quoteContinuityText(it)) }
                }
            }
        }
        if (full.length <= maxCharacters) return ContinuityRenderedFragment(full, false,
            fragment.messages.filter { message ->
                message.content.isNotBlank() || message.attachments.any { it.isNotBlank() }
            }.map { it.id }.toSet())
    }

    val chatIndices = fragment.messages.indices.filter { index ->
        fragment.messages[index].role.lowercase() in setOf("user", "assistant") &&
            fragment.messages[index].content.isNotBlank()
    }
    val primary = listOfNotNull(
        chatIndices.lastOrNull { fragment.messages[it].role.equals("user", ignoreCase = true) },
        chatIndices.lastOrNull { fragment.messages[it].role.equals("assistant", ignoreCase = true) },
    ).distinct()
    if (primary.isEmpty()) return null
    val intro = buildString {
        append("\n【原文节选 · $title】\n")
        append("〔节选说明：按句末或换段边界保留首尾原文；省略标记不是原话。〕\n")
        if (fragment.messages.any { it.attachments.isNotEmpty() }) {
            append("〔节选说明：附件元数据未带入，附件内容未读取。〕\n")
        }
    }
    fun compose(parts: Map<Int, ContinuityMessageExcerpt>): String = buildString {
        append(intro)
        var next = 0
        parts.toSortedMap().forEach { (index, part) ->
            if (index > next) append("〔节选说明：此处省略 ${index - next} 条消息〕\n")
            append(part.text)
            next = index + 1
        }
        if (next < fragment.messages.size) {
            append("〔节选说明：此处省略 ${fragment.messages.size - next} 条消息〕\n")
        }
    }

    val placeholder = ContinuityMessageExcerpt("", 0)
    val parts = linkedMapOf<Int, ContinuityMessageExcerpt>()
    val sharedBudget = (maxCharacters - compose(primary.associateWith { placeholder }).length) / primary.size
    primary.forEach { index ->
        excerptContinuityMessage(fragment.messages[index], sharedBudget)?.let { parts[index] = it }
    }
    // Reuse unused space, prioritizing the latest question and answer before earlier chat messages.
    for (index in primary + chatIndices.asReversed().filterNot { it in primary }) {
        val available = maxCharacters - compose(parts + (index to placeholder)).length
        val candidate = excerptContinuityMessage(fragment.messages[index], available) ?: continue
        if (candidate.sourceCharacters >= (parts[index]?.sourceCharacters ?: 0)) parts[index] = candidate
    }
    if (parts.isEmpty()) return null
    val text = compose(parts)
    if (text.length > maxCharacters) return null
    return ContinuityRenderedFragment(text, true, parts.keys.map { fragment.messages[it].id }.toSet())
}

private data class ContinuityMessageExcerpt(val text: String, val sourceCharacters: Int)

private fun excerptContinuityMessage(message: ChatMessage, limit: Int): ContinuityMessageExcerpt? {
    val content = message.content
    val header = continuityRoleHeader(message)
    if (limit <= header.length || content.isBlank()) return null
    if (content.length <= limit - header.length) {
        val full = header + quoteContinuityText(content)
        if (full.length <= limit) return ContinuityMessageExcerpt(full, content.length)
    }
    val boundaries = continuityNaturalBoundaries(content, limit)
    val firstContent = content.indexOfFirst { !it.isWhitespace() }
    val lastContent = content.indexOfLast { !it.isWhitespace() }
    val heads = boundaries.filter { it > firstContent && it < content.length && it <= limit }
    val tails = boundaries.filter { it > 0 && it <= lastContent && content.length - it <= limit }.asReversed()
    fun render(head: Int, tail: Int): ContinuityMessageExcerpt? {
        if (head >= tail || head + content.length - tail > limit - header.length) return null
        val text = buildString {
            append(header)
            if (head == 0) append("〔节选说明：省略开头部分原文〕\n")
            else append(quoteContinuityText(content.substring(0, head)))
            if (head > 0 && tail < content.length) append("〔节选说明：省略中间部分原文〕\n")
            if (tail < content.length) append(quoteContinuityText(content.substring(tail)))
            else append("〔节选说明：省略末尾部分原文〕\n")
        }
        return if (text.length <= limit) ContinuityMessageExcerpt(text, head + content.length - tail) else null
    }

    var head = 0
    var tail = content.length
    var result: ContinuityMessageExcerpt? = null
    if (heads.isNotEmpty() && tails.isNotEmpty()) {
        render(heads.first(), tails.first())?.let { head = heads.first(); tail = tails.first(); result = it }
    }
    if (result == null && tails.isNotEmpty()) {
        render(0, tails.first())?.let { tail = tails.first(); result = it }
    }
    if (result == null && heads.isNotEmpty()) {
        render(heads.first(), content.length)?.let { head = heads.first(); result = it }
    }
    if (result == null) return null
    var growHead = true
    var growTail = true
    while (growHead || growTail) {
        val tryHead = growHead && (!growTail || head <= content.length - tail)
        val boundary = if (tryHead) heads.firstOrNull { it > head && it < tail }
            else tails.firstOrNull { it < tail && it > head }
        val expanded = boundary?.let { if (tryHead) render(it, tail) else render(head, it) }
        if (expanded == null) {
            if (tryHead) growHead = false else growTail = false
        } else {
            if (tryHead) head = boundary else tail = boundary
            result = expanded
        }
    }
    return result
}

/** Keep only boundaries within a possible output window, even for very large input messages. */
private fun continuityNaturalBoundaries(text: String, window: Int): List<Int> {
    val boundaries = mutableListOf<Int>()
    val closing = "\"'”’）)]】」』"
    var index = 0
    while (index < text.length) {
        val character = text[index]
        val sentenceEnd = character in "。！？!?" || (character == '.' &&
            (index + 1 == text.length || text[index + 1].isWhitespace() || text[index + 1] in closing))
        if (sentenceEnd || character == '\n' || character == '\r') {
            index++
            if (sentenceEnd) {
                while (index < text.length && (text[index] in "。！？!?." || text[index] in closing)) index++
            }
            while (index < text.length && text[index].isWhitespace()) index++
            if (index <= window || text.length - index <= window) boundaries += index
        } else index++
    }
    return boundaries
}

private fun quoteContinuityText(text: String): String = buildString {
    text.lineSequence().forEach { append("> $it\n") }
}

private fun continuityRoleHeader(message: ChatMessage): String {
    val role = when (message.role.lowercase()) {
        "user" -> "我的原话"
        "assistant" -> "你当时的原话"
        "system" -> "旧对话系统消息（引用）"
        "tool" -> "旧对话工具消息（引用）"
        else -> "原角色 ${continuityExcerptLabel(message.role)}（引用）"
    }
    return "\n> 【$role】\n"
}

private fun continuityExcerptLabel(value: String): String {
    val singleLine = value.replace('\n', ' ').replace('\r', ' ')
    var end = minOf(80, singleLine.length)
    if (end > 0 && end < singleLine.length && singleLine[end - 1].isHighSurrogate() &&
        singleLine[end].isLowSurrogate()) end--
    return singleLine.substring(0, end).ifBlank { "未命名" }
}
